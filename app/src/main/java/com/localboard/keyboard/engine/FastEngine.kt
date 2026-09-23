package com.localboard.keyboard.engine

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * The always-on, keystroke-speed prediction engine.
 *
 * Three signals are blended:
 *  1. Base corpus frequency (bundled 32k-word dictionary, trie-backed)
 *  2. Personal usage counts learned from the user's typing (in-memory maps,
 *     persisted to the encrypted DB)
 *  3. Context n-grams (bigram/trigram "what follows these words")
 *
 * Typical lookup: < 1 ms with cache, 1-5 ms cold. Zero network, zero native code.
 */
class FastEngine(private val context: Context) {

    private val trie = Trie()

    // Personal model (user-learned). Keys keep lookup allocation-free-ish.
    // Concurrent, not plain HashMap: writes still all happen on the main
    // thread, but predictions and the correction walk now READ these from a
    // background dispatcher. ConcurrentHashMap's weakly-consistent iterators
    // are exactly the guarantee that makes that safe — a plain HashMap can
    // corrupt or throw when iterated during a concurrent put.
    private val userUnigram = java.util.concurrent.ConcurrentHashMap<String, Int>(4096)
    private val userBigram = java.util.concurrent.ConcurrentHashMap<String, Int>(4096)    // "prev|word"
    private val userTrigram = java.util.concurrent.ConcurrentHashMap<String, Int>(2048)   // "prev2|prev1|word"

    /** Learned email-shaped tokens (exactly one '@', no scheme — see
     *  [isEmailToken]) with their counts. A tiny side index into
     *  [userUnigram] so [emailCompletions] never scans the full unigram map
     *  per keystroke. Maintained at every userUnigram mutation site. */
    private val emailTokens =
        java.util.concurrent.ConcurrentHashMap<String, Int>(64)
    /** P1: prefix indexes — predictNextWord used to startsWith-scan all ~16k
     *  n-gram keys on the main thread after every space press. Indexes make
     *  next-word prediction two map reads. Maintained at every mutation
     *  site (loadUserModel / importExternalWords / learn). */
    private val bigramByFirst =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Int>>()
    private val trigramByFirst2 =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Int>>()

    /** Words whose unigram count is below DICTIONARY_PROMOTION_THRESHOLD —
     *  the only slice of userUnigram predictCompletion must scan per
     *  keystroke (promoted words are already reachable through the trie).
     *  Maintained at every userUnigram mutation site. */
    private val unpromotedWords =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun indexBigram(p1: String, w: String, c: Int, replace: Boolean) {
        val m = bigramByFirst.getOrPut(p1) { java.util.concurrent.ConcurrentHashMap() }
        if (replace) m[w] = c else m.merge(w, c, Int::plus)
    }

    private fun indexTrigram(p2: String, p1: String, w: String, c: Int, replace: Boolean) {
        val m = trigramByFirst2.getOrPut("$p2|$p1") { java.util.concurrent.ConcurrentHashMap() }
        if (replace) m[w] = c else m.merge(w, c, Int::plus)
    }

    // Per-app vocabulary boosts: packageName -> immutable (word -> count) map.
    // ConcurrentHashMap + value swap keeps reads safe from any thread.
    private val appVocab = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()

    // Style profile word affinities: word -> weight multiplier in [1..2]
    @Volatile
    private var styleBoosts: Map<String, Float> = emptyMap()

    private val predictionCache = LruCache<String, List<Suggestion>>(512)

    /** Bumped whenever the personal model changes. Part of the prediction
     *  cache key, so invalidation is O(1) instead of a 512-entry clear. */
    @Volatile
    private var modelVersion = 0

    @Volatile
    var isReady = false
        private set

    val baseWordCount: Int get() = trie.wordCount
    val learnedWordCount: Int get() = userUnigram.size

    /** Exposed for the swipe decoder's trie walk. Null until ready. */
    fun rootNode(): Trie.Node? = if (isReady) trie.root else null

    /**
     * Every word the trie knows (base dictionary + injected personal words),
     * with effective frequencies. Used by the swipe decoder's candidate
     * generator; result is large (~30k) so callers should cache it.
     */
    fun allWords(): List<Pair<String, Long>> {
        if (!isReady) return emptyList()
        // collectAll() walks the whole trie and builds ~114k fresh strings.
        // The swipe table asks for this on every rebuild, so cache it and
        // invalidate only when the TRIE actually changes — which is far
        // rarer than a learn (most learns just bump a count).
        cachedWords?.let { if (cachedWordsVersion == trieVersion) return it }
        val list = trie.collectAll()
        cachedWords = list
        cachedWordsVersion = trieVersion
        return list
    }

    @Volatile private var cachedWords: List<Pair<String, Long>>? = null
    @Volatile private var cachedWordsVersion = -1

    /** Bumped only when a word enters or leaves the TRIE (promotion, typo
     *  removal, external import, bulk reload) — not on every count tick.
     *  This is what the swipe table should key its rebuilds on: only
     *  trie-visible words can ever be glided. */
    @Volatile
    var trieVersion = 0
        private set

    private fun bumpTrie() { trieVersion++ }

