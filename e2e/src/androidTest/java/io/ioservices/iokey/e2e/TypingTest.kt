package io.ioservices.iokey.e2e

import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Touch
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What is struck is what arrives: every character, once, in order, at the speed people really type. */
class TypingTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val password = HostActivity.PASSWORD

    /** The sentence uses only ordinary words, so nothing in it has any reason to be corrected. */
    private val sentence = "the quick brown fox jumps over the lazy dog "
    private val sentenceAsTyped = "The quick brown fox jumps over the lazy dog "

    @Test
    fun knownSentence_atARealisticPace() {
        showReady(chat)
        kb.type(sentence)
        assertTextStays(sentenceAsTyped)
    }

    @Test
    fun knownSentence_atAFastPace() {
        showReady(chat)
        kb.type(sentence, gapMs = FAST_GAP_MS)
        assertTextStays(sentenceAsTyped)
    }

    /** Nothing here can be a word, and a password field corrects nothing, so every letter must simply arrive. */
    private fun assertRandomLettersArrive(gapMs: Long) {
        showReady(password)
        val random = Random(LETTER_SEED)
        val letters = String(CharArray(LETTER_COUNT) { 'a' + random.nextInt(26) })
        kb.type(letters, gapMs)
        assertTextStaysIgnoringCase(letters, field = password)
    }

    @Test
    fun randomLetters_atARealisticPace() = assertRandomLettersArrive(Keyboard.TYPING_GAP_MS)

    @Test
    fun randomLetters_atAFastPace() = assertRandomLettersArrive(FAST_GAP_MS)

    @Test
    fun autocorrectSwitchedOff_typoStaysAsTyped() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("auto_correct" to false))
        ime.select()
        showReady(chat)
        kb.type("for teh ")
        assertTextStays("For teh ")
    }

    @Test
    fun backspace_removesExactlyOneCharacter() {
        showReady(chat)
        kb.type("hello")
        assertText("Hello")
        kb.backspace()
        assertTextStays("Hell")
        kb.backspace(2)
        assertTextStays("He")
    }

    // ---------------------------------------------------------------------------------------------
    // Backspace with text selected: the selection goes, all of it and nothing else
    // ---------------------------------------------------------------------------------------------

    /** A finger takes far longer to select something than a keystroke takes to settle. */
    private fun selectByHand(start: Int, end: Int) {
        SystemClock.sleep(HAND_TRAVEL_MS)
        host.setSelection(chat, start, end)
        SystemClock.sleep(SELECTION_SETTLE_MS)
    }

    private fun assertBackspaceTakesSelection(before: String, start: Int, end: Int, after: String) {
        host.setText(chat, before)
        showReady(chat)
        selectByHand(start, end)
        kb.backspace()
        assertTextStays(after, message = "Backspace removes the selection and only the selection")
        assertComposing(null, message = "what is left before the selection stays as it was")
        assertEquals("the caret stays where the selection began", start to start, host.selection(chat))
    }

    @Test
    fun backspace_removesASelectedWord() = assertBackspaceTakesSelection("hello world", 6, 11, "hello ")

    @Test
    fun backspace_afterSelectAll_emptiesTheField() = assertBackspaceTakesSelection("hello world", 0, 11, "")

    @Test
    fun backspace_removesASelectionAfterPunctuation() = assertBackspaceTakesSelection("hello, world", 7, 12, "hello, ")

    @Test
    fun backspace_removesASelectionInsideTheText() = assertBackspaceTakesSelection("one two three", 4, 7, "one  three")

    /** The range is gone with the first press, so the next acts at the caret like any other. */
    @Test
    fun backspace_afterASelectionWent_takesOneCharacter() {
        host.setText(chat, "one two three")
        showReady(chat)
        selectByHand(4, 7)
        kb.backspace()
        assertTextStays("one  three")
        kb.backspace()
        assertTextStays("one three", message = "the next press takes one character, not the range again")
    }

    /**
     * A hold goes on into whole-word delete. The first press has taken the selection by then, so the words that
     * go after it are the ones in front of the caret, and what follows the selection is never touched.
     */
    @Test
    fun holdingBackspace_takesTheSelectionThenWhatIsBeforeIt() {
        val text = "one two three four five six seven eight nine ten"
        val start = text.indexOf("nine")
        host.setText(chat, text)
        showReady(chat)
        selectByHand(start, start + "nine".length)
        val key = centre(Keyboard.DELETE)
        touch.press(key.x, key.y, HOLD_DELETE_MS)
        val now = Wait.untilStable(SELECTION_SETTLE_MS * 4, 150) { host.text(chat) }
        assertFalse("the selection must go first. Text: '$now'", now.contains("nine"))
        assertTrue("what comes after the selection must stay. Text: '$now'", now.endsWith(" ten"))
        assertTrue("the hold went on into what is before the caret. Text: '$now'", now.length < text.length - "nine".length)
    }

    /** The emoji panel's Backspace is a view of its own, and takes the same care. */
    @Test
    fun emojiPanelBackspace_removesASelection() {
        host.setText(chat, "hello world")
        showReady(chat)
        kb.tapKey(EMOJI_KEY)
        val opened = Wait.until(PANEL_MS, 60) { emojiPanelBackspace() != null }
        check(opened) { "The emoji panel did not open. In its windows: ${kb.visibleTextInImeWindows()}" }
        selectByHand(6, 11)
        val r = checkNotNull(emojiPanelBackspace())
        touch.tap(r.exactCenterX(), r.exactCenterY())
        assertTextStays("hello ", message = "the emoji panel's Backspace removes the selection and only the selection")
    }

    private fun emojiPanelBackspace(): android.graphics.Rect? {
        E2eDevice.ua.clearCache()
        val window = E2eDevice.ua.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val node = window.root?.findAccessibilityNodeInfosByText(EMOJI_PANEL_BACKSPACE)?.firstOrNull() ?: return null
        return android.graphics.Rect().also { node.getBoundsInScreen(it) }
    }

    /** The keyboard's own way to select: the selection pad's All key, and its Backspace. */
    @Test
    fun selectionPad_allThenBackspace_emptiesTheField() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("selection_key" to true))
        ime.select()
        val text = "hello world"
        host.setText(chat, text)
        showReady(chat)
        kb.tapKey(SELECTION_PAD_KEY)
        check(Wait.until(PANEL_MS, 60) { kb.readLayout()?.face(SELECT_ALL_KEY) != null }) { "The selection pad did not open" }
        kb.tapKey(SELECT_ALL_KEY)
        check(Wait.until(PANEL_MS, 60) { host.selection(chat) == (0 to text.length) }) {
            "The All key selected ${host.selection(chat)}"
        }
        SystemClock.sleep(SELECTION_SETTLE_MS)
        kb.tapKey(Keyboard.DELETE)
        assertTextStays("", message = "All, then Backspace, empties the field")
    }

    // ---------------------------------------------------------------------------------------------
    // Rollover: the next finger lands before the last one lifts
    // ---------------------------------------------------------------------------------------------

    private fun centre(label: String): Touch.Point {
        val r = kb.layout().require(label).rect
        return Touch.Point(r.exactCenterX(), r.exactCenterY())
    }

    private fun assertRolloverPairs(firstLiftsFirst: Boolean) {
        showReady(chat)
        kb.type("so ")
        assertText("So ")
        val pairs = listOf("th", "he", "an", "in", "er", "on", "qp", "za")
        var expected = "So "
        for (pair in pairs) {
            touch.rollover(centre(pair.substring(0, 1)), centre(pair.substring(1, 2)), firstLiftsFirst = firstLiftsFirst)
            expected += pair
            assertText(expected, message = "the rollover $pair must commit both keys in order")
        }
    }

    @Test
    fun rollover_firstFingerLiftsFirst_bothKeysInOrder() = assertRolloverPairs(firstLiftsFirst = true)

    @Test
    fun rollover_secondFingerLiftsFirst_bothKeysInOrder() = assertRolloverPairs(firstLiftsFirst = false)

    @Test
    fun rolloverChain_wholeWordInOrder() {
        showReady(chat)
        kb.type("so ")
        assertText("So ")
        touch.rolloverChain("there".map { centre(it.toString()) })
        assertText("So there")
    }

    // ---------------------------------------------------------------------------------------------
    // Auto-capitals: the keyboard must not capitalise what the sentence and the field do not call for
    // ---------------------------------------------------------------------------------------------

    @Test
    fun autoCaps_notAppliedInAPasswordField() {
        showReady(password)
        kb.type("abc")
        assertText("abc", field = password, message = "a password must get exactly the case that was typed")
    }

    @Test
    fun autoCaps_notAppliedMidSentence() {
        host.setText(chat, "the cat ")
        showReady(chat)
        kb.type("sat")
        assertText("the cat sat", message = "the field already holds the start of the sentence")
    }

    @Test
    fun autoCaps_appliedAtTheStartOfASentence() {
        showReady(chat)
        kb.type("one. two")
        assertText("One. Two")
    }

    private companion object {
        /** Keys about 75 ms apart, a burst from a very fast typist. */
        const val FAST_GAP_MS = 30L
        const val LETTER_SEED = 20261104
        const val LETTER_COUNT = 90
        const val HAND_TRAVEL_MS = 400L
        const val SELECTION_SETTLE_MS = 300L
        const val PANEL_MS = 3_000L

        /** Past the repeat delay and past the speed-up to whole words. */
        const val HOLD_DELETE_MS = 2_000L
        const val EMOJI_KEY = "Emoji"
        const val EMOJI_PANEL_BACKSPACE = "backspace"
        const val SELECTION_PAD_KEY = "Cursor and selection"
        const val SELECT_ALL_KEY = "Select all"
    }
}
