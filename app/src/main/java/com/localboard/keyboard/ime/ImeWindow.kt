package com.localboard.keyboard.ime

import android.inputmethodservice.InputMethodService.Insets
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.util.BottomInsetPolicy
import com.localboard.keyboard.util.GeometryLog
import com.localboard.keyboard.util.LocalStats

/**
 * The keyboard window: navigation-bar strip, touchable region, refresh rate.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class WindowState {
    /** What the display-level query said for this show; null if it threw or has not run. */
    var displayBottom: Int? = null

    /** The most any dispatch has said this show; null until the first one arrives. */
    var dispatchBottom: Int? = null

    /** The last non-zero answer for the current input view, for a query that throws. */
    var lastGoodBottom: Int? = null

    var healAttempts = 0
    var healPosted = false
    var healAwaitingResult = false

    /** Kept to avoid an allocation on every traversal, which is every drawn frame. */
    val location = IntArray(2)

    // What the geometry log last saw, so that a frame that changed nothing writes nothing.
    var loggedWindow = -1
    var loggedRoot = -1
    var loggedRootWanted = -1
    var loggedStripLayout = -1
    var loggedStripTop = -1
    var loggedStripHeight = -1
}

/**
 * How many times one show may try to repair a short window. A window that stays
 * short after this is taller than the screen allows, which no layout can repair.
 */
private const val MAX_HEALS_PER_SHOW = 2

/**
 * Ask the display for a refresh rate that matches what is actually moving.
 *
 * A 120 Hz panel composites the keyboard window 120 times a second whether
 * or not anything changed. Static typing has nothing to show at that rate —
 * a keypress is one repaint — so the window asks for 60 while typing and
 * the panel's full rate only during a glide, where the trail really does
 * follow the finger frame by frame.
 *
 * Best-effort: `preferredRefreshRate` is a hint, and some OEM compositors
 * ignore it. It costs nothing when ignored.
 */
internal fun LLMKeyboardService.applyRefreshRatePolicy(gliding: Boolean) {
    if (!prefs.throttleRefreshRate) {
        if (requestedHz != 0f) setWindowRefreshRate(0f)
        return
    }
    val target = if (gliding) maxDisplayHz() else 60f
    if (target != requestedHz) setWindowRefreshRate(target)
}

internal fun LLMKeyboardService.maxDisplayHz(): Float = try {
    val display = (getSystemService(android.content.Context.DISPLAY_SERVICE)
        as? android.hardware.display.DisplayManager)
        ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
    display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
} catch (_: Throwable) { 120f }

internal fun LLMKeyboardService.setWindowRefreshRate(hz: Float) {
    try {
        val w = window?.window ?: return
        val lp = w.attributes
        lp.preferredRefreshRate = hz
        w.attributes = lp
        requestedHz = hz
    } catch (_: Throwable) {
        // Never let a display hint break text input.
    }
}

/**
 * Ask the window manager how tall the bottom bars are, for the whole display.
 *
 * This does not depend on the keyboard window's own frame, which is what makes it
 * right on the first show of a new process: that frame can still be empty when the
 * first dispatch is computed against it, and a dispatch then says 0. It ignores
 * visibility, because an app hiding its bars does not remove the gesture area and
 * the system brings the bar back while a keyboard is up.
 *
 * It cannot see the caption bar this window draws for itself, which is why a
 * dispatch may still raise the answer. A failed query is null, not 0, so that
 * [BottomInsetPolicy] can tell "none" from "could not ask".
 *
 * Before API 35 the window's decor keeps this space clear itself and takes it off
 * what it dispatches, so there the strip has nothing to add, and an answer from here
 * would reserve the space twice.
 */
internal fun LLMKeyboardService.displayBottomInset(): Int? {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) return 0
    return try {
        val types = android.view.WindowInsets.Type.navigationBars() or
            android.view.WindowInsets.Type.tappableElement()
        getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(types).bottom
    } catch (_: Throwable) {
        null
    }
}

/**
 * Size the strip behind the navigation bar, from the one answer to how tall it is.
 *
 * Always compares against the strip's own current height, so a freshly
 * inflated strip (0dp from the layout file) is always corrected no matter
 * what the window last reported.
 */
