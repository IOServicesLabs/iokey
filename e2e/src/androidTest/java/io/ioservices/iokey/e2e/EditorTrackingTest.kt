package io.ioservices.iokey.e2e

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.inputmethod.BaseInputConnection
import io.ioservices.iokey.e2e.harness.DeviceEnv
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The keyboard knows what it wrote, and tells that from what anything else did to the field. What goes wrong when it
 * cannot: after a configuration change the app's next setText leaves it typing into nothing, a word's echoes after a
 * re-show read as outside moves, and a filter that dropped letters leaves it believing in text that is not there.
 * This window echoes fast unless a test holds up its main thread, so most of what a slow editor does is for the unit tests.
 */
class EditorTrackingTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val single = HostActivity.SINGLE

    /** The decoder's first glide can be a slow one, and these tests are about what comes after a glide. */
    private fun warmUpGlide() {
        kb.show(single)
        ime.waitIdle()
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
    }

    private fun settledText(field: String, before: String): String {
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(field) != before }
        return Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(field) }
    }

    private fun waitForKeyboardAfterConfigChange() {
        SystemClock.sleep(DeviceEnv.CONFIG_SETTLE_MS)
        kb.waitForShown()
    }

    private fun typeThenAppClearsThenTypeAgain() {
        kb.type("hi")
        assertTextStaysIgnoringCase("hi")
        host.setText(chat, "")
        SystemClock.sleep(APP_CHANGE_SETTLE_MS)
        kb.type("yo")
        assertTextStaysIgnoringCase("yo")
    }

    @Test
    fun rotateThenAppClearsField_typingStillLands() {
        showReady(chat)
        atEnd { env.setRotation(0) }
        env.setRotation(1)
        waitForKeyboardAfterConfigChange()
        typeThenAppClearsThenTypeAgain()
    }

    @Test
    fun nightModeThenAppClearsField_typingStillLands() {
        showReady(chat)
        atEnd { env.setNightMode(false) }
        env.setNightMode(true)
        waitForKeyboardAfterConfigChange()
        typeThenAppClearsThenTypeAgain()
    }

    /** A configuration restart leaves the editor's composing region in place, and the next letter would replace it. */
    @Test
    fun rotateMidWord_losesNoLetters() {
        showReady(chat)
        atEnd { env.setRotation(0) }
        kb.type("hel")
        assertTextStaysIgnoringCase("hel")
        env.setRotation(1)
        waitForKeyboardAfterConfigChange()
        kb.type("p")
        assertTextStaysIgnoringCase("help")
    }

    /** Nothing reports what a filter dropped. Backspace must act on the text that is there, not the text believed in. */
    @Test
    fun truncatingField_droppedWordThenBackspaceTakesAVisibleLetter() {
        showReady(single)
        host.onMain { it.field(single).filters = arrayOf(InputFilter.LengthFilter(7)) }
        kb.type("abc def")
        assertTextStaysIgnoringCase("abc def", field = single)
        kb.type(" ghi ")
        assertTextStaysIgnoringCase("abc def", field = single)
        kb.backspace()
        assertTextStaysIgnoringCase("abc de", field = single)
    }

    /** A word running past the limit must not leave Backspace taking letters only the keyboard can see. */
    @Test
    fun truncatingField_backspaceAfterAQuietMomentTakesALetter() {
        showReady(single)
        host.onMain { it.field(single).filters = arrayOf(InputFilter.LengthFilter(5)) }
        kb.type("abcdefgh")
        assertTextStaysIgnoringCase("abcde", field = single)
        record("composingAfterTheCut", host.composing(single) ?: "none")
        SystemClock.sleep(QUIET_MS)
        kb.backspace()
        assertTextStaysIgnoringCase("abcd", field = single)
        kb.type("x")
        assertTextStaysIgnoringCase("abcdx", field = single)
        kb.backspace()
        assertTextStaysIgnoringCase("abcd", field = single)
    }

    /** A pick moves the caret back and then forward again, and none of that is anyone else's doing. */
    @Test
    fun alternatePickThenBackspace_takesTheWholeWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        kb.glide("hello")
        settledText(chat, "So ")
        SystemClock.sleep(BAR_SETTLE_MS)
        val beforePick = host.text(chat)
        kb.tapSlot(Keyboard.Slot.LEFT)
        val picked = settledText(chat, beforePick)
        record("glided", beforePick)
        record("picked", picked)
        val withoutLastWord = picked.trimEnd(' ').substringBeforeLast(' ') + " "
        kb.backspace()
        assertTextStays(withoutLastWord, message = "one press takes the last word and its space, from '$picked'")
    }

    /** Hide then tap delivers the first show's offsets again, and those must not count as where the caret is. */
    @Test
    fun hideThenGlide_backspaceTakesTheWholeWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so far so good ")
        val before = host.text(chat)
        kb.hideWithBack()
        kb.show(chat)
        kb.glide("hello")
        val glided = settledText(chat, before)
        record("glided", glided)
        kb.backspace()
        assertTextStays(before, message = "one press takes what one gesture wrote, from '$glided'")
    }

    /** An app emptying its field without a restart: the next message starts with a capital and with nothing left over. */
    @Test
    fun inPlaceClear_nextMessageStartsClean() {
        showReady(chat)
        kb.type("so far ")
        assertText("So far ")
        host.onMain { it.field(chat).text.clear() }
        SystemClock.sleep(APP_CHANGE_SETTLE_MS)
        kb.type("yo")
        assertTextStays("Yo")
    }

    /** The batch is what sends the selection update. Without it no echo comes and this proves nothing. */
    @Test
    fun appDropsComposing_nextLetterDoesNotDuplicate() {
        showReady(chat)
        kb.type("hel")
        assertTextStaysIgnoringCase("hel")
        host.onMain {
            val f = it.field(chat)
            f.beginBatchEdit()
            BaseInputConnection.removeComposingSpans(f.text)
            f.endBatchEdit()
        }
        SystemClock.sleep(APP_CHANGE_SETTLE_MS)
        kb.type("p")
        assertTextStaysIgnoringCase("help")
    }

    /**
     * Keeps the app's main thread busy while [block] runs, free each time only to take what queued up meanwhile. One stall
     * before a key would not do: the keyboard's own read at Space waits it out, and the writes after that read are reported
     * back at once. This way they wait for the next stall, as they would in a slow field, and the keys keep coming.
     */
    private fun inASlowField(block: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val stillSlow = AtomicBoolean(true)
        main.post(object : Runnable {
            override fun run() {
                SystemClock.sleep(APP_STALL_MS)
                if (stillSlow.get()) main.post(this)
            }
        })
        try {
            block()
        } finally {
            stillSlow.set(false)
        }
    }

    /**
     * The first complaint, in a slow field: a correction taken back, one more letter, then Space. What the field reports
     * of the keyboard's own writes comes late, and taken for an outside change it ends the undo or drops the word. A
     * dropped word looks right by eye, because the next letter lands right behind it, so the region is the check.
     */
    @Test
    fun slowField_undoThenOneMoreLetter_staysOneWord() {
        showReady(chat)
        kb.type("for ")
        inASlowField {
            kb.type("teh ")
            assertText("For the ", message = "the correction must fire on Space, or nothing is taken back here")
            kb.backspace()
            kb.type("x")
            assertComposing("tehx", message = "the word put back and the letter after it must be one word, still composing")
            kb.type(" ")
            assertTextStays("For tehx ", message = "a word the person took back and went on with stays as typed")
        }
    }

    /** A read that gave up says nothing about where the caret is, so a letter in the middle of a sentence stays small. */
    @Test
    fun readThatGivesUpMidSentence_leavesShiftAlone() {
        showReady(chat)
        kb.type("so far goo")
        assertText("So far goo")
        Handler(Looper.getMainLooper()).post { SystemClock.sleep(READ_GIVES_UP_STALL_MS) }
        kb.backspace()
        kb.type("d")
        assertTextStays("So far god")
    }

    private companion object {
        const val GLIDE_PAUSE_MS = 400L
        const val GLIDE_COMMIT_CEILING_MS = 2_500L
        const val GLIDE_SETTLE_MS = 1_500L
        const val BAR_SETTLE_MS = 600L
        const val APP_CHANGE_SETTLE_MS = 300L

        /** Past the keyboard's check after the last write. */
        const val QUIET_MS = 700L

        /** Twice the keyboard's 150 ms self-edit timer, and well under the 2 s a read of the field waits. */
        const val APP_STALL_MS = 300L

        /** Past the 2 s a read of the field waits before it gives up. */
        const val READ_GIVES_UP_STALL_MS = 2_500L
    }
}
