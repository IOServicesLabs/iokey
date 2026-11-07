package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Touch
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finger does on the keys, apart from which key it hits: a tap stays a tap, a slide on space does what it looks
 * like, a second finger neither loses nor runs away with text, and a glide survives a small overshoot.
 */
class TouchGestureTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT

    private fun centre(label: String): Touch.Point {
        val r = kb.layout().require(label).rect
        return Touch.Point(r.exactCenterX(), r.exactCenterY())
    }

    private fun dp(v: Int): Float = v * host.onMain { it.resources.displayMetrics.density }

    /** Text in the field and past the first letter, so that Shift has settled and any letter that follows is lower case. */
    private fun startWithSomeText() {
        showReady(chat)
        kb.type("so ")
        assertText("So ")
    }

    /** As SwipeTest: the first gesture of a new process is a test of its own, and here it is out of the way. */
    private fun warmUpGlide() {
        kb.show(HostActivity.SINGLE)
        ime.waitIdle()
        kb.glide("hello")
        SystemClock.sleep(GLIDE_PAUSE_MS)
    }

    private fun settledText(before: String): String {
        Wait.until(GLIDE_COMMIT_CEILING_MS) { host.text(chat) != before }
        return Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(chat) }
    }

    // ---------------------------------------------------------------------------------------------
    // Rollover from a key that changes the page
    // ---------------------------------------------------------------------------------------------

    private fun emojiPanelNode(text: String): Rect? {
        E2eDevice.ua.clearCache()
        val window = E2eDevice.ua.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        val node = window.root?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return null
        return Rect().also { node.getBoundsInScreen(it) }
    }

    private fun assertEmojiPanelOpen() {
        val open = Wait.until(PANEL_MS, 60) { emojiPanelNode(EMOJI_PANEL_BACKSPACE) != null }
        assertTrue("The emoji key's own press must have opened the panel. In its windows: ${kb.visibleTextInImeWindows()}", open)
    }

    /** Back to the letters by the panel's own key, and proves the keyboard is whole. */
    private fun closeEmojiPanel() {
        val abc = checkNotNull(emojiPanelNode(EMOJI_PANEL_LETTERS)) { "The emoji panel has no letters key" }
        touch.tap(abc.exactCenterX(), abc.exactCenterY())
        kb.forgetLayout()
        check(Wait.until(PANEL_MS, 60) { kb.readLayout()?.isLetters == true }) { "The letters did not come back" }
        kb.forgetLayout()
        kb.assertIokeyShowing()
    }

    /**
     * The panel takes the view off the window, so nothing it started for the second finger can ever hear that finger
     * lift. Left to run, the repeat would delete characters and then words behind the panel.
     */
    @Test
    fun emojiThenDelete_deletesNothing() {
        startWithSomeText()
        touch.rollover(centre(EMOJI_KEY), centre(Keyboard.DELETE))
        assertTextStays("So ", forMs = RUNAWAY_WATCH_MS, message = "a second finger on Backspace while emoji opens")
        assertEmojiPanelOpen()
        closeEmojiPanel()
        assertTextStays("So ", message = "and nothing has gone behind the panel")
    }

    /** A popup for that finger would have no window to show from, and the process would go down with it. */
    @Test
    fun emojiThenLetter_typesNothingAndNoPopup() {
        startWithSomeText()
        val process = ime.pid()
        touch.rollover(centre(EMOJI_KEY), centre("a"))
        SystemClock.sleep(PAST_THE_LONG_PRESS_MS)
        assertEquals("The keyboard's process must live through this", process, ime.pid())
        assertTextStays("So ", message = "a second finger on a letter while emoji opens")
        assertEmojiPanelOpen()
        closeEmojiPanel()
        kb.tapKey("a")
        assertTextStaysIgnoringCase("So a", message = "back on the letters, a tap types its own letter")
    }

    /** Holding Enter sends the message, so a timer left running for it would send one with nobody holding anything. */
    @Test
    fun emojiThenEnter_firesNothing() {
        startWithSomeText()
        touch.rollover(centre(EMOJI_KEY), centre(Keyboard.ENTER))
        SystemClock.sleep(PAST_THE_LONG_PRESS_MS)
        assertTextStays("So ", message = "no newline from a second finger on Enter while emoji opens")
        assertEmojiPanelOpen()
        closeEmojiPanel()

        val send = HostActivity.SEND
        kb.show(send)
        kb.type("so ")
        assertText("So ", field = send)
        val actions = host.editorActions()
        touch.rollover(centre(EMOJI_KEY), centre(Keyboard.ENTER))
        SystemClock.sleep(PAST_THE_LONG_PRESS_MS)
        assertEquals("Nothing may be sent", actions, host.editorActions())
    }

    /** On the symbols page the first row is the digits, so a finger landing where q was lands on 1. */
    @Test
    fun symbolsThenDigit_typesTheDigit() {
        startWithSomeText()
        touch.rollover(centre(Keyboard.SYMBOLS), centre("q"))
        assertTextStays("So 1", message = "the second finger types the key under it on the new page")
    }

    @Test
    fun symbolsThenDelete_takesOneCharacter() {
        startWithSomeText()
        touch.rollover(centre(Keyboard.SYMBOLS), centre(Keyboard.DELETE))
        assertTextStays("So", forMs = RUNAWAY_WATCH_MS, message = "one press, one character, and no repeat after the lift")
    }

    @Test
    fun symbolsThenEnter_isOneTap() {
        startWithSomeText()
        val actions = host.editorActions()
        touch.rollover(centre(Keyboard.SYMBOLS), centre(Keyboard.ENTER))
        assertTextStays("So \n", forMs = LONG_PRESS_WATCH_MS, message = "one tap's newline, and nothing more when a long press would have fired")
        assertEquals("A tap on Enter here is not the field's action", actions, host.editorActions())
    }

    // ---------------------------------------------------------------------------------------------
    // A finger still down as the keyboard changes under it
    // ---------------------------------------------------------------------------------------------

    /** A hidden window hears no lift, so a Backspace held as it goes must not go on deleting behind it. */
    @Test
    fun holdBackspaceThenHide_stopsDeleting() {
        host.setText(chat, LONG_TEXT)
        showReady(chat)
        moveCaretByHand(LONG_TEXT.length)
        val key = centre(Keyboard.DELETE)
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, listOf(0 to key), wait = true)
        // Not waited on: the window this finger came down on has gone, and the system may have nowhere to send its lift.
        atEnd { inject(down, MotionEvent.ACTION_UP, listOf(0 to key), wait = false) }
        val repeating = Wait.until(RUNAWAY_WATCH_MS) { host.text(chat).length < LONG_TEXT.length - 1 }
        assertTrue("A held Backspace must be repeating before the keyboard hides, or this proves nothing", repeating)
        kb.hideWithBack()
        val left = Wait.untilStable(HIDE_SETTLE_MS) { host.text(chat) }
        assertTrue("The deleting must stop with text still there to delete, and the field holds '$left'", left.isNotEmpty())
        assertTextStays(left, forMs = RUNAWAY_WATCH_MS, message = "nothing more goes once the keyboard is down")
    }

    /** An app can move on to its next field as this one empties, with the keyboard up and the same keys. A hold was never meant for that field. */
    @Test
    fun holdBackspaceThenFocusMoves_leavesTheNextFieldAlone() {
        val next = HostActivity.SINGLE
        host.setText(next, NEXT_FIELD_TEXT)
        host.setText(chat, LONG_TEXT)
        showReady(chat)
        moveCaretByHand(LONG_TEXT.length)
        val key = centre(Keyboard.DELETE)
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, listOf(0 to key), wait = true)
        atEnd { inject(down, MotionEvent.ACTION_UP, listOf(0 to key), wait = false) }
        val repeating = Wait.until(RUNAWAY_WATCH_MS) { host.text(chat).length < LONG_TEXT.length - 1 }
        assertTrue("A held Backspace must be repeating before the focus moves, or this proves nothing", repeating)
        host.onMain { it.field(next).requestFocus() }
        val left = Wait.untilStable(HIDE_SETTLE_MS) { host.text(chat) }
        assertTextStays(left, forMs = RUNAWAY_WATCH_MS, message = "the field it was held in, once the focus has gone")
        assertEquals("The next field keeps its text", NEXT_FIELD_TEXT, host.text(next))
    }

    /** A restart of the field goes back to the letters from any page, and by the lift the key under the finger has gone. */
    @Test
    fun fieldRestartChangingThePage_typesNothingAtTheLift() {
        startWithSomeText()
        kb.tapKey(Keyboard.SYMBOLS)
        check(Wait.until(PANEL_MS, 60) { kb.readLayout()?.face(Keyboard.LETTERS) != null }) { "The symbols page did not open" }
        kb.forgetLayout()
        val key = centre("1")
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, listOf(0 to key), wait = true)
        host.setText(chat, "So ")
        check(Wait.until(PANEL_MS, 60) { kb.readLayout()?.isLetters == true }) { "The restart did not bring the letters back" }
        kb.forgetLayout()
        inject(down, MotionEvent.ACTION_UP, listOf(0 to key), wait = true)
        assertTextStays("So ", message = "the finger came down on a key that is no longer there to type")
    }

    // ---------------------------------------------------------------------------------------------
    // Space
    // ---------------------------------------------------------------------------------------------

    /** A thumb that rolls sideways on the most used key still gets its space. */
    @Test
    fun smallSlideOnSpace_typesASpace() {
        showReady(chat)
        kb.type("so")
        assertText("So")
        val space = centre(Keyboard.SPACE)
        touch.glide(listOf(space, Touch.Point(space.x + dp(SMALL_SLIDE_DP), space.y)), SPACE_SLIDE_MS)
        assertTextStays("So ", message = "a slide under the arming distance is a tap")
        assertEquals("The caret stays at the end", 3, host.caret(chat))
    }

    @Test
    fun longerSlideOnSpace_movesTheCaretAndTypesNoSpace() {
        showReady(chat)
        kb.type("so")
        assertText("So")
        val space = centre(Keyboard.SPACE)
        touch.glide(listOf(space, Touch.Point(space.x - dp(LONGER_SLIDE_DP), space.y)), SPACE_SLIDE_MS)
        assertTextStays("So", message = "a slide past the arming distance types no space")
        val moved = Wait.until(CARET_MS) { host.caret(chat) == 1 }
        assertTrue("The caret moves back one at once, and is at ${host.caret(chat)}", moved)
    }

    // ---------------------------------------------------------------------------------------------
    // A slip while typing, and a flick right after it
    //
    // In both of these the tap and the stroke follow each other with no assert, read or sleep between, because the
    // keyboard treats a touch differently for 500 ms after a tap, and an assert can take longer than that.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun tapThenOneKeySlip_typesTheLetter() {
        startWithSomeText()
        val tapped = centre("t")
        val from = centre("h")
        val to = centre("j")
        tapThenStroke(tapped, from, to, SLIP_MS)
        assertTextStays("So th", message = "a finger that slips one key right after a tap has typed the key it came down on")
    }

    @Test
    fun flickToTheBarRightAfterTyping_picksTheWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so tomorr")
        assertText("So tomorr")
        SystemClock.sleep(BAR_SETTLE_MS)
        val grid = kb.layout().view
        val bar = Touch.Point(grid.width() * MAIN_SLOT_X, grid.top - dp(FLICK_ABOVE_KEYS_DP))
        val tapped = centre("o")
        val from = centre("r")
        tapThenStroke(tapped, from, bar, SLIP_MS, restMs = SLIDE_UP_REST_MS)
        settledText("So tomorro")
        assertTextStays("So tomorrow ", message = "a quick flick up to the bar right after a letter still picks the word")
    }

    // ---------------------------------------------------------------------------------------------
    // Slow starts, and a second finger during a glide
    // ---------------------------------------------------------------------------------------------

    /** A finger on a key is never quite still. A hold with a little drift is still a hold. */
    @Test
    fun driftingHold_opensThePopup() {
        startWithSomeText()
        val key = centre("e")
        val drifted = Touch.Point(key.x + DRIFT_PX, key.y)
        touch.glide(listOf(Touch.Stroke(listOf(key, drifted), DRIFT_MS), Touch.Stroke(listOf(drifted), HOLD_AFTER_DRIFT_MS)))
        assertTextStays("So e", message = "hold and lift on e types the letter its popup leads with")
    }

    /** Far enough in the time a long press takes that this is a glide starting slowly, and a lift must not type the popup's digit. */
    @Test
    fun slowSlideStart_opensNoPopup() {
        startWithSomeText()
        val key = centre("e")
        val moved = Touch.Point(key.x + SLOW_SLIDE_PX, key.y)
        touch.glide(listOf(Touch.Stroke(listOf(key, moved), SLOW_SLIDE_MS), Touch.Stroke(listOf(moved), HOLD_AFTER_SLOW_SLIDE_MS)))
        val text = settledText("So ")
        record("field", text)
        assertFalse("No popup may have opened, but the field holds '$text'", text.contains("3"))
        val added = text.removePrefix("So ").trim()
        assertTrue("The lift types a letter or a word, and the field holds '$text'", added.isNotEmpty() && added.all { it.isLetter() })
    }

    /** Too far to be a finger at rest and too short to be a glide, held well past the long press: only the key it began on is left. */
    @Test
    fun slideShortOfArming_typesTheLetter() {
        startWithSomeText()
        val key = centre("e")
        val moved = Touch.Point(key.x + SHORT_SLIDE_PX, key.y)
        touch.glide(listOf(Touch.Stroke(listOf(key, moved), SHORT_SLIDE_MS), Touch.Stroke(listOf(moved), HOLD_AFTER_SHORT_SLIDE_MS)))
        assertTextStays("So e", message = "the letter, and not the digit its popup leads with")
    }

    /** A popup whose finger never went anywhere was a slow tap, so a second finger landing types its key and then its own. */
    @Test
    fun secondFingerOnAnOpenPopup_typesTheKeyAndTheNext() {
        startWithSomeText()
        val held = centre("e")
        val next = centre("t")
        val both = listOf(0 to held, 1 to next)
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, listOf(0 to held), wait = true)
        SystemClock.sleep(PAST_THE_LONG_PRESS_MS)
        inject(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both, wait = true)
        SystemClock.sleep(Touch.DEFAULT_HOLD_MS)
        inject(down, MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both, wait = true)
        SystemClock.sleep(Touch.DEFAULT_HOLD_MS)
        inject(down, MotionEvent.ACTION_UP, listOf(1 to next), wait = true)
        assertTextStays("So et", message = "both letters, in the order the fingers came down")
    }

    private fun along(path: List<Touch.Point>, fraction: Float): Touch.Point {
        val lengths = FloatArray(path.size)
        for (i in 1 until path.size) {
            lengths[i] = lengths[i - 1] + kotlin.math.hypot(path[i].x - path[i - 1].x, path[i].y - path[i - 1].y)
        }
        val want = lengths.last() * fraction
        var i = 1
        while (i < path.size - 1 && lengths[i] < want) i++
        val span = lengths[i] - lengths[i - 1]
        val t = if (span <= 0f) 0f else (want - lengths[i - 1]) / span
        return Touch.Point(path[i - 1].x + (path[i].x - path[i - 1].x) * t, path[i - 1].y + (path[i].y - path[i - 1].y) * t)
    }

    private fun inject(down: Long, action: Int, pointers: List<Pair<Int, Touch.Point>>, wait: Boolean, at: Long = SystemClock.uptimeMillis()) {
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
        val event = MotionEvent.obtain(
            down, at, action, pointers.size, props, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val delivered = E2eDevice.ua.injectInputEvent(event, wait)
        event.recycle()
        check(delivered) { "The system refused an injected input event" }
    }

    /**
     * A tap and the stroke straight after it, each event stamped with when the finger did it, as a digitizer stamps them
     * whatever the keyboard is busy with. [Touch] waits for the keyboard at every down, so in a process still warming up
     * its stroke carries later times than any finger made, and the touch looks older than the 150 ms in question.
     */
    private fun tapThenStroke(tap: Touch.Point, from: Touch.Point, to: Touch.Point, strokeMs: Long, restMs: Long = 0L) {
        val tapDown = SystemClock.uptimeMillis()
        val strokeDown = tapDown + Touch.DEFAULT_HOLD_MS
        inject(tapDown, MotionEvent.ACTION_DOWN, listOf(0 to tap), wait = false, at = tapDown)
        SystemClock.sleep(Touch.DEFAULT_HOLD_MS)
        inject(tapDown, MotionEvent.ACTION_UP, listOf(0 to tap), wait = false, at = strokeDown)
        inject(strokeDown, MotionEvent.ACTION_DOWN, listOf(0 to from), wait = false, at = strokeDown)
        var elapsed = 0L
        while (elapsed < strokeMs) {
            SystemClock.sleep(SAMPLE_MS)
            elapsed += SAMPLE_MS
            val point = along(listOf(from, to), minOf(elapsed.toFloat() / strokeMs, 1f))
            inject(strokeDown, MotionEvent.ACTION_MOVE, listOf(0 to point), wait = false, at = strokeDown + elapsed)
        }
        SystemClock.sleep(restMs)
        inject(strokeDown, MotionEvent.ACTION_UP, listOf(0 to to), wait = false, at = strokeDown + elapsed + restMs)
    }

    /** One finger along [path], and a thumb that comes down at [thumb] [thumbAt] of the way through and stays until after the lift. */
    private fun glideWhileAThumbLands(path: List<Touch.Point>, thumb: Touch.Point, thumbAt: Float, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, listOf(0 to path.first()), wait = true)
        var thumbDown = false
        val start = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= durationMs) break
            val fraction = elapsed.toFloat() / durationMs
            val at = along(path, fraction)
            if (!thumbDown && fraction >= thumbAt) {
                inject(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0 to at, 1 to thumb), wait = true)
                thumbDown = true
            } else {
                inject(down, MotionEvent.ACTION_MOVE, if (thumbDown) listOf(0 to at, 1 to thumb) else listOf(0 to at), wait = false)
            }
            SystemClock.sleep(SAMPLE_MS)
        }
        val both = listOf(0 to path.last(), 1 to thumb)
        inject(down, MotionEvent.ACTION_MOVE, both, wait = false)
        SystemClock.sleep(Keyboard.GLIDE_END_REST_MS)
        inject(down, MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both, wait = true)
        SystemClock.sleep(SAMPLE_MS)
        inject(down, MotionEvent.ACTION_UP, listOf(1 to thumb), wait = true)
    }

    /** Pointer [id] along [path] at a steady speed, ending on its last point. */
    private fun moveAlong(down: Long, id: Int, path: List<Touch.Point>, durationMs: Long) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= durationMs) break
            inject(down, MotionEvent.ACTION_MOVE, listOf(id to along(path, elapsed.toFloat() / durationMs)), wait = false)
            SystemClock.sleep(SAMPLE_MS)
        }
        inject(down, MotionEvent.ACTION_MOVE, listOf(id to path.last()), wait = false)
    }

    /**
     * One finger along [path] and at rest on its end, a thumb coming down at [thumb] [beforeLiftMs] before that finger lifts,
     * and the lift. The two are stamped as a digitizer stamps them, so that a slow moment here cannot change how long before
     * the lift the thumb landed. Returns the stream's down time, with the thumb still down for the caller to finish.
     */
    private fun glideWithAThumbJustBeforeTheLift(path: List<Touch.Point>, thumb: Touch.Point, beforeLiftMs: Long, durationMs: Long): Long {
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, listOf(0 to path.first()), wait = true)
        moveAlong(down, 0, path, durationMs)
        SystemClock.sleep(Keyboard.GLIDE_END_REST_MS)
        val both = listOf(0 to path.last(), 1 to thumb)
        val landed = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both, wait = true, at = landed)
        SystemClock.sleep(beforeLiftMs)
        inject(down, MotionEvent.ACTION_POINTER_UP or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), both, wait = true, at = landed + beforeLiftMs)
        return down
    }

    /** With a word typed and the first gesture of the process out of the way, the keys of the word to glide. */
    private fun readyToGlide(word: String): List<Touch.Point> {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        return word.map { centre(it.toString()) }
    }

    /** A thumb coming to rest on the board halfway through used to commit half a word, and here it must change nothing. */
    @Test
    fun glideWithASecondFinger_keepsGoing() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        val word = "because"
        val path = word.map { centre(it.toString()) }
        glideWhileAThumbLands(path, centre(Keyboard.SPACE), THUMB_AT, Keyboard.GLIDE_MS_PER_KEY * word.length)
        settledText("So ")
        assertTextStays("So because ", message = "the glide carries on past the second finger, and that finger types nothing")
    }

    /** Space would hide it, because the tap is absorbed after a glide's own space. On a letter it would show. */
    @Test
    fun restingThumbOnALetter_typesNothing() {
        val path = readyToGlide("because")
        glideWhileAThumbLands(path, centre("t"), THUMB_AT, Keyboard.GLIDE_MS_PER_KEY * path.size)
        settledText("So ")
        assertTextStays("So because ", message = "a thumb that came down long before the lift is at rest, and stays that way after it")
    }

    /** A thumb at rest on Space is not a tap either, or Backspace would take the space and not the word. */
    @Test
    fun restingThumbOnSpace_leavesBackspaceTheWholeWord() {
        val path = readyToGlide("because")
        glideWhileAThumbLands(path, centre(Keyboard.SPACE), THUMB_AT, Keyboard.GLIDE_MS_PER_KEY * path.size)
        settledText("So ")
        assertText("So because ")
        kb.backspace()
        assertTextStays("So ", message = "one press takes back what one gesture wrote")
    }

    /** The glide of a word with the other thumb tapping [key] across its lift: down just before it, up just after. */
    private fun glideWithALateTapOn(key: String) {
        val path = readyToGlide("because")
        val thumb = centre(key)
        val down = glideWithAThumbJustBeforeTheLift(path, thumb, JUST_BEFORE_THE_LIFT_MS, Keyboard.GLIDE_MS_PER_KEY * path.size)
        SystemClock.sleep(Touch.DEFAULT_HOLD_MS)
        inject(down, MotionEvent.ACTION_UP, listOf(1 to thumb), wait = true)
        settledText("So ")
    }

    /** Two thumbs: the next letter comes down just before the glide lifts, and it lands after the word. */
    @Test
    fun thumbJustBeforeTheLift_typesItsLetterAfterTheWord() {
        glideWithALateTapOn("t")
        assertTextStays("So because t", message = "the word, then the letter")
    }

    /** From there it is a letter like any other: the next tap joins it, and Backspace takes one at a time. */
    @Test
    fun thumbJustBeforeTheLift_nextTapAndBackspaceFollowTheLetter() {
        glideWithALateTapOn("t")
        assertText("So because t")
        kb.tapKey("h")
        assertText("So because th")
        kb.backspace(2)
        assertTextStays("So because ", message = "two presses take two letters, and the word stands")
    }

    /** A tap of Space like any other after a glide: its space is absorbed, and Backspace then takes that space and not the word. */
    @Test
    fun thumbJustBeforeTheLiftOnSpace_isATapOfSpace() {
        glideWithALateTapOn(Keyboard.SPACE)
        assertText("So because ")
        kb.backspace()
        assertTextStays("So because", message = "as after any Space tapped by hand")
    }

    /** The thumb that came down at the start of the next word goes on to glide it. */
    @Test
    fun thumbJustBeforeTheLift_canGlideTheNextWord() {
        val path = readyToGlide("because")
        val next = "the".map { centre(it.toString()) }
        val down = glideWithAThumbJustBeforeTheLift(path, next.first(), JUST_BEFORE_THE_LIFT_MS, Keyboard.GLIDE_MS_PER_KEY * path.size)
        moveAlong(down, 1, next, Keyboard.GLIDE_MS_PER_KEY * next.size)
        SystemClock.sleep(Keyboard.GLIDE_END_REST_MS)
        inject(down, MotionEvent.ACTION_UP, listOf(1 to next.last()), wait = true)
        settledText("So ")
        assertTextStays("So because the ", message = "both words, each from its own finger")
    }

    /** Backspace acts on the touch, so it stays out of this, or it would take the word that has just landed. */
    @Test
    fun thumbJustBeforeTheLiftOnBackspace_takesNothing() {
        glideWithALateTapOn(Keyboard.DELETE)
        assertTextStays("So because ", message = "the word stands")
    }

    // ---------------------------------------------------------------------------------------------
    // The bar
    // ---------------------------------------------------------------------------------------------

    /** A stroke along the top row that rises above the keys and comes back, the way a thumb aims high. */
    @Test
    fun overshootAboveTheTopRow_keepsTheWord() = assertOvershootKeepsTheWord(OVERSHOOT_DP)

    /** Too little to count as leaving the keys, the commonest overshoot of all, so these points go to the decoder as they are. */
    @Test
    fun smallOvershootAboveTheTopRow_keepsTheWord() = assertOvershootKeepsTheWord(SMALL_OVERSHOOT_DP)

    private fun assertOvershootKeepsTheWord(overshootDp: Int) {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        // A short hop, up and back within about a key, so that it comes down where it went up: coming down far off is the next test.
        val i = centre("i")
        val hop = (centre("o").x - i.x) / 2f
        val above = kb.layout().view.top - dp(overshootDp)
        val path = listOf(
            centre("t"), centre("y"),
            Touch.Point(i.x - hop, i.y), Touch.Point(i.x, above), Touch.Point(i.x + hop, i.y),
            centre("p"), centre("e"),
        )
        touch.glide(path, Keyboard.GLIDE_MS_PER_KEY * 4, dwellEndMs = Keyboard.GLIDE_END_REST_MS)
        settledText("So ")
        assertTextStays("So type ", message = "the part of the stroke above the keys costs nothing")
    }

    /** Up onto the bar and down again a long way off: the straight line between would be a stroke nobody traced. */
    @Test
    fun slideToTheBarAndFarBack_commitsNoWord() {
        warmUpGlide()
        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        val grid = kb.layout().view
        val barY = (kb.windowState().frame.top + grid.top) / 2f
        val q = centre("q")
        val w = centre("w")
        val farX = w.x + (w.x - q.x) * FAR_BACK_KEYS
        val path = listOf(q, w, Touch.Point(w.x, barY), Touch.Point(farX, barY), Touch.Point(farX, centre("a").y))
        touch.glide(path, FAR_BACK_MS, dwellEndMs = FAR_BACK_REST_MS)
        assertTextStays("So ", forMs = LONG_PRESS_WATCH_MS, message = "nothing lands, neither a word nor the letter it began on")
    }

    // ---------------------------------------------------------------------------------------------
    // A field with no glide
    // ---------------------------------------------------------------------------------------------

    /**
     * Among the dots of a password nobody could see a word go missing. A drag there is a slow tap on the key it began on,
     * and only that: a long press must not catch it either, or a digit lands in the password.
     */
    @Test
    fun dragInAPasswordField_typesThePressedLetter() {
        warmUpGlide()
        val password = HostActivity.PASSWORD
        kb.show(password)
        touch.glide(listOf(centre("a"), centre("l")), DRAG_MS)
        assertTextStaysIgnoringCase("a", field = password, message = "a drag across the letters")

        kb.show(chat)
        kb.type("so ")
        assertText("So ")
        touch.glide(listOf(centre("a"), centre("l")), DRAG_MS)
        val text = settledText("So ")
        record("field", text)
        assertTrue("The same drag in a normal field still glides, and the field holds '$text'", text.length > "So a".length)
    }

    private companion object {
        const val EMOJI_KEY = "Emoji"
        const val EMOJI_PANEL_BACKSPACE = "backspace"
        const val EMOJI_PANEL_LETTERS = "ABC"
        const val PANEL_MS = 3_000L

        /** Past the 400 ms repeat delay, with time for a runaway repeat to take something. */
        const val RUNAWAY_WATCH_MS = 2_000L

        /** Far more than a hold can take before the keyboard is down, even a slow hide: 50 ms a character, then 180 ms a word. */
        val LONG_TEXT = "word ".repeat(60).trim()
        const val NEXT_FIELD_TEXT = "other words here"
        const val HIDE_SETTLE_MS = 3_000L

        /** Past the 380 ms a long press takes. */
        const val PAST_THE_LONG_PRESS_MS = 600L
        const val LONG_PRESS_WATCH_MS = 1_000L

        const val SMALL_SLIDE_DP = 20
        const val LONGER_SLIDE_DP = 30
        const val SPACE_SLIDE_MS = 80L
        const val CARET_MS = 2_000L

        /** Inside the keyboard's 150 ms for a touch right after a tap. */
        const val SLIP_MS = 80L

        /** Under twice the touch slop here, about 46 px. */
        const val DRIFT_PX = 25f
        const val DRIFT_MS = 100L
        const val HOLD_AFTER_DRIFT_MS = 320L

        /** Past twice the touch slop and about where a glide arms, in less than the 380 ms a long press takes. */
        const val SLOW_SLIDE_PX = 70f
        const val SLOW_SLIDE_MS = 350L
        const val HOLD_AFTER_SLOW_SLIDE_MS = 100L

        /** Past twice the touch slop here, about 46 px, and short of where a glide arms, about 66 px. */
        const val SHORT_SLIDE_PX = 55f
        const val SHORT_SLIDE_MS = 100L
        const val HOLD_AFTER_SHORT_SLIDE_MS = 400L

        /**
         * Past the 10 dp that counts as leaving the keys, on a stroke from the top row that stays under the 1.6 keys,
         * about 151 px here, that a touch right after a tap needs on the keys. Only the bar's own rule can arm this one.
         */
        const val FLICK_ABOVE_KEYS_DP = 14

        const val THUMB_AT = 0.5f
        const val SAMPLE_MS = 12L

        /** Well inside the 200 ms before a glide's lift in which a second finger counts as the next touch. */
        const val JUST_BEFORE_THE_LIFT_MS = 100L

        const val OVERSHOOT_DP = 15

        /** Under the 10 dp above the keys that counts as leaving them. */
        const val SMALL_OVERSHOOT_DP = 6
        const val FAR_BACK_KEYS = 5f
        const val FAR_BACK_MS = 600L
        const val FAR_BACK_REST_MS = 200L

        const val DRAG_MS = 500L

        const val GLIDE_COMMIT_CEILING_MS = 2_500L
        const val GLIDE_SETTLE_MS = 1_500L
        const val GLIDE_PAUSE_MS = 400L
        const val BAR_SETTLE_MS = 600L
        const val SLIDE_UP_REST_MS = 120L

        /** Inside the bar's main slot, with or without the mic. */
        const val MAIN_SLOT_X = 0.375f
    }
}
