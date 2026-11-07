package io.ioservices.iokey.e2e.harness

import android.os.SystemClock

/**
 * Display, rotation, theme and navigation state of the emulator. Tests change these as triggers and must
 * never leave them changed, so [restoreDefaults] runs before and after every test and touches only what
 * differs: each needless change here is a configuration change the keyboard would see.
 */
class DeviceEnv(private val shell: Shell) {

    enum class Navigation(val overlay: String, val mode: String) {
        GESTURE("com.android.internal.systemui.navbar.gestural", "2"),
        THREE_BUTTON("com.android.internal.systemui.navbar.threebutton", "0"),
    }

    fun restoreDefaults() {
        setAutoRotate(false)
        if (rotation() != 0) {
            setRotation(0)
            SystemClock.sleep(CONFIG_SETTLE_MS)
        }
        if (hasDisplayOverride()) {
            resetDisplay()
            SystemClock.sleep(CONFIG_SETTLE_MS)
        }
        if (isNightMode()) {
            setNightMode(false)
            SystemClock.sleep(CONFIG_SETTLE_MS)
        }
        if (navigation() != Navigation.GESTURE) {
            setNavigation(Navigation.GESTURE)
            SystemClock.sleep(CONFIG_SETTLE_MS)
        }
    }

    private fun setAutoRotate(on: Boolean) {
        val value = if (on) "1" else "0"
        if (shell.exec("settings get system accelerometer_rotation").trim() != value) {
            shell.exec("settings put system accelerometer_rotation $value")
        }
    }

    fun rotation(): Int = shell.exec("settings get system user_rotation").trim().toIntOrNull() ?: 0

    /** Returns as soon as the setting is written. Callers that race the rotation depend on that. */
    fun setRotation(quarterTurns: Int) {
        shell.exec("settings put system user_rotation $quarterTurns")
    }

    fun hasDisplayOverride(): Boolean {
        val size = shell.exec("wm size")
        val density = shell.exec("wm density")
        return size.contains("Override") || density.contains("Override")
    }

    fun setDisplay(width: Int, height: Int, density: Int) {
        shell.exec("wm size ${width}x$height")
        shell.exec("wm density $density")
    }

    fun resetDisplay() {
        shell.exec("wm size reset")
        shell.exec("wm density reset")
    }

    fun isNightMode(): Boolean = shell.exec("cmd uimode night").contains("Night mode: yes")

    fun setNightMode(on: Boolean) {
        shell.exec("cmd uimode night ${if (on) "yes" else "no"}")
    }

    fun navigation(): Navigation =
        if (shell.exec("settings get secure navigation_mode").trim() == Navigation.THREE_BUTTON.mode) {
            Navigation.THREE_BUTTON
        } else {
            Navigation.GESTURE
        }

    fun setNavigation(nav: Navigation) {
        shell.exec("cmd overlay enable-exclusive --category ${nav.overlay}")
        check(Wait.until(10_000, 100) { shell.exec("settings get secure navigation_mode").trim() == nav.mode }) {
            "Navigation mode did not change to $nav"
        }
    }

    /** The first full-screen app on a device gets a panel explaining it, which would take the next tap. */
    fun skipFullScreenExplanation() {
        shell.exec("settings put secure immersive_mode_confirmations confirmed")
    }

    companion object {
        /** How long a configuration change takes to reach every window on this emulator. */
        const val CONFIG_SETTLE_MS = 2_500L
    }
}
