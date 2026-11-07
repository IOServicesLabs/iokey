package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the keyboard learns must outlive the moment it was learned in. Opening the keyboard's own settings and
 * a process restart are both everyday events, and neither may cost the user their words.
 *
 * Both words are invented. The corrector leaves the first alone and takes the second for a typo, so the first
 * shows whether a word is still known and the second whether it is still respected.
 */
class LearningTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val shell get() = E2eDevice.shell

    /** The settings screen is reached from the finished first-run screen, so the first run must be over. */
    @Before
    fun firstRunIsOver() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf("onboarding_done" to true, "tutorial_seen" to true))
        ime.select()
        showReady(chat)
    }

    /** Types the word as a user with their own vocabulary would, taking it back when it is corrected. */
    private fun useWord(word: String, times: Int = USES) {
        repeat(times) {
            kb.type("so $word ")
            Wait.until(CORRECTION_MS, 40) { !host.text(chat).lowercase().endsWith(" $word ") }
            if (!host.text(chat).lowercase().endsWith(" $word ")) {
                kb.backspace()
                check(Wait.until(CORRECTION_MS, 40) { host.text(chat).lowercase().endsWith(" $word") }) {
                    "Backspace did not take the correction back: '${host.text(chat)}'"
                }
                kb.type(" ")
            }
        }
        check(host.text(chat).lowercase().endsWith(" $word ")) { "'$word' is not in the field: '${host.text(chat)}'" }
    }

    private fun assertCompletes(word: String) {
        val prefix = word.dropLast(1)
        val before = host.text(chat)
        kb.type(prefix)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        Wait.until(CHIP_MS, 40) { host.text(chat).length > before.length + prefix.length }
        val added = host.text(chat).substring(before.length)
        assertTrue(
            "'$prefix' must complete to the learned word '$word' from the bar's main slot, but gave '$added'",
            added.lowercase() == "$word ",
        )
    }

    private fun assertStaysAsTyped(word: String) {
        val before = host.text(chat)
        kb.type("so $word ")
        SystemClock.sleep(CORRECTION_MS)
        val added = host.text(chat).substring(before.length)
        assertTrue("'$word' was kept by the user and must not be corrected, but gave '$added'", added.lowercase() == "so $word ")
    }

    private fun restartProcess() {
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)
        ime.killProcess()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
    }

    private fun topActivity(): String =
        shell.exec("dumpsys activity activities").lineSequence().firstOrNull { it.contains("topResumedActivity") }.orEmpty()

    private fun findText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString().equals(text, ignoreCase = true)) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { c -> findText(c, text)?.let { return it } }
        }
        return null
    }

    /** Through the app's own launcher screen and its button, since the settings screen has no other way in. */
    private fun openSettingsAndComeBack() {
        kb.hideWithBack()
        shell.exec("am start -n ${ime.pkg}/$ONBOARDING")
        var button: AccessibilityNodeInfo? = null
        val found = Wait.until(SCREEN_MS, 200) {
            E2eDevice.ua.clearCache()
            val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg }
            button = root?.findAccessibilityNodeInfosByViewId("${ime.pkg}:id/$OPEN_SETTINGS_ID")?.firstOrNull()
                ?: root?.let { findText(it, OPEN_SETTINGS_LABEL) }
            button != null
        }
        check(found) { "The launcher screen has no '$OPEN_SETTINGS_LABEL' button. Top: ${topActivity()}" }
        val r = Rect()
        button!!.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
        check(Wait.until(SCREEN_MS, 200) { topActivity().contains("SettingsActivity") }) {
            "The settings screen did not open. Top: ${topActivity()}"
        }
        ime.waitIdle()
        host.bringToFront()
        kb.show(chat)
        ime.waitIdle()
        kb.assertIokeyShowing()
    }

    // ---------------------------------------------------------------------------------------------
    // A word the keyboard learned
    // ---------------------------------------------------------------------------------------------

    /** The control: learning itself works, so the tests below can only fail on keeping what was learned. */
    @Test
    fun learnedWord_completesInTheSameSession() {
        useWord(KNOWN_WORD)
        assertCompletes(KNOWN_WORD)
    }

    /** A word used more must not be harder to reach than one used less. */
    @Test
    fun learnedWord_stillCompletesAfterMoreUses() {
        useWord(KNOWN_WORD, times = MORE_USES)
        assertCompletes(KNOWN_WORD)
    }

    /** What the next test has to beat: a guess at a correction that does not even start with what was typed. It is
     *  its own test because tapping the guess teaches it, and a taught guess can become a fix, which may lead. */
    @Test
    fun aWordNeverTyped_getsACorrectionHintInTheMainSlot() {
        val prefix = HINTED_WORD.dropLast(1)
        val before = host.text(chat)
        kb.type(prefix)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        Wait.until(CHIP_MS, 40) { !host.text(chat).substring(before.length).startsWith(prefix, ignoreCase = true) }
        val offered = host.text(chat).substring(before.length)
        assertTrue(
            "No correction hint for '$prefix' led the bar: '$offered'",
            offered.isNotBlank() && !offered.startsWith(prefix, ignoreCase = true),
        )
    }

    /** A guess at a correction must not take the main slot from a word the user has typed. */
    @Test
    fun learnedWord_leadsOverACorrectionHint() {
        useWord(HINTED_WORD)
        assertCompletes(HINTED_WORD)
    }

    /** The keyboard's writes sit in the store's log until something folds them in, which is what hiding it is for. */
    @Test
    fun hidingTheKeyboard_foldsTheLogIntoTheStoreFile() {
        useWord(KNOWN_WORD)
        kb.hideWithBack()
        var bytes = 0L
        val grew = Wait.until(CHECKPOINT_MS, 200) {
            bytes = shell.sh("run-as ${ime.pkg} sh -c 'stat -c %s databases/localboard.db'").trim().toLongOrNull() ?: 0L
            bytes > ONE_PAGE_BYTES
        }
        assertTrue("Hiding the keyboard must checkpoint, but the store file stayed at $bytes bytes", grew)
    }

    @Test
    fun learnedWord_completesAfterOpeningSettings() {
        useWord(KNOWN_WORD)
        openSettingsAndComeBack()
        assertCompletes(KNOWN_WORD)
    }

    @Test
    fun learnedWord_completesAfterAProcessRestart() {
        useWord(KNOWN_WORD)
        restartProcess()
        assertCompletes(KNOWN_WORD)
    }

    @Test
    fun learnedWord_completesAfterSettingsAndARestart() {
        useWord(KNOWN_WORD)
        openSettingsAndComeBack()
        restartProcess()
        assertCompletes(KNOWN_WORD)
    }

    // ---------------------------------------------------------------------------------------------
    // A word the keyboard had to be taught not to correct
    // ---------------------------------------------------------------------------------------------

    @Test
    fun keptWord_staysInTheSameSession() {
        useWord(KEPT_WORD)
        assertStaysAsTyped(KEPT_WORD)
    }

    @Test
    fun keptWord_staysAfterOpeningSettings() {
        useWord(KEPT_WORD)
        openSettingsAndComeBack()
        assertStaysAsTyped(KEPT_WORD)
    }

    @Test
    fun keptWord_staysAfterAProcessRestart() {
        useWord(KEPT_WORD)
        restartProcess()
        assertStaysAsTyped(KEPT_WORD)
    }

    @Test
    fun keptWord_staysAfterSettingsAndARestart() {
        useWord(KEPT_WORD)
        openSettingsAndComeBack()
        restartProcess()
        assertStaysAsTyped(KEPT_WORD)
    }

    private companion object {
        const val KNOWN_WORD = "xylqor"
        const val KEPT_WORD = "mirble"

        /** Invented, and close enough to a dictionary word that the corrector offers that word as a hint. */
        const val HINTED_WORD = "qwibbit"
        const val USES = 3
        const val MORE_USES = 5

        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS_ID = "btn_settings"
        const val OPEN_SETTINGS_LABEL = "Open Settings"

        const val CORRECTION_MS = 300L
        const val BAR_SETTLE_MS = 600L
        const val CHIP_MS = 1_000L
        const val SCREEN_MS = 10_000L
        const val CHECKPOINT_MS = 10_000L

        /** What the store file holds until a first checkpoint: its header page and nothing else. */
        const val ONE_PAGE_BYTES = 4_096L

        /** Longer than the delay on the keyboard's write of what it learned. */
        const val PERSIST_MS = 3_000L
    }
}
