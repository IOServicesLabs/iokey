package com.localboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.localboard.keyboard.R
import com.localboard.keyboard.engine.DwellMask
import com.localboard.keyboard.engine.HitKey
import com.localboard.keyboard.engine.HitMap
import com.localboard.keyboard.engine.KeyLayoutMath
import com.localboard.keyboard.engine.KeyboardGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fully custom-rendered keyboard (no deprecated framework KeyboardView).
 * Handles: key rendering, press previews, long-press alternates with drag
 * selection, glide-typing path capture with a fading trail, delete
 * auto-repeat, and shift state. Emits semantic events via [KeyboardListener].
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface KeyboardListener {
        fun onText(text: String)
        fun onKey(type: KeyType)
        /** Returns true when the path decoded into a word (false = treat as a tap). */
        fun onSwipePath(points: List<PointF>): Boolean
        fun onSwipeStarted()
        fun onSwipeEnded()
        /** Long-press backspace acceleration: delete the previous whole word. */
        fun onWordDelete() {}
        /** Long-press on the sparkle action slot: open the quick-prompt box. */
        fun onWifmLongPressed() {}
        /** Mid-glide preview: current best candidates for the partial path. */
        fun onSwipeProgress(points: List<PointF>) {}
        /** Finger rose above the keyboard into the suggestion bar mid-glide;
         *  [xFraction] is the finger's horizontal position, 0..1 across the bar. */
        fun onSwipeBarHover(xFraction: Float) {}
        /** Finger lifted while over the suggestion bar. Return true when a bar
         *  word was committed; false falls back to decoding the frozen path. */
        fun onSwipeBarPick(xFraction: Float): Boolean = false
        /** Long-press on the space bar: the user wants the IME switcher. */
        fun onSpaceLongPress() {}
        /** Long-press on Enter: perform the field's editor action (send,
         *  search, go) even where a tap now types a newline instead. */
        fun onEnterLongPress() {}
        /** Finger sliding along the space bar: scrub the editor cursor
         *  [delta] characters (negative = left, positive = right). */
        fun onSpaceCursorMove(delta: Int) {}
    }

    enum class ShiftState { OFF, ON, CAPS_LOCK }

    var listener: KeyboardListener? = null
    var swipeEnabled = true
    /** Gliding would be on but this field does not allow it. Long presses still go by the
     *  rule for a finger that moves, or a drag could open a popup and type its character. */
    var glideBlockedHere = false
    var hapticEnabled = true
    var soundEnabled = false

    /** Customizable per-key feedback (vibration level, sound pack, volume).
     *  The legacy hapticEnabled/soundEnabled flags above are kept as a cheap
     *  master gate — when both are false, feedback() skips everything. */
    val keyFeedback = KeyFeedback(context)

    /** Animation level: 0=off, 1=reduced, 2=full. Drives the press effect
     *  and the glide-trail fade speed. */
    var animLevel = 2
        set(v) { field = v.coerceIn(0, 2); trail?.fadeSpeed = trailFadeSpeed(field) }

    /** Trail fade speed for an animation level: 0=off-feel (near-instant
     *  fade), 1=reduced, 2=full. Shared by the animLevel setter and setTheme,
     *  which builds a fresh trail and must not drop the preference. */
    private fun trailFadeSpeed(level: Int): Float = when (level) { 0 -> 2f; 1 -> 1.4f; else -> 1f }

    /** Key press effect: 0=Lift (grow+accent), 1=Bounce (bigger grow),
     *  2=Glow (accent halo, no motion), 3=None (static shade). animLevel==0
     *  forces the static shade regardless. */
    var pressEffect = 0
        set(v) { field = v.coerceIn(0, 3); invalidate() }

    /** Effect actually used this frame — animation level 0 kills motion. */
    private val effectivePressEffect: Int
        get() = if (animLevel == 0) 3 else pressEffect

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            // Shift changes letter legends and the indicator ring, so the
            // cached grid is stale — but ONLY on the letters page. The symbols
            // pages have no shift key and no case to change, so rebuilding
            // there would throw away a good cache for nothing.
            if (model.isLetters) markLayersDirty() else invalidate()
        }

    /** Show the press-preview bubble above tapped character keys. */
    var previewEnabled = true

    /** Fired on each key press, for decoration that lives outside this view
     *  (the navigation-bar strip) and needs to react to typing. */
    var onKeyPressed: ((KeyType) -> Unit)? = null

    /**
     * Identity of the layout currently applied to THIS view, so the service can
     * skip re-attaching an identical grid on every field focus.
     *
     * It lives on the view rather than on the service on purpose. The service
     * outlives its input view — Android destroys and recreates the input view
     * on any configuration change (rotation, day/night, density, an app going
     * fullscreen and back) — so a cache held on the service describes a view
     * that no longer exists. When it did, the identity still matched, the
     * service skipped setKeyboard(), and the brand-new view was left showing
     * the default layout from its own field initialiser instead of the user's.
     * A fresh view has a null key here and therefore always gets configured.
     */
    var appliedModelKey: String? = null

    private var model: KeyboardModel = KeyboardLayouts.qwerty()
    // Cheap static placeholder: loading the real theme here ran
    // SharedPreferences I/O on the main thread at construction, and the
    // service always replaces it via setTheme(loadCached) before the view is
    // shown. The init block below only reads .background, which is a plain
    // colour on the fallback.
    private var theme: KeyboardTheme = FALLBACK_THEME

    val geometry = KeyboardGeometry()

    // What the layout math takes from the settings. Where every key goes is
    // arithmetic on these and the view's width, so gaps and heights keep no copy
    // of their own here to drift from it.
    private var heightScale = 1f
    private var formFactor = 1f
    // Gaps are user-tunable: tighter gaps mean bigger key faces at the same
    // keyboard height, which is the cheapest possible fix for "cramped".
    private var keySpacing = 1f
    private var textScale = 1f
    private var cornerRadius = dp(8).toFloat()
    private val keyRects = ArrayList<KeyRect>()

    /** Who owns each point of the grid. Its keys are in the same order as [keyRects],
     *  because both are built by the same loop. */
    private var hitMap: HitMap? = null

    // ── One-handed mode ──────────────────────────────────────────────────
    // On a phone the size of an S22 Ultra the far edge of the keyboard is
    // genuinely out of thumb reach. One-handed mode narrows the key grid and
    // docks it to one side, leaving a gutter on the other. The grid itself is
    // unchanged — only the usable width and its origin move — so touch
    // targets, swipe geometry and the spatial corrector all stay consistent.

    /** 0 = full width, 1 = docked right, 2 = docked left. */
    private var oneHandedMode = 0
    private var oneHandedWidth = 0.82f

    /** The tallest this board may be and still fit the screen with everything around it.
     *  Applied by the next layout: setting it moves nothing by itself. */
    var maxBoardHeightPx = Int.MAX_VALUE

    fun setOneHanded(mode: Int, widthFraction: Float) {
        val m = mode.coerceIn(0, 2)
        val w = widthFraction.coerceIn(0.6f, 1f)
        if (m == oneHandedMode && w == oneHandedWidth) return
        oneHandedMode = m
        oneHandedWidth = w
        if (width > 0) computeKeyRects()
        invalidate()
    }

    fun isOneHanded(): Boolean = oneHandedMode != 0

    /** Flip the dock side; no-op when one-handed is off. */
    fun toggleOneHandedSide() {
        if (oneHandedMode == 0) return
        setOneHanded(if (oneHandedMode == 1) 2 else 1, oneHandedWidth)
    }

    /** Scales the gap between keys. 1.0 = stock 4.5dp; lower = bigger keys. */
    fun setKeySpacing(scale: Float) {
        val s = scale.coerceIn(0.4f, 1.8f)
        if (s == keySpacing) return
        keySpacing = s
        if (width > 0) computeKeyRects()
        requestLayout()
        invalidate()
    }

    /** Scales key height and label text. 1.0 = default. */
    fun setMetrics(heightScale: Float, textScale: Float) {
        val cfg = resources.configuration
        formFactor = KeyLayoutMath.formFactor(
            landscape = cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            smallestWidthDp = cfg.smallestScreenWidthDp,
            fontScale = cfg.fontScale
        )
        this.heightScale = heightScale
        this.textScale = textScale
        if (width > 0) computeKeyRects()
        requestLayout()
        invalidate()
    }

    private data class KeyRect(val key: Key, val rect: RectF)

    // Paints
    private val iconCache = HashMap<Int, Drawable>()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bgPaint = Paint()

    // Touch state
    private var pressedKey: KeyRect? = null
    private var isSwiping = false
    /** Finger left the key grid upward into the suggestion bar mid-glide:
     *  the glide freezes and the bar candidates become drag-selectable. */
    private var barSelectMode = false
    /** The event time of the finger's latest report during a glide. A still finger sends few moves, so its time
     *  on a word of the bar can only be read from the report that brought it there and the one at its lift. */
    var barTouchAt = 0L
        private set
    /** P2: the current touch is scrubbing the cursor along the space bar —
     *  suppress the space dispatch on finger-up. */
    private var spaceCursorMode = false
    private var spaceCursorLastX = 0f
    /** The only pointer we track; extra fingers never move our coordinates. */
    private var activePointerId = -1
    /** The latest finger to land during a glide and be ignored, where and when, for that
     *  glide's lift to judge. -1 when there is none. */
    private var lateFingerId = -1
    private var lateFingerX = 0f
    private var lateFingerY = 0f
    private var lateFingerAt = 0L
    private var lastSwipeProgressAt = 0L
    /** Raw points from finger-down, so a glide that arms late still starts at
     *  the true gesture origin instead of mid-word. */
    private val pendingPoints = ArrayList<PointF>(40)
    private var pressAnimStart = 0L
    private var releaseAnimKey: KeyRect? = null
    private var releaseAnimStart = 0L
    private var trailEnabled = true
    private var swipeActivationScale = 1.0f
    private var longPressMs = 380L

    /** User-tunable glide arming distance (multiplies the base threshold). */
    fun setSwipeActivationScale(f: Float) { swipeActivationScale = f.coerceIn(0.6f, 1.4f) }
    fun setTrailEnabled(b: Boolean) { trailEnabled = b; invalidate() }
    fun setLongPressMs(ms: Int) { longPressMs = ms.coerceIn(250, 600).toLong() }

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastTapCommitAt = 0L
    /** Twice the touch slop, the floor [swipeActivation] already keeps: past this a held
     *  key is on its way somewhere, so its long press is off. */
    private val longPressCancelPx: Float by lazy { ViewConfiguration.get(context).scaledTouchSlop * 2f }
    // Swipe-vs-tap discrimination: fast typists drift between taps, so the
    // glide threshold is deliberately high; shorter gestures always decode
    // back to a plain tap of the starting key (no more eaten letters).
    /** Distance the finger must travel before a glide may start — deliberately
     *  near one full key width so fast tap-typing is never read as a swipe. */
    private val swipeActivation: Float
        get() {
            // The MEASURED key width, not width/10. Ten keys is the top QWERTY
            // row only, and in one-handed mode the grid is narrower than the
            // view — so width/10 overstated the arming distance by up to a
            // fifth in exactly the mode chosen because reach is already hard.
            val measured = geometry.averageKeyWidth
            val keyW = when {
                measured > 0f -> measured
                width > 0 -> width / 10f
                else -> dp(44).toFloat()
            }
            // The floor scales with the setting too. It used to be fixed at
            // 3x touch slop, which on a typical phone sat above the hair-
            // trigger end of the range — so the bottom third of the glide
            // sensitivity slider was one value, and the first thing anyone
            // tries when glide typing stops recognising their words (making it
            // arm sooner) did nothing at all.
            val slop = ViewConfiguration.get(context).scaledTouchSlop *
                2.0f * swipeActivationScale
            // 0.7 of a key (was 0.85): within ONE touch a tap-typing finger
            // rarely slides more than half a key, while a short glide such
            // as "as" or "we" is barely more than one. The decoder's
            // language prior now resolves those short gestures reliably, so
            // the arming distance no longer has to protect it.
            return max(slop, keyW * 0.7f * swipeActivationScale)
        }
    private val swipePoints = ArrayList<PointF>(64)
    private var swipePathLen = 0f
    private var trail: GestureTrailRenderer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var deleteRepeatCount = 0

    // Long-press popup
    private var popupWindow: PopupWindow? = null
    private var popupLabels: List<TextView> = emptyList()
    /** What the popup currently offers, tile order — item 0 is the key's own
     *  label followed by the key's alternate characters. Kept separately from
     *  [KeyRect.key.popup] because the displayed list has the plain letter
     *  prepended. */
    private var popupItems: List<String> = emptyList()
    private var popupSelected = -1

    /** The finger has left the popup's first item on purpose. A second finger landing
     *  before that means the popup was a slow tap, so the key itself is what gets typed. */
    private var popupFingerMoved = false

    /** Scratch for getLocationOnScreen — reused; one allocation per MOVE
     *  event during popup drag was coming from these lookups. */
    private val locScratch = IntArray(2)

    /** Second scratch so showPopup can hold the view's and the root's
     *  screen positions at the same time. */
    private val rootLocScratch = IntArray(2)

    /** Raw screen x/y where the long-press opened, or -1 when no popup is
     *  up. The selection stays on item 0 until the finger moves past
     *  [popupStickyPx] from here. */
    private var popupOriginX = -1f
    private var popupOriginY = -1f

    /** Half a key width: far enough that resting-finger jitter never changes
     *  the default, close enough that a deliberate slide still selects. */
    private val popupStickyPx: Float
        get() = max(dp(18).toFloat(), geometry.averageKeyWidth * 0.5f)
    private var longPressFired = false

    /** Posted on the main thread when the key rects have changed, coalesced so a settings
     *  change that moves them twice reports the last rects only. Requesting layout from
     *  here is not needed and nothing should. */
    var onGeometryReady: (() -> Unit)? = null
    private var notifiedRectsHash = 0
    private val geometryReadyTick = Runnable { onGeometryReady?.invoke() }

    /** When the long press under way falls due, and whether a late one has gone to wait for a frame. */
    private var longPressDueAt = 0L
    private var longPressAwaitingFrame = false

    /**
     * A stall on this thread can leave moves queued behind a timer that is by then overdue, and moves are only read at the
     * next frame. They may be the finger leaving the key, so a late timer looks again once they have been read. It stays
     * queued meanwhile, so that a move takes it off as it takes off any long press, and it still acts if no frame comes.
     */
    private val longPressRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!longPressAwaitingFrame && SystemClock.uptimeMillis() - longPressDueAt >= LONG_PRESS_LATE_MS) {
                longPressAwaitingFrame = true
                handler.postDelayed(this, LONG_PRESS_FRAME_WAIT_MS)
                postOnAnimation(longPressAfterFrame)
            } else {
                fireLongPress()
            }
        }
    }

    /** Runs after that frame's input, so a finger that really stayed still gets its popup here. */
    private val longPressAfterFrame = Runnable {
        if (handler.hasCallbacks(longPressRunnable)) {
            handler.removeCallbacks(longPressRunnable)
            fireLongPress()
        }
    }

    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            // A lift this view never hears, because it was taken off the window or the press
            // was cancelled, must not leave the repeat eating text nobody can see.
            if (pressedKey?.key?.type != KeyType.DELETE || !isAttachedToWindow) return
            deleteRepeatCount++
            if (deleteRepeatCount <= 20) {
                listener?.onKey(KeyType.DELETE)
                handler.postDelayed(this, 50)
            } else {
                // Held long: accelerate into whole-word deletion, slowly enough that a
                // word can be seen going and the finger can stop on the right one.
                listener?.onWordDelete()
                handler.postDelayed(this, 180)
            }
        }
    }

    init {
        setBackgroundColor(theme.background)
    }

    /** Screen-px tap point of the most recent letter dispatch. The IME
     *  consumes (and nulls) it while building its parallel tap trace. */
    @Volatile
    var lastCharTapPoint: PointF? = null

    /**
     * True while the letters page is up. The symbols pages, the number pad
     * and the selection pad all read false — the chrome row above the keys
     * swaps to toolbar icons off the letters page, and reads this instead of
     * keeping a page state of its own.
     */
    val isLettersPage: Boolean
        get() = model.isLetters

    /** Fired after [setKeyboard] applies a new model, so the chrome row can
     *  re-read [isLettersPage] without polling. One subscriber at a time is
     *  all the layout ever has; a new chrome row detaches the old callback. */
    var onModelApplied: (() -> Unit)? = null

    fun setKeyboard(model: KeyboardModel) {
        // The view stays attached across a page change, so nothing else ends the touch. A
        // field restart can change the page under a finger, whose lift would type a key that
        // has gone. The two touch paths that change the page themselves keep what they need.
        cancelTouch()
        releaseAnimKey = null
        // Capture the outgoing page BEFORE the swap so the content area can
        // crossfade into the new one (see [PageTransition]). No-op when there
        // is nothing rendered yet or when motion is off.
        beginPageTransition()
        this.model = model
        // BUGFIX: when the new model has the same row count as the old one,
        // onSizeChanged never fires and key rects were never recomputed —
        // the symbols pages looked "dead". Recompute explicitly.
        // computeKeyRects() already marks the layers dirty; when the view has
        // no size yet there is no layer to invalidate.
        if (width > 0 && height > 0) computeKeyRects() else invalidate()
        requestLayout()
        onModelApplied?.invoke()
    }

    fun setTheme(theme: KeyboardTheme) {
        this.theme = theme
        cornerRadius = dp(theme.keyRadiusDp).toFloat()
        // A fresh trail starts at fadeSpeed 1f; re-apply the animation-level
        // preference or a theme change silently resets it.
        trail = GestureTrailRenderer(theme.trail).also { it.fadeSpeed = trailFadeSpeed(animLevel) }
        setBackgroundColor(theme.background)
        // Resolved once per theme change, never per frame: Typeface.create
        // hits the font cache and is far too costly for the draw path.
        textPaint.typeface = theme.typeface()
        markLayersDirty()
    }

    fun currentModel(): KeyboardModel = model

    /** System animator duration scale — the "remove animations" accessibility
     *  setting. 0 (or unreadable) means motion is off: every animation this
     *  view runs degrades to an instant cut. Read at each animation's start
     *  (a page change, a popup), never per frame. */
    private fun animatorScale(): Float {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                android.provider.Settings.Global.getFloat(
                    context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
                )
            } else 1f
        } catch (_: Throwable) { 1f }
    }

    /** Snapshot the outgoing page (background + resting grid, straight from
     *  the cached layers) so [setKeyboard]'s swap can crossfade. Skipped —
     *  leaving an instant cut — when nothing is rendered yet (the very first
     *  layout: there is no old frame, and the first swap stays free of extra
     *  work), when the user's animation level is off, or when the system
     *  animator scale is 0. */
    private fun beginPageTransition() {
        endPageTransition()
        if (animLevel == 0) return
        val durationMs = (PAGE_TRANSITION_MS * animatorScale()).toLong()
        if (durationMs <= 0L) return
        if (width <= 0 || height <= 0 || keyRects.isEmpty()) return
        val bg = bgLayer
        val keys = keysLayer
        val bgUsable = bg != null && !bg.isRecycled && bg.width == width && bg.height == height
        val keysUsable = keys != null && !keys.isRecycled && keys.width == width && keys.height == height
        // A flat theme colour with no layers built yet has nothing worth
        // capturing; the new page appears instantly, as before this pass.
        if (!bgUsable && !keysUsable && wallpaper == null) return
        val bmp = try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { return }
        val c = Canvas(bmp)
        if (bgUsable) c.drawBitmap(bg, 0f, 0f, null)
        else {
            // No usable background layer (live background, or a size change
            // in flight): a flat fill keeps the old page legible enough to
            // dissolve from.
            c.drawColor(theme.background)
        }
        if (keysUsable) c.drawBitmap(keys, 0f, 0f, null)
        else for (kr in keyRects) drawKey(c, kr)
        pageTransition = PageTransition(bmp, SystemClock.uptimeMillis(), durationMs)
    }

    private fun endPageTransition() {
        val t = pageTransition ?: return
        pageTransition = null
        if (!t.snapshot.isRecycled) t.snapshot.recycle()
    }

    /** Crossfade progress 0..1 with a fast-out ease; 1 means finished. */
    private fun pageTransitionAlpha(t: PageTransition): Float {
        val raw = (SystemClock.uptimeMillis() - t.startAtMs).toFloat() / t.durationMs
        if (raw >= 1f) return 1f
        val clamped = raw.coerceIn(0f, 1f)
        return 1f - (1f - clamped) * (1f - clamped)
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, KeyLayoutMath.heightPx(model.rows.size, boardMetrics()))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeKeyRects()   // marks the layers dirty itself
    }


    private fun layoutMetrics() = KeyLayoutMath.metrics(
        density = resources.displayMetrics.density,
        viewWidthPx = width,
        heightScale = heightScale,
        formFactor = formFactor,
        keySpacing = keySpacing,
        oneHandedMode = oneHandedMode,
        oneHandedWidth = oneHandedWidth
    )

    private fun boardMetrics() =
        KeyLayoutMath.capped(layoutMetrics(), model.rows.size, maxBoardHeightPx, dp(28).toFloat())

    /**
     * Keys are drawn where the layout math says, and nothing learned about the user's
     * touch is an input to it. That is the whole promise of a keyboard whose keys stay
     * put: a key that moves under a finger that has adapted to it is a new source of
     * typos, and no amount of typing or testing should be able to do that.
     */
    private fun computeKeyRects() {
        keyRects.clear()
        geometry.clear()
        keyTouchHelper.invalidateRoot()
        val metrics = boardMetrics()
        val boxes = KeyLayoutMath.layout(
            model.rows.map { row ->
                KeyLayoutMath.Row(FloatArray(row.keys.size) { row.keys[it].weight }, row.edgePadding)
            },
            metrics
        )
        val hitRows = model.rows.mapIndexed { r, row ->
            row.keys.mapIndexed { i, key ->
                val box = boxes[r][i]
                val rect = RectF(box.left, box.top, box.right, box.bottom)
                keyRects += KeyRect(key, rect)

                // Register letters with the geometry for swipe decoding.
                if (key.type == KeyType.CHAR && key.output.length == 1 &&
                    key.output[0].isLetter()
                ) {
                    geometry.register(key.output[0].lowercaseChar(), rect)
                }
                HitKey(
                    HitKey.symbolFor(key.type == KeyType.SPACE, key.type == KeyType.CHAR, key.output),
                    box.left, box.top, box.right, box.bottom
                )
            }
        }
        // Side padding at a screen edge belongs to the edge keys, so a tap beside the grid
        // there still types: both sides at full width, the docked side one-handed. Only the
        // side facing the gutter stops at the grid, because past it there is nothing to press.
        hitMap = HitMap(
            hitRows,
            if (oneHandedMode == 1) metrics.originX else 0f,
            if (oneHandedMode == 2) metrics.originX + metrics.usableWidth else width.toFloat()
        )
        geometry.finishLayout()
        // Any geometry change invalidates the cached grid. Doing this here
        // rather than at each call site means a new setter that moves keys
        // can't silently leave a stale bitmap on screen — which is exactly
        // the failure mode a render cache invites.
        markLayersDirty()
        // Folded from the boxes themselves, because a page switch can land on the same
        // letter layout version with other rects. Posted rather than called: one settings
        // change runs this several times on in-between rects, and this function can run
        // inside onSizeChanged, where the callback must not reach layout.
        var rectsHash = 17
        for (row in boxes) {
            for (box in row) {
                rectsHash = 31 * rectsHash + box.left.toBits()
                rectsHash = 31 * rectsHash + box.top.toBits()
                rectsHash = 31 * rectsHash + box.right.toBits()
                rectsHash = 31 * rectsHash + box.bottom.toBits()
            }
        }
        if (rectsHash != notifiedRectsHash) {
            notifiedRectsHash = rectsHash
            handler.removeCallbacks(geometryReadyTick)
            handler.post(geometryReadyTick)
        }
    }

    // ---------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------

    // --- Wallpaper / live background ---

    private var wallpaper: android.graphics.Bitmap? = null
    private var wallpaperDim = 0.45f

    /** The animated background. Style NONE means "flat theme colour". */
    val liveBg = LiveBackground()

    // ── Cached render layers ─────────────────────────────────────────────
    //
    // Two bitmaps replace what used to be recomputed on every single frame:
    //
    //  bgLayer   — the static background, wallpaper crop and scrim already
    //              composited. The old code allocated a Matrix and re-scaled
    //              the source bitmap on EVERY draw, so a keystroke paid for a
    //              filtered image resample.
    //  keysLayer — all ~40 keys in their resting state. A keypress changes one
    //              key; repainting the whole grid to show it was the single
    //              biggest waste in the draw path.
    //
    // Per frame the cost is now: one background blit, one keys blit, and one
    // live key draw. Both layers are rebuilt only when the layout, theme,
    // shift state or size actually changes — see [markLayersDirty].
    private var bgLayer: android.graphics.Bitmap? = null
    private var keysLayer: android.graphics.Bitmap? = null
    private var layerW = 0
    private var layerH = 0
    private val layerPaint = android.graphics.Paint()

    /** Set while rendering into [keysLayer]: suppresses press/animation state
     *  so the cached grid is always the resting appearance. */
    private var buildingLayer = false

    /** Scratch for clip queries on the draw path — never allocate per frame. */
    private val clipScratch = android.graphics.Rect()

    /** Drives the press-effect animation, one key's dirty rect at a time. */
    private val pressAnimTick = Runnable { invalidateKeyArea(liveKey()) }

    /** Drives the trail fade, repainting only where the trail is. */
    private val trailTick = Runnable { invalidateTrailArea() }

    // ── Page-change transition ───────────────────────────────────────────
    //
    // Gboard crossfades the key grid when the page changes (?123, ABC, the
    // number pad). The window still resizes exactly once — only the content
    // crossfades: the previous page's pixels (captured from the cached
    // layers before the model swap) sit underneath while the new content
    // fades in on a layer. The whole thing runs inside onDraw on posted
    // invalidates, so no part of it ever lands between a touch and its
    // commit.
    private class PageTransition(
        val snapshot: android.graphics.Bitmap,
        val startAtMs: Long,
        val durationMs: Long,
    )

    private var pageTransition: PageTransition? = null

    /** Reused tick + alpha paint: the transition runs ~9 frames and must not
     *  allocate per frame. */
    private val pageTransitionTick = Runnable {
        if (pageTransition != null) invalidate()
    }
    private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trailBounds = RectF()
    private val trailPrevBounds = RectF()
    private var trailPrevValid = false

    /**
     * The decoder's current best word for the glide in progress, drawn in a
     * bubble that rides just above the fingertip (Gboard's floating
     * preview). The eye is on the finger during a glide, not on the
     * suggestion bar: with the word forming under the finger the user lifts
     * the moment it reads right, instead of finishing the shape, looking up
     * to check, and only then moving on — the "pause after every swipe".
     * Set by the IME from its live preview decode; cleared here when the
     * gesture ends.
     */
    private var glideWord: String? = null
    private val glideBubbleBounds = RectF()
    private val glideBubblePrev = RectF()
    private var glideBubblePrevValid = false

    fun setGlideWord(text: String?) {
        if (text == glideWord) return
        if (!isSwiping && text != null) return   // gesture already over
        glideWord = text
        invalidateTrailArea()
    }

    /** Bubble rect for [glideWord] at the current fingertip, or false. */
    private fun glideBubbleRect(out: RectF): Boolean {
        val w = glideWord ?: return false
        if (!isSwiping || barSelectMode || w.isEmpty()) return false
        previewTextPaint.textSize = sp(20) * textScale
        val tw = previewTextPaint.measureText(w)
        val padX = dp(14).toFloat()
        val bw = tw + padX * 2
        val bh = dp(40).toFloat()
        var left = lastSampleX - bw / 2
        left = min(max(left, dp(4).toFloat()), width - bw - dp(4))
        // Above the fingertip so the thumb never covers it; on the top row
        // there is no room above (the bar is a different view), so flip
        // below — the finger is on the top row, the bubble on the second.
        var top = lastSampleY - dp(58) - bh
        if (top < dp(2)) top = lastSampleY + dp(44)
        out.set(left, top, left + bw, top + bh)
        return true
    }

    private fun drawGlideBubble(canvas: Canvas) {
        val w = glideWord ?: return
        val r = glideBubbleBounds
        if (!glideBubbleRect(r)) return
        previewPaint.color = theme.popupBackground
        canvas.drawRoundRect(r, dp(10).toFloat(), dp(10).toFloat(), previewPaint)
        previewTextPaint.color = theme.text
        val baseline = r.centerY() - (previewTextPaint.descent() + previewTextPaint.ascent()) / 2
        canvas.drawText(w, r.centerX(), baseline, previewTextPaint)
    }

    /**
     * Invalidate the trail's region: the current bounding box unioned with the
     * previous frame's.
     *
     * The union is load-bearing. The trail's tail expires from behind, so the
     * pixels that need clearing this frame are the ones that were covered LAST
     * frame and are not covered now. Invalidating only the current box would
     * leave the faded tail painted on screen.
     */
    private fun invalidateTrailArea() {
        val t = trail
        if (t == null || width <= 0) { invalidate(); return }
        // An animated background repaints everything anyway.
        if (animatedBgActive() && liveBg.isActive()) { invalidate(); return }
        val has = t.bounds(trailBounds)
        val hasBubble = glideBubbleRect(glideBubbleBounds)
        if (!has && !trailPrevValid && !hasBubble && !glideBubblePrevValid) { invalidate(); return }
        val pad = dp(24).toFloat()   // stroke width + round cap + head blob
        var l = if (has) trailBounds.left else Float.MAX_VALUE
        var top = if (has) trailBounds.top else Float.MAX_VALUE
        var r = if (has) trailBounds.right else -Float.MAX_VALUE
        var b = if (has) trailBounds.bottom else -Float.MAX_VALUE
        if (trailPrevValid) {
            l = min(l, trailPrevBounds.left)
            top = min(top, trailPrevBounds.top)
            r = max(r, trailPrevBounds.right)
            b = max(b, trailPrevBounds.bottom)
        }
        // The floating word moves with the finger and vanishes on lift, so
        // its last frame's pixels need clearing exactly like the trail's tail.
        if (hasBubble) {
            l = min(l, glideBubbleBounds.left)
            top = min(top, glideBubbleBounds.top)
            r = max(r, glideBubbleBounds.right)
            b = max(b, glideBubbleBounds.bottom)
        }
        if (glideBubblePrevValid) {
            l = min(l, glideBubblePrev.left)
            top = min(top, glideBubblePrev.top)
            r = max(r, glideBubblePrev.right)
            b = max(b, glideBubblePrev.bottom)
        }
        invalidate(
            (l - pad).toInt(), (top - pad).toInt(),
            (r + pad).toInt() + 1, (b + pad).toInt() + 1
        )
        if (has) { trailPrevBounds.set(trailBounds); trailPrevValid = true } else trailPrevValid = false
        if (hasBubble) { glideBubblePrev.set(glideBubbleBounds); glideBubblePrevValid = true }
        else glideBubblePrevValid = false
    }

    /** Draw key faces as outlines instead of fills — far fewer lit pixels on
     *  an OLED panel, which is where a keyboard's power actually goes. */
    var outlineKeys = false
        set(v) { if (field != v) { field = v; markLayersDirty() } }

    // NOTE — a RenderNode cache lived here in 1.7.2 and is deliberately gone.
    //
    // Caching the grid as a display list instead of pixels is genuinely
    // cheaper, but a STANDALONE RenderNode's display list belongs to the
    // window's hardware renderer, and an IME window is torn down and rebuilt
    // every time the keyboard hides. After a hide/show the recorded list is
    // unusable while hasDisplayList() still reports true, so the cache
    // returned a node that drew absolutely nothing: the keys vanished until
    // something happened to call markLayersDirty() and re-record it.
    //
    // A Bitmap is plain CPU memory with no such lifetime, and it keeps the
    // win that actually mattered — one blit instead of ~40 key draws per
    // frame. Doing this properly with a RenderNode would mean re-recording on
    // every surface change; not worth an invisible keyboard.

    fun markLayersDirty() {
        keysLayer?.recycle()
        keysLayer = null
        bgLayer?.recycle()
        bgLayer = null
        com.localboard.keyboard.util.FrameStats.layerRebuilds++
        invalidate()
    }

    /** Wallpaper bitmap (already sampled down) or null; [dim] 0..0.85 scrim. */
    fun setWallpaper(bmp: android.graphics.Bitmap?, dim: Float) {
        wallpaper = bmp
        wallpaperDim = dim.coerceIn(0f, 0.85f)
        bgLayer?.recycle()
        bgLayer = null
        invalidate()
    }

    /** Legacy on/off entry point: maps to the drifting-colour style. */
    fun setLiveBackground(enabled: Boolean) {
        liveBg.style = if (enabled) LiveBackground.Style.BLOBS else LiveBackground.Style.NONE
        invalidate()
    }

    /** Full animated-background configuration. */
    fun setLiveBackground(styleId: String, fps: Int, whileTyping: Boolean, intensity: Float) {
        liveBg.style = LiveBackground.Style.fromId(styleId)
        liveBg.fps = fps
        liveBg.onlyWhileTyping = whileTyping
        liveBg.intensity = intensity
        invalidate()
    }

    /** True when an animated background is actually in play. A wallpaper wins:
     *  animating behind a photo is invisible and pure cost. */
    private fun animatedBgActive(): Boolean =
        liveBg.style != LiveBackground.Style.NONE &&
            (wallpaper?.isRecycled != false)

    /** Compose the static background once, at view size. */
    private fun ensureBgLayer(): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0) return null
        bgLayer?.let { if (!it.isRecycled && layerW == width && layerH == height) return it }
        val bmp = try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { return null }
        val c = Canvas(bmp)
        val src = wallpaper
        if (src != null && !src.isRecycled) {
            val bw = src.width.toFloat()
            val bh = src.height.toFloat()
            val scale = maxOf(width / bw, height / bh)
            val dx = (width - bw * scale) / 2f
            val dy = (height - bh * scale) / 2f
            val m = android.graphics.Matrix()
            m.setScale(scale, scale)
            m.postTranslate(dx, dy)
            c.drawBitmap(src, m, bgPaint)
            // Scrim keeps keys legible; tinted with the theme bg. Baked in
            // here so it is never a second full-area fill per frame.
            bgPaint.color = theme.background
            bgPaint.alpha = (wallpaperDim * 255).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
            bgPaint.alpha = 255
        } else {
            bgPaint.color = theme.background
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
        bgLayer = bmp
        layerW = width
        layerH = height
        return bmp
    }

    /** Render every key once into a transparent layer. */
    private fun ensureKeysLayer(): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0 || keyRects.isEmpty()) return null
        keysLayer?.let { if (!it.isRecycled && it.width == width && it.height == height) return it }
        val bmp = try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { return null }
        val c = Canvas(bmp)
        buildingLayer = true
        try {
            for (kr in keyRects) drawKey(c, kr)
        } finally {
            buildingLayer = false
        }
        keysLayer = bmp
        return bmp
    }

    /**
     * The key currently drawn live (pressed or mid-release), if any.
     *
     * Guards against a reference that outlived its layout. pressedKey and
     * releaseAnimKey are held across calls, and computeKeyRects() replaces
     * every KeyRect object — so a stale one carries the OLD rect. Drawing it
     * clips a hole at coordinates that mean nothing in the new layout. Cheap
     * to verify by identity, and it makes the whole class of bug unreachable
     * rather than fixed at one call site.
     */
    private fun liveKey(): KeyRect? {
        if (isSwiping) return null
        val k = pressedKey ?: releaseAnimKey ?: return null
        return if (keyRects.any { it === k }) k else null
    }

    /**
     * Dirty region for one key: the face, the press-effect overshoot, and the
     * preview bubble that floats above it. Generous on purpose — a rect that
     * is 20% too big still repaints ~3% of the view instead of 100%, whereas
     * one that is 1px too small leaves visible litter on screen.
     */
    /**
     * The outer bound of what [drawKey] can paint for one key: its face plus
     * the press-effect overshoot (bounce peaks at 1.09x, so 12% is
     * comfortable). Used ONLY to size the invalidate.
     *
     * Overlapping a neighbour here is harmless — it just repaints a little
     * more than strictly necessary. Overlapping a neighbour in [keyClipRect]
     * is not, which is why they are two functions.
     */
    private fun keyPaintRect(kr: KeyRect, out: RectF) {
        val r = kr.rect
        val gx = r.width() * 0.12f
        val gy = r.height() * 0.12f
        out.set(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy)
    }

    /**
     * The region of the CACHED grid to remove before drawing the live key.
     *
     * Its only job is to take away the resting key so a press effect that dips
     * BELOW resting size cannot reveal cached pixels around the shrunken face.
     * The face itself covers that completely, so this is the face plus a
     * hairline for antialiasing — and, critically, nothing more.
     *
     * THE INVARIANT: keyClipRect ⊆ keyPaintRect ⊆ the invalidated region.
     * Anything cut out but not repainted is a hole in the keyboard.
     *
     * This used to be keyPaintRect, and that is what "every key looks like a
     * PNG" was. A 12% margin is measured against the key's OWN width: 3.7dp on
     * a 31dp letter key, which fits inside the 4.5dp gutter, but 15dp on a
     * 126dp key of the 3-column number pad — so pressing 5 bit a 10dp strip out
     * of both 4 and 6, and nothing ever repainted it. The letters layout hid
     * the bug because it is the one layout whose keys are narrow enough for the
     * proportion to stay inside the gutter. A margin that scales with the key
     * cannot be safe on a layout whose keys are four times wider; this one does
     * not scale.
     */
    private fun keyClipRect(kr: KeyRect, out: RectF) {
        val r = kr.rect
        out.set(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f)
    }

    private val keyAreaScratch = RectF()
    private val keyClipScratch = RectF()

    private fun invalidateKeyArea(kr: KeyRect?) {
        if (kr == null || width <= 0) { invalidate(); return }
        // While an animated background is running, a partial repaint would
        // advance the animation inside the dirty rect only, leaving a visible
        // seam against the rest of the frame. Animation frames are full-view
        // anyway, so just take one.
        if (animatedBgActive() && liveBg.isActive()) { invalidate(); return }
        keyPaintRect(kr, keyAreaScratch)
        // Superset of the painted region: a margin for antialiasing. The
        // press-preview pill floats in the root's ViewOverlay, outside this
        // view; its host-side dirty rects are handled in syncPressPreview.
        val pad = dp(4).toFloat()
        invalidate(
            (keyAreaScratch.left - pad).toInt() - 1,
            (keyAreaScratch.top - pad).toInt() - 1,
            (keyAreaScratch.right + pad).toInt() + 1,
            (keyAreaScratch.bottom + pad).toInt() + 1
        )
    }

    /** Background for this frame: one blit for the static case, or the
     *  animated layer. Returns true when another animation frame is wanted. */
    private fun drawBackground(canvas: Canvas): Boolean {
        if (animatedBgActive()) {
            liveBg.setColors(theme.background, theme.accent, theme.trail, theme.keyFunctional)
            return liveBg.draw(canvas, width, height)
        }
        // No wallpaper: a solid fill beats a bitmap blit outright — same
        // result, but nothing to allocate and no source pixels to read.
        // Caching a flat colour into a view-sized bitmap would be slower than
        // not caching it.
        val src = wallpaper
        if (src == null || src.isRecycled) {
            canvas.drawColor(theme.background)
            return false
        }
        val layer = ensureBgLayer()
        if (layer != null && !layer.isRecycled) {
            canvas.drawBitmap(layer, 0f, 0f, layerPaint)
        } else {
            canvas.drawColor(theme.background)
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stats = com.localboard.keyboard.util.FrameStats
        stats.frames++
        // A frame whose clip covers the view repainted everything. This is the
        // number that tells you whether dirty-rect drawing is actually working:
        // if it climbs with keystrokes, something is calling invalidate().
        //
        // getClipBounds() with no argument allocates a Rect every call — an
        // allocation per frame, in the counter that exists to police exactly
        // that kind of waste. The out-parameter overload reuses ours.
        if (canvas.getClipBounds(clipScratch) &&
            clipScratch.width() >= width && clipScratch.height() >= height
        ) {
            stats.fullRedraws++
        }

        // Page-change crossfade. The old page's snapshot goes down at full
        // strength; the new content draws into an alpha layer on top. The
        // window resize that accompanies a page change is untouched — this
        // animates pixels, not layout.
        var contentLayer = -1
        pageTransition?.let { t ->
            val alpha = pageTransitionAlpha(t)
            if (alpha >= 1f) {
                endPageTransition()
            } else {
                canvas.drawBitmap(t.snapshot, 0f, 0f, null)
                transitionPaint.alpha = (alpha * 255).toInt()
                contentLayer = canvas.saveLayer(
                    0f, 0f, width.toFloat(), height.toFloat(), transitionPaint
                )
                removeCallbacks(pageTransitionTick)
                postOnAnimation(pageTransitionTick)
            }
        }

        val wantsBgFrame = drawBackground(canvas)
        if (wantsBgFrame) stats.backgroundFrames++

        // The resting key grid comes from the cached layer. The one key being
        // pressed or released is cut out of the blit and drawn live over the
        // background, so its scale/glow animation is correct even when it
        // shrinks below its resting size.
        val live = liveKey()
        val layer = ensureKeysLayer()

        // Cut the live key out of the cached grid, then draw it for real. The
        // cut-out matters: the cached key is the resting colour, and it would
        // otherwise peek out around the edges of any effect that does not cover it.
        fun blitGrid() {
            if (layer != null && !layer.isRecycled) canvas.drawBitmap(layer, 0f, 0f, layerPaint)
            else for (kr in keyRects) drawKey(canvas, kr)
        }
        if (live != null && layer != null) {
            // Cut out the key's own face and nothing else. Anything erased
            // beyond it belongs to a neighbouring key, and nothing repaints
            // that. drawKey runs with the clip restored, so a press effect that
            // grows past the face still paints over the grid normally.
            keyClipRect(live, keyClipScratch)
            canvas.save()
            canvas.clipOutRect(keyClipScratch)
            blitGrid()
            canvas.restore()
            drawKey(canvas, live)
        } else {
            blitGrid()
        }
        if (contentLayer >= 0) canvas.restoreToCount(contentLayer)

        // Press animation keeps asking for frames, but only over the key's own
        // dirty rect — this used to repaint the entire grid at 60 fps.
        if (effectivePressEffect != 3 &&
            ((pressedKey != null && System.currentTimeMillis() - pressAnimStart < PRESS_ANIM_MS) ||
                releaseAnimKey != null)
        ) {
            // Reused Runnable: a lambda here would allocate once per animation
            // frame, ~9 per keypress.
            removeCallbacks(pressAnimTick)
            postDelayed(pressAnimTick, 16)
        }

        // Press preview pill above the tapped character key. It lives in the
        // window root's ViewOverlay (see syncPressPreview), not in this
        // canvas: on the top row the pill hangs over the bar, outside this
        // view's bounds.
        syncPressPreview()

        // Glide trail. Repaints its own region rather than the whole keyboard:
        // during a glide this ran at animation rate with a full-view invalidate
        // every frame.
        (if (trailEnabled) trail else null)?.let { t ->
            val alive = t.draw(canvas, dp(20).toFloat())
            if (alive) postOnAnimation(trailTick)
        }
        // The word forming under the finger, over the trail.
        drawGlideBubble(canvas)

        // Schedule the next animated-background frame at the style's own pace
        // rather than chasing vsync. Nothing is scheduled when the window is
        // hidden, or when the animation has settled.
        if (wantsBgFrame && isShown) postInvalidateDelayed(liveBg.frameDelayMs)
    }

    private fun drawKey(canvas: Canvas, kr: KeyRect) {
        val key = kr.key
        val r = kr.rect
        // While rendering the cached layer every key is at rest — the live key
        // is drawn separately on top of it.
        val pressed = !buildingLayer && pressedKey === kr && !isSwiping

        // Press feedback — four selectable effects (see Prefs.pressEffect).
        // Lift: slight grow with an accent lift, eased back on release.
        // Bounce: bigger grow, eased back on release. A wobble would only flicker in the
        // few frames a key now has.
        // Glow: no motion, accent halo ring + stronger tint.
        // None: static darker shade, zero animation frames (cheapest).
        val now = System.currentTimeMillis()
        var scale = 1f
        var lift = 0f
        var glow = 0f
        var staticPress = false
        // Skipped while building the cached layer: besides being the wrong
        // visual state, these branches assign to releaseAnimKey, so running
        // them during a layer rebuild would eat a real release animation.
        if (!buildingLayer) when (effectivePressEffect) {
            3 -> {
                if (pressed) staticPress = true
                if (releaseAnimKey === kr) releaseAnimKey = null
            }
            1 -> { // bounce
                if (pressed) {
                    val t = ((now - pressAnimStart) / PRESS_ANIM_MS).coerceIn(0f, 1f)
                    scale = 1f + 0.09f * t
                    lift = t * 0.7f
                } else if (releaseAnimKey === kr) {
                    val t = ((now - releaseAnimStart) / PRESS_ANIM_MS).coerceIn(0f, 1f)
                    if (t >= 1f) {
                        releaseAnimKey = null
                    } else {
                        val ease = (1f - t) * (1f - t)
                        scale = 1f + 0.09f * ease
                        lift = ease * 0.7f
                    }
                }
            }
            2 -> { // glow
                if (pressed) {
                    val t = ((now - pressAnimStart) / PRESS_ANIM_MS).coerceIn(0f, 1f)
                    glow = t
                } else if (releaseAnimKey === kr) {
                    val t = ((now - releaseAnimStart) / PRESS_ANIM_MS).coerceIn(0f, 1f)
                    if (t >= 1f) releaseAnimKey = null else glow = 1f - t
                }
            }
            else -> { // lift (default)
                if (pressed) {
                    val t = ((now - pressAnimStart) / PRESS_ANIM_MS).coerceIn(0f, 1f)
                    scale = 1f + 0.055f * t
                    lift = t
                } else if (releaseAnimKey === kr) {
                    val t = ((now - releaseAnimStart) / PRESS_ANIM_MS).coerceIn(0f, 1f)
                    if (t >= 1f) {
                        releaseAnimKey = null
                    } else {
                        scale = 1.055f - 0.055f * t
                        lift = 1f - t
                    }
                }
            }
        }

        val baseColor = if (key.functional) theme.keyFunctional else theme.key
        keyPaint.color = when {
            staticPress -> blendColor(baseColor, 0xFF000000.toInt(), 0.16f)
            glow > 0f -> blendColor(baseColor, theme.accent, 0.42f * glow)
            lift > 0f -> blendColor(baseColor, theme.accent, 0.34f * lift)
            else -> baseColor
        }
        if (scale != 1f) {
            canvas.save()
            canvas.scale(scale, scale, r.centerX(), r.centerY())
        }
        if (outlineKeys && !staticPress && glow <= 0f && lift <= 0f) {
            // Outline mode: a hairline border instead of a filled face. On an
            // OLED panel the number of lit subpixels is what costs power, and
            // 40 filled key faces are most of the keyboard's emission. Pressed
            // and animating keys still fill, so touch feedback stays obvious.
            keyPaint.style = Paint.Style.STROKE
            keyPaint.strokeWidth = dp(1f).toFloat()
            keyPaint.color = blendColor(keyPaint.color, theme.text, 0.35f)
            canvas.drawRoundRect(
                r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f,
                cornerRadius, cornerRadius, keyPaint
            )
            keyPaint.style = Paint.Style.FILL
        } else {
            canvas.drawRoundRect(r, cornerRadius, cornerRadius, keyPaint)
        }
        if (glow > 0f) {
            pressedPaint.color = theme.accent
            pressedPaint.alpha = (190 * glow).toInt()
            pressedPaint.style = Paint.Style.STROKE
            pressedPaint.strokeWidth = dp(2f).toFloat()
            canvas.drawRoundRect(
                r.left + 2, r.top + 2, r.right - 2, r.bottom - 2,
                cornerRadius, cornerRadius, pressedPaint
            )
            pressedPaint.style = Paint.Style.FILL
            pressedPaint.alpha = 255
        }

        // Shift indicator ring when active.
        if (key.type == KeyType.SHIFT && shiftState != ShiftState.OFF) {
            pressedPaint.color = theme.accent
            pressedPaint.style = Paint.Style.STROKE
            pressedPaint.strokeWidth = dp(1.5f).toFloat()
            canvas.drawRoundRect(
                r.left + 1, r.top + 1, r.right - 1, r.bottom - 1,
                cornerRadius, cornerRadius, pressedPaint
            )
            pressedPaint.style = Paint.Style.FILL
        }

        if (key.icon != 0) {
            // Cache mutated drawables: ContextCompat.getDrawable inflates a
            // fresh instance per call — several allocs per key per frame.
            val d: Drawable? = iconCache.getOrPut(key.icon) {
                ContextCompat.getDrawable(context, key.icon)?.mutate()
                    ?: ContextCompat.getDrawable(context, key.icon)!!
            }
            d?.let {
                val tint = when {
                    key.type == KeyType.SHIFT && shiftState != ShiftState.OFF -> theme.accent
                    // P3: icon (functional) keys acknowledge the touch even
                    // when the press-effect style is subtle or None.
                    pressed -> theme.accent
                    else -> theme.text
                }
                it.setTint(tint)
                val size = if (key.type == KeyType.SPACE) 0 else dp(22)
                if (size > 0) {
                    val cx = r.centerX().toInt()
                    val cy = r.centerY().toInt()
                    it.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                    it.draw(canvas)
                }
            }
            if (key.type == KeyType.SHIFT && shiftState == ShiftState.CAPS_LOCK) {
                // Small dot under the arrow for caps-lock.
                pressedPaint.color = theme.accent
                canvas.drawCircle(r.centerX(), r.bottom - dp(8), dp(2.5f).toFloat(), pressedPaint)
            }
        } else if (key.label.isNotEmpty()) {
            val isLetter = key.type == KeyType.CHAR && key.output.length == 1 && key.output[0].isLetter()
            val label = if (isLetter && shiftState != ShiftState.OFF && model.isLetters) {
                key.label.uppercase()
            } else key.label

            // P3: functional label keys (?123, ABC, =\<) also tint on press.
            // P4: keyText overrides the legend color on high-contrast keys.
            textPaint.color = if (pressed && key.functional) theme.accent
                else theme.keyText ?: theme.text
            textPaint.textSize = (if (key.type == KeyType.CHAR) sp(21) else sp(15)) * textScale
            textPaint.isFakeBoldText = false
            val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, r.centerX(), baseline, textPaint)

            // Tiny hint on any key with alternates.
            //
            // This used to be gated on model.isLetters, which hid it on the
            // one page whose layout depends on it. The roomy symbols page
            // carries only seven keys in its third row precisely BECAUSE the
            // rest live in popups — < holds > [ ] { }, " holds the smart
            // quotes and guillemets, : holds ;, ! and ? hold ¡ and ¿ — and
            // with no hint drawn, that page reads as though the characters
            // were simply missing rather than one long-press away.
            val hint = key.hintText
            if (hint.isNotEmpty()) {
                smallTextPaint.color = theme.keyText ?: theme.textSecondary
                smallTextPaint.textSize = sp(9) * textScale
                canvas.drawText(hint, r.right - dp(9), r.top + dp(12), smallTextPaint)
            }
        }

        // Space bar: subtle pill.
        if (key.type == KeyType.SPACE) {
            keyPaint.color = theme.keyText ?: theme.textSecondary
            keyPaint.alpha = 90
            val w = r.width() * 0.45f
            canvas.drawRoundRect(
                r.centerX() - w / 2, r.centerY() - dp(1.5f),
                r.centerX() + w / 2, r.centerY() + dp(1.5f),
                dp(2).toFloat(), dp(2).toFloat(), keyPaint
            )
            keyPaint.alpha = 255
        }

        if (scale != 1f) canvas.restore()
    }

    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val aa = (a ushr 24) + (((b ushr 24) - (a ushr 24)) * f).toInt()
        val rr = ((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * f).toInt()
        val gg = ((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * f).toInt()
        val bb = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * f).toInt()
        return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** The bubble stays a moment after the lift: until then the finger is in the way of it,
     *  and at typing speed that is all the time it ever had. The case is the one it was
     *  showing, since the letter itself can take the shift off as it lands. */
    private var previewLingerKey: KeyRect? = null
    private var previewLingerUpper = false
    private val previewLingerEnd = Runnable { endPreviewLinger() }

    private fun endPreviewLinger() {
        val kr = previewLingerKey ?: return
        previewLingerKey = null
        handler.removeCallbacks(previewLingerEnd)
        invalidateKeyArea(kr)
    }

    /** The key whose press preview is showing, or null when there is none.
     *  Same conditions the old in-canvas bubble used. */
    private fun previewKey(): KeyRect? {
        val kr = (pressedKey ?: previewLingerKey) ?: return null
        return if (previewEnabled && !isSwiping && kr.key.type == KeyType.CHAR && !longPressFired) kr
        else null
    }

    /** Gboard's floating press preview: the key's label in a pill above the
     *  key. Drawn into the window root's ViewOverlay because the pill must
     *  be free to extend ABOVE this view on the top row (over the bar), and
     *  clipChildren=false cannot do that: NavStripView.onDraw and
     *  drawBackground fill the current clip with drawColor, so a widened
     *  clip would just paint over the strip (the D4/B2 note). The overlay
     *  draws after every child of the root, unclipped. */
    private inner class PressPreviewDrawable : Drawable() {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        var label: String = ""

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || label.isEmpty()) return
            bgPaint.color = theme.popupBackground
            val r = dp(8).toFloat()
            canvas.drawRoundRect(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r, r, bgPaint
            )
            fgPaint.color = theme.text
            fgPaint.textSize = sp(26) * textScale
            val baseline = b.centerY() - (fgPaint.descent() + fgPaint.ascent()) / 2
            canvas.drawText(label, b.centerX().toFloat(), baseline, fgPaint)
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            fgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            fgPaint.colorFilter = colorFilter
        }

        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private var previewOverlay: PressPreviewDrawable? = null
    private var previewOverlayHost: View? = null
    /** Where the pill sat in host coordinates last frame — the region a move
     *  or a removal must repaint, exactly the way the glide bubble unions its
     *  previous bounds. */
    private val previewPrevHostRect = Rect()
    private var previewPrevHostRectValid = false
    private val previewHostScratch = Rect()

    /** Positions the overlay pill over [previewKey], or removes it. Called
     *  from onDraw, so every state change that matters already forced a
     *  repaint of this view. Host dirty rects are kept correct here: the
     *  pill lives outside this view, so invalidating only inside its bounds
     *  would leave the pill's pixels unpainted or unerased. */
    private fun syncPressPreview() {
        val kr = previewKey()
        val host = if (kr != null) rootView else null
        if (kr == null || host == null) {
            clearPressPreviewOverlay()
            return
        }

        // Pill geometry in this view's coordinates. Unlike the old bubble the
        // top is NOT clamped at 0: the overlay is outside this view's clip.
        val r = kr.rect
        val pw = max(r.width() * 1.15f, dp(34).toFloat())
        val ph = dp(52).toFloat()
        val left = min(max(r.centerX() - pw / 2, dp(2).toFloat()), width - pw - dp(2))
        val top = r.top - ph - dp(4)

        // View coordinates -> host coordinates, allocation-free.
        getLocationOnScreen(locScratch)
        val vx = locScratch[0]
        val vy = locScratch[1]
        host.getLocationOnScreen(locScratch)
        val dx = vx - locScratch[0]
        val dy = vy - locScratch[1]
        val hostRect = previewHostScratch
        hostRect.set(
            (left + dx).toInt(), (top + dy).toInt(),
            (left + pw + dx).toInt() + 1, (top + ph + dy).toInt() + 1
        )

        val d = previewOverlay ?: PressPreviewDrawable().also { previewOverlay = it }
        if (previewOverlayHost !== host) {
            previewOverlayHost?.overlay?.remove(d)
            host.overlay.add(d)
            previewOverlayHost = host
        }
        d.label = if (kr === previewLingerKey) {
            if (previewLingerUpper) kr.key.label.uppercase() else kr.key.label
        } else {
            if (shiftState != ShiftState.OFF && model.isLetters) kr.key.label.uppercase()
            else kr.key.label
        }
        d.bounds = hostRect

        val pad = dp(8)
        val dirty = Rect(
            if (previewPrevHostRectValid) previewPrevHostRect else hostRect
        )
        dirty.union(hostRect)
        host.invalidate(dirty.left - pad, dirty.top - pad, dirty.right + pad, dirty.bottom + pad)
        previewPrevHostRect.set(hostRect)
        previewPrevHostRectValid = true
    }

    private fun clearPressPreviewOverlay() {
        val d = previewOverlay ?: return
        previewOverlay = null
        val host = previewOverlayHost
        previewOverlayHost = null
        host?.overlay?.remove(d)
        if (host != null && previewPrevHostRectValid) {
            val pad = dp(8)
            host.invalidate(
                previewPrevHostRect.left - pad, previewPrevHostRect.top - pad,
                previewPrevHostRect.right + pad, previewPrevHostRect.bottom + pad
            )
        }
        previewPrevHostRectValid = false
    }

    // ---------------------------------------------------------------
    // Touch handling
    // ---------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            com.localboard.keyboard.util.PhoneStats.noteKeyDown(event.eventTime)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                handleDownAt(event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) handleMove(event, idx)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    handleUpAt(event.getX(event.actionIndex), event.getY(event.actionIndex), event.eventTime)
                    activePointerId = -1
                    promoteLateFinger(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                handleUpAt(event.x, event.y, event.eventTime)
                activePointerId = -1
                lateFingerId = -1
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    /** A second finger touches while one is down (two-thumb typing): finish
     *  the current gesture, then track the new finger as a fresh touch.
     *  Without per-pointer tracking, index-0 coordinates jump between fingers
     *  and read as a huge swipe. */
    private fun handlePointerDown(e: MotionEvent) {
        // A glide well under way carries on: a thumb coming to rest on the board must not
        // commit half a word. A finger that has barely moved may still be two-thumb typing.
        if (isSwiping && swipePathLen > geometry.averageKeyWidth) {
            lateFingerId = e.getPointerId(e.actionIndex)
            lateFingerX = e.getX(e.actionIndex)
            lateFingerY = e.getY(e.actionIndex)
            lateFingerAt = e.eventTime
            return
        }
        val kr = pressedKey
        barSelectMode = false
        if (isSwiping) {
            // An abandoned glide stays abandoned, whatever ends it.
            val decoded = !glideAbandoned && listener?.onSwipePath(ArrayList(swipePoints)) == true
            val consumedAsSwipe = decoded || glideAbandoned || swipePathLen >= width * 0.30f
            if (!consumedAsSwipe && kr != null && !longPressFired && kr.key.type != KeyType.DELETE) {
                dispatchTap(kr)
                // That key may have opened the emoji panel, which takes this view off the
                // window. No later event reaches it, so the new finger must not start
                // anything here that only a lift could end.
                if (!isAttachedToWindow) { pressedKey = null; activePointerId = -1; return }
            }
            listener?.onSwipeEnded()
            glideWord = null
            trail?.finish()
            invalidateTrailArea()
            swipePoints.clear()
            swipePathLen = 0f
        } else if (kr != null && !longPressFired && !spaceCursorMode && kr.key.type != KeyType.DELETE) {
            dispatchTap(kr)
            if (!isAttachedToWindow) { pressedKey = null; activePointerId = -1; return }
        }
        spaceCursorMode = false
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)
        // A popup open under the first finger ends here, and both of its outcomes used to be
        // lost: the character picked, and the key itself when nothing was picked.
        if (popupWindow?.isShowing == true && kr != null) {
            if (popupFingerMoved) {
                commitPopupSelection()
            } else {
                dispatchTap(kr)
                if (!isAttachedToWindow) { pressedKey = null; activePointerId = -1; return }
            }
        }
        dismissPopup()
        pressedKey = null
        activePointerId = e.getPointerId(e.actionIndex)
        handleDownAt(e.getX(e.actionIndex), e.getY(e.actionIndex), e.eventTime)
    }

    /**
     * A glide's finger has just lifted. A finger that landed in its last moments is the next
     * letter or glide, begun while this one finished, so it becomes a touch like any other, from
     * where it landed: a next glide needs that origin. One that landed earlier is a thumb at
     * rest. It stays ignored and types nothing at its own lift. Backspace is left out because it
     * acts on the touch, and would take the word just committed.
     */
    private fun promoteLateFinger(e: MotionEvent) {
        val id = lateFingerId
        lateFingerId = -1
        if (id < 0 || e.findPointerIndex(id) < 0) return
        if (e.eventTime - lateFingerAt > LATE_FINGER_MS) return
        if (findKey(lateFingerX, lateFingerY)?.key?.type == KeyType.DELETE) return
        activePointerId = id
        handleDownAt(lateFingerX, lateFingerY, lateFingerAt)
    }

    private fun handleDownAt(x: Float, y: Float, eventTime: Long) {
        // A popup left over from a touch that never lifted must not catch this tap.
        dismissPopup()
        endPreviewLinger()
        downX = x
        downY = y
        downAt = eventTime
        isSwiping = false
        longPressFired = false
        spaceCursorMode = false
        glideAbandoned = false
        pendingPoints.clear()
        pendingPoints += PointF(x, y)
        rawCount = 0
        recordRaw(x, y, eventTime)
        pressAnimStart = System.currentTimeMillis()
        pressedKey = findKey(x, y)
        pressedKey?.let { kr ->
            feedback()
            when (kr.key.type) {
                KeyType.DELETE -> {
                    listener?.onKey(KeyType.DELETE)
                    deleteRepeatCount = 0
                    handler.postDelayed(deleteRepeatRunnable, 400)
                }
                else -> {
                    // P2: the space bar has no popup chars but still wants a
                    // long-press — it opens the IME switcher.
                    if (kr.key.popup.isNotEmpty() ||
                        kr.key.type == KeyType.SPACE ||
                        kr.key.type == KeyType.ENTER
                    ) {
                        // A frame still awaited belongs to the press before this one.
                        if (longPressAwaitingFrame) {
                            longPressAwaitingFrame = false
                            removeCallbacks(longPressAfterFrame)
                        }
                        longPressDueAt = SystemClock.uptimeMillis() + longPressMs
                        handler.postDelayed(longPressRunnable, longPressMs)
                    }
                }
            }
            com.localboard.keyboard.util.FrameStats.keystrokes++
            liveBg.onKeyPress()
            // The nav-bar strip runs its own LiveBackground, so a reactive
            // style there needs the same signal or it would never react.
            onKeyPressed?.invoke(kr.key.type)
            // One key changed, so repaint one key's worth of pixels. This was
            // a full-view invalidate, i.e. all ~40 keys, on every keystroke.
            invalidateKeyArea(kr)
        }
    }

    private fun handleMove(e: MotionEvent, idx: Int) {
        val kr = pressedKey ?: return

        if (popupWindow?.isShowing == true) {
            // rawX/rawY without an index are pointer 0's — wrong finger when
            // a second thumb is down. Use the tracked pointer's coordinates.
            updatePopupSelection(e.getRawX(idx), e.getRawY(idx))
            return
        }

        // P2: sliding along the space bar scrubs the editor cursor one char
        // per step (Gboard-style). Under the arming distance a lift types the
        // space, so a thumb that rolls sideways on the most used key still
        // gets it. Arming moves the caret at once: there is no stretch where a
        // lift would do nothing at all. The one case left is the end of the
        // text, where a move right has nowhere to go and no space comes either.
        if (kr.key.type == KeyType.SPACE && !longPressFired) {
            val sx = e.getX(idx)
            val slid = abs(sx - downX)
            // Sooner than arming, or a slow start to a scrub opens the IME switcher.
            if (slid > longPressCancelPx) handler.removeCallbacks(longPressRunnable)
            if (!spaceCursorMode && slid > dp(24)) {
                spaceCursorMode = true
                spaceCursorLastX = sx
                handler.removeCallbacks(longPressRunnable)
                listener?.onSpaceCursorMove(if (sx > downX) 1 else -1)
                return
            }
            if (spaceCursorMode) {
                val stepPx = dp(16).toFloat()
                val steps = ((sx - spaceCursorLastX) / stepPx).toInt()
                if (steps != 0) {
                    spaceCursorLastX += steps * stepPx
                    listener?.onSpaceCursorMove(steps)
                }
                return
            }
        }

        // A letter's popup is for a finger that stays put. Past twice the slop this is a glide
        // starting slowly, or a slip where gliding is off, and at 380 ms it would otherwise
        // throw the gesture away for a digit or a symbol at lift.
        if (!isSwiping && (swipeEnabled || glideBlockedHere) && model.isLetters &&
            kr.key.type == KeyType.CHAR && !longPressFired
        ) {
            val dx = e.getX(idx) - downX
            val dy = e.getY(idx) - downY
            if (dx * dx + dy * dy > longPressCancelPx * longPressCancelPx) {
                handler.removeCallbacks(longPressRunnable)
            }
        }

        // Start glide typing when the finger travels on the letter board.
        if (!isSwiping && swipeEnabled && model.isLetters &&
            kr.key.type == KeyType.CHAR && !longPressFired
        ) {
            val dx = e.getX(idx) - downX
            val dy = e.getY(idx) - downY
            // Above the keys it is today's distance: a flick up to pick a word right after
            // typing has only the top row to travel in, and could never make the longer one.
            val act = if (e.getY(idx) < -barEnterPx) swipeActivation else armingDistance(e.eventTime)
            if (dx * dx + dy * dy > act * act) {
                isSwiping = true
                glideWord = null
                handler.removeCallbacks(longPressRunnable)
                // Seed with the full buffered path from finger-down — the
                // decoder needs the true origin, not the arming point.
                swipePoints.clear()
                swipePoints.addAll(pendingPoints)
                swipePathLen = 0f
                for (i in 1 until swipePoints.size) {
                    swipePathLen += dist(swipePoints[i - 1], swipePoints[i].x, swipePoints[i].y)
                }
                // Path length continues from the true last sample, not from
                // whichever point survived thinning.
                lastSampleX = swipePoints.last().x
                lastSampleY = swipePoints.last().y
                trail?.start(swipePoints.first().x, swipePoints.first().y)
                listener?.onSwipeStarted()
                // One full repaint as the gesture begins: isSwiping now
                // suppresses the pressed-key rendering, and that key may sit
                // outside the trail's opening bounds. Every later frame is
                // dirty-rect only.
                trailPrevValid = false
                invalidate()
            } else {
                // Buffer the pre-activation path THINNED, like the glide
                // path itself: a raw cap of 40 samples was a third of a
                // second on a 120 Hz digitizer, after which a slow-starting
                // glide lost the stretch between its origin and the arming
                // point. History counts too: at 60 Hz while typing, a glide begun
                // under the longer arming distance would otherwise reach the
                // decoder with half of its first 150 ms.
                for (i in 0 until e.historySize) {
                    val hx = e.getHistoricalX(idx, i); val hy = e.getHistoricalY(idx, i)
                    recordRaw(hx, hy, e.getHistoricalEventTime(i))
                    if (pendingPoints.size < 400 &&
                        dist(pendingPoints.last(), hx, hy) >= minPointGapPx
                    ) {
                        pendingPoints += PointF(hx, hy)
                    }
                }
                val ex = e.getX(idx); val ey = e.getY(idx)
                recordRaw(ex, ey, e.eventTime)
                if (pendingPoints.size < 400 &&
                    dist(pendingPoints.last(), ex, ey) >= minPointGapPx
                ) {
                    pendingPoints += PointF(ex, ey)
                }
            }
        }

        if (isSwiping) {
            if (glideAbandoned) return
            val ex = e.getX(idx)
            val ey = e.getY(idx)
            barTouchAt = e.eventTime
            // Swipe-up word selection: above the top edge is the suggestion
            // bar's strip. The glide freezes (points stop accruing, so the
            // frozen path stays decodable) and the finger drives the bar's
            // highlight instead — Gboard-style "slide up to pick a word".
            if (barSelectMode) {
                if (ey <= barExitPx) {
                    listener?.onSwipeBarHover((ex / width).coerceIn(0f, 1f))
                    return
                }
                // Back on the keys. Coming back near where it left, the glide carries on from
                // there. Far from it, the straight line between would be a stroke nobody
                // traced, so nothing comes of this gesture: a lost word costs one more glide,
                // a wrong one costs a delete as well.
                barSelectMode = false
                listener?.onSwipeBarHover(-1f)
                lastSampleX = ex; lastSampleY = ey
                if (abs(ex - swipePoints.last().x) > geometry.averageKeyWidth * 1.5f) {
                    glideAbandoned = true
                    glideWord = null
                    trail?.finish()
                    invalidateTrailArea()
                    return
                }
                // This event's history is from over the bar, so only its own point counts.
                recordRaw(ex, ey, e.eventTime)
                swipePoints += PointF(ex, ey)
                trail?.add(ex, ey)
                invalidateTrailArea()
                return
            }
            if (ey < -barEnterPx) {
                barSelectMode = true
                glideWord = null
                listener?.onSwipeBarHover((ex / width).coerceIn(0f, 1f))
                invalidate()
                return
            }
            // SAMPLE THINNING. Every historical sample used to become a PointF,
            // so one glide on a 120 Hz digitizer allocated a few hundred objects
            // and handed the decoder a path denser than it can use — it resamples
            // to a fixed count regardless. Points closer together than a couple
            // of dp carry no shape information, only allocation and copy cost.
            //
            // Path LENGTH still accumulates from every sample (it gates the
            // tap-vs-glide decision), and the trail still receives every sample
            // so the visual stays smooth.
            for (i in 0 until e.historySize) {
                val hx = e.getHistoricalX(idx, i); val hy = e.getHistoricalY(idx, i)
                recordRaw(hx, hy, e.getHistoricalEventTime(i))
                swipePathLen += dist(lastSampleX, lastSampleY, hx, hy)
                lastSampleX = hx; lastSampleY = hy
                if (dist(swipePoints.last(), hx, hy) >= minPointGapPx) swipePoints += PointF(hx, hy)
                trail?.add(hx, hy)
            }
            recordRaw(ex, ey, e.eventTime)
            swipePathLen += dist(lastSampleX, lastSampleY, ex, ey)
            lastSampleX = ex; lastSampleY = ey
            if (dist(swipePoints.last(), ex, ey) >= minPointGapPx) swipePoints += PointF(ex, ey)
            trail?.add(ex, ey)
            // Live candidates while gliding, throttled to ~14 Hz.
            val now = e.eventTime
            if (swipePoints.size >= 6 && now - lastSwipeProgressAt > 70) {
                lastSwipeProgressAt = now
                listener?.onSwipeProgress(ArrayList(swipePoints))
            }
            invalidateTrailArea()
        }
    }

    private fun dist(p: PointF, x: Float, y: Float): Float {
        val dx = p.x - x; val dy = p.y - y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Minimum spacing between retained glide points. Below this the decoder's
     *  resampler cannot tell the difference, so the sample is pure overhead. */
    private val minPointGapPx: Float by lazy { dp(2f).toFloat() }

    /** Last raw digitizer sample, for path-length accumulation independent of
     *  which samples were retained. */
    private var lastSampleX = 0f
    private var lastSampleY = 0f

    // Into the bar a good way above the keys, so a small overshoot on the top row stays a
    // glide, and back out a little below their top edge, so that one finger wavering at the
    // edge does not flip between the two.
    private val barEnterPx: Float by lazy { dp(10f).toFloat() }
    private val barExitPx: Float by lazy { dp(4f).toFloat() }

    /** The finger came back from the bar far from where it left. Moves are ignored from then
     *  on, and the lift commits nothing and types nothing. */
    private var glideAbandoned = false

    /** A letter right after a tap has to slide further before it can be a glide, for its first
     *  150 ms: a thumb that slips during a tap is not starting a word. A real glide loses
     *  nothing, because the samples are kept from finger-down. */
    private fun armingDistance(now: Long): Float {
        val base = swipeActivation
        return if (now - lastTapCommitAt < 500 && now - downAt < 150) {
            max(base, geometry.averageKeyWidth * 1.6f)
        } else {
            base
        }
    }

    // Every raw sample of the touch, unthinned, for the pause on a doubled letter. Primitive
    // arrays and no PointF per sample: a move event must not pay in garbage for this.
    private val rawXs = FloatArray(RAW_SAMPLE_CAPACITY)
    private val rawYs = FloatArray(RAW_SAMPLE_CAPACITY)
    private val rawTimes = LongArray(RAW_SAMPLE_CAPACITY)
    private var rawCount = 0
    private val dwellKeyX = FloatArray(26)
    private val dwellKeyY = FloatArray(26)

    private fun recordRaw(x: Float, y: Float, time: Long) {
        if (rawCount >= RAW_SAMPLE_CAPACITY) return
        rawXs[rawCount] = x
        rawYs[rawCount] = y
        rawTimes[rawCount] = time
        rawCount++
    }

    /**
     * Letters the finger paused on in this glide, as a 26-bit mask with bit c-'a'. Null means
     * the decoder should use its own fallback, never "no pauses": not swiping, no key width
     * yet, or so long a stroke that the samples ran out. Main thread only. Good inside
     * onSwipePath and onSwipeProgress: the samples are reset at the next finger down, so a
     * caller that goes off the main thread reads it first and passes the Int along.
     */
    fun swipeDwellMask(): Int? {
        if (!isSwiping) return null
        val keyW = geometry.averageKeyWidth
        if (keyW <= 0f || rawCount >= RAW_SAMPLE_CAPACITY) return null
        dwellKeyX.fill(Float.NaN)
        dwellKeyY.fill(Float.NaN)
        for ((c, p) in geometry.keyCenters) {
            val i = c - 'a'
            if (i in 0 until 26) {
                dwellKeyX[i] = p.x
                dwellKeyY[i] = p.y
            }
        }
        return DwellMask.of(rawXs, rawYs, rawTimes, rawCount, keyW, dwellKeyX, dwellKeyY)
    }

    private fun handleUpAt(x: Float, y: Float, eventTime: Long) {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)

        if (popupWindow?.isShowing == true) {
            commitPopupSelection()
            dismissPopup()
            pressedKey = null
            invalidate()
            return
        }

        val kr = pressedKey
        val upperAtLift = shiftState != ShiftState.OFF && model.isLetters
        var tapped = false
        if (isSwiping) {
            var consumedByBar = false
            val wasOverBar = barSelectMode
            barSelectMode = false
            if (wasOverBar && y < 0f) {
                // Lifted over the bar: commit whichever word the finger is on.
                // False (gap between words / empty slot) falls through to the
                // normal decode of the frozen path — no gesture is ever lost.
                barTouchAt = eventTime
                consumedByBar = listener?.onSwipeBarPick((x / width).coerceIn(0f, 1f)) == true
            }
            if (!consumedByBar && !glideAbandoned) {
                // A lift over the bar is off the path, and the decoder would anchor on it.
                if (!wasOverBar) {
                    recordRaw(x, y, eventTime)
                    swipePathLen += dist(lastSampleX, lastSampleY, x, y)
                    // The lift point is always kept regardless of spacing: it is
                    // the end of the word and the decoder anchors on it.
                    swipePoints += PointF(x, y)
                }
                // Activation (≈ one key width) already guarantees intent; this is
                // only a second-chance guard so decode failures fall back to a tap.
                // Just under the decoder's own floor (0.7 key widths ≈ 0.07
                // of the board): "we"/"as" are one key of travel and must
                // reach the decoder rather than fall back to a tap.
                val minSwipeLen = width * 0.06f
                val decoded = swipePathLen >= minSwipeLen &&
                    (listener?.onSwipePath(ArrayList(swipePoints)) == true)
                // A long deliberate glide that fails to decode is still a swipe —
                // swallow it. Falling back to a tap here typed a stray letter for
                // every failed word ("jumped" → "jhjd").
                val consumedAsSwipe = decoded || swipePathLen >= width * 0.30f
                if (!consumedAsSwipe && kr != null && !longPressFired && kr.key.type != KeyType.DELETE) {
                    dispatchTap(kr)
                }
            }
            listener?.onSwipeEnded()
            glideWord = null
            trail?.finish()
            // One more repaint over the bubble's last position: the trail
            // fade ticks would clear it eventually, but only if the trail is
            // enabled — and never instantly.
            invalidateTrailArea()
            swipePoints.clear()
            swipePathLen = 0f
        } else if (kr != null && !longPressFired && !spaceCursorMode && kr.key.type != KeyType.DELETE) {
            // DELETE fired on finger-down (repeat starts there); every other
            // key fires on up. Dispatching DELETE here too ate two chars.
            // A space-bar cursor scrub must not also type a space on lift.
            dispatchTap(kr)
            tapped = true
        }

        isSwiping = false
        spaceCursorMode = false
        // dispatchKey() above may have swapped the whole layout (emoji, the
        // cursor pad, ?123 / ABC). computeKeyRects() then rebuilt every
        // KeyRect, so `kr` is a reference into the layout that no longer
        // exists — animating it would clip a hole at the old coordinates and
        // paint the old key floating over the new keyboard. That is the
        // "keys look like a PNG" artifact.
        val stillValid = keyRects.any { it === kr }
        releaseAnimKey = if (stillValid) kr else null
        releaseAnimStart = System.currentTimeMillis()
        pressedKey = null
        if (tapped && stillValid && previewEnabled && kr != null && kr.key.type == KeyType.CHAR) {
            previewLingerKey = kr
            previewLingerUpper = upperAtLift
            handler.removeCallbacks(previewLingerEnd)
            handler.postDelayed(previewLingerEnd, PREVIEW_LINGER_MS)
        }
        // Release animation is also one key's worth of pixels.
        if (stillValid) invalidateKeyArea(kr) else invalidate()
    }

    /** The field changed with the keyboard still up, and a held Backspace must not run on into
     *  the next one. Nothing else about the touch ends here: a digit being pressed as a code
     *  form moves to its next box still types at its lift. */
    fun stopDeleteRepeat() {
        handler.removeCallbacks(deleteRepeatRunnable)
    }

    private fun cancelTouch() {
        activePointerId = -1
        lateFingerId = -1
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)
        dismissPopup()
        endPreviewLinger()
        barSelectMode = false
        spaceCursorMode = false
        glideAbandoned = false
        if (isSwiping) {
            listener?.onSwipeEnded()
            glideWord = null
            trail?.finish()
            swipePoints.clear()
        }
        isSwiping = false
        pressedKey = null
        invalidate()
    }

    /**
     * A key dispatched because a touch of it ended. The point is where the finger came down,
     * for the service's tap trace: rollover and failed-glide taps have one like any other, and
     * [downX] still holds this finger at every site, because a new finger is only tracked
     * after this runs. The service consumes it synchronously in handleText; anything but a
     * single letter leaves it null. The time is what the next touch measures [armingDistance]
     * from.
     */
    private fun dispatchTap(kr: KeyRect) {
        lastCharTapPoint = if (kr.key.type == KeyType.CHAR &&
            kr.key.output.length == 1 && kr.key.output[0].isLetter()
        ) PointF(downX, downY) else null
        lastTapCommitAt = SystemClock.uptimeMillis()
        dispatchKey(kr.key)
    }

    private fun dispatchKey(key: Key) {
        when (key.type) {
            KeyType.CHAR, KeyType.COMMA, KeyType.PERIOD -> {
                val out = if (model.isLetters && shiftState != ShiftState.OFF &&
                    key.output.length == 1 && key.output[0].isLetter()
                ) key.output.uppercase() else key.output
                listener?.onText(out)
            }
            KeyType.SPACE -> listener?.onText(" ")
            else -> listener?.onKey(key.type)
        }
    }

    // ------------------------------------------------------------------
    // P4: TalkBack — every key is a virtual view, announced with a proper
    // name and clickable through the accessibility focus ("explore by
    // touch" plus double-tap to activate).
    // ------------------------------------------------------------------

    private fun describeKey(key: Key): String = when (key.type) {
        KeyType.DELETE -> "Delete"
        KeyType.SHIFT -> when (shiftState) {
            ShiftState.OFF -> "Shift"
            ShiftState.ON -> "Shift, on"
            ShiftState.CAPS_LOCK -> "Caps lock, on"
        }
        KeyType.ENTER -> "Enter"
        KeyType.SPACE -> "Space"
        KeyType.EMOJI -> "Emoji"
        KeyType.MODE_SYMBOLS -> "Symbols"
        KeyType.MODE_SYMBOLS2 -> "More symbols"
        KeyType.MODE_LETTERS -> "Letters"
        KeyType.MODE_SELECT -> "Cursor and selection"
        KeyType.CURSOR_LEFT -> "Move left"
        KeyType.CURSOR_RIGHT -> "Move right"
        KeyType.CURSOR_UP -> "Move up"
        KeyType.CURSOR_DOWN -> "Move down"
        KeyType.CURSOR_HOME -> "Start of line"
        KeyType.CURSOR_END -> "End of line"
        KeyType.SELECT_TOGGLE -> "Selection mode"
        KeyType.SELECT_ALL -> "Select all"
        KeyType.CUT -> "Cut"
        KeyType.COPY -> "Copy"
        KeyType.PASTE -> "Paste"
        KeyType.COMMA -> "Comma"
        KeyType.PERIOD -> "Period"
        KeyType.CHAR -> key.output
    }

    private val keyTouchHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            for (i in keyRects.indices) {
                if (keyRects[i].rect.contains(x, y)) return i
            }
            return HOST_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (i in keyRects.indices) virtualViewIds.add(i)
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val kr = keyRects.getOrNull(virtualViewId) ?: return
            node.className = "android.widget.Button"
            node.contentDescription = describeKey(kr.key)
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(
                android.graphics.Rect(
                    kr.rect.left.toInt(), kr.rect.top.toInt(),
                    kr.rect.right.toInt(), kr.rect.bottom.toInt()
                )
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: android.os.Bundle?
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val kr = keyRects.getOrNull(virtualViewId) ?: return false
            feedback()
            dispatchKey(kr.key)
            return true
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, keyTouchHelper)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        keyTouchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * The key a touch-down means. The point decides it alone: every point of the grid
     * belongs to exactly one key, so what is drawn is what gets typed, and the margin
     * beside an outer key is that key's rather than a neighbour's from another row.
     */
    private fun findKey(x: Float, y: Float): KeyRect? {
        val i = hitMap?.keyAt(x, y) ?: return null
        return keyRects.getOrNull(i)
    }

    // ---------------------------------------------------------------
    // Long-press alternates popup
    // ---------------------------------------------------------------

    /** The popup's background: Gboard's white chip — a rounded slab for the
     *  tile rows plus a small tail dropping from item 0's column down toward
     *  the pressed key. The tail is why the tile row keeps a few dp of
     *  bottom padding. */
    private inner class PopupChipDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tail = Path()
        /** Tail anchor in container coordinates, set after the measure pass. */
        var notchX = 0f

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            paint.color = theme.popupBackground
            val tailH = dp(7).toFloat()
            val bodyBottom = (b.bottom - tailH).coerceAtLeast(b.top.toFloat())
            val radius = dp(10).toFloat()
            canvas.drawRoundRect(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), bodyBottom,
                radius, radius, paint
            )
            if (bodyBottom < b.bottom) {
                val halfW = dp(6).toFloat()
                tail.reset()
                tail.moveTo(notchX - halfW, bodyBottom - 1f)
                tail.lineTo(notchX, b.bottom.toFloat())
                tail.lineTo(notchX + halfW, bodyBottom - 1f)
                tail.close()
                canvas.drawPath(tail, paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun fireLongPress() {
        // Off the window there is no token to show a popup with, and no lift to come. Enter's
        // action would send the message with nobody holding the key.
        if (!isAttachedToWindow) return
        val kr = pressedKey ?: return
        if (kr.key.type == KeyType.ENTER) {
            // Enter now types a newline in fields that accept one, so the
            // field's own action (send, search, go) needs somewhere else to
            // live. Holding the key is the obvious place and costs nothing:
            // Enter has no popup characters to compete with.
            longPressFired = true
            feedback()
            listener?.onEnterLongPress()
            return
        }
        if (kr.key.type == KeyType.SPACE && kr.key.popup.isEmpty()) {
            // P2: long-press space = IME switcher (every other long-press
            // target has popup chars; space never did, so this gesture was
            // simply dead).
            longPressFired = true
            feedback()
            listener?.onSpaceLongPress()
            return
        }
        if (kr.key.popup.isEmpty()) return
        longPressFired = true
        showPopup(kr)
    }

    private fun showPopup(kr: KeyRect) {
        dismissPopup()
        // The default alternative is the key's OWN label, Gboard-style: the
        // first tile is the plain letter and the accents (or digit hints on
        // the top row) fan out after it. Hold-and-release types the same
        // letter a tap would, so nothing about the popup can surprise a key
        // that only got held a moment too long.
        val plain = if (model.isLetters && shiftState != ShiftState.OFF) kr.key.label.uppercase()
        else kr.key.label
        val items = listOf(plain) + kr.key.popup
        popupItems = items
        // A GRID, Gboard-style, not a strip: the period key alone carries
        // sixteen marks, and a sixteen-wide strip runs off both screen
        // edges. Rows of up to POPUP_COLUMNS, the FIRST row nearest the key
        // so item 0 sits under the finger and the rest fan out from there.
        // Keys on the right half fan LEFTWARD (each row laid out reversed)
        // so the strip has room instead of being clamped against the edge
        // with item 0 far from the finger.
        val leftward = kr.rect.centerX() > width / 2f
        val chip = PopupChipDrawable()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = chip
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        // Labels are created in ITEM order (so an index into popupLabels is
        // an index into kr.key.popup) and attached to their row in VISUAL
        // order; rows are attached last-chunk-first so chunk 0 is the bottom.
        val labels = ArrayList<TextView>(items.size)
        val rowViews = items.chunked(POPUP_COLUMNS).map { row ->
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val rowLabels = row.map { text ->
                TextView(context).apply {
                    this.text = text
                    setTextColor(theme.text)
                    textSize = 20f * textScale
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
            }
            labels += rowLabels
            for (tv in if (leftward) rowLabels.asReversed() else rowLabels) rowView.addView(tv)
            rowView
        }
        for (rowView in rowViews.asReversed()) {
            container.addView(
                rowView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = if (leftward) Gravity.END else Gravity.START }
            )
        }
        popupLabels = labels
        popupSelected = 0
        popupFingerMoved = false
        popupLabels[0].setBackgroundColor(theme.accent)
        popupLabels[0].setTextColor(onAccentColor())

        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        )
        popup.elevation = dp(4).toFloat()
        popupWindow = popup
        // Where the finger was when the popup opened — the origin the sticky
        // threshold measures against.
        val loc = locScratch
        getLocationOnScreen(loc)
        popupOriginX = loc[0] + downX
        popupOriginY = loc[1] + downY

        container.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val r = kr.rect
        // Anchor ITEM 0 under the key, not the middle of the popup.
        //
        // Centring the whole strip put the finger over the middle entry while
        // the highlight sat on item 0, so the first move event snapped the
        // selection to whatever happened to be under the finger. On the top
        // row that meant long-pressing "e" and releasing gave an accent
        // instead of the letter. Item 0's horizontal position inside the
        // container depends on the fan direction, so it is read back after
        // the measure pass rather than assumed to be the left edge. The
        // chip's tail hangs from that same point, Gboard-style.
        val first = popupLabels[0]
        val item0Centre = if (leftward) {
            container.measuredWidth - container.paddingRight - first.measuredWidth / 2f
        } else {
            container.paddingLeft + first.measuredWidth / 2f
        }
        chip.notchX = item0Centre
        // showAtLocation's offsets are measured from the top-left of the IME
        // WINDOW (the root view's frame), not from this view's top-left — the
        // comment that lived here had the frame wrong, which is how the popup
        // ended up drawn on top of the suggestion bar for every row. Adding
        // the view's own full screen Y was the old "bottom edge" bug for the
        // same reason: both mistakes amount to missing the view-to-window
        // delta. The same delta the press preview converts through when it
        // draws on rootView's overlay. Fetched fresh here rather than reusing
        // the popup-origin lookup so a future edit can't desynchronise them.
        getLocationOnScreen(loc)
        rootView.getLocationOnScreen(rootLocScratch)
        val x = (loc[0] - rootLocScratch[0] + r.centerX() - item0Centre)
            .roundToInt()
            .coerceIn(0, max(0, width - container.measuredWidth))
        val yOff = (loc[1] - rootLocScratch[1] + r.top - container.measuredHeight - dp(6)).toInt()
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, yOff)
        // Scale-in out of the key, Gboard-style: the chip's tail end is the
        // pivot, so it grows from the key it belongs to. A slide onto the
        // next key rebuilds the popup (dismissPopup above) and the new chip
        // animates from its own key — the animations never stack. With the
        // system animator scale at 0 (reduced/off) the chip appears fully
        // formed instead.
        val popupInMs = (POPUP_IN_MS * animatorScale()).toLong()
        if (popupInMs > 0L) {
            container.pivotX = item0Centre
            container.pivotY = container.measuredHeight.toFloat()
            container.scaleX = 0.82f
            container.scaleY = 0.82f
            container.alpha = 0f
            container.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(popupInMs)
                .setInterpolator(POPUP_IN_INTERPOLATOR)
                .start()
        }
        // The popup replaces the press preview: longPressFired flipped above,
        // and this repaint is what hides the overlay pill.
        invalidate()
    }

    /** Readable text color on the accent (yellow accent needs black, the
     *  usual dark accents need white). */
    private fun onAccentColor(): Int {
        val c = theme.accent
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return if ((0.299 * r + 0.587 * g + 0.114 * b) < 128) Color.WHITE else Color.BLACK
    }

    private fun updatePopupSelection(rawX: Float, rawY: Float) {
        val container = popupWindow?.contentView as? LinearLayout ?: return
        // The default sticks until the finger deliberately moves. Anchoring
        // item 0 under the key handles the common case, but a popup near the
        // screen edge gets clamped and item 0 ends up beside the finger rather
        // than under it — without this, the tiniest drift would still steal the
        // selection. Below the threshold, "hold and release" always yields the
        // first alternate, which on the top row is the digit.
        if (popupOriginX >= 0f && abs(rawX - popupOriginX) < popupStickyPx &&
            abs(rawY - popupOriginY) < popupStickyPx
        ) return
        popupFingerMoved = true
        container.getLocationOnScreen(locScratch)
        val localX = rawX - locScratch[0]
        val localY = rawY - locScratch[1]
        // Nearest cell centre in the grid. The finger is BELOW the popup
        // (it sits above the key), so the vertical term is measured against
        // the popup's bottom row for anything under it: a finger that has
        // only slid sideways along the key row selects along the bottom
        // row, and moving up past it selects the row above.
        var best = -1
        var bestDist = Float.MAX_VALUE
        val bottomRowY = (popupLabels[0].parent as View).let { it.top + it.height / 2f }
        val fy = min(localY, bottomRowY)
        popupLabels.forEachIndexed { i, tv ->
            val row = tv.parent as View
            val cx = row.left + tv.left + tv.width / 2f
            val cy = row.top + tv.height / 2f
            val dx = localX - cx
            val dy = fy - cy
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (best != popupSelected && best >= 0) {
            popupLabels.forEachIndexed { i, tv ->
                if (i == best) {
                    tv.setBackgroundColor(theme.accent)
                    tv.setTextColor(onAccentColor())
                } else {
                    tv.setBackgroundColor(Color.TRANSPARENT)
                    tv.setTextColor(theme.text)
                }
            }
            popupSelected = best
        }
    }

    private fun commitPopupSelection() {
        // CRASH FIX: coerceIn(0, size - 1) builds an EMPTY range when the key
        // has no popup entries at all (size 0 -> max = -1), which throws
        //   IllegalArgumentException: Cannot coerce value to an empty range
        // Reachable whenever a long-press lands on a key without alternates.
        if (pressedKey == null || popupItems.isEmpty()) return
        val idx = popupSelected.coerceIn(0, popupItems.size - 1)
        val text = popupItems.getOrNull(idx) ?: return
        val out = if (model.isLetters && shiftState != ShiftState.OFF) text.uppercase() else text
        listener?.onText(out)
        if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
    }

    private fun dismissPopup() {
        // Stop a scale-in mid-flight so a recycled content view never holds
        // a running animator; the window going away makes the rest moot.
        popupWindow?.contentView?.animate()?.cancel()
        popupWindow?.dismiss()
        popupWindow = null
        popupItems = emptyList()
        popupOriginX = -1f
        popupOriginY = -1f
    }

    // ---------------------------------------------------------------
    // Feedback + helpers
    // ---------------------------------------------------------------

    private fun feedback() {
        // Customizable per-key feedback (vibration intensity + sound pack).
        // Skip everything when both channels are off — the cheap common case.
        if (keyFeedback.vibLevel <= 0 && keyFeedback.soundPack <= 0) return
        keyFeedback.keyDown()
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A tick cleared below never told anyone about these rects, so let the next
        // computeKeyRects report them even if they come out the same.
        if (handler.hasCallbacks(geometryReadyTick)) notifiedRectsHash = 0
        // Its end is one of the callbacks cleared here, and it must not be up on the way back.
        previewLingerKey = null
        handler.removeCallbacksAndMessages(null)
        dismissPopup()
        clearPressPreviewOverlay()
        // Three view-sized bitmaps are worth releasing when the keyboard goes
        // away: the IME process is long-lived and usually idle, and held
        // bitmaps are memory pressure on the entire system. The page-fade
        // snapshot is a fourth of the same size; without this a hide landing
        // mid-crossfade (or the emoji panel opening from a page key) pins it
        // until the next setKeyboard.
        endPageTransition()
        keysLayer?.recycle(); keysLayer = null
        bgLayer?.recycle(); bgLayer = null
        liveBg.release()
        // The SoundPool leaks otherwise: one pool per KeyboardView, never
        // freed, in a process that builds a new view per configuration change.
        keyFeedback.release()
    }

    /**
     * Safety net for the whole cached-layer scheme.
     *
     * An IME view survives window hide/show cycles without ever being
     * detached, which is precisely how a stale cache goes unnoticed. Throwing
     * the layers away whenever the window becomes visible again costs one
     * rebuild per keyboard opening — unmeasurable against the thousands of
     * frames that follow — and makes "the keys are invisible until I refresh"
     * unreachable by construction, including for causes I have not thought of.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) markLayersDirty()
        // A hidden window hears no lift, so nothing held may run on behind it.
        if (visibility != VISIBLE) cancelTouch()
    }

    private companion object {
        /** Gboard's popup width: the period key's sixteen marks are two
         *  rows of eight. */
        private const val POPUP_COLUMNS = 8

        /** About 17 s of one finger at 120 Hz. A stroke that fills it gets the decoder's fallback. */
        private const val RAW_SAMPLE_CAPACITY = 2048

        /** How long a key takes to press and to settle: 3 to 4 frames, since a key that is
         *  still moving when the next one lands reads as lag at typing speed. */
        private const val PRESS_ANIM_MS = 64f

        /** Page-change crossfade length. Short enough to read as a swap, long
         *  enough not to chase the finger to the next key. Scaled by the
         *  system animator duration scale at swap time. */
        private const val PAGE_TRANSITION_MS = 150f

        /** Popup scale-in length — under the 150 ms the eye reads as instant. */
        private const val POPUP_IN_MS = 120f

        /** Fast-out curve for the popup's scale-in: most of the growth lands
         *  in the first half so the chip feels like it pops, not a swell. */
        private val POPUP_IN_INTERPOLATOR: android.view.animation.Interpolator =
            android.view.animation.PathInterpolator(0.2f, 0f, 0.25f, 1f)

        private const val PREVIEW_LINGER_MS = 60L

        /** A long press this far past due has sat behind a stall, and moves may be queued that were never read. Two
         *  frames, so that an ordinary busy frame delays nobody. A proposal, not measured on real fingers. */
        private const val LONG_PRESS_LATE_MS = 32L

        /** How long that look waits for a frame before a long press goes ahead without one. */
        private const val LONG_PRESS_FRAME_WAIT_MS = 500L

        /** How close to a glide's lift a second finger must have landed to count as the next
         *  touch. Anything older is taken for a thumb at rest. A proposal, not measured. */
        private const val LATE_FINGER_MS = 200L

        /** I/O-free placeholder theme, replaced by setTheme() before the view
         *  is ever shown. Mirrors KeyboardTheme.fromPalette for the built-in
         *  Mid-Century palette (fromPalette itself is private to the theme). */
        val FALLBACK_THEME: KeyboardTheme = run {
            val p = KeyboardTheme.EXTRA_PALETTES.first { it.id == "midcentury" }
            val r = (p.bg shr 16) and 0xFF
            val g = (p.bg shr 8) and 0xFF
            val b = p.bg and 0xFF
            KeyboardTheme(
                background = p.bg, key = p.key, keyFunctional = p.keyFunc,
                text = p.text, textSecondary = p.textSec, accent = p.accent,
                divider = p.textSec, trail = p.accent, popupBackground = p.popupBg,
                isDark = (0.299 * r + 0.587 * g + 0.114 * b) < 128,
                keyRadiusDp = p.radiusDp, keyText = p.keyText
            )
        }
    }
}
