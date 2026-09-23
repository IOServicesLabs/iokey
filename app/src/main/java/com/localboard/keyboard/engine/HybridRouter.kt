package com.localboard.keyboard.engine

import com.localboard.keyboard.util.EmojiData

/**
 * The orchestrator from the architecture doc: decides what fills the
 * prediction bar on every keystroke. The fast path (n-gram engine) is
 * always synchronous and instant; heavier providers can be blended in
 * when they prove fast enough.
 */
class HybridRouter(private val fastEngine: FastEngine) {

    /**
     * @param prefix current in-progress word ("" = predicting next word)
     * @param prev1 previous committed word
     * @param prev2 word before that
     * @param appPackage host app package for per-app boosts
     * @param capitalize whether suggestions should be capitalized
     * @param includeEmoji append an emoji suggestion when a word maps to one
     */
    fun route(
        prefix: String,
        prev1: String,
        prev2: String,
        appPackage: String = "",
        capitalize: Boolean = false,
        includeEmoji: Boolean = true
    ): List<Suggestion> {
        val base = fastEngine.predict(prefix, prev1, prev2, appPackage, maxResults = 5)
        if (base.isEmpty()) return emptyList()

        val out = ArrayList<Suggestion>(4)
        for (s in base) {
            val display = applyCase(s.text, prefix, capitalize)
            out += s.copy(text = display)
        }

        // Emoji companion: if the top completed word maps to an emoji, offer it last.
        if (includeEmoji) {
            val anchor = (base.firstOrNull()?.text ?: prefix).lowercase()
            EmojiData.wordToEmoji[anchor]?.let { emoji ->
                if (out.none { it.text == emoji }) {
                    out += Suggestion(emoji, base.first().score * 0.5, Suggestion.Kind.EMOJI)
                }
            }
        }
        return out.take(4)
    }

    private fun applyCase(word: String, prefix: String, capitalize: Boolean): String {
        if (word.isEmpty()) return word
        // Mirror how the user started the word (hEllo -> Hello), honor auto-caps.
        val prefixCaps = prefix.isNotEmpty() && prefix.first().isUpperCase()
        val allCaps = prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }
        return when {
            allCaps -> word.uppercase()
            prefixCaps || capitalize -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
