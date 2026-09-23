package com.localboard.keyboard.context

import android.content.Context
import androidx.room.withTransaction
import com.localboard.keyboard.data.db.AppContextEntity
import com.localboard.keyboard.data.db.AppDatabase
import com.localboard.keyboard.data.db.NGramEntity
import com.localboard.keyboard.data.db.StyleStatEntity
import com.localboard.keyboard.engine.FastEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The privacy context engine. Everything the keyboard learns flows through
 * here, gets batched in memory, and is persisted to the encrypted database.
 * Nothing ever leaves the device — the app holds no internet permission.
 */
class ContextEngine(
    context: Context,
    private val fastEngine: FastEngine
) {
    private val appContext = context.applicationContext
    private val db by lazy { AppDatabase.get(appContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { com.localboard.keyboard.util.Prefs(appContext) }
    private val typingLog by lazy { TypingLog(appContext) }

    /** False when the encrypted store couldn't initialize on this device.
     *  The keyboard keeps working entirely in memory; nothing crashes. */
    @Volatile
    var persistenceAvailable = true
        private set

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

    // Pending n-gram increments, flushed to DB in batches.
    private val pendingIncrements = HashMap<Triple<String, String, String>, Int>()
    // Committed text awaiting the encrypted typing log (text, app, screen, origin).
    private val pendingLogLines = ArrayList<TypingLog.PendingLine>()
    // Usage-stat deltas accumulated in memory between flushes.
    private val usageDeltas = HashMap<String, Long>()
    private var flushJob: Job? = null

    // In-memory per-app vocab, persisted periodically.
    private val appVocabDirty = HashMap<String, HashMap<String, Int>>()

    @Volatile
    var isInitialized = false
        private set

    // ------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        try {
            fastEngine.loadBaseDictionary()
            // The bigram model rides behind the dictionary on the same IO
            // pass: ~160k entries, a few hundred ms, and every consumer
            // treats "not loaded yet" as context-blind rather than broken.
            fastEngine.loadBaseLanguageModel()

            val ngrams = db.ngramDao()
            // The n-gram table was append-only: drop stale one-off entries
            // (untouched for 90 days, count <= 1) before they get rehydrated.
            ngrams.prune(System.currentTimeMillis() - NGRAM_PRUNE_AGE_MS)
            val unigrams = ngrams.topUnigrams(6000).map { it.word to it.count }
            val bigrams = ngrams.topBigrams(6000).map { Triple(it.prev1, it.word, it.count) }
            val trigrams = ngrams.topTrigrams(4000)
                .map { Triple(it.prev2 to it.prev1, it.word, it.count) }
            val stats = db.styleStatDao().all().associate { it.key to it.value }

            // A5: typo verdicts must be restored BEFORE the model load —
            // injectPersonal consults the suppression set, and struck or
            // suppressed words must not rehydrate into the unigram map.
            val suppressedRaw = stats["typo_suppressions"]
                ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            val strikeEntries = stats["typo_strikes"]
                ?.split(',')?.filter { it.isNotBlank() }
                ?.mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
                }?.toMap() ?: emptyMap()
            val confirmedRaw = stats["confirmed_words"]
                ?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

            // Engine maps are main-thread confined; hydrate on the main thread.
            withContext(Dispatchers.Main) {
                fastEngine.restoreSuppressions(suppressedRaw.toSet())
                fastEngine.restoreStrikes(strikeEntries)
                fastEngine.restoreConfirmed(confirmedRaw.toSet())
                // restoreSuppressions only re-arms the blacklist — evict each
                // still-live suppressed word from trie + userUnigram as well.
                // removeWordEverywhere re-stamps the verdict with "now", so
                // restore once more afterwards to keep the ORIGINAL stamps
                // (otherwise the 30-day TTL would never expire anything).
                val liveSuppressed = suppressedRaw
                    .map { it.substringBefore('@').lowercase() }
                    .filter { fastEngine.isSuppressed(it) }
                    .toSet()
                liveSuppressed.forEach { fastEngine.removeWordEverywhere(it) }
                if (liveSuppressed.isNotEmpty()) {
                    fastEngine.restoreSuppressions(suppressedRaw.toSet())
                }
                // Strike-1 words and suppressed words stay out of the
                // rehydrated model — their demotion now survives restarts.
                val blocked = liveSuppressed + strikeEntries.keys
                fastEngine.loadUserModel(
                    unigrams.filter { it.first !in blocked },
                    bigrams.filter { it.second !in blocked },
                    trigrams.filter { it.second !in blocked }
                )
                stats["profile"]?.let {
                    profile = StyleProfile.fromJson(it)
                    refinement.restore(profile)
                    applyStyleBoosts(profile.toBoostMap())
                }
                personalityNotes = stats["personality_notes"] ?: ""
                personaNotes = stats["persona_notes"] ?: ""
            }
            // The typing log is a rolling window, not an archive.
            typingLog.prune(days = 7)
        } catch (t: Throwable) {
            // Persistence broken on this device -> in-memory-only mode.
            persistenceAvailable = false
        }
        isInitialized = true
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
                db.typingLogDao().recentForAppAndScreen(packageName, screen, 30)
                    .map { it.text }.mapNotNull(::clean)
            } catch (t: Throwable) {
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
                db.typingLogDao().recentForAppAndScreen(packageName, screen, 40)
            } catch (t: Throwable) {
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
        if (!persistenceAvailable) return
        try {
            db.styleStatDao().putAll(listOf(StyleStatEntity("personality_notes", notes)))
        } catch (t: Throwable) {
            persistenceAvailable = false
        }
    }

    /** The last 48h of typing-log text for the nightly distiller. */
    suspend fun typingLogSnapshot(sinceMs: Long, limit: Int = 400) =
        typingLog.since(sinceMs, limit)

    /** Wipe everything stored for one app (used when it joins the blocklist). */
    suspend fun eraseAppData(packageName: String) = withContext(Dispatchers.IO) {
        ScreenBuffer.clear(packageName)  // RAM-only screen captures, if any
        if (!persistenceAvailable) return@withContext
        try {
            db.appContextDao().deleteByPackageAndThreads(packageName)
            typingLog.clearApp(packageName)
            withContext(Dispatchers.Main) { fastEngine.setAppVocab(packageName, emptyMap()) }
        } catch (t: Throwable) {
            persistenceAvailable = false
        }
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
        else try { db.ngramDao().topUnigrams(limit) } catch (t: Throwable) { emptyList() }
    }

    /** Learned vocabulary as (word, count) for [com.localboard.keyboard.util.BackupManager].
     *  Words only — no n-gram context, no message text, nothing that could
     *  reconstruct a conversation from a backup file. */
    suspend fun learnedWordsForBackup(limit: Int = 4000): List<Pair<String, Int>> =
        withContext(Dispatchers.IO) {
            if (!persistenceAvailable) emptyList()
            else try {
                db.ngramDao().topUnigrams(limit).map { it.word to it.count }
            } catch (_: Throwable) { emptyList() }
        }

    /** Remove a word from the learned model everywhere, then hot-reload the engine. */
    suspend fun deleteLearnedWord(word: String) = withContext(Dispatchers.IO) {
        if (!persistenceAvailable) return@withContext
        try {
            db.ngramDao().deleteWord(word)
            val ngrams = db.ngramDao()
            val unigrams = ngrams.topUnigrams(6000).map { it.word to it.count }
            val bigrams = ngrams.topBigrams(6000).map { Triple(it.prev1, it.word, it.count) }
            val trigrams = ngrams.topTrigrams(4000)
                .map { Triple(it.prev2 to it.prev1, it.word, it.count) }
            withContext(Dispatchers.Main) {
                fastEngine.loadUserModel(unigrams, bigrams, trigrams)
            }
        } catch (t: Throwable) {
            // best effort; engine reloads on next app start regardless
        }
    }

    fun learnWord(
        word: String,
        prev1: String,
        prev2: String,
        appPackage: String,
        selected: Boolean = false
    ) {
        if (!isInitialized || isBlocked(appPackage) || !prefs.learningEnabled) return
        val w = word.lowercase().trim()
        if (w.length < 2 || w.any { it.isDigit() }) return
        // An explicit add can lift an old typo suppression inside learn() —
        // persist the new snapshot so the word stays re-added after a restart.
        val wasSuppressed = fastEngine.isSuppressed(w)
        fastEngine.learn(w, prev1, prev2, selected)
        if (wasSuppressed && !fastEngine.isSuppressed(w)) persistSuppressions()
        queuePersist(prev1.lowercase(), prev2.lowercase(), w)
        trackAppVocab(appPackage, w)
        incrementUsage("words_learned_total", 1L)
    }

    /** Explicit user intent ("yes, this is my word" — prediction-bar tap,
     *  undo of a correction). Confirmed words are immune from autocorrect and
     *  promote into the trie below the high-usage threshold; persisted so the
     *  immunity survives restarts. */
    fun noteConfirmed(word: String) {
        if (!prefs.learningEnabled || word.length < 2) return
        fastEngine.noteConfirmed(word.lowercase())
        persistConfirmed()
    }

    private fun persistConfirmed() {
        serviceScopeLaunchIO {
            try {
                if (persistenceAvailable) {
                    val snap = fastEngine.confirmedWordsSnapshot()
                        .toList().takeLast(1000).joinToString(",")
                    db.styleStatDao().putAll(listOf(
                        com.localboard.keyboard.data.db.StyleStatEntity(
                            "confirmed_words", snap
                        )
                    ))
                }
            } catch (_: Throwable) { }
        }
    }

    private fun persistSuppressions() {
        serviceScopeLaunchIO {
            try {
                if (persistenceAvailable) {
                    // Dated entries ("word@epochMs") so verdicts expire after
                    // 30 days; legacy bare words in old stores still restore.
                    val snap = fastEngine.suppressionsSnapshotDated().toList()
                        .takeLast(500).joinToString(",")
                    val strikes = fastEngine.strikesSnapshot().entries
                        .joinToString(",") { "${it.key}=${it.value}" }
                    db.styleStatDao().putAll(listOf(
                        com.localboard.keyboard.data.db.StyleStatEntity(
                            "typo_suppressions", snap
                        ),
                        com.localboard.keyboard.data.db.StyleStatEntity(
                            "typo_strikes", strikes
                        )
                    ))
                }
            } catch (_: Throwable) { }
        }
    }

    /** The user accepted an autocorrection AWAY from [word]. A5: the FIRST
     *  time this happens the word is only demoted (a strike); n-grams are
     *  deleted and the word is blacklisted only on the second strike. The
     *  strike-1 demotion also drops the word's DB unigram row so it survives
     *  a restart (previously the count rehydrated on the next launch). */
    fun noteTypo(word: String, appPackage: String) {
        if (!isInitialized || isBlocked(appPackage)) return
        val w = word.lowercase().trim()
        if (w.length < 2) return
        fastEngine.noteTypo(w)
        if (fastEngine.isSuppressed(w)) {
            // Strike 2 = confirmed typo: every n-gram row goes, and the style
            // engine unlearns the word so its boost stops amplifying a typo.
            refinement.removeWord(w)
            serviceScopeLaunchIO {
                try {
                    if (persistenceAvailable) db.ngramDao().deleteWord(w)
                } catch (_: Throwable) { }
            }
        } else {
            // Strike 1 = demotion: drop only the unigram row; context rows
            // stay until the verdict is confirmed.
            serviceScopeLaunchIO {
                try {
                    if (persistenceAvailable) db.ngramDao().deleteUnigram(w)
                } catch (_: Throwable) { }
            }
        }
        persistSuppressions()
    }

    private fun serviceScopeLaunchIO(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** P2: the user long-pressed a suggestion and chose Remove. Banishes the
     *  word from the live engine AND its persisted n-gram rows, and marks
     *  it suppressed so predictions stop offering it. Explicit, reversible:
     *  tapping the word in the bar later lifts the suppression. */
    /** Undo one learned occurrence of [word] in memory and on disk. See
     *  [FastEngine.unlearnRecent] for why this is not [removeWordEverywhere]. */
    fun unlearnRecent(word: String) {
        if (!isInitialized) return
        val w = word.lowercase().trim()
        if (w.length < 2) return
        fastEngine.unlearnRecent(w)
        // A -1 nets against the +1 that is usually still sitting in the
        // write-ahead map from the commit a moment ago; otherwise it lands as
        // a decrement on the stored row.
        synchronized(pendingIncrements) {
            pendingIncrements.merge(Triple("", "", w), -1, Int::plus)
        }
        ensureFlushScheduled()
    }

    fun removeWordEverywhere(word: String) {
        if (!isInitialized) return
        val w = word.lowercase().trim()
        if (w.length < 2) return
        fastEngine.removeWordEverywhere(w)
        refinement.removeWord(w)  // stop style boosts from amplifying it
        serviceScopeLaunchIO {
            try {
                if (persistenceAvailable) db.ngramDao().deleteWord(w)
            } catch (_: Throwable) { }
        }
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
                    if (!skipNgramLearn) {
                        fastEngine.learn(w, prev1, prev2)
                        queuePersist(prev1, prev2, w)
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
        refineStyle(clean)
        if (prefs.typingLogEnabled) {
            synchronized(pendingLogLines) {
                pendingLogLines += TypingLog.PendingLine(
                    clean.take(500), appPackage, threadKey,
                    origin = if (aiOrigin) 1 else 0
                )
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
                if (updateStyleProfile) {
                    db.styleStatDao().putAll(listOf(StyleStatEntity("profile", result.profile.toJson())))
                }
                val now = System.currentTimeMillis()
                val entities = ArrayList<NGramEntity>(unigramList.size + bigramList.size + trigramList.size)
                unigramList.forEach { (w, c) -> entities += NGramEntity("", "", w, c, now) }
                bigramList.forEach { (p, w, c) -> entities += NGramEntity(p, "", w, c, now) }
                trigramList.forEach { (pp, w, c) -> entities += NGramEntity(pp.second, pp.first, w, c, now) }
                entities.chunked(500).forEach { db.ngramDao().upsertAll(it) }
            } catch (t: Throwable) {
                persistenceAvailable = false
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
                persistenceAvailable = false
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
                fastEngine.loadUserModelMerge(capped, emptyList(), emptyList())
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
                    persistenceAvailable = false
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
            persistenceAvailable = false
        }
    }

    // ------------------------------------------------------------------
    // Stats / privacy dashboard / wipe
    // ------------------------------------------------------------------

    suspend fun dashboardStats(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!persistenceAvailable) {
            return@withContext mapOf(
                "Storage mode" to "In-memory only (device keystore/SQLCipher unavailable)",
                "Learned words (this session)" to fastEngine.learnedWordCount.toString(),
                "Sentences analyzed for style" to profile.sentencesAnalyzed.toString(),
                "Network permissions held" to "0 (fully offline)"
            )
        }
        try {
            val usage = db.usageStatDao().all().associate { it.key to it.value }
            mapOf(
                "Storage encryption" to if (AppDatabase.encryptionActive) "SQLCipher AES-256" else "Plain (SQLCipher unavailable)",
                "Learned words" to db.ngramDao().count().toString(),
                "Word events processed" to (usage["words_learned_total"] ?: 0L).toString(),
                "Apps with context profiles" to db.appContextDao().count().toString(),
                "Sentences analyzed for style" to profile.sentencesAnalyzed.toString(),
                "Estimated formality" to "%.0f%%".format(profile.formality * 100),
                "Network usage" to "Model downloads only (user-initiated); typing never transmitted"
            )
        } catch (t: Throwable) {
            persistenceAvailable = false
            mapOf("Storage mode" to "In-memory only (error reading store)")
        }
    }

    suspend fun eraseAllLearnedData() = withContext(Dispatchers.IO) {
        if (persistenceAvailable) {
            try {
                db.ngramDao().clear()
                db.styleStatDao().clear()
                db.appContextDao().clear()
                db.usageStatDao().clear()
                db.distillationDao().clear()
                typingLog.clear()
            } catch (t: Throwable) {
                persistenceAvailable = false
            }
        }
        ScreenBuffer.clearAll()
        withContext(Dispatchers.Main) {
            profile = StyleProfile.EMPTY
            personalityNotes = ""
            personaNotes = ""
            refinement.restore(profile)
            fastEngine.loadUserModel(emptyList(), emptyList(), emptyList())
            applyStyleBoosts(emptyMap())
            fastEngine.restoreSuppressions(emptySet())
        }
        synchronized(pendingIncrements) { pendingIncrements.clear() }
        synchronized(pendingLogLines) { pendingLogLines.clear() }
        synchronized(usageDeltas) { usageDeltas.clear() }
        synchronized(appVocabDirty) { appVocabDirty.clear() }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun queuePersist(prev1: String, prev2: String, word: String) {
        synchronized(pendingIncrements) {
            pendingIncrements.merge(Triple(prev1, prev2, word), 1, Int::plus)
        }
        ensureFlushScheduled()
    }

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
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(PASSIVE)").close()
        } catch (_: Throwable) {
            // PASSIVE never blocks a reader; failing is harmless either way.
        }
    }

    private suspend fun flushPending() {
        val batch: Map<Triple<String, String, String>, Int>
        synchronized(pendingIncrements) {
            batch = HashMap(pendingIncrements)
            pendingIncrements.clear()
        }
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
        if (!persistenceAvailable) {
            // In-memory mode: still fold app-vocab deltas into the live engine
            // (persistAppVocab handles the no-DB path itself).
            for ((key, delta) in vocabBatch) persistAppVocab(key, delta)
            return
        }
        try {
            // One Room transaction for the whole flush: each increment used to
            // commit as its OWN SQLCipher transaction (per-key fsync cost).
            db.withTransaction {
                if (batch.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val inserts = ArrayList<NGramEntity>(batch.size)
                    for ((key, delta) in batch) {
                        // A net-negative delta (an unlearn that outran its
                        // learn's flush) has nothing to insert.
                        if (delta == 0) continue
                        val updated = db.ngramDao().increment(key.first, key.second, key.third, delta, now)
                        if (delta < 0) continue
                        if (updated == 0) {
                            inserts += NGramEntity(key.first, key.second, key.third, delta, now)
                        }
                    }
                    if (inserts.isNotEmpty()) db.ngramDao().upsertAll(inserts)
                }
                if (usageBatch.isNotEmpty()) {
                    for ((key, delta) in usageBatch) {
                        // Single atomic upsert instead of read-then-write.
                        db.usageStatDao().addDelta(key, delta)
                    }
                }
            }
            if (logBatch.isNotEmpty()) typingLog.logAll(logBatch)
            for ((key, delta) in vocabBatch) persistAppVocab(key, delta)
        } catch (t: Throwable) {
            persistenceAvailable = false
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
        synchronized(appVocabDirty) {
            for (key in keys) {
                val map = appVocabDirty.getOrPut(key) { HashMap() }
                map.merge(word, 1, Int::plus)
                if (map.size >= 40) {
                    val snapshot = HashMap(map)
                    map.clear()
                    scope.launch { persistAppVocab(key, snapshot) }
                }
            }
        }
    }

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
        try {
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
        } catch (t: Throwable) {
            persistenceAvailable = false
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
            if (!persistenceAvailable) return@launch
            try {
                db.styleStatDao().putAll(listOf(StyleStatEntity("profile", profile.toJson())))
            } catch (t: Throwable) {
                persistenceAvailable = false
            }
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
    }
}
