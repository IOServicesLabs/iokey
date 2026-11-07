package io.ioservices.iokey.e2e

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * A draft from start to end with a real model. Everything else stops at the door: no other test here gets past a file
 * that fails to load. It needs two small GGUF files in the emulator's temporary folder, which tools/e2e/llm-model.ps1
 * puts there, and each test skips with a message saying so when its file is missing, so the rest of the suite is
 * unaffected. It is not in the fixed suite.
 *
 * Only the LEAP engine can run on this emulator, and only a model with no half-precision cache, which is what
 * tools/e2e/make-llm-test-model.py writes. So these tests cover the keyboard's flow around that engine. The other engine
 * dies on its first decode here, and what goes on inside it, a stop racing a model switch included, stays untested.
 *
 * The files are padded to pass for models a person would use, because the keyboard takes anything much smaller for a
 * failed download. The loader only checks that the tensors lie inside the file, so the padding does it no harm.
 *
 * These models have random weights and write word salad, and that is all these tests need. How good a draft is, and how
 * fast a phone is, is out of reach here: the emulator runs the arm64 libraries by translation, so its timings say
 * nothing about any phone.
 */
class LlmModelTest : E2eTestBase() {

    /** A wipe removes any model and leaves the keyboard as it is on first install. */
    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    // ---------------------------------------------------------------------------------------------
    // Reading what the keyboard says
    // ---------------------------------------------------------------------------------------------

    /**
     * The bar draws its words itself, so nothing in the window tree holds a draft. What the keyboard announces to
     * accessibility does, and listening on the side leaves the harness's own listener in place. The filter takes in
     * every event and accepts one only to end the wait, which the next event does once the test is over.
     */
    private inner class Announcements {
        private val said = CopyOnWriteArrayList<String>()

        @Volatile
        private var over = false

        private val worker = thread(name = "announcements") {
            while (!over) {
                try {
                    E2eDevice.ua.executeAndWaitForEvent({}, { event ->
                        if (event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT) said += event.text.joinToString(" ")
                        over
                    }, LISTEN_SPAN_MS)
                } catch (_: TimeoutException) {
                    // Only the end of one span of listening.
                }
            }
        }

        fun forget() = said.clear()

        fun drafts(): List<String> = said.filter { it.startsWith(DRAFT_READY) }.map { it.removePrefix(DRAFT_READY) }

        fun close() {
            over = true
            worker.join(LISTEN_SPAN_MS + 1_000)
        }
    }

    private lateinit var said: Announcements

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

    private fun isUp(text: String) = nodeWith(text) != null

