package com.localboard.keyboard.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTextTest {
    @Test
    fun aDraftAfterAWordGetsOneSpace() {
        assertEquals(" sounds good", DraftText.forInsertion("yeah", "sounds good"))
    }

    @Test
    fun aDraftAfterASpaceGetsNoSecondOne() {
        assertEquals("sounds good", DraftText.forInsertion("yeah ", "sounds good"))
    }

    @Test
    fun anEmptyFieldGetsNoSpace() {
        assertEquals("sounds good", DraftText.forInsertion("", "sounds good"))
    }

    @Test
    fun punctuationStaysAgainstTheWordBeforeIt() {
        assertEquals(", sure", DraftText.forInsertion("yeah", ", sure"))
        assertEquals("sure", DraftText.forInsertion("(", "sure"))
    }

    @Test
    fun whitespaceAroundADraftGoes() {
        assertEquals(" sounds good", DraftText.forInsertion("yeah", "\n sounds good \n"))
    }

    @Test
    fun aWordRightAfterTheCursorIsNotGluedOn() {
        assertEquals(" and you ", DraftText.forInsertion("yeah", "and you", "there"))
        assertEquals(" and you", DraftText.forInsertion("yeah", "and you", " there"))
    }

    @Test
    fun aDraftRepeatingTheFieldOnlyAddsTheRest() {
        assertEquals(" good", DraftText.forInsertion("yeah sounds", "yeah sounds good"))
        assertEquals("good", DraftText.forInsertion("yeah sounds ", "Yeah sounds good"))
    }

    /**
     * Under 18 characters the sanitizer accepts a repeat of the draft whole, since a short answer may
     * simply be the natural one. It is the insertion that has to keep it from doubling.
     */
    @Test
    fun aShortRepeatTheSanitizerLetThroughDoesNotDouble() {
        val field = "yeah sounds"
        val verdict = OutputSanitizer.review("yeah sounds good", 36, emptyList(), null, field)
        val kept = (verdict as OutputSanitizer.Accepted).text
        assertEquals(" good", DraftText.forInsertion(field, kept))
    }

    @Test
    fun aDraftThatAddsNothingInsertsNothing() {
        assertEquals("", DraftText.forInsertion("see you soon", "see you soon"))
        assertEquals("", DraftText.forInsertion("see you soon ", "  soon "))
    }

    @Test
    fun onlyWholeWordsCountAsARepeat() {
        assertEquals(" sounds good", DraftText.forInsertion("so", "sounds good"))
        assertEquals(" so what", DraftText.forInsertion("also", "so what"))
    }

    @Test
    fun aRewriteFindsItsSourceWhateverSpacesFollow() {
        assertEquals(9, DraftText.charsToReplace("hello team", "ello team"))
        assertEquals(12, DraftText.charsToReplace("say hello team  ", "hello team"))
    }

    @Test
    fun aRewriteLeavesAFieldThatChangedAlone() {
        assertNull(DraftText.charsToReplace("hello team and more", "hello team"))
        assertNull(DraftText.charsToReplace("", "hello team"))
        assertNull(DraftText.charsToReplace("hello team", ""))
    }

    @Test
    fun aDraftIsReviewedWithTheRequestsOwnRules() {
        val chat = "Chat on my screen:\nThem: are you coming tonight\nYou: yeah what time\nThem: 7 at marios\n"
        val reply = GenerateRequest(contextBefore = chat, styleProfile = null)
        assertEquals(OutputSanitizer.Accepted("sounds good"), reply.review("sounds good"))
        assertEquals(
            OutputSanitizer.Reason.ECHO,
            (reply.review("are you coming tonight") as OutputSanitizer.Rejected).reason
        )
    }

    @Test
    fun aRewriteMayStayCloseToItsSource() {
        val source = "are you coming to the party tonight"
        val rewrite = GenerateRequest(
            contextBefore = "Chat on my screen:\nThem: $source\n",
            styleProfile = null,
            rewriteSource = source
        )
        assertEquals(OutputSanitizer.Accepted(source), rewrite.review(source))
    }

    @Test
    fun aSetAsideDraftStillFitsAfterTypingOnOrDeletingBack() {
        assertTrue(DraftText.stillFollows("see you soon", "see you soon", LIMIT))
        assertTrue(DraftText.stillFollows("see you soon h", "see you soon", LIMIT))
        assertTrue("one Backspace pushed it aside", DraftText.stillFollows("see you soo", "see you soon", LIMIT))
    }

    @Test
    fun aSetAsideDraftDoesNotFitAnotherChatInTheSameBox() {
        assertFalse(DraftText.stillFollows("what time", "see you soon", LIMIT))
        assertFalse("the box is empty now", DraftText.stillFollows("", "see you soon", LIMIT))
        assertFalse(DraftText.stillFollows("see you later", "see you soon", LIMIT))
    }

    @Test
    fun anEmptyBoxCannotSayWhichChatItWas() {
        assertTrue(DraftText.stillFollows("", "", LIMIT))
        assertTrue(DraftText.stillFollows("h", "", LIMIT))
    }

    /** Long enough to have been cut, so it starts somewhere in the middle of what is read now. */
    @Test
    fun aLongTextIsFoundWhereverItNowStarts() {
        val whole = (1..40).joinToString(" ") { "word$it" }
        val kept = whole.takeLast(LIMIT)
        assertEquals(LIMIT, kept.length)

        assertTrue(DraftText.stillFollows(whole, kept, LIMIT))
        assertTrue("typed on", DraftText.stillFollows(whole + " and more", kept, LIMIT))
        assertTrue("a word deleted", DraftText.stillFollows(whole.dropLast(6), kept, LIMIT))
        assertFalse(DraftText.stillFollows("a different long message, " + "again ".repeat(30), kept, LIMIT))
    }

    private companion object {
        const val LIMIT = 100
    }
}
