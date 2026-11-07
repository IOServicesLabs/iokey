package com.localboard.keyboard.memory

data class Lexicon(val protectedWords: Set<String>, val boosts: Map<String, Float>)

/** Ephemeral words for this conversation. Never written to n-grams or the trie. */
object ConversationLexicon {
    /** Marks count as part of a word: many scripts write their vowels with them. */
    private val WORD = Regex("[\\p{L}][\\p{L}\\p{M}'\\-]*")

    /** A line break counts: phones capitalize the first word after one too. */
    private val SENTENCE_END = Regex("[.!?…\\n]\\s*$")

    fun build(
        title: String,
        turns: List<Turn>,
        isCommon: (String) -> Boolean,
        maxWords: Int = 200
    ): Lexicon {
        val protect = LinkedHashSet<String>()
        val count = HashMap<String, Int>()
        WORD.findAll(title).forEach { m ->
            if (m.value.length >= 2) protect += m.value.lowercase()
        }
        for (t in turns) {
            for (m in WORD.findAll(t.text)) {
                val w = m.value
                val lower = w.lowercase()
                if (lower.length < 2) continue
                if (!isCommon(lower)) count[lower] = (count[lower] ?: 0) + 1
                // A mid-sentence capital in someone else's message is a name or a term.
                if (t.speaker != Speaker.ME && w[0].isUpperCase() && w.any { it.isLowerCase() }) {
                    val before = t.text.substring(0, m.range.first)
                    val startsSentence = before.isBlank() || SENTENCE_END.containsMatchIn(before)
                    if (!startsSentence && !isCommon(lower)) protect += lower
                }
            }
        }
        val boosts = LinkedHashMap<String, Float>()
        protect.forEach { boosts[it] = 2.0f }
        count.entries.filter { it.value >= 2 }.sortedByDescending { it.value }
            .forEach { e -> if (e.key !in boosts) boosts[e.key] = 1.0f }
        return Lexicon(
            protect.take(maxWords).toSet(),
            boosts.entries.take(maxWords).associate { it.key to it.value }
        )
    }
}
