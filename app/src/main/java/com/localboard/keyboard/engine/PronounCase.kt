package com.localboard.keyboard.engine

import com.localboard.keyboard.engine.correction.FieldCorrection

/**
 * The first person's capital: i, i'm, i'll, i'd and i've, whichever way they were written.
 *
 * This is a table and not a correction. Nothing here goes through the corrector or its session, because a word's
 * capital is not a mistake the person made and can refuse: i'm taken for a typo would leave the dictionary, and a
 * refused i would stay lower case for months. A word the table has no row for is never touched, so ill stays ill.
 *
 * There is no language to check. The keyboard has one subtype and no setting for it, so a lone i in another language
 * gets the capital as well.
 */
object PronounCase {

    private val TABLE = setOf("i", "i'm", "i'll", "i'd", "i've")

    /** No row in the table is longer than this, so nearly every word is settled without being looked up. */
    private const val LONGEST = 4

    /** What ends a typed word with its capital. These are the marks that fire automatic correction, and Space. */
    private const val CAPITALISING_SEPARATORS = " ,.!?:;"

    /** [word] with its capital, or null when it needs none: not in the table, or holding any capital already. */
    fun cased(word: String): String? {
        if (word.isEmpty() || word.length > LONGEST || word[0] != 'i') return null
        if (word.replace('’', '\'') !in TABLE) return null
        return "I" + word.substring(1)
    }

    /** The way back from [cased]. */
    fun lowered(cased: String): String = "i" + cased.substring(1)

    /** Each space-separated word of [text] through [cased]. The same text when nothing changed. */
    fun casedTokens(text: String): String {
        if ('i' !in text) return text
        val tokens = text.split(' ')
        val out = tokens.map { cased(it) ?: it }
        return if (out == tokens) text else out.joinToString(" ")
    }

    /** A closing bracket, a quote, / - @ _ and anything longer than one character leave the word as typed. */
    fun capitalisesAt(separator: String): Boolean =
        separator.length == 1 && separator[0] in CAPITALISING_SEPARATORS

    /** May a word being typed get its capital here? Only where Space could also correct it. */
    fun mayCaseTyped(autoCorrect: Boolean, field: FieldCorrection): Boolean =
        autoCorrect && field == FieldCorrection.AUTO

    /** May a word the keyboard itself writes, a glide or a word on the bar, get its capital here? */
    fun mayCaseOffered(field: FieldCorrection): Boolean = field != FieldCorrection.OFF
}