    /** The message lying on the bar. The keys and the system's buttons are not text views, and the reader scrolls. */
    private fun noticeOnBar(): String? {
        var found: String? = null
        val barBottom = kb.readLayout()?.view?.top ?: return null
        walkImeWindows { node ->
            if (found == null && node.className?.toString() == TEXT_VIEW && !node.text.isNullOrBlank()) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.bottom <= barBottom + BAR_SLOP_PX) found = node.text.toString()
            }
        }
        return found
    }

    /** What the reader shows, or null when it is shut. */
    private fun readerText(): String? {
        var found: String? = null
        walkImeWindows { node ->
            if (found == null && node.className?.toString() == SCROLL_VIEW) {
                found = node.getChild(0)?.text?.toString()
            }
        }
        return found
    }

    /** Taps what says just this, or failing that what has it in its words: a dialog's message can hold a button's name. */
    private fun press(text: String) {
        var exact: AccessibilityNodeInfo? = null
        walkImeWindows { node ->
            if (exact == null && node.text?.toString().equals(text, ignoreCase = true)) exact = node
        }
        val node = exact ?: nodeWith(text)
            ?: throw AssertionError("No '$text' to press. On screen: ${kb.visibleTextInImeWindows()}")
        val r = Rect()
        node.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun assertOpens(text: String) {
        assertTrue(
            "'$text' must open. On screen: ${kb.visibleTextInImeWindows()}, field: '${host.text(chat)}'",
            Wait.until(SHOWN_MS, 100) { isUp(text) },
        )
    }

    private fun assertCloses(text: String) {
        assertTrue("'$text' must close. On screen: ${kb.visibleTextInImeWindows()}", Wait.until(SHOWN_MS, 100) { !isUp(text) })
    }

    private fun assertReaderShows(draft: String) {
        tapBarText()
        assertTrue(
            "a tap on the bar must open the whole draft, '$draft'. Reader: '${readerText()}'",
            Wait.until(SHOWN_MS, 100) { readerText() == draft },
        )
        tapBarText()
        assertTrue("a second tap shuts it", Wait.until(SHOWN_MS, 100) { readerText() == null })
    }

    private fun assertLands(draft: String, typedBefore: String) {
        tapCheck()
        assertTrue(
            "the draft must land, '$draft'. Field: '${host.text(chat)}'",
            Wait.until(SHOWN_MS, 100) { host.text(chat) != typedBefore },
        )
        val field = host.text(chat)
        assertTrue("what was there stays in front: '$field'", field.startsWith(typedBefore.trimEnd()))
        assertTrue("what lands is the draft, '$draft': '$field'", field.contains(draft.trim()))
    }

    /** For looking at afterwards. The bar's words can be seen no other way. */
    private fun shot(name: String) {
        val dir = E2eDevice.instrumentation.targetContext.getExternalFilesDir("shots") ?: return
        E2eDevice.ua.takeScreenshot()?.let { picture ->
            File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Setting up
    // ---------------------------------------------------------------------------------------------

    /** Copied in after the wipe, as a person's download would have been there before. Nothing else is seeded unless asked. */
    private fun aModelIsOnThePhone(
        onDevice: String = MODEL_ON_DEVICE,
        files: List<String> = listOf(MODEL_FILE),
        modelId: String = MODEL_ID,
        askAboutTheScreen: Boolean = false,
    ) {
        val present = E2eDevice.shell.sh("ls $onDevice 2>/dev/null").contains(onDevice)
        if (!present) {
            val why = "No test model at $onDevice. tools/e2e/llm-model.ps1 puts one there."
            Log.w(TAG, why)
            record("skipped", why)
        }
        assumeTrue("No test model at $onDevice. tools/e2e/llm-model.ps1 puts one there.", present)
        ime.forceStop()
        val copy = files.joinToString("\n") { file ->
            val path = "files/models/$file"
            "cat $onDevice | run-as ${ime.pkg} sh -c 'mkdir -p files/models; cat > $path; " +
                "[ \$(stat -c %s $path) -gt $WORKING_SIZE_BYTES ] || truncate -s $PADDED_SIZE $path'"
        }
        val out = E2eDevice.shell.sh(copy)
        assertEquals("the model must copy without a complaint", "", out.trim())
        ime.seedBooleanPrefs(
            buildMap {
                put("onboarding_done", true)
                put("tutorial_seen", true)
                // So that the numbers can be read off Settings afterwards.
                put("settings_simple", false)
                put("show_advanced", true)
                if (!askAboutTheScreen) put("screen_offer_never", true)
            },
        )
        ime.seedStringPrefs(mapOf("active_model_id" to modelId))
        // The sparkle follows the stored answer, and with nothing stored the first show has none.
        kb.seedDraftingAnswer(true)
        ime.select()
        said = Announcements()
        atEnd {
            said.close()
            ime.forceStop()
            for (file in files) E2eDevice.shell.sh("run-as ${ime.pkg} rm -f files/models/$file")
        }
    }

    private fun showKeyboard(typed: String = "") {
        showReady(chat)
        if (typed.isNotEmpty()) kb.type(typed)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()
    }

    /** Runs [start] and waits for the draft it leads to. Any message on the bar instead fails the test with its words. */
    private fun draftFrom(start: () -> Unit): String {
        said.forget()
        start()
        return awaitDraft()
    }

    private fun awaitDraft(limitMs: Long = DRAFT_MS): String {
        var notice: String? = null
        Wait.until(limitMs, 100) {
            notice = noticeOnBar()
            said.drafts().isNotEmpty() || notice != null
        }
        val draft = said.drafts().lastOrNull()
        record("lastDraft", draft ?: "none")
        assertNotNull(
            "a draft must come, and the bar said '$notice'. Field: '${host.text(chat)}', screen: ${kb.visibleTextInImeWindows().filter { it.length > 1 }}, " +
                "process: ${ime.pid()}",
            draft,
        )
        return draft!!
    }

    private fun tapCross() {
        val view = kb.layout().view
        val dp = host.onMain { it.resources.displayMetrics.density }
        touch.tap(view.right - 72 * dp, (kb.windowState().frame.top + view.top) / 2f)
    }

    /** The check mark, where the sparkle is when no draft is up. */
    private fun tapCheck() = kb.tapSparkle()

    private fun tapBarText() = kb.tapSlot(Keyboard.Slot.CENTRE)

    /** The box takes the keyboard while it is open, so the keys are looked up again. */
    private fun openQuickPrompt() {
        kb.tapSparkle(LONG_PRESS_MS)
        assertOpens(QUICK_PROMPT_TITLE)
        kb.forgetLayout()
        assertTrue(
            "the keyboard must come up on the box. On screen: ${kb.visibleTextInImeWindows().filter { it.length > 1 }}",
            Wait.until(KEYBOARD_BACK_MS, 100) { kb.isInputShown() && kb.readLayout()?.isLetters == true },
        )
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.forgetLayout()
    }

    private fun boxText(): String {
        var found: String? = null
        walkImeWindows { node ->
            if (found == null && node.className?.toString() == EDIT_TEXT) found = node.text?.toString()
        }
        return found.orEmpty()
    }

    /** Back in the field with the keyboard up, whose keys may not be where they were. */
    private fun closeTheBoxWith(button: String) {
        press(button)
        assertCloses(QUICK_PROMPT_TITLE)
        kb.forgetLayout()
    }

    /** What AI diagnostics in Settings says: the model, the prompt as it was sent, and each run's timing. */
    private fun diagnostics(): String {
        openSettings()
        var report: String? = null
        Wait.until(SETTINGS_MS, 200) {
            report = nodeWith(DIAG_MARK)?.text?.toString()
            report != null
        }
        assertNotNull("Settings must show the diagnostics. On screen: ${kb.visibleTextInImeWindows().take(30)}", report)
        host.bringToFront()
        return report!!
    }

    /** Through the app's own first screen and its button, since Settings has no other way in. */
    private fun openSettings() {
        // From the start each time: Settings only says what happened by the time it opened.
        E2eDevice.shell.exec("am start --activity-clear-task -n ${ime.pkg}/$ONBOARDING")
        assertTrue("the first screen must offer Settings", Wait.until(SETTINGS_MS, 200) { isUp(OPEN_SETTINGS) })
        var button: AccessibilityNodeInfo? = null
        walkImeWindows { node ->
            if (button == null && node.isClickable && node.text?.toString().equals(OPEN_SETTINGS, ignoreCase = true)) button = node
        }
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun assertAlive(pid: Int?) {
        assertEquals("the keyboard's process must have lived through this", pid, ime.pid())
    }

    // ---------------------------------------------------------------------------------------------
    // A draft, from the tap to the field
    // ---------------------------------------------------------------------------------------------

    /** The first thing that has to work, and where the numbers come from. */
    @Test
    fun tapGivesADraftForReviewAndWritesNothingYet() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        assertText("See you soon ")
        val pid = ime.pid()

        val tappedAt = SystemClock.uptimeMillis()
        val draft = draftFrom {
            kb.tapSparkle()
            shot("after-tap")
        }
        record("firstDraftMs", SystemClock.uptimeMillis() - tappedAt)
        record("firstDraftText", draft)
        shot("first-draft")

        assertEquals("a draft waits in the bar, out of the field", "See you soon ", host.text(chat))
        tapBarText()
        assertTrue(
            "a tap on the bar must open the whole draft. Reader: '${readerText()}'",
            Wait.until(SHOWN_MS, 100) { readerText() == draft },
        )
        shot("reader")
        tapBarText()
        assertTrue("a second tap shuts it", Wait.until(SHOWN_MS, 100) { readerText() == null })
        assertEquals("See you soon ", host.text(chat))
        assertAlive(pid)

        tapCross()
        SystemClock.sleep(BAR_SETTLE_MS)
        val warmAt = SystemClock.uptimeMillis()
        val second = draftFrom { kb.tapSparkle() }
        record("warmDraftMs", SystemClock.uptimeMillis() - warmAt)
        record("warmDraftText", second)
        assertAlive(pid)

        val report = diagnostics()
        Log.i(TAG, "AI diagnostics:\n$report")
        record("diagnostics", report.substringAfter("load: "))
        assertTrue("the run must be in the log with its timing: $report", report.contains(RUN_TIMING))
        assertAlive(pid)
    }

    @Test
    fun checkMarkWritesTheDraftIntoTheField() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        val typed = host.text(chat)
        val pid = ime.pid()

        val draft = draftFrom { kb.tapSparkle() }
        assertLands(draft, typed)
        val field = host.text(chat)
        record("fieldAfterCheck", field)

        SystemClock.sleep(BAR_SETTLE_MS)
        shot("after-check")
        assertEquals("the reader goes with the draft", null, readerText())
        // The bar's words cannot be read, but tapping one shows whether it went on from the draft.
        tapBarText()
        SystemClock.sleep(BAR_SETTLE_MS)
        record("wordTheBarOfferedAfterTheDraft", host.text(chat).removePrefix(field))
        assertEquals("a tap on the bar now is no check mark for an old draft", null, readerText())
        assertAlive(pid)
    }

    @Test
    fun crossLeavesTheFieldAsItWas() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        val typed = host.text(chat)

        draftFrom { kb.tapSparkle() }
        tapCross()
        assertTextStays(typed, chat, BAR_SETTLE_MS)
        assertEquals("the reader goes with the draft", null, readerText())

        // Out of review, so the same place on the bar starts over and writes nothing.
        draftFrom { kb.tapSparkle() }
        assertEquals(typed, host.text(chat))
    }

    /**
     * Near the end of a run, when it is writing, and early on, while it reads the prompt, which nothing can interrupt, so
     * the next run has to wait for that one to let go. A prompt takes this model most of its run, so a whole run is timed
     * first to find the writing part. On a busy host that stop may find the run already done, and is then a plain cross.
     */
    @Test
    fun stopDuringTheRunLeavesTheKeyboardAliveAndASecondDraftWorks() {
        aModelIsOnThePhone(onDevice = SLOW_MODEL_ON_DEVICE)
        showKeyboard("see you soon ")
        val typed = host.text(chat)
        val pid = ime.pid()

        val wholeRunMs = timedRun()
        record("wholeRunMs", wholeRunMs)
        tapCross()
        SystemClock.sleep(BAR_SETTLE_MS)

        said.forget()
        kb.tapSparkle()
        SystemClock.sleep(wholeRunMs * WRITING_SIXTEENTHS / 16)
        shot("while-writing")
        record("writingStopFoundItDone", said.drafts().isNotEmpty())
        tapCross()
        said.forget()
        SystemClock.sleep(AFTER_A_STOP_MS)
        assertEquals("a stop leaves the field alone", typed, host.text(chat))
        assertTrue("nothing may come of a stopped run: ${said.drafts()}", said.drafts().isEmpty())
        assertAlive(pid)

        kb.tapSparkle()
        SystemClock.sleep(WHILE_READING_MS)
        shot("while-reading")
        assertTrue("this model cannot be done yet: ${said.drafts()}", said.drafts().isEmpty())
        tapCross()
        SystemClock.sleep(AFTER_A_STOP_MS)
        assertEquals("a stop leaves the field alone", typed, host.text(chat))
        assertTrue("nothing may come of a stopped run: ${said.drafts()}", said.drafts().isEmpty())
        assertAlive(pid)

        val at = SystemClock.uptimeMillis()
        val draft = draftFrom {
            kb.tapSparkle()
            SystemClock.sleep(WHILE_READING_MS)
            shot("waiting-for-the-stopped-run")
        }
        record("draftAfterAStopWhileReadingMs", SystemClock.uptimeMillis() - at)
        assertLands(draft, typed)
        assertAlive(pid)
    }

    /** A sparkle tap to its draft, in the time the keyboard takes now. */
    private fun timedRun(): Long {
        val at = SystemClock.uptimeMillis()
        draftFrom { kb.tapSparkle() }
        return SystemClock.uptimeMillis() - at
    }

    /** With nothing streamed yet there is nothing for the check mark to take, and the run goes on. Later it takes what has. */
    @Test
    fun checkMarkDuringTheRunWaitsForTextAndThenTakesWhatThereIs() {
        aModelIsOnThePhone(onDevice = SLOW_MODEL_ON_DEVICE)
        showKeyboard("see you soon ")
        val typed = host.text(chat)
        val pid = ime.pid()

        said.forget()
        val at = SystemClock.uptimeMillis()
        kb.tapSparkle()
        SystemClock.sleep(WHILE_READING_MS)
        tapCheck()
        assertOpens(NOTHING_USABLE)
        assertEquals("nothing to take, so nothing is written", typed, host.text(chat))
        assertTrue("the run must go on to its draft", Wait.until(DRAFT_MS, 100) { said.drafts().isNotEmpty() })
        val wholeRunMs = SystemClock.uptimeMillis() - at
        record("wholeRunMs", wholeRunMs)
        tapCross()
        SystemClock.sleep(BAR_SETTLE_MS)

        said.forget()
        kb.tapSparkle()
        SystemClock.sleep(wholeRunMs * WRITING_SIXTEENTHS / 16)
        record("earlyCheckFoundItDone", said.drafts().isNotEmpty())
        tapCheck()
        assertTrue(
            "the check mark must write what has streamed, or say why not. Field: '${host.text(chat)}', bar: '${noticeOnBar()}'",
            Wait.until(SHOWN_MS, 100) { host.text(chat) != typed || noticeOnBar() != null },
        )
        record("fieldAfterEarlyCheck", host.text(chat))
        record("barAfterEarlyCheck", noticeOnBar().orEmpty())
        val fieldThen = host.text(chat)
        SystemClock.sleep(AFTER_A_STOP_MS)
        if (fieldThen != typed) assertEquals("taking it ends the run, so nothing more comes", fieldThen, host.text(chat))
        assertAlive(pid)
    }

    @Test
    fun typingDropsARunningDraft() {
        aModelIsOnThePhone(onDevice = SLOW_MODEL_ON_DEVICE)
        showKeyboard("see you soon ")
        val pid = ime.pid()

        val ways = listOf<Pair<String, () -> Unit>>(
            "a letter" to { kb.type("k") },
            "Backspace" to { kb.backspace() },
            "Enter" to { kb.tapKey(Keyboard.ENTER) },
            "a glide" to { kb.glide("the") },
        )
        for ((name, way) in ways) {
            val before = host.text(chat)
            said.forget()
            kb.tapSparkle()
            SystemClock.sleep(WHILE_READING_MS)
            assertTrue("this model cannot be done yet: ${said.drafts()}", said.drafts().isEmpty())
            way()
            assertTrue(
                "$name must reach the field, which held '$before'",
                Wait.until(SHOWN_MS, 100) { host.text(chat) != before },
            )
            SystemClock.sleep(AFTER_A_STOP_MS)
            assertTrue("$name drops the run, so no draft comes: ${said.drafts()}", said.drafts().isEmpty())
            assertFalse("and half a run is not kept: $name", isUp(SET_ASIDE))
            assertAlive(pid)
        }

        draftFrom { kb.tapSparkle() }
        assertAlive(pid)
    }

    @Test
    fun shiftPageAndEmojiLeaveAFinishedDraftAlone() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        val typed = host.text(chat)
        val draft = draftFrom { kb.tapSparkle() }

        kb.tapKey(Keyboard.SHIFT)
        SystemClock.sleep(BAR_SETTLE_MS)
        assertReaderShows(draft)

        kb.tapKey(Keyboard.SYMBOLS)
        assertTrue("the page must change", Wait.until(SHOWN_MS, 100) { kb.readLayout()?.isLetters == false })
        kb.forgetLayout()
        assertReaderShows(draft)

        kb.tapKey(Keyboard.LETTERS)
        assertTrue("and change back", Wait.until(SHOWN_MS, 100) { kb.readLayout()?.isLetters == true })
        kb.forgetLayout()
        assertReaderShows(draft)

        kb.tapKey(EMOJI_KEY)
        assertOpens(EMOJI_CLOSE)
        press(EMOJI_CLOSE)
        assertTrue("and back from the emoji", Wait.until(SHOWN_MS, 100) { kb.readLayout()?.isLetters == true })
        kb.forgetLayout()
        assertReaderShows(draft)

        assertEquals("none of it is typing", typed, host.text(chat))
        assertLands(draft, typed)
    }

    @Test
    fun aLetterSetsAFinishedDraftAsideAndTheNoticeBringsItBack() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        val draft = draftFrom { kb.tapSparkle() }

        kb.type("k")
        assertOpens(SET_ASIDE)
        val field = host.text(chat)
        assertTrue("the letter is typed: '$field'", field.endsWith("k", ignoreCase = true))

        press(SET_ASIDE)
        assertCloses(SET_ASIDE)
        assertReaderShows(draft)
        assertEquals("bringing it back writes nothing", field, host.text(chat))
        assertLands(draft, field)
    }

    @Test
    fun theSparkleBringsASetAsideDraftBackToo() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        val draft = draftFrom { kb.tapSparkle() }

        kb.type("k")
        assertOpens(SET_ASIDE)
        val field = host.text(chat)

        said.forget()
        kb.tapSparkle()
        assertCloses(SET_ASIDE)
        assertReaderShows(draft)
        assertTrue("that was no new run: ${said.drafts()}", said.drafts().isEmpty())
        assertLands(draft, field)
    }

    @Test
    fun screenOfferOpensWithAModelAndNotNowGoesOnToADraft() {
        aModelIsOnThePhone(askAboutTheScreen = true)
        showKeyboard("see you soon ")
        val typed = host.text(chat)
        val pid = ime.pid()

        said.forget()
        kb.tapSparkle()
        assertOpens(OFFER_TITLE)
        shot("offer")
        press(NOT_NOW)
        assertCloses(OFFER_TITLE)
        kb.forgetLayout()
        val draft = awaitDraft()
        record("draftAfterNotNow", draft)
        assertEquals(typed, host.text(chat))
        assertAlive(pid)

        tapCross()
        SystemClock.sleep(BAR_SETTLE_MS)
        draftFrom { kb.tapSparkle() }
        assertFalse("Not now means not again for a while", isUp(OFFER_TITLE))
    }

    @Test
    fun screenOfferDontAskAgainGoesOnToADraftAndStaysAway() {
        aModelIsOnThePhone(askAboutTheScreen = true)
        showKeyboard("see you soon ")
        val typed = host.text(chat)

        said.forget()
        kb.tapSparkle()
        assertOpens(OFFER_TITLE)
        press(DONT_ASK)
        assertCloses(OFFER_TITLE)
        kb.forgetLayout()
        awaitDraft()
        assertEquals(typed, host.text(chat))

        tapCross()
        SystemClock.sleep(BAR_SETTLE_MS)
        draftFrom { kb.tapSparkle() }
        assertFalse("it must stay away", isUp(OFFER_TITLE))
    }

    @Test
    fun quickPromptOpensTakesTypingAndItsTextReachesTheDraft() {
        aModelIsOnThePhone()
        showKeyboard()
        val pid = ime.pid()

        openQuickPrompt()
        shot("quick-prompt")
        kb.type("tell them the bus is late")
        val asked = boxText()
        record("typedInTheBox", asked)
        assertTrue("the box must take what is typed: '$asked'", asked.contains("late", ignoreCase = true))

        said.forget()
        closeTheBoxWith(WRITE)
        val draft = awaitDraft()
        record("quickPromptDraft", draft)
        assertEquals("the draft waits for review here too", "", host.text(chat))
        assertLands(draft, "")
        assertAlive(pid)

        val report = diagnostics()
        Log.i(TAG, "AI diagnostics:\n$report")
        assertTrue(
            "what was typed in the box must be in the prompt, '$asked': $report",
            report.contains(asked.trim(), ignoreCase = true),
        )
    }

    /** The source goes only when the check mark says so. The model has no idea when to stop, so it may be cut off instead. */
    @Test
    fun quickPromptRewriteReplacesTheTextOnlyOnTheCheckMark() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        val typed = host.text(chat)

        openQuickPrompt()
        kb.type("make it short")
        said.forget()
        closeTheBoxWith(REWRITE)

        var notice: String? = null
        Wait.until(DRAFT_MS, 100) {
            notice = noticeOnBar()
            said.drafts().isNotEmpty() || notice != null
        }
        val draft = said.drafts().lastOrNull()
        record("rewriteOutcome", draft ?: "notice: $notice")
        assertEquals("nothing moves before the check mark", typed, host.text(chat))
        if (draft == null) {
            assertTrue("only a cut-off rewrite comes to nothing: '$notice'", notice.orEmpty().contains(CUT_OFF))
            return
        }
        tapCheck()
        assertTrue(
            "the rewrite must replace the text. Draft '$draft', field '${host.text(chat)}', bar '${noticeOnBar()}'",
            Wait.until(SHOWN_MS, 100) { host.text(chat).trim() == draft.trim() },
        )
    }

    /** Typing changed the text it was to replace, so the check mark has to find that out and say so. */
    @Test
    fun aSetAsideRewriteIsStillARewrite() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")

        openQuickPrompt()
        said.forget()
        closeTheBoxWith(REWRITE)
        val draft = awaitDraft()

        kb.type("k")
        assertOpens(SET_ASIDE)
        press(SET_ASIDE)
        assertCloses(SET_ASIDE)
        assertReaderShows(draft)

        val field = host.text(chat)
        tapCheck()
        assertOpens(TEXT_HAS_CHANGED)
        assertEquals("nothing is replaced or added", field, host.text(chat))
    }

    /** An app can reuse one box, and one id, for every chat. A draft written for one must not come back over another. */
    @Test
    fun aSetAsideDraftDoesNotComeBackOverAnotherChatInTheSameBox() {
        aModelIsOnThePhone()
        showKeyboard("see you soon ")
        draftFrom { kb.tapSparkle() }

        kb.type("k")
        assertOpens(SET_ASIDE)
        // What an app does on changing chats: the same field, other text, and the input restarted.
        host.setText(chat, "what time is it ")
        SystemClock.sleep(BAR_SETTLE_MS)

        said.forget()
        kb.tapSparkle()
        // A new run announces its draft and one brought back says nothing, so this fails if the old one came back.
        awaitDraft()
    }

    /** Two empty boxes look the same, so a restart is all there is to go on. */
    @Test
    fun aSetAsideDraftFromAnEmptyBoxDoesNotSurviveARestart() {
        aModelIsOnThePhone()
        showKeyboard()
        draftFrom { kb.tapSparkle() }

        kb.type("k")
        assertOpens(SET_ASIDE)
        host.setText(chat, "")
        SystemClock.sleep(BAR_SETTLE_MS)

        press(SET_ASIDE)
        assertOpens(NO_LONGER_FITS)
        assertEquals("", host.text(chat))
    }

    /** The promise to such a field covers every copy of what was read for the draft. */
    @Test
    fun aFieldThatAsksNotToBeRememberedLeavesNoPromptInTheDiagnostics() {
        aModelIsOnThePhone()
        showReady(HostActivity.PRIVATE)
        kb.type("see you soon ")
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.layout()

        draftFrom { kb.tapSparkle() }

        val report = diagnostics()
        assertTrue("the placeholder must stand in: $report", report.contains(PROMPT_NOT_KEPT))
        assertFalse("what was typed must not be there: $report", report.contains("see you soon", ignoreCase = true))
    }

    /** Gone as the box closed, and what was typed still reaches a real draft. */
    @Test
    fun anInstructionLeftBehindByTheBoxComesBackAndReachesTheDraft() {
        aModelIsOnThePhone()
        showKeyboard()
        openQuickPrompt()
        kb.type("tell them the bus is late")
        val asked = boxText()

        press(WRITE)
        touch.key(KeyEvent.KEYCODE_HOME)
        kb.waitForHidden()
        SystemClock.sleep(FIELD_WAIT_MS)

        host.bringToFront()
        kb.forgetLayout()
        showKeyboard()
        assertOpens(PROMPT_OFFER)
        said.forget()
        press(PROMPT_OFFER)
        assertCloses(PROMPT_OFFER)
        awaitDraft()
        assertEquals("the draft waits for review", "", host.text(chat))

        val report = diagnostics()
        assertTrue("what was typed must be in the prompt, '$asked': $report", report.contains(asked.trim(), ignoreCase = true))
    }

    /**
     * A stop, then another model, so that the next draft takes the old model away while the engine is still reading the
     * stopped run's prompt, which nothing can interrupt. The long text is there to make that reading outlast the trip to
     * Settings. The log lines mark the tap, to read against the engine's own lines for the stopped run.
     */
    @Test
    fun stopThenSwitchModelDoesNotCrashAndTheNextDraftUsesTheNewOne() {
        aModelIsOnThePhone(onDevice = SLOW_MODEL_ON_DEVICE, files = listOf(MODEL_FILE, OTHER_MODEL_FILE))
        showKeyboard()
        host.setText(chat, LONG_TEXT)
        SystemClock.sleep(BAR_SETTLE_MS)
        val pid = ime.pid()

        kb.tapSparkle()
        SystemClock.sleep(WHILE_READING_MS)
        tapCross()

        openSettings()
        var row: AccessibilityNodeInfo? = null
        Wait.until(SETTINGS_MS, 200) {
            row = nodeWith(OTHER_MODEL_NAME)
            row != null
        }
        var clickable = row
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertNotNull("Settings must offer '$OTHER_MODEL_NAME'", clickable)
        clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        assertTrue(
            "the row must show it chosen",
            Wait.until(SHOWN_MS, 100) { isUp("$CHOSEN_MARK $OTHER_MODEL_NAME") },
        )
        assertAlive(pid)

        host.bringToFront()
        // Not showKeyboard, which waits for the keyboard's process to be idle, and so for the reading to end.
        kb.show(chat)
        Log.i(TAG, "Sparkle after the switch")
        draftFrom { kb.tapSparkle() }
        assertAlive(pid)

        val report = diagnostics()
        Log.i(TAG, "AI diagnostics:\n$report")
        assertTrue("the draft must have come from the new model: $report", report.contains("$MODEL_LINE $OTHER_MODEL_NAME"))
        assertAlive(pid)
    }

    private companion object {
        const val TAG = "IOKeyE2E"

        const val MODEL_ON_DEVICE = "/data/local/tmp/iokey-test-model.gguf"
        const val SLOW_MODEL_ON_DEVICE = "/data/local/tmp/iokey-test-model-slow.gguf"

        /** A catalog entry whose name sends it to the LEAP engine, the only one that runs on this emulator. */
        const val MODEL_FILE = "LFM2-700M-Q4_K_M.gguf"
        const val MODEL_ID = "lfm2_700m"

        /** Another of the catalog's files on the same engine, to switch to. */
        const val OTHER_MODEL_FILE = "LFM2-1.2B-Q4_K_M.gguf"
        const val OTHER_MODEL_NAME = "LFM2 1.2B"

        /** Over what the keyboard counts as a finished download. */
        const val WORKING_SIZE_BYTES = 50_000_000L
        const val PADDED_SIZE = "60M"

        /** With the prompt around it, as much as fits this model's window: its small vocabulary gives about 2 characters a token. */
        val LONG_TEXT = "see you soon at the bus stop by the old market. ".repeat(48)

        const val DRAFT_READY = "Draft ready: "
        const val OFFER_TITLE = "Screen reading is off"
        const val NOT_NOW = "Not now"
        const val DONT_ASK = "Don't ask again"
        const val QUICK_PROMPT_TITLE = "Quick prompt"
        const val WRITE = "Write"
        const val REWRITE = "Rewrite this text"
        const val CUT_OFF = "ran too long and got cut off"
        const val TEXT_HAS_CHANGED = "The text has changed since"
        const val NOTHING_USABLE = "Nothing usable yet"
        const val EMOJI_KEY = "Emoji"
        const val EMOJI_CLOSE = "ABC"
        const val SET_ASIDE = "Draft set aside"
        const val NO_LONGER_FITS = "no longer fits"
        const val PROMPT_OFFER = "Your prompt didn't run"
        const val PROMPT_NOT_KEPT = "(not kept"
        const val FIELD_WAIT_MS = 6_000L
        const val CHOSEN_MARK = "\u25cf"
        const val MODEL_LINE = "model:"
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS = "Open Settings"
        const val DIAG_MARK = "screen ctx last draft"
        const val RUN_TIMING = "tok/s"
        const val TEXT_VIEW = "android.widget.TextView"
        const val SCROLL_VIEW = "android.widget.ScrollView"
        const val EDIT_TEXT = "android.widget.EditText"

        const val LISTEN_SPAN_MS = 30_000L
        const val BAR_SETTLE_MS = 500L
        const val BAR_SLOP_PX = 4
        const val SHOWN_MS = 3_000L
        const val SETTINGS_MS = 15_000L
        const val KEYBOARD_BACK_MS = 10_000L
        const val LONG_PRESS_MS = 900L

        /** The slow model reads a prompt for several seconds, about three quarters of its run, and writes for the rest. */
        const val WHILE_READING_MS = 1_000L
        const val WRITING_SIXTEENTHS = 13L

        /** Long enough for anything a stopped run was going to do, on its way out, to show. */
        const val AFTER_A_STOP_MS = 2_000L

        /** A load and a run, by translation on a busy host. Long enough to tell slow from stuck. */
        const val DRAFT_MS = 120_000L
    }
}
