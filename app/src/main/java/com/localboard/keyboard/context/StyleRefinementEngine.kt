package com.localboard.keyboard.context

/**
 * Watches every committed sentence and keeps the StyleProfile converging
 * toward how the user actually writes *now* — with exponential decay so
 * old habits fade as new ones form. All in-memory; the ContextEngine
 * persists snapshots periodically.
 */
class StyleRefinementEngine {

    private var emaSentenceLength = 0f
    private var emaEmojiRate = 0f
    private var emaFormality = 0.5f
    private var lowercaseStarts = 0
    private var observedSentences = 0
    private var initialized = false

    private val wordCounter = HashMap<String, Int>()
    private val emojiCounter = HashMap<String, Int>()

    /** Unlearn a word (confirmed typo / explicit removal) so the next profile
     *  refresh stops boosting it. */
    fun removeWord(w: String) {
        wordCounter.remove(w.lowercase())
    }

    fun restore(profile: StyleProfile) {
        emaSentenceLength = profile.avgSentenceLength
        emaEmojiRate = profile.emojiPerSentence
        emaFormality = profile.formality
        observedSentences = profile.sentencesAnalyzed
        wordCounter.clear()
        wordCounter.putAll(profile.topWords)
        emojiCounter.clear()
        profile.topEmojis.forEachIndexed { i, e -> emojiCounter[e] = 8 - i }
        initialized = true
    }

    /** Observe one committed text segment; returns an updated profile when
     *  enough new evidence has accumulated, otherwise the current one. */
    fun observe(text: String, current: StyleProfile): StyleProfile {
        if (!initialized) restore(current)
        val sentences = text.split(SENTENCE_SPLIT).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return current

        for (sentence in sentences) {
            val words = WORD_RE.findAll(sentence).map { it.value }.toList()
            if (words.isEmpty()) continue
            observedSentences++

            emaSentenceLength = decay(emaSentenceLength, words.size.toFloat())
            if (sentence.trim().firstOrNull()?.isLowerCase() == true) lowercaseStarts++

            val emojis = EMOJI_RE.findAll(sentence).toList()
            emaEmojiRate = decay(emaEmojiRate, emojis.size.toFloat())
            emojis.forEach { emojiCounter.merge(it.value, 1, Int::plus) }

            var casual = 0
            var formal = 0
            for (w in words) {
                val lw = w.lowercase()
                wordCounter.merge(lw, 1, Int::plus)
                if (lw in CASUAL) casual++
                if (lw in FORMAL) formal++
            }
            if (casual + formal > 0) {
                emaFormality = decay(emaFormality, formal.toFloat() / (casual + formal))
            }
        }

        // Emit a refreshed profile every REFRESH_EVERY sentences.
        return if (observedSentences % REFRESH_EVERY == 0) {
            // Bound the raw counters: every refresh keeps only the most-seen
            // entries, so a long-lived session can't grow them without limit.
            if (wordCounter.size > MAX_COUNTER_ENTRIES) {
                val keep = wordCounter.entries.sortedByDescending { it.value }
                    .take(MAX_COUNTER_ENTRIES)
                wordCounter.clear()
                keep.forEach { wordCounter[it.key] = it.value }
            }
            if (emojiCounter.size > MAX_COUNTER_ENTRIES) {
                val keep = emojiCounter.entries.sortedByDescending { it.value }
                    .take(MAX_COUNTER_ENTRIES)
                emojiCounter.clear()
                keep.forEach { emojiCounter[it.key] = it.value }
            }
            current.copy(
                avgSentenceLength = emaSentenceLength.coerceIn(2f, 60f),
                emojiPerSentence = emaEmojiRate,
                capitalizationStyle = if (lowercaseStarts * 2 > observedSentences) 1 else 0,
                formality = emaFormality,
                topWords = wordCounter.entries
                    .filter { it.key.length > 2 }
                    .sortedByDescending { it.value }
                    .take(120)
                    .map { it.toPair() },
                topEmojis = emojiCounter.entries
                    .sortedByDescending { it.value }
                    .take(8)
                    .map { it.key },
                sentencesAnalyzed = observedSentences
            )
        } else current
    }

    private fun decay(old: Float, new: Float): Float =
        old * (1f - ALPHA) + new * ALPHA

    companion object {
        private const val ALPHA = 0.15f
        /** Refresh cadence. Was 4: every refresh pushed new style boosts into
         *  the FastEngine, which evicts the ENTIRE prediction cache — at every
         *  4th observed sentence that cache never lived long enough to hit. */
        private const val REFRESH_EVERY = 25
        /** Hard cap on the raw word/emoji counters (top-N by count kept). */
        private const val MAX_COUNTER_ENTRIES = 5000
        private val WORD_RE = Regex("[A-Za-z][A-Za-z'’\\-]*")
        private val SENTENCE_SPLIT = Regex("[.!?\\n]+")
        private val EMOJI_RE = Regex(
            "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF" +
                "\\uD83E\\uDD00-\\uD83E\\uDDFF\\u2600-\\u27BF]"
        )
        private val CASUAL = setOf(
            "lol", "omg", "btw", "gonna", "wanna", "gotta", "yeah", "nope",
            "kinda", "dunno", "thx", "pls", "idk", "tbh", "ngl", "haha", "nah"
        )
        private val FORMAL = setOf(
            "therefore", "furthermore", "however", "regarding", "sincerely",
            "accordingly", "nevertheless", "thus", "hence", "moreover"
        )
    }
}
