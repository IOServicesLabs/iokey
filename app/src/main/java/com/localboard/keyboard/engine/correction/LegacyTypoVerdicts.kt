package com.localboard.keyboard.engine.correction

/**
 * Reads the typo verdicts the old correction rules left in style_stats, so that one upgrade can
 * clear them. Those rules struck a word when a correction merely fired, so the same loop that kept
 * re-correcting a user's words also blacklisted them. Pure: the caller does the database work.
 *
 * Stored formats, as the engine and ContextEngine write them:
 *  - typo_suppressions: comma-joined "word@epochMs". An old store also holds bare "word" entries.
 *  - typo_strikes: comma-joined "word=count@epochMs".
 */
object LegacyTypoVerdicts {
    const val SUPPRESSIONS_KEY = "typo_suppressions"
    const val STRIKES_KEY = "typo_strikes"

    /** A blacklisted word. [at] is null for a bare legacy entry, which never expired. */
    data class Suppression(val word: String, val at: Long?)

    /** A struck word. [count] and [at] are null where the stored text does not parse. */
    data class Strike(val word: String, val count: Int?, val at: Long?)

    /**
     * The stamp is whatever follows the last '@', and only if it is a number: a removed email
     * chip is stored as "me@site.com@1700000000000", and splitting at the first '@' would blame
     * the unrelated word "me". Text that has no stamp is taken whole, because treating too little
     * as a struck word only keeps a row, where treating too much deletes someone else's.
     */
    fun parseSuppressions(stored: String?): List<Suppression> =
        entries(stored).mapNotNull { entry ->
            val at = entry.lastIndexOf('@')
            val stamp = if (at < 0) null else entry.substring(at + 1).toLongOrNull()
            val word = clean(if (stamp == null) entry else entry.substring(0, at))
            if (word.isEmpty()) null else Suppression(word, stamp)
        }

    /** An entry with no word before its '=' is skipped, as the loader has always done. */
    fun parseStrikes(stored: String?): List<Strike> =
        entries(stored).mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val word = clean(entry.substring(0, eq))
            if (word.isEmpty()) return@mapNotNull null
            val value = entry.substring(eq + 1).split('@')
            Strike(word, value.getOrNull(0)?.toIntOrNull(), value.getOrNull(1)?.toLongOrNull())
        }

    /**
     * Every word either list names, expired or not. A word appears here even when its entry is
     * partly unreadable: the old loader kept every key of the strike list out of the model, so
     * each of these words has counts the new rules would otherwise bring back as trusted.
     */
    fun struckWords(suppressions: String?, strikes: String?): Set<String> =
        (parseSuppressions(suppressions).map { it.word } + parseStrikes(strikes).map { it.word }).toSet()

    private fun entries(stored: String?): List<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun clean(word: String) = word.trim().lowercase()
}