internal fun LLMKeyboardService.applyNavStripHeight() {
    if (!navStripInitialized) return
    val w = windowState
    val mode = prefs.navStripMode
    val target = BottomInsetPolicy.resolve(mode, w.displayBottom, w.dispatchBottom, w.lastGoodBottom)
    if (target > 0) w.lastGoodBottom = target
    val lp = navStrip.layoutParams ?: return
    if (lp.height == target) return
    // Each show forgets what the last one's dispatches said, and this show's first
    // dispatch comes with its first traversal. Dropping to the display's answer until
    // then would only shrink the strip and grow it back, around a measure for nothing,
    // wherever the window keeps a caption bar clear. So only a dispatch can lower it.
    if (target < lp.height && w.dispatchBottom == null && mode != BottomInsetPolicy.MODE_RECLAIM) return
    lp.height = target
    navStrip.layoutParams = lp
    relayoutUpToWindow(navStrip)
}

/**
 * Make a change inside the window reach the window's own height.
 *
 * View.requestLayout stops climbing at the first parent already flagged, and leaves
 * the measure cache of every view above that untouched. The first dispatch of a
 * traversal lands in exactly that state. The next measure at the display's height
 * then reuses the height from before this change, the window is told the old size,
 * and with nothing left pending it stays that way: the keys sit under the navigation
 * bar and the top of the keyboard sits that much too low. Forcing the whole chain
 * clears those caches. Anything that changes the strip's height has to come through here.
 */
private fun relayoutUpToWindow(from: View) {
    var top = from
    var parent = from.parent
    while (parent is View) {
        parent.forceLayout()
        top = parent
        parent = parent.parent
    }
    top.requestLayout()
}

/**
 * Which part of the window accepts touches.
 *
 * P3: with gesture navigation the default insets leave a dead strip under
 * the space row — taps fall into the nav zone and vanish — so the frame is
 * touchable rather than just the content.
 *
 * From API 35 the IME window is laid out edge to edge and extends BEHIND
 * the navigation bar, and a touchable frame then covers the bar's own
 * controls: the chevron that dismisses the keyboard stopped responding,
 * because the keyboard was claiming those touches. So the frame is
 * touchable minus exactly the strip the navigation bar occupies.
 *
 * The strip's own position is the measurement, read live from the view. Its top is
 * the boundary in every state, including a window that came up short and left the
 * strip outside the root, where the root's height minus the strip's would cut the
 * bottom key row out of the touchable region.
 *
 * Runs on every traversal, so it is also where a short window gets noticed.
 */
internal fun LLMKeyboardService.computeTouchableInsets(outInsets: Insets) {
    val root = inputRoot
    if (root == null || !navStripInitialized || root.width == 0) {
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        return
    }
    if (navStrip.height > 0) {
        val at = windowState.location
        root.getLocationInWindow(at)
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(at[0], at[1], at[0] + root.width, at[1] + navStrip.top)
    } else {
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
    }
    logGeometryIfChanged(root)
    healGeometryIfNeeded(root)
}

/** The strip's bottom edge belongs inside the window. If it is below it, the window is short. */
private fun LLMKeyboardService.windowIsShort(root: View): Boolean {
    val decor = root.rootView
    val lp = navStrip.layoutParams ?: return false
    if (decor.height <= 0 || !navStrip.isLaidOut) return false
    val at = windowState.location
    root.getLocationInWindow(at)
    return at[1] + navStrip.top + lp.height > decor.height
}

/**
 * The last line of defence. Seeding the strip and forcing the chain cover the orders of
 * events worked out from the source, but the order is a race at exactly the moment a
 * phone is busiest. This runs at the end of every traversal whatever came before it, so
 * a short window does not outlast the next one.
 *
 * Posted, because a traversal can end short on its way to being right, with the next one
 * already queued. The post lands behind that one, and only what is still short by then
 * gets repaired, so that the count below means a window that really was short.
 */
private fun LLMKeyboardService.healGeometryIfNeeded(root: View) {
    val w = windowState
    if (!windowIsShort(root)) {
        if (w.healAwaitingResult) {
            w.healAwaitingResult = false
            LocalStats.bump(LocalStats.Counter.STRIP_HEALED)
            logGeometry(GeometryLog.Event.HEALED)
        }
        return
    }
    if (w.healPosted || w.healAttempts >= MAX_HEALS_PER_SHOW) return
    w.healPosted = true
    root.post {
        w.healPosted = false
        if (root !== inputRoot || !windowIsShort(root)) return@post
        w.healAttempts++
        w.healAwaitingResult = true
        logGeometry(GeometryLog.Event.HEAL)
        relayoutUpToWindow(navStrip)
    }
}

