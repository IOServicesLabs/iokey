package io.ioservices.iokey.e2e

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar with no model on the device, which is the state of every new install. With nothing capable of writing there
 * is no sparkle and no pointer to it, the words have the room, and nothing on the bar starts a draft. What a person
 * sees comes from the bar's nodes: a tap where the sparkle would be lands on the mic or commits a word. One test holds
 * the sparkle through the one show a stored answer lasts, because a stale answer can still reach it. No test here
 * downloads a model, so what a real model produces is out of this suite's reach.
 */
class LlmTest : E2eTestBase() {

    /** A wipe removes any model and leaves the keyboard as it is on first install. */
    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    /** The words on the bar are not enough, because a tap on them may be all that fails. It must open the dialog. */
    private fun assertSetupOffered(how: String) {
        assertTrue(
            "with no model, $how must say how to get one. On screen: ${kb.visibleTextInImeWindows()}, " +
                "field: '${host.text(chat)}'",
            Wait.until(OFFERED_MS, 100) { kb.visibleTextInImeWindows().any { SETUP_NOTICE in it } },
        )
        kb.tapSlot(Keyboard.Slot.CENTRE)
        atEnd { touch.key(KeyEvent.KEYCODE_BACK) }
        assertTrue(
            "a tap on those words must open the setup dialog. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(OFFERED_MS, 100) { kb.visibleTextInImeWindows().any { SETUP_TITLE in it } },
        )
    }

    /** Room was the point: the right word's node reaches the bar's edge, or stops at the mic, with nothing else beside it. */
    @Test
    fun noModel_afterTyping_noSparkleAndTheRightWordHasTheRoom() {
        showReady(chat)
        kb.type("see you soon ")
        assertText("See you soon ")
        SystemClock.sleep(BAR_SETTLE_MS)

        val nodes = kb.barNodes()
        assertFalse("with no model there is no sparkle. The bar offers: $nodes", kb.sparkleIsThere())
        val words = nodes.filter { it.label != VOICE_TYPING }
        assertTrue("the bar must offer words after typing, and it offers: $nodes", words.isNotEmpty())
        val barWidth = kb.layout().view.width()
        val mic = nodes.firstOrNull { it.label == VOICE_TYPING }
        val edge = mic?.rect?.left ?: barWidth
        val room = (edge / SLOTS).toInt()
        for (word in words) {
            assertEquals("each slot takes a third of what the buttons leave: $nodes", room.toFloat(), word.rect.width().toFloat(), SLOP_PX)
        }
        val rightmost = words.maxByOrNull { it.rect.right }!!
        if (rightmost.rect.left > edge / 2) {
            assertEquals("the right word must reach the end of the words' room: $nodes", edge.toFloat(), rightmost.rect.right.toFloat(), SLOP_PX)
        }
        assertEquals("nothing here writes", "See you soon ", host.text(chat))
    }

    /** The centre of an empty bar means nothing with no model. It is where the pointer is when there is one. */
    @Test
    fun noModel_emptyCentreTapDoesNothing() {
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)

        kb.tapSlot(Keyboard.Slot.CENTRE)

        assertFalse(
            "with no model the bar must not offer setup. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(LOOK_MS, 100) { kb.visibleTextInImeWindows().any { SETUP_NOTICE in it } },
        )
        assertEquals("", host.text(chat))
    }

    /** No sparkle and no words pointing at one. A mic may still be there, which is not about drafting. */
    @Test
    fun noModel_emptyBarHasNoSparkleAndNoPointer() {
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)

        val nodes = kb.readableBarNodes()

        assertFalse("with no model there is no sparkle. The bar offers: $nodes", kb.sparkleIsThere())
        assertTrue("with no model there is no pointer to it. The bar offers: $nodes", nodes.none { it.label == POINTER })
    }

    /**
     * Proves only that the reader works, on a toast from the foreground host. It says nothing about a toast from the
     * keyboard: an input method is not a foreground app, so the system drops its toasts unless it may post
     * notifications. A failing test's saved system log tells the two apart, with "Suppressing toast from package".
     */
    @Test
    fun control_toastReaderHearsTheForegroundHost() {
        showReady(chat)
        host.onMain { Toast.makeText(it, CONTROL_TOAST, Toast.LENGTH_SHORT).show() }
        val heard = Wait.until(TOAST_MS, 100) { CONTROL_TOAST in E2eDevice.toasts.seen() }
        assertTrue("A toast shown by the host must reach the reader. Heard: ${E2eDevice.toasts.seen()}", heard)
    }

    /**
     * A stored yes with no model behind it lasts one show, and in that show the button's old path still leads to setup.
     * The show after it has no sparkle. So this is the one show, and it must not go through the second show of a repair.
     */
    @Test
    fun holdingSparkleWithNoModel_offersSetup() {
        ime.forceStop()
        kb.seedDraftingAnswer(true)
        ime.select()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSparkle(holdMs = LONG_PRESS_MS)
        assertSetupOffered("holding the button")
    }

    private companion object {
        const val BAR_SETTLE_MS = 500L
        const val LONG_PRESS_MS = 900L
        const val TOAST_MS = 3_000L
        const val CONTROL_TOAST = "A toast from the test host"

        const val LOOK_MS = 1_500L
        const val OFFERED_MS = 3_000L
        const val SETUP_NOTICE = "AI writing needs a model on this phone"
        const val SETUP_TITLE = "Set up AI writing"
        const val POINTER = "Write it for me, empty bar"
        const val VOICE_TYPING = "Voice typing"
        const val SLOTS = 3f
        const val SLOP_PX = 3f
    }
}
