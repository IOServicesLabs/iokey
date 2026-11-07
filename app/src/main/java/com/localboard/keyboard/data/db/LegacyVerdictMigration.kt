package com.localboard.keyboard.data.db

import com.localboard.keyboard.engine.correction.LegacyTypoVerdicts

/**
 * Clears the typo verdicts the old correction rules left behind, once. Those rules struck a word
 * when a correction merely fired, so the verdicts name real words the user fought to keep as well
 * as real typos, and nothing can tell the two apart.
 *
 * Every row ending in a struck word goes too, context rows included. The old loader kept these words
 * out of the model, and the new rules do not, so what remained would otherwise come back: as a count
 * that makes a typo immune, or as a next-word suggestion. A real word loses rows that were hidden
 * anyway, and is learned again the next time it is typed.
 *
 * So does every row with a struck word as its context, or the word could still shape what is offered
 * after it. This runs after the Room migration has counted those rows into the words that followed,
 * so no other word loses any of its count here.
 *
 * The list of struck words stays, in a row no loader reads as a verdict. A backup from before this
 * upgrade still holds these words with their counts, and nothing else could keep them out of a
 * restore. An erase takes the row with everything else.
 *
 * The caller runs this in a transaction, after the Room migration and before anything is loaded.
 */
object LegacyVerdictMigration {
    const val DONE_KEY = "ac_migration"
    private const val DONE = "1"

    /** The words this struck, comma-joined. See [struckWordsIn]. */
    const val STRUCK_WORDS_KEY = "ac_migration_struck_words"

    /** The most words one statement deletes context rows for, well inside SQLite's limit on
     *  bound values, given that the statement binds each word twice. */
    private const val CONTEXT_BATCH = 200

    /** The row an erase has to put back, since this must not run again over verdicts made since. */
    fun doneRow() = StyleStatEntity(DONE_KEY, DONE)

    /** The words stored under [STRUCK_WORDS_KEY]. A verdict entry can't hold a comma, so neither can these. */
    fun struckWordsIn(stored: String?): Set<String> =
        stored.orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    /** True when it ran, false when an earlier start already had. */
    suspend fun runOnce(ngrams: NGramDao, stats: StyleStatDao): Boolean {
        if (stats.get(DONE_KEY) == DONE) return false
        val struck = LegacyTypoVerdicts.struckWords(
            stats.get(LegacyTypoVerdicts.SUPPRESSIONS_KEY),
            stats.get(LegacyTypoVerdicts.STRIKES_KEY)
        )
        for (word in struck) ngrams.deleteWord(word)
        for (batch in struck.chunked(CONTEXT_BATCH)) ngrams.deleteContexts(batch)
        stats.putAll(
            listOf(
                StyleStatEntity(LegacyTypoVerdicts.SUPPRESSIONS_KEY, ""),
                StyleStatEntity(LegacyTypoVerdicts.STRIKES_KEY, ""),
                StyleStatEntity(STRUCK_WORDS_KEY, struck.joinToString(",")),
                doneRow()
            )
        )
        return true
    }
}
