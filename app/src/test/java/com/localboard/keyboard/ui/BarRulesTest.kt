package com.localboard.keyboard.ui

import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.ime.GlideRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarRulesTest {

    private fun word(text: String, score: Double = 1.0) = Suggestion(text, score)
    private fun raw(text: String) = Suggestion(text, 0.0)
    private fun revert(text: String) = Suggestion(text, -1.0)

    /** [centre, raw, right], as a word being typed gives. */
    private fun typing(centre: String, typed: String, right: String) = listOf(word(centre), raw(typed), word(right))

    /** What is being typed when the list is in step with it: the word as typed, or nothing. */
    private fun inStep(list: List<Suggestion>): String = list.getOrNull(BarRules.rawIndex(list))?.text.orEmpty()

    private fun pressed(list: List<Suggestion>, slot: Int) = BarRules.pressOn(list, slot, inStep(list))

    /** Slot numbers: 0 left, 1 centre, 2 right. What is being typed matches the list, unless a row says otherwise. */
    private fun commit(
        slot: Int,
        down: List<Suggestion>,
        up: List<Suggestion> = down,
        showSinceDown: Boolean = false,
        modeChanged: Boolean = false,
        revertLive: Boolean = false,
        composingAtDown: String = inStep(down),
        composing: String = inStep(up)
    ): String? = BarRules.wordToCommit(
        BarRules.pressOn(down, slot, composingAtDown), up, showSinceDown, modeChanged, revertLive, composing
    )

    // ---- which word is the raw one ----

    @Test
    fun rawIndex_isThePlaceAndTheScore() {
        assertEquals(1, BarRules.rawIndex(typing("the", "teh", "they")))
        assertEquals(0, BarRules.rawIndex(listOf(raw("qxzvk"))))
        assertEquals(-1, BarRules.rawIndex(emptyList()))
        assertEquals(-1, BarRules.rawIndex(listOf(word("the"))))
        assertEquals(-1, BarRules.rawIndex(listOf(word("the"), word("a"), word("an"))))
    }

    /** An engine word can score exactly 0.0, so the score alone would call it the word as typed. */
    @Test
    fun rawIndex_aCentreWordScoringZeroBesideTheRawWord() {
        assertEquals(1, BarRules.rawIndex(listOf(raw("then"), raw("the"), word("they"))))
        assertEquals(-1, BarRules.rawIndex(listOf(raw("then"), word("the"), word("they"))))
    }

    @Test
    fun revertChip_isNotTheRawWord() {
        assertEquals(-1, BarRules.rawIndex(listOf(word("a"), word("b"), revert("teh"))))
        assertEquals(-1, BarRules.rawIndex(listOf(revert("teh"))))
    }

    // ---- which slot holds which word ----

    @Test
    fun slots_normalListPutsTheTopPickInTheCentre() {
        val list = typing("the", "teh", "they")
        assertEquals("teh", BarRules.wordInSlot(list, 0)?.text)
        assertEquals("the", BarRules.wordInSlot(list, 1)?.text)
        assertEquals("they", BarRules.wordInSlot(list, 2)?.text)
        assertEquals(1, BarRules.indexInSlot(list, 0))
        assertEquals(0, BarRules.indexInSlot(list, 1))
        assertEquals(2, BarRules.indexInSlot(list, 2))
    }

    @Test
    fun slots_aRawOnlyWordDrawsLeftAndTheOthersAreEmpty() {
        val list = listOf(raw("qxzvk"))
        assertEquals(0, BarRules.indexInSlot(list, 0))
        assertEquals(-1, BarRules.indexInSlot(list, 1))
        assertEquals(-1, BarRules.indexInSlot(list, 2))
        assertEquals("qxzvk", BarRules.wordInSlot(list, 0)?.text)
        assertNull(BarRules.wordInSlot(list, 1))
        assertNull(BarRules.wordInSlot(list, 2))
    }

    @Test
    fun slots_aLoneRealWordStaysInTheCentre() {
        val list = listOf(word("the"))
        assertNull(BarRules.wordInSlot(list, 0))
        assertEquals("the", BarRules.wordInSlot(list, 1)?.text)
    }

    @Test
    fun slots_thereAreOnlyThree() {
        val list = typing("the", "teh", "they")
        for (slot in listOf(-1, 3, 5, 6, 7)) {
            assertEquals(-1, BarRules.indexInSlot(list, slot))
            assertNull(BarRules.wordInSlot(list, slot))
        }
    }

    @Test
    fun rawOnly_tellsTheSameWordInTwoPlacesApart() {
        assertTrue(BarRules.isRawOnly(listOf(raw("a"))))
        assertFalse(BarRules.isRawOnly(listOf(word("a"))))
        assertFalse(BarRules.isRawOnly(emptyList()))
    }

    // ---- what a press holds ----

    @Test
    fun press_onAWordHoldsItAndItsKind() {
        val onRaw = pressed(typing("the", "teh", "they"), 0)
        assertEquals("teh", onRaw.word?.text)
        assertTrue(onRaw.wasRaw)
        assertTrue(onRaw.listHadRaw)
        assertFalse(onRaw.listWasEmpty)

        val onCentre = pressed(typing("the", "teh", "they"), 1)
        assertEquals("the", onCentre.word?.text)
        assertFalse(onCentre.wasRaw)
        assertTrue(onCentre.listHadRaw)

        val nextWords = pressed(listOf(word("a"), word("b"), word("c")), 2)
        assertEquals("c", nextWords.word?.text)
        assertFalse(nextWords.listHadRaw)
    }

    /** No word means no pill and no feel, because those go by what the press holds. */
    @Test
    fun press_onAnEmptySlotLatchesNoWord() {
        assertNull(pressed(emptyList(), 1).word)
        assertTrue(pressed(emptyList(), 1).listWasEmpty)
        assertNull(pressed(listOf(raw("qxzvk")), 1).word)
        assertNull(pressed(listOf(word("the")), 0).word)
        assertNull(pressed(typing("the", "teh", "they"), 3).word)
        assertNull(pressed(typing("the", "teh", "they"), -1).word)
    }

    // ---- what a lift commits ----

    @Test
    fun unchangedList_commitsWhatWasAimedAt() {
        val list = typing("the", "teh", "they")
        assertEquals("teh", commit(0, list))
        assertEquals("the", commit(1, list))
        assertEquals("they", commit(2, list))
    }

    @Test
    fun aShowSinceDown_commitsNothing() {
        val list = typing("the", "teh", "they")
        for (slot in 0..2) assertNull(commit(slot, list, showSinceDown = true))
        assertFalse(BarRules.pressStands(showSinceDown = true, modeChanged = false))
    }

    @Test
    fun aChangeOfMode_commitsNothing() {
        val list = typing("the", "teh", "they")
        for (slot in 0..2) assertNull(commit(slot, list, modeChanged = true))
        assertFalse(BarRules.pressStands(showSinceDown = false, modeChanged = true))
        assertTrue(BarRules.pressStands(showSinceDown = false, modeChanged = false))
    }

    @Test
    fun theLatestListEmpty_commitsNothing() {
        for (slot in 0..2) assertNull(commit(slot, typing("the", "teh", "they"), up = emptyList()))
    }

    /** 'hel' on the chip with 'hell' composing: taking the old letters would lose the last one. */
    @Test
    fun rawChip_commitsTheLatestRawWord() {
        assertEquals("hell", commit(0, typing("hello", "hel", "help"), up = typing("hello", "hell", "hells")))
        assertEquals("hell", commit(0, listOf(raw("hel")), up = listOf(raw("hell"))))
    }

    @Test
    fun rawChip_withNoRawWordLeft_commitsNothing() {
        assertNull(commit(0, typing("hello", "hel", "help"), up = listOf(word("a"), word("b"), word("c"))))
    }

    /** The person's own choice, a typo or not. */
    @Test
    fun rawChip_thatIsATypoCommitsItAsTyped() {
        assertEquals("teh", commit(0, typing("the", "teh", "they")))
    }

    @Test
    fun revertChip_needsTheChipStillThereAndAFixStillLive() {
        val withChip = listOf(word("cat"), word("dog"), revert("teh"))
        assertEquals("teh", commit(2, withChip, revertLive = true))
        assertNull("a fix that has gone would commit the typo, and teach it", commit(2, withChip, revertLive = false))
        assertNull(
            "a chip that has gone leaves nothing to undo",
            commit(2, withChip, up = listOf(word("cat"), word("dog"), word("hat")), revertLive = true),
        )
        assertNull(
            "another chip is another word",
            commit(2, withChip, up = listOf(word("cat"), word("dog"), revert("hte")), revertLive = true),
        )
    }

    @Test
    fun otherWord_stillCommitsAfterLeavingTheList() {
        val down = typing("hello", "hel", "help")
        assertEquals("hello", commit(1, down, up = typing("hell", "hell", "hells")))
        assertEquals("help", commit(2, down, up = typing("hell", "hell", "hells")))
        val nextWords = listOf(word("a"), word("b"), word("c"))
        assertEquals("c", commit(2, nextWords, up = listOf(word("d"), word("e"), word("f"))))
    }

    /** A pending correction is an ordinary word here: the aim stands. */
    @Test
    fun correction_commitsLikeAnyOtherWord() {
        assertEquals("the", commit(1, typing("the", "teh", "they"), up = typing("the", "tehr", "they")))
    }

    /** The word just typed ended under the finger, and committing the aim would double it: 'the the '. */
    @Test
    fun theLatestListLostItsRawWord_commitsNothing() {
        assertNull(commit(1, typing("the", "the", "they"), up = listOf(word("cat"), word("dog"), word("hat"))))
    }

    /** A word began under the finger, and committing the aim would replace its letter: the 'h'. */
    @Test
    fun theLatestListGainedARawWord_commitsNothing() {
        assertNull(commit(1, listOf(word("cat"), word("dog"), word("hat")), up = typing("hello", "h", "he")))
    }

    @Test
    fun anEmptySlotAtDown_commitsNothingEvenIfAWordHasArrived() {
        assertNull(commit(1, emptyList(), up = listOf(word("the"))))
        assertNull(commit(1, listOf(raw("q")), up = typing("quite", "qu", "quit")))
        assertNull(commit(2, listOf(word("the")), up = typing("a", "b", "c")))
    }

    /** It goes in at the cursor and does not replace the glide word, which is no worse than today. */
    @Test
    fun aHeldGlideAlternate_goesInWhenNextWordsReplacedItsList() {
        val alternates = listOf(word("glide"), word("glade"), word("guide"))
        assertEquals("guide", commit(2, alternates, up = listOf(word("a"), word("b"), word("c"))))
    }

    @Test
    fun aRawOnlyWord_isARawChip() {
        assertEquals("qxzvk", commit(0, listOf(raw("qxzvk"))))
        assertEquals("qxzvkq", commit(0, listOf(raw("qxzvk")), up = listOf(raw("qxzvkq"))))
    }

    // ---- a list behind what is being typed ----

    /** Nearly every tap: the list has caught up, and what lands is the word on the chip. */
    @Test
    fun anOrdinaryTap_commitsTheWordOnTheChip() {
        val typed = typing("the", "teh", "they")
        for (slot in 0..2) assertEquals(BarRules.wordInSlot(typed, slot)?.text, commit(slot, typed, composing = "teh"))
        val nextWords = listOf(word("a"), word("b"), word("c"))
        for (slot in 0..2) assertEquals(BarRules.wordInSlot(nextWords, slot)?.text, commit(slot, nextWords, composing = ""))
        assertEquals("qxzvk", commit(0, listOf(raw("qxzvk")), composing = "qxzvk"))
    }

    /** 'he' on the chip, 'hel' composing, and the list a pass behind: the old letters would lose the l and teach the fragment. */
    @Test
    fun rawChip_commitsWhatIsComposing_whenTheListIsBehind() {
        assertEquals("hel", commit(0, typing("hello", "he", "help"), composing = "hel"))
        assertEquals("qxzvkq", commit(0, listOf(raw("qxzvk")), composing = "qxzvkq"))
        assertEquals("a backspace is the same", "he", commit(0, typing("hello", "hel", "help"), composing = "he"))
    }

    /** The aim of any other word stands: the word the finger came down on, whatever has been typed since. */
    @Test
    fun otherWord_standsWhileTheWordGrowsAheadOfTheList() {
        assertEquals("hello", commit(1, typing("hello", "hel", "help"), composing = "hell"))
        assertEquals("help", commit(2, typing("hello", "hel", "help"), composing = "hell"))
    }

    /** Space lands under the finger and the list has not caught up: committing would give 'hel hello ' or 'hel hel '. */
    @Test
    fun aWordThatEndedAheadOfTheList_commitsNothing() {
        val list = typing("hello", "hel", "help")
        for (slot in 0..2) assertNull(commit(slot, list, composing = ""))
        assertNull(commit(0, listOf(raw("qxzvk")), composing = ""))
    }

    /** A letter lands under a finger on a next word and the list has not caught up: committing would replace the letter. */
    @Test
    fun aWordThatBeganAheadOfTheList_commitsNothing() {
        val nextWords = listOf(word("a"), word("b"), word("c"))
        for (slot in 0..2) assertNull(commit(slot, nextWords, composing = "h"))
    }

    /** A letter since the fix does not decide this chip. Whether the fix can still be undone does. */
    @Test
    fun revertChip_isDecidedBeforeWhatIsComposing() {
        val withChip = listOf(word("cat"), word("dog"), revert("teh"))
        assertEquals("teh", commit(2, withChip, revertLive = true, composing = "a"))
        assertNull(commit(2, withChip, revertLive = false, composing = "a"))
    }

    // ---- the one way back a glide leaves ----

    /**
     * With no next word to go beside it, the bar after a glide is this one word on the left, with the raw word's score,
     * and nothing is being typed. Read as the word as typed, a tap on it would find no such word and do nothing.
     */
    @Test
    fun aLoneAlternate_takesATapLikeAnyOtherWord() {
        val lone = listOf(raw("glade"))
        assertEquals("glade", commit(0, lone, composingAtDown = "", composing = ""))
        assertFalse(BarRules.pressOn(lone, 0, "").wasRaw)
        assertFalse(BarRules.pressOn(lone, 0, "").listHadRaw)
    }

    @Test
    fun aLoneAlternate_standsWhenNextWordsArriveUnderTheFinger() {
        val up = listOf(word("a"), word("glade"), word("b"))
        assertEquals("glade", commit(0, listOf(raw("glade")), up = up, composingAtDown = "", composing = ""))
    }

    @Test
    fun aLoneAlternate_aWordBeginningUnderTheFingerStopsIt() {
        assertNull(commit(0, listOf(raw("glade")), composingAtDown = "", composing = "h"))
    }

    /** The same shape while a word is being typed is that word, and committing it again after a space would double it. */
    @Test
    fun aRawOnlyWordThatEndedUnderTheFinger_isNotALoneAlternate() {
        assertNull(commit(0, listOf(raw("qxzvk")), composingAtDown = "qxzvk", composing = ""))
    }

    // ---- what the glide's rules build, as these rules read it ----

    /**
     * The scores that mark a bar word are each file's private copy, so the shapes above would stay green if one copy
     * changed. These run the lists GlideRules really builds, because that change would leave a tap on the alternate doing nothing.
     */
    @Test
    fun theLoneWordGlideRulesBuilds_takesATap() {
        val bar = GlideRules.barAfterGlide(emptyList(), word("glade"), "glide")
        assertEquals("it must draw on the left", "glade", BarRules.wordInSlot(bar, 0)?.text)
        val press = BarRules.pressOn(bar, 0, "")
        assertFalse(press.wasRaw)
        assertEquals("glade", BarRules.wordToCommit(press, bar, false, false, false, ""))
    }

    @Test
    fun theAlternateBesideNextWords_takesATapOnTheLeft() {
        val bar = GlideRules.barAfterGlide(listOf(word("you"), word("for")), word("glade"), "glide")
        assertEquals("the next word goes in the centre", "you", BarRules.wordInSlot(bar, 1)?.text)
        val press = BarRules.pressOn(bar, 0, "")
        assertEquals("glade", press.word?.text)
        assertFalse(press.wasRaw)
        assertEquals("glade", BarRules.wordToCommit(press, bar, false, false, false, ""))
    }

    /** An alternate that came in carrying a marker's score must not read as the word as typed, with nothing being typed. */
    @Test
    fun anAlternateScoredLikeTheRawWord_isStillAWord() {
        val bar = GlideRules.barAfterGlide(listOf(word("you")), raw("glade"), "glide")
        assertEquals(-1, BarRules.rawIndex(bar))
        assertEquals("glade", BarRules.wordToCommit(BarRules.pressOn(bar, 0, ""), bar, false, false, false, ""))
    }

    @Test
    fun theRevertChipGlideRulesBuilds_isReadAsOne() {
        val bar = GlideRules.barDuringStroke(listOf(word("cat"), word("dog")), null, "teh")
        val press = BarRules.pressOn(bar, 2, "")
        assertEquals("teh", BarRules.wordToCommit(press, bar, false, false, true, ""))
        assertNull("a fix that has gone would commit the typo", BarRules.wordToCommit(press, bar, false, false, false, ""))
    }

    // ---- the pointer ----

    private fun pointer(
        slot: Int = 1,
        down: List<Suggestion> = emptyList(),
        up: List<Suggestion> = emptyList(),
        showSinceDown: Boolean = false,
        modeChanged: Boolean = false,
        chipShowing: Boolean = false,
        draftingAvailable: Boolean = true
    ) = BarRules.pointerStartsDraft(pressed(down, slot), up, showSinceDown, modeChanged, chipShowing, draftingAvailable)

    @Test
    fun pointer_startsADraftOnlyInTheCentreWithTheBarEmptyThroughout() {
        assertTrue(pointer())
        assertFalse("a side slot", pointer(slot = 0))
        assertFalse("a side slot", pointer(slot = 2))
        assertFalse("a word has arrived", pointer(up = listOf(word("the"))))
        assertFalse("a word has gone: never a draft instead of it", pointer(down = listOf(word("the"))))
        assertFalse("a raw-only list has an empty centre, and it is not the pointer", pointer(down = listOf(raw("q")), up = listOf(raw("q"))))
        assertFalse("the chip takes the pointer's place", pointer(chipShowing = true))
        assertFalse("no model able to draft", pointer(draftingAvailable = false))
        assertFalse(pointer(showSinceDown = true))
        assertFalse(pointer(modeChanged = true))
    }

    // ---- fading ----

    @Test
    fun animates_notWhileTyping() {
        var lastChange = 10_000L
        // A run of five, 100 ms apart. The first is measured from a change just before it.
        for (i in 1..5) {
            val now = lastChange + 100
            assertFalse(BarRules.animates(now, lastChange, animLevel = 2))
            lastChange = now
        }
    }

    @Test
    fun animates_afterAPause() {
        assertTrue(BarRules.animates(11_500L, 10_000L, animLevel = 2))
        assertTrue(BarRules.animates(11_000L, 10_000L, animLevel = 1))
        assertFalse(BarRules.animates(10_999L, 10_000L, animLevel = 2))
    }

    /** Key gaps of 300 ms and well past it are ordinary typing, and the words read at once at any such pace. */
    @Test
    fun animates_notAtAnOrdinaryTypingPace() {
        assertFalse(BarRules.animates(10_300L, 10_000L, animLevel = 2))
        assertFalse(BarRules.animates(10_600L, 10_000L, animLevel = 2))
    }

    @Test
    fun animates_neverAtLevelZero() {
        assertFalse(BarRules.animates(99_000L, 0L, animLevel = 0))
        assertFalse(BarRules.animates(10_500L, 10_000L, animLevel = 0))
    }

    // ---- the stored answer ----

    /** A yes from a background download must not shift the slots under a thumb, so a restart keeps what it has. */
    @Test
    fun aRestartKeepsTheAnswerItHas() {
        assertFalse(BarRules.readsStoredAnswer(restarting = true, answerHeld = true))
        assertFalse(BarRules.readsStoredAnswer(restarting = true, answerHeld = false))
    }

    @Test
    fun aShowThatIsNotARestartReadsTheStoredAnswer() {
        assertTrue(BarRules.readsStoredAnswer(restarting = false, answerHeld = true))
        assertTrue(BarRules.readsStoredAnswer(restarting = false, answerHeld = false))
        assertTrue(BarRules.readsStoredAnswer(restarting = false, answerHeld = null))
    }

    /** A process whose first show is a restart has nothing to keep. */
    @Test
    fun aRestartWithNothingHeldReadsTheStoredAnswer() {
        assertTrue(BarRules.readsStoredAnswer(restarting = true, answerHeld = null))
    }
}
