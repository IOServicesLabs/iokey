package io.ioservices.iokey.e2e

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Touch
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggestion bar as a person's thumb meets it, read from its nodes: which word is where, what a tap lands, and
 * whether it has a sparkle. How a word fades and how a press feels cannot be seen from here, and neither can a list
 * changing between a finger's down and up, so those rules are in the unit tests of the bar's rules.
 *
 * The sparkle follows one stored answer, read when a show is not a restart and worked out again for the next show.
 * Where a test needs the new answer it waits for that file, because a show that follows too soon would read the old one.
 */
class BarTest : E2eTestBase() {

    /** A wipe removes any model and leaves the keyboard as it is on first install. */
    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    private fun wordNodes(): List<Keyboard.BarNode> = kb.barNodes().filter { it.label !in NOT_WORDS }

    private fun settledWordNodes(): List<Keyboard.BarNode> = Wait.untilStable(BAR_STABLE_MS, 100) { wordNodes() }

    private fun barWidth(): Int = kb.layout().view.width()

    private fun tap(node: Keyboard.BarNode) = touch.tap(node.rect.exactCenterX(), node.rect.exactCenterY())

    /** Something the size of a model where the quick default's file goes. It will not load, and nothing here needs it to. */
    private fun aFakeModelFile() {
        ime.forceStop()
        E2eDevice.shell.sh("run-as ${ime.pkg} sh -c 'mkdir -p files/models; truncate -s 60M files/models/$FAKE_MODEL_FILE'")
        atEnd {
            ime.forceStop()
            E2eDevice.shell.sh("run-as ${ime.pkg} rm -f files/models/$FAKE_MODEL_FILE")
            kb.forgetDraftingAnswer()
        }
    }

