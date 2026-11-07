package io.ioservices.iokey.e2e

import android.os.SystemClock
import android.view.KeyEvent
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message the keyboard shows inside its own window, reached the one way that needs no model: the drafting
 * button in the one show a stored answer lasts with no model behind it. What a message says after a real model has run
 * is out of this suite's reach, but every message goes through the same view, so what these find about the view holds
 * for all of them.
 */
class DraftNoticeTest : E2eTestBase() {

    /** A wipe removes any model and leaves the keyboard as it is on first install. */
    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    private fun noticeIsUp() = kb.visibleTextInImeWindows().any { SETUP_NOTICE in it }

    /**
     * A stored yes with no model behind it. The show works the answer out again for the next one, so this must be
     * exactly one show, and it cannot go through the repair of a short window, which is a second show. A rare short
     * show fails a test that uses this.
     */
    private fun showOnceWithAStoredYes(field: String = chat) {
        ime.forceStop()
        kb.seedDraftingAnswer(true)
        ime.select()
        kb.show(field)
        ime.waitIdle()
        kb.assertIokeyShowing()
    }

    /** A file that looks like a model, chosen, and the basic composer doing the writing. The sparkle stays over any number of shows. */
    private fun theSparkleStaysForEveryShow() {
        ime.forceStop()
        E2eDevice.shell.sh("run-as ${ime.pkg} sh -c 'mkdir -p files/models; truncate -s 60M files/models/$FAKE_MODEL_FILE'")
        atEnd {
            ime.forceStop()
            E2eDevice.shell.sh("run-as ${ime.pkg} rm -f files/models/$FAKE_MODEL_FILE")
            kb.forgetDraftingAnswer()
        }
        ime.seedStringPrefs(mapOf("active_model_id" to FAKE_MODEL_ID, "active_provider" to "local_style"))
        kb.seedDraftingAnswer(true)
        ime.select()
    }

