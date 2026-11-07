package io.ioservices.iokey.e2e.harness

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Drives and reads the keyboard through what a user or a screen reader can reach: its window, its
 * accessibility nodes and touches on the screen. Nothing here knows about the app's code.
 */
class Keyboard(
    private val ua: UiAutomation,
    private val shell: Shell,
    private val ime: Ime,
    private val host: Host,
    private val touch: Touch,
) {

    /** One drawn key. [label] is what the key announces to a screen reader. */
    data class Face(val label: String, val rect: Rect)

    /** What the window manager and the input method service report about the keyboard window. */
    data class WindowState(
        val shown: Boolean,
        val visible: Boolean,
        val requestedHeight: Int,
        val frame: Rect,
        /** On screen. The whole frame when the service does not limit touches to a region. */
        val touchable: Rect,
    )

    /** The drawn keys and the view that holds them, in screen coordinates. */
    data class Layout(val view: Rect, val faces: List<Face>) {
        fun face(label: String): Face? = faces.firstOrNull { it.label == label }

        fun require(label: String): Face =
            face(label) ?: throw AssertionError("No key labelled '$label' on the keyboard. Keys: ${faces.map { it.label }}")

        /** Lowest drawn edge of any key. */
        val lowestKeyBottom: Int get() = faces.maxOf { it.rect.bottom }

        val isLetters: Boolean get() = face("q") != null && face("a") != null
    }

    private var cached: Layout? = null

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    fun isInputShown(): Boolean = shell.exec("dumpsys input_method").contains("mInputShown=true")

    fun windowState(): WindowState {
        val win = shell.exec("dumpsys window InputMethod")
        val service = shell.exec("dumpsys input_method")
        val frame = FRAME.find(win)?.let { m ->
            val v = m.groupValues.drop(1).map { it.toInt() }
            Rect(v[0], v[1], v[2], v[3])
        } ?: Rect()
        val requested = REQUESTED.find(win)?.groupValues?.get(2)?.toInt() ?: 0
        val region = TOUCH_REGION.find(service)?.let { m ->
            val v = m.groupValues.drop(1).map { it.toInt() }
            Rect(v[0], v[1], v[2], v[3])
        }
        val regionInUse = TOUCH_INSETS.find(service)?.groupValues?.get(1) == TOUCHABLE_INSETS_REGION
        val touchable = if (regionInUse && region != null) {
            Rect(frame.left + region.left, frame.top + region.top, frame.left + region.right, frame.top + region.bottom)
        } else {
            Rect(frame)
        }
        return WindowState(
            shown = service.contains("mInputShown=true"),
            visible = win.contains("isVisible=true"),
            requestedHeight = requested,
            frame = frame,
            touchable = touchable,
        )
    }

    private fun imeWindow(): AccessibilityWindowInfo? =
        ua.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** The keys as they are drawn right now, or null when the key grid is not on screen. */
    fun readLayout(): Layout? {
        ua.clearCache()
        val root = imeWindow()?.root ?: return null
        if (root.packageName?.toString() != ime.pkg) return null
        val view = findKeyGrid(root) ?: return null
        val faces = ArrayList<Face>(view.childCount)
        for (i in 0 until view.childCount) {
            val child = view.getChild(i) ?: continue
            val label = child.contentDescription?.toString() ?: continue
            val r = Rect()
            child.getBoundsInScreen(r)
            faces += Face(label, r)
        }
        if (faces.isEmpty()) return null
        val bounds = Rect()
        view.getBoundsInScreen(bounds)
        return Layout(bounds, faces).also { cached = it }
    }

    /** The key grid is the one view whose children are virtual buttons, one for each key. */
    private fun findKeyGrid(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.childCount >= MIN_KEYS_IN_GRID && node.getChild(0)?.className?.toString() == BUTTON_CLASS) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findKeyGrid(it) }
            if (found != null) return found
        }
        return null
    }

    /**
     * How far up from the bottom of the screen the system's own buttons reach, where it draws back and switch
     * buttons inside the keyboard's window, as it does in gesture navigation here. Zero where it does not.
     */
    fun systemButtonsHeight(screenHeight: Int): Int = systemBackButton()?.let { screenHeight - it.top } ?: 0

    /** The system's back button inside the keyboard's window, on screen, or null where it draws none. */
    fun systemBackButton(): Rect? {
        ua.clearCache()
        val back = imeWindow()?.root?.findAccessibilityNodeInfosByViewId(SYSTEM_BACK_BUTTON_ID)?.firstOrNull() ?: return null
        val r = Rect()
        back.getBoundsInScreen(r)
        return r.takeUnless { it.isEmpty }
    }

    /** Any text in any window the keyboard owns or opened, for prompts and panels drawn as real views. */
    fun visibleTextInImeWindows(): List<String> {
        ua.clearCache()
        val out = ArrayList<String>()
        fun walk(node: AccessibilityNodeInfo) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        for (w in ua.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == ime.pkg) walk(root)
        }
        return out
    }

    fun layout(): Layout = cached ?: readLayout() ?: throw AssertionError("The keyboard's keys cannot be read")

    fun forgetLayout() {
        cached = null
    }

    // ---------------------------------------------------------------------------------------------
    // Showing and hiding
    // ---------------------------------------------------------------------------------------------

    /**
     * Taps the field the way a user would and waits for the keyboard to finish coming up. [firstHoldMs] is for the
     * test of this method, which needs a first tap that is sure to land as a long press.
     */
    fun show(field: String, firstHoldMs: Long = Touch.DEFAULT_HOLD_MS) {
        forgetLayout()
        ime.ensureRunning()
        val r = host.fieldRect(field)
        tapFieldUntilItIsNotALongPress((r.right - 30).toFloat(), (r.bottom - 30).toFloat(), firstHoldMs)
        waitForShown()
    }

    /**
     * On a starved emulator a 45 ms tap can land as a long press. The field then opens its floating menu, and after
     * a long press it never asks for the keyboard, so the wait below would run out with nothing wrong with the keyboard.
     * The tap goes again only when the field reports that long click, so a keyboard that stays down after a real tap
     * still fails. The injected lift waits for the host to finish with it, so any long click has been counted by then.
     */
    private fun tapFieldUntilItIsNotALongPress(x: Float, y: Float, firstHoldMs: Long) {
        var holdMs = firstHoldMs
        repeat(MAX_TAPS_ON_A_FIELD) {
            val longClicksBefore = host.fieldLongClicks()
            touch.tap(x, y, holdMs)
            if (host.fieldLongClicks() == longClicksBefore) return
            tapsThatLandedAsLongPresses++
            Log.w(TAG, "A tap on a field landed as a long press, held $holdMs ms, so it goes again")
            holdMs = Touch.DEFAULT_HOLD_MS
            // Past the double-tap window, or the next tap would be read as the second of two.
            SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + DOUBLE_TAP_MARGIN_MS)
        }
    }

    fun waitForShown() {
        if (!Wait.until(SHOW_TIMEOUT_MS, 100) { isInputShown() && readLayout()?.isLetters == true }) {
            fail(
                "The keyboard did not come up within ${SHOW_TIMEOUT_MS / 1000} s. Current IME: ${ime.currentIme()}, " +
                    describeState() + ", taps that landed as long presses in this test: $tapsThatLandedAsLongPresses"
            )
        }
        settle()
        assertIokeyShowing()
    }

    /** Waits until the window and the keys stop moving, so an entry animation is not measured. */
    fun settle() {
        Wait.untilStable(SETTLE_TIMEOUT_MS) { windowState() to readLayout()?.faces }
        forgetLayout()
    }

    /** Back, as the user dismisses the keyboard. Pressing it with the keyboard down would leave the app. */
    fun hideWithBack() {
        if (!isInputShown()) return
        touch.key(KeyEvent.KEYCODE_BACK)
        waitForHidden()
    }

    /**
     * Hides and waits for the system to take the keyboard's window down, which comes some time after it stops
     * being visible. A configuration change before that takes another path through the framework, so a test
     * of something that happens "while hidden" must wait here or it is really testing that race.
     */
    fun hideCompletely() {
        hideWithBack()
        val gone = Wait.until(WINDOW_GONE_TIMEOUT_MS, 100) { shell.exec("dumpsys window InputMethod").contains(VIEW_GONE) }
        check(gone) {
            "The keyboard's window was not taken down: " +
                shell.exec("dumpsys window InputMethod").lineSequence().firstOrNull { it.contains("mViewVisibility") }
        }
        SystemClock.sleep(AFTER_GONE_MS)
    }

    fun waitForHidden() {
        check(Wait.until(5_000, 100) { !isInputShown() }) { "The keyboard did not hide" }
        // The window is removed by an exit animation after the service has been told.
        Wait.until(2_000, 100) { !windowState().visible }
        forgetLayout()
    }

    /**
     * Every test must pass through this before it believes anything it reads. A silent fallback to another
     * keyboard, or a keyboard that never appeared, would otherwise look like a pass or a false failure.
     */
    fun assertIokeyShowing() {
        assertEquals("IOKey must be the selected keyboard", ime.id, ime.currentIme())
        val state = windowState()
        assertTrue("The keyboard must be showing: $state", state.shown && state.visible && state.frame.height() > 0)
        val layout = readLayout()
        assertTrue("IOKey's letter keys must be readable on screen: $layout", layout?.isLetters == true)
        forgetLayout()
        confirmedShowing = true
    }

    /** The window keeps nothing clear below the keys although the system has a navigation area there. */
    fun isShort(): Boolean {
        val grid = readLayout()?.view ?: return false
        return host.navigationBarBottomPx() > 0 && grid.bottom >= windowState().frame.bottom
    }

    /** Whether [assertIokeyShowing] has passed since this was last cleared. */
    var confirmedShowing = false
        private set

    fun clearConfirmation() {
        confirmedShowing = false
    }

    /** Taps on a field that landed as long presses since this was last taken. Each one is the emulator stalling. */
    var tapsThatLandedAsLongPresses = 0
        private set

    fun takeTapsThatLandedAsLongPresses(): Int = tapsThatLandedAsLongPresses.also { tapsThatLandedAsLongPresses = 0 }

    fun describeState(): String {
        val state = runCatching { windowState().toString() }.getOrElse { "window state unreadable: $it" }
        val keys = runCatching { readLayout()?.faces?.size ?: 0 }.getOrElse { -1 }
        return "$state, keys readable: $keys, process: ${ime.pid()}"
    }

    // ---------------------------------------------------------------------------------------------
    // Typing
    // ---------------------------------------------------------------------------------------------

    fun tapKey(label: String, holdMs: Long = Touch.DEFAULT_HOLD_MS) {
        val r = layout().require(label).rect
        touch.tap(r.exactCenterX(), r.exactCenterY(), holdMs)
    }

    /** Taps the keys of [text] at a realistic pace. Letters are given in lower case; shift is the keyboard's business. */
    fun type(text: String, gapMs: Long = TYPING_GAP_MS) {
        for (c in text) {
            tapKey(labelFor(c))
            SystemClock.sleep(gapMs)
        }
    }

    fun backspace(times: Int = 1, gapMs: Long = TYPING_GAP_MS) {
        repeat(times) {
            tapKey(DELETE)
            SystemClock.sleep(gapMs)
        }
    }

    /** Types one character that lives on the symbols page, and returns to the letters. */
    fun typeSymbol(symbol: String) {
        tapKey(SYMBOLS)
        readSettledLayout { it.face(symbol) != null }
        tapKey(symbol)
        SystemClock.sleep(TYPING_GAP_MS)
        val now = readSettledLayout { true }
        if (now.face(LETTERS) != null) {
            tapKey(LETTERS)
            readSettledLayout { it.isLetters }
        }
    }

    private fun readSettledLayout(condition: (Layout) -> Boolean): Layout {
        var last: Layout? = null
        Wait.until(3_000, 60) {
            last = readLayout()
            last?.let(condition) == true
        }
        return last ?: throw AssertionError("The keyboard's keys cannot be read")
    }

    /**
     * A glide through the centres of the keys of [word], as a careful finger would trace it. It rests on the last
     * key before lifting, so the outcome is the keyboard's reading of the whole stroke. What a lift without that
     * rest does is for the tests that ask about it, and only those, by way of [endRestMs].
     */
    fun glide(word: String, msPerKey: Long = GLIDE_MS_PER_KEY, endRestMs: Long = GLIDE_END_REST_MS) {
        val faces = layout()
        val path = ArrayList<Touch.Point>(word.length)
        for (c in word) {
            if (!c.isLetter()) continue
            val r = faces.require(c.lowercaseChar().toString()).rect
            val p = Touch.Point(r.exactCenterX(), r.exactCenterY())
            if (path.lastOrNull() != p) path += p
        }
        touch.glide(path, msPerKey * word.length, dwellEndMs = endRestMs)
    }

    fun labelFor(c: Char): String = when (c) {
        ' ' -> SPACE
        ',' -> COMMA
        '.' -> PERIOD
        '\n' -> ENTER
        else -> c.lowercaseChar().toString()
    }

    // ---------------------------------------------------------------------------------------------
    // The suggestion bar
    // ---------------------------------------------------------------------------------------------

    enum class Slot { LEFT, CENTRE, RIGHT }

    /**
     * Taps by position, for what has no node of its own or for a test about the position itself: three equal slots,
     * then up to two buttons of 48 dp, the mic and the sparkle, either of which may be absent. The x positions used
     * here sit inside the same slot with no button, one or two, on any phone's width.
     */
    fun tapSlot(slot: Slot) {
        val (x, y) = slotCentre(slot)
        touch.tap(x, y)
    }

    /** One of the bar's nodes: a word, the sparkle, the pointer, the mic or the paste chip. [label] is what it announces. */
    data class BarNode(val label: String, val rect: Rect)

    /**
     * What the bar offers right now, from its accessibility nodes: every button with a description above the keys.
     * The notice and the emoji row are not buttons, and the keys are not above themselves. Empty when no key grid can be read.
     */
    fun barNodes(): List<BarNode> = barNodesOrNull().orEmpty()

    /** The same, with null when no key grid can be read. A bar that cannot be read has nothing on it either, and that proves nothing. */
    private fun barNodesOrNull(): List<BarNode>? {
        ua.clearCache()
        val root = imeWindow()?.root ?: return null
        if (root.packageName?.toString() != ime.pkg) return null
        val grid = findKeyGrid(root) ?: return null
        val keysTop = Rect().also(grid::getBoundsInScreen).top
        val out = ArrayList<BarNode>(BAR_NODES_AT_MOST)
        fun walk(node: AccessibilityNodeInfo) {
            val label = node.contentDescription?.toString()
            if (node.className?.toString() == BUTTON_CLASS && !label.isNullOrBlank()) {
                val r = Rect().also(node::getBoundsInScreen)
                if (r.bottom <= keysTop) out += BarNode(label, r)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return out
    }

    /** For a test saying what is not on the bar: waits for the nodes to be readable, and fails if they never are. */
    fun readableBarNodes(): List<BarNode> {
        var nodes: List<BarNode>? = null
        Wait.until(BAR_SETTLE_TIMEOUT_MS, 100) {
            nodes = barNodesOrNull()
            nodes != null
        }
        return nodes ?: throw AssertionError("The bar cannot be read, so nothing can be said about what is on it")
    }

    /** Whether the bar has its sparkle. A tap to find out could land on the mic or commit a word, so this only reads. */
    fun sparkleIsThere(): Boolean = readableBarNodes().any { it.label == SPARKLE_LABEL }

    /**
     * Taps the bar's chip showing [word], and fails if it is not on the bar. The bar's nodes are looked at until they
     * stop changing, because a word offered a moment ago may still be on its way out. Only they are searched, since a
     * key can have the same label. [fallback] is no longer used, and stays so that callers go on compiling.
     */
    @Suppress("UNUSED_PARAMETER")
    fun tapBarWord(word: String, fallback: Slot) {
        Wait.until(BAR_SETTLE_TIMEOUT_MS, 100) { barNodes().any { it.label == word } }
        val nodes = Wait.untilStable(BAR_SETTLE_TIMEOUT_MS, 100) { barNodes() }
        val node = nodes.firstOrNull { it.label == word }
            ?: throw AssertionError("'$word' is not on the bar. The bar offers: ${nodes.map { it.label }}")
        touch.tap(node.rect.exactCenterX(), node.rect.exactCenterY())
    }

    /**
     * Sets the stored answer about whether a model can draft, the one the sparkle follows. Only safe while the process
     * is stopped. The value then lasts for one show: that show works the answer out again, for the next.
     */
    fun seedDraftingAnswer(yes: Boolean) {
        val xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
            "    <boolean name=\"$DRAFTING_READY\" value=\"$yes\" />\n</map>\n"
        shell.sh(
            "run-as ${ime.pkg} sh -c 'mkdir -p shared_prefs; rm -f $BAR_STATE_PATH.bak; cat > $BAR_STATE_PATH' <<'E2E_EOF'\n" +
                "${xml}E2E_EOF\n"
        )
        check(storedDraftingAnswer() == yes) { "Could not store the drafting answer. Read back: ${storedDraftingAnswer()}" }
    }

    /** The stored answer as it is on disk now, or null with none stored. For waiting on it, not for sleeping. */
    fun storedDraftingAnswer(): Boolean? =
        Regex("""name="$DRAFTING_READY" value="(true|false)"""").find(shell.sh("run-as ${ime.pkg} cat $BAR_STATE_PATH 2>/dev/null"))
            ?.groupValues?.get(1)?.toBoolean()

    /** Removes the stored answer. Only safe while the process is stopped. */
    fun forgetDraftingAnswer() {
        shell.sh("run-as ${ime.pkg} rm -f $BAR_STATE_PATH $BAR_STATE_PATH.bak")
    }

    fun tapSparkle(holdMs: Long = Touch.DEFAULT_HOLD_MS) {
        val width = layout().view.right.toFloat()
        val dp = host.onMain { it.resources.displayMetrics.density }
        touch.tap(width - 24 * dp, barCentreY(), holdMs)
    }

    private fun slotCentre(slot: Slot): Pair<Float, Float> {
        val width = layout().view.width().toFloat()
        val fraction = when (slot) {
            Slot.LEFT -> 0.125f
            Slot.CENTRE -> 0.375f
            Slot.RIGHT -> 0.70f
        }
        return (width * fraction) to barCentreY()
    }

    private fun barCentreY(): Float {
        val view = layout().view
        val frameTop = windowState().frame.top
        return (frameTop + view.top) / 2f
    }

    companion object {
        const val SPACE = "Space"
        const val COMMA = "Comma"
        const val PERIOD = "Period"
        const val ENTER = "Enter"
        const val DELETE = "Delete"
        const val SYMBOLS = "Symbols"
        const val LETTERS = "Letters"
        const val SHIFT = "Shift"

        /** About 8 keys a second with the hold time: an ordinary, not a heroic, typing speed. */
        const val TYPING_GAP_MS = 80L

        const val GLIDE_MS_PER_KEY = 170L

        /**
         * Longer than the keyboard may keep treating a reading it took while the finger moved as still current,
         * with room for that reading to arrive late on a busy emulator.
         */
        const val GLIDE_END_REST_MS = 400L

        private const val TAG = "IOKeyE2E"
        private const val MAX_TAPS_ON_A_FIELD = 4
        private const val DOUBLE_TAP_MARGIN_MS = 100L
        private const val BUTTON_CLASS = "android.widget.Button"
        private const val MIN_KEYS_IN_GRID = 10
        private const val BAR_NODES_AT_MOST = 6
        private const val BAR_SETTLE_TIMEOUT_MS = 3_000L
        private const val SPARKLE_LABEL = "Write it for me"
        private const val BAR_STATE_PATH = "shared_prefs/bar_state.xml"
        private const val DRAFTING_READY = "drafting_ready"
        private const val SHOW_TIMEOUT_MS = 30_000L
        private const val SETTLE_TIMEOUT_MS = 6_000L
        private const val TOUCHABLE_INSETS_REGION = "3"
        private const val VIEW_GONE = "mViewVisibility=0x8"
        private const val SYSTEM_BACK_BUTTON_ID = "android:id/input_method_nav_back"
        private const val WINDOW_GONE_TIMEOUT_MS = 6_000L
        private const val AFTER_GONE_MS = 300L

        private val FRAME = Regex("""\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
        private val REQUESTED = Regex("""Requested w=(\d+) h=(\d+)""")
        private val TOUCH_REGION = Regex("""touchableRegion=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)""")
        private val TOUCH_INSETS = Regex("""touchableInsets=(\d+)""")
    }
}
