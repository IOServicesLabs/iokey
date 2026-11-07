package io.ioservices.iokey.e2e

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Typing numbers section is how later decisions get made from the phone itself, and it sits on a settings
 * screen a user may copy from, so it must hold no trace of what was typed. Each block reads 'not measured' until the
 * line that feeds it has been called. A row that depends on such a line skips rather than passes when it finds that,
 * so the results show which rows proved nothing.
 */
class PhoneNumbersTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val shell get() = E2eDevice.shell

    private fun root(): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        return E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg }
    }

    private fun collectText(node: AccessibilityNodeInfo, into: MutableList<String>) {
        node.text?.toString()?.let { into += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, into) }
    }

    private fun screenText(): List<String> {
        val root = root() ?: return emptyList()
        return ArrayList<String>().also { collectText(root, it) }
    }

    private fun findById(id: String): AccessibilityNodeInfo? =
        root()?.findAccessibilityNodeInfosByViewId("${ime.pkg}:id/$id")?.firstOrNull()

    private fun findByText(text: String): AccessibilityNodeInfo? =
        root()?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }

    private fun waitFor(what: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { node = find(); node != null }) { "$what. The screen reads: ${screenText()}" }
        return node!!
    }

    private fun click(what: String, find: () -> AccessibilityNodeInfo?) {
        check(waitFor("No $what", find).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "The $what did not take the click" }
    }

    /** Past first run, with everything in Settings open, and [field] given a hint nobody should ever see again. */
    private fun prepare(field: String = chat) {
        ime.forceStop()
        ime.seedBooleanPrefs(
            mapOf("onboarding_done" to true, "tutorial_seen" to true, "show_advanced" to true, "settings_simple" to false),
        )
        atEnd { ime.clearData() }
        ime.select()
        host.onMain { it.field(field).hint = HINT }
    }

    private fun showOn(field: String = chat) {
        prepare(field)
        showReady(field)
    }

    private fun typeWords(field: String = chat): List<String> {
        kb.type(TYPED)
        assertTrue(
            "The words must reach the field, or this proves nothing: '${host.text(field)}'",
            Wait.until(TEXT_MS, 40) { host.text(field).trim().split(" ").size == TYPED.trim().split(" ").size },
        )
        return host.text(field).lowercase().trim().split(" ")
    }

    /** A stalled emulator can stretch a tap into a long press, which costs that one letter and says nothing about the count. */
    private fun everyKeyLanded(inTheField: List<String>) = inTheField.joinToString(" ") == TYPED.trim()

    /** How many readings a block's figures rest on, or null while it reads 'not measured'. */
    private fun readingsIn(block: String): Int? = READINGS.find(block)?.groupValues?.get(1)?.toInt()

    private fun clickMotionAndReadSection(): List<String> {
        click("button to the screen that holds the numbers") { findById(MOTION_BUTTON_ID) }
        var text = emptyList<String>()
        check(Wait.until(SCREEN_MS, 200) { text = screenText(); text.any { it == SECTION } }) {
            "No '$SECTION' section on the screen. It reads: $text"
        }
        return text
    }

    /** Through the app's own screens and buttons, the way a user gets there. */
    private fun openSection(hideKeyboardFirst: Boolean = true): List<String> {
        if (hideKeyboardFirst) kb.hideWithBack()
        shell.exec("am start -n ${ime.pkg}/$ONBOARDING")
        val button = waitFor("The launcher screen has no '$OPEN_SETTINGS_LABEL' button") { findByText(OPEN_SETTINGS_LABEL) }
        val r = Rect()
        button.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
        return clickMotionAndReadSection()
    }

    /** A block is one piece of text that opens with its title, so its numbers can be read apart from the rest. */
    private fun block(screen: List<String>, title: String): String =
        screen.firstOrNull { it.startsWith("$title\n") } ?: throw AssertionError("No '$title' block. The screen reads: $screen")

    private fun skipUntilItsLineIsIn(block: String, line: String) {
        assumeFalse("$line has not been called, so this row proves nothing yet: $block", block.contains(NOT_MEASURED))
    }

    @Test
    fun section_showsItsNumbers_andNothingTyped() {
        showOn()
        val inTheField = typeWords()

        val screen = openSection()

        for (title in BLOCKS) block(screen, title)
        val keyToCommit = block(screen, KEY_TO_COMMIT)
        assertTrue("The figures must carry the build type:\n$keyToCommit", BUILD_TYPE.containsMatchIn(keyToCommit))
        assertTrue("The figures must say since when:\n$keyToCommit", keyToCommit.contains("since process started "))
        assertTrue(
            "The keyboard started in this process, so its cold start must have a line:\n${block(screen, COLD_START)}",
            ENGINES_BUILT.containsMatchIn(block(screen, COLD_START)),
        )
        assertTrue(block(screen, COUNTS), block(screen, COUNTS).contains("correction_fired:"))
        val readings = readingsIn(keyToCommit)
        if (readings != null && everyKeyLanded(inTheField)) {
            assertEquals("Each key of '$TYPED', Space included, must make one reading:\n$keyToCommit", TYPED.length, readings)
        }
        record("keyToCommit", keyToCommit)
        record("liftDecode", block(screen, LIFT_DECODE))
        record("coldStart", block(screen, COLD_START))

        val everything = screen.joinToString("\n").lowercase()
        for (word in inTheField) {
            assertFalse("'$word' was typed and must not be on this screen:\n$everything", everything.contains(word))
        }
        assertFalse("The field's hint must not be on this screen:\n$everything", everything.contains(HINT.lowercase()))
    }

    /** The keyboard came up on the field, so the show has a figure, and the block no longer reads 'not measured'. */
    @Test
    fun keyboardShow_hasAReading() {
        showOn()

        val show = block(openSection(), KEYBOARD_SHOW)

        record("keyboardShow", show)
        assertTrue("A show has happened, so it must have a reading:\n$show", (readingsIn(show) ?: 0) >= 1)
    }

    /** A test without focus cannot read the clipboard, so the host pastes it, the way the user would. */
    private fun pasteIntoTheHost(field: String): String {
        host.bringToFront()
        var pasted = ""
        Wait.until(TEXT_MS * 5, 200) {
            pasted = host.onMain {
                val box = it.field(field)
                box.setText("")
                box.requestFocus()
                box.onTextContextMenuItem(android.R.id.paste)
                box.text.toString()
            }
            pasted.isNotEmpty()
        }
        return pasted
    }

    private fun clipIsMarkedSensitive(): Boolean = host.onMain {
        it.getSystemService(ClipboardManager::class.java).primaryClipDescription
            ?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
    }

    /** A paste chip takes the place of the bar's pointer for two minutes, whichever test copied it. */
    private fun clearTheClipboardAtEnd() {
        atEnd { host.onMain { it.getSystemService(ClipboardManager::class.java).clearPrimaryClip() } }
    }

    @Test
    fun copy_holdsNothingTyped() {
        showOn()
        clearTheClipboardAtEnd()
        val inTheField = typeWords()
        openSection()

        click("copy button") { findByText(COPY_LABEL) }
        val pasted = pasteIntoTheHost(chat)

        assertTrue("The copy must not be empty", pasted.isNotBlank())
        for (title in BLOCKS) assertTrue("'$title' must be in the copy:\n$pasted", pasted.contains(title))
        // The window section's hint says to copy it, and this is the one copy button on the screen.
        for (part in listOf(WINDOW_HEADING, SHORT_WINDOWS)) assertTrue("'$part' must be in the copy:\n$pasted", pasted.contains(part))
        for (word in inTheField) {
            assertFalse("'$word' was typed and must not be in the copy:\n$pasted", pasted.lowercase().contains(word))
        }
        assertFalse("The field's hint must not be in the copy:\n$pasted", pasted.contains(HINT, ignoreCase = true))
        for (heading in AI_DIAGNOSTICS_HEADINGS) {
            assertFalse("The AI diagnostics must not be in the copy, but '$heading' is:\n$pasted", pasted.contains(heading))
        }
        // It names the apps typed into, so whatever reads the clipboard has to be told to keep it out of sight.
        assertTrue("The copy must be marked sensitive", clipIsMarkedSensitive())
        // Shown clean above, so the run's results can say what the section really reads.
        record("report", pasted)
    }

    private fun pasteChip(): Keyboard.BarNode {
        var chip: Keyboard.BarNode? = null
        check(Wait.until(SCREEN_MS, 100) { chip = kb.barNodes().firstOrNull { it.label == PASTE_CHIP }; chip != null }) {
            "No paste chip. The bar offers: ${kb.barNodes()}"
        }
        return chip!!
    }

    /**
     * The report names the apps typed into, and the sheet cannot take out one row, so it must never get in. Its chip still
     * comes up, as for any fresh clip. The plain clip is there because an empty history opens no sheet.
     */
    @Test
    fun copy_staysOutOfClipHistory() {
        showOn()
        clearTheClipboardAtEnd()
        host.onMain { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", PLAIN_CLIP)) }
        pasteChip()
        openSection()

        click("copy button") { findByText(COPY_LABEL) }
        var pasted = ""
        val copied = Wait.until(SCREEN_MS, 200) { pasted = pasteIntoTheHost(chat); pasted.startsWith(REPORT_TITLE) }
        assertTrue("The report must be what is on the clipboard: '$pasted'", copied)
        assertTrue("History refuses a clip this long, so this would prove nothing:\n$pasted", pasted.length <= LONGEST_CLIP_KEPT)
        host.setText(chat, "")
        showReady(chat)
        val chip = pasteChip()
        touch.press(chip.rect.exactCenterX(), chip.rect.exactCenterY(), LONG_PRESS_MS)

        var sheet = emptyList<String>()
        val opened = Wait.until(SCREEN_MS, 100) { sheet = kb.visibleTextInImeWindows(); sheet.any { it.contains(PLAIN_CLIP) } }
        assertTrue("The sheet must open on the plain clip. On screen: $sheet", opened)
        assertFalse("The report must not be in clip history. On screen: $sheet", sheet.any { it.contains(REPORT_TITLE) })
        assertFalse("The report must not be stored either", ime.readPrefsXml().orEmpty().contains(REPORT_TITLE))
        touch.key(KeyEvent.KEYCODE_BACK)

        // The next plain clip clears the mark and the same words go in, so the checks above were able to fail.
        host.onMain { it.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", pasted)) }
        assertTrue(
            "The same words with no mark must reach clip history",
            Wait.until(SCREEN_MS, 200) { ime.readPrefsXml().orEmpty().contains(REPORT_TITLE) },
        )
    }

    /**
     * A force-stop would show only as user-requested, and this is what the system's own kill looks like. There is no keyboard
     * to hide afterwards, and Back with the process gone would go nowhere.
     */
    @Test
    fun killedProcess_showsInDeaths() {
        showOn()
        ime.killProcess(waitForRestart = false)

        openSection(hideKeyboardFirst = false)

        var deaths = ""
        val seen = Wait.until(DEATH_RECORD_MS, 500) {
            deaths = screenText().firstOrNull { it.startsWith("$DEATHS\n") }.orEmpty()
            // The newest row, because Android's list outlives a wipe and can still hold an earlier test's kill.
            KILLED.containsMatchIn(deaths.lines().firstOrNull { DEATH_ROW.containsMatchIn(it) }.orEmpty()) || run {
                findByText(REFRESH_LABEL)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                false
            }
        }
        assertTrue("The newest row must be signaled, with status 9:\n$deaths", seen)
        assertTrue("It must say whether this phone names low-memory kills:\n$deaths", deaths.contains("low-memory kills named by this phone: "))
        record("deaths", deaths)
    }

    @Test
    fun restartOfAField_addsOneRowAndNoHint() {
        prepare()
        // Not through showReady: its repair of a short window is a second show, and this row counts shows.
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
        host.setText(chat, INVENTED)
        SystemClock.sleep(RESTART_SETTLE_MS)

        val screen = openSection()
        val fields = block(screen, FIELDS)

        assertFalse("The field's hint must not be in the list:\n$fields", fields.contains(HINT, ignoreCase = true))
        assertFalse("What the app placed in the field must not be in the list:\n$fields", fields.contains(INVENTED, ignoreCase = true))
        skipUntilItsLineIsIn(fields, "noteField")
        assertEquals("One row from the show, none from the restart:\n$fields", 1, fields.lines().count { it.startsWith(HOST_PACKAGE) })
        // The window's measurements gain a line each time the keyboard is handed a field, restarts included. With only
        // one, the restart never got this far and the row above proves nothing.
        val handedAField = screen.sumOf { text -> text.lines().count { it.contains(WINDOW_SHOW) } }
        assertTrue("The restart must have reached the keyboard, but the window noted $handedAField:\n$screen", handedAField >= 2)
        record("timesHandedAField", handedAField)
    }

    @Test
    fun privateField_showsNoPackage() {
        showOn(HostActivity.PRIVATE)
        typeWords(HostActivity.PRIVATE)

        val fields = block(openSection(), FIELDS)

        skipUntilItsLineIsIn(fields, "noteField")
        assertTrue("The row must read as a private field:\n$fields", fields.contains(PRIVATE_FIELD))
        assertFalse("That row must not name the app:\n$fields", fields.contains(HOST_PACKAGE))
    }

    /** What a run of the overnight job leaves behind. Only safe while the process is stopped. */
    private fun aNightlyRunIsOnFile() {
        ime.forceStop()
        val xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
            "    <long name=\"nightly_time\" value=\"${System.currentTimeMillis()}\" />\n" +
            "    <string name=\"nightly_outcome\">$NIGHTLY_OUTCOME</string>\n</map>\n"
        shell.sh("run-as ${ime.pkg} sh -c 'mkdir -p shared_prefs; cat > shared_prefs/phone_stats.xml' <<'E2E_EOF'\n${xml}E2E_EOF\n")
    }

    /**
     * Erasing wipes the settings too, so Settings comes back with its sections shut and the way to the numbers has to be
     * opened by hand. The process must live through all this: killing it or seeding preferences would empty the list and
     * prove nothing. The nightly line lives in a file of its own, which is why it goes in before the process starts.
     */
    @Test
    fun erase_emptiesTheFieldList() {
        aNightlyRunIsOnFile()
        showOn()
        val pid = ime.pid()
        val screenBefore = openSection()
        val before = block(screenBefore, FIELDS)
        assertTrue("The nightly line must show what is on file, or this proves nothing:\n$screenBefore", block(screenBefore, NIGHTLY).contains(NIGHTLY_OUTCOME))

        touch.key(KeyEvent.KEYCODE_BACK)
        click("erase button") { findById(ERASE_BUTTON_ID) }
        click("button that confirms the erase") {
            root()?.findAccessibilityNodeInfosByViewId(DIALOG_CONFIRM_ID)?.firstOrNull()
        }
        click("Keyboard section, shut, as it is after an erase") {
            root()?.findAccessibilityNodeInfosByText(KEYBOARD_SECTION)
                ?.firstOrNull { it.text?.toString()?.trim()?.endsWith(KEYBOARD_SECTION_SHUT) == true }
        }

        val screenAfter = clickMotionAndReadSection()
        val after = block(screenAfter, FIELDS)

        assertEquals("The process must live through this, or the list emptied for the wrong reason", pid, ime.pid())
        assertFalse("The list must not name the app after an erase:\n$after", after.contains(HOST_PACKAGE))
        assertTrue("The list must be empty after an erase:\n$after", after.contains(NOT_MEASURED))
        assertTrue("The nightly line must go with an erase:\n$screenAfter", block(screenAfter, NIGHTLY).contains(NEVER_RAN))
        skipUntilItsLineIsIn(before, "noteField")
        assertTrue("The list must have named the app before the erase, or this proves nothing:\n$before", before.contains(HOST_PACKAGE))
    }

    /** Letters are sent on the lift. If the press were what started the clock, every reading here would be over the hold. */
    @Test
    fun holdingAKey_doesNotMoveTheFigure() {
        showOn()
        repeat(HELD_TAPS) {
            kb.tapKey(HELD_KEY, HOLD_MS)
            SystemClock.sleep(HELD_GAP_MS)
        }
        // A stalled emulator can stretch a hold into a long press, which costs that one letter and nothing else.
        assertTrue(
            "The holds must still type their letters, or the hold is too long: '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).length >= HELD_TAPS / 2 },
        )

        val keyToCommit = block(openSection(), KEY_TO_COMMIT)

        skipUntilItsLineIsIn(keyToCommit, "noteKeyDown or noteCommitted")
        record("heldKey", keyToCommit)
        val p95 = P95.find(keyToCommit)?.groupValues?.get(1)?.toInt() ?: throw AssertionError("No p95 in:\n$keyToCommit")
        assertTrue("A $HOLD_MS ms hold must not show in the figure:\n$keyToCommit", p95 < HOLD_MS)
    }

    /** Backspace acts on its press and spends the stamp there, so the repeats of a held one find none to read. */
    @Test
    fun backspace_countsOnce_howeverLongItIsHeld() {
        showOn()
        assumeTrue("A letter was lost on the way in, so the count here would prove nothing", everyKeyLanded(typeWords()))
        kb.tapKey(Keyboard.DELETE)
        SystemClock.sleep(HELD_GAP_MS)
        val beforeTheHold = host.text(chat).length
        kb.tapKey(Keyboard.DELETE, BACKSPACE_HOLD_MS)
        assertTrue(
            "The hold must have repeated, or this proves nothing: '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).length < beforeTheHold - 1 },
        )

        val keyToCommit = block(openSection(), KEY_TO_COMMIT)

        skipUntilItsLineIsIn(keyToCommit, "noteKeyDown or noteCommitted")
        record("heldBackspace", keyToCommit)
        assertEquals("The keys of '$TYPED', a tap and one hold of Backspace:\n$keyToCommit", TYPED.length + 2, readingsIn(keyToCommit))
    }

    private companion object {
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS_LABEL = "Open Settings"
        const val MOTION_BUTTON_ID = "btn_motion"
        const val ERASE_BUTTON_ID = "btn_clear"
        const val DIALOG_CONFIRM_ID = "android:id/button1"
        const val KEYBOARD_SECTION = "Keyboard"
        const val KEYBOARD_SECTION_SHUT = "Keyboard  ▸"
        const val HOST_PACKAGE = "io.ioservices.iokey.e2e"

        const val SECTION = "Typing numbers"
        const val KEY_TO_COMMIT = "Key to commit"
        const val LIFT_DECODE = "Lift decode"
        const val KEYBOARD_SHOW = "Keyboard show"
        const val COLD_START = "Cold start"
        const val DEATHS = "Keyboard process deaths, last 7 days"
        const val NIGHTLY = "Nightly job, last run"
        const val FIELDS = "Last 30 fields"
        const val COUNTS = "Counts"
        val BLOCKS = listOf(KEY_TO_COMMIT, LIFT_DECODE, KEYBOARD_SHOW, COLD_START, DEATHS, NIGHTLY, FIELDS, COUNTS)
        const val REPORT_TITLE = "IOKey typing numbers"
        const val WINDOW_HEADING = "Keyboard window"
        const val SHORT_WINDOWS = "Short windows repaired: "
        const val REFRESH_LABEL = "Refresh numbers"
        const val COPY_LABEL = "Copy these numbers"
        const val NOT_MEASURED = "not measured"
        const val NEVER_RAN = "never ran"
        const val NIGHTLY_OUTCOME = "TOO_HOT"
        const val PRIVATE_FIELD = "private field"
        const val WINDOW_SHOW = " show window="
        const val PASTE_CHIP = "Paste"

        const val TYPED = "plum zinc "
        const val INVENTED = "quince"
        const val HINT = "Zorbling Hint Xq"
        const val PLAIN_CLIP = "a note copied from elsewhere"
        val AI_DIAGNOSTICS_HEADINGS = listOf("screen ctx last draft", "typed ctx", "field ctx", "last prompt", "quick lane: ", "crash log")

        val BUILD_TYPE = Regex("""build: \S+""")
        val ENGINES_BUILT = Regex("""engines built: \d+ ms""")
        val KILLED = Regex("""signaled\s+status 9\b""")
        val DEATH_ROW = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}\s""")
        val P95 = Regex("""p95 (\d+)\+? ms""")
        val READINGS = Regex(""", n (\d+)""")

        const val SCREEN_MS = 20_000L
        const val TEXT_MS = 1_000L
        const val DEATH_RECORD_MS = 15_000L
        const val RESTART_SETTLE_MS = 500L
        const val LONG_PRESS_MS = 900L

        /** ClipHistory refuses anything longer. */
        const val LONGEST_CLIP_KEPT = 5_000

        /** Repeats start 400 ms into the press, and then there is one every 50 ms. */
        const val BACKSPACE_HOLD_MS = 1_000L

        /** The default long press is 380 ms, and a hold that opens it types nothing. */
        const val HOLD_MS = 250L
        const val HELD_TAPS = 20
        const val HELD_GAP_MS = 80L
        const val HELD_KEY = "a"
    }
}
