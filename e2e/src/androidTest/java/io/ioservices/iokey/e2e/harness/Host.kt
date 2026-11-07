package io.ioservices.iokey.e2e.harness

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import io.ioservices.iokey.e2e.HostActivity
import java.util.concurrent.atomic.AtomicReference

/** The app under the keyboard, read and set on its main thread. */
class Host(private val instrumentation: Instrumentation) {

    private var hostClass: Class<out HostActivity> = HostActivity::class.java

    fun launch(kind: Class<out HostActivity> = HostActivity::class.java) {
        if (HostActivity.current != null) close()
        hostClass = kind
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(instrumentation.targetContext.packageName, kind.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
    }

    /** Brings the host back to the front after something else was opened over it. */
    fun bringToFront() {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(instrumentation.targetContext.packageName, hostClass.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.targetContext.startActivity(intent)
        check(Wait.until(10_000) { onMain { it.hasWindowFocus() } }) { "The host did not return to the front" }
        instrumentation.waitForIdleSync()
    }

    fun close() {
        val activity = HostActivity.current ?: return
        instrumentation.runOnMainSync { activity.finish() }
        Wait.until(5_000) { HostActivity.current == null }
    }

    fun <T> onMain(block: (HostActivity) -> T): T {
        // A window being recreated for a configuration change is gone for a moment.
        Wait.until(RECREATE_TIMEOUT_MS) { HostActivity.current != null }
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync {
            result.set(
                runCatching {
                    val activity = HostActivity.current ?: error("The host activity is not running")
                    block(activity)
                }
            )
        }
        return result.get().getOrThrow()
    }

    fun text(field: String): String = onMain { it.field(field).text.toString() }

    /** Text placed by the app itself, as a saved draft would be. Do this before the field has focus. */
    fun setText(field: String, value: String) = onMain {
        val f = it.field(field)
        f.setText(value)
        f.setSelection(value.length)
    }

    /** Moves the caret the way the app or a tap elsewhere would, from outside the keyboard. */
    fun setCaret(field: String, position: Int) = onMain { it.field(field).setSelection(position) }

    /** Selects text the way a double tap in the field would, from outside the keyboard. */
    fun setSelection(field: String, start: Int, end: Int) = onMain { it.field(field).setSelection(start, end) }

    fun caret(field: String): Int = onMain { it.field(field).selectionStart }

    /** Where the selection starts and ends. The two are equal when there is only a caret. */
    fun selection(field: String): Pair<Int, Int> = onMain { it.field(field).selectionStart to it.field(field).selectionEnd }

    /** The text currently in the composing region (underlined, not yet final). */
    fun composing(field: String): String? = onMain {
        val range = it.composingRange(field) ?: return@onMain null
        it.field(field).text.subSequence(range.first, range.last + 1).toString()
    }

    fun fieldRect(field: String): Rect = onMain { it.fieldRectOnScreen(field) }

    fun navigationBarBottomPx(): Int = onMain { it.navigationBarBottomPx() }

    fun windowSizePx(): Pair<Int, Int> = onMain { it.windowSizePx() }

    fun appTouchDowns(): Int = HostActivity.appTouchDowns.get()

    fun editorActions(): Int = HostActivity.editorActions.get()

    fun fieldLongClicks(): Int = HostActivity.fieldLongClicks.get()

    fun hasWindowFocus(): Boolean = onMain { it.hasWindowFocus() }

    private companion object {
        const val RECREATE_TIMEOUT_MS = 5_000L
    }
}
