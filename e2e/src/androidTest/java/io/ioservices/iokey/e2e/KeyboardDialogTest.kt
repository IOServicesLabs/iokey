package io.ioservices.iokey.e2e

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import io.ioservices.iokey.e2e.harness.DeviceEnv
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every window the keyboard raises over itself, and what its buttons do. These could not open for a long time: the
 * system refused the window and the keyboard only said so in a message, so a test that stopped at the words on the
 * bar passed while a tap on them failed. Each test here opens the window for real and presses what a person would.
 *
 * What runs past a model's load is out of reach, because no model runs here. A file that only looks like a model gets
 * as far as a failed load, and that is enough to show the path from each button to the draft.
 */
class KeyboardDialogTest : E2eTestBase() {

    /** A wipe removes any model and leaves the keyboard as it is on first install. */
    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    /**
     * Something the size of a model where the active one should be, chosen, so that it counts as able to draft. It is
     * the quick default's file, and that alone would not bring the sparkle. It will not load, and nothing here needs it to.
     */
    private fun aModelFileIsOnThePhone() {
        ime.forceStop()
        E2eDevice.shell.sh("run-as ${ime.pkg} sh -c 'mkdir -p files/models; truncate -s 60M files/models/$FAKE_MODEL_FILE'")
        atEnd {
            ime.forceStop()
            E2eDevice.shell.sh("run-as ${ime.pkg} rm -f files/models/$FAKE_MODEL_FILE")
            kb.forgetDraftingAnswer()
        }
        ime.seedStringPrefs(mapOf("active_model_id" to FAKE_MODEL_ID))
        kb.seedDraftingAnswer(true)
        firstRunIsOver()
    }

