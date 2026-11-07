package io.ioservices.iokey.e2e.harness

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Raw input through UiAutomation.injectInputEvent. It reaches the IME window, carries several pointers in
 * one stream, and has none of the JVM start-up cost per event that makes "adb shell input" useless for
 * glides and rollover.
 */
class Touch(private val ua: UiAutomation) {

    data class Point(val x: Float, val y: Float)

    fun tap(x: Float, y: Float, holdMs: Long = DEFAULT_HOLD_MS) {
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, listOf(0 to Point(x, y))))
        sleep(holdMs)
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(0 to Point(x, y))))
    }

    /** Finger down, hold, finger up: long-press popups, the delete repeat, sparkle's quick prompt. */
    fun press(x: Float, y: Float, holdMs: Long) = tap(x, y, holdMs)

    /**
     * A second finger lands before the first lifts, as in two-thumb typing. The fingers overlap by
     * [overlapMs]. [firstLiftsFirst] false makes the later finger lift first, the other real order.
     */
    fun rollover(a: Point, b: Point, holdMs: Long = 50, overlapMs: Long = 40, firstLiftsFirst: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        val both = listOf(0 to a, 1 to b)
        inject(motion(down, down, MotionEvent.ACTION_DOWN, listOf(0 to a)))
        sleep(holdMs)
        inject(motion(down, SystemClock.uptimeMillis(), pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), both))
        sleep(overlapMs)
        if (firstLiftsFirst) {
            inject(motion(down, SystemClock.uptimeMillis(), pointerAction(MotionEvent.ACTION_POINTER_UP, 0), both))
            sleep(holdMs)
            inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(1 to b)))
        } else {
            inject(motion(down, SystemClock.uptimeMillis(), pointerAction(MotionEvent.ACTION_POINTER_UP, 1), both))
            sleep(holdMs)
            inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(0 to a)))
        }
    }

    /**
     * Keys struck in a run with each finger still down as the next lands, the way fast typists roll through
     * a word. Two fingers alternate, so the stream never carries more than two pointers.
     */
    fun rolloverChain(keys: List<Point>, holdMs: Long = 50, overlapMs: Long = 40) {
        require(keys.size >= 2) { "A rollover needs at least two keys" }
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, listOf(0 to keys[0])))
        sleep(holdMs)
        for (i in 1 until keys.size) {
            val landing = (i % 2) to keys[i]
            val lifting = ((i - 1) % 2) to keys[i - 1]
            // A motion event lists its pointers in id order, so a pointer's index follows from its id.
            val both = listOf(landing, lifting).sortedBy { it.first }
            inject(motion(down, SystemClock.uptimeMillis(), pointerAction(MotionEvent.ACTION_POINTER_DOWN, both.indexOf(landing)), both))
            sleep(overlapMs)
            inject(motion(down, SystemClock.uptimeMillis(), pointerAction(MotionEvent.ACTION_POINTER_UP, both.indexOf(lifting)), both))
            sleep(holdMs)
        }
        val last = keys.lastIndex
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf((last % 2) to keys[last])))
    }

    /** Part of a glide: the finger follows [path] over [durationMs]. A single point is a finger at rest there. */
    class Stroke(val path: List<Point>, val durationMs: Long)

    /**
     * One finger along [path] at a steady speed. The stroke is resampled on a fixed clock so its speed and
     * sample rate do not depend on how far apart the waypoints happen to be.
     */
    fun glide(path: List<Point>, durationMs: Long, sampleMs: Long = 12, dwellStartMs: Long = 30, dwellEndMs: Long = 30) {
        require(path.size >= 2) { "A glide needs at least two points" }
        glide(listOf(Stroke(path, durationMs)), sampleMs, dwellStartMs, dwellEndMs)
    }

    /** One finger through [strokes] without lifting, each at its own speed, as a hesitating hand moves. */
    fun glide(strokes: List<Stroke>, sampleMs: Long = 12, dwellStartMs: Long = 30, dwellEndMs: Long = 30) {
        val down = SystemClock.uptimeMillis()
        inject(motion(down, down, MotionEvent.ACTION_DOWN, listOf(0 to strokes.first().path.first())))
        sleep(dwellStartMs)
        for (stroke in strokes) moveAlong(down, stroke, sampleMs)
        val end = strokes.last().path.last()
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, listOf(0 to end)), wait = false)
        sleep(dwellEndMs)
        inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(0 to end)))
    }

    private fun moveAlong(down: Long, stroke: Stroke, sampleMs: Long) {
        val path = stroke.path
        if (path.size == 1) {
            // A finger at rest has nothing new to report.
            sleep(stroke.durationMs)
            return
        }
        val lengths = FloatArray(path.size)
        for (i in 1 until path.size) lengths[i] = lengths[i - 1] + distance(path[i - 1], path[i])
        val total = lengths.last()
        val start = SystemClock.uptimeMillis()
        var segment = 1
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= stroke.durationMs) break
            val along = total * elapsed / stroke.durationMs
            while (segment < path.size - 1 && lengths[segment] < along) segment++
            val a = path[segment - 1]
            val b = path[segment]
            val span = lengths[segment] - lengths[segment - 1]
            val t = if (span <= 0f) 0f else (along - lengths[segment - 1]) / span
            val at = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            inject(motion(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, listOf(0 to at)), wait = false)
            sleep(sampleMs)
        }
    }

    fun key(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        inject(keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode))
        inject(keyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode))
    }

    private fun keyEvent(down: Long, at: Long, action: Int, code: Int) =
        KeyEvent(
            down, at, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
        )

    private fun inject(event: android.view.InputEvent, wait: Boolean = true) {
        val delivered = ua.injectInputEvent(event, wait)
        (event as? MotionEvent)?.recycle()
        check(delivered) { "The system refused an injected input event" }
    }

    private fun motion(down: Long, at: Long, action: Int, pointers: List<Pair<Int, Point>>): MotionEvent {
        val props = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].first
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].second.x
                y = pointers[i].second.y
                pressure = 1f
                size = 0.1f
            }
        }
        return MotionEvent.obtain(
            down, at, action, pointers.size, props, coords, 0, 0, 1f, 1f,
            FINGER_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    private fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun sleep(ms: Long) {
        if (ms > 0) SystemClock.sleep(ms)
    }

    companion object {
        const val DEFAULT_HOLD_MS = 45L
        private const val FINGER_DEVICE_ID = 0
    }
}