    /** Load the bundled dictionary. Call once, off the main thread. */
    suspend fun loadBaseDictionary() = withContext(Dispatchers.IO) {
        if (isReady) return@withContext
        context.assets.open("base_dictionary.txt").use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    val freq = line.substring(tab + 1).toLongOrNull() ?: continue
                    trie.insert(word, freq)
                }
            }
        }
        // A3: contractions are real words. The old dictionary had ZERO
        // apostrophes, so the keyboard could never produce "don't" or "I'm" —
        // the most visible "not Gboard" symptom there is. Injected here (not
        // in the asset) so the list is curated and corpus-independent.
        for ((w, f) in CONTRACTIONS) trie.insert(w, f)
        // ...and the raw apostrophe-less forms the hard map owns are NOT
        // words: the shipped dictionary carries "dont"/"im"/"youre" as valid
        // entries, which blocks correction and gets them suggested. Strip
        // them so the map owns those strings outright.
        for (raw in CONTRACTION_FIXES.keys) trie.remove(raw)
        // P0: texting abbreviations — the corpus's formal register dropped
        // "omw"/"hbu"/"rn", exactly the words a chat keyboard types most.
        for ((w, f) in TEXTING) trie.insert(w, f)
        isReady = true
    }

    /**
     * The bundled bigram model (see [BaseLanguageModel]). Null until
     * [loadBaseLanguageModel] has run; every consumer treats null as "no
     * context information", so predictions work — context-blind — from the
     * moment the dictionary is ready and get sharper a few hundred
     * milliseconds later when this lands.
     */
    @Volatile
    var languageModel: BaseLanguageModel? = null
        private set

    /** Parse the bigram asset. Idempotent; call once, off the main thread,
     *  after [loadBaseDictionary]. A missing or corrupt asset leaves the
     *  engine context-blind rather than broken. */
    suspend fun loadBaseLanguageModel() = withContext(Dispatchers.IO) {
        if (languageModel != null) return@withContext
        try {
            val t0 = System.currentTimeMillis()
            context.assets.open("base_bigrams.txt").use { stream ->
                languageModel = BaseLanguageModel.load(stream)
            }
            modelVersion++
            android.util.Log.i(
                "FastEngine",
                "base language model: ${languageModel?.vocabularySize} words, " +
                    "${languageModel?.entryCount} bigrams in ${System.currentTimeMillis() - t0} ms"
            )
        } catch (t: Throwable) {
            // Context-blind is a degraded mode, not a failure — but a silent
            // one is undiagnosable from a release build, so say so.
            android.util.Log.w("FastEngine", "base language model failed to load; context-blind", t)
        }
    }

    /** Hydrate the personal model from the encrypted database. */
    fun loadUserModel(
        unigrams: List<Pair<String, Int>>,
        bigrams: List<Triple<String, String, Int>>,
        trigrams: List<Triple<Pair<String, String>, String, Int>>
    ) {
        userUnigram.clear()
        userBigram.clear()
        userTrigram.clear()
        bigramByFirst.clear()
        trigramByFirst2.clear()
        unpromotedWords.clear()
        emailTokens.clear()
        unigrams.forEach { (w, c) ->
            userUnigram[w] = c
            if (c < DICTIONARY_PROMOTION_THRESHOLD) unpromotedWords.add(w)
            if (isEmailToken(w)) emailTokens[w] = c
        }
        bigrams.forEach { (p, w, c) ->
            userBigram["$p|$w"] = c
            indexBigram(p, w, c, replace = true)
        }
        trigrams.forEach { (pp, w, c) ->
            userTrigram["${pp.first}|${pp.second}|$w"] = c
            indexTrigram(pp.first, pp.second, w, c, replace = true)
        }
        unigrams.forEach { (w, _) -> injectPersonal(w) }
        bumpTrie()
        topUnigramDirty = Int.MAX_VALUE
        predictionCache.evictAll()
    }

    /**
     * Personal words must live in the trie too — otherwise they can never be
     * swiped, prefix-predicted, or used as autocorrect targets. But NOT every
     * typed word belongs in the dictionary: a word is only injected once it's
     * been typed deliberately a few times (threshold below), so one-off typos
     * never become suggestions. N-gram statistics still track everything —
     * that's the raw material the nightly style distillation works from.
     */
    private fun injectPersonal(w: String) {
        if (w.length < 3) return
        // Learned emails live only in userUnigram + the email index and are
        // served by emailCompletions() — never through the trie, where they
        // would pollute swipe candidates and ordinary completions.
        if ('@' in w) return
        if (typoSuppressions.contains(w)) return
        val count = userUnigram[w] ?: return
        if (count < DICTIONARY_PROMOTION_THRESHOLD) return
        // Learned-typo loop fix: reaching the promotion threshold alone no
        // longer proves intent — the same finger-slip typed 4 times used to
        // be injected into the dictionary, after which it looked "known" and
        // could never be corrected. Promotion now requires explicit
        // confirmation (noteConfirmed) or usage far past the typo band.
        if (w !in confirmedWords && count < STRONG_USAGE_THRESHOLD) return
        val scaled = count.coerceAtMost(50) * 60_000L
        if (scaled > trie.frequency(w)) {
            if (trie.frequency(w) == 0L) injectedPersonal.add(w)
            trie.insert(w, scaled)
            bumpTrie()
        }
    }

    /** Words [injectPersonal] added to the trie that the shipped dictionary
     *  does not contain — the only trie entries [unlearnRecent] may remove. */
    private val injectedPersonal =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Undo ONE learned occurrence of [word]: the personal count drops by one
     * and, if that empties it, the word leaves the personal model (and the
     * trie, if it only got there through personal promotion). The shipped
     * dictionary is never touched and nothing is suppressed.
     *
     * This replaces calls to [removeWordEverywhere] on the "user just
     * rejected this" paths — backspace over a glide, revert of an
     * autocorrect, retype after a correction. Those paths fire almost only
     * on REAL words the engine chose wrongly ("there" for "three", "the"
     * for "teh"), and removeWordEverywhere deleted that word from the
     * dictionary and hid it from every suggestion for 30 days. Every
     * corrected glide banished an English word; a heavy glide user lost
     * dozens, which is a large part of why glide "never got it right".
     */
    fun unlearnRecent(word: String) {
        val w = word.trim().lowercase()
        if (w.length < 2) return
        val c = userUnigram[w] ?: return
        val n = c - 1
        if (n <= 0) {
            userUnigram.remove(w)
            unpromotedWords.remove(w)
            emailTokens.remove(w)
            if (injectedPersonal.remove(w)) {
                trie.remove(w)
                bumpTrie()
            }
            scrubNgrams(w)
        } else {
            userUnigram[w] = n
            if (isEmailToken(w)) emailTokens[w] = n
            if (n < DICTIONARY_PROMOTION_THRESHOLD) unpromotedWords.add(w)
        }
        topUnigramDirty = Int.MAX_VALUE
        modelVersion++
    }

    /** Words the user has REJECTED by accepting an autocorrection away from
     *  them — proven typos. They can never enter the dictionary, no matter
     *  how often the same finger-slip repeats. Persisted by ContextEngine. */
    private val typoSuppressions =
        java.util.Collections.synchronizedSet(HashSet<String>())

    fun suppressionsSnapshot(): Set<String> =
        synchronized(typoSuppressions) { typoSuppressions.toSet() }

    /** Original verdict time per suppressed word — the snapshot writes THIS
     *  stamp, not "now". The 1.6.6 snapshot re-stamped every verdict at each
     *  persist, so the 30-day TTL never actually expired anything. (P0)
     *  ConcurrentHashMap: written on the main thread but read during
     *  snapshot building that can race with a strike-2 verdict. */
    private val suppressionStamps =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Words the user has EXPLICITLY confirmed as intended vocabulary (via
     *  [noteConfirmed] — a prediction-bar tap or an undone correction wired
     *  by the caller). Only these words are immune from autocorrection at
     *  low counts; a bare repeat count no longer confers immunity, which is
     *  what let a typo typed 3x become permanently uncorrectable. Persisted
     *  by ContextEngine alongside the suppression snapshot. */
    private val confirmedWords =
        java.util.Collections.synchronizedSet(HashSet<String>())

    /** Mark [word] as deliberate vocabulary: immune from autocorrection and
     *  eligible for dictionary promotion at the normal threshold. */
    fun noteConfirmed(word: String) {
        val w = word.lowercase().trim()
        if (w.length < 2) return
        confirmedWords.add(w)
        clearStrikes(w)
        if ((userUnigram[w] ?: 0) >= DICTIONARY_PROMOTION_THRESHOLD) injectPersonal(w)
    }

    fun confirmedWordsSnapshot(): Set<String> =
        synchronized(confirmedWords) { confirmedWords.toSet() }

    fun restoreConfirmed(words: Set<String>) {
        val clean = words.map { it.lowercase().trim() }.filter { it.length >= 2 }
        synchronized(confirmedWords) {
            confirmedWords.clear()
            confirmedWords.addAll(clean)
        }
        for (w in clean) {
            if ((userUnigram[w] ?: 0) >= DICTIONARY_PROMOTION_THRESHOLD) injectPersonal(w)
        }
    }

    fun restoreSuppressions(words: Set<String>) {
        typoSuppressions.clear()
        suppressionStamps.clear()
        val now = System.currentTimeMillis()
        for (raw in words) {
            // Format: "word@epochMs" (expirable); a bare word is a legacy
            // permanent entry from the pre-two-strike era — honored forever.
            val at = raw.indexOf('@')
            if (at < 0) {
                typoSuppressions += raw.lowercase()
            } else {
                val w = raw.substring(0, at).lowercase()
                val ts = raw.substring(at + 1).toLongOrNull() ?: 0L
                if (now - ts <= SUPPRESSION_TTL_MS) {
                    typoSuppressions += w
                    suppressionStamps[w] = ts
                }
            }
        }
        modelVersion++
    }

    /** First-strike demotions: word -> (strikes, lastStrikeAt). One accepted
     *  correction is evidence, not proof — the word is benched, not hanged. */
    private val typoStrikes = HashMap<String, Pair<Int, Long>>()

    fun strikesSnapshot(): Map<String, String> = HashMap<String, String>().also { out ->
        synchronized(typoStrikes) {
            for ((w, s) in typoStrikes) out[w] = "${s.first}@${s.second}"
        }
    }

    fun restoreStrikes(entries: Map<String, String>) {
        val now = System.currentTimeMillis()
        synchronized(typoStrikes) {
            typoStrikes.clear()
            for ((w, v) in entries) {
                val parts = v.split('@')
                val count = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val ts = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                if (now - ts <= SUPPRESSION_TTL_MS) typoStrikes[w] = count to ts
            }
        }
    }

    /** Snapshot of HARD suppressions with their ORIGINAL verdict stamps
     *  ("word@epochMs"); stamp-less legacy entries persist bare (permanent). */
    fun suppressionsSnapshotDated(): Set<String> =
        synchronized(typoSuppressions) {
            typoSuppressions.map { w ->
                val ts = suppressionStamps[w]
                if (ts == null) w else "$w@$ts"
            }.toSet()
        }

    /** The user accepted a correction AWAY from [word].
     *  A5: ONE correction used to blacklist a word forever — and with the old
     *  transposition-cost error rate, that actively eroded the personal
     *  dictionary over time. Now: first correction = strike one (dropped from
     *  the personal model, nothing permanent); a second correction-away of
     *  the SAME word (without the user reverting/re-adding it in between)
     *  = confirmed typo (trie removal + blacklist). Everything expires after
     *  30 days — vocabulary verdicts should not outlive habit changes. */
    fun noteTypo(word: String) {
        val w = word.lowercase().trim()
        if (w.length < 2 || w.any { it.isDigit() }) return
        val now = System.currentTimeMillis()
        val strikes: Int
        synchronized(typoStrikes) {
            val prev = typoStrikes[w]
            strikes = (if (prev != null && now - prev.second <= SUPPRESSION_TTL_MS)
                prev.first else 0) + 1
            typoStrikes[w] = strikes to now
        }
        userUnigram.remove(w)
        unpromotedWords.remove(w)
        emailTokens.remove(w)
        // Strike one already demotes the trie entry — a deliberately re-typed
        // word is restored by injectPersonal on the next learn, so this costs
        // a deliberate word nothing but immediately stops suggesting a typo
        // that had reached promotion strength before the verdict arrived.
        trie.remove(w)
        bumpTrie()
        if (strikes >= 2) {
            // Confirmed typo: scrub it from the n-gram model too, not just
            // the unigram map — otherwise "prev|typo" entries keep
            // recommending the very word the user has now rejected twice.
            scrubNgrams(w)
            typoSuppressions.add(w)
            suppressionStamps[w] = now
            synchronized(typoStrikes) { typoStrikes.remove(w) }
        }
        topUnigramDirty = Int.MAX_VALUE
        modelVersion++
    }

    /** Remove every trace of [w] from the n-gram maps and their prefix
     *  indexes. Shared by [noteTypo] (strike 2) and [removeWordEverywhere]. */
    private fun scrubNgrams(w: String) {
        userBigram.keys.removeAll { it.startsWith("$w|") || it.endsWith("|$w") }
        userTrigram.keys.removeAll {
            it.startsWith("$w|") || it.endsWith("|$w") || it.contains("|$w|")
        }
        bigramByFirst.values.forEach { it.remove(w) }
        bigramByFirst.entries.removeAll { it.value.isEmpty() }
        trigramByFirst2.values.forEach { it.remove(w) }
        trigramByFirst2.entries.removeAll { it.value.isEmpty() }
    }

    /** Explicit intent (prediction-bar tap, undo of a correction) clears all
     *  strikes — the word was never a typo, the engine was. */
    private fun clearStrikes(w: String) {
        synchronized(typoStrikes) { typoStrikes.remove(w) }
    }

    /** Words imported from an external personal dictionary (the system user
     *  dictionary, where Gboard puts words the user explicitly added). Being
     *  curated there IS explicit intent — so they enter at promotion strength
     *  and lift any old typo verdict, exactly like a prediction-bar add.
     *  Returns how many words were newly added or reinstated. */
    fun importExternalWords(words: List<String>): List<String> {
        val promoted = ArrayList<String>(words.size)
        for (raw in words) {
            val w = raw.lowercase().trim()
            if (w.length < 2 || w.length > 40 || w.any { it.isDigit() }) continue
            typoSuppressions.remove(w)
            suppressionStamps.remove(w)
            clearStrikes(w)
            // A curated external dictionary entry IS explicit intent.
            confirmedWords.add(w)
            val cur = userUnigram[w] ?: 0
            if (cur < DICTIONARY_PROMOTION_THRESHOLD) {
                userUnigram[w] = DICTIONARY_PROMOTION_THRESHOLD
                promoted += w
            }
            unpromotedWords.remove(w)
            injectPersonal(w)
        }
        modelVersion++
        return promoted
    }

    /** Merge a bulk-imported corpus into the existing personal model. */
    fun loadUserModelMerge(
        unigrams: List<Pair<String, Int>>,
        bigrams: List<Triple<String, String, Int>>,
        trigrams: List<Triple<Pair<String, String>, String, Int>>
    ) {
        unigrams.forEach { (w, c) ->
            userUnigram.merge(w, c, Int::plus)
            if ((userUnigram[w] ?: 0) < DICTIONARY_PROMOTION_THRESHOLD)
                unpromotedWords.add(w) else unpromotedWords.remove(w)
            if (isEmailToken(w)) emailTokens.merge(w, c, Int::plus)
        }
        bigrams.forEach { (p, w, c) ->
            userBigram.merge("$p|$w", c, Int::plus)
            indexBigram(p, w, c, replace = false)
        }
        trigrams.forEach { (pp, w, c) ->
            userTrigram.merge("${pp.first}|${pp.second}|$w", c, Int::plus)
            indexTrigram(pp.first, pp.second, w, c, replace = false)
        }
        unigrams.forEach { (w, _) -> injectPersonal(w) }
        bumpTrie()
        predictionCache.evictAll()
    }

    fun setAppVocab(packageName: String, words: Map<String, Int>) {
        appVocab[packageName] = words.toMap() // immutable snapshot
    }

    /** Fold a fresh delta into the LIVE vocab (P0) — the current thread's
     *  words take effect immediately instead of after an app switch. */
    fun mergeAppVocab(packageName: String, delta: Map<String, Int>, scale: Int = 1) {
        val merged = HashMap(appVocab[packageName] ?: emptyMap())
        delta.forEach { (w, c) -> merged.merge(w, c * scale, Int::plus) }
        appVocab[packageName] = merged.toMap()
        predictionCache.evictAll()
    }

    fun setStyleBoosts(boosts: Map<String, Float>) {
        styleBoosts = boosts
        predictionCache.evictAll()
    }

    /** P2: the user long-pressed this word in the bar and chose Remove —
     *  banish it from every learned structure (trie, unigram, both n-gram
     *  maps and their prefix indexes, every app's vocab) and suppress
     *  re-suggestion. A base-dictionary entry isn't deleted (it was never
     *  "learned"), but suppression still hides it from predictions. */
    fun removeWordEverywhere(word: String) {
        val w = word.trim().lowercase()
        if (w.length < 2) return
        userUnigram.remove(w)
        unpromotedWords.remove(w)
        emailTokens.remove(w)
        trie.remove(w)
        bumpTrie()
        scrubNgrams(w)
        for ((pkg, vocab) in appVocab) {
            if (vocab.containsKey(w)) {
                val next = HashMap(vocab); next.remove(w)
                appVocab[pkg] = next.toMap()
            }
        }
        // Suppress exactly like a confirmed typo: hidden from predictions,
        // lifted again by an explicit bar tap of the word (learn selected).
        typoSuppressions.add(w)
        suppressionStamps[w] = System.currentTimeMillis()
        topUnigramDirty = Int.MAX_VALUE
        predictionCache.evictAll()
    }

    /** True when [word] has been observed following [prev1] (personal model). */
    /** Personal usage count of a word (0 = never used). */
    fun userFrequency(word: String): Int = userUnigram[word.lowercase()] ?: 0

    /** Like [userFrequency] for a word the caller has ALREADY lowercased —
     *  hot paths call this per candidate, so no per-call lowercase(). */
    fun userFrequencyLower(w: String): Long = userUnigram[w]?.toLong() ?: 0L

    fun hasBigram(prev1: String, word: String): Boolean =
        userBigram.containsKey("${prev1.lowercase()}|${word.lowercase()}")

    /** Like [hasBigram] with both args ALREADY lowercase. Reads the nested
     *  prefix index instead of building a "prev|word" key, so no per-call
     *  lowercase() and no string concatenation. */
    fun hasBigramLower(loweredPrev: String, lowerWord: String): Boolean =
        bigramByFirst[loweredPrev]?.containsKey(lowerWord) == true

    /**
     * The missed space bar: "thisis" -> "this is".
     *
     * A run-on is invisible to [autoCorrectTop], because inserting a space is
     * not an edit it considers and the merged form is nowhere near a real word
     * by edit distance — "helloworld" is ten edits from anything. So the whole
     * token silently becomes one unknown super-word, prediction gives up on
     * it, and both real words are lost from the learned model too.
     *
     * Every split point is tried; both halves must be genuinely known. The
     * score is the rarer half's frequency, not the sum: a split is only as
     * trustworthy as its weakest side, and summing lets a very common word
     * ("the" + "reforest") drag an implausible partner through.
     *
     * Short fragments are where this goes wrong. The shipped dictionary is a
     * word LIST, not a list of words people write: it carries dozens of
     * two-letter entries — scrabble fragments, abbreviations, foreign
     * particles — at frequencies high enough to clear any score threshold. A
     * frequency bar alone therefore cannot tell "is it" from the "ki" in a
     * name, and the token that reaches here is by definition one the
     * dictionary does not know: a name, a handle, a brand, a piece of jargon.
     * Those are precisely the words the user most needs typed verbatim.
     *
     * So short halves are decided by membership, not by frequency: a genuine
     * missed space only ever produces a one- or two-letter half from a very
     * small closed set of English words, and [SHORT_WORDS] is that set.
     *
     * Returns null unless one split is clearly best, so an ambiguous token is
     * left alone rather than guessed at.
     */
    fun splitRunOn(
        word: String,
        prev1: String = "",
        capitalized: Boolean = false
    ): Pair<String, String>? {
        if (!isReady) return null
        // A capitalised token the dictionary does not know is far likelier to
        // be a name than a missed space. Cutting "Sarahs" in half is a worse
        // error than leaving a genuinely run-on "Thisis" alone — the first
        // mangles something the user typed deliberately and will type again,
        // the second is a typo they can see and fix.
        if (capitalized) return null
        val w = word.lowercase()
        if (w.length < 4 || w.length > 24) return null
        if (w.any { !it.isLetter() }) return null
        // Already a word, or one the user has typed on purpose before. One
        // prior use is enough: a run-on typed twice is rare, while a name
        // typed twice is a certainty, and only one of those is worth guessing
        // about.
        if (trie.contains(w)) return null
        if ((userUnigram[w] ?: 0) >= 1) return null

        var bestScore = 0.0
        var best: Pair<String, String>? = null
        var runnerUp = 0.0

        for (cut in 1 until w.length) {
            val a = w.substring(0, cut)
            val b = w.substring(cut)
            if (!isSplittablePart(a) || !isSplittablePart(b)) continue

            val fa = partFrequency(a)
            val fb = partFrequency(b)
            if (fa <= 0L || fb <= 0L) continue

            // The weaker half decides. A split is a claim about BOTH words.
            var s = kotlin.math.ln(1.0 + kotlin.math.min(fa, fb).toDouble())
            // Real bigrams are strong evidence the pair belongs together.
            if (hasBigram(a, b)) s += 3.0
            if (prev1.isNotEmpty() && hasBigram(prev1, a)) s += 1.0
            // Two-letter fragments are the main source of nonsense splits.
            if (a.length == 2) s -= 1.5
            if (b.length == 2) s -= 1.5
            // A balanced cut is likelier than shaving one letter off the end.
            s -= kotlin.math.abs(a.length - b.length) * 0.15

            if (s > bestScore) { runnerUp = bestScore; bestScore = s; best = a to b }
            else if (s > runnerUp) runnerUp = s
        }

        if (best == null) return null
        // Absolute confidence, and a clear win over the second-best cut.
        if (bestScore < SPLIT_MIN_SCORE) return null
        if (bestScore - runnerUp < SPLIT_MARGIN) return null
        return best
    }

    /** Halves that are allowed to appear in a split.
     *
     *  Anything one or two letters long has to be an actual English word, not
     *  merely a dictionary entry — see [SHORT_WORDS]. This is the single guard
     *  that stops an unknown name from acquiring a plausible-looking "a" +
     *  rest or "ab" + rest split. */
    private fun isSplittablePart(p: String): Boolean =
        if (p.length <= 2) p in SHORT_WORDS else true

    /** Frequency of a split half, 0 when it is not a word at all. */
    private fun partFrequency(p: String): Long {
        val dict = trie.frequency(p)          // 0 when absent, never null
        val user = (userUnigram[p] ?: 0).toLong()
        // A word the user actually types counts for more than corpus rank —
        // names and slang are exactly the halves a corpus will not have.
        return if (user > 0) user * 4L + dict else dict
    }

    /**
     * Space-bar autocorrect: best dictionary word within a small edit distance
     * of [word]. Returns null when [word] is already known or nothing is close
     * enough. Trie-guided Damerau-Levenshtein walk with keyboard-adjacency
     * weighting: swapping a letter for the key NEXT to it costs half, because
     * that's how thumbs actually miss. This is what lifts real-world accuracy
     * well above plain edit distance ("gake"→"fake" is 0.5 away, not 1).
     */
    fun autoCorrect(
        word: String,
        minLen: Int = 3,
        forceMaxDist: Int = 0,
        prev1: String = "",
        prev2: String = ""
    ): String? = autoCorrectTop(word, 1, minLen, forceMaxDist, prev1, prev2)
        .firstOrNull()?.first

    /** Top corrections, best first: (word, score). Empty when [word] is known.
     *  [prev1]/[prev2] (the committed words before this one) let context break
     *  exact geometric ties — "wnat" is what/want at the same distance; only
     *  "i ___" decides (A2). */
    fun autoCorrectTop(
        word: String,
        limit: Int,
        minLen: Int = 3,
        forceMaxDist: Int = 0,
        prev1: String = "",
        prev2: String = "",
        correctionBudget: Int = 30_000
    ): List<Pair<String, Double>> =
        autoCorrectScored(word, minLen, forceMaxDist, prev1, prev2, correctionBudget)
            .take(limit)
            .map { it.first to it.third }

    /** Space-bar autocorrect for auto-apply: like [autoCorrect] but null
     *  unless the win is DECISIVE — the winner is within one weighted edit of
     *  the typed string, or it beats the runner-up by a clear score margin.
     *  A borderline auto-applied correction is a word the user has to undo,
     *  so anything weaker is left exactly as typed.
     *
     *  Short words and real dictionary words are handled by the context-
     *  override rule inside [correctionProbe] (which this now delegates to). */
    fun autoCorrectWithMargin(w: String, prev1: String?, prev2: String?): AutoFix? =
        correctionProbe(w, prev1, prev2).first

    /**
     * One candidate walk, two answers:
     *  - first: the decisive auto-commit fix (same contract as
     *    [autoCorrectWithMargin]), null when the win is not decisive;
     *  - second: the best candidate regardless, so the caller can show it as
     *    a NON-committed bar suggestion when no auto-commit happened.
     *
     *  Two word classes reach here that the classic corrector used to bounce
     *  at the door:
     *
     *  A. REAL dictionary words ("id", "ut", "form"). The old `trie.contains`
     *     early-out meant a correctly-spelled word could never be corrected,
     *     even when the n-gram context made another reading overwhelming
     *     ("what id" -> "what it").
     *  B. TWO-LETTER strings. The old minLen-3 gate never let them into the
     *     walk at all.
     *
     *  Both now go through the CONTEXT OVERRIDE rule: the typed word competes
     *  as a hypothesis scored at distance 0 with its real frequency and its
     *  own context bonus. A candidate only auto-commits when it beats that
     *  baseline by [CONTEXT_OVERRIDE_MARGIN] AND the advantage is genuinely
     *  context-driven — the winner's bigram/trigram bonus must exceed the
     *  typed word's, and stripping context from both scores must erase the
     *  win (otherwise raw frequency, not context, is doing the work). When
     *  either check fails the typed word stands and the candidate is only
     *  offered as a suggestion.
     *
     *  Unknown words of length >= 3 keep the classic decisive rule
     *  (one weighted edit, or a clear margin over the runner-up).
     */
    fun correctionProbe(
        w: String,
        prev1: String?,
        prev2: String?,
        /** Autocorrect boldness (Prefs.autoCorrectLevel): 1 light, 2 balanced,
         *  3 bold. Everything below "bold" follows Gboard's rule that a word
         *  the dictionary knows is never rewritten — the context override is
         *  a bold-only behaviour, and the bar still offers the candidate. */
        level: Int = 2
    ): Pair<AutoFix?, String?> {
        if (!isReady) return null to null
        val lower = w.lowercase().replace('’', '\'')
        if (lower.length < 2) return null to null
        if (lower.any { !it.isLetter() && it != '\'' }) return null to null
        val p1 = prev1 ?: ""
        val p2 = prev2 ?: ""
        // Two-letter words get a tight net: ~one adjacent-key substitution
        // (a 0.5-weighted edit, two at most). Anything wider surfaces junk
        // from the dictionary's large two-letter fragment population.
        val ranked = autoCorrectScored(
            lower, minLen = 2,
            forceMaxDist = if (lower.length == 2) SHORT_WORD_MAX_DIST else 0,
            prev1 = p1, prev2 = p2, correctionBudget = 30_000,
            allowKnownWord = true
        )
        var pool = ranked
        if (lower.length == 2) {
            // Short-candidate guard: a 2-letter fix must be a REAL English
            // short word (the closed SHORT_WORDS set) or something the user
            // personally uses — never a scrabble-fragment dictionary entry.
            pool = ranked.filter {
                it.first in SHORT_WORDS || (userUnigram[it.first] ?: 0) >= 2
            }
        }
        val winner = pool.firstOrNull()
        // A real short word ("id", "on", "ok") counts as known even when the
        // trie's two-letter tail is what says so.
        val isKnown = trie.contains(lower) || lower in SHORT_WORDS
        if (!isKnown && lower.length >= 3) {
            if (winner == null) return null to null
            val margin = pool.getOrNull(1)?.let { winner.third - it.third }
                ?: Double.POSITIVE_INFINITY
            // Classic decisive rule: within one weighted edit, or a clear
            // margin over the runner-up. Light insists on the single edit.
            val decisive = winner.second <= 1.0 || (level >= 2 && margin >= 1.0)
            if (!decisive) return null to winner.first
            // The winner must be a word worth betting on. The dictionary's
            // 110k entries include a long tail of names and fragments, and
            // an unknown typed word — most often a name, a handle, a product
            // — is one edit from several of them. Gboard leaves such a word
            // alone rather than rewrite it into something rarer than the
            // typo itself; the floor relaxes with length because a long word
            // has few neighbours and a one-edit match is almost surely meant.
            // The user's own words are exempt: typed twice is worth more than
            // any corpus count.
            val winFreq = trie.frequency(winner.first)
            val userCount = userUnigram[winner.first] ?: 0
            val floor = when {
                lower.length >= 8 -> AUTOCOMMIT_FLOOR_LONG
                lower.length >= 6 -> AUTOCOMMIT_FLOOR_MID
                else -> AUTOCOMMIT_FLOOR_SHORT
            }
            if (winFreq < floor && userCount < 2) return null to winner.first
            return AutoFix(winner.first, winner.third, margin) to winner.first
        }
        // Context override: known word, or a 2-letter string. (Immunity —
        // confirmedWords / strong usage — was already enforced inside
        // autoCorrectScored, so an immune word arrives here with an empty
        // pool and is left alone.)
        if (winner == null || winner.first == lower) return null to null
        // Below bold, a known word is never auto-rewritten: "form" stays
        // "form" even when "from" would read better. The candidate is still
        // offered on the bar. A two-letter UNKNOWN string ("ot", "ni") can
        // still be fixed at any level — that path is a typo, not a word.
        if (level < 3 && isKnown) return null to winner.first
        val lp1 = p1.lowercase()
        val lp2 = p2.lowercase()
        val typedFreq = max(trie.frequency(lower), (userUnigram[lower] ?: 0).toLong() * 8)
        val baselineCtx = contextBonusFor(lp1, lp2, lower)
        // The typed word's baseline: distance 0, real frequency, own context.
        val baseline = ln((typedFreq + 1).toDouble()) * FREQ_BLEND_W + baselineCtx
        val winnerCtx = contextBonusFor(lp1, lp2, winner.first)
        val winBy = winner.third - baseline
        val contextDecisive = winnerCtx > baselineCtx &&
            (winner.third - winnerCtx) < baseline + CONTEXT_OVERRIDE_MARGIN
        return if (winBy >= CONTEXT_OVERRIDE_MARGIN && contextDecisive) {
            AutoFix(winner.first, winner.third, winBy) to winner.first
        } else {
            null to winner.first
        }
    }

    /** The shared candidate search behind [autoCorrectTop] and
     *  [correctionProbe]. Returns (word, weightedDist, blendedScore),
     *  best first, not truncated. Empty when [word] is already known, unless
     *  [allowKnownWord] is set (the context-override path scores the typed
     *  word itself as the baseline instead). */
    private fun autoCorrectScored(
        word: String,
        minLen: Int,
        forceMaxDist: Int,
        prev1: String,
        prev2: String,
        correctionBudget: Int,
        allowKnownWord: Boolean = false
    ): List<Triple<String, Double, Double>> {
        if (!isReady) return emptyList()
        val w = word.lowercase().replace('’', '\'')
        // Apostrophes are word characters ("dont" is a typo of "don't"). (A3)
        if (w.length < minLen || w.any { !it.isLetter() && it != '\'' }) return emptyList()
        if (w.isEmpty()) return emptyList()
        // Immunity from correction now requires EXPLICIT confirmation
        // (noteConfirmed). A bare repeat count is only weaker secondary
        // evidence, and the bar sits well above the old 3: a finger-slip
        // typo repeats easily, and at the old threshold a typo typed 3x
        // became permanently uncorrectable (the learned-typo loop).
        if (w in confirmedWords) return emptyList()
        if ((userUnigram[w] ?: 0) >= STRONG_USAGE_THRESHOLD) return emptyList()
        if (!allowKnownWord && trie.contains(w)) return emptyList()
        val maxDist = if (forceMaxDist > 0) forceMaxDist.toDouble()
        else if (w.length >= 6) 2.0 else 1.5

        val results = ArrayList<Triple<String, Double, Double>>() // word, weightedDist, freqScore
        // P0: 9000 steps starved on the 114k-word dictionary — deeper trie
        // letters were never explored ("thsi" offered "thai" again until the
        // budget reached ~30k). Spatial's wide net gets its own larger budget.
        var budget = correctionBudget
        val path = StringBuilder()
        val n = w.length
        // DL rows reused by depth: each node's row is fully rewritten before
        // use and only prev/prevPrev are live, so a depth-indexed buffer
        // makes allocations O(word length) per correction instead of one
        // DoubleArray(n+1) per explored node. n + 32 bounds the walk: past
        // n + maxDist/minEditCost letters, rowMin already exceeds maxDist
        // and the walk descends no further.
        val rows = arrayOfNulls<DoubleArray>(n + 32)
        val initial = DoubleArray(n + 1) { it.toDouble() }
        rows[0] = initial

        fun walk(node: Trie.Node, letter: Char, depth: Int, prevRow: DoubleArray, prevPrevRow: DoubleArray?, prevLetter: Char) {
            if (budget <= 0) return
            budget--
            path.append(letter)
            val row = if (depth < rows.size) {
                rows[depth] ?: DoubleArray(n + 1).also { rows[depth] = it }
            } else {
                DoubleArray(n + 1)
            }
            row[0] = prevRow[0] + 1
            var rowMin = row[0]
            for (i in 1..n) {
                val subCost = if (w[i - 1] == letter) 0.0 else keyDist(w[i - 1], letter)
                // Insert/delete of an APOSTROPHE is cheap: "dont" -> "don't"
                // is one dropped punct mark, not a real letter error. (A3)
                val insCost = if (letter == '\'') APOSTROPHE_EDIT_COST else 1.0
                val delCost = if (w[i - 1] == '\'') APOSTROPHE_EDIT_COST else 1.0
                var v = minOf(prevRow[i] + delCost, row[i - 1] + insCost, prevRow[i - 1] + subCost)
                if (prevPrevRow != null && i >= 2 && prevLetter == w[i - 1] && letter == w[i - 2]) {
                    // A1: transposition is the most common fast-typing error
                    // class — at cost 1.0 every "thsi" lost to a one-adjacent-
                    // key word no matter how rare ("thsi"->"thai", "teh"->"ten").
                    // Offline against the real dictionary: 21/32 -> 30/32 at 0.5.
                    v = minOf(v, prevPrevRow[i - 2] + TRANSPOSITION_COST)
                }
                row[i] = v
                if (v < rowMin) rowMin = v
            }
            val d = row[n]
            if (d in 0.25..maxDist) {
                val cand = path.toString()
                val freq = max(node.frequency, (userUnigram[cand] ?: 0).toLong() * 8)
                if (freq > 0L) {
                    results += Triple(cand, d, ln((freq + 1).toDouble()))
                }
            }
            if (rowMin <= maxDist) {
                // Beam ordering at shallow depth: children that plausibly
                // continue the typed word are visited first, so the exploration
                // budget is spent on realistic substitutions before exotic
                // branches it would never reach otherwise.
                if (depth <= 1 && depth < n) {
                    val target = w[depth]
                    for (e in node.children.entries.sortedBy { keyDist(target, it.key) }) {
                        walk(e.value, e.key, depth + 1, row, prevRow, letter)
                    }
                } else {
                    for ((c, child) in node.children) walk(child, c, depth + 1, row, prevRow, letter)
                }
            }
            path.deleteCharAt(path.length - 1)
        }

        // Root level gets the same plausible-first ordering (depth 0).
        val rootOrdered = trie.root.children.entries.sortedBy { keyDist(w[0], it.key) }
        for (e in rootOrdered) walk(e.value, e.key, 1, initial, null, ' ')
        // A2: one BLENDED score instead of lexicographic (distance, freq).
        // The old sort let distance strictly dominate, so frequency could
        // only break perfect ties and context could never vote at all.
        // Blend calibrated offline: holds 30/32 on the typo set while making
        // frequency and n-gram context genuinely influence the outcome.
        val p1 = prev1.lowercase()
        val p2 = prev2.lowercase()
        return results
            .distinctBy { it.first }
            .filter { offerable(it.first) }
            .map { t ->
                val blended = t.third * FREQ_BLEND_W - t.second + contextBonusFor(p1, p2, t.first)
                Triple(t.first, t.second, blended)
            }
            .sortedByDescending { it.third }
    }

    /** N-gram context bonus for a candidate: bigram with prev1 plus the
     *  stronger trigram with (prev2, prev1), capped so context can tip a
     *  close call but never outweigh the evidence of the keys themselves. */
    private fun contextBonusFor(p1: String, p2: String, cand: String): Double {
        var b = 0.0
        if (p1.isNotEmpty()) {
            userBigram["$p1|$cand"]?.let { b += ln(1.0 + it) * 0.3 }
            // General English votes too, more quietly than the user's own
            // phrases: enough to break a geometric tie ("i wnat" -> want,
            // not what), never enough on its own to re-spell a real word.
            languageModel?.let { b += it.contextScore(p1, cand, LM_CORRECT_W) }
        }
        if (p1.isNotEmpty() && p2.isNotEmpty()) {
            userTrigram["$p2|$p1|$cand"]?.let { b += ln(1.0 + it) * 0.45 }
        }
        return b.coerceAtMost(CONTEXT_BONUS_CAP)
    }

    /**
     * A4: joint spatial+language correction — the Gboard-shaped fix. Instead
     * of reconstructing intent from the lossy letter string, each candidate
     * is scored by how well it explains WHERE the taps actually landed:
     * Gaussian spatial likelihood (sigma ≈ half a key width) per letter
     * against the live key geometry, blended with (capped) frequency and
     * n-gram context. Geometry dominates; language breaks near-ties.
     * Same-length candidates get true point alignment (substitution and
     * transposition — the dominant fast-typing error classes); length-
     * changing candidates stay reachable via an alignment penalty.
     * The raw typed string competes as a hypothesis with home-field
     * advantage: a correction must actually WIN, not just tie.
     */
    fun spatialCorrect(
        word: String,
        trace: List<android.graphics.PointF>,
        geometry: KeyboardGeometry,
        prev1: String = "",
        prev2: String = "",
        limit: Int = 4,
        /** Edit-distance ceiling for the candidate pool. 0 keeps this
         *  function's own length-based choice; a caller passes a real value to
         *  impose the user's autocorrect boldness, which otherwise applied to
         *  the non-spatial path only and left "light" running the widest net
         *  the engine has. */
        maxEditDist: Int = 0
    ): List<Pair<String, Double>> {
        if (!isReady || trace.size != word.length || !geometry.hasLetters()) return emptyList()
        val w = word.lowercase().replace('’', '\'')
        if (w.length < 3 || w.any { !it.isLetter() && it != '\'' }) return emptyList()
        // Belt and braces: the guard above compares `trace` to `word`, but every
        // loop below indexes `w`. lowercase() does not preserve length for all
        // inputs, and this list has historically arrived live and mutating.
        // Re-check against the string that is actually indexed.
        if (w.length != trace.size) return emptyList()
        if (trie.contains(w)) return emptyList()
        // Same immunity rule as autoCorrectScored: confirmed vocabulary, or
        // usage far past the typo band.
        if (w in confirmedWords) return emptyList()
        if ((userUnigram[w] ?: 0) >= STRONG_USAGE_THRESHOLD) return emptyList()
        val keyW = geometry.averageKeyWidth
        if (keyW <= 0f) return emptyList()
        // Wide net from the classic path, then rescore every candidate
        // spatially. P0: maxDist 3 over the 114k trie needs ~70k explored
        // nodes — the pool gets its own budget so the net is actually wide.
        val pool = autoCorrectTop(
            w, 24, minLen = 3,
            forceMaxDist = if (maxEditDist > 0) maxEditDist
            else if (w.length >= 6) 3 else 2,
            prev1 = prev1, prev2 = prev2, correctionBudget = 120_000
        )
        if (pool.isEmpty()) return emptyList()
        val sigma2 = 2.0 * (0.55 * keyW) * (0.55 * keyW)
        val p1 = prev1.lowercase()
        val p2 = prev2.lowercase()
        fun ctxBonus(cand: String): Double = contextBonusFor(p1, p2, cand)
        val scored = ArrayList<Pair<String, Double>>(pool.size)
        for ((cand, _) in pool) {
            var spatial = 0.0
            var aligned = true
            var lengthMismatch = false
            if (cand.length == trace.size && cand.length <= w.length) {
                // P0: score EVERY position — skipping matching ones made the
                // comparison asymmetric (the candidate skipped costs the raw
                // string pays), handing common words a structural bonus.
                for (i in cand.indices) {
                    val d2 = geometry.squaredDistance(cand[i], trace[i].x, trace[i].y)
                    if (d2 == Float.MAX_VALUE) { aligned = false; break }
                    spatial -= d2 / sigma2
                }
            } else {
                // P0: -2.5 per missing letter was weaker than the +4.7 a
                // common longer word could win from language+context alone —
                // insert/delete candidates effectively always lost (and
                // occasionally won wrongly). Penalty 4.5, language capped.
                lengthMismatch = true
                spatial = -4.5 * kotlin.math.abs(cand.length - trace.size)
            }
            if (!aligned) continue
            val freq = max(trie.frequency(cand), (userUnigram[cand] ?: 0).toLong() * 8)
            var lang = ln((freq + 1).toDouble()).coerceAtMost(14.0) * FREQ_BLEND_W
            if (lengthMismatch) lang = lang.coerceAtMost(2.0)
            scored += cand to (spatial + lang + ctxBonus(cand))
        }
        // The raw string's own likelihood: taps landed near ITS letters by
        // construction, so it sits near zero cost. A small bonus keeps
        // borderline "corrections" from overriding what was actually typed.
        var rawScore = 1.0
        for (i in 0 until min(w.length, trace.size)) {
            val d2 = geometry.squaredDistance(w[i], trace[i].x, trace[i].y)
            if (d2 != Float.MAX_VALUE) rawScore -= d2 / sigma2
        }
        return scored
            .filter { it.second > rawScore }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /** 0 for the same key, 0.5 for adjacent keys on a QWERTY grid, 1 otherwise. */
    private fun keyDist(a: Char, b: Char): Double =
        if (a == b) 0.0 else if (NEIGHBORS[a]?.contains(b) == true) 0.5 else 1.0

    /** Public adjacency check — used by correction-driven hand calibration
     *  ("hit q, meant w": the two keys must be neighbors for the signal to
     *  be a hand-position issue rather than a real typo). */
    fun keysAdjacent(a: Char, b: Char): Boolean =
        a != b && NEIGHBORS[a.lowercaseChar()]?.contains(b.lowercaseChar()) == true

    /** Learn one occurrence. Persisting is handled by ContextEngine (batched).
     *  Counts every word for statistics, but only promotes deliberate repeats
     *  into the swipe/correction dictionary (see injectPersonal). [selected]
     *  means the user tapped the word in the prediction bar — explicit intent,
     *  worth several sightings. Confirmed typos (suppressed) are never learned. */
    fun isSuppressed(word: String): Boolean = typoSuppressions.contains(word.lowercase())

    fun learn(word: String, prev1: String, prev2: String, selected: Boolean = false) {
        if (word.length < 2) return
        val w = word.lowercase()
        // Email-shaped tokens keep their digits ("jane2@…") and may run long;
        // every other token with a digit is still refused outright.
        val emailish = isEmailToken(w)
        if (emailish) {
            if (w.length > 64) return
        } else if (w.any { it.isDigit() }) return
        if (typoSuppressions.contains(w)) {
            // Explicit intent overrides an old typo verdict: the user tapping
            // this exact word in the prediction bar (or reverting a correction
            // back to it) means "this IS my word" — lift the suppression so
            // the word can finally stick. Without this, a word once
            // corrected-away could never be re-added: every learn bounced off
            // the blacklist and the keyboard kept asking to add it again.
            if (!selected) return
            typoSuppressions.remove(w)
            suppressionStamps.remove(w)
            predictionCache.evictAll()
        }
        if (selected) clearStrikes(w)
        val newCount = (userUnigram[w] ?: 0) + (if (selected) SELECTION_BOOST else 1)
        userUnigram[w] = newCount
        if (emailish) emailTokens[w] = newCount
        if (newCount < DICTIONARY_PROMOTION_THRESHOLD) unpromotedWords.add(w)
        else unpromotedWords.remove(w)
        if (newCount >= DICTIONARY_PROMOTION_THRESHOLD) injectPersonal(w)
        if (prev1.isNotEmpty()) {
            userBigram.merge("${prev1.lowercase()}|$w", 1, Int::plus)
            indexBigram(prev1.lowercase(), w, 1, replace = false)
        }
        if (prev2.isNotEmpty() && prev1.isNotEmpty()) {
            userTrigram.merge("${prev2.lowercase()}|${prev1.lowercase()}|$w", 1, Int::plus)
            indexTrigram(prev2.lowercase(), prev1.lowercase(), w, 1, replace = false)
        }
        // A6: the predictions LRU keyed on (prefix, context) goes stale the
        // moment any count moves. Clearing all 512 entries per committed word
        // (the 1.6.6 fix) was correct but threw away every in-flight entry,
        // so the cache never survived a space press. Bumping a version that
        // is part of the cache KEY gives the same correctness — stale entries
        // are simply unreachable — at O(1), and repeat lookups within one
        // version still hit.
        modelVersion++
        if (topUnigramDirty != Int.MAX_VALUE) topUnigramDirty++
        evictPersonalMapsIfNeeded()
    }

    /** Amortized cap on the personal maps: once a map exceeds its limit, the
     *  lowest-count 10% is dropped. Runs O(n log n) only on the rare crossing
     *  (~once per thousand learns), so the per-keystroke cost stays O(1). */
    private fun evictPersonalMapsIfNeeded() {
        if (userUnigram.size > MAX_PERSONAL_UNIGRAMS) {
            val doomed = userUnigram.entries
                .sortedBy { it.value }
                .take(MAX_PERSONAL_UNIGRAMS / 10)
            for (e in doomed) {
                if (userUnigram.remove(e.key, e.value)) {
                    unpromotedWords.remove(e.key)
                    emailTokens.remove(e.key)
                }
            }
            topUnigramDirty = Int.MAX_VALUE
        }
        if (userBigram.size > MAX_PERSONAL_BIGRAMS) {
            val doomed = userBigram.entries
                .sortedBy { it.value }
                .take(MAX_PERSONAL_BIGRAMS / 10)
            for (e in doomed) {
                if (!userBigram.remove(e.key, e.value)) continue
                val sep = e.key.indexOf('|')
                if (sep <= 0) continue
                val p = e.key.substring(0, sep)
                val w = e.key.substring(sep + 1)
                bigramByFirst[p]?.let { m ->
                    m.remove(w, e.value)
                    if (m.isEmpty()) bigramByFirst.remove(p, m)
                }
            }
        }
        if (userTrigram.size > MAX_PERSONAL_TRIGRAMS) {
            val doomed = userTrigram.entries
                .sortedBy { it.value }
                .take(MAX_PERSONAL_TRIGRAMS / 10)
            for (e in doomed) {
                if (!userTrigram.remove(e.key, e.value)) continue
                val first = e.key.indexOf('|')
                val last = e.key.lastIndexOf('|')
                if (first <= 0 || last <= first) continue
                val prefix = e.key.substring(0, last)
                val w = e.key.substring(last + 1)
                trigramByFirst2[prefix]?.let { m ->
                    m.remove(w, e.value)
                    if (m.isEmpty()) trigramByFirst2.remove(prefix, m)
                }
            }
        }
    }

    /**
     * Main entry point.
     * @param prefix letters typed so far for the current word ("" for next-word prediction)
     * @param prev1 previous committed word, "" if none
     * @param prev2 word before that, "" if none
     * @param appPackage current host app for per-app boosts ("" to skip)
     */
    fun predict(
        prefix: String,
        prev1: String,
        prev2: String,
        appPackage: String = "",
        maxResults: Int = 5
    ): List<Suggestion> {
        if (!isReady) return emptyList()
        val p1 = prev1.lowercase()
        val p2 = prev2.lowercase()
        // modelVersion is part of the key: learn() invalidates by making
        // older entries unreachable instead of clearing all 512 of them.
        val cacheKey = "$modelVersion\u0001$prefix\u0001$p1\u0001$p2\u0001$appPackage"
        predictionCache.get(cacheKey)?.let { return it }

        val result = if (prefix.isEmpty()) {
            predictNextWord(p1, p2, appPackage, maxResults)
        } else {
            predictCompletion(prefix.lowercase(), p1, p2, appPackage, maxResults)
        }
        predictionCache.put(cacheKey, result)
        return result
    }

    // ---- internals -------------------------------------------------------

    private fun predictCompletion(
        prefix: String,
        p1: String,
        p2: String,
        appPackage: String,
        maxResults: Int
    ): List<Suggestion> {
        val candidates = trie.completions(prefix, cap = 48)
        val scored = ArrayList<Suggestion>(candidates.size + 8)

        for ((word, freq) in candidates) {
            scored += Suggestion(word, score(word, freq, p1, p2, appPackage))
        }
        // Contextual candidates the raw-frequency cut may have missed: the
        // previous word's likeliest continuations that start with the
        // prefix. "see you t" wants "tomorrow", which is nowhere near the
        // top 48 "t" words by bare frequency but is the top continuation of
        // "you" — score() then ranks it on its context-aware frequency.
        val lm = languageModel
        if (lm != null && p1.isNotEmpty()) {
            for ((word, _) in lm.topContinuations(p1, 40)) {
                if (!word.startsWith(prefix) || word == prefix) continue
                if (candidates.any { it.first == word }) continue
                val freq = trie.frequency(word)
                if (freq <= 0L) continue
                scored += Suggestion(word, score(word, freq, p1, p2, appPackage))
            }
        }
        // A user-coined word that isn't in the base dictionary can still be offered.
        // Only words BELOW the promotion threshold are scanned — anything at or
        // above it lives in the trie already and arrives via completions().
        for (word in unpromotedWords) {
            val count = userUnigram[word] ?: continue
            // Emails are served by emailCompletions() in email context only —
            // never as ordinary word completions in arbitrary fields.
            if ('@' in word) continue
            if (word.startsWith(prefix) && word != prefix && trie.frequency(word) == 0L) {
                // ln-scaled like score()'s own unigram term: a raw linear
                // "+ count" on top of a log-scale score let one much-typed
                // coinage bury every real completion.
                scored += Suggestion(
                    word,
                    score(word, 1L, p1, p2, appPackage) + ln(1.0 + count) * WEIGHT_UNIGRAM
                )
            }
        }
        // Typing the exact word should always be an option (center slot).
        if (prefix.length > 1) {
            scored += Suggestion(prefix, RAW_TYPED_BONUS)
        }
        // Fat-finger completions: when the prefix itself completes to little,
        // try single adjacent-key substitutions ("becahse" -> "because").
        // This is what keeps tap-typing predictions useful mid-word.
        if (prefix.length >= 3 && candidates.size < 3) {
            val seen = scored.map { it.text }.toHashSet()
            for (i in prefix.indices) {
                val neighbors = NEIGHBORS[prefix[i]] ?: continue
                for (n in neighbors) {
                    val variant = prefix.substring(0, i) + n + prefix.substring(i + 1)
                    for ((word, freq) in trie.completions(variant, cap = 6)) {
                        if (word in seen) continue
                        seen += word
                        scored += Suggestion(
                            word,
                            score(word, freq, p1, p2, appPackage) - FAT_FINGER_PENALTY
                        )
                    }
                }
            }
        }
        return scored.sortedByDescending { it.score }
            .distinctBy { it.text }
            .filter { it.text == prefix || offerable(it.text) }
            .take(maxResults)
    }

    /** Words the bar may volunteer: everything except [OffensiveWords] the
     *  user has not adopted themselves. */
    private fun offerable(lower: String): Boolean =
        OffensiveWords.allowedFor(lower, userUnigram[lower] ?: 0)

    // ---- top-unigram cache -----------------------------------------------

    private var topUnigramCache: List<Pair<String, Int>> = emptyList()
    private var topUnigramDirty = Int.MAX_VALUE   // force a build on first use

    /** Strongest personal unigrams, rebuilt lazily. A word that just crossed
     *  into the top slice can wait a couple of dozen keystrokes to appear —
     *  rebuilding on every learn would reintroduce the O(n) sort we just
     *  removed. */
    private fun topUnigrams(): List<Pair<String, Int>> {
        if (topUnigramDirty >= TOP_UNIGRAM_REBUILD_EVERY) {
            topUnigramCache = userUnigram.entries
                .asSequence()
                .filter { it.value >= 2 }
                .sortedByDescending { it.value }
                .take(TOP_UNIGRAM_COUNT)
                .map { it.key to it.value }
                .toList()
            topUnigramDirty = 0
        }
        return topUnigramCache
    }

    private fun predictNextWord(
        p1: String,
        p2: String,
        appPackage: String,
        maxResults: Int
    ): List<Suggestion> {
        val scores = HashMap<String, Double>(64)

        // P1: two indexed map reads instead of startsWith-scans over all
        // ~16k n-gram keys — this runs on the main thread after every space.
        // Suppressed (proven-typo / user-removed) words are skipped before
        // merging: their n-gram entries may still exist between a strike and
        // the next scrub, and suppression must hide them regardless.
        // Learned emails never appear as next-word predictions either — they
        // are served by emailCompletions() in email context only.
        // Exact-context floor: a continuation seen even ONCE after this exact
        // previous word (or word pair) must outrank the context-free fillers
        // below — the strongest STARTER_WORDS entry scores ~3.86
        // (ln(5M)*0.25) and a personal unigram only passes ~5.5 after a dozen
        // typed sightings (ln(1+count)*WEIGHT_UNIGRAM). Without the floor a
        // one-sighting bigram (1*3.0 = 3.0) lost to ALL twelve starters and
        // to any unigram typed 5+ times, so next-word predictions looked
        // context-free until a phrase had been typed repeatedly. Gboard
        // surfaces a one-sighting continuation immediately; the floor
        // reproduces that. Trigram floor > bigram floor keeps the 3-word
        // phrase continuation above the 2-word one at equal counts.
        val trigramBacked = HashSet<String>()
        if (p2.isNotEmpty() && p1.isNotEmpty()) {
            trigramByFirst2["$p2|$p1"]?.forEach { (w, count) ->
                if (typoSuppressions.contains(w) || '@' in w) return@forEach
                scores.merge(w, count * WEIGHT_TRIGRAM + CONTEXT_FLOOR_TRIGRAM, Double::plus)
                if (count >= 2) trigramBacked += w
            }
        }
        if (p1.isNotEmpty()) {
            bigramByFirst[p1]?.forEach { (w, count) ->
                if (typoSuppressions.contains(w) || '@' in w) return@forEach
                // When a meaningful trigram (seen at least twice in exactly
                // this two-word context) already speaks for this word, halve
                // the bigram echo — the 3-word phrase continuation should
                // outrank the looser 2-word one, not stack with it. The floor
                // stays: exact context is exact context.
                val damp = if (w in trigramBacked) 0.5 else 1.0
                scores.merge(w, count * WEIGHT_BIGRAM * damp + CONTEXT_FLOOR_BIGRAM, Double::plus)
            }
        }
        // General-English continuations from the bundled bigram model. This
        // is what makes a FRESH install predict "you" after "thank" and
        // "to" after "want": before it, only phrases this user had already
        // typed could ever appear here. Scored on ln P(word|prev), so the
        // most predictable continuations land around 8-9 points — above a
        // personal bigram seen once (5.5) but below one seen twice (8.5):
        // the user's own habits still win as soon as they are habits.
        val lm = languageModel
        if (lm != null && p1.isNotEmpty()) {
            for ((w, p) in lm.topContinuations(p1, LM_NEXT_CANDIDATES)) {
                if (typoSuppressions.contains(w)) continue
                // Domain shift: the bundled counts are web text, the keyboard
                // types messages. P_chat(w|prev) ≈ P_web(w|prev) x
                // P_chat(w)/P_web(w), and that ratio is only far from 1 for a
                // handful of conversational words — "are you" is the 8th
                // continuation of "are" on the web and the first in a chat.
                val s = (ln(p) + BaseLanguageModel.LOG_OFFSET + (CHAT_LOG_RATIO[w] ?: 0.0)) * LM_NEXT_W
                if (s > 0.0) scores.merge(w, s, Double::plus)
            }
        }
        // Personal unigrams are a PERSONALISATION signal, not a candidate
        // source. A context-backed candidate the user also types a lot gets
        // a modest boost; a word with no contextual support only ever fills
        // a slot nothing contextual wants, at a capped score.
        //
        // This used to add ln(1+count) * WEIGHT_UNIGRAM (2.2) for every top
        // unigram regardless of context. After a few weeks of use the three
        // most-typed words sit at ~2000 sightings, i.e. ~17 points — above
        // the strongest bundled continuation (~9) and above a personal bigram
        // seen three times — so the bar showed the same three words after
        // EVERY space, whatever the sentence ("you, the, just"). The earlier
        // move from linear to ln-scaled counts had only softened this; the
        // fix is that context-free evidence must never outrank context.
        //
        // Only the strongest unigrams are considered. Scanning all ~6k every
        // time was the last O(n) walk on the post-space main-thread path.
        for ((word, count) in topUnigrams()) {
            if (typoSuppressions.contains(word) || '@' in word) continue
            val lw = ln(1.0 + count)
            if (scores.containsKey(word)) {
                scores.merge(word, kotlin.math.min(lw * UNIGRAM_CTX_BOOST, UNIGRAM_CTX_CAP), Double::plus)
            } else {
                scores[word] = kotlin.math.min(lw * UNIGRAM_FILLER_W, UNIGRAM_FILLER_CAP)
            }
        }
        // Sentence starters are for when there is NO context: at the start
        // of a field, or after a word nothing knows. Blending them in on top
        // of a real continuation row put "the"/"a"/"it" above "you" after
        // "thank" and above "for" after "good" — the model had the right
        // answer and the filler out-voted it.
        val hasContext = trigramBacked.isNotEmpty() ||
            (p1.isNotEmpty() && bigramByFirst[p1]?.isNotEmpty() == true) ||
            (lm != null && p1.isNotEmpty() && lm.hasRow(p1))
        if (!hasContext || scores.size < 3) {
            for ((word, freq) in STARTER_WORDS) {
                if (typoSuppressions.contains(word)) continue
                if (scores.size < 64 || scores.containsKey(word)) {
                    scores.merge(word, ln(freq.toDouble()) * 0.25, Double::plus)
                }
            }
        }

        val appBoosts = appPackage.let { appVocab[it] }
        val styled = scores.entries.map { (w, s) ->
            var final = s
            appBoosts?.get(w)?.let { final += ln(1.0 + it) * 1.2 }
            styleBoosts[w]?.let { final *= it }
            Suggestion(w, final)
        }
        return styled.sortedByDescending { it.score }
            .filter { offerable(it.text) }
            .take(maxResults)
    }

    /**
     * Learned email addresses matching [prefix], best (most-used) first.
     * Matches on the full address ("john.doe@gm") or just the local part
     * ("joh"); an empty prefix returns the user's top addresses, which is
     * what an empty email field should offer. Cheap: scans only the small
     * [emailTokens] index, suppression-filtered, capped at [limit].
     */
    fun emailCompletions(prefix: String, limit: Int = 3): List<String> {
        if (!isReady || emailTokens.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = ArrayList<Pair<String, Int>>(4)
        for ((token, count) in emailTokens) {
            if (typoSuppressions.contains(token)) continue
            if (p.isEmpty() || token.startsWith(p) ||
                token.substringBefore('@').startsWith(p)
            ) {
                out += token to count
            }
        }
        return out.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Email-shaped token: exactly one '@' with something on both sides, no
     *  scheme, no whitespace. Deliberately loose about the domain — the point
     *  is to keep "jane2@work-mail.example" learnable, not to validate. */
    private fun isEmailToken(w: String): Boolean {
        val at = w.indexOf('@')
        if (at <= 0 || at != w.lastIndexOf('@') || at >= w.length - 1) return false
        return !w.contains("://") && !w.any { it.isWhitespace() }
    }

    private fun score(word: String, baseFreq: Long, p1: String, p2: String, appPackage: String): Double {
        // Context-aware frequency: after a previous word the bundled bigram
        // model knows, a candidate is scored by how often it actually
        // FOLLOWS that word (backing off to a discounted unigram count when
        // the pair is unseen). "I t" -> "think" now beats "the".
        val lm = languageModel
        val eff = if (lm != null && p1.isNotEmpty()) lm.effectiveFrequency(p1, word, baseFreq) else baseFreq
        var s = ln(eff.toDouble() + 1.0)
        userUnigram[word]?.let { s += ln(1.0 + it) * WEIGHT_UNIGRAM }
        if (p1.isNotEmpty()) {
            userBigram["$p1|$word"]?.let { s += ln(1.0 + it) * WEIGHT_BIGRAM }
        }
        if (p2.isNotEmpty() && p1.isNotEmpty()) {
            userTrigram["$p2|$p1|$word"]?.let { s += ln(1.0 + it) * WEIGHT_TRIGRAM }
        }
        appVocab[appPackage]?.get(word)?.let { s += ln(1.0 + it) * 1.2 }
        styleBoosts[word]?.let { s *= it }
        return s
    }

    companion object {
        private const val WEIGHT_UNIGRAM = 2.2
        private const val WEIGHT_BIGRAM = 3.0
        private const val WEIGHT_TRIGRAM = 4.0

        /** Weight on (ln P(word|prev) + 9) for the bundled bigram model in
         *  next-word prediction. 1.1 puts the most predictable continuations
         *  (P ≈ 0.4) at ~8.8 — between a personal bigram seen once and one
         *  seen twice. */
        private const val LM_NEXT_W = 1.1
        /** How many bundled continuations of the previous word are pooled
         *  into next-word prediction. */
        private const val LM_NEXT_CANDIDATES = 24
        /** The bundled model's vote inside the autocorrect blend. Blend scale:
         *  one adjacent-key substitution costs 0.5, so at 0.15 the strongest
         *  possible context (~1.3) can flip a close call but not re-spell a
         *  clean word against the keys. */
        private const val LM_CORRECT_W = 0.15
        /** Ceiling on the whole context bonus in autocorrect (was 1.2 with
         *  personal n-grams only). */
        private const val CONTEXT_BONUS_CAP = 1.5

        /** Flat bonus for matching the EXACT previous word(s) in next-word
         *  prediction, added on top of count*WEIGHT so a one-sighting
         *  continuation beats context-free fillers from the first use.
         *  Bigram: 3.0 + 2.5 = 5.5 > every starter word (~3.86 max) and any
         *  unigram typed fewer than ~12 times. Trigram sits 2.0 above the
         *  bigram floor so the longer match wins at equal counts. */
        private const val CONTEXT_FLOOR_BIGRAM = 2.5
        private const val CONTEXT_FLOOR_TRIGRAM = 3.5
        /** Per ln(1+count) of personal usage ADDED to a candidate that context
         *  already proposes, capped at [UNIGRAM_CTX_CAP]: enough to break a
         *  near-tie toward a word the user actually types, never enough to
         *  reorder the model's ranking — everyone's count for "to" and "the"
         *  is high, which says nothing about this user. */
        private const val UNIGRAM_CTX_BOOST = 0.3
        private const val UNIGRAM_CTX_CAP = 1.5
        /** Per ln(1+count) for a personal word with NO contextual support,
         *  capped at [UNIGRAM_FILLER_CAP] — below the weakest bundled
         *  continuation worth showing ((ln P + 9) * LM_NEXT_W at P ≈ 0.002)
         *  and below every context floor, so fillers only fill. */
        private const val UNIGRAM_FILLER_W = 0.35
        private const val UNIGRAM_FILLER_CAP = 2.4
        /** ln(P_chat(w) / P_web(w)) for the words where the messaging
         *  domain differs most from the bundled web counts: second and
         *  first person, and the chat spellings. Applied to the bundled
         *  model's next-word continuations only — personal n-grams already
         *  ARE the user's domain. */
        private val CHAT_LOG_RATIO: Map<String, Double> = hashMapOf(
            "you" to 1.6, "i" to 1.4, "me" to 1.4, "u" to 1.4, "ya" to 1.0,
            "we" to 0.9, "my" to 0.9, "your" to 0.9, "us" to 0.7, "our" to 0.5,
            "i'm" to 1.2, "you're" to 1.0, "i'll" to 1.0, "i've" to 0.8,
            "lol" to 1.0, "yeah" to 1.0, "ok" to 0.8, "okay" to 0.8,
            "gonna" to 0.8, "wanna" to 0.8, "tomorrow" to 0.6, "tonight" to 0.6,
            "later" to 0.5, "home" to 0.4, "there" to 0.3, "here" to 0.3
        )

        /** A1: cost of one adjacent-letter transposition ("thsi" -> "this").
         *  Offline vs the real dictionary: 1.0 scored 21/32, 0.5 scored 30/32. */
        private const val TRANSPOSITION_COST = 0.5
        /** A3: inserting/deleting a dropped apostrophe ("dont" -> "don't"). */
        private const val APOSTROPHE_EDIT_COST = 0.3
        /** A2: frequency's vote in the blended correction score. */
        private const val FREQ_BLEND_W = 0.25

        /** Context-override rule: how far a candidate must beat the TYPED
         *  word's own baseline (typed word scored at distance 0 with its real
         *  frequency and context bonus) before a known/short word is
         *  auto-corrected. Deliberately stronger than the classic 1.0
         *  runner-up margin — re-spelling a real word the user typed is a
         *  much bolder claim than fixing an unknown string. */
        private const val CONTEXT_OVERRIDE_MARGIN = 2.0
        /** Corpus-count floors an auto-committed correction must clear,
         *  by typed length (see correctionProbe). ~11k words clear 2000. */
        private const val AUTOCOMMIT_FLOOR_SHORT = 2000L
        private const val AUTOCOMMIT_FLOOR_MID = 600L
        private const val AUTOCOMMIT_FLOOR_LONG = 100L

        /** forceMaxDist for 2-letter corrections: 1.0 weighted ≈ one
         *  adjacent-key substitution (or two half-cost neighbor swaps at
         *  most). The dictionary's junk 2-letter population makes anything
         *  wider pure noise. */
        private const val SHORT_WORD_MAX_DIST = 1

        /** How many personal unigrams the next-word blend considers. Beyond
         *  this a word cannot out-score the n-gram contributions anyway. */
        private const val TOP_UNIGRAM_COUNT = 300
        /** Learns to absorb before the top-unigram slice is rebuilt. */
        private const val TOP_UNIGRAM_REBUILD_EVERY = 24
        /** A5: suppression verdicts expire — 30 days. */
        private const val SUPPRESSION_TTL_MS = 30L * 86_400_000L

        /** A6: the raw typed string stays ON the bar, but no longer pinned to
         *  slot 1 by a 1e6 wall — a clearly-better correction now takes the
         *  position the thumb actually goes to. */
        private const val RAW_TYPED_BONUS = 8.0
        /** A word must be typed this often before it joins the dictionary.
         *  Raised from 3: the same finger-slip typo easily repeats 3 times;
         *  4 plus the suppression of corrected words keeps the dictionary clean. */
        private const val DICTIONARY_PROMOTION_THRESHOLD = 4
        /** Usage count at which an UNCONFIRMED word is treated as deliberate
         *  vocabulary — immune from autocorrection and eligible for trie
         *  promotion. Confirmed words (noteConfirmed) clear the lower
         *  promotion threshold; everyone else must pass this higher bar,
         *  which a repeated finger-slip typo essentially never reaches
         *  without being corrected away first. */
        private const val STRONG_USAGE_THRESHOLD = 8
        /** Caps on the personal maps; on overflow the lowest-count 10% is
         *  evicted (amortized, see evictPersonalMapsIfNeeded). */
        private const val MAX_PERSONAL_UNIGRAMS = 10_000
        private const val MAX_PERSONAL_BIGRAMS = 32_000
        private const val MAX_PERSONAL_TRIGRAMS = 24_000
        /** Tapping a word in the prediction bar is explicit "this is my word"
         *  intent — worth an instant promotion on first sight. */
        private const val SELECTION_BOOST = 4
        /** Score discount for one-adjacent-key completion variants. */
        private const val FAT_FINGER_PENALTY = 1.2

        /** Physical adjacency for fat-finger-aware correction costs.
         *
         *  NOT a constant: it must describe the layout actually on screen.
         *  With a hardcoded QWERTY table an AZERTY user's every "a" would be
         *  scored as adjacent to "s" (true on QWERTY, wrong on theirs), and
         *  the correction search would spend its budget on candidates their
         *  fingers could never have produced. Rebuilt by [setKeyboardRows]
         *  whenever the layout changes. */
        @Volatile
        private var NEIGHBORS: Map<Char, Set<Char>> = buildNeighbors(
            listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        )

        /** Floor on a run-on split's score. ln(freq) based, so 8.0 means the
         *  weaker half is a genuinely common word (roughly the top few
         *  thousand), not a rare one that merely exists in the dictionary.
         *  Raised from 6.0: at 6.0 the bar was clearing on words nobody
         *  writes, which is how ordinary names ended up cut in two. */
        private const val SPLIT_MIN_SCORE = 8.0

        /** How far the best split must beat the runner-up. Below this the
         *  token is genuinely ambiguous and is left exactly as typed —
         *  silently splitting a word the wrong way is worse than not
         *  splitting it, because the user has to undo two words instead of
         *  fixing one. A real run-on has one obviously right cut; a name that
         *  happens to be divisible has several mediocre ones, so the margin is
         *  what separates the two cases. */
        private const val SPLIT_MARGIN = 2.0

        /** Every one- and two-letter word a missed space can plausibly leave
         *  behind.
         *
         *  Deliberately a closed list rather than a dictionary lookup. The
         *  shipped 114k dictionary is a word LIST — it contains "aa", "ab",
         *  "ae", "ai", "al", "ar", "ba", "bi", "de", "el", "es", "et", "ka",
         *  "la", "li", "na", "od", "oe", "pa", "re", "ta", "ti", "ut", "xi"
         *  and dozens more, at frequencies high enough to clear any threshold.
         *  Every one of those is a way for an unrecognised name to be cut in
         *  half. None of them is a word an English sentence actually contains,
         *  so membership is the right test and frequency is not. */
        private val SHORT_WORDS = hashSetOf(
            "a", "i",
            "am", "an", "as", "at", "be", "by", "do", "go", "he", "hi", "if",
            "in", "is", "it", "me", "my", "no", "of", "oh", "ok", "on", "or",
            "so", "to", "up", "us", "we"
        )

        /** Point the correction model at a different letter arrangement.
         *  Rows are top-to-bottom, lowercase, letters only. */
        fun setKeyboardRows(rows: List<String>) {
            val clean = rows.map { r -> r.filter { it.isLetter() } }.filter { it.isNotEmpty() }
            if (clean.size >= 2) NEIGHBORS = buildNeighbors(clean)
        }

        private fun buildNeighbors(rows: List<String>): Map<Char, Set<Char>> {
            val map = HashMap<Char, MutableSet<Char>>()
            fun add(a: Char, b: Char) {
                map.getOrPut(a) { HashSet() }.add(b)
                map.getOrPut(b) { HashSet() }.add(a)
            }
            for (r in rows.indices) {
                val row = rows[r]
                for (i in row.indices) {
                    if (i > 0) add(row[i], row[i - 1])
                    if (i < row.length - 1) add(row[i], row[i + 1])
                    if (r > 0) {
                        val above = rows[r - 1]
                        // Rows are staggered; a key touches the two above it.
                        val ai = i + (if (r == 2) 1 else 0)
                        for (j in (ai - 1)..ai) {
                            if (j in above.indices) add(row[i], above[j])
                        }
                    }
                }
            }
            return map
        }

        /** A3: the ~120 contractions English actually uses, at corpus-realistic
         *  frequencies (matching the base dictionary's count scale). "don't" is
         *  genuinely a top-200 word; its absence made the keyboard incapable of
         *  everyday English. */
        private val CONTRACTIONS: List<Pair<String, Long>> = listOf(
            "don't" to 9_000_000L, "it's" to 9_500_000L, "i'm" to 7_500_000L,
            "that's" to 6_500_000L, "can't" to 5_000_000L, "didn't" to 4_500_000L,
            "doesn't" to 4_200_000L, "you're" to 4_000_000L, "i'll" to 4_000_000L,
            "won't" to 3_800_000L, "isn't" to 3_200_000L, "what's" to 3_000_000L,
            "there's" to 3_000_000L, "couldn't" to 2_800_000L, "they're" to 2_600_000L,
            "we're" to 2_400_000L, "i've" to 2_600_000L, "wasn't" to 2_600_000L,
            "shouldn't" to 2_200_000L, "wouldn't" to 2_400_000L, "haven't" to 1_800_000L,
            "you'll" to 2_000_000L, "we'll" to 2_000_000L, "he's" to 2_200_000L,
            "she's" to 1_800_000L, "let's" to 2_600_000L, "who's" to 1_400_000L,
            "aren't" to 1_600_000L, "weren't" to 1_500_000L, "hasn't" to 1_300_000L,
            "hadn't" to 900_000L, "you've" to 1_400_000L, "we've" to 1_500_000L,
            "they've" to 1_200_000L, "could've" to 1_300_000L, "should've" to 1_400_000L,
            "would've" to 1_500_000L, "might've" to 700_000L, "must've" to 600_000L,
            "i'd" to 2_000_000L, "you'd" to 1_300_000L, "he'd" to 1_000_000L,
            "she'd" to 800_000L, "we'd" to 900_000L, "they'd" to 1_000_000L,
            "it'd" to 500_000L, "that'd" to 500_000L, "there'd" to 400_000L,
            "it'll" to 700_000L, "that'll" to 600_000L, "there'll" to 300_000L,
            "he'll" to 900_000L, "she'll" to 700_000L, "they'll" to 900_000L,
            "who'll" to 200_000L, "how's" to 600_000L, "where's" to 500_000L,
            "when's" to 400_000L, "why's" to 300_000L, "here's" to 1_200_000L,
            "who've" to 200_000L, "what've" to 150_000L, "who'd" to 300_000L,
            "what'd" to 400_000L, "how'd" to 500_000L, "what'll" to 200_000L,
            "everybody's" to 200_000L, "someone's" to 250_000L, "anyone's" to 200_000L,
            "nobody's" to 250_000L, "nothing's" to 300_000L, "something's" to 350_000L,
            "everything's" to 300_000L, "one's" to 400_000L, "o'clock" to 800_000L,
            "y'all" to 700_000L, "c'mon" to 350_000L, "ma'am" to 400_000L,
            "'cause" to 600_000L, "'til" to 300_000L, "'bout" to 250_000L
        )

        /** A3: the top contraction typos, hard-mapped at space-commit. These
         *  strings are not valid standalone English words ("wont"/"cant" are
         *  technically dictionary entries but essentially never intended —
         *  same trade Gboard makes), so mapping them is near-zero-risk. The
         *  undo path covers the exceptions. */
        private val CONTRACTION_FIXES: Map<String, String> = mapOf(
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
            "cant" to "can't", "couldnt" to "couldn't", "wont" to "won't",
            "wouldnt" to "wouldn't", "shouldnt" to "shouldn't", "isnt" to "isn't",
            "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
            "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
            "im" to "I'm", "ive" to "I've", "youre" to "you're",
            "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
            "thats" to "that's", "whats" to "what's", "theres" to "there's",
            "heres" to "here's", "hes" to "he's", "shes" to "she's",
            "whos" to "who's", "hows" to "how's", "wheres" to "where's",
            "whens" to "when's", "theyre" to "they're",
            "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
            "weve" to "we've", "yall" to "y'all", "oclock" to "o'clock",
            "cmon" to "c'mon", "itll" to "it'll", "thatll" to "that'll",
            "couldve" to "could've", "shouldve" to "should've",
            "wouldve" to "would've", "mightve" to "might've", "mustve" to "must've"
        )

        /** P0: texting abbreviations — the corpus's formal register underweights
         *  "omw"/"hbu"/"rn", which a chat keyboard types constantly.
         *
         *  Scale matters. These used to be injected at 300k-1.3M, i.e. as
         *  common as "time" and "have" and 20-300x their own dictionary
         *  counts ("omg" 4.7k, "idk" 6.2k). At that weight the glide decoder
         *  produced "mfw" for a gesture that was plainly "me" and next-word
         *  prediction offered "wtf" after ordinary words. They now sit at a
         *  chat-realistic 5-10x their dictionary counts: still top-2000
         *  words, still completions for "om"/"id"/"tb", no longer able to
         *  outrank everyday English on a coin-flip gesture. (Trie.insert
         *  keeps the larger of dictionary and injected count.) */
        private val TEXTING: List<Pair<String, Long>> = listOf(
            "omw" to 30_000L, "hbu" to 6_000L, "wbu" to 3_000L,
            "rn" to 35_000L, "af" to 25_000L, "ty" to 10_000L,
            "thx" to 8_000L, "tysm" to 3_000L, "pls" to 15_000L,
            "plz" to 6_000L, "tbh" to 40_000L, "idk" to 50_000L,
            "idc" to 5_000L, "ikr" to 8_000L, "ngl" to 15_000L,
            "ttyl" to 3_000L, "lmk" to 12_000L, "brb" to 8_000L,
            "gtg" to 6_000L, "imo" to 12_000L, "imho" to 3_000L,
            "fwiw" to 2_000L, "smh" to 15_000L, "wdym" to 3_000L,
            "hby" to 1_500L, "fr" to 20_000L, "bc" to 15_000L,
            "cuz" to 12_000L, "tho" to 25_000L, "tmrw" to 4_000L,
            "tmr" to 3_000L, "gn" to 5_000L, "gm" to 6_000L,
            "hf" to 1_500L, "gl" to 2_000L, "wb" to 1_500L,
            "wya" to 3_000L, "wyd" to 6_000L, "ofc" to 6_000L,
            "msg" to 6_000L, "txt" to 4_000L, "vid" to 4_000L,
            "lowkey" to 6_000L, "highkey" to 1_500L, "sus" to 5_000L,
            "slay" to 4_000L, "dm" to 8_000L, "dms" to 3_000L,
            "irl" to 6_000L, "tfw" to 1_500L, "mfw" to 1_500L,
            "bruh" to 10_000L, "sheesh" to 3_000L, "oop" to 2_000L,
            "lmaooo" to 1_500L, "lmao" to 60_000L, "lmfao" to 15_000L,
            "rofl" to 3_000L, "omg" to 45_000L, "omfg" to 4_000L,
            "wtf" to 40_000L, "wth" to 5_000L, "ffs" to 5_000L,
            "smfh" to 1_500L, "istg" to 3_000L
        )

        /** A3: direct contraction fix for a space-committed word, null if none.
         *  Skipped when the user has deliberately typed the raw form enough
         *  times that it became learned vocabulary. */
        fun contractionFixFor(rawLower: String, userCount: Int = 0): String? {
            if (userCount >= 3) return null
            return CONTRACTION_FIXES[rawLower]
        }

        /** Frequent sentence starters used when no personal context exists yet. */
        private val STARTER_WORDS = listOf(
            "the" to 5_000_000L, "i" to 4_500_000L, "a" to 3_500_000L,
            "it" to 2_500_000L, "that" to 2_000_000L, "and" to 1_800_000L,
            "is" to 1_600_000L, "for" to 1_400_000L, "on" to 1_200_000L,
            "this" to 1_100_000L, "with" to 1_000_000L, "you" to 950_000L
        )

        @Volatile
        private var shared: FastEngine? = null

        fun shared(context: Context): FastEngine {
            return shared ?: synchronized(this) {
                shared ?: FastEngine(context.applicationContext).also { shared = it }
            }
        }
    }
}

/** Result of [FastEngine.autoCorrectWithMargin]: the winning correction, its
 *  blended score, and how far it beat the runner-up ([Double.POSITIVE_INFINITY]
 *  when it was the only candidate). */
data class AutoFix(val word: String, val score: Double, val margin: Double)
