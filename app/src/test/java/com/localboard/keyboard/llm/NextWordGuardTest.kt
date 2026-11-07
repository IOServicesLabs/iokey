package com.localboard.keyboard.llm

import com.localboard.keyboard.ime.NextWordGuard
import org.junit.Assert.assertEquals
import org.junit.Test

/** The next-word path looks for a model and reads the field only past this table. */
class NextWordGuardTest {

    private fun ask(
        hasField: Boolean = true,
        aiAllowedHere: Boolean = true,
        fieldAi: Boolean = true,
        fieldSuggestions: Boolean = true,
        typedSinceSeed: Int = 1,
        skipOnce: Boolean = false
    ) = NextWordGuard.mayAsk(hasField, aiAllowedHere, fieldAi, fieldSuggestions, typedSinceSeed, skipOnce)

    @Test
    fun table() {
        val rows = listOf(
            Triple("all clear", ask(), true),
            Triple("several typed", ask(typedSinceSeed = 7), true),
            Triple("no field", ask(hasField = false), false),
            Triple("the app is blocked for AI", ask(aiAllowedHere = false), false),
            Triple("a URL, email, filter or password field", ask(fieldAi = false), false),
            // FieldPolicy keeps ai true here, so this row is the only thing that stops it.
            Triple("a field that turned suggestions off", ask(fieldSuggestions = false), false),
            Triple("nothing typed since the trail was read", ask(typedSinceSeed = 0), false),
            Triple("the first pass of a show", ask(skipOnce = true), false),
            Triple("the first pass of a restart, with words typed before it", ask(typedSinceSeed = 12, skipOnce = true), false),
        )
        for ((what, got, want) in rows) assertEquals(what, want, got)
    }

    @Test
    fun eachOfTheFourFieldConditionsStopsItAlone() {
        val blocks = listOf(
            ask(hasField = false),
            ask(aiAllowedHere = false),
            ask(fieldAi = false),
            ask(fieldSuggestions = false),
        )
        assertEquals(listOf(false, false, false, false), blocks)
    }
}
