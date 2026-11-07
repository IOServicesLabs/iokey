package io.ioservices.iokey.e2e.harness

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ioservices.iokey.e2e.HostActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Common setup. Each test starts from the emulator's default display, navigation and theme, with IOKey
 * selected and a fresh host window. A failure saves a screenshot and the window state before anything is
 * torn down, so a headless run can be inspected afterwards.
 */
@RunWith(AndroidJUnit4::class)
abstract class E2eTestBase {
    protected val dev get() = E2eDevice
    protected val kb get() = E2eDevice.keyboard
    protected val host get() = E2eDevice.host
    protected val ime get() = E2eDevice.ime
    protected val env get() = E2eDevice.env
    protected val touch get() = E2eDevice.touch

    /** Which kind of app window the test runs under. */
    protected open val hostClass: Class<out HostActivity> = HostActivity::class.java

    /** True for tests that must not see anything learned by earlier tests. */
    protected open val wipeLearnedStateBeforeEachTest: Boolean = false

    private val endActions = ArrayList<() -> Unit>()
    private var currentTest = ""

    /** Runs [action] when the test is over, whether or not it passed, and after any failure evidence is saved. */
    protected fun atEnd(action: () -> Unit) {
        endActions += action
    }

    private val deviceRule = TestRule { base: Statement, description: Description ->
        object : Statement() {
            override fun evaluate() {
                currentTest = "${description.testClass.simpleName}.${description.methodName}"
                Log.i(TAG, "begin ${description.testClass.simpleName}#${description.methodName}")
                try {
                    env.restoreDefaults()
                    if (wipeLearnedStateBeforeEachTest) ime.clearData()
                    ime.select()
                    host.launch(hostClass)
                    clearWhateverCoversTheHost()
                    // The launcher is locked to portrait. If it came to the front over a sideways rotation, for
                    // instance after a run was cut short, the system puts that rotation back as this window opens.
                    env.restoreDefaults()
                    check(Wait.until(DeviceEnv.CONFIG_SETTLE_MS) { host.windowSizePx().let { it.first < it.second } }) {
                        "The host window must start in portrait, but it is ${host.windowSizePx()}"
                    }
                    E2eDevice.toasts.clear()
                    kb.clearConfirmation()
                    base.evaluate()
                    check(kb.confirmedShowing) { "This test never confirmed that IOKey was the keyboard showing, so what it read proves nothing" }
                } catch (t: Throwable) {
                    runCatching { saveEvidence(description, t) }
                        .onFailure { Log.w(TAG, "Could not save failure evidence", it) }
                    throw t
                } finally {
                    runCatching { clearWhateverCoversTheHost() }
                    endActions.asReversed().forEach { action ->
                        runCatching(action).onFailure { Log.w(TAG, "A test's end action failed", it) }
                    }
                    // In this order, or the launcher comes to the front over a rotation the next test would inherit.
                    env.restoreDefaults()
                    runCatching { host.close() }
                    runCatching { noteSwallowedErrors() }.onFailure { Log.w(TAG, "Could not read the keyboard's crash log", it) }
                    runCatching { noteTapsThatLandedAsLongPresses() }.onFailure { Log.w(TAG, "Could not record this test's long presses", it) }
                    Log.i(TAG, "end ${description.testClass.simpleName}#${description.methodName}")
                }
            }
        }
    }

