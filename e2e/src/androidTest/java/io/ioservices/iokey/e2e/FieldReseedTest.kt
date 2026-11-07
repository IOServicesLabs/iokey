package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Touch
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Come back to a half-written message, and the bar and the next glide carry on from the words already there, in lower
 * case, because that is the middle of a sentence. A new message starts fresh. A field with nothing to go on keeps its
 * blank bar. The bar cannot be read, so it is checked by tapping it: a blank bar has nothing to put in the field.
 *
 * No test here can show that a password field is never read. The host counts no reads, and the keyboard makes short reads
 * there already, for the capital of the next letter. That rests on the rule's own table and on there being one place
 * the words are read from.
 */
class FieldReseedTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    /** The first gesture of a process is a test of its own, so these start after one, in another field. */
    private fun warmUpGlide() {
        kb.show(HostActivity.SINGLE)
        ime.waitIdle()
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
    }

    /** A field holding [text] when the keyboard comes up on it, as a message left half written does. */
    private fun comeBackTo(text: String, field: String = chat) {
        warmUpGlide()
        kb.hideWithBack()
        host.setText(field, text)
        showReady(field)
        SystemClock.sleep(BAR_SETTLE_MS)
    }

    private fun centre(label: String): Touch.Point {
        val r = kb.layout().require(label).rect
        return Touch.Point(r.exactCenterX(), r.exactCenterY())
    }

    private fun between(a: String, b: String): Touch.Point {
        val first = centre(a)
        val second = centre(b)
        return Touch.Point((first.x + second.x) / 2f, (first.y + second.y) / 2f)
    }

    private fun settledText(before: String, field: String = chat): String {
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(field) != before }
        return Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(field) }
    }

    /** A stroke that starts between i and u, then goes to s: "is" or "us" is for the sentence to decide. Gives the field after it. */
    private fun glideIsOrUs(field: String = chat): String {
        val before = host.text(field)
        touch.glide(listOf(between("i", "u"), centre("s")), AMBIGUOUS_GLIDE_MS, dwellEndMs = Keyboard.GLIDE_END_REST_MS)
        return settledText(before, field)
    }

    // ---------------------------------------------------------------------------------------------
    // The middle of a sentence stays lower case
    // ---------------------------------------------------------------------------------------------

    @Test
    fun midSentenceAfterAReturn_aGlideStaysLowerCase() {
        comeBackTo("see you ")
        kb.hideWithBack()
        kb.show(chat)
        kb.glide("there")
        settledText("see you ")
        assertTextStays("see you there ", message = "back in the middle of a sentence, the next word has no capital")
    }

    /** With focus, on purpose: this is the restart an app causes when it sets its own field's text. */
    @Test
    fun midSentenceAfterARestart_aGlideStaysLowerCase() {
        comeBackTo("see you ")
        host.setText(chat, "see you ")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.glide("there")
        settledText("see you ")
        assertTextStays("see you there ", message = "the app set the same text again, and this is still the middle of a sentence")
    }

    /** At the end of this text a capital is due, and where the caret goes one is not. */
    @Test
    fun midSentenceAfterAMovedCursor_aGlideStaysLowerCase() {
        comeBackTo("see you soon. ")
        moveCaretByHand("see you ".length)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.glide("there")
        settledText("see you soon. ")
        assertTextStays("see you there soon. ", message = "the caret is in the middle of a sentence now")
    }

    @Test
    fun midSentence_chipsAreLowerCase() {
        comeBackTo("thank ")
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("thank you ", message = "a chip in the middle of a sentence has no capital")
    }

    // ---------------------------------------------------------------------------------------------
    // Nothing to go on, or nothing that may be read: the bar stays blank
    // ---------------------------------------------------------------------------------------------

    private fun assertTheBarIsBlank(holding: String, field: String = chat) {
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays(holding, field = field, message = "a blank bar has nothing to put in the field")
    }

    @Test
    fun anEmptyField_keepsABlankBar() {
        comeBackTo("")
        assertTheBarIsBlank("")
    }

    @Test
    fun aHalfWordField_keepsABlankBar() {
        comeBackTo("le")
        assertTheBarIsBlank("le")
    }

    @Test
    fun aUrlFieldHoldingText_keepsABlankBar() {
        comeBackTo("thank ", HostActivity.URL)
        assertTheBarIsBlank("thank ", HostActivity.URL)
    }

    @Test
    fun aPrivateFieldHoldingText_keepsABlankBar() {
        comeBackTo("thank ", HostActivity.PRIVATE)
        assertTheBarIsBlank("thank ", HostActivity.PRIVATE)
    }

    // ---------------------------------------------------------------------------------------------
    // What the app does to its own field
    // ---------------------------------------------------------------------------------------------

    /**
     * The stroke gives one word in a fresh field, whichever it is, and it must give that same word after a message
     * that was sent and cleared. After 'let' the sentence says one word and after 'this' the other, so at least one of
     * the two would show a sent message still steering the next one.
     */
    @Test
    fun whenTheAppClearsTheField_theNextMessageStartsFresh() {
        warmUpGlide()
        kb.show(chat)
        val fresh = glideIsOrUs()
        record("fresh", fresh)
        for (sent in listOf("let ", "this ")) {
            host.setText(chat, "")
            SystemClock.sleep(BAR_SETTLE_MS)
            kb.type(sent)
            host.setText(chat, "")
            SystemClock.sleep(BAR_SETTLE_MS)
            assertEquals("'$sent' went with the last message", fresh, glideIsOrUs())
        }
    }

    /** Some apps put a saved draft back only after the keyboard is up, and then there is no new show to read it at. */
    @Test
    fun aDraftRestoredAfterTheFirstShow_isTheContext() {
        warmUpGlide()
        kb.show(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        host.setText(chat, "thank ")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("thank you ", message = "the words the app put back are the context")
    }

    // ---------------------------------------------------------------------------------------------
    // Where the caret is
    // ---------------------------------------------------------------------------------------------

    /** The bar follows the caret by itself, and the next word lands there with a space before what follows. */
    @Test
    fun aMovedCursor_changesTheChipsWithNoKeyPress() {
        comeBackTo("thank so ")
        moveCaretByHand("thank ".length)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("thank you so ", message = "the chip is for the word before the caret, with no key pressed")
    }

    /** The keyboard's own cursor keys move the caret too, and the next glide follows where it went. */
    @Test
    fun cursorPadMove_aGlideFollowsTheWordBeforeTheNewCaret() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("selection_key" to true))
        ime.select()
        comeBackTo("let this ")
        kb.tapKey(CURSOR_PAD_KEY)
        check(Wait.until(PANEL_MS, 60) { kb.readLayout()?.face(MOVE_LEFT_KEY) != null }) { "The cursor pad did not open" }
        repeat("this ".length) {
            kb.tapKey(MOVE_LEFT_KEY)
            SystemClock.sleep(CURSOR_KEY_GAP_MS)
        }
        assertEquals("the pad moves the caret", "let ".length, host.caret(chat))
        kb.tapKey(Keyboard.LETTERS)
        check(Wait.until(PANEL_MS, 60) { kb.readLayout()?.isLetters == true }) { "The letters did not come back" }
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word before the caret is 'let', so the stroke is 'us'", "let us this ", glideIsOrUs())
    }

    /** 'tomor' was left in the field and passed over as unfinished, so the word finished now has to join what the next word follows. */
    @Test
    fun aHalfWordFinishedAfterAReturn_isTheWordBefore() {
        comeBackTo("le")
        kb.type("t ")
        assertText("let ")
        assertEquals("the word is 'let' now, so the stroke is 'us'", "let us ", glideIsOrUs())
    }

    // ---------------------------------------------------------------------------------------------
    // A message left with no space after its last word
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aWordLeftTouchingTheCaret_isWhatAGlideFollows() {
        comeBackTo("let")
        assertEquals("the word is 'let', so the stroke is 'us'", "let us ", glideIsOrUs())
    }

    @Test
    fun aWordLeftTouchingTheCaretAfterOthers_isWhatAGlideFollows() {
        comeBackTo("so let")
        assertEquals("the word is 'let', so the stroke is 'us'", "so let us ", glideIsOrUs())
    }

    /** A rotation does this to the field, and it must not lose the word. */
    @Test
    fun aRestartWithTheSameText_keepsTheWordLeftTouchingTheCaret() {
        comeBackTo("so let")
        host.setText(chat, "so let")
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word is still 'let', so the stroke is 'us'", "so let us ", glideIsOrUs())
    }

    @Test
    fun aSpaceAfterAWordLeftTouchingTheCaret_endsIt() {
        comeBackTo("let")
        kb.type(" ")
        assertText("let ")
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word is 'let', so the stroke is 'us'", "let us ", glideIsOrUs())
    }

    @Test
    fun aCommaAfterAWordLeftTouchingTheCaret_endsIt() {
        comeBackTo("let")
        kb.type(",")
        assertText("let,")
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word is 'let', so the stroke is 'us'", "let, us ", glideIsOrUs())
    }

    @Test
    fun aWordLeftTouchingTheCaret_isWhatTheChipsFollow() {
        comeBackTo("so thank")
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTextStays("so thank you ", message = "the chip is for the word touching the caret, and it lands apart from it")
    }

    /** The trail is emptied, which only the learning can show, and that is a row of the scenarios. What the field can show is that all of it stays. */
    @Test
    fun aHalfWordLeftTouchingTheCaret_aGlideLandsApartFromIt() {
        comeBackTo("see you tomor")
        kb.glide("there")
        settledText("see you tomor")
        assertTextStays("see you tomor there ", message = "the word lands after what was left, and a space keeps them apart")
    }

    @Test
    fun withAutoSpaceOff_aGlideGluesToTheWordLeftTouchingTheCaret() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("auto_space" to false))
        ime.select()
        comeBackTo("so")
        val before = host.text(chat)
        kb.glide("there")
        val text = settledText(before)
        record("field", text)
        assertTrue("no space goes in with auto-space off, but the field holds '$text'", text.length > before.length && ' ' !in text)
    }

    @Test
    fun aCaretMovedOffTheWordLeftThere_forgetsIt() {
        comeBackTo("this let")
        moveCaretByHand("this ".length)
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word before the caret is 'this', so the stroke is 'is'", "this is let", glideIsOrUs())
    }

    @Test
    fun aCaretMovedOntoAnotherWord_followsThatOne() {
        comeBackTo("let this")
        moveCaretByHand("let".length)
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the caret touches 'let', so the stroke is 'us'", "let us this", glideIsOrUs())
    }

    @Test
    fun aNewShow_forgetsTheWordLeftTouchingTheCaret() {
        comeBackTo("let")
        kb.hideWithBack()
        host.setText(chat, "this ")
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the field says 'this' now, so the stroke is 'is'", "this is ", glideIsOrUs())
    }

    @Test
    fun aSelectionDeleted_forgetsTheWordLeftTouchingTheCaret() {
        comeBackTo("this let")
        host.setSelection(chat, "this ".length, "this let".length)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.backspace()
        assertText("this ")
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word before the caret is 'this', so the stroke is 'is'", "this is ", glideIsOrUs())
    }

    // ---------------------------------------------------------------------------------------------
    // A private field
    // ---------------------------------------------------------------------------------------------

    /** Nothing here is remembered, but the next word still follows the last one while the field is open. */
    @Test
    fun privateField_aTypedWordDecidesAnAmbiguousGlide() {
        val field = HostActivity.PRIVATE
        warmUpGlide()
        kb.show(field)
        kb.type("let ")
        assertEquals("after 'let', the stroke is 'us'", "Let us ", glideIsOrUs(field))
        kb.type("this ")
        assertEquals("after 'this', the stroke is 'is'", "Let us this is ", glideIsOrUs(field))
    }

    // ---------------------------------------------------------------------------------------------
    // A window over the keyboard
    // ---------------------------------------------------------------------------------------------

    private fun nodeWith(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val here = node.text?.toString()
            if (here != null && (if (exact) here.equals(text, ignoreCase = true) else here.contains(text, ignoreCase = true))) {
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { c -> walk(c)?.let { return it } }
            return null
        }
        for (w in E2eDevice.ua.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == ime.pkg) walk(root)?.let { return it }
        }
        return null
    }

    /** The quick prompt's own connection comes and goes in the middle of the field, and the words in the field are still there after it. */
    @Test
    fun cancelTheBox_thenAGlideFollowsTheWordsInTheField() {
        ime.forceStop()
        E2eDevice.shell.sh("run-as ${ime.pkg} sh -c 'mkdir -p files/models; truncate -s 60M files/models/$FAKE_MODEL_FILE'")
        atEnd {
            ime.forceStop()
            E2eDevice.shell.sh("run-as ${ime.pkg} rm -f files/models/$FAKE_MODEL_FILE")
            kb.forgetDraftingAnswer()
        }
        ime.seedStringPrefs(mapOf("active_model_id" to FAKE_MODEL_ID))
        kb.seedDraftingAnswer(true)
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("onboarding_done" to true, "tutorial_seen" to true))
        ime.select()

        host.setText(chat, "let ")
        kb.show(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()
        kb.tapSparkle(holdMs = LONG_PRESS_MS)
        assertTrue(
            "'$QUICK_PROMPT_TITLE' must open. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { nodeWith(QUICK_PROMPT_TITLE) != null },
        )
        Wait.until(SHOWN_MS, 100) { kb.readLayout()?.isLetters == true }

        val cancel = nodeWith("Cancel", exact = true)
            ?: throw AssertionError("No Cancel to press. On screen: ${kb.visibleTextInImeWindows()}")
        val r = Rect()
        cancel.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
        assertTrue(
            "'$QUICK_PROMPT_TITLE' must close. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { nodeWith(QUICK_PROMPT_TITLE) == null },
        )
        SystemClock.sleep(FIELD_BACK_MS)
        kb.assertIokeyShowing()

        assertEquals("the field still says 'let', so the stroke is 'us'", "let us ", glideIsOrUs())
    }

    private companion object {
        const val GLIDE_COMMIT_CEILING_MS = 2_500L
        const val GLIDE_SETTLE_MS = 1_500L
        const val GLIDE_PAUSE_MS = 400L
        const val AMBIGUOUS_GLIDE_MS = 500L

        /** Past the caret's debounce and the bar's own refresh. */
        const val BAR_SETTLE_MS = 600L

        const val CURSOR_PAD_KEY = "Cursor and selection"
        const val MOVE_LEFT_KEY = "Move left"
        const val PANEL_MS = 3_000L
        const val CURSOR_KEY_GAP_MS = 120L

        const val SHOWN_MS = 3_000L
        const val LONG_PRESS_MS = 900L
        const val FIELD_BACK_MS = 800L
        const val FAKE_MODEL_FILE = "Qwen_Qwen3-0.6B-Q4_K_M.gguf"
        const val FAKE_MODEL_ID = "qwen3_06b"
        const val QUICK_PROMPT_TITLE = "Quick prompt"
    }
}
