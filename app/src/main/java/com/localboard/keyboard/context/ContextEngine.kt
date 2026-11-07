package com.localboard.keyboard.context

import android.content.Context
import androidx.room.withTransaction
import com.localboard.keyboard.data.db.AppContextEntity
import com.localboard.keyboard.data.db.AppDatabase
import com.localboard.keyboard.data.db.LegacyVerdictMigration
import com.localboard.keyboard.data.db.NGramEntity
import com.localboard.keyboard.data.db.NGramQueue
import com.localboard.keyboard.data.db.NGramRows
import com.localboard.keyboard.data.db.StyleStatEntity
import com.localboard.keyboard.data.db.applyBatch
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.correction.LegacyTypoVerdicts
import com.localboard.keyboard.engine.correction.RejectionMemory
import com.localboard.keyboard.llm.AiDiagLog
import com.localboard.keyboard.util.TypingDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The privacy context engine. Everything the keyboard learns flows through
 * here, gets batched in memory, and is persisted to the encrypted database.
 * Nothing learned here is sent anywhere. The app does hold the internet
 * permission, which IOKey uses to download the models the user chooses.
 */
class ContextEngine private constructor(
    context: Context,
    private val fastEngine: FastEngine
) {
    private val appContext = context.applicationContext
    // Asked for each time: the store can be replaced while this engine lives. See [startFresh].
    private val db get() = AppDatabase.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { com.localboard.keyboard.util.Prefs(appContext) }
    private val typingLog by lazy { TypingLog(appContext) }

    /** False when the store would not open on this device, which is also what a
     *  failed migration looks like. The keyboard keeps working in memory, what it
     *  would have written waits in the queues, and the open is tried again: see
     *  [retryStoreIfDue]. Nothing crashes. A write that fails later does not set
     *  this: it is retried. */
    @Volatile
    var persistenceAvailable = true
        private set

    private val health = StorageHealth(remembered = PrefsStoreFailureMemory(appContext))

    /** What is wrong with the store, in plain words, or null when nothing is. */
    val storageProblem: String? get() = health.problem()

    /** What the person can do about a store that will not open. */
    val storeAdvice: StoreAdvice get() = health.advice()

    /** What the keyboard has to tell the person about the store, once. See [storeNoticeShown]. */
    fun storeNoticeDue(): StoreAdvice? = health.noticeDue()

    fun storeNoticeShown() = health.noticeShown()

    private val storedRows = StoredRowGuard(
        setOf(
            CONFIRMED_WORDS_KEY, REMOVED_WORDS_KEY, PROFILE_KEY,
            LegacyTypoVerdicts.SUPPRESSIONS_KEY, LegacyTypoVerdicts.STRIKES_KEY
        )
    )

    /** False while memory holds only what was typed since this start, because the store would not
     *  open or its rows would not load. A word leaving that memory, or that memory not knowing a
     *  word, says nothing about the rows the store holds for it. Touching only what this process
     *  queued is safe then. Deleting a word's rows is not, unless the person asked for it. */
    @Volatile
    private var modelHasReadTheTable = false

    /** Words taken off the bar with Remove, which stay out of the dictionary too. A typo
     *  verdict goes in the same blacklist but leaves a shipped word where it is. */
    private val removedWords = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Bumped under [writeLock] by every erase. See [unlessErasedSince]. */
    @Volatile
    var eraseCount = 0
        private set

    /** Records a failed store operation and arranges another try. A cancelled
     *  caller is not a broken store: this engine outlives the screen that called
     *  it, so reading a cancellation as a failure would stop saving for nothing. */
    private fun storageFailed(t: Throwable) {
        if (t is CancellationException) throw t
        val firstInARow = health.failures == 0
        health.writeFailed(t)
        android.util.Log.w("ContextEngine", "store failed: ${health.lastError}")
        if (firstInARow) reportToDiagnostics(STORAGE_DIAG, health.problem())
        scheduleRetry()
    }

    /** The first open of this process failed, which is what counts towards offering a way out. */
    private fun storeWouldNotOpenAtStart(t: Throwable) {
        if (t is CancellationException) throw t
        persistenceAvailable = false
        health.startFailed(t, storageIsShort(), AppDatabase.libraryMissing)
        android.util.Log.e("ContextEngine", "store would not open", t)
        reportToDiagnostics(STORAGE_DIAG, health.problem())
    }

    /** A later try failed too. Nothing new to report, and these keep coming. */
    private fun storeStillWouldNotOpen(t: Throwable) {
        if (t is CancellationException) throw t
        persistenceAvailable = false
        health.openFailed(t, storageIsShort(), AppDatabase.libraryMissing)
        android.util.Log.w("ContextEngine", "store still would not open: ${StorageHealth.describe(t)}")
    }

    /** A full disk does not always say so: not every way of failing to open one comes back as "full". */
    private fun storageIsShort(): Boolean = appContext.filesDir.usableSpace < LOW_STORAGE_BYTES

    /** Held by whatever may open or move the store, so that a retry can't cross a start fresh or an erase. */
    private val storeOpenLock = Mutex()

    init {
        // Every [AppDatabase.get] tries while there is no store, so anyone can be the one who gets
        // it open. Without this it would sit there healthy while writes wait and Settings offers
        // to move it.
        AppDatabase.onStoreOpened = {
            if (!persistenceAvailable && isInitialized) scope.launch { tryStoreAgain() }
        }
    }

    /**
     * For the keyboard to call when it shows or hides, off whose thread this does its work. Those are
     * the moments it is known to be alive, and the process often lives long enough for a passing
     * failure to pass. A try may move aside a store that reads as corrupt, the same as a start would.
     */
    fun retryStoreIfDue() {
        if (persistenceAvailable || !isInitialized) return
        if (AppDatabase.isOpenOnDisk() || health.takeOpenRetry()) scope.launch { tryStoreAgain() }
    }

    private suspend fun tryStoreAgain() {
        storeOpenLock.withLock { tryStoreAgainLocked() }
    }

    /** True when the store is open after this, with what it holds in memory and what waited for it in the store. */
    private suspend fun tryStoreAgainLocked(): Boolean {
        if (persistenceAvailable) return true
        try {
            openForUse(AppDatabase.tryAgain(appContext))
        } catch (t: Throwable) {
            storeStillWouldNotOpen(t)
            return false
        }
        dropWhatIsNoLongerAllowed()
        health.storeOpened()
        persistenceAvailable = true
        reportToDiagnostics(STORAGE_DIAG, "Saved words opened again.")
        readStore(merging = true)
        return true
    }

    /** All of this was allowed when it was typed, but it can have waited for days. */
    private fun dropWhatIsNoLongerAllowed() {
        if (!prefs.learningEnabled) {
            pendingNgrams.clear()
            synchronized(pendingLogLines) { pendingLogLines.clear() }
            synchronized(appVocabDirty) { appVocabDirty.clear() }
            return
        }
        synchronized(pendingLogLines) {
            pendingLogLines.removeAll { !prefs.typingLogEnabled || !prefs.mayLearnIn(it.appPackage) }
        }
        synchronized(appVocabDirty) {
            appVocabDirty.keys.removeAll { !prefs.mayLearnIn(it.substringBefore('|')) }
        }
    }

    /** The first touch of the file is what runs a pending Room migration. */
    private fun openForUse(store: AppDatabase) {
        store.openHelper.writableDatabase
        if (store.openHelper.databaseName == null) {
            throw IllegalStateException("only a temporary store in memory could be opened")
        }
    }

    /** Learning that stops with no sign of why reads as the keyboard being dumb, so a
     *  store problem goes where a bug report comes from: Settings, Advanced. */
    private fun reportToDiagnostics(what: String, text: String?) {
        AiDiagLog.add(
            AiDiagLog.Entry(
                at = System.currentTimeMillis(), model = "", purpose = what,
                promptTokens = 0, reusedTokens = 0, promptMs = 0, genTokens = 0, genMs = 0,
                finish = -1, note = text.orEmpty()
            )
        )
    }

    private var retryJob: Job? = null

    /** Keeps trying until a write works, at the pace [health] sets. Anything still
     *  in a failed batch was put back, and flushing it here means it lands even if
     *  no one types again. A store that would not open has [retryStoreIfDue], and a
     *  flush here could never clear that failure. */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (persistenceAvailable && health.failures > 0) {
                delay(health.msUntilRetry().coerceAtLeast(FLUSH_DELAY_MS))
                flushPending()
            }
        }
    }

    @Volatile
    var profile: StyleProfile = StyleProfile.EMPTY
        private set

    /** Nightly-distilled "who this person is" notes (from the typing log). */
    @Volatile
    var personalityNotes: String = ""
        private set

    /** Nightly-counted "what this person talks about, and with whom".
     *  [personalityNotes] covers HOW they write; this covers the subject
     *  matter and the conversations, which is most of what makes a draft
     *  sound informed rather than generic. Counted, never summarised. */
    @Volatile
    var personaNotes: String = ""
        private set

    /** True when the user blocked context saving for this app. */
    fun isBlocked(packageName: String): Boolean = prefs.isAppBlocked(packageName)

    /** P0: the editor-hint thread key ("hint:John") for the app the IME is
     *  currently in — writes used it, reads never did, so screen-blind apps
     *  (Facebook, Instagram) lost all per-thread recall. The service sets it
     *  on every input-view start. */
    @Volatile
    private var hintThreadKey: String = ""

    fun setHintThreadKey(key: String) {
        hintThreadKey = key
    }

    /** The effective conversation key for [pkg]: accessibility title when
     *  the screen stream sees one, the editor's own hint otherwise. */
    private fun threadKeyFor(pkg: String): String =
        ScreenBuffer.currentScreenKey(pkg).ifBlank { hintThreadKey }

    // Rolling style refinement
    private val refinement = StyleRefinementEngine()

    // Pending n-gram changes, flushed to DB in batches.
    private val pendingNgrams = NGramQueue()
    // Committed text awaiting the encrypted typing log (text, app, screen, origin).
    private val pendingLogLines = ArrayList<TypingLog.PendingLine>()
    // Usage-stat deltas accumulated in memory between flushes.
    private val usageDeltas = HashMap<String, Long>()
    private var flushJob: Job? = null

    // In-memory per-app vocab, persisted periodically.
    private val appVocabDirty = newestKept<HashMap<String, Int>>(MAX_QUEUED_VOCAB_BUCKETS)
    // Style-stat rows awaiting the next flush; a later put to a key replaces an earlier one.
    private val pendingStyleStats = HashMap<String, String>()

    /** Keeps a wipe from interleaving with a batched write to the same tables. */
    private val writeLock = Mutex()

    @Volatile
    var isInitialized = false
        private set

    /** Held for the whole first load. The keyboard and any settings screen can
     *  ask for it at the same moment, and a second run would reload the model
     *  the first one just filled. */
    private val initLock = Mutex()

    private class EarlyLearn(
        val word: String,
        val prev1: String,
        val prev2: String,
        val appPackage: String,
        val selected: Boolean
    )

    /** Words typed before the first load finished, learned when it does. Touched on
     *  the main thread only, like the service that calls [learnWord]. */
    private val earlyLearns = ArrayDeque<EarlyLearn>()

    private val dictionaryReady = CompletableDeferred<Unit>()
    private val firstLoadDone = CompletableDeferred<Unit>()

    /** Returns once the bundled dictionary is in, or has failed to load. */
    suspend fun awaitDictionary() = dictionaryReady.await()

    /** Returns once the first load is over, with the learned words in if the store gave them up. */
    suspend fun awaitFirstLoad() = firstLoadDone.await()

    // ------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------

    suspend fun initialize() {
        if (isInitialized) return
        initLock.withLock {
            if (isInitialized) return
            // The first caller may be a screen. Closing it must not abort a load
            // the keyboard relies on too.
            withContext(Dispatchers.IO + NonCancellable) { runFirstLoad() }
        }
    }

    private suspend fun runFirstLoad() {
        try {
            fastEngine.loadBaseDictionary()
            dictionaryReady.complete(Unit)
            // The bigram model rides behind the dictionary on the same IO
            // pass: ~160k entries, a few hundred ms, and every consumer
            // treats "not loaded yet" as context-blind rather than broken.
            fastEngine.loadBaseLanguageModel()
        } catch (t: Throwable) {
            // The bundled files, not the store: what was learned can still load.
            android.util.Log.e("ContextEngine", "dictionary failed to load", t)
            reportToDiagnostics("dictionary", StorageHealth.describe(t))
        }
        dictionaryReady.complete(Unit)
        loadStore()
        // Everything that waited for the load runs at the moment it ends, with
        // nothing able to get in between.
        withContext(Dispatchers.Main) {
            isInitialized = true
            while (earlyLearns.isNotEmpty()) {
                val e = earlyLearns.removeFirst()
                learnNow(e.word, e.prev1, e.prev2, e.appPackage, e.selected)
            }
        }
        firstLoadDone.complete(Unit)
    }

    private suspend fun loadStore() {
        try {
            openForUse(db)
        } catch (t: Throwable) {
            storeWouldNotOpenAtStart(t)
            return
        }
        health.storeOpened()
        readStore(merging = false)
    }

    /**
     * Everything the store holds goes into memory, with whatever memory took in while the store
     * was closed kept alongside it. With [merging] that has to be saved as well: the queues go to
     * the store first, since the model load replaces what memory holds with what the table holds.
     */
    private suspend fun readStore(merging: Boolean) {
        AppDatabase.movedAsideBecause?.let {
            reportToDiagnostics(
                STORAGE_DIAG,
                "Saved words could not be read (${StorageHealth.describe(it)}), so the keyboard " +
                    "moved them aside and started learning again. It did not delete them."
            )
        }
        try {
            // A failure here is retried at the next start, because its marker is written in
            // the same transaction. The model can still load in the meantime.
            db.withTransaction { LegacyVerdictMigration.runOnce(db.ngramDao(), db.styleStatDao()) }
        } catch (t: Throwable) {
            storageFailed(t)
        }
        try {
            // The n-gram table was append-only: drop stale one-off entries
            // (untouched for 90 days, count <= 1) before they get rehydrated.
            db.ngramDao().prune(System.currentTimeMillis() - NGRAM_PRUNE_AGE_MS)
        } catch (t: Throwable) {
            // Only tidying, and no reason to go without what the rows below hold.
            storageFailed(t)
        }
        if (merging) flushPending()
        try {
            val stats = db.styleStatDao().all().associate { it.key to it.value }

            // Typo verdicts must be restored BEFORE the model load — injectPersonal
            // consults the suppression set, and suppressed words must not rehydrate
            // into the unigram map.
            val suppressedRaw = stats[LegacyTypoVerdicts.SUPPRESSIONS_KEY]
                ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            val strikeEntries = stats[LegacyTypoVerdicts.STRIKES_KEY]
                ?.split(',')?.filter { it.isNotBlank() }
                ?.mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
                }?.toMap() ?: emptyMap()
            val confirmedRaw = stats[CONFIRMED_WORDS_KEY]
                ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            val removedRaw = stats[REMOVED_WORDS_KEY]
                ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            if (merging) settleVerdictsGivenUnread(confirmedRaw)

            val liveSuppressed = withContext(Dispatchers.Main) {
                // At the first load memory holds no verdicts. After a store that was closed it
                // holds those given meanwhile, which never reached a row: see [StoredRowGuard].
                val suppressedAll = suppressedRaw + fastEngine.suppressionsSnapshotDated()
                val removedAll = removedRaw + removedWords
                fastEngine.restoreSuppressions(suppressedAll.toSet())
                fastEngine.restoreStrikes(strikeEntries + fastEngine.strikesSnapshot())
                fastEngine.restoreConfirmed(confirmedRaw.toSet() + fastEngine.confirmedWordsSnapshot())
                val live = suppressedAll
                    .map { it.substringBefore('@').lowercase() }
                    .filter { fastEngine.isSuppressed(it) }
                    .toSet()
                // restoreSuppressions only re-arms the blacklist, which is all a
                // typo verdict asks for: the rows are kept out of the load below,
                // and a shipped word stays in the dictionary as noteTypo left it.
                // A word the user chose to remove leaves the trie as well.
                // removeWordEverywhere re-stamps the verdict with "now", so
                // restore once more afterwards to keep the ORIGINAL stamps
                // (otherwise the 30-day TTL would never expire anything).
                removedWords.clear()
                removedAll.filterTo(removedWords) { it in live }
                removedWords.forEach { fastEngine.removeWordEverywhere(it) }
                if (removedWords.isNotEmpty()) {
                    fastEngine.restoreSuppressions(suppressedAll.toSet())
                }
                // The profile comes before the model, so that the model failing to
                // load can't cost it: the rows saved whole are all in memory from here.
                // Whichever has seen more of this person stays, which at a first load is
                // always the stored one, and after a closed store nearly always.
                stats[PROFILE_KEY]?.let {
                    val stored = StyleProfile.fromJson(it)
                    if (stored.sentencesAnalyzed >= profile.sentencesAnalyzed) {
                        profile = stored
                        refinement.restore(stored)
                        applyStyleBoosts(stored.toBoostMap())
                    }
                }
                personalityNotes = stats["personality_notes"] ?: personalityNotes
                personaNotes = stats["persona_notes"] ?: personaNotes
                live
            }
            storedRows.loadRead()
            // Saves of these rows were refused until now, so what memory added has yet to go in.
            if (merging) withContext(Dispatchers.Main) { saveWhatMemoryHolds() }
            // A strike is only a note, so a struck word loads like any other.
            loadPersonalModel(liveSuppressed)

            // The typing log is a rolling window, not an archive.
            typingLog.prune(days = 7)
        } catch (t: Throwable) {
            if (t !is CancellationException) health.loadFailed(t)
            storageFailed(t)
        }
    }

    /**
     * A word struck down twice while memory had not read the table may only have looked like a typo
     * because memory could not see it was the person's own. Now that can be seen. A word they
     * confirmed, or use so much that the keyboard would have known it, keeps its rows and loses the
     * verdict. Any other goes the way it would have gone with the store open.
     */
    private suspend fun settleVerdictsGivenUnread(storedConfirmed: List<String>) {
        // Words the person removed are not in question, and queued their own deletions.
        val given = fastEngine.suppressionsSnapshot() - removedWords
        if (given.isEmpty()) return
        val wellUsed = db.ngramDao().topUnigrams(FastEngine.MAX_PERSONAL_UNIGRAMS)
            .filter { it.count >= FastEngine.STRONG_USAGE_THRESHOLD }.map { it.word }
        val theirOwn = given.intersect((storedConfirmed + wellUsed).toSet())
        withContext(Dispatchers.Main) {
            theirOwn.forEach { fastEngine.clearTypoVerdict(it) }
            (given - theirOwn).forEach { deleteStoredWord(it) }
        }
    }

    /**
     * The one way stored rows reach the engine, up to the caps the engine itself keeps, so
     * that a reload does not cut off what memory would have held. The load runs on the thread
     * that runs every correction walk and prediction: it inserts into the trie, and none of
     * them can then be walking it. That is also the cold start's keyboard opening, so it stays
     * off the main thread.
     */
    private suspend fun loadPersonalModel(blocked: Set<String>) {
        val ngrams = db.ngramDao()
        val unigrams = ngrams.topUnigrams(FastEngine.MAX_PERSONAL_UNIGRAMS)
            .filter { it.word !in blocked }.map { it.word to it.count }
        val bigrams = ngrams.topBigrams(FastEngine.MAX_PERSONAL_BIGRAMS)
            .filter { it.word !in blocked }.map { Triple(it.prev1, it.word, it.count) }
        val trigrams = ngrams.topTrigrams(FastEngine.MAX_PERSONAL_TRIGRAMS)
            .filter { it.word !in blocked }.map { Triple(it.prev2 to it.prev1, it.word, it.count) }
        withContext(TypingDispatcher.io) {
            fastEngine.loadUserModel(unigrams, bigrams, trigrams)
        }
        modelHasReadTheTable = true
    }

    /** A few recent real messages by this person, most recent last — the
     *  voice examples the drafting prompt shows the model. Prefer messages
     *  from this app (same conversation register), fall back to global.
     *  Cleaned: no URLs, no one-word fragments, incognito-excluded. */
    suspend fun voiceExamples(packageName: String?, count: Int = 3): List<String> {
        if (packageName != null && isBlocked(packageName)) return emptyList()
        fun clean(s: String): String? {
            val t = s.trim()
            if (t.length !in 6..90) return null
            if (t.contains("http") || t.contains("www.")) return null
            if (t.count { it == ' ' } < 1) return null
            if (!t.any { it.isLetter() }) return null
            return t
        }
        // B8: this conversation's messages first (the way the user talks to
        // THIS person), then the app's, then global.
        val screen = if (packageName != null) threadKeyFor(packageName) else ""
        val inThread = if (packageName != null && screen.isNotEmpty()) {
            try {
                // Off the caller's thread, which can be the keyboard's main one. The
                // first touch of [db] may be what opens the store and migrates it.
                withContext(Dispatchers.IO) {
                    db.typingLogDao().recentForAppAndScreen(packageName, screen, 30)
                }.map { it.text }.mapNotNull(::clean)
            } catch (t: Throwable) {
                storageFailed(t)
                emptyList()
            }
        } else emptyList()
        val inApp = if (packageName != null)
            typingLog.recentForApp(packageName, 30).mapNotNull(::clean)
        else emptyList()
        val global = typingLog.since(0L, 60).map { it.text }.mapNotNull(::clean)
        // ORDERING IS LOAD-BEARING — this was inverted on both axes until 1.6.8.
        // The three lists are in PRIORITY order (this thread, then this app,
        // then anywhere), and every underlying query is `ORDER BY ts DESC`, so
        // each list is newest-first. `takeLast(count)` therefore returned the
        // OLDEST entries of the LOWEST-priority list: the model was shown stale
        // messages from unrelated apps, and in-thread examples were used only
        // when the global log held fewer than `count` clean rows — i.e. never,
        // after the first day of typing. That is the "drafts don't sound like
        // this conversation" bug, and it also put other chats' text in the
        // prompt for no reason.
        //
        // take() keeps priority and recency; reversed() then puts the most
        // recent last, which is the order the prompt block wants.
        return (inThread + inApp + global).distinct().take(count).reversed()
    }

    /** What the user recently typed in [packageName], oldest→newest, joined.
     *  Fallback for editors that won't share their on-screen text. */
    suspend fun recentTypedContext(packageName: String, maxChars: Int = 500): String {
        if (isBlocked(packageName)) return ""
        // Prefer what was typed in THIS conversation (the typing log is
        // tagged per screen when the capture knows the screen title); fall
        // back to app-wide when the screen is new or unknown.
        val screen = threadKeyFor(packageName)
        if (screen.isNotEmpty()) {
            val byScreen = try {
                // See voiceExamples: this can be the keyboard's main thread.
                withContext(Dispatchers.IO) {
                    db.typingLogDao().recentForAppAndScreen(packageName, screen, 40)
                }
            } catch (t: Throwable) {
                storageFailed(t)
                emptyList()
            }
            if (byScreen.isNotEmpty()) {
                val joined = byScreen.asReversed().joinToString(" ") { it.text }.takeLast(maxChars)
                if (joined.length >= 60) return joined
            }
        }
        // 40 fragments ≈ the last several messages, not just the last dozen
        // words — the AI drafts noticeably better with the whole recent
        // exchange instead of a sliver of it.
        val frags = typingLog.recentForApp(packageName, 40)
        if (frags.isEmpty()) return ""
        return frags.asReversed().joinToString(" ").takeLast(maxChars)
    }

    /** Persist new nightly-distilled personality notes. */
    suspend fun setPersonalityNotes(notes: String) {
        personalityNotes = notes
        if (!persistenceAvailable) {
            putStyleStat("personality_notes", notes)
            return
        }
        try {
            db.styleStatDao().putAll(listOf(StyleStatEntity("personality_notes", notes)))
        } catch (t: Throwable) {
            // The night's work is not repeated until the next night, so it gets retried.
            putStyleStat("personality_notes", notes)
            storageFailed(t)
        }
    }

    /** The stored value of the style_stats row [key], or null when it has none or
     *  the store can't be read. Sees a value just handed to [putStyleStat]. */
    suspend fun styleStat(key: String): String? = writeLock.withLock {
        synchronized(pendingStyleStats) { pendingStyleStats[key] }
            ?: withContext(Dispatchers.IO) {
                if (!persistenceAvailable) {
                    // Whoever asked now holds none of what is stored, and the store may come back.
                    storedRows.readFailed(key)
                    return@withContext null
                }
                try {
                    db.styleStatDao().get(key).also { storedRows.readWorked(key) }
                } catch (t: Throwable) {
                    storedRows.readFailed(key)
                    storageFailed(t)
                    null
                }
            }
    }

    /** Stores [value] in the style_stats row [key] with the next batched flush,
     *  so a burst of snapshots costs one write and the last one wins. Safe to
     *  call on the main thread. The value waits while the store is closed, and is
     *  dropped when it would replace a row that could not be read: see [StoredRowGuard]. */
    fun putStyleStat(key: String, value: String) {
        if (!storedRows.maySave(key)) return
        synchronized(pendingStyleStats) { pendingStyleStats[key] = value }
        ensureFlushScheduled()
    }

    /**
     * Runs [write] unless an erase has come since [eraseCountBefore], read from [eraseCount] before
     * reading whatever [write] saves. For slow work outside this class, such as the nightly run,
     * whose results would otherwise bring erased data back. Under the lock an erase also takes,
     * so the write has either landed and gets wiped, or never happens.
     */
    suspend fun unlessErasedSince(eraseCountBefore: Int, write: suspend (AppDatabase) -> Unit) {
        writeLock.withLock {
            if (eraseCount == eraseCountBefore) write(db)
        }
    }

    /** The last 48h of typing-log text for the nightly distiller. */
    suspend fun typingLogSnapshot(sinceMs: Long, limit: Int = 400) =
        typingLog.since(sinceMs, limit)

    /** Wipe everything stored for one app (used when it joins the blocklist).
     *  Not tied to the caller's lifetime: once asked for, it finishes. The store
     *  is tried even when [persistenceAvailable] is false, since one failed write
     *  sets that with all the data still on disk.
     *
     *  The copy set aside goes too. It holds this app's rows, and nothing can use it.
     *
     *  @return true only when the store on disk was reached and this app's rows
     *  are gone, and every listener finished. False means data from this app may
     *  still be on this device, and the caller has to say so. */
    suspend fun eraseAppData(packageName: String): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            ScreenBuffer.clear(packageName)  // RAM-only screen captures, if any
            withContext(Dispatchers.Main) { fastEngine.setAppVocab(packageName, emptyMap()) }
            val storeWiped = writeLock.withLock {
                // Under the lock, so a flush has either finished, and its rows get wiped,
                // or finds nothing of this app's left to write after the wipe.
                synchronized(pendingLogLines) {
                    pendingLogLines.removeAll { it.appPackage == packageName }
                }
                synchronized(appVocabDirty) {
                    appVocabDirty.keys.removeAll {
                        it == packageName || it.startsWith("$packageName|")
                    }
                }
                val rowsGone = try {
                    db.appContextDao().deleteByPackageAndThreads(packageName)
                    // Not typingLog.clearApp(), which swallows the failure this has to report.
                    db.typingLogDao().clearApp(packageName)
                    // The in-memory fallback has no file name. Emptying it says nothing
                    // about the file on disk that could not be opened.
                    db.openHelper.databaseName != null
                } catch (t: Throwable) {
                    storageFailed(t)
                    false
                }
                // After the store, since opening it can be what sets it aside.
                AppDatabase.deleteSetAsideCopy(appContext) && rowsGone
            }
            notifyErased(packageName) && storeWiped
        }

    // ------------------------------------------------------------------
    // Learning
    // ------------------------------------------------------------------

    /**
     * Learn one committed word with its immediate context.
     * Called on the main thread; engine-map writes are main-confined.
     */
    /** Top learned words by usage count (for the style/dictionary viewer). */
    suspend fun topLearnedWords(limit: Int = 120): List<NGramEntity> = withContext(Dispatchers.IO) {
        if (!persistenceAvailable) emptyList()
        else try {
            db.ngramDao().topUnigrams(limit)
        } catch (t: Throwable) {
            storageFailed(t)
            emptyList()
        }
    }

    /** Learned vocabulary as (word, count) for [com.localboard.keyboard.util.BackupManager].
     *  Words only — no n-gram context, no message text, nothing that could
     *  reconstruct a conversation from a backup file. */
    suspend fun learnedWordsForBackup(limit: Int = 4000): List<Pair<String, Int>> =
        withContext(Dispatchers.IO) {
            if (!persistenceAvailable) emptyList()
            else try {
                db.ngramDao().topUnigrams(limit).map { it.word to it.count }
            } catch (t: Throwable) {
                storageFailed(t)
                emptyList()
            }
        }

    /** Remove a word from the learned model everywhere, then hot-reload the engine. */
    suspend fun deleteLearnedWord(word: String) = withContext(Dispatchers.IO) {
        val w = word.lowercase().trim()
        forgetRefusal(w)
        if (!persistenceAvailable) {
            // Waits with the rest, or the word would come back with the store.
            deleteStoredWord(w)
            return@withContext
        }
        try {
            deleteStoredWord(w)
            // The reload replaces what memory holds with what the table holds, so
            // everything still on its way there has to land first.
            flushPending()
            loadPersonalModel(fastEngine.suppressionsSnapshot() + w)
        } catch (t: Throwable) {
            storageFailed(t)
        }
    }

    /** Holds what the user refused about single words outside this class, and has to hear when one goes. */
    fun interface WordForgottenListener {
        /** Runs on the main thread. */
        fun onWordForgotten(word: String)
    }

    private val wordForgottenListeners = java.util.concurrent.CopyOnWriteArraySet<WordForgottenListener>()

    fun addWordForgottenListener(listener: WordForgottenListener) {
        wordForgottenListeners.add(listener)
    }

    /** This engine can outlive whatever registered, so owners with a shorter life must take themselves off. */
    fun removeWordForgottenListener(listener: WordForgottenListener) {
        wordForgottenListeners.remove(listener)
    }

    /** A word the user removed must not stay refused. A running keyboard holds the refusals and writes
     *  them back itself. With none running, the stored row is the only copy. */
    private suspend fun forgetRefusal(w: String) {
        val told = withContext(Dispatchers.Main) {
            wordForgottenListeners.forEach { it.onWordForgotten(w) }
            wordForgottenListeners.isNotEmpty()
        }
        if (told) return
        val row = styleStat(RejectionMemory.STYLE_STATS_KEY) ?: return
        val memory = RejectionMemory(System::currentTimeMillis).also { it.restore(row) }
        memory.forget(w)
        if (memory.dirty) putStyleStat(RejectionMemory.STYLE_STATS_KEY, memory.serialize())
    }

    fun learnWord(
        word: String,
        prev1: String,
        prev2: String,
        appPackage: String,
        selected: Boolean = false
    ) {
        if (isBlocked(appPackage) || !prefs.learningEnabled) return
        val w = word.lowercase().trim()
        if (w.length < 2 || w.any { it.isDigit() }) return
        if (!isInitialized) {
            // Typed while the first load runs. Dropping these is what makes the first
            // words after a restart count for nothing.
            if (earlyLearns.size < EARLY_LEARN_LIMIT) {
                earlyLearns.addLast(EarlyLearn(w, prev1, prev2, appPackage, selected))
            }
            return
        }
        learnNow(w, prev1, prev2, appPackage, selected)
    }

    private fun learnNow(
        w: String,
        prev1: String,
        prev2: String,
        appPackage: String,
        selected: Boolean
    ) {
        // A learn can lift an old strike or typo suppression: persist the new
        // snapshot so the word stays re-added after a restart.
        val hadStrike = fastEngine.hasStrike(w)
        val wasSuppressed = fastEngine.isSuppressed(w)
        val counted = fastEngine.learn(w, prev1, prev2, selected)
        if ((wasSuppressed && !fastEngine.isSuppressed(w)) || (hadStrike && !fastEngine.hasStrike(w))) {
            persistSuppressions()
        }
        if (counted) {
            pendingNgrams.addLearned(
                prev1.lowercase(), prev2.lowercase(), w,
                unigramDelta = if (selected) FastEngine.SELECTION_BOOST else 1
            )
        }
        trackAppVocab(appPackage, w)
        incrementUsage("words_learned_total", 1L)
    }

    /** Explicit user intent ("yes, this is my word": a prediction-bar tap).
     *  Undoing a correction does not come here: it protects the word through
     *  RejectionMemory and confirms nothing. Confirmed words are immune from
     *  autocorrect and promote into the trie below the high-usage threshold;
     *  persisted so the immunity survives restarts. */
    fun noteConfirmed(word: String) {
        // Before the first load the memory holds no verdicts yet, and saving that
        // empty snapshot would overwrite the ones on disk for good.
        if (!isInitialized) return
        if (!prefs.learningEnabled || word.length < 2) return
        fastEngine.noteConfirmed(word.lowercase())
        persistConfirmed()
    }

    /** The confirmation of [word] turned out to be a slip: take it back in memory
     *  and on disk. See [FastEngine.unconfirm] for who may call this. */
    fun unconfirm(word: String) {
        if (!isInitialized) return  // See noteConfirmed.
        fastEngine.unconfirm(word)
        persistConfirmed()
    }

    /** The user said [word] is not a typo: lift any strike or blacklist entry in
     *  memory and on disk. */
    fun clearTypoVerdict(word: String) {
        if (!isInitialized) return  // See noteConfirmed.
        fastEngine.clearTypoVerdict(word)
        persistSuppressions()
    }

    // Verdicts travel with the batched flush, so a failed write is retried and a
    // snapshot can't land after an erase.
    private fun persistConfirmed() {
        putStyleStat(
            CONFIRMED_WORDS_KEY,
            fastEngine.confirmedWordsSnapshot().toList().takeLast(1000).joinToString(",")
        )
    }

    private fun persistSuppressions() {
        // Dated entries ("word@epochMs") so verdicts expire after 30 days.
        putStyleStat(
            LegacyTypoVerdicts.SUPPRESSIONS_KEY,
            fastEngine.suppressionsSnapshotDated().toList().takeLast(500).joinToString(",")
        )
        putStyleStat(
            LegacyTypoVerdicts.STRIKES_KEY,
            fastEngine.strikesSnapshot().entries.joinToString(",") { "${it.key}=${it.value}" }
        )
        // A word back off the blacklist is no longer removed, or a later typo
        // verdict on it would take it out of the dictionary at the next start.
        removedWords.retainAll { fastEngine.isSuppressed(it) }
        putStyleStat(REMOVED_WORDS_KEY, removedWords.joinToString(","))
    }

    /** The user accepted an autocorrection AWAY from [word]. The FIRST time
     *  this happens is only a note, and nothing stored changes, since a
     *  correction firing once proves nothing. The second makes it a confirmed
     *  typo. Nothing is recorded with learning off. */
    fun noteTypo(word: String, appPackage: String) {
        if (!isInitialized || isBlocked(appPackage) || !prefs.learningEnabled) return
        val w = word.lowercase().trim()
        if (w.length < 2) return
        fastEngine.noteTypo(w)
        if (fastEngine.isSuppressed(w)) {
            // Confirmed typo: every n-gram row goes, and the style engine
            // unlearns the word so its boost stops amplifying a typo.
            refinement.removeWord(w)
            // Memory that never read the table can't see that this is the person's own word. The
            // rows wait for [settleVerdictsGivenUnread], which can.
            if (modelHasReadTheTable) deleteStoredWord(w)
        }
        persistSuppressions()
    }

    /** [word]'s stored rows go with the next flush, which also retries it if the
     *  write fails. Going through the queue is what stops rows on their way to the
     *  table from landing after the delete and bringing the word back. */
    private fun deleteStoredWord(word: String) {
        pendingNgrams.deleteWord(word)
        ensureFlushScheduled()
    }

    /** Undo one learned occurrence of [word] in memory and on disk. See
     *  [FastEngine.unlearnRecent] for why this is not [removeWordEverywhere].
     *  [prev1] and [prev2] are the words it was learned after. */
    fun unlearnRecent(word: String, prev1: String = "", prev2: String = "") {
        val w = word.lowercase().trim()
        if (w.length < 2) return
        if (!isInitialized) {
            // Not learned yet: take the waiting occurrence back instead.
            val waiting = earlyLearns.indexOfLast { it.word == w }
            if (waiting >= 0) earlyLearns.removeAt(waiting)
            return
        }
        val p1 = prev1.lowercase()
        val p2 = prev2.lowercase()
        // A decrement is only true of a row the engine holds and takes one from,
        // so these are read before it does.
        val heldRows = NGramRows.rowsFor(p1, p2, w).filter { (rowPrev1, rowPrev2, _) ->
            when {
                rowPrev1.isEmpty() -> fastEngine.userFrequency(w) > 0
                rowPrev2.isEmpty() -> fastEngine.bigramRowLower(p1)?.containsKey(w) == true
                else -> fastEngine.trigramRowLower(p2, p1)?.containsKey(w) == true
            }
        }
        val emptied = fastEngine.unlearnRecent(w, p1, p2)
        if (emptied && modelHasReadTheTable) {
            // The word left the model, so its rows leave the table. Decrements would
            // keep the rows of every other context it was learned in.
            deleteStoredWord(w)
        } else if (emptied && !persistenceAvailable) {
            // Only memory has lost it. With the store closed, all it knew of the word is still in
            // the queue, and the months of rows behind that must be there when the store returns.
            pendingNgrams.forgetQueued(w)
        } else if (heldRows.isNotEmpty()) {
            pendingNgrams.takeBack(heldRows)
            ensureFlushScheduled()
        }
    }

    /** P2: the user long-pressed a suggestion and chose Remove. Banishes the
     *  word from the live engine AND its persisted n-gram rows, and marks
     *  it suppressed so predictions stop offering it. Explicit, reversible:
     *  tapping the word in the bar later lifts the suppression. */
    fun removeWordEverywhere(word: String) {
        if (!isInitialized) return
        val w = word.lowercase().trim()
        if (w.length < 2) return
        fastEngine.removeWordEverywhere(w)
        removedWords.add(w)
        refinement.removeWord(w)  // stop style boosts from amplifying it
        deleteStoredWord(w)
        persistSuppressions()
    }

    /** A full sentence/segment was committed: learn its n-grams and track
     *  per-app vocabulary. [skipNgramLearn] (AI-originated text): skip the
     *  n-gram learn/persist so the model never trains on its own output,
     *  while per-app vocabulary still tracks the words the user kept. */
    fun onTextCommitted(text: String, appPackage: String, skipNgramLearn: Boolean = false) {
        if (!isInitialized || isBlocked(appPackage) || !prefs.learningEnabled) return
        // Never learn from links: URLs tokenize into junk vocabulary
        // ("https", "youtu", "be", "watch") that then pollutes predictions,
        // swipe candidates and the AI style profile.
        val linkless = URL_RE.replace(text.lowercase(), " ")
        val words = WORD_RE.findAll(linkless).map { it.value }.toList()
        if (words.isNotEmpty()) {
            var prev1 = ""
            var prev2 = ""
            for (w in words) {
                if (w.length >= 2) {
                    if (!skipNgramLearn && fastEngine.learn(w, prev1, prev2)) {
                        pendingNgrams.addLearned(prev1, prev2, w)
                    }
                    trackAppVocab(appPackage, w)
                }
                prev2 = prev1
                prev1 = w
            }
            incrementUsage("words_learned_total", words.size.toLong())
        }
        // NOTE: the typing log is NOT fed here anymore. Per-word rows starved
        // every consumer (voice examples filter single words out; the distiller
        // analyzed word salad). Whole messages arrive via onMessageCommitted.
        // Style is NOT observed here either: this text is usually a single
        // already-lowercased committed word, which treated as a "sentence"
        // corrupted emaSentenceLength and capitalizationStyle and double-fed
        // the refinement counters. Real multi-word text reaches the style
        // engine via onSentenceCommitted / onMessageCommitted.
    }

    /** B3: log whole MESSAGES, not words. One row per sent message — tagged
     *  with the thread key — is the food voice examples, recent-context and
     *  the nightly distiller are all built for. Same batched, encrypted
     *  pipeline as before; only the granularity changed. */
    fun onMessageCommitted(
        text: String,
        appPackage: String,
        threadKey: String,
        aiOrigin: Boolean = false
    ) {
        if (!isInitialized || isBlocked(appPackage) || !prefs.learningEnabled) return
        val clean = text.trim()
        if (clean.length < 2) return
        // Whole messages are the style engine's real food: multi-word,
        // original-case text (the per-word path corrupted the sentence stats).
        // An accepted draft is the model's writing, and measuring it would feed
        // the model's habits back to it as this person's.
        if (!aiOrigin) refineStyle(clean)
        if (prefs.typingLogEnabled) {
            synchronized(pendingLogLines) {
                pendingLogLines += TypingLog.PendingLine(
                    clean.take(500), appPackage, threadKey,
                    origin = if (aiOrigin) 1 else 0
                )
                keepNewestLogLines()
            }
            ensureFlushScheduled()
        }
    }

    /** Style-only learning hook for sentence boundaries (no n-gram double counting). */
    fun onSentenceCommitted(text: String) {
        if (!isInitialized || !prefs.learningEnabled) return
        refineStyle(text)
    }

    private fun refineStyle(text: String) {
        val newProfile = refinement.observe(text, profile)
        if (newProfile !== profile) {
            profile = newProfile
            applyStyleBoosts(newProfile.toBoostMap())
            scheduleProfilePersist()
        }
    }

    /** setStyleBoosts evicts the ENTIRE prediction cache — only push when the
     *  boost map actually changed since the last push. */
    @Volatile
    private var lastStyleBoosts: Map<String, Float> = emptyMap()

    private fun applyStyleBoosts(boosts: Map<String, Float>) {
        if (boosts == lastStyleBoosts) return
        lastStyleBoosts = boosts
        fastEngine.setStyleBoosts(boosts)
    }

    /** Bulk import from onboarding (samples / freeform / SMS) and from chat
     *  exports (WhatsApp & co). [updateStyleProfile] is true for onboarding,
     *  where the corpus IS the style seed; chat imports pass false — they
     *  feed the word library and phrase model without overwriting a style
     *  profile the user may already be happy with. */
    suspend fun importCorpus(text: String, updateStyleProfile: Boolean = true) = withContext(Dispatchers.IO) {
        val result = StyleDistiller.distill(text)
        if (updateStyleProfile) {
            profile = result.profile
            applyStyleBoosts(result.profile.toBoostMap())
        }

        // Feed the fast engine in memory (main-thread confined maps).
        val unigramList = result.unigrams.toList()
        val bigramList = result.bigrams.entries.map {
            val (p, w) = it.key.split('|', limit = 2)
            Triple(p, w, it.value)
        }
        val trigramList = result.trigrams.entries.map {
            val parts = it.key.split('|')
            Triple(parts[0] to parts[1], parts[2], it.value)
        }
        withContext(Dispatchers.Main) {
            fastEngine.loadUserModelMerge(unigramList, bigramList, trigramList)
        }

        // Persist profile + n-grams (best-effort; in-memory model already works)
        if (persistenceAvailable) {
            try {
                val now = System.currentTimeMillis()
                // Added to what is stored, as the merge above added to what memory holds.
                // A restart reads these rows, so anything else is a count that drops then.
                // All of it or none: a kill part way must not leave half an import behind.
                db.withTransaction {
                    if (updateStyleProfile) {
                        db.styleStatDao().putAll(listOf(StyleStatEntity(PROFILE_KEY, result.profile.toJson())))
                    }
                    val ngrams = db.ngramDao()
                    unigramList.forEach { (w, c) -> ngrams.addCount("", "", w, c, now) }
                    bigramList.forEach { (p, w, c) -> ngrams.addCount(p, "", w, c, now) }
                    trigramList.forEach { (pp, w, c) -> ngrams.addCount(pp.second, pp.first, w, c, now) }
                }
            } catch (t: Throwable) {
                storageFailed(t)
            }
        }

        if (updateStyleProfile) {
            withContext(Dispatchers.Main) {
                refinement.restore(result.profile)
            }
        }
    }

    /** Import words from an external personal dictionary (the system user
     *  dictionary — where Gboard's "add to dictionary" puts them). Enters at
     *  promotion strength; words already known at that strength keep their
     *  higher counts (upsert is REPLACE-on-conflict, so they must not be
     *  rewritten lower). Returns the number of words newly learned. */
    suspend fun importExternalDictionary(words: List<String>): Int = withContext(Dispatchers.IO) {
        val clean = words.map { it.lowercase().trim() }
            .filter { it.length >= 2 && it.length <= 40 }
            .distinct()
        if (clean.isEmpty()) return@withContext 0
        var promoted = emptyList<String>()
        withContext(Dispatchers.Main) {
            promoted = fastEngine.importExternalWords(clean)
        }
        if (persistenceAvailable && promoted.isNotEmpty()) {
            try {
                val now = System.currentTimeMillis()
                promoted.map { NGramEntity("", "", it, 4, now) }
                    .chunked(500).forEach { db.ngramDao().upsertAll(it) }
                // External adds lift typo suppressions in memory — persist the
                // new snapshot so reinstated words survive restarts.
                persistSuppressions()
            } catch (t: Throwable) {
                storageFailed(t)
            }
        }
        promoted.size
    }

    /** Import (word, count) pairs from an external dictionary that tracks
     *  frequencies (e.g. a backup/restore of the learned model). Unlike
     *  [importExternalDictionary], existing HIGHER counts are preserved
     *  (MAX-on-conflict upsert) and the imported counts feed the engine
     *  proportionally. Returns the number of words imported. */
    suspend fun importExternalDictionaryWithCounts(words: List<Pair<String, Int>>): Int =
        withContext(Dispatchers.IO) {
            val clean = words.mapNotNull { (raw, c) ->
                val w = raw.lowercase().trim()
                if (w.length >= 2 && w.length <= 40 && c > 0 && w.none { it.isDigit() })
                    w to c else null
            }.distinctBy { it.first }
            if (clean.isEmpty()) return@withContext 0
            // injectPersonal caps trie frequency at count 50 — importing more
            // than that buys nothing, so cap at the same frequency scaling.
            val capped = clean.map { (w, c) -> w to c.coerceAtMost(TRIE_FREQ_COUNT_CAP) }
            withContext(Dispatchers.Main) {
                fastEngine.mergeRestoredCounts(capped)
            }
            if (persistenceAvailable) {
                try {
                    val now = System.currentTimeMillis()
                    db.withTransaction {
                        for ((w, c) in capped) {
                            db.ngramDao().upsertMax("", "", w, c, now)
                        }
                    }
                } catch (t: Throwable) {
                    storageFailed(t)
                }
            }
            capped.size
        }

    /** Load per-app vocabulary boosts when the user switches apps. B8: the
     *  current thread's bucket is merged in at double weight — this
     *  conversation's vocabulary outranks the app's average. */
    suspend fun loadAppContext(packageName: String) = withContext(Dispatchers.IO) {
        if (packageName.isEmpty() || !persistenceAvailable) return@withContext
        try {
            val merged = HashMap<String, Int>()
            db.appContextDao().get(packageName)?.let { entity ->
                parseTopWords(entity.topWordsJson).forEach { (w, c) -> merged.merge(w, c, Int::plus) }
            }
            val thread = ScreenBuffer.currentScreenKey(packageName)
            if (thread.isNotEmpty()) {
                db.appContextDao().get("$packageName|$thread")?.let { entity ->
                    parseTopWords(entity.topWordsJson)
                        .forEach { (w, c) -> merged.merge(w, c * 2, Int::plus) }
                }
            }
            if (merged.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    fastEngine.setAppVocab(packageName, merged)
                }
            }
        } catch (t: Throwable) {
            storageFailed(t)
        }
    }

    // ------------------------------------------------------------------
    // Stats / privacy dashboard / wipe
    // ------------------------------------------------------------------

    suspend fun dashboardStats(): Map<String, String> = withContext(Dispatchers.IO) {
        val rows = if (!persistenceAvailable) {
            mapOf(
                "Storage mode" to "In-memory only",
                "Learned words (this session)" to fastEngine.learnedWordCount.toString(),
                "Sentences analyzed for style" to profile.sentencesAnalyzed.toString(),
                "Network permissions held" to "0 (fully offline)"
            )
        } else try {
            val usage = db.usageStatDao().all().associate { it.key to it.value }
            mapOf(
                "Storage encryption" to if (AppDatabase.encryptionActive) "SQLCipher AES-256" else "Plain (SQLCipher unavailable)",
                "Learned words" to db.ngramDao().wordCount().toString(),
                "Word events processed" to (usage["words_learned_total"] ?: 0L).toString(),
                "Apps with context profiles" to db.appContextDao().count().toString(),
                "Sentences analyzed for style" to profile.sentencesAnalyzed.toString(),
                "Estimated formality" to "%.0f%%".format(profile.formality * 100),
                "Network usage" to "Model downloads only (user-initiated); typing never transmitted"
            )
        } catch (t: Throwable) {
            storageFailed(t)
            mapOf("Storage mode" to "Could not read the store")
        }
        val problem = storageProblem ?: return@withContext rows
        rows + ("Storage problem" to problem)
    }

    /** Holds a copy of learned data outside this class. Without this it would
     *  write that copy back over an erase the next time it persisted. */
    fun interface EraseListener {
        /** Runs on the main thread once the store has been wiped or the wipe has
         *  failed, because memory has to be dropped either way. [packageName] is
         *  the one app erased, or null for everything. The erase waits for this,
         *  so slow work belongs on another dispatcher, and a throw makes
         *  [eraseAllLearnedData] report that it did not finish. */
        suspend fun onErased(packageName: String?)
    }

    private val eraseListeners = java.util.concurrent.CopyOnWriteArraySet<EraseListener>()

    fun addEraseListener(listener: EraseListener) {
        eraseListeners.add(listener)
    }

    /** This engine can outlive whatever registered, so owners with a shorter
     *  life must take themselves off. */
    fun removeEraseListener(listener: EraseListener) {
        eraseListeners.remove(listener)
    }

    /** False when a listener failed. The others still run. */
    private suspend fun notifyErased(packageName: String?): Boolean =
        withContext(Dispatchers.Main) {
            var allFinished = true
            for (listener in eraseListeners) {
                try {
                    listener.onErased(packageName)
                } catch (t: Throwable) {
                    allFinished = false
                    android.util.Log.w(
                        "ContextEngine", "erase listener failed: ${t.javaClass.simpleName}"
                    )
                }
            }
            allFinished
        }

    /** Wipes everything learned, on disk and in memory, then tells the
     *  [EraseListener]s. Not tied to the caller's lifetime: once asked for, it
     *  finishes. The store is tried even when [persistenceAvailable] is false,
     *  since one failed write sets that with all the data still on disk.
     *
     *  @return true only when the store on disk was reached and emptied and every
     *  listener finished. False means personal data may still be on this device,
     *  and the caller has to say so. */
    suspend fun eraseAllLearnedData(): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        // A first load still running would put the old rows back in memory.
        initialize()
        withContext(Dispatchers.Main) {
            profile = StyleProfile.EMPTY
            personalityNotes = ""
            personaNotes = ""
            refinement.restore(profile)
            applyStyleBoosts(emptyMap())
            earlyLearns.clear()
            // The last prompt quotes what the user typed.
            AiDiagLog.lastPrompt = ""
            AiDiagLog.lastPromptPurpose = ""
            AiDiagLog.lastPromptAt = 0L
        }
        withContext(TypingDispatcher.io) { fastEngine.forgetEverythingLearned() }
        // Everything launched on [scope] is a write on its way to these tables. A
        // flush or a delayed profile save landing after the wipe would undo it.
        scope.coroutineContext[Job]?.children?.toList()?.forEach { it.cancelAndJoin() }
        removedWords.clear()
        val storeWiped = storeOpenLock.withLock {
            writeLock.withLock {
                eraseCount++
                // Dropped under the lock, so a flush from outside [scope] has either
                // finished, and its rows get wiped, or finds nothing left to write.
                pendingNgrams.clear()
                synchronized(pendingLogLines) { pendingLogLines.clear() }
                synchronized(usageDeltas) { usageDeltas.clear() }
                synchronized(appVocabDirty) { appVocabDirty.clear() }
                synchronized(pendingStyleStats) { pendingStyleStats.clear() }
                val emptied = wipeStore()
                // A store that will not open can't be emptied, and it takes no key to delete it.
                val storeGone = emptied || AppDatabase.deleteStore(appContext)
                // What was set aside is personal data like the rest, and stays only for as long
                // as the user wants what they typed kept. After the store, since opening it can
                // be what sets everything aside.
                val copyGone = AppDatabase.deleteSetAsideCopy(appContext)
                if (storeGone && (!emptied || !persistenceAvailable)) newStoreOpens()
                storeGone && copyGone
            }
        }
        ScreenBuffer.clearAll()
        notifyErased(null) && storeWiped
    }

    /** What a tap on Start fresh came to. */
    enum class StartFreshResult {
        /** The store opened after all, so nothing moved and every word is back. */
        NOT_NEEDED,

        /** The old store is out of the way, and saving goes on in a new one. */
        STARTED_FRESH,

        /** Nothing changed. [storageProblem] says why. */
        FAILED
    }

    /**
     * The user gave up on a store that will not open: it is moved aside, not deleted, and saving
     * starts again in a new one. It is tried once more first, since whatever made it fail may have
     * passed, and then every word comes back and nothing has to move. What memory has learned since
     * the failure goes into the new store. Only ever the user's choice, since a failure can pass and
     * bring every word back.
     */
    suspend fun startFresh(): StartFreshResult = withContext(Dispatchers.IO + NonCancellable) {
        initialize()
        storeOpenLock.withLock {
            // A second dialog, or a retry that got there first.
            if (persistenceAvailable) return@withLock StartFreshResult.NOT_NEEDED
            val movedBefore = AppDatabase.movedAsideBecause
            if (tryStoreAgainLocked()) {
                // That try may have found the store reads as corrupt and moved it itself.
                return@withLock if (AppDatabase.movedAsideBecause === movedBefore) {
                    StartFreshResult.NOT_NEEDED
                } else StartFreshResult.STARTED_FRESH
            }
            try {
                writeLock.withLock { AppDatabase.startFresh(appContext) }
            } catch (t: Throwable) {
                storeStillWouldNotOpen(t)
                return@withLock StartFreshResult.FAILED
            }
            writeLock.withLock { newStoreOpens() }
            withContext(Dispatchers.Main) { saveWhatMemoryHolds() }
            ensureFlushScheduled()
            reportToDiagnostics(STORAGE_DIAG, "Started fresh. Old saved words were moved aside.")
            StartFreshResult.STARTED_FRESH
        }
    }

    /** The store on disk holds nothing now, so whatever was wrong with the old one is over. */
    private suspend fun newStoreOpens() {
        try {
            openForUse(db)
        } catch (t: Throwable) {
            storeStillWouldNotOpen(t)
            return
        }
        // It was all allowed when it was typed, and it can have waited for days.
        dropWhatIsNoLongerAllowed()
        health.storeReplaced()
        storedRows.storeEmptied()
        // An empty store has nothing to overrule what memory decided while it could not read one,
        // so those words go from what waits to be written, as they would have when judged.
        if (!modelHasReadTheTable) {
            (fastEngine.suppressionsSnapshot() - removedWords).forEach { pendingNgrams.deleteWord(it) }
        }
        modelHasReadTheTable = true
        persistenceAvailable = true
        try {
            // There are no old verdicts here, and the marker keeps the next start
            // from taking the new ones for old.
            db.withTransaction { LegacyVerdictMigration.runOnce(db.ngramDao(), db.styleStatDao()) }
        } catch (t: Throwable) {
            storageFailed(t)
        }
    }

    /** Saves of rows nobody could read were refused while the store was closed, and these are ones. */
    private fun saveWhatMemoryHolds() {
        persistConfirmed()
        persistSuppressions()
        if (profile.sentencesAnalyzed > 0) scheduleProfilePersist()
    }

    private suspend fun wipeStore(): Boolean = try {
        db.ngramDao().clear()
        db.styleStatDao().clear()
        // Says only that the old verdicts are gone, which they are. Without it the
        // next start would take the verdicts made after this erase for old ones.
        db.styleStatDao().putAll(listOf(LegacyVerdictMigration.doneRow()))
        db.appContextDao().clear()
        db.usageStatDao().clear()
        db.distillationDao().clear()
        // Not typingLog.clear(), which swallows the failure this has to report.
        db.typingLogDao().clear()
        // Nothing is left that a failed write was trying to save.
        health.writeWorked()
        // Nor can a save now replace anything that was never read.
        storedRows.storeEmptied()
        modelHasReadTheTable = true
        // The in-memory fallback has no file name. Emptying it says nothing about
        // the file on disk that could not be opened.
        db.openHelper.databaseName != null
    } catch (t: Throwable) {
        storageFailed(t)
        false
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun ensureFlushScheduled() {
        if (flushJob?.isActive != true) {
            flushJob = scope.launch {
                delay(FLUSH_DELAY_MS)
                flushPending()
            }
        }
    }

    /** Flush everything buffered right now (typing bursts ending, keyboard
     *  hiding): n-gram increments, typing-log lines, usage counters. */
    suspend fun flushNow() = flushPending()

    /**
     * Fold the write-ahead log back into the database. Called when the
     * keyboard hides — the one moment we know no read is imminent.
     *
     * Without this the WAL only checkpoints when it happens to cross its
     * threshold mid-typing, i.e. during the latency-sensitive window. Doing it
     * on hide moves that work somewhere nobody is waiting, and keeps every
     * subsequent prediction reading fewer (encrypted) pages.
     */
    suspend fun checkpoint() = withContext(Dispatchers.IO) {
        if (!persistenceAvailable) return@withContext
        try {
            flushPending()
            // The cursor has to be stepped: unlike the pragmas that only set something,
            // this one does its work when run, so preparing and closing it does nothing.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").use { it.moveToFirst() }
        } catch (_: Throwable) {
            // PASSIVE never blocks a reader; failing is harmless either way.
        }
    }

    private suspend fun flushPending() {
        writeLock.withLock { flushPendingLocked() }
    }

    private suspend fun flushPendingLocked() {
        if (!persistenceAvailable) {
            // All of it waits in the queues for [tryStoreAgain]. Only per-app vocabulary has to
            // reach the live engine meanwhile, which a healthy store does as it writes it, and a
            // bucket that keeps growing can be handed over whole each time (see persistAppVocab).
            val waiting = synchronized(appVocabDirty) { appVocabDirty.mapValues { HashMap(it.value) } }
            for ((key, delta) in waiting) persistAppVocab(key, delta)
            return
        }
        // Still backing off from a failed write. [scheduleRetry] comes back for this.
        if (health.msUntilRetry() > 0L) return
        val statBatch: List<StyleStatEntity>
        synchronized(pendingStyleStats) {
            statBatch = pendingStyleStats.map { StyleStatEntity(it.key, it.value) }
            pendingStyleStats.clear()
        }
        val ngramBatch = pendingNgrams.drain()
        val logBatch: List<TypingLog.PendingLine>
        synchronized(pendingLogLines) {
            logBatch = ArrayList(pendingLogLines)
            pendingLogLines.clear()
        }
        val usageBatch: Map<String, Long>
        synchronized(usageDeltas) {
            usageBatch = HashMap(usageDeltas)
            usageDeltas.clear()
        }
        // Below-threshold app-vocab buckets used to stay RAM-only until 40
        // words accumulated — drain them with every flush instead.
        val vocabBatch: Map<String, Map<String, Int>>
        synchronized(appVocabDirty) {
            vocabBatch = appVocabDirty.mapValues { HashMap(it.value) }
            appVocabDirty.clear()
        }
        try {
            // One Room transaction for the whole flush: each increment used to
            // commit as its OWN SQLCipher transaction (per-key fsync cost).
            db.withTransaction {
                db.ngramDao().applyBatch(ngramBatch, System.currentTimeMillis())
                for ((key, delta) in usageBatch) {
                    // Single atomic upsert instead of read-then-write.
                    db.usageStatDao().addDelta(key, delta)
                }
                if (statBatch.isNotEmpty()) db.styleStatDao().putAll(statBatch)
            }
        } catch (t: Throwable) {
            // Rolled back, so none of it landed and all of it goes back in the queue.
            pendingNgrams.putBack(ngramBatch)
            synchronized(usageDeltas) {
                for ((key, delta) in usageBatch) usageDeltas.merge(key, delta, Long::plus)
            }
            synchronized(pendingStyleStats) {
                // A newer value for the key may have arrived since, and it wins.
                for (stat in statBatch) pendingStyleStats.putIfAbsent(stat.key, stat.value)
            }
            putBackLogLines(logBatch)
            putBackVocab(vocabBatch)
            storageFailed(t)
            return
        }
        val unwritten = HashMap(vocabBatch)
        var logWritten = false
        try {
            if (logBatch.isNotEmpty()) typingLog.logAll(logBatch)
            logWritten = true
            for ((key, delta) in vocabBatch) {
                persistAppVocab(key, delta)
                unwritten.remove(key)
            }
            if (health.failures > 0) {
                health.writeWorked()
                reportToDiagnostics(STORAGE_DIAG, "Saving works again.")
            }
        } catch (t: Throwable) {
            if (!logWritten) putBackLogLines(logBatch)
            putBackVocab(unwritten)
            storageFailed(t)
        }
    }

    private fun putBackLogLines(lines: List<TypingLog.PendingLine>) {
        synchronized(pendingLogLines) {
            pendingLogLines.addAll(0, lines)
            keepNewestLogLines()
        }
    }

    /** Whole messages, and the store may stay down for days. Call this holding [pendingLogLines]. */
    private fun keepNewestLogLines() {
        val over = pendingLogLines.size - MAX_PENDING_LOG_LINES
        if (over > 0) pendingLogLines.subList(0, over).clear()
    }

    /** A map that drops what was touched longest ago once it holds more than [limit]. */
    private fun <V> newestKept(limit: Int): HashMap<String, V> =
        object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > limit
        }

    private fun putBackVocab(buckets: Map<String, Map<String, Int>>) {
        synchronized(appVocabDirty) {
            for ((key, delta) in buckets) {
                val bucket = appVocabDirty.getOrPut(key) { newestKept(MAX_QUEUED_VOCAB_WORDS) }
                delta.forEach { (w, c) -> bucket.merge(w, c, Int::plus) }
            }
        }
    }

    private fun trackAppVocab(packageName: String, word: String) {
        if (packageName.isEmpty() || word.length < 3) return
        // B8: learn into the thread bucket too — your boss and your partner
        // on the same messenger no longer share one vocabulary. The thread
        // row lives in the same table keyed "pkg|threadKey" (no schema change).
        val thread = ScreenBuffer.currentScreenKey(packageName)
        val keys = if (thread.isNotEmpty())
            listOf(packageName, "$packageName|$thread") else listOf(packageName)
        // Written with the next flush, under the write lock, which is what keeps it from
        // landing after an erase. Whoever calls this also schedules that flush.
        synchronized(appVocabDirty) {
            for (key in keys) appVocabDirty.getOrPut(key) { newestKept(MAX_QUEUED_VOCAB_WORDS) }.merge(word, 1, Int::plus)
        }
    }

    /** A failure reaches the caller, which still holds [delta] to put back. */
    private suspend fun persistAppVocab(key: String, delta: Map<String, Int>) {
        val bar = key.indexOf('|')
        val basePkg = if (bar > 0) key.substring(0, bar) else key
        if (!persistenceAvailable) {
            // P0: only the BASE package key ever touches the live engine map —
            // a "pkg|thread" engine row is write-only garbage that predict()
            // never reads (the map grew one dead entry per conversation).
            if (bar < 0) {
                val merged = HashMap(delta)
                withContext(Dispatchers.Main) { fastEngine.setAppVocab(basePkg, merged) }
            }
            return
        }
        val existing = db.appContextDao().get(key)
        val merged = if (existing != null) parseTopWords(existing.topWordsJson) else HashMap()
        delta.forEach { (w, c) -> merged.merge(w, c, Int::plus) }
        val top = merged.entries.sortedByDescending { it.value }.take(40)
        val json = JSONArray(top.map { JSONArray().put(it.key).put(it.value) }).toString()
        db.appContextDao().put(AppContextEntity(key, json, System.currentTimeMillis()))
        // P0: bound the table — 40 most recent threads per app, 400 total.
        if (bar > 0) {
            db.appContextDao().pruneThreads(basePkg, 40)
            db.appContextDao().pruneAllThreads(400)
        }
        withContext(Dispatchers.Main) {
            if (bar < 0) {
                fastEngine.setAppVocab(basePkg, HashMap(top.associate { it.key to it.value }))
            } else if (key == "$basePkg|${ScreenBuffer.currentScreenKey(basePkg)}") {
                // P0: this delta belongs to the conversation on screen —
                // fold it into the LIVE map (×2, matching loadAppContext)
                // instead of waiting for an app switch to reload.
                fastEngine.mergeAppVocab(basePkg, delta, scale = 2)
            }
        }
    }

    private fun parseTopWords(json: String): HashMap<String, Int> {
        val map = HashMap<String, Int>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                map[pair.getString(0)] = pair.getInt(1)
            }
        } catch (_: Exception) { }
        return map
    }

    private var profilePersistJob: Job? = null
    private fun scheduleProfilePersist() {
        if (profilePersistJob?.isActive == true) return
        profilePersistJob = scope.launch {
            delay(10_000)
            putStyleStat(PROFILE_KEY, profile.toJson())
        }
    }

    private fun incrementUsage(key: String, delta: Long) {
        // In-memory merge; the batch flush writes it. Used to launch a
        // coroutine and two encrypted-DB ops per committed word.
        synchronized(usageDeltas) { usageDeltas.merge(key, delta, Long::plus) }
        ensureFlushScheduled()
    }

    companion object {
        private const val FLUSH_DELAY_MS = 1_500L
        /** A few seconds of fast typing while the first load runs. */
        private const val EARLY_LEARN_LIMIT = 256
        private const val MAX_PENDING_LOG_LINES = 500
        /** Only the top 40 words of each bucket get stored, so most of a longer wait never would. */
        private const val MAX_QUEUED_VOCAB_WORDS = 300
        /** One per app, and one per conversation in it. */
        private const val MAX_QUEUED_VOCAB_BUCKETS = 100
        /** Under this, a failure to open is taken to be about space, whatever it says. */
        private const val LOW_STORAGE_BYTES = 10L * 1024 * 1024
        private const val STORAGE_DIAG = "word storage"
        private const val CONFIRMED_WORDS_KEY = "confirmed_words"
        private const val REMOVED_WORDS_KEY = "removed_words"
        private const val PROFILE_KEY = "profile"
        /** N-gram entries untouched for this long with count <= 1 are pruned
         *  at startup (90 days). */
        private const val NGRAM_PRUNE_AGE_MS = 90L * 24 * 60 * 60 * 1000
        /** Matches the count ceiling FastEngine.injectPersonal uses when
         *  scaling trie frequency (count.coerceAtMost(50) * 60_000). */
        private const val TRIE_FREQ_COUNT_CAP = 50
        private val WORD_RE = Regex("[A-Za-z][A-Za-z'’\\-]*")
        /** Matches full URLs and bare domains (youtube.com/x, bit.ly/abc…). */
        private val URL_RE = Regex(
            "(https?://|www\\.)\\S+" +
                "|\\b\\S+?\\.(com|net|org|io|co|app|dev|me|gg|tv|info|biz|ly|link|site)(/\\S*)?\\b",
            RegexOption.IGNORE_CASE
        )

        @Volatile
        private var sharedInstance: ContextEngine? = null

        /** The one engine for this process. Two engines each reload the shared
         *  [FastEngine] on their first [initialize], discarding what it learned
         *  since, and each keep their own copy of the learned data, so an erase
         *  through one is undone when the other writes its copy back. */
        fun shared(context: Context): ContextEngine =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: context.applicationContext.let { app ->
                    ContextEngine(app, FastEngine.shared(app)).also { sharedInstance = it }
                }
            }
    }
}
