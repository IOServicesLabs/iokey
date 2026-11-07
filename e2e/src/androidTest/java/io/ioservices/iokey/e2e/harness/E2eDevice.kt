package io.ioservices.iokey.e2e.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry

/** Everything a test touches, built once per instrumentation run. */
object E2eDevice {
    val instrumentation = InstrumentationRegistry.getInstrumentation()

    val ua: UiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also {
        val info = it.serviceInfo
        // Interactive windows lets the tree of the IME window be read; not-important views keeps the
        // key nodes in that tree whatever the app decides about their importance.
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        it.serviceInfo = info
    }

    private val args = InstrumentationRegistry.getArguments()

    /** Which installed build to test. Change it with -e imePackage on am instrument, or run.ps1's -AppPackage. */
    val imePackage: String = args.getString("imePackage") ?: "io.ioservices.iokey.full.debug"
    private val imeService: String = args.getString("imeService") ?: "com.localboard.keyboard.ime.LLMKeyboardService"

    val toasts = ToastLog(ua)
    val shell = Shell(ua)
    val touch = Touch(ua)
    val env = DeviceEnv(shell)
    val ime = Ime(shell, imePackage, imeService)
    val host = Host(instrumentation)
    val keyboard = Keyboard(ua, shell, ime, host, touch)
}