    private fun assertTheStoredAnswerBecomes(answer: Boolean) {
        assertTrue(
            "the stored answer must become $answer, and it is ${kb.storedDraftingAnswer()}",
            Wait.until(ANSWER_MS, 100) { kb.storedDraftingAnswer() == answer },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Which word is where
    // ---------------------------------------------------------------------------------------------

    /** Letters nothing is close to: the word as typed is all there is, on the left, where that word always is. */
    @Test
    fun rawOnlyList_drawsLeftAndATapCommitsIt() {
        showReady(chat)
        kb.type("so $NO_NEAR_WORD")
        assertText("So $NO_NEAR_WORD")
        SystemClock.sleep(BAR_SETTLE_MS)

        val words = settledWordNodes()

        assertEquals("the word as typed must be alone. The bar offers: $words", listOf(NO_NEAR_WORD), words.map { it.label })
        assertTrue(
            "it must draw on the left. It is at ${words[0].rect} on a bar ${barWidth()} wide",
            words[0].rect.exactCenterX() < barWidth() / 3f,
        )
        tap(words[0])
        assertText("So $NO_NEAR_WORD ", message = "a tap on it must commit it")
    }

    /** The glide's slide up reads the same slots as the drawing and the tap, so it has to land on what is drawn. */
    @Test
    fun slideUpOntoTheRawWordOnTheLeft_picksIt() {
        kb.show(HostActivity.SINGLE)
        ime.waitIdle()
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        kb.show(chat)
        kb.type("so $NO_NEAR_WORD")
        assertText("So $NO_NEAR_WORD")
        SystemClock.sleep(BAR_SETTLE_MS)
        assertEquals("the word as typed must be alone", listOf(NO_NEAR_WORD), settledWordNodes().map { it.label })

        val grid = kb.layout().view
        val barY = (kb.windowState().frame.top + grid.top) / 2f
        val from = kb.layout().require("r").rect
        touch.glide(
            listOf(Touch.Point(from.exactCenterX(), from.exactCenterY()), Touch.Point(grid.width() * LEFT_SLOT_X, barY)),
            SLIDE_UP_MS,
            dwellEndMs = SLIDE_UP_REST_MS,
        )

        assertTextStays("So $NO_NEAR_WORD ", message = "the word under the finger takes the place of what is typed, with a space")
    }

    /** The word comes from the node's own label and the tap goes to its own place, so what is drawn and what lands must agree. */
    @Test
    fun plainTap_commitsTheWordOnItsNode() {
        showReady(chat)
        kb.type("so hel")
        assertText("So hel")
        SystemClock.sleep(BAR_SETTLE_MS)
        val aimed = settledWordNodes().filter { it.label != "hel" }.maxByOrNull { it.rect.left }
        assertNotNull("the bar must offer a word besides what is typed. It offers: ${kb.barNodes()}", aimed)

        tap(aimed!!)

        assertText("So ${aimed.label} ", message = "the word tapped, and nothing of 'hel'")
    }

    /**
     * Timing-sensitive, and not the proof of the rule: a key lands while a finger holds the chip of the word as typed,
     * and the word goes in with that letter. Holding through the long-press time does nothing on this chip.
     */
    @Test
    fun holdingAChipWhileAKeyLands_commitsNoOldWord() {
        showReady(chat)
        kb.type("so hel")
        assertText("So hel")
        SystemClock.sleep(BAR_SETTLE_MS)
        val chip = settledWordNodes().firstOrNull { it.label == "hel" }
        assertNotNull("the bar must have the word as typed. It offers: ${kb.barNodes()}", chip)
        val key = kb.layout().require("l").rect

        touch.rollover(
            Touch.Point(chip!!.rect.exactCenterX(), chip.rect.exactCenterY()),
            Touch.Point(key.exactCenterX(), key.exactCenterY()),
            holdMs = ROLLOVER_HOLD_MS,
            overlapMs = ROLLOVER_OVERLAP_MS,
            firstLiftsFirst = false,
        )

        assertText("So hell ", message = "the chip commits what the word has become, so no letter is lost")
    }

    // ---------------------------------------------------------------------------------------------
    // The paste chip
    // ---------------------------------------------------------------------------------------------

    private fun clearClipboard() {
        host.onMain { (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip() }
    }

    /** A tap on the left of the bar pastes while the chip is there, so a word offered at that place would be a false promise. */
    @Test
    fun pasteChip_hidesTheLeftWordNode() {
        clearClipboard()
        atEnd { clearClipboard() }
        showReady(chat)
        kb.type("so ")
        assertText("So ")
        SystemClock.sleep(BAR_SETTLE_MS)
        val thirdOfTheBar = barWidth() / 3f
        val left = settledWordNodes().firstOrNull { it.rect.exactCenterX() < thirdOfTheBar }
        assertNotNull("this needs a word on the left. The bar offers: ${kb.barNodes()}", left)

        host.onMain {
            (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("test", COPIED))
        }

        assertTrue(
            "the chip must come up. The bar offers: ${kb.barNodes()}",
            Wait.until(SHOWN_MS, 100) { kb.barNodes().any { it.label == PASTE } },
        )
        val nodes = kb.barNodes()
        val chip = nodes.first { it.label == PASTE }
        assertTrue("the chip belongs on the left, at $chip", chip.rect.exactCenterX() < thirdOfTheBar)
        assertTrue(
            "nothing but the chip may be there. The bar offers: $nodes",
            nodes.none { it.label != PASTE && it.rect.exactCenterX() < thirdOfTheBar },
        )
        assertFalse("the left word's node must be gone. The bar offers: $nodes", nodes.any { it.label == left!!.label })
        assertFalse("the clip's text must not be on a node. The bar offers: $nodes", nodes.any { COPIED.take(10) in it.label })
    }

    /** What the chip pasted is what the next word follows, the same as anything else before the caret. */
    @Test
    fun pasteChip_aGlideFollowsWhatItPasted() {
        clearClipboard()
        atEnd { clearClipboard() }
        kb.show(HostActivity.SINGLE)
        ime.waitIdle()
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
        kb.show(chat)
        kb.type("this ")
        assertText("This ")
        host.onMain {
            (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("test", "let"))
        }
        assertTrue(
            "the chip must come up. The bar offers: ${kb.barNodes()}",
            Wait.until(SHOWN_MS, 100) { kb.barNodes().any { it.label == PASTE } },
        )
        val chip = kb.barNodes().first { it.label == PASTE }
        touch.tap(chip.rect.exactCenterX(), chip.rect.exactCenterY())
        assertText("This let")
        SystemClock.sleep(BAR_SETTLE_MS)

        val i = kb.layout().require("i").rect
        val u = kb.layout().require("u").rect
        val s = kb.layout().require("s").rect
        val start = Touch.Point((i.exactCenterX() + u.exactCenterX()) / 2f, (i.exactCenterY() + u.exactCenterY()) / 2f)
        touch.glide(listOf(start, Touch.Point(s.exactCenterX(), s.exactCenterY())), AMBIGUOUS_GLIDE_MS, dwellEndMs = Keyboard.GLIDE_END_REST_MS)
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(chat) != "This let" }
        assertTextStays("This let us ", message = "the word before the caret is the pasted 'let', so the stroke is 'us'")
    }

    // ---------------------------------------------------------------------------------------------
    // The sparkle follows the stored answer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun noStoredAnswer_firstShowHasNoSparkleAndTheNextDoes() {
        aFakeModelFile()
        ime.seedStringPrefs(mapOf("active_model_id" to FAKE_MODEL_ID))
        ime.select()

        kb.show(chat)
        assertFalse("nothing is stored, so the first show starts with none. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
        assertTheStoredAnswerBecomes(true)
        assertFalse("an answer that changes during a show must not move the slots. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())

        kb.hideWithBack()
        kb.show(chat)
        assertTrue("the next show has it. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
    }

    /** A model deleted while no show was up can leave this. It costs one show, and no slot moves during it. */
    @Test
    fun storedYesWithNoFile_sparkleForOneShowThenGone() {
        ime.forceStop()
        kb.seedDraftingAnswer(true)
        ime.select()

        kb.show(chat)
        assertTrue("the stored answer stands for this show. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
        assertTheStoredAnswerBecomes(false)
        assertTrue("an answer that changes during a show must not move the slots. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())

        kb.hideWithBack()
        kb.show(chat)
        assertFalse("the next show has none. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
    }

    /**
     * A quick default on the phone and nothing chosen is no drafting model, so the answer turns off. Choosing it on the
     * bar writes the stored id with no Settings screen in the way, and only a listener can turn the answer back on.
     */
    @Test
    fun choosingAModelInTheKeyboard_theNextShowHasTheSparkle() {
        aFakeModelFile()
        ime.seedStringPrefs(mapOf("active_provider" to "local_style"))
        kb.seedDraftingAnswer(true)
        ime.select()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        SystemClock.sleep(BAR_SETTLE_MS)
        assertTheStoredAnswerBecomes(false)

        kb.tapSparkle()
        assertTrue(
            "the sparkle must offer the model on the phone. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(SHOWN_MS, 100) { kb.visibleTextInImeWindows().any { COMPOSER_NOTICE in it } },
        )
        kb.tapSlot(Keyboard.Slot.CENTRE)
        assertTheStoredAnswerBecomes(true)

        kb.hideWithBack()
        kb.show(chat)
        assertTrue("the next show has the sparkle. The bar offers: ${kb.barNodes()}", kb.sparkleIsThere())
    }

    /** A failed load can be low memory, so it takes no sparkle away, and a process coming up cold must not hop one in or out. */
    @Test
    fun failedLoad_keepsTheSparkleAndColdShowKeepsTheSlots() {
        aFakeModelFile()
        ime.seedStringPrefs(mapOf("active_model_id" to FAKE_MODEL_ID))
        ime.seedBooleanPrefs(mapOf("screen_offer_never" to true))
        kb.seedDraftingAnswer(true)
        ime.select()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        SystemClock.sleep(BAR_SETTLE_MS)
        val before = sparkleSpan()

        kb.tapSparkle()
        assertTrue(
            "the draft must get as far as a failed load. On screen: ${kb.visibleTextInImeWindows()}",
            Wait.until(LOAD_MS, 200) { kb.visibleTextInImeWindows().any { LOAD_FAILED in it } },
        )

        kb.hideWithBack()
        kb.show(chat)
        assertEquals("the sparkle after a failed load", before, sparkleSpan())

        ime.forceStop()
        ime.select()
        kb.show(chat)
        assertEquals("the sparkle after a cold show", before, sparkleSpan())
    }

    /** Across the bar only: a window that came up short is another test's business. */
    private fun sparkleSpan(): Pair<Int, Int> {
        val nodes = kb.barNodes()
        val sparkle = nodes.firstOrNull { it.label == SPARKLE }
            ?: throw AssertionError("The sparkle must be there. The bar offers: $nodes")
        return sparkle.rect.left to sparkle.rect.right
    }

    // ---------------------------------------------------------------------------------------------
    // What starts a model
    // ---------------------------------------------------------------------------------------------

    private fun theNextWordSwitchIsOn() {
        aFakeModelFile()
        ime.seedBooleanPrefs(
            mapOf(
                "onboarding_done" to true,
                "tutorial_seen" to true,
                // So that the load can be read off Settings afterwards.
                "settings_simple" to false,
                "show_advanced" to true,
                "llm_suggestions" to true,
            ),
        )
        ime.select()
    }

    private fun walkImeWindows(visit: (AccessibilityNodeInfo) -> Unit) {
        E2eDevice.ua.clearCache()
        fun walk(node: AccessibilityNodeInfo) {
            visit(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        for (w in E2eDevice.ua.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == ime.pkg) walk(root)
        }
    }

    private fun nodeWith(text: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walkImeWindows { node ->
            if (found == null && node.text?.toString()?.contains(text, ignoreCase = true) == true) found = node
        }
        return found
    }

    /** What AI diagnostics in Settings says. Settings shares the keyboard's process, and that is the only record of a load. */
    private fun diagnostics(): String {
        E2eDevice.shell.exec("am start --activity-clear-task -n ${ime.pkg}/$ONBOARDING")
        assertTrue("the first screen must offer Settings", Wait.until(SETTINGS_MS, 200) { nodeWith(OPEN_SETTINGS) != null })
        var button: AccessibilityNodeInfo? = null
        walkImeWindows { node ->
            if (button == null && node.isClickable && node.text?.toString().equals(OPEN_SETTINGS, ignoreCase = true)) button = node
        }
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var report: String? = null
        Wait.until(SETTINGS_MS, 200) {
            report = nodeWith(DIAG_MARK)?.text?.toString()
            report != null
        }
        assertNotNull("Settings must show the diagnostics. On screen: ${kb.visibleTextInImeWindows().take(30)}", report)
        host.bringToFront()
        return report!!
    }

    /**
     * A show reads the words already in the field, and that is no reason to load anything. Until the trail is read from
     * the field this passes with nothing to test, and it means something only with that in.
     */
    @Test
    fun showIntoAFieldHoldingText_startsNoLoad() {
        theNextWordSwitchIsOn()
        host.setText(chat, "see you soon ")
        showReady(chat)
        SystemClock.sleep(LANE_SETTLE_MS)

        val report = diagnostics()

        assertFalse("nothing was typed, so nothing may load. Diagnostics: $report", report.lineSequence().any { it.startsWith(LOAD_LINE) })
    }

    /** The other half, so the test above cannot pass by the path never running at all. */
    @Test
    fun typedWordAfterAShow_startsALoad() {
        theNextWordSwitchIsOn()
        showReady(chat)
        kb.type("see you soon ")
        assertText("See you soon ")
        SystemClock.sleep(LANE_SETTLE_MS)

        val report = diagnostics()

        assertTrue("a typed word must ask the model, which means loading it. Diagnostics: $report", report.lineSequence().any { it.startsWith(LOAD_LINE) })
    }

    /**
     * What the keyboard held of a word still composing goes when it hides, so nothing runs for it with the keyboard down.
     * The field keeps the word all the same, and typing on from it after coming back makes one word. That nothing was
     * read or run meanwhile cannot be seen from here, because the host counts no reads.
     */
    @Test
    fun hidingWithAWordComposing_losesNothing() {
        showReady(chat)
        kb.type("so far goo")
        assertText("So far goo")

        kb.hideWithBack()
        assertTextStays("So far goo", message = "hiding must leave the word in the field")

        kb.show(chat)
        assertTextStays("So far goo", message = "coming back must leave the word in the field")
        assertComposing(null, message = "the word is plain text now, and the next letter must not write it again")
        kb.type("d ")
        assertTextStays("So far good ", message = "typing on lands right after it")
    }

    private companion object {
        const val BAR_SETTLE_MS = 600L
        const val BAR_STABLE_MS = 2_000L
        const val SHOWN_MS = 3_000L
        const val GLIDE_PAUSE_MS = 400L

        /** The answer is worked out on a worker as the show begins. A first look at the disk on a busy emulator is not quick. */
        const val ANSWER_MS = 10_000L

        /** Generous: the first load of the native library on a busy emulator is not quick. */
        const val LOAD_MS = 20_000L

        /** The next-word path waits a moment before asking, and the native library has to load before the file can fail. */
        const val LANE_SETTLE_MS = 6_000L

        const val SETTINGS_MS = 15_000L
        const val AMBIGUOUS_GLIDE_MS = 500L
        const val GLIDE_COMMIT_CEILING_MS = 2_500L
        const val SLIDE_UP_MS = 80L

        /** Well past what a chip that takes something back asks of a finger, though the word as typed asks nothing. */
        const val SLIDE_UP_REST_MS = 300L
        const val LEFT_SLOT_X = 0.125f

        /** The chip is held past the long-press time, with room for the key's pass to land before it lifts. */
        const val ROLLOVER_HOLD_MS = 300L
        const val ROLLOVER_OVERLAP_MS = 60L

        /** Invented letters that nothing in the dictionary is close to. */
        const val NO_NEAR_WORD = "qxzvk"
        const val COPIED = "a note copied from elsewhere"

        const val SPARKLE = "Write it for me"
        const val POINTER = "Write it for me, empty bar"
        const val PASTE = "Paste"
        const val VOICE_TYPING = "Voice typing"
        val NOT_WORDS = setOf(SPARKLE, POINTER, PASTE, VOICE_TYPING)

        const val COMPOSER_NOTICE = "basic composer"
        const val LOAD_FAILED = "Couldn't load"
        const val LOAD_LINE = "load:"
        const val FAKE_MODEL_FILE = "Qwen_Qwen3-0.6B-Q4_K_M.gguf"
        const val FAKE_MODEL_ID = "qwen3_06b"
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS = "Open Settings"
        const val DIAG_MARK = "screen ctx last draft"
    }
}
