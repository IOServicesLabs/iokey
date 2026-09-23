package com.localboard.keyboard.context

import kotlin.math.min

/**
 * One-shot style analysis of a text corpus (pasted writing, SMS export,
 * or bundled samples). Produces a StyleProfile plus the raw word/n-gram
 * counts used to seed the personal prediction model.
 */
object StyleDistiller {

    data class DistillResult(
        val profile: StyleProfile,
        /** word -> count */
        val unigrams: Map<String, Int>,
        /** "prev|word" -> count */
        val bigrams: Map<String, Int>,
        /** "prev2|prev1|word" -> count */
        val trigrams: Map<String, Int>
    )

    private val WORD_RE = Regex("[A-Za-z][A-Za-z'’\\-]*")
    private val SENTENCE_SPLIT = Regex("[.!?\\n]+")
    private val EMOJI_RE = Regex(
        "[\\uD83C\\uDF00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF" +
            "\\uD83E\\uDD00-\\uD83E\\uDDFF\\u2600-\\u27BF]"
    )

    private val CASUAL_MARKERS = setOf(
        "lol", "omg", "btw", "gonna", "wanna", "gotta", "yeah", "yep", "nope",
        "kinda", "dunno", "cmon", "thx", "pls", "plz", "idk", "tbh", "ngl",
        "bruh", "dude", "haha", "hehe", "nah", "yall", "imma", "ain't"
    )
    private val FORMAL_MARKERS = setOf(
        "therefore", "furthermore", "however", "regarding", "sincerely",
        "accordingly", "nevertheless", "thus", "hence", "moreover",
        "additionally", "consequently", "appreciate", "inquire", "assist"
    )

    fun distill(text: String): DistillResult {
        val sentences = SENTENCE_SPLIT.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val unigrams = HashMap<String, Int>()
        val bigrams = HashMap<String, Int>()
        val trigrams = HashMap<String, Int>()
        val emojiCounts = HashMap<String, Int>()

        var totalWords = 0
        var lowercasedSentences = 0
        var contractions = 0
        var casualHits = 0
        var formalHits = 0

        for (sentence in sentences) {
            val tokens = WORD_RE.findAll(sentence).map { it.value }.toList()
            if (tokens.isEmpty()) continue
            totalWords += tokens.size

            if (sentence.firstOrNull()?.isLowerCase() == true) lowercasedSentences++

            var prev1 = ""
            var prev2 = ""
            for (token in tokens) {
                val w = token.lowercase()
                unigrams.merge(w, 1, Int::plus)
                if (token.contains('\'') || token.contains('’')) contractions++
                if (w in CASUAL_MARKERS) casualHits++
                if (w in FORMAL_MARKERS) formalHits++
                if (prev1.isNotEmpty()) bigrams.merge("$prev1|$w", 1, Int::plus)
                if (prev2.isNotEmpty() && prev1.isNotEmpty()) {
                    trigrams.merge("$prev2|$prev1|$w", 1, Int::plus)
                }
                prev2 = prev1
                prev1 = w
            }

            for (m in EMOJI_RE.findAll(sentence)) {
                emojiCounts.merge(m.value, 1, Int::plus)
            }
        }

        val sentenceCount = sentences.size.coerceAtLeast(1)
        val emojiTotal = emojiCounts.values.sum()
        val styleHits = casualHits + formalHits
        val formality = if (styleHits == 0) 0.5f
        else formalHits.toFloat() / styleHits

        val profile = StyleProfile(
            avgSentenceLength = (totalWords.toFloat() / sentenceCount).coerceIn(2f, 60f),
            emojiPerSentence = emojiTotal.toFloat() / sentenceCount,
            capitalizationStyle = if (lowercasedSentences * 2 > sentenceCount) 1 else 0,
            formality = formality,
            usesContractions = contractions > sentenceCount / 4,
            topWords = unigrams.entries
                .filter { it.key.length > 2 }
                .sortedByDescending { it.value }
                .take(120)
                .map { it.toPair() },
            topEmojis = emojiCounts.entries
                .sortedByDescending { it.value }
                .take(8)
                .map { it.key },
            sentencesAnalyzed = min(sentenceCount, Int.MAX_VALUE - 1)
        )

        return DistillResult(profile, unigrams, bigrams, trigrams)
    }
}
