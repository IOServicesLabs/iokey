package io.ioservices.iokey.e2e.harness

import android.app.UiAutomation
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Toasts are drawn by the system, so no window tree holds their text. The system does announce them to
 * accessibility, and that is the one place a test can read what the keyboard told the user.
 */
class ToastLog(ua: UiAutomation) {

    private val texts = CopyOnWriteArrayList<String>()

    init {
        ua.setOnAccessibilityEventListener { event ->
            // A notification announces itself the same way, and carries itself as the event's parcelable.
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData == null) {
                texts += event.text.joinToString(" ")
            }
        }
    }

    fun clear() = texts.clear()

    fun seen(): List<String> = texts.toList()
}