    /** Settings is reached from the finished first-run screen. */
    private fun firstRunIsOver() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("onboarding_done" to true, "tutorial_seen" to true))
        ime.select()
    }

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

    private fun boxText(): String? {
        E2eDevice.ua.clearCache()
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString()?.endsWith("EditText") == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { c -> walk(c)?.let { return it } }
            return null
        }
        for (w in E2eDevice.ua.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == ime.pkg) walk(root)?.let { return it.text?.toString().orEmpty() }
        }
        return null
    }

    private fun isUp(text: String) = nodeWith(text) != null

    private fun assertOpens(text: String) {
        assertTrue(
            "'$text' must open. On screen: ${kb.visibleTextInImeWindows()}, field: '${host.text(chat)}'",
            Wait.until(SHOWN_MS, 100) { isUp(text) },
        )
    }

    private fun assertCloses(text: String) {
        assertTrue(
            "'$text' must close. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { !isUp(text) },
        )
    }

    private fun press(text: String) {
        val node = nodeWith(text, exact = true) ?: nodeWith(text)
            ?: throw AssertionError("No '$text' to press. On screen: ${kb.visibleTextInImeWindows()}")
        val r = Rect()
        node.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun holdSlot(slot: Keyboard.Slot) {
        val view = kb.layout().view
        val fraction = when (slot) {
            Keyboard.Slot.LEFT -> 0.125f
            Keyboard.Slot.CENTRE -> 0.375f
            Keyboard.Slot.RIGHT -> 0.70f
        }
        touch.press(view.width() * fraction, (kb.windowState().frame.top + view.top) / 2f, LONG_PRESS_MS)
    }

    private fun topActivity(): String =
        E2eDevice.shell.exec("dumpsys activity activities").lineSequence().firstOrNull { it.contains("topResumedActivity") }.orEmpty()

    // ---------------------------------------------------------------------------------------------
    // Setting up a model
    // ---------------------------------------------------------------------------------------------

    /**
     * A stored yes with no model behind it is how the setup dialog is still reached, and it lasts one show. So this is
     * exactly one show, and it cannot go through the repair of a short window, which is a second.
     */
    private fun openModelSetup() {
        ime.forceStop()
        kb.seedDraftingAnswer(true)
        ime.select()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSparkle()
        assertOpens(SETUP_NOTICE)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertOpens(SETUP_TITLE)
    }

    @Test
    fun modelSetup_notNowClosesItAndTheKeyboardStillTypes() {
        openModelSetup()

        press("Not now")

        assertCloses(SETUP_TITLE)
        assertEquals("", host.text(chat))
        kb.assertIokeyShowing()
        kb.type("a")
        assertText("A")
    }

    /** A refused download must say so. "Downloading" on the bar for something that never started is worse than nothing. */
    @Test
    fun modelSetup_downloadWithTheNetworkOffSaysItFailedAndWhy() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("onboarding_done" to true, "tutorial_seen" to true, "network_allowed" to false))
        ime.select()
        atEnd {
            ime.forceStop()
            ime.seedBooleanPrefs(mapOf("network_allowed" to true))
        }
        openModelSetup()

        press("Download")

        assertCloses(SETUP_TITLE)
        assertOpens(DOWNLOAD_FAILED)
        assertFalse("nothing was downloading. On screen: ${kb.visibleTextInImeWindows()}", isUp("Downloading the"))
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertOpens(SETUP_TITLE)
        assertOpens("switched off")
        press("Not now")
        assertCloses(SETUP_TITLE)
    }

    @Test
    fun modelSetup_chooseAnotherOpensSettings() {
        firstRunIsOver()
        openModelSetup()

        press("Choose another")

        assertTrue(
            "the button must open the app. Top activity: ${topActivity()}",
            Wait.until(SHOWN_MS, 100) { topActivity().contains(ime.pkg) },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The quick prompt
    // ---------------------------------------------------------------------------------------------

    private fun openQuickPrompt() {
        aModelFileIsOnThePhone()
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()
        kb.tapSparkle(holdMs = LONG_PRESS_MS)
        assertOpens(QUICK_PROMPT_TITLE)
        // The keys stay where they are. Reading them may not work over some windows, so this is only a wait.
        Wait.until(SHOWN_MS, 100) { kb.readLayout()?.isLetters == true }
    }

    /** The file is not a model, so a draft that got as far as loading it says it could not. */
    private fun assertADraftWasAttempted(fieldStays: String = "") {
        assertTrue(
            "a draft must run, as far as failing to load the file. On screen: ${kb.visibleTextInImeWindows()}, " +
                "field: '${host.text(chat)}'",
            Wait.until(LOAD_MS, 200) { isUp(LOAD_FAILED) },
        )
        assertEquals("a failed draft must leave the field alone", fieldStays, host.text(chat))
    }

    /** A box that cannot be typed in is worse than no box, and worst if the letters go into the chat underneath. */
    @Test
    fun quickPrompt_typingGoesIntoItsBoxAndNotTheField() {
        openQuickPrompt()

        kb.type("hello")
        SystemClock.sleep(BAR_SETTLE_MS)

        assertEquals("what is typed must not reach the field under the box", "", host.text(chat))
        assertEquals("what is typed must reach the box", "hello", boxText()?.lowercase())
    }

    /** What the box says has to outlast the box closing and the app's field coming back. */
    @Test
    fun quickPrompt_writeHandsBackToTheFieldAndDrafts() {
        openQuickPrompt()
        kb.type("say hello")

        press("Write")

        assertCloses(QUICK_PROMPT_TITLE)
        assertADraftWasAttempted()
        kb.assertIokeyShowing()
    }

    @Test
    fun quickPrompt_emptyWriteStaysOpenAndSaysWhy() {
        openQuickPrompt()

        press("Write")

        assertOpens("Say what to write first")
        assertTrue("the box must stay open", isUp(QUICK_PROMPT_TITLE))
        press("Cancel")
        assertCloses(QUICK_PROMPT_TITLE)
    }

    @Test
    fun quickPrompt_cancelGoesBackToTheFieldAndTypingLandsThere() {
        openQuickPrompt()

        press("Cancel")

        assertCloses(QUICK_PROMPT_TITLE)
        SystemClock.sleep(FIELD_BACK_MS)
        kb.assertIokeyShowing()
        kb.type("a")
        assertText("A")
    }

    @Test
    fun quickPrompt_rewriteReachesTheDraftAndLeavesTheTextAlone() {
        aModelFileIsOnThePhone()
        showReady(chat)
        kb.type("see you soon ")
        assertText("See you soon ")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()
        kb.tapSparkle(holdMs = LONG_PRESS_MS)
        assertOpens(REWRITE)

        press(REWRITE)

        assertCloses(QUICK_PROMPT_TITLE)
        assertADraftWasAttempted(fieldStays = "See you soon ")
    }

    /** Said inside the box: a message on the bar would be gone by the time the app's field came back. */
    @Test
    fun quickPrompt_rewriteOfTooLongATextStaysOpenAndSaysWhy() {
        aModelFileIsOnThePhone()
        val longText = "word ".repeat(260)
        host.setText(chat, longText)
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()
        kb.tapSparkle(holdMs = LONG_PRESS_MS)
        assertOpens(REWRITE)

        press(REWRITE)

        assertOpens("too long to rewrite")
        assertTrue("the box must stay open", isUp(QUICK_PROMPT_TITLE))
        press("Cancel")
        assertCloses(QUICK_PROMPT_TITLE)
        assertEquals(longText, host.text(chat))
    }

    /**
     * Home lands before the app's field comes back, which is also what an app that clears its focus looks like, or just
     * after a draft has started from it, and this test cannot choose which. The box was the only place the instruction
     * was, so either way it has to be kept, and the keyboard must not chase the person to the launcher in the meantime.
     */
    @Test
    fun quickPrompt_leavingAsItClosesKeepsTheInstructionAndOffersItBack() {
        openQuickPrompt()
        kb.type("say hello")

        press("Write")
        touch.key(KeyEvent.KEYCODE_HOME)

        assertTheKeyboardStaysDownOverTheLauncher()
        host.bringToFront()
        showReady(chat)
        assertOpens(PROMPT_OFFER)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertADraftWasAttempted()
    }

    @Test
    fun quickPrompt_leavingAsItCancelsLeavesTheKeyboardDownAndOffersNothing() {
        openQuickPrompt()
        kb.type("say hello")

        press("Cancel")
        touch.key(KeyEvent.KEYCODE_HOME)

        assertTheKeyboardStaysDownOverTheLauncher()
        host.bringToFront()
        showReady(chat)
        assertFalse(
            "a cancelled box has nothing to offer. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(LOOK_MS, 100) { isUp(PROMPT_OFFER) },
        )
    }

    private fun assertTheKeyboardStaysDownOverTheLauncher() {
        kb.waitForHidden()
        assertFalse(
            "the keyboard must not come up after the person has left. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(FIELD_WAIT_MS, 200) { kb.isInputShown() },
        )
    }

    /** Focus can land where no draft can go. That has to be said, and the next field that can take it gets the offer. */
    @Test
    fun quickPrompt_landingOnAnAddressFieldSaysSoAndTheNextFieldOffersIt() {
        openQuickPrompt()
        kb.type("say hello")
        // Where the app's focus will be when its window is back.
        host.onMain { it.field(HostActivity.URL).requestFocus() }

        press("Write")

        assertCloses(QUICK_PROMPT_TITLE)
        assertOpens(RULED_OUT)
        assertEquals("", host.text(HostActivity.URL))
        showReady(chat)
        assertOpens(PROMPT_OFFER)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertADraftWasAttempted()
    }

    /** A thumb landing beside the box must not cost a long instruction. */
    @Test
    fun quickPrompt_aTapOutsideLeavesItOpenWithItsText() {
        openQuickPrompt()
        kb.type("hello")

        val above = host.fieldRect(chat)
        touch.tap(above.exactCenterX(), above.top + 5f)
        SystemClock.sleep(BAR_SETTLE_MS)

        assertTrue("the box must stay open. On screen: ${kb.visibleTextInImeWindows()}", isUp(QUICK_PROMPT_TITLE))
        assertEquals("hello", boxText()?.lowercase())
        press("Cancel")
        assertCloses(QUICK_PROMPT_TITLE)
    }

    /** A rebuilt activity would find its request gone and close, with the text in it. */
    @Test
    fun quickPrompt_aDarkModeChangeLeavesItOpenWithItsText() {
        openQuickPrompt()
        kb.type("hello")

        env.setNightMode(true)
        SystemClock.sleep(DeviceEnv.CONFIG_SETTLE_MS)

        assertTrue("the box must stay open. On screen: ${kb.visibleTextInImeWindows()}", isUp(QUICK_PROMPT_TITLE))
        assertEquals("hello", boxText()?.lowercase())
        press("Cancel")
        assertCloses(QUICK_PROMPT_TITLE)
    }

    // ---------------------------------------------------------------------------------------------
    // The screen-reading offer
    // ---------------------------------------------------------------------------------------------

    private fun openScreenOffer() {
        aModelFileIsOnThePhone()
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()
        kb.tapSparkle()
        assertOpens(OFFER_TITLE)
    }

    private fun assertTheOfferStaysAway() {
        kb.tapSparkle()
        assertFalse(
            "the sparkle must go straight to a draft now. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(LOOK_MS, 100) { isUp(OFFER_TITLE) },
        )
    }

    @Test
    fun screenOffer_notNowDraftsStraightAwayAndStaysAway() {
        openScreenOffer()

        press("Not now")

        assertCloses(OFFER_TITLE)
        assertADraftWasAttempted()
        assertTheOfferStaysAway()
    }

    @Test
    fun screenOffer_dontAskAgainDraftsStraightAwayAndStaysAway() {
        openScreenOffer()

        press("Don't ask again")

        assertCloses(OFFER_TITLE)
        assertADraftWasAttempted()
        assertTheOfferStaysAway()
    }

    @Test
    fun screenOffer_sayWhatToWriteOpensThePrompt() {
        openScreenOffer()

        press("Say what to write")

        assertCloses(OFFER_TITLE)
        assertOpens(QUICK_PROMPT_TITLE)
        press("Cancel")
        assertCloses(QUICK_PROMPT_TITLE)
    }

    @Test
    fun screenOffer_turnOnOpensTheSystemSettings() {
        openScreenOffer()

        press("Turn on")

        assertTrue(
            "the button must open the system's settings. Top activity: ${topActivity()}",
            Wait.until(SHOWN_MS, 100) { topActivity().contains("settings", ignoreCase = true) },
        )
    }

    /** For a phone that greys out the switch: the way to allow it is on the app's own page. */
    @Test
    fun screenOffer_openAppInfoClosesItAndOpensTheSystemPage() {
        openScreenOffer()

        press("Open App info")

        assertCloses(OFFER_TITLE)
        assertTrue(
            "the button must open the system's page for this app. Top activity: ${topActivity()}",
            Wait.until(SHOWN_MS, 100) { topActivity().contains("settings", ignoreCase = true) },
        )
    }

    /** Not able to load is a dead end unless the message leads somewhere. */
    @Test
    fun couldNotLoad_tapOpensSettings() {
        openScreenOffer()
        press("Not now")
        assertADraftWasAttempted()

        kb.tapSlot(Keyboard.Slot.CENTRE)

        assertTrue(
            "the message must open the app. Top activity: ${topActivity()}",
            Wait.until(SHOWN_MS, 100) { topActivity().contains(ime.pkg) },
        )
    }

    /** A message with nothing to do on a tap lets the touch through to the bar, which here starts a draft. */
    @Test
    fun plainNotice_letsATapThroughToTheBar() {
        clearClipboard()
        aModelFileIsOnThePhone()
        ime.forceStop()
        ime.seedStringPrefs(mapOf("active_provider" to "local_style"))
        ime.select()
        showReady(chat)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSparkle()
        assertOpens("basic composer")
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertOpens(TAP_THE_SPARKLE)

        kb.tapSlot(Keyboard.Slot.CENTRE)

        assertCloses(TAP_THE_SPARKLE)
        assertOpens(OFFER_TITLE)
    }

    /** The sparkle offers the basic composer, and a tap on that offer leaves a message with nothing to do on a tap. */
    private fun theSparkleOffersTheComposer() {
        clearClipboard()
        atEnd { clearClipboard() }
        aModelFileIsOnThePhone()
        ime.forceStop()
        ime.seedStringPrefs(mapOf("active_provider" to "local_style"))
        ime.select()
        showReady(chat)
    }

    private fun aPlainNoticeComesUp() {
        kb.tapSparkle()
        assertOpens("basic composer")
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertOpens(TAP_THE_SPARKLE)
    }

    /** The message hides the words under it, so the tap that clears it lands none of them. They are there for the next tap. */
    @Test
    fun plainNotice_aTapTakesItAwayAndLandsNoWordItWasHiding() {
        theSparkleOffersTheComposer()
        kb.type("so ")
        assertText("So ")
        SystemClock.sleep(BAR_SETTLE_MS)
        val aimed = kb.barNodes().firstOrNull { it.rect.exactCenterX() < kb.layout().view.width() / 2f }
            ?: throw AssertionError("This needs a word under the message. The bar offers: ${kb.barNodes()}")
        aPlainNoticeComesUp()

        touch.tap(aimed.rect.exactCenterX(), aimed.rect.exactCenterY())

        assertCloses(TAP_THE_SPARKLE)
        assertTextStays("So ", message = "the tap was aimed at the message, and the word was hidden under it")
        kb.tapBarWord(aimed.label, Keyboard.Slot.CENTRE)
        assertText("So ${aimed.label} ", message = "with the message gone the word takes a tap as ever")
    }

    /** The paste chip lies under the message as a word does, and landing a clip nobody could see is worse. */
    @Test
    fun plainNotice_aTapTakesItAwayAndPastesNothing() {
        theSparkleOffersTheComposer()
        host.onMain {
            (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("test", COPIED))
        }
        SystemClock.sleep(CLIP_SETTLE_MS)
        aPlainNoticeComesUp()

        kb.tapSlot(Keyboard.Slot.LEFT)

        assertCloses(TAP_THE_SPARKLE)
        assertTextStays("", message = "the tap was aimed at the message, and the chip was hidden under it")
        kb.tapSlot(Keyboard.Slot.LEFT)
        assertText(COPIED, message = "with the message gone the chip takes a tap as ever")
    }

    // ---------------------------------------------------------------------------------------------
    // Clipboard history
    // ---------------------------------------------------------------------------------------------

    /** A paste chip takes the place of the bar's pointer for two minutes, whichever test copied it. */
    private fun clearClipboard() {
        host.onMain { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip() }
    }

    private fun openClipboardHistory() {
        atEnd { clearClipboard() }
        showReady(chat)
        host.onMain {
            (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("test", COPIED))
        }
        SystemClock.sleep(CLIP_SETTLE_MS)
        holdSlot(Keyboard.Slot.LEFT)
        assertOpens(CLIP_HISTORY_TITLE)
    }

    @Test
    fun clipboardHistory_tappingAnEntryPastesIt() {
        openClipboardHistory()

        press(COPIED)

        assertCloses(CLIP_HISTORY_TITLE)
        assertText(COPIED)
    }

    @Test
    fun clipboardHistory_manageThenPinMarksTheEntry() {
        openClipboardHistory()

        press("Pin / remove")
        assertOpens(COPIED)
        press(COPIED)

        assertOpens(CLIP_HISTORY_TITLE)
        assertTrue(
            "the entry must come back pinned. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { nodeWith(COPIED)?.text?.startsWith(PINNED) == true },
        )
        press("Cancel")
    }

    @Test
    fun clipboardHistory_manageThenClearEmptiesIt() {
        openClipboardHistory()

        press("Pin / remove")
        assertTrue(
            "the manage sheet must list the entry. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { isUp(COPIED) },
        )
        press("Clear all")
        assertCloses(COPIED)
        assertOpens(CLIP_CLEARED)

        holdSlot(Keyboard.Slot.LEFT)
        assertFalse(
            "a cleared history must not open with its old entries. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(LOOK_MS, 100) { isUp(CLIP_HISTORY_TITLE) && isUp(COPIED) },
        )
        assertEquals("", host.text(chat))
    }

    private fun leaveWithHomeAndComeBack() {
        touch.key(KeyEvent.KEYCODE_HOME)
        kb.waitForHidden()
        host.bringToFront()
    }

    private fun assertTheSheetStaysAway() {
        assertFalse(
            "the sheet must not come back with the keyboard. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(LOOK_MS, 100) { isUp(COPIED) },
        )
    }

    /** A child window hides with the keyboard and comes back with it, so it has to be closed as the keyboard goes. */
    @Test
    fun clipboardHistory_homeClosesItAndItStaysAwayWhenTheKeyboardReturns() {
        openClipboardHistory()

        leaveWithHomeAndComeBack()
        showReady(chat)

        assertTheSheetStaysAway()
    }

    /** That next field could be a password. There must be nothing left over it to tap. */
    @Test
    fun clipboardHistory_leftOpenNeverReachesTheNextField() {
        openClipboardHistory()

        leaveWithHomeAndComeBack()
        showReady(HostActivity.SINGLE)

        assertTheSheetStaysAway()
        assertEquals("", host.text(HostActivity.SINGLE))
        assertEquals("", host.text(chat))
    }

    /** The keyboard can move to another field with no hide on the way. To it, this is another field. */
    @Test
    fun clipboardHistory_closesWhenTheKeyboardMovesToAnotherField() {
        openClipboardHistory()

        host.onMain {
            val field = it.field(chat)
            field.id = ANOTHER_FIELD_ID
            it.getSystemService(InputMethodManager::class.java).restartInput(field)
        }

        assertCloses(COPIED)
        assertEquals("", host.text(chat))
    }

    /** An app formatting its own text starts the keyboard over on the same field. That must not take the sheet away. */
    @Test
    fun clipboardHistory_staysOpenAndStillPastesWhenItsOwnFieldRestarts() {
        openClipboardHistory()

        host.setText(chat, "hello ")
        SystemClock.sleep(BAR_SETTLE_MS)

        assertTrue("the sheet must stay open. On screen: ${kb.visibleTextInImeWindows()}", isUp(COPIED))
        press(COPIED)
        assertCloses(CLIP_HISTORY_TITLE)
        assertText("hello $COPIED")
    }

    // ---------------------------------------------------------------------------------------------
    // Removing a suggestion
    // ---------------------------------------------------------------------------------------------

    private fun openRemoveSuggestion(): String {
        holdSlot(Keyboard.Slot.CENTRE)
        assertOpens(REMOVE_QUESTION)
        return nodeWith(REMOVE_QUESTION)?.text?.toString().orEmpty()
    }

    @Test
    fun removeSuggestion_cancelKeepsTheWordAndRemoveTakesItAway() {
        showReady(chat)
        kb.type("the ")
        SystemClock.sleep(BAR_SETTLE_MS)

        val asked = openRemoveSuggestion()
        press("Cancel")
        assertCloses(REMOVE_QUESTION)
        assertEquals("cancel must keep the word", asked, openRemoveSuggestion())

        press("Remove")
        assertCloses(REMOVE_QUESTION)
        SystemClock.sleep(BAR_SETTLE_MS)
        assertFalse("remove must take the word off the bar", asked == openRemoveSuggestion())
        press("Cancel")
        assertEquals("The ", host.text(chat))
    }

    private companion object {
        const val BAR_SETTLE_MS = 500L
        const val SHOWN_MS = 3_000L
        const val LOOK_MS = 1_500L
        const val LONG_PRESS_MS = 900L
        const val CLIP_SETTLE_MS = 800L
        const val FIELD_BACK_MS = 800L

        /** Longer than the keyboard waits for the app's field after the box closes, which is about 5.3 s. */
        const val FIELD_WAIT_MS = 6_000L

        /** Any id the host's own fields do not use. */
        const val ANOTHER_FIELD_ID = 4242

        /** Generous: the first load of the native library on a busy emulator is not quick. */
        const val LOAD_MS = 20_000L

        /** The default active model's file, so that nothing else has to be set for it to count as present. */
        const val FAKE_MODEL_FILE = "Qwen_Qwen3-0.6B-Q4_K_M.gguf"
        const val FAKE_MODEL_ID = "qwen3_06b"

        const val SETUP_NOTICE = "AI writing needs a model on this phone"
        const val SETUP_TITLE = "Set up AI writing"
        const val DOWNLOAD_FAILED = "download failed"
        const val QUICK_PROMPT_TITLE = "Quick prompt"
        const val REWRITE = "Rewrite this text"
        const val PROMPT_OFFER = "Your prompt didn't run"
        const val RULED_OUT = "Drafts can't go in this field"
        const val OFFER_TITLE = "Screen reading is off"
        const val LOAD_FAILED = "Couldn't load"
        const val TAP_THE_SPARKLE = "Tap the sparkle to draft"
        const val PINNED = "📌"
        const val CLIP_HISTORY_TITLE = "Clipboard"
        const val REMOVE_QUESTION = "from suggestions?"
        const val COPIED = "a note copied from elsewhere"
        const val CLIP_CLEARED = "Clipboard history cleared"
    }
}
