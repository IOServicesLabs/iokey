package io.ioservices.iokey.e2e

import android.os.SystemClock
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Autocorrect must not fight the user. A correction is welcome once; after the user takes it back, or
 * edits the word by hand, the word is theirs. Each test starts with nothing learned.
 *
 * The first word of a field is capitalised by the keyboard, so most sentences here start with a filler
 * word and the typo itself stays in lower case. "teh" is corrected through a closed table and "tomarrow"
 * through the general corrector, so a test that depends on the correction firing holds for both paths.
 */
class AutocorrectTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    @Before
    fun ready() {
        showReady(chat)
    }

    /** Types "for teh " and checks that the correction fired, which every undo test starts from. */
    private fun typeTypoAndSeeItCorrected() {
        kb.type("for teh ")
        assertText("For the ", message = "the correction must fire on space")
    }

    // ---------------------------------------------------------------------------------------------
    // The correction itself
    // ---------------------------------------------------------------------------------------------

    @Test
    fun correctionFiresOnSpace() {
        typeTypoAndSeeItCorrected()
        assertComposing(null)
    }

    @Test
    fun correctionFiresOnSpace_generalCorrector() {
        kb.type("see you tomarrow ")
        assertText("See you tomorrow ")
    }

    @Test
    fun correctionFiresOnPunctuation() {
        kb.type("for teh,")
        assertText("For the,")
    }

    @Test
    fun sameTypoElsewhere_stillCorrected() {
        kb.type("for teh cat and teh ")
        assertText("For the cat and the ")
    }

    /** The first fix still stands, so typing the same typo after the same word refuses nothing. */
    @Test
    fun sameTypoAfterTheSameWord_correctedEachTime() {
        kb.type("for teh cat for teh cat for teh ")
        assertTextStays("For the cat for the cat for the ")
    }

    @Test
    fun capitalisedTypo_keepsItsCapital() {
        kb.type("teh ")
        assertText("The ")
        kb.backspace()
        assertText("Teh", message = "undo keeps the capital")
    }

    @Test
    fun loneLowerCaseI_becomesACapital() {
        kb.type("so i ")
        assertTextStays("So I ")
    }

    /** Whether a word gets corrected must not depend on how quickly the space came after its last letter. */
    @Test
    fun fastTyping_correctsLikeSlowTyping() {
        kb.type("for teh ", gapMs = FAST_GAP_MS)
        assertText("For the ")
    }

    @Test
    fun centreChip_appliesTheCorrection() {
        kb.type("for teh")
        assertText("For teh")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertText("For the ")
    }

    // ---------------------------------------------------------------------------------------------
    // Undo, then Space, then the same word again
    // ---------------------------------------------------------------------------------------------

    @Test
    fun backspaceRightAfterCorrection_restoresWhatWasTyped() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        assertText("For teh")
        assertComposing("teh")
    }

    @Test
    fun spaceAfterUndo_keepsTheWord() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.type(" ")
        assertTextStays("For teh ")
    }

    @Test
    fun retypeAfterUndo_stays() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.type(" teh ")
        assertTextStays("For teh teh ")
    }

    @Test
    fun retypeAfterUndo_staysAfterHidingAndShowingTheKeyboard() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.type(" ")
        assertText("For teh ")
        kb.hideWithBack()
        kb.show(chat)
        kb.type("teh ")
        assertTextStaysIgnoringCase("For teh teh ", message = "a word the user took back must stay theirs after a hide")
    }

    @Test
    fun retypeAfterUndo_staysInAnotherField() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.type(" ")
        assertText("For teh ")
        kb.show(HostActivity.SINGLE)
        kb.type("so teh ")
        assertTextStaysIgnoringCase("so teh ", field = HostActivity.SINGLE, message = "a word the user took back is theirs in every field")
    }

    @Test
    fun retypeAfterUndo_staysAfterProcessRestart() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.type(" ")
        assertText("For teh ")
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_SETTLE_MS)
        ime.killProcess()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        kb.type("teh ")
        assertTextStaysIgnoringCase("For teh teh ", message = "a word the user took back must stay theirs after a restart")
    }

    @Test
    fun repeatedFight_nothingEverRecorrects() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.type(" ")
        var expected = "For teh "
        assertText(expected)
        repeat(3) {
            kb.hideWithBack()
            kb.show(chat)
            kb.type("teh ")
            expected += "teh "
            assertTextStaysIgnoringCase(expected, message = "show number ${it + 2}")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The app clears the field, and a glide comes next
    // ---------------------------------------------------------------------------------------------

    /**
     * Many chat apps clear the field with setText after Send. Input then restarts with no hide and no selection
     * update. A different word comes first in the next message, so only a refusal that was remembered can hold it.
     */
    @Test
    fun undoThenSendInAFieldClearedBySetText_keepsTheRefusal() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        assertComposing("teh")
        host.setText(chat, "")
        assertText("")
        kb.type("so teh ")
        assertTextStaysIgnoringCase("so teh ", message = "sending the word the user took back keeps it theirs")
    }

    /** A restart can also come from an app setting the same text again, and Backspace can still reach the correction. */
    @Test
    fun restartThatLeavesTheText_keepsTheUndo() {
        typeTypoAndSeeItCorrected()
        host.setText(chat, "For the ")
        assertText("For the ")
        kb.backspace()
        assertText("For teh", message = "the correction is still right there")
    }

    @Test
    fun glideRightAfterUndo_keepsTheRestoredWord() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        assertComposing("teh")
        kb.glide("hello")
        Wait.until(GLIDE_COMMIT_MS) { host.text(chat) != "For teh" }
        assertTextStays("For teh hello ", message = "the glide starts a new word and the restored word stays")
        kb.type("so teh ")
        assertTextStays("For teh hello so teh ", message = "going on with a glide keeps the refusal")
    }

    // ---------------------------------------------------------------------------------------------
    // Erasing and retyping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun eraseAfterUndoAndRetype_stays() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        assertText("For teh")
        kb.backspace(3)
        assertText("For ")
        kb.type("teh ")
        assertTextStays("For teh ")
    }

    /** No undo here: another letter was typed first, so the first backspace cannot be the undo. */
    @Test
    fun eraseWithoutUndoAndRetype_stays() {
        typeTypoAndSeeItCorrected()
        kb.type("x")
        assertText("For the x")
        kb.backspace(5)
        assertText("For ")
        kb.type("teh ")
        assertTextStays("For teh ", message = "retyping the same word in the same place is a refusal")
    }

    /** The first complaint, for someone who erases a fix at once: the retype stays, and the fix is refused everywhere. */
    @Test
    fun eraseAtOnceAndRetype_refusesTheFix() {
        typeTypoAndSeeItCorrected()
        kb.type("x")
        kb.backspace(5)
        assertText("For ")
        kb.type("teh ")
        assertText("For teh ", message = "the retype stays")
        kb.type("and teh ")
        assertTextStays("For teh and teh ", message = "a fix taken out before anything came after it is refused at once")
    }

    /** The fix stood, so the person had moved on. Erasing it to redo the sentence and making the same slip refuses nothing yet. */
    @Test
    fun eraseAFixThatStoodAndRetype_staysButBansNothing() {
        kb.type("for teh cat ")
        assertText("For the cat ")
        kb.backspace(8)
        assertText("For ")
        kb.type("teh ")
        assertText("For teh ", message = "the retype stays this once")
        kb.type("and teh ")
        assertTextStays("For teh and the ", message = "the correction still fires in another place")
    }

    /** The same again later: a second time is a refusal. */
    @Test
    fun eraseAFixThatStoodAndRetypeTwice_refusesTheFix() {
        kb.type("for teh cat ")
        kb.backspace(8)
        kb.type("teh and teh cat ")
        assertText("For teh and the cat ")
        kb.backspace(8)
        assertText("For teh and ")
        kb.type("teh ")
        assertText("For teh and teh ", message = "the second retype stays")
        kb.type("so teh ")
        assertTextStays("For teh and teh so teh ", message = "the second retype refuses the fix everywhere")
    }

    /** Send clears the field before any Space comes, and the retype still counts. */
    @Test
    fun eraseRetypeThenSendInAFieldClearedBySetText_keepsTheRefusal() {
        typeTypoAndSeeItCorrected()
        kb.type("x")
        kb.backspace(5)
        assertText("For ")
        kb.type("teh")
        assertComposing("teh")
        host.setText(chat, "")
        assertText("")
        kb.type("so teh ")
        assertTextStaysIgnoringCase("so teh ", message = "sending a word typed back over its fix keeps it theirs")
    }

    /** A finger takes far longer to get to a word than a keystroke takes to settle, so a selection right after one is not what a person does. */
    private fun selectInChat(start: Int, end: Int) {
        SystemClock.sleep(SELECT_TRAVEL_MS)
        host.setSelection(chat, start, end)
        SystemClock.sleep(SELECT_SETTLE_MS)
    }

    /** Selecting the fix and typing the typo over it takes the fix out as surely as erasing it does. */
    @Test
    fun typeOverASelectedFix_refusesTheFix() {
        typeTypoAndSeeItCorrected()
        selectInChat("For ".length, "For the".length)
        kb.type("teh ")
        assertText("For teh  ", message = "typed over, it stays")
        // The space that followed the selection is still there, after the caret.
        kb.type("and teh ")
        assertTextStays("For teh and teh  ", message = "a fix taken out before anything came after it is refused at once")
    }

    /** Further back in the sentence, the words before the caret are no guide to where this is. Only the selection says. */
    @Test
    fun typeOverASelectedFixThatStood_staysButBansNothing() {
        kb.type("for teh cat ")
        assertText("For the cat ")
        selectInChat("For ".length, "For the".length)
        kb.type("teh ")
        assertTextStays("For teh  cat ", message = "typed over, it stays this once")
        moveCaretByHand("For teh  cat ".length)
        kb.type("and teh ")
        assertTextStays("For teh  cat and the ", message = "the correction still fires in another place")
    }

    /** Selected and deleted, and then the typo: the same refusal as typing over it. */
    @Test
    fun deleteASelectedFixAndRetype_refusesTheFix() {
        typeTypoAndSeeItCorrected()
        selectInChat("For ".length, "For the".length)
        kb.backspace()
        assertText("For  ")
        kb.type("teh ")
        assertText("For teh  ", message = "the retype stays")
        // The space that followed the selection is still there, after the caret.
        kb.type("and teh ")
        assertTextStays("For teh and teh  ", message = "a fix taken out before anything came after it is refused at once")
    }

    @Test
    fun eraseAfterUndoAndTypeADifferentTypo_stillCorrects() {
        typeTypoAndSeeItCorrected()
        kb.backspace()
        kb.backspace(3)
        assertText("For ")
        kb.type("tomarrow ")
        assertText("For tomorrow ")
    }

    /** The user holds delete to erase. That is not "this is my word", so it must teach nothing. */
    @Test
    fun holdDeleteAfterCorrection_erasesAndKeepsNothing() {
        typeTypoAndSeeItCorrected()
        val delete = kb.layout().require(Keyboard.DELETE).rect
        touch.press(delete.exactCenterX(), delete.exactCenterY(), HOLD_DELETE_MS)
        assertText("", message = "holding delete erases the text")
        // A different preceding word, so nothing here counts as retyping in the same place.
        kb.type("so teh ")
        assertTextStays("So the ", message = "erasing must not have taught the typo")
    }

    /** Hold delete to redo a sentence and the same thumb types the same slip: left alone, and the correction survives. */
    @Test
    fun holdDeleteAFixThatStoodAndRetype_staysButBansNothing() {
        kb.type("for teh cat ")
        assertText("For the cat ")
        val delete = kb.layout().require(Keyboard.DELETE).rect
        touch.press(delete.exactCenterX(), delete.exactCenterY(), HOLD_DELETE_MS)
        assertText("", message = "holding delete erases the text")
        kb.type("for teh ")
        assertTextStaysIgnoringCase("for teh ", message = "the same slip after the same word stays this once")
        kb.type("and teh ")
        assertTextStaysIgnoringCase("for teh and the ", message = "one hold of delete must not refuse the correction")
    }

    // ---------------------------------------------------------------------------------------------
    // Editing by hand
    // ---------------------------------------------------------------------------------------------

    /** The most literal form of the complaint: back up, fix one letter, and get corrected again. */
    @Test
    fun undoThenChangeOneLetter_variantStays() {
        kb.type("see you tomarrow ")
        assertText("See you tomorrow ")
        kb.backspace()
        assertText("See you tomarrow")
        kb.backspace()
        kb.type("e ")
        assertTextStays("See you tomarroe ", message = "a word the user edited by hand must not be corrected")
    }

    @Test
    fun handEditWhileComposing_notCorrected() {
        kb.type("for tehh")
        assertText("For tehh")
        kb.backspace()
        kb.type(" ")
        assertTextStays("For teh ", message = "a word the user fixed by hand must not be corrected")
    }

    @Test
    fun backspaceIntoSettledCorrection_unchanged_staysAsIs() {
        kb.type("for teh cat ")
        assertText("For the cat ")
        kb.backspace(4)
        assertText("For the ")
        kb.backspace()
        assertText("For the")
        kb.type(" ")
        assertTextStays("For the ")
    }

    @Test
    fun backspaceIntoSettledCorrection_editedBackToTyped_stays() {
        kb.type("for teh cat ")
        assertText("For the cat ")
        kb.backspace(5)
        assertText("For the")
        kb.backspace(2)
        kb.type("eh ")
        assertTextStays("For teh ", message = "going back and restoring the typed spelling must stick")
    }

    /** As above, before the next word has come along to settle the correction. */
    @Test
    fun backspaceIntoUnsettledCorrection_editedBackToTyped_stays() {
        typeTypoAndSeeItCorrected()
        kb.type("c")
        assertText("For the c")
        kb.backspace(2)
        assertText("For the")
        kb.backspace(2)
        kb.type("eh ")
        assertTextStays("For teh ", message = "going back and restoring the typed spelling must stick")
    }

    /** An ordinary edit of a correction refuses nothing, and the new word is a word like any other. */
    @Test
    fun backspaceIntoSettledCorrection_ordinaryEdit_stays() {
        kb.type("for teh cat ")
        assertText("For the cat ")
        kb.backspace(5)
        assertText("For the")
        kb.type("m ")
        assertTextStays("For them ")
    }

    @Test
    fun typingInsideAWord_notCorrected() {
        kb.type("so wonderful")
        assertText("So wonderful")
        moveCaretByHand("So wonder".length)
        kb.type("teh")
        assertText("So wondertehful")
        kb.type(" ")
        assertTextStays("So wonderteh ful", message = "letters typed inside a word are not a word to correct")
    }

    // ---------------------------------------------------------------------------------------------
    // Punctuation and the Enter key
    // ---------------------------------------------------------------------------------------------

    @Test
    fun punctuationFiredCorrection_undoRestoresExactly() {
        kb.type("for teh,")
        assertText("For the,")
        kb.backspace()
        assertTextStays("For teh,", message = "the first backspace takes back the correction and keeps the comma")
    }

    @Test
    fun punctuationFiredCorrection_secondBackspaceRemovesCommaAndWordStays() {
        kb.type("for teh,")
        assertText("For the,")
        kb.backspace()
        assertText("For teh,")
        kb.backspace()
        assertText("For teh")
        kb.type(" ")
        assertTextStays("For teh ", message = "never corrected again")
    }

    @Test
    fun enterInMultiLineField_correctsLikeSpace() {
        kb.type("for teh\n")
        assertText("For the\n")
    }

    @Test
    fun enterCorrection_undoRestoresTheNewline() {
        kb.type("for teh\n")
        assertText("For the\n")
        kb.backspace()
        assertText("For teh\n")
    }

    @Test
    fun sendAction_doesNotCorrect() {
        kb.show(HostActivity.SEND)
        kb.type("for teh")
        val actions = host.editorActions()
        kb.tapKey(Keyboard.ENTER)
        assertTrue("Enter must perform the field's send action", Wait.until(2_000) { host.editorActions() > actions })
        assertTextStays("For teh", field = HostActivity.SEND, message = "send cannot be taken back, so it must not correct")
    }

    // ---------------------------------------------------------------------------------------------
    // Chips
    // ---------------------------------------------------------------------------------------------

    @Test
    fun rawChipBeforeSpace_commitsWhatWasTyped() {
        kb.type("for teh")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.LEFT)
        assertTextStays("For teh ")
    }

    @Test
    fun rawChipThenBackspace_wipesTheWholeWord() {
        kb.type("for teh")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.LEFT)
        assertText("For teh ")
        kb.backspace()
        assertText("For ")
    }

    /** The revert chip after a correction on space puts the word back with its space, ready to type on. */
    @Test
    fun revertChipAfterSpaceCorrection_restoresWithSeparator() {
        typeTypoAndSeeItCorrected()
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapBarWord("teh", fallback = Keyboard.Slot.RIGHT)
        assertText("For teh ", message = "the chip restores the word and its space")
        kb.type("cat")
        assertText("For teh cat", message = "the next word must not run on")
    }

    /** Tapping the chip once appended the typed word after the comma: "the,teh ". */
    @Test
    fun revertChipAfterPunctuationCorrection_noCorruption() {
        kb.type("for teh,")
        assertText("For the,")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapBarWord("teh", fallback = Keyboard.Slot.RIGHT)
        assertTextStays("For teh,", message = "the chip must restore exactly what was typed and add nothing")
    }

    // ---------------------------------------------------------------------------------------------
    // Things undo must leave alone
    // ---------------------------------------------------------------------------------------------

    /** The caret moved and the text before it happens to equal the fix. Backspace there is only a backspace. */
    @Test
    fun staleUndoCannotFireElsewhere() {
        kb.type("and the cat for teh ")
        assertText("And the cat for the ")
        moveCaretByHand("And the ".length)
        kb.backspace()
        assertTextStays("And thecat for the ", message = "backspace at another place must delete one character there")
    }

    @Test
    fun runOnWord_splitAndUndo() {
        kb.type("for doingwell ")
        assertText("For doing well ")
        kb.backspace()
        assertText("For doingwell")
    }

    // ---------------------------------------------------------------------------------------------
    // Fields where correction has no business
    // ---------------------------------------------------------------------------------------------

    private fun assertNotCorrectedIn(field: String) {
        kb.show(field)
        kb.type("teh ")
        // Whether the first letter is capitalised here is another question, so case is not compared.
        assertTextStaysIgnoringCase("teh ", field = field, message = "autocorrect must leave this field alone")
    }

    @Test
    fun passwordField_neverCorrected() = assertNotCorrectedIn(HostActivity.PASSWORD)

    @Test
    fun noSuggestionsField_neverCorrected() = assertNotCorrectedIn(HostActivity.NO_SUGGESTIONS)

    @Test
    fun urlField_neverCorrected() = assertNotCorrectedIn(HostActivity.URL)

    @Test
    fun emailField_neverCorrected() = assertNotCorrectedIn(HostActivity.EMAIL)

    /** A web text box that does not ask for correction gets suggestions only: the fix is on the bar, and Space leaves the word. */
    @Test
    fun webFieldWithoutTheFlag_offersTheFixAndDoesNotApplyIt() {
        val field = HostActivity.WEB
        kb.show(field)
        kb.type("so teh ")
        assertTextStaysIgnoringCase("so teh ", field = field, message = "this field takes suggestions only")
        kb.type("teh")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStaysIgnoringCase("so teh the ", field = field, message = "the bar still offers the fix")
    }

    /** The field asks for nothing to be learned: a refusal holds while the user is there, and is not kept. */
    @Test
    fun privateField_refusalHoldsThereAndIsNotRemembered() {
        val field = HostActivity.PRIVATE
        kb.show(field)
        kb.type("for teh ")
        assertTextStaysIgnoringCase("for the ", field = field, message = "the correction must fire on space")
        kb.backspace()
        kb.type(" teh ")
        assertTextStaysIgnoringCase("for teh teh ", field = field, message = "a word taken back stays taken back in this field")
        kb.show(chat)
        kb.type("so teh ")
        assertTextStays("So the ", message = "a private field must leave nothing behind for the next field")
    }

    // ---------------------------------------------------------------------------------------------
    // The capital I. A change of case at the end of a word, not a correction. An empty field has
    // Shift on, so a typed i there is a capital with or without any of this: each case starts with a word.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun capital_atAComma() {
        kb.type("so i,")
        assertTextStays("So I,")
    }

    @Test
    fun capital_atEnter() {
        kb.type("so i\n")
        assertTextStays("So I\n")
    }

    /** Send cannot be taken back, so a correction does not run there. A capital is no correction, and the message must go out right. */
    @Test
    fun capital_beforeSend() {
        val field = HostActivity.SEND
        kb.show(field)
        kb.type("so do i")
        val actions = host.editorActions()
        kb.tapKey(Keyboard.ENTER)
        assertTrue("Enter must perform the field's send action", Wait.until(2_000) { host.editorActions() > actions })
        assertTextStays("So do I", field = field)
    }

    /** Someone who hit Space early on 'is' must carry on to 'is', and not to 'Is'. */
    @Test
    fun capital_backspaceThenALetter_carriesOn() {
        kb.type("so i ")
        assertText("So I ")
        kb.backspace()
        assertText("So i", message = "one Backspace takes the capital back along with the space")
        assertComposing("i")
        kb.type("s ")
        assertTextStays("So is ")
    }

    /** Taken back once it stays taken back, for that word. Nothing is remembered, so the next i is a capital again. */
    @Test
    fun capital_backspaceThenSpace_keepsItOnce() {
        kb.type("so i ")
        assertText("So I ")
        kb.backspace()
        assertText("So i")
        kb.type(" ")
        assertTextStays("So i ", message = "the word was finished as it stood")
        kb.type("i ")
        assertTextStays("So i I ", message = "a fresh i gets its capital")
    }

    /** One more Space and Backspace must not bring back a capital already taken back. */
    @Test
    fun capital_takenBack_staysThatWayThroughAnotherSpaceAndBackspace() {
        kb.type("so i ")
        assertText("So I ")
        kb.backspace()
        assertText("So i")
        kb.type(" ")
        assertText("So i ")
        kb.backspace()
        assertText("So i")
        assertComposing("i")
        kb.type(" ")
        assertTextStays("So i ", message = "the capital was taken back and must not return")
        kb.type("i ")
        assertTextStays("So i I ", message = "a fresh i gets its capital")
    }

    /** Space too early, and a letter before noticing. Backspacing to the i must carry on to 'is', and not to 'Is'. */
    @Test
    fun capital_aLetterAfterItErased_thenALetter_carriesOn() {
        kb.type("so i s")
        assertText("So I s")
        kb.backspace(2)
        assertText("So i", message = "erasing the letter and the space takes the capital back too")
        assertComposing("i")
        kb.type("s ")
        assertTextStays("So is ")
    }

    /** A held Backspace can stop one repeat late on the i, and that is no sign the capital was unwanted. */
    @Test
    fun capital_aLetterAfterItErased_thenSpace_capitalisesAgain() {
        kb.type("so i s")
        assertText("So I s")
        kb.backspace(2)
        assertText("So i")
        kb.type(" ")
        assertTextStays("So I ")
    }

    /** Backspace removes the comma as it always has. Only a capital made at Space or a full stop can be taken back. */
    @Test
    fun capital_backspaceAfterAComma_keepsTheCapital() {
        kb.type("so i,")
        assertText("So I,")
        kb.backspace()
        assertText("So I")
        kb.type(" ")
        assertTextStays("So I ")
    }

    @Test
    fun capital_iE_staysAsTyped() {
        kb.type("so i.e. ")
        assertTextStays("So i.e. ")
    }

    @Test
    fun capital_atAFullStopThenSpace() {
        kb.type("so i. ")
        assertTextStays("So I. ")
    }

    private fun typeWithApostrophes(text: String) {
        for (c in text) {
            if (c == '\'') kb.typeSymbol("'") else kb.type(c.toString())
        }
    }

    /** Were the capital a correction, the second Space would settle i'm as a typo and take it out of the dictionary, and the bar would stop offering it. */
    @Test
    fun capital_contractionTwice_barStillOffersIt() {
        typeWithApostrophes("so i'm ")
        assertText("So I'm ")
        typeWithApostrophes("i'm ")
        assertText("So I'm I'm ")
        typeWithApostrophes("i'")
        assertText("So I'm I'm i'")
        kb.tapBarWord("I'm", fallback = Keyboard.Slot.CENTRE)
        assertTextStays("So I'm I'm I'm ")
    }

    /**
     * The same way back for a contraction. It also takes back the count the word was learned with, which nothing on the
     * screen shows, so that half rests on the code and this row on the text.
     */
    @Test
    fun capital_contraction_backspaceTakesItBack() {
        typeWithApostrophes("so i'm ")
        assertText("So I'm ")
        kb.backspace()
        assertText("So i'm")
        assertComposing("i'm")
        kb.type(" ")
        assertTextStays("So i'm ", message = "taken back, it stays as typed")
        typeWithApostrophes("i'm ")
        assertTextStays("So i'm I'm ", message = "a fresh one gets its capital")
    }

    /** The chip showing the word as typed is the deliberate way to a lower case i. */
    @Test
    fun capital_rawChip_commitsLowerCase() {
        kb.type("so i")
        assertText("So i")
        kb.tapBarWord("i", fallback = Keyboard.Slot.LEFT)
        assertTextStays("So i ")
    }

    /** The count of corrections fired, as the keyboard stores it. No file yet, or no such row, means it has not fired. */
    private fun correctionsFired(): Long {
        val xml = E2eDevice.shell.sh("run-as ${ime.pkg} cat shared_prefs/local_stats.xml 2>/dev/null")
        return CORRECTIONS_FIRED.find(xml)?.groupValues?.get(1)?.toLong() ?: 0L
    }

    /** 'teh' at the end shows the read can see a correction, so the count staying still before it means something. */
    @Test
    fun capital_typedCapitalIsLeftAlone_andIsNotACorrection() {
        val before = correctionsFired()
        kb.type("so ")
        assertText("So ")
        // Shift's label says whether it is on, and it was on when the field opened, so the keys are read again here.
        kb.forgetLayout()
        kb.tapKey(Keyboard.SHIFT)
        kb.type("i ")
        assertText("So I ")
        kb.type("i ")
        assertTextStays("So I I ")
        SystemClock.sleep(PERSIST_SETTLE_MS)
        assertEquals("a capital is not a correction, whoever typed it", before, correctionsFired())
        kb.type("teh ")
        assertText("So I I the ")
        Wait.until(PERSIST_SETTLE_MS) { correctionsFired() > before }
        assertEquals("a real correction is counted", before + 1, correctionsFired())
    }

    /** Whether the first letter is capitalised here is another question, so the end of the field is compared exactly and the start not at all. */
    private fun assertLoneIStaysLowerCaseIn(field: String) {
        kb.show(field)
        kb.type("so i ")
        assertTextStaysIgnoringCase("so i ", field = field)
        val text = host.text(field)
        assertTrue("the $field field holds '$text'", text.endsWith(" i "))
    }

    @Test
    fun capital_neverInUrlEmailOrPassword() {
        assertLoneIStaysLowerCaseIn(HostActivity.URL)
        assertLoneIStaysLowerCaseIn(HostActivity.EMAIL)
        assertLoneIStaysLowerCaseIn(HostActivity.PASSWORD)
    }

    @Test
    fun capital_autocorrectOff_staysAsTyped() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("auto_correct" to false))
        ime.select()
        showReady(chat)
        kb.type("so i ")
        assertTextStays("So i ")
    }

    @Test
    fun capital_suggestionsOnlyField_staysAsTyped() = assertLoneIStaysLowerCaseIn(HostActivity.WEB)

    /**
     * A real correction that lands on a word in the table: the capital comes with it, and its undo gives back what was
     * typed. A run-on cannot show this, because the dictionary has no lone i for a split to cut off.
     */
    @Test
    fun capital_insideACorrection_andItsUndo() {
        kb.type("so ")
        assertText("So ")
        typeWithApostrophes("i'vr ")
        assertText("So I've ")
        kb.backspace()
        assertText("So i'vr")
    }

    // ---------------------------------------------------------------------------------------------
    // The nightly job
    // ---------------------------------------------------------------------------------------------

    /**
     * WorkManager's builder throws on backoff criteria beside an idle constraint, but only on a device. The keyboard's
     * start-up would swallow that and leave the old job enqueued, with no idle, so this is the one place it can show.
     */
    @Test
    fun nightlyJob_isEnqueuedWithIdle() {
        var dump = ""
        val found = Wait.until(JOB_ENQUEUED_MS, 250) {
            dump = E2eDevice.shell.exec("dumpsys jobscheduler ${ime.pkg}")
            REQUIRES_IDLE.containsMatchIn(dump)
        }
        val lines = dump.lineSequence().filter { "constraints" in it || "Requires:" in it || "JOB #" in it }.joinToString(" | ") { it.trim() }
        record("job", lines)
        assertTrue("The job must require the phone to be idle. The dump says: ${lines.ifEmpty { dump.take(1_500) }}", found)
    }

    private companion object {
        /** Long enough for the debounced prediction pass to put its words on the bar. */
        const val BAR_SETTLE_MS = 500L

        val CORRECTIONS_FIRED = Regex("""name="correction_fired" value="(\d+)"""")

        /** The job's own request reads deviceIdle=true, and what the scheduler then requires names IDLE. */
        val REQUIRES_IDLE = Regex("""Required constraints:[^\n]*\bIDLE\b|deviceIdle=true""")

        /** The job is enqueued by the keyboard's start-up, on WorkManager's own threads. */
        const val JOB_ENQUEUED_MS = 10_000L

        /** Long enough for a write the keyboard makes when it hides to reach disk. */
        const val PERSIST_SETTLE_MS = 1_500L

        const val FAST_GAP_MS = 30L

        /** A process's first glide may be held while the keyboard builds what it needs. */
        const val GLIDE_COMMIT_MS = 4_000L

        /** Past the repeat delay and past the speed-up to whole words, enough to erase a short line. */
        const val HOLD_DELETE_MS = 2_000L

        const val SELECT_TRAVEL_MS = 400L
        const val SELECT_SETTLE_MS = 300L
    }
}
