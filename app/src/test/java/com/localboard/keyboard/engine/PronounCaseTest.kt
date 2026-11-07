package com.localboard.keyboard.engine

import com.localboard.keyboard.engine.correction.FieldCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PronounCaseTest {

    @Test
    fun theFiveWords_withEitherApostrophe_changeOnlyTheFirstLetter() {
        assertEquals("I", PronounCase.cased("i"))
        for (tail in listOf("'m", "'ll", "'d", "'ve", "’m", "’ll", "’d", "’ve")) {
            assertEquals("I$tail", PronounCase.cased("i$tail"))
        }
    }

    @Test
    fun aWordWithAnyCapital_isLeftAlone() {
        for (word in listOf("I", "I'm", "I'M", "I’m", "i'M", "i'LL", "I've")) {
            assertNull(word, PronounCase.cased(word))
        }
    }

    @Test
    fun otherWords_areNotInTheTable() {
        for (word in listOf("ill", "id", "well", "im", "ive", "in", "is", "it", "ii", "i'", "i’", "i'mm", "i'lll", "i'x", "'i")) {
            assertNull(word, PronounCase.cased(word))
        }
    }

    @Test
    fun anEmptyString_isNotInTheTable() {
        assertNull(PronounCase.cased(""))
    }

    @Test
    fun loweredIsTheWayBack() {
        for (word in listOf("i", "i'm", "i'll", "i'd", "i've", "i’m")) {
            assertEquals(word, PronounCase.lowered(PronounCase.cased(word)!!))
        }
    }

    @Test
    fun casedTokens_casesEachWordOnItsOwn() {
        assertEquals("I think", PronounCase.casedTokens("i think"))
        assertEquals("so I'm I", PronounCase.casedTokens("so i'm i"))
    }

    @Test
    fun casedTokens_keepsTheSameTextWhenNothingChanged() {
        for (text in listOf("so ill", "I think", "", "this is it", "two  spaces")) {
            assertSame(text, PronounCase.casedTokens(text))
        }
    }

    @Test
    fun casedTokens_keepsTheSpacingItWasGiven() {
        assertEquals("so  I ", PronounCase.casedTokens("so  i "))
    }

    @Test
    fun sixMarksAndSpace_endAWordWithItsCapital() {
        for (mark in listOf(" ", ",", ".", "!", "?", ":", ";")) {
            assertTrue("'$mark'", PronounCase.capitalisesAt(mark))
        }
    }

    @Test
    fun anythingElse_leavesTheWordAsTyped() {
        for (mark in listOf("/", "-", "@", "_", ")", "]", "\"", "'", "’", "\n", "", ", ", "..", "a", "1")) {
            assertFalse("'$mark'", PronounCase.capitalisesAt(mark))
        }
    }

    @Test
    fun aTypedWord_needsTheSwitchOnAndAFieldThatCorrects() {
        assertTrue(PronounCase.mayCaseTyped(true, FieldCorrection.AUTO))
        assertFalse(PronounCase.mayCaseTyped(true, FieldCorrection.SUGGEST_ONLY))
        assertFalse(PronounCase.mayCaseTyped(true, FieldCorrection.OFF))
        assertFalse(PronounCase.mayCaseTyped(false, FieldCorrection.AUTO))
        assertFalse(PronounCase.mayCaseTyped(false, FieldCorrection.SUGGEST_ONLY))
        assertFalse(PronounCase.mayCaseTyped(false, FieldCorrection.OFF))
    }

    @Test
    fun aWordTheKeyboardWrites_onlyStopsInAFieldThatIsOff() {
        assertTrue(PronounCase.mayCaseOffered(FieldCorrection.AUTO))
        assertTrue(PronounCase.mayCaseOffered(FieldCorrection.SUGGEST_ONLY))
        assertFalse(PronounCase.mayCaseOffered(FieldCorrection.OFF))
    }
}