internal fun LLMKeyboardService.wireWindowInsets(root: View) {
    // The service outlives its input view. What it knew about the old one's window can
    // be wrong for this one: a rotation or a resolution change is what re-creates it.
    val state = windowState
    state.displayBottom = null
    state.dispatchBottom = null
    state.lastGoodBottom = null
    state.healAttempts = 0
    state.healPosted = false
    state.healAwaitingResult = false
    resetEmojiViewState()

    // API 35+ draws this window edge to edge, so without this the bottom
    // key row sits underneath the navigation bar. Before 35 the system
    // reserved that space for us; giving the strip the same height restores
    // the old geometry rather than changing it.
    //
    // It is a real view rather than padding on the root because the strip
    // has to be PAINTED — an empty gap in a transparent window shows the
    // app underneath, which reads as a hole in the keyboard. Padding could
    // only be filled by giving the root a background, and that would
    // repaint the entire keyboard area underneath the keys for the sake of
    // a 24dp strip.
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val navType = androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        // The stable height whether or not the bar is showing right now. A
        // bar that is mid-animation, or hidden for a moment by the app on
        // screen, is still there to keep clear of.
        val nav = insets.getInsetsIgnoringVisibility(navType)
        val cutout = insets.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        )
        // Landscape moves both the navigation bar and the camera cutout to
        // the SIDES on many devices, where a bottom-only inset does nothing
        // and the outer key columns end up under them. Take whichever
        // intrudes further on each edge.
        val left = maxOf(nav.left, cutout.left)
        val right = maxOf(nav.right, cutout.right)
        if (v.paddingLeft != left || v.paddingRight != right) {
            v.setPadding(left, v.paddingTop, right, v.paddingBottom)
        }
        // Stable or not, these are computed against the window's own frame, so a
        // dispatch on a frame that is still empty says 0 for the navigation bar and
        // is not news. It can only raise the answer, as the caption bar does: that is
        // the back and switcher buttons, where the system draws them inside this
        // window, standing taller than the navigation bar itself.
        val bars = navType or
            androidx.core.view.WindowInsetsCompat.Type.captionBar() or
            androidx.core.view.WindowInsetsCompat.Type.tappableElement()
        state.dispatchBottom = maxOf(state.dispatchBottom ?: 0, insets.getInsetsIgnoringVisibility(bars).bottom)
        applyNavStripHeight()
        logGeometry(GeometryLog.Event.DISPATCH)
        insets
    }
    // requestApplyInsets on a DETACHED view is a no-op: its whole body is
    // `if (mParent != null) mParent.requestFitSystemWindows()`, and
    // InputMethodService does not attach the input view until after this
    // method returns. Asking once the view is actually in the window is
    // the difference between requesting insets and only appearing to.
    root.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                androidx.core.view.ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        }
    )
    if (root.isAttachedToWindow) androidx.core.view.ViewCompat.requestApplyInsets(root)
    logGeometry(GeometryLog.Event.NEW_VIEW)
}

internal fun LLMKeyboardService.onShowRequestInsets(info: EditorInfo, restarting: Boolean) {
    // Before anything measures. The first traversal of a show measures first and only
    // then dispatches, so a strip that is right by now is right from the first measure,
    // with no resize for the user to see. A restart is the same show, so what it knows stands.
    val state = windowState
    if (!restarting || state.displayBottom == null) state.displayBottom = displayBottomInset()
    if (!restarting) state.dispatchBottom = null
    state.healAttempts = 0
    state.healAwaitingResult = false
    applyNavStripHeight()
    logGeometry(GeometryLog.Event.SHOW)
    // Re-read the insets on every show. InputMethodService CACHES the
    // input view, so a hide/show does not re-create it and does not by
    // itself guarantee another dispatch. The display cannot see a caption
    // bar, and this is what brings that answer back after the line above
    // forgot it.
    inputRoot?.let { r ->
        if (r.isAttachedToWindow) androidx.core.view.ViewCompat.requestApplyInsets(r)
    }
}

