package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keyboard's window measured has to be readable on the phone itself. How the window comes up was worked out
 * on stock Android, and this is the only way to see what another vendor's framework really does when no cable is
 * attached. It sits on a settings screen a user may copy from, so it must hold no trace of what was typed.
 */
class WindowMeasurementsTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val shell get() = E2eDevice.shell

    private fun collectText(node: AccessibilityNodeInfo, into: MutableList<String>) {
        node.text?.toString()?.let { into += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectText(it, into) }
    }

    private fun screenText(): List<String> {
        E2eDevice.ua.clearCache()
        val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg } ?: return emptyList()
        return ArrayList<String>().also { collectText(root, it) }
    }

    private fun findById(id: String): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg } ?: return null
        return root.findAccessibilityNodeInfosByViewId("${ime.pkg}:id/$id").firstOrNull()
    }

    private fun findByText(text: String): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg } ?: return null
        return root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
    }

    /** Through the app's own screens and buttons, the way a user gets there. */
    private fun openMeasurementsScreen(): List<String> {
        kb.hideWithBack()
        shell.exec("am start -n ${ime.pkg}/$ONBOARDING")
        var button: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { button = findByText(OPEN_SETTINGS_LABEL); button != null }) {
            "The launcher screen has no '$OPEN_SETTINGS_LABEL' button"
        }
        val r = Rect()
        button!!.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
        var motion: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { motion = findById(MOTION_BUTTON_ID); motion != null }) {
            "The settings screen has no button to the screen that holds the measurements"
        }
        check(motion!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "That button did not take the click" }
        var text = emptyList<String>()
        check(Wait.until(SCREEN_MS, 200) { text = screenText(); text.any { MEASUREMENT.containsMatchIn(it) } }) {
            "No measurements on the screen. It reads: $text"
        }
        return text
    }

    @Test
    fun measurementsCanBeReadInSettings_andHoldNothingTyped() {
        ime.forceStop()
        ime.seedBooleanPrefs(
            mapOf("onboarding_done" to true, "tutorial_seen" to true, "show_advanced" to true, "settings_simple" to false),
        )
        atEnd { ime.clearData() }
        ime.select()
        showReady(chat)
        kb.type(TYPED)
        assertTrue(
            "The words must reach the field, or this proves nothing: '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).trim().split(" ").size == TYPED.trim().split(" ").size },
        )
        val inTheField = host.text(chat).lowercase().trim().split(" ")
        val shownHeight = kb.windowState().frame.height()

        val screen = openMeasurementsScreen()

        val block = screen.first { MEASUREMENT.containsMatchIn(it) }
        val measurements = block.lines().filter { MEASUREMENT.containsMatchIn(it) }
        assertTrue("A show must be among them:\n$block", measurements.any { it.contains(" show window=") })
        assertTrue(
            "The window's height as the window manager saw it, $shownHeight, must be among them:\n$block",
            measurements.any { it.contains(" window=$shownHeight ") },
        )
        for (line in measurements) {
            assertTrue("Each line must be a time, an event and numbers, but one reads: $line", ONLY_NUMBERS.matches(line))
        }
        for (word in inTheField) {
            assertFalse("'$word' was typed and must not be among the measurements:\n$block", block.lowercase().contains(word))
        }
    }

    private companion object {
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS_LABEL = "Open Settings"
        const val MOTION_BUTTON_ID = "btn_motion"
        const val TYPED = "plum zinc "
        const val SCREEN_MS = 20_000L
        const val TEXT_MS = 1_000L
        val MEASUREMENT = Regex("""\d\d:\d\d:\d\d\.\d{3} [a-z ]+ window=""")
        val ONLY_NUMBERS = Regex("""\d\d:\d\d:\d\d\.\d{3} [a-z ]+?( [a-z]+=-?\d+| cut)+""")
    }
}