    private fun assertTheSparkleIsThere(where: String) {
        SystemClock.sleep(BAR_SETTLE_MS)
        assertTrue("$where must have the sparkle, or this test has nothing to test. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
    }

    private fun assertNoSparkle(field: String) {
        SystemClock.sleep(BAR_SETTLE_MS)
        assertFalse("$field must not offer drafting. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
        assertEquals("", host.text(field))
    }

    private fun tapSparkleAndSeeTheNotice() {
        kb.tapSparkle()
        assertTrue(
            "the button must bring up the message. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { noticeIsUp() },
        )
    }

    /** A message that shifts the keys under a thumb would be worse than no message. */
    @Test
    fun notice_movesNoKeyAndLeavesTheWindowAlone() {
        showOnceWithAStoredYes()
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.forgetLayout()
        val keysBefore = kb.layout()
        val windowBefore = kb.windowState()

        tapSparkleAndSeeTheNotice()
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.forgetLayout()
        val keysWith = kb.layout()
        val windowWith = kb.windowState()

        assertEquals("the view holding the keys", keysBefore.view, keysWith.view)
        assertEquals("the keys", keysBefore.faces, keysWith.faces)
        assertEquals("the window", windowBefore.frame, windowWith.frame)
        assertEquals("where the window takes touches", windowBefore.touchable, windowWith.touchable)
    }

    @Test
    fun notice_goesOnTheNextKeyPressAndTheKeyStillTypes() {
        showOnceWithAStoredYes()
        SystemClock.sleep(BAR_SETTLE_MS)
        tapSparkleAndSeeTheNotice()

        kb.type("a")

        assertTrue(
            "a key press must take the message away. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { !noticeIsUp() },
        )
        assertText("A")
    }

    @Test
    fun notice_tapOpensTheSetupDialogAndWritesNothing() {
        showOnceWithAStoredYes()
        SystemClock.sleep(BAR_SETTLE_MS)
        tapSparkleAndSeeTheNotice()

        kb.tapSlot(Keyboard.Slot.CENTRE)
        atEnd { touch.key(KeyEvent.KEYCODE_BACK) }

        assertTrue(
            "a tap on the message must do what it says. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { kb.visibleTextInImeWindows().any { SETUP_TITLE in it } },
        )
        assertEquals("the setup dialog must leave the field alone", "", host.text(chat))
    }

    /** The bar's pointer says "Write it for me", and it should do that when tapped. */
    @Test
    fun emptyBar_tapStartsTheSameFlowAsTheButton() {
        showOnceWithAStoredYes()
        SystemClock.sleep(BAR_SETTLE_MS)

        kb.tapSlot(Keyboard.Slot.CENTRE)

        assertTrue(
            "a tap on the empty bar must start drafting, which here means offering setup. " +
                "On screen: ${kb.visibleTextInImeWindows()}, field: '${host.text(chat)}'",
            Wait.until(SHOWN_MS, 100) { noticeIsUp() },
        )
        assertEquals("", host.text(chat))
    }

    /** Only the pointer's own words start a draft. A thumb landing short of the keys in a side slot means nothing. */
    @Test
    fun emptyBar_sideSlotsDoNotStartADraft() {
        showOnceWithAStoredYes()
        SystemClock.sleep(BAR_SETTLE_MS)

        kb.tapSlot(Keyboard.Slot.LEFT)
        kb.tapSlot(Keyboard.Slot.RIGHT)

        assertFalse(
            "a tap beside the pointer must do nothing. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(PASSWORD_WATCH_MS, 100) { noticeIsUp() },
        )
    }

    /** A tap aimed at the button must not run what the message offers instead. */
    @Test
    fun tappableNotice_leavesTheSparkleUncovered() {
        showOnceWithAStoredYes()
        SystemClock.sleep(BAR_SETTLE_MS)
        tapSparkleAndSeeTheNotice()

        kb.tapSparkle()

        assertFalse(
            "a tap on the button must not count as a tap on the message. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(PASSWORD_WATCH_MS, 100) { kb.visibleTextInImeWindows().any { SETUP_TITLE in it } },
        )
        assertTrue("the button says the same thing again", noticeIsUp())
    }

    /** The chat shows the sparkle first, or a bar that never had one would pass. A tap to find out would land on a word or the mic. */
    @Test
    fun passwordField_hasNoDraftingAndTheNextFieldGetsItBack() {
        theSparkleStaysForEveryShow()
        showReady(chat)
        assertTheSparkleIsThere(chat)

        showReady(HostActivity.PASSWORD)
        assertNoSparkle(HostActivity.PASSWORD)

        showReady(chat)
        assertTheSparkleIsThere("the next field")
        kb.tapSparkle()
        assertTrue(
            "the button must work there. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { kb.visibleTextInImeWindows().any { COMPOSER_NOTICE in it } },
        )
    }

    /** An address is no place for a draft, and nothing a model writes would be one. */
    @Test
    fun addressFields_haveNoDrafting() {
        theSparkleStaysForEveryShow()
        for (field in listOf(HostActivity.URL, HostActivity.EMAIL)) {
            showReady(chat)
            assertTheSparkleIsThere(chat)

            showReady(field)
            assertNoSparkle(field)
        }
    }

    /** An app that turns the bar's words off has not ruled out asking for a draft. */
    @Test
    fun fieldWithoutSuggestions_stillOffersDrafting() {
        showOnceWithAStoredYes(HostActivity.NO_SUGGESTIONS)
        SystemClock.sleep(BAR_SETTLE_MS)
        tapSparkleAndSeeTheNotice()
    }

    private companion object {
        const val BAR_SETTLE_MS = 500L
        const val SHOWN_MS = 3_000L
        const val PASSWORD_WATCH_MS = 1_500L
        const val SETUP_NOTICE = "AI writing needs a model on this phone"
        const val SETUP_TITLE = "Set up AI writing"
        const val COMPOSER_NOTICE = "basic composer"

        /** The quick default's file and its id, so that a file on the phone and a choice of it are all there is to it. */
        const val FAKE_MODEL_FILE = "Qwen_Qwen3-0.6B-Q4_K_M.gguf"
        const val FAKE_MODEL_ID = "qwen3_06b"
    }
}