/**
 * Note a measurement, for the diagnostics screen and the system log. Numbers only:
 * this is how the window's real behaviour on a phone gets seen.
 */
internal fun LLMKeyboardService.logGeometry(event: GeometryLog.Event) {
    val root = inputRoot ?: return
    if (!navStripInitialized) return
    val state = windowState
    val insets = root.rootWindowInsets
    fun bottom(type: Int) = insets?.getInsetsIgnoringVisibility(type)?.bottom ?: GeometryLog.UNKNOWN
    val geometry = GeometryLog.Geometry(
        window = root.rootView.height,
        root = root.height,
        rootWanted = root.measuredHeight,
        rootTooSmall = root.measuredHeightAndState and View.MEASURED_STATE_TOO_SMALL != 0,
        stripLayout = navStrip.layoutParams?.height ?: GeometryLog.UNKNOWN,
        stripTop = navStrip.top,
        stripHeight = navStrip.height,
        navigationBar = bottom(android.view.WindowInsets.Type.navigationBars()),
        captionBar = bottom(android.view.WindowInsets.Type.captionBar()),
        tappable = bottom(android.view.WindowInsets.Type.tappableElement()),
        gestures = bottom(android.view.WindowInsets.Type.mandatorySystemGestures()),
        display = state.displayBottom ?: GeometryLog.UNKNOWN,
        dispatch = state.dispatchBottom ?: GeometryLog.UNKNOWN,
        lastGood = state.lastGoodBottom ?: GeometryLog.UNKNOWN
    )
    GeometryLog.record(event, geometry)
    android.util.Log.i("IOKeyInsets", GeometryLog.describe(event, geometry))
}

/** Compares plain numbers first, because this runs for every frame the keyboard draws. */
private fun LLMKeyboardService.logGeometryIfChanged(root: View) {
    val s = windowState
    val window = root.rootView.height
    val stripLayout = navStrip.layoutParams?.height ?: GeometryLog.UNKNOWN
    if (window == s.loggedWindow && root.height == s.loggedRoot &&
        root.measuredHeight == s.loggedRootWanted && stripLayout == s.loggedStripLayout &&
        navStrip.top == s.loggedStripTop && navStrip.height == s.loggedStripHeight
    ) {
        return
    }
    s.loggedWindow = window
    s.loggedRoot = root.height
    s.loggedRootWanted = root.measuredHeight
    s.loggedStripLayout = stripLayout
    s.loggedStripTop = navStrip.top
    s.loggedStripHeight = navStrip.height
    logGeometry(GeometryLog.Event.LAYOUT)
}

/**
 * Fill the strip behind the navigation bar.
 *
 * Shares the animated background's Pro gate and low-power override rather
 * than having its own: they are the same cost in the same place, and a
 * "decoration off" state that leaves one animation running would be a
 * strange thing to explain. Falling back to MATCH rather than to nothing
 * matters — nothing means a see-through hole under the keys, which is the
 * defect this whole strip exists to fix, so it must never be the fallback.
 */
internal fun LLMKeyboardService.applyNavStrip(lowPower: Boolean, bgAllowed: Boolean) {
    if (!navStripInitialized) return
    // A mode left from an older version or a restored backup can still take the
    // strip's height away, so this has to run whenever prefs are re-read, not only
    // on inset changes.
    applyNavStripHeight()
    val t = theme
    val bg = t?.background ?: android.graphics.Color.BLACK
    navStrip.matchColor = bg
    navStrip.fillColor = prefs.navStripColor
    val wantsAnimation = prefs.navStripMode == 2 && !lowPower && bgAllowed
    navStrip.mode = when {
        wantsAnimation -> com.localboard.keyboard.ui.NavStripView.Mode.ANIMATED
        prefs.navStripMode == 1 -> com.localboard.keyboard.ui.NavStripView.Mode.SOLID
        else -> com.localboard.keyboard.ui.NavStripView.Mode.MATCH
    }
    if (wantsAnimation) {
        navStrip.style =
            com.localboard.keyboard.ui.LiveBackground.Style.fromId(prefs.navStripStyle)
        navStrip.setAnimationTuning(
            prefs.liveBgFps, prefs.liveBgIntensity, prefs.liveBgWhileTyping
        )
        if (t != null) {
            navStrip.setAnimationColors(t.background, t.accent, t.trail, t.keyFunctional)
        }
    }
}