    /** The timeout is longer than any healthy test takes: a hung test must not hold up the rest of the run. */
    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(Timeout.builder().withTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).withLookingForStuckThread(true).build())
        .around(deviceRule)

    /**
     * A window a test opened over the host, such as the system's keyboard picker, outlives the test and would
     * take every touch of the next one. The system closes its own dialogs on this broadcast. Back is for
     * anything else, and the host itself ignores Back.
     */
    private fun clearWhateverCoversTheHost() {
        repeat(MAX_CLEAR_ATTEMPTS) { attempt ->
            if (Wait.until(HOST_FOCUS_MS) { host.hasWindowFocus() }) return
            if (attempt == 0) {
                E2eDevice.shell.exec("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            } else {
                touch.key(KeyEvent.KEYCODE_BACK)
            }
        }
        check(Wait.until(HOST_FOCUS_MS) { host.hasWindowFocus() }) {
            "Something covers the host: ${E2eDevice.shell.exec("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus") }}"
        }
    }

    /** Shows the keyboard on [field] and waits until it is ready to correct what is typed. */
    protected fun showReady(field: String = HostActivity.CHAT) {
        kb.show(field)
        ime.waitIdle()
        kb.assertIokeyShowing()
        repairShortWindow(field)
    }

    /**
     * Now and then a new process comes up short with no trigger at all, in the state the insets tests bring about
     * on purpose. The system's buttons then cover the corners of the bottom row, and a test about something else
     * would trip over them, so it gets the repair the next show brings. The run's results list each case, because
     * there should be none. A repair that did not take is no longer about something else, so it fails the test. A test
     * whose answer a second show would change must not come through here.
     */
    private fun repairShortWindow(field: String) {
        if (!kb.isShort()) return
        val note = "$currentTest: ${kb.windowState()}"
        Log.w(TAG, "A show came up short in $note")
        appendToResults(SHORT_SHOWS_FILE, "$note\n")
        kb.hideCompletely()
        kb.show(field)
        assertFalse("The window was still short after the hide and show that should repair it: ${kb.windowState()}", kb.isShort())
    }

    /**
     * Puts a number or a short note in the run's results, under this test's name. A passing test has no message, so
     * this is the only way it can say how well it passed, and a later run can then show a drop that pass and fail
     * would hide.
     */
    protected fun record(name: String, value: Any) {
        appendToResults(RECORDED_FILE, "$currentTest\t$name\t${value.toString().replace(WHITESPACE, " ")}\n")
    }

    /** The keyboard lives through some exceptions, so no test fails over them, and the results are where they show. */
    private fun noteSwallowedErrors() {
        for (entry in ime.takeSwallowedErrors()) {
            Log.w(TAG, "The keyboard caught an exception and carried on, during $currentTest")
            appendToResults(SWALLOWED_ERRORS_FILE, "$currentTest\t${entry.replace("\n", "\\n")}\n")
        }
    }

    /** The harness tapped again after each of these, so the results are the only place they show. */
    private fun noteTapsThatLandedAsLongPresses() {
        val count = kb.takeTapsThatLandedAsLongPresses()
        if (count > 0) record("tapsThatLandedAsLongPresses", count)
    }

    /** A file for run.ps1 to fetch after the run, for what the instrumentation's own output has no place for. */
    private fun appendToResults(fileName: String, text: String) {
        E2eDevice.instrumentation.targetContext.getExternalFilesDir(null)?.let { File(it, fileName).appendText(text) }
    }

    /**
     * Moves the caret as a tap in the field would. A finger takes far longer to get there than a keystroke takes
     * to settle, and a keyboard may read a selection change right after its own edit as the echo of that edit.
     */
    protected fun moveCaretByHand(position: Int, field: String = HostActivity.CHAT) {
        SystemClock.sleep(HAND_TRAVEL_MS)
        host.setCaret(field, position)
        SystemClock.sleep(CARET_SETTLE_MS)
    }

    /** Waits for the field to hold exactly [expected], and fails with what it holds instead. */
    protected fun assertText(expected: String, field: String = HostActivity.CHAT, message: String = "") {
        Wait.until(TEXT_TIMEOUT_MS) { host.text(field) == expected }
        val prefix = if (message.isEmpty()) "" else "$message: "
        assertEquals("${prefix}text in the $field field", expected, host.text(field))
    }

    /** Gives anything asynchronous time to change the field, then checks it still holds [expected]. */
    protected fun assertTextStays(expected: String, field: String = HostActivity.CHAT, forMs: Long = 600, message: String = "") {
        assertText(expected, field, message)
        SystemClock.sleep(forMs)
        assertText(expected, field, message)
    }

    /**
     * As [assertTextStays], where the question is which letters are there. After the keyboard comes back up,
     * whether the next letter is a capital is a separate matter with tests of its own, and it must not hide
     * or fake the answer here.
     */
    protected fun assertTextStaysIgnoringCase(expected: String, field: String = HostActivity.CHAT, forMs: Long = 600, message: String = "") {
        Wait.until(TEXT_TIMEOUT_MS) { host.text(field).equals(expected, ignoreCase = true) }
        SystemClock.sleep(forMs)
        val prefix = if (message.isEmpty()) "" else "$message: "
        assertEquals("${prefix}text in the $field field, ignoring case", expected.lowercase(), host.text(field).lowercase())
    }

    protected fun assertComposing(expected: String?, field: String = HostActivity.CHAT, message: String = "") {
        Wait.until(TEXT_TIMEOUT_MS) { host.composing(field) == expected }
        val prefix = if (message.isEmpty()) "" else "$message: "
        assertEquals("${prefix}composing region of the $field field", expected, host.composing(field))
    }

    private fun saveEvidence(description: Description, failure: Throwable) {
        val dir = E2eDevice.instrumentation.targetContext.getExternalFilesDir("failures") ?: return
        val base = "${description.testClass.simpleName}.${description.methodName}"
        E2eDevice.ua.takeScreenshot()?.let { shot ->
            File(dir, "$base.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        File(dir, "$base.txt").writeText(
            buildString {
                appendLine(failure.message)
                appendLine()
                appendLine(kb.describeState())
                appendLine("current IME: ${ime.currentIme()}")
                appendLine("navigation: ${env.navigation()}, rotation: ${env.rotation()}, night: ${env.isNightMode()}")
                appendLine("host window: ${runCatching { host.windowSizePx() }.getOrNull()}")
                appendLine(runCatching { kb.readLayout()?.faces?.joinToString("\n") }.getOrNull())
                appendLine()
                appendLine("Fields:")
                runCatching { appendLine("chat: '${host.text(HostActivity.CHAT)}' composing '${host.composing(HostActivity.CHAT)}'") }
                appendLine()
                appendLine("Toasts seen: ${E2eDevice.toasts.seen()}")
                appendLine()
                appendLine("System log:")
                appendLine(recentLogLines())
            }
        )
    }

    /** logcat applies a line count before its own filters, so a busy log would leave nothing. Filter here instead. */
    private fun recentLogLines(): String {
        val pid = ime.pid()
        return E2eDevice.shell.exec("logcat -d -t $LOG_LINES_READ").lineSequence()
            .filter { line -> LOG_OF_INTEREST.containsMatchIn(line) || (pid != null && line.contains(" $pid ")) }
            .toList().takeLast(LOG_LINES_KEPT).joinToString("\n")
    }

    private companion object {
        const val TAG = "IOKeyE2E"
        const val SHORT_SHOWS_FILE = "short-shows.txt"
        const val RECORDED_FILE = "recorded.txt"
        const val SWALLOWED_ERRORS_FILE = "swallowed-errors.txt"
        const val TEST_TIMEOUT_SECONDS = 300L
        const val TEXT_TIMEOUT_MS = 4_000L
        const val HAND_TRAVEL_MS = 400L
        const val CARET_SETTLE_MS = 300L
        const val HOST_FOCUS_MS = 1_500L
        const val MAX_CLEAR_ATTEMPTS = 3
        const val LOG_LINES_READ = 5_000
        const val LOG_LINES_KEPT = 200
        val WHITESPACE = Regex("""\s+""")

        /** NotificationService logs a toast it drops, which is how a dropped toast differs from one never sent. */
        val LOG_OF_INTEREST = Regex("""\b(InputMethodManagerService|ActivityManager|AndroidRuntime|NotificationService|IOKeyE2E)\b""")
    }
}
