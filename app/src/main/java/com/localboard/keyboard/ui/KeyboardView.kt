package com.localboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
    var hapticEnabled = true
    var soundEnabled = false

    /** Customizable per-key feedback (vibration level, sound pack, volume).
     *  The legacy hapticEnabled/soundEnabled flags above are kept as a cheap
     *  master gate — when both are false, feedback() skips everything. */
    val keyFeedback = KeyFeedback(context)

    /** Animation level: 0=off, 1=reduced, 2=full. Drives the press-preview
     *  pop-in animation and the glide-trail fade speed. */
    var animLevel = 2
        set(v) { field = v.coerceIn(0, 2); trail?.fadeSpeed = trailFadeSpeed(field) }

    /** Trail fade speed for an animation level: 0=off-feel (near-instant
     *  fade), 1=reduced, 2=full. Shared by the animLevel setter and setTheme,
     *  which builds a fresh trail and must not drop the preference. */
    private fun trailFadeSpeed(level: Int): Float = when (level) { 0 -> 2f; 1 -> 1.4f; else -> 1f }

    /** Key press effect: 0=Lift (grow+accent), 1=Bounce (overshoot wobble),
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
    var onKeyPressed: (() -> Unit)? = null

    /** Floor on how far the layout fit may shrink a key, as a fraction of its
     *  nominal width. A key that fits the hand but is too narrow to hit is a
     *  worse keyboard than one that is slightly off-centre. */
    private val MIN_KEY_FRACTION = 0.55f

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

    // Layout metrics (mutable: user height scale)
    private var keyHeightPx = dp(56)
    private var textScale = 1f
    // Gaps are user-tunable: tighter gaps mean bigger key faces at the same
    // keyboard height, which is the cheapest possible fix for "cramped".
    private var keyGap = dp(4.5f)
    private var rowGap = dp(6)
    private val sidePadding = dp(3)
    private val topPadding = dp(8)
    private val bottomPadding = dp(8)
    private var cornerRadius = dp(8).toFloat()
    private val keyRects = ArrayList<KeyRect>()

    // ── One-handed mode ──────────────────────────────────────────────────
    // On a phone the size of an S22 Ultra the far edge of the keyboard is
    // genuinely out of thumb reach. One-handed mode narrows the key grid and
    // docks it to one side, leaving a gutter on the other. The grid itself is
    // unchanged — only the usable width and its origin move — so touch
    // targets, swipe geometry and the spatial corrector all stay consistent.

    /** 0 = full width, 1 = docked right, 2 = docked left. */
    private var oneHandedMode = 0
    private var oneHandedWidth = 0.82f

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

    /** Usable key-grid width after one-handed narrowing. */
    private fun gridWidth(): Float =
        if (oneHandedMode == 0) (width - sidePadding * 2).toFloat()
        else (width - sidePadding * 2) * oneHandedWidth

    /** Left edge of the key grid (the dock gutter goes on the other side). */
    private fun gridLeft(): Float {
        if (oneHandedMode == 0) return sidePadding.toFloat()
        val slack = (width - sidePadding * 2) * (1f - oneHandedWidth)
        return if (oneHandedMode == 1) sidePadding + slack else sidePadding.toFloat()
    }

    /** Scales the gap between keys. 1.0 = stock 4.5dp; lower = bigger keys. */
    fun setKeySpacing(scale: Float) {
        val s = scale.coerceIn(0.4f, 1.8f)
        val newKeyGap = dp(4.5f * s)
        val newRowGap = dp(6f * s)
        if (newKeyGap == keyGap && newRowGap == rowGap) return
        keyGap = newKeyGap
        rowGap = newRowGap
        if (width > 0) computeKeyRects()
        requestLayout()
        invalidate()
    }

    /** Scales key height and label text. 1.0 = default. */
    fun setMetrics(heightScale: Float, textScale: Float) {
        // P3: form-factor adaptation — landscape gets shorter keys (vertical
        // space is precious there), tablets slightly taller ones.
        val cfg = resources.configuration
        var formFactor = 1f
        if (cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            formFactor *= 0.85f
        }
        if (cfg.smallestScreenWidthDp >= 600) formFactor *= 1.12f
        // P4: key LABELS scale with the system font size (sp), but key
        // HEIGHTS didn't — large-text users got clipped labels. Grow the
        // rows with fontScale, capped so the board still fits.
        if (cfg.fontScale > 1f) formFactor *= cfg.fontScale.coerceAtMost(1.3f)
        keyHeightPx = dp(56 * heightScale * formFactor)
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
    /** P2: the current touch is scrubbing the cursor along the space bar —
     *  suppress the space dispatch on finger-up. */
    private var spaceCursorMode = false
    private var spaceCursorLastX = 0f
    /** The only pointer we track; extra fingers never move our coordinates. */
    private var activePointerId = -1
    private var lastSwipeProgressAt = 0L
    /** Raw points from finger-down, so a glide that arms late still starts at
     *  the true gesture origin instead of mid-word. */
    private val pendingPoints = ArrayList<PointF>(40)
    private var keyOffsetXPx = 0f
    private var keyOffsetYPx = 0f
    private var pressAnimStart = 0L
    private var previewShownAt = 0L
    private var releaseAnimKey: KeyRect? = null
    private var releaseAnimStart = 0L
    private var trailEnabled = true
    private var swipeActivationScale = 1.0f
    private var longPressMs = 380L

    /** User-tunable glide arming distance (multiplies the base threshold). */
    fun setSwipeActivationScale(f: Float) { swipeActivationScale = f.coerceIn(0.6f, 1.4f) }
    fun setTrailEnabled(b: Boolean) { trailEnabled = b; invalidate() }
    fun setLongPressMs(ms: Int) { longPressMs = ms.coerceIn(250, 600).toLong() }

    /** Shift the whole key grid (finger-fit calibration), in dp. */
    fun setKeyOffsets(xDp: Float, yDp: Float) {
        keyOffsetXPx = dp(xDp).toFloat()
        keyOffsetYPx = dp(yDp).toFloat()
        if (width > 0) computeKeyRects()
        // The Y offset is part of the measured height (see onMeasure).
        requestLayout()
        invalidate()
    }
    private var downX = 0f
    private var downY = 0f
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
    private var popupSelected = -1

    /** Scratch for getLocationOnScreen — reused; one allocation per MOVE
     *  event during popup drag was coming from these lookups. */
    private val locScratch = IntArray(2)

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

    private val longPressRunnable = Runnable { fireLongPress() }
    private val deleteRepeatRunnable = object : Runnable {
        override fun run() {
            deleteRepeatCount++
            if (deleteRepeatCount > 8) {
                // Held long: accelerate into whole-word deletion.
                listener?.onWordDelete()
                handler.postDelayed(this, 110)
            } else {
                listener?.onKey(KeyType.DELETE)
                handler.postDelayed(this, 55)
            }
        }
    }

    init {
        setBackgroundColor(theme.background)
    }

    /** Continuous per-key touch adaptation (owned by the service). */
    var tapModel: com.localboard.keyboard.util.TapLearningModel? = null

    /** A4: screen-px tap point of the most recent letter dispatch. The IME
     *  consumes (and nulls) it while building its parallel tap trace. */
    @Volatile
    var lastCharTapPoint: PointF? = null

    fun setKeyboard(model: KeyboardModel) {
        releaseAnimKey = null
        this.model = model
        // BUGFIX: when the new model has the same row count as the old one,
        // onSizeChanged never fires and key rects were never recomputed —
        // the symbols pages looked "dead". Recompute explicitly.
        // computeKeyRects() already marks the layers dirty; when the view has
        // no size yet there is no layer to invalidate.
        if (width > 0 && height > 0) computeKeyRects() else invalidate()
        requestLayout()
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

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = model.rows.size
        // keyOffsetYPx shifts every row down in computeKeyRects(); without
        // reserving that space here a positive Y calibration pushes the bottom
        // row past the measured edge, clipping it and deadening its touches.
        val height = (topPadding + bottomPadding + rows * keyHeightPx +
            (rows - 1) * rowGap + max(0, keyOffsetYPx.roundToInt()))
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeKeyRects()   // marks the layers dirty itself
    }


    /**
     * Per-key layout fit, in dp, from the calibration typing test: where this
     * user's finger actually lands relative to each key's nominal centre.
     *
     * Unlike [tapModel], which nudges the hit-test target while leaving the
     * drawn key where it was, this MOVES THE KEY. The two must not both be
     * applied to the same measurement or the shift lands twice, so baking a
     * fit into the layout clears the tap model — from then on the model
     * measures residual drift from the new positions.
     */
    var keyFit: Map<String, Pair<Float, Float>> = emptyMap()
        set(v) { field = v; if (width > 0) computeKeyRects(); invalidate() }

    private fun computeKeyRects() {
        keyRects.clear()
        geometry.clear()
        keyTouchHelper.invalidateRoot()
        val usableWidth = gridWidth()
        val originX = gridLeft()
        var y = topPadding + keyOffsetYPx
        val density = resources.displayMetrics.density
        var prevRowBottom = Float.NEGATIVE_INFINITY

        for (row in model.rows) {
            val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat()
            val gaps = keyGap * (row.keys.size - 1)
            val edgePad = usableWidth * row.edgePadding
            val keyArea = usableWidth - gaps - edgePad * 2
            val originRowX = originX + edgePad + keyOffsetXPx

            // ── nominal boundaries ────────────────────────────────────────
            // Laid out first as a list of edges so the fit can move the edges
            // BETWEEN keys rather than the keys themselves. Nudging each key
            // independently would open gaps and overlaps; moving the shared
            // boundary keeps the row exactly tiled while both neighbours'
            // centres travel toward where the finger lands.
            val n = row.keys.size
            val widths = FloatArray(n) { keyArea * (row.keys[it].weight / totalWeight) }
            val edges = FloatArray(n + 1)
            edges[0] = originRowX
            for (i in 0 until n) edges[i + 1] = edges[i] + widths[i] + keyGap

            val fitX = FloatArray(n)
            var dySum = 0f
            var dyCount = 0
            if (keyFit.isNotEmpty()) {
                for (i in 0 until n) {
                    val k = row.keys[i]
                    if (k.type != KeyType.CHAR || k.output.length != 1) continue
                    val off = keyFit[k.output] ?: continue
                    fitX[i] = off.first * density
                    dySum += off.second
                    dyCount++
                }
                // Interior edges only: the row's outer edges stay put so the
                // keyboard keeps its margins and total width.
                for (j in 1 until n) {
                    val shift = (fitX[j - 1] + fitX[j]) / 2f
                    val lo = edges[j - 1] + widths[j - 1] * MIN_KEY_FRACTION
                    val hi = edges[j + 1] - widths[j] * MIN_KEY_FRACTION - keyGap
                    edges[j] = (edges[j] + shift).coerceIn(minOf(lo, hi), maxOf(lo, hi))
                }
            }

            // Whole-row vertical fit. Rows move together so the grid still
            // reads as rows; clamped against the previous row's bottom so a
            // large offset can never make two rows collide.
            var rowY = y
            if (dyCount > 0) {
                val dy = (dySum / dyCount) * density
                rowY = (y + dy).coerceAtLeast(prevRowBottom + keyGap)
            }

            for (i in 0 until n) {
                val key = row.keys[i]
                val left = edges[i]
                val right = edges[i + 1] - keyGap
                val rect = RectF(left, rowY, right, rowY + keyHeightPx)
                keyRects += KeyRect(key, rect)

                // Register letters with the geometry for swipe decoding.
                if (key.type == KeyType.CHAR && key.output.length == 1 &&
                    key.output[0].isLetter()
                ) {
                    geometry.register(key.output[0].lowercaseChar(), rect)
                }
            }
            prevRowBottom = rowY + keyHeightPx
            y += keyHeightPx + rowGap
        }
        geometry.finishLayout()
        // Any geometry change invalidates the cached grid. Doing this here
        // rather than at each call site means a new setter that moves keys
        // can't silently leave a stale bitmap on screen — which is exactly
        // the failure mode a render cache invites.
        markLayersDirty()
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

    /** Drives the press-effect and preview pop-in animations, one key's
     *  dirty rect at a time. */
    private val pressAnimTick = Runnable { invalidateKeyArea(liveKey()) }

    /** Drives the trail fade, repainting only where the trail is. */
    private val trailTick = Runnable { invalidateTrailArea() }

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
        // Superset of the painted region: a margin for antialiasing, plus the
        // preview bubble that floats above a character key.
        val pad = dp(4).toFloat()
        val bubble = if (previewEnabled && kr.key.type == KeyType.CHAR) dp(64).toFloat() else 0f
        invalidate(
            (keyAreaScratch.left - pad).toInt() - 1,
            (keyAreaScratch.top - pad - bubble).toInt() - 1,
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

        val wantsBgFrame = drawBackground(canvas)
        if (wantsBgFrame) stats.backgroundFrames++

        // The resting key grid comes from the cached layer. The one key being
        // pressed or released is cut out of the blit and drawn live over the
        // background, so its scale/glow animation is correct even when it
        // shrinks below its resting size.
        val live = liveKey()
        val layer = ensureKeysLayer()

        // Cut the live key out of the cached grid, then draw it for real. The
        // cut-out matters: the bounce effect dips BELOW resting size, so a
        // cached key underneath would peek out around the edges.
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

        // Press animation keeps asking for frames, but only over the key's own
        // dirty rect — this used to repaint the entire grid at 60 fps.
        if (effectivePressEffect != 3 &&
            ((pressedKey != null && System.currentTimeMillis() - pressAnimStart < 140) ||
                releaseAnimKey != null)
        ) {
            // Reused Runnable: a lambda here would allocate once per animation
            // frame, ~9 per keypress.
            removeCallbacks(pressAnimTick)
            postDelayed(pressAnimTick, 16)
        }

        // Press preview bubble above the tapped character key.
        pressedKey?.let { kr ->
            if (previewEnabled && !isSwiping && kr.key.type == KeyType.CHAR && !longPressFired) {
                drawPressPreview(canvas, kr)
            }
        }

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
        // Bounce: bigger overshoot with a wobble that settles on release.
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
                    val t = ((now - pressAnimStart) / 110f).coerceIn(0f, 1f)
                    scale = 1f + 0.09f * t
                    lift = t * 0.7f
                } else if (releaseAnimKey === kr) {
                    val t = ((now - releaseAnimStart) / 240f).coerceIn(0f, 1f)
                    if (t >= 1f) {
                        releaseAnimKey = null
                    } else {
                        // damped wobble: overshoots, dips below 1, settles
                        val decay = (1f - t) * (1f - t)
                        scale = 1f + 0.09f * decay *
                            kotlin.math.cos((t * 2.5f * Math.PI).toFloat())
                        lift = decay * 0.5f
                    }
                }
            }
            2 -> { // glow
                if (pressed) {
                    val t = ((now - pressAnimStart) / 90f).coerceIn(0f, 1f)
                    glow = t
                } else if (releaseAnimKey === kr) {
                    val t = ((now - releaseAnimStart) / 160f).coerceIn(0f, 1f)
                    if (t >= 1f) releaseAnimKey = null else glow = 1f - t
                }
            }
            else -> { // lift (default)
                if (pressed) {
                    val t = ((now - pressAnimStart) / 90f).coerceIn(0f, 1f)
                    scale = 1f + 0.055f * t
                    lift = t
                } else if (releaseAnimKey === kr) {
                    val t = ((now - releaseAnimStart) / 140f).coerceIn(0f, 1f)
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

    /** Scratch for the press-preview bubble — never allocate per frame. */
    private val previewRectScratch = RectF()

    private fun drawPressPreview(canvas: Canvas, kr: KeyRect) {
        val r = kr.rect
        val pw = max(r.width() * 1.15f, dp(34).toFloat())
        val ph = dp(52).toFloat()
        val left = min(max(r.centerX() - pw / 2, dp(2).toFloat()), width - pw - dp(2))
        val top = r.top - ph - dp(4)
        if (top < 0) return
        val previewRect = previewRectScratch
        previewRect.set(left, top, left + pw, top + ph)

        // Pop-in duration follows the animation-level preference; at "off"
        // the bubble appears instantly (and the extra invalidates stop too).
        val animMs = when (animLevel) { 0 -> 0f; 1 -> 70f; else -> 110f }
        val pop = if (animMs <= 0f) 1f
            else ((System.currentTimeMillis() - previewShownAt) / animMs).coerceIn(0.2f, 1f)
        canvas.save()
        canvas.scale(pop, pop, r.centerX(), r.bottom)
        previewPaint.color = theme.popupBackground
        canvas.drawRoundRect(previewRect, dp(8).toFloat(), dp(8).toFloat(), previewPaint)

        previewTextPaint.color = theme.text
        previewTextPaint.textSize = sp(26) * textScale
        val label = if (shiftState != ShiftState.OFF && model.isLetters) {
            kr.key.label.uppercase()
        } else kr.key.label
        val baseline = previewRect.centerY() - (previewTextPaint.descent() + previewTextPaint.ascent()) / 2
        canvas.drawText(label, previewRect.centerX(), baseline, previewTextPaint)
        canvas.restore()
        // Pop-in frames repaint only the key's dirty rect (invalidateKeyArea
        // already budgets for the bubble above it), not the whole grid.
        if (pop < 1f) {
            removeCallbacks(pressAnimTick)
            postDelayed(pressAnimTick, 16)
        }
    }

    // ---------------------------------------------------------------
    // Touch handling
    // ---------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                handleDownAt(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) handleMove(event, idx)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    handleUpAt(event.getX(event.actionIndex), event.getY(event.actionIndex))
                    activePointerId = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                handleUpAt(event.x, event.y)
                activePointerId = -1
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
        val kr = pressedKey
        barSelectMode = false
        if (isSwiping) {
            val decoded = listener?.onSwipePath(ArrayList(swipePoints)) == true
            val consumedAsSwipe = decoded || swipePathLen >= width * 0.30f
            if (!consumedAsSwipe && kr != null && !longPressFired && kr.key.type != KeyType.DELETE) {
                dispatchKey(kr.key)
            }
            listener?.onSwipeEnded()
            glideWord = null
            trail?.finish()
            invalidateTrailArea()
            swipePoints.clear()
            swipePathLen = 0f
        } else if (kr != null && !longPressFired && !spaceCursorMode && kr.key.type != KeyType.DELETE) {
            dispatchKey(kr.key)
        }
        spaceCursorMode = false
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)
        dismissPopup()
        pressedKey = null
        activePointerId = e.getPointerId(e.actionIndex)
        handleDownAt(e.getX(e.actionIndex), e.getY(e.actionIndex))
    }

    private fun handleDownAt(x: Float, y: Float) {
        downX = x
        downY = y
        isSwiping = false
        longPressFired = false
        spaceCursorMode = false
        pendingPoints.clear()
        pendingPoints += PointF(x, y)
        pressAnimStart = System.currentTimeMillis()
        previewShownAt = pressAnimStart
        pressedKey = findKey(x, y)
        pressedKey?.let { kr ->
            feedback()
            when (kr.key.type) {
                KeyType.DELETE -> {
                    listener?.onKey(KeyType.DELETE)
                    deleteRepeatCount = 0
                    handler.postDelayed(deleteRepeatRunnable, 500)
                }
                else -> {
                    // P2: the space bar has no popup chars but still wants a
                    // long-press — it opens the IME switcher.
                    if (kr.key.popup.isNotEmpty() ||
                        kr.key.type == KeyType.SPACE ||
                        kr.key.type == KeyType.ENTER
                    ) {
                        handler.postDelayed(longPressRunnable, longPressMs)
                    }
                }
            }
            com.localboard.keyboard.util.FrameStats.keystrokes++
            liveBg.onKeyPress()
            // The nav-bar strip runs its own LiveBackground, so a reactive
            // style there needs the same signal or it would never react.
            onKeyPressed?.invoke()
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
        // per step (Gboard-style). Arms after a small dead zone so a resting
        // thumb's jitter never moves the cursor; starting the slide cancels
        // the pending long-press (IME switcher).
        if (kr.key.type == KeyType.SPACE && !longPressFired) {
            val sx = e.getX(idx)
            if (!spaceCursorMode && kotlin.math.abs(sx - downX) > dp(10)) {
                spaceCursorMode = true
                spaceCursorLastX = downX
                handler.removeCallbacks(longPressRunnable)
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

        // Start glide typing when the finger travels on the letter board.
        if (!isSwiping && swipeEnabled && model.isLetters &&
            kr.key.type == KeyType.CHAR && !longPressFired
        ) {
            val dx = e.getX(idx) - downX
            val dy = e.getY(idx) - downY
            val act = swipeActivation
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
                // point.
                val ex = e.getX(idx); val ey = e.getY(idx)
                if (pendingPoints.size < 400 &&
                    dist(pendingPoints.last(), ex, ey) >= minPointGapPx
                ) {
                    pendingPoints += PointF(ex, ey)
                }
            }
        }

        if (isSwiping) {
            val ex = e.getX(idx)
            val ey = e.getY(idx)
            // Swipe-up word selection: above the top edge is the suggestion
            // bar's strip. The glide freezes (points stop accruing, so the
            // frozen path stays decodable) and the finger drives the bar's
            // highlight instead — Gboard-style "slide up to pick a word".
            if (barSelectMode) {
                listener?.onSwipeBarHover((ex / width).coerceIn(0f, 1f))
                return
            }
            if (ey < -dp(2)) {
                barSelectMode = true
                glideWord = null
                trail?.finish()
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
                swipePathLen += dist(lastSampleX, lastSampleY, hx, hy)
                lastSampleX = hx; lastSampleY = hy
                if (dist(swipePoints.last(), hx, hy) >= minPointGapPx) swipePoints += PointF(hx, hy)
                trail?.add(hx, hy)
            }
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

    private fun handleUpAt(x: Float, y: Float) {
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
        if (isSwiping) {
            var consumedByBar = false
            if (barSelectMode) {
                barSelectMode = false
                // Lifted over the bar: commit whichever word the finger is on.
                // False (gap between words / empty slot) falls through to the
                // normal decode of the frozen path — no gesture is ever lost.
                consumedByBar = listener?.onSwipeBarPick((x / width).coerceIn(0f, 1f)) == true
            }
            if (!consumedByBar) {
                swipePathLen += dist(lastSampleX, lastSampleY, x, y)
                // The lift point is always kept regardless of spacing: it is
                // the end of the word and the decoder anchors on it.
                swipePoints += PointF(x, y)
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
                    dispatchKey(kr.key)
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
            recordTapLearning(kr, x, y)
            // A4: stash the raw tap point for the service's tap trace — the
            // joint spatial correction scores WHERE the finger landed, not
            // just which letter won the rect lookup. Consumed synchronously
            // by handleText; any non-letter dispatch leaves it null.
            lastCharTapPoint = if (kr.key.type == KeyType.CHAR &&
                kr.key.output.length == 1 && kr.key.output[0].isLetter()
            ) PointF(x, y) else null
            dispatchKey(kr.key)
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
        // Release animation is also one key's worth of pixels.
        if (stillValid) invalidateKeyArea(kr) else invalidate()
    }

    /** Record the tap for the per-key learning model (plain taps only). */
    private fun recordTapLearning(kr: KeyRect?, x: Float, y: Float) {
        val key = kr?.key ?: return
        if (key.type != KeyType.CHAR || key.output.length != 1) return
        val d = resources.displayMetrics.density
        tapModel?.record(key.output, (x - kr.rect.centerX()) / d, (y - kr.rect.centerY()) / d)
    }

    private fun cancelTouch() {
        activePointerId = -1
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(deleteRepeatRunnable)
        dismissPopup()
        barSelectMode = false
        spaceCursorMode = false
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

    /** Center of a letter key in dp (for calibration math), null if absent. */
    fun keyCenterDp(letter: Char): PointF? {
        for (kr in keyRects) {
            if (kr.key.type == KeyType.CHAR &&
                kr.key.output.equals(letter.toString(), ignoreCase = true)
            ) {
                val d = resources.displayMetrics.density
                return PointF(kr.rect.centerX() / d, kr.rect.centerY() / d)
            }
        }
        return null
    }

    private fun findKey(x: Float, y: Float): KeyRect? {
        // Stock feel first: the key whose rect contains the tap wins, always.
        // The per-key model is a NUDGE, not a re-targeting system — it votes
        // only when the tap MISSES every rect (edge slips and gap taps, which
        // is exactly where finger-habit errors live). Re-targeting every tap
        // to the nearest learned center made keys feel smaller and fights.
        for (kr in keyRects) {
            if (kr.rect.contains(x, y)) return kr
        }
        // The gaps between keys are visual only — a tap inside a gap belongs
        // to the nearer key (each key's hit area extends half a gap, the
        // AOSP midpoint rule). Gaps are ~10% of the keyboard width; treating
        // them as dead zones was eating taps on fast typing.
        val halfX = keyGap / 2f
        val halfY = rowGap / 2f
        var gapBest: KeyRect? = null
        var gapBestD = Float.MAX_VALUE
        for (kr in keyRects) {
            val r = kr.rect
            if (x >= r.left - halfX && x <= r.right + halfX &&
                y >= r.top - halfY && y <= r.bottom + halfY
            ) {
                val dx = x - r.centerX()
                val dy = y - r.centerY()
                val d = dx * dx + dy * dy
                if (d < gapBestD) { gapBestD = d; gapBest = kr }
            }
        }
        if (gapBest != null) return gapBest
        val tm = tapModel
        if (tm != null && tm.hasOffsets()) {
            val density = resources.displayMetrics.density
            var best: KeyRect? = null
            var bestD2 = Float.MAX_VALUE
            for (kr in keyRects) {
                if (kr.key.type != KeyType.CHAR || kr.key.output.length != 1) continue
                val off = tm.offsetFor(kr.key.output) ?: continue
                // Deadband: offsets under ~3dp are noise, not a habit.
                if (off.first * off.first + off.second * off.second < 9f) continue
                val cx = kr.rect.centerX() + off.first * density
                val cy = kr.rect.centerY() + off.second * density
                val dx = x - cx
                val dy = y - cy
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) { bestD2 = d2; best = kr }
            }
            // Miss-only and close to a learned landing zone: nudge it there.
            val keyW = if (width > 0) width / 10f else dp(44).toFloat()
            val threshold = keyW * 0.55f
            if (best != null && bestD2 < threshold * threshold) return best
        }
        // Forgiving bottom row: snap near-misses vertically.
        // Forgiving bottom row: snap near-misses vertically.
        for (kr in keyRects) {
            if (x >= kr.rect.left && x <= kr.rect.right &&
                abs(y - kr.rect.centerY()) < keyHeightPx * 0.75f
            ) return kr
        }
        // Fast sloppy taps often land in the gaps between keys: assign the tap
        // to the nearest key instead of dropping the letter.
        var best: KeyRect? = null
        var bestD2 = Float.MAX_VALUE
        val maxDx = keyGap * 0.9f
        for (kr in keyRects) {
            val dx = maxOf(kr.rect.left - x, 0f, x - kr.rect.right)
            val dy = maxOf(kr.rect.top - y, 0f, y - kr.rect.bottom)
            if (dx > maxDx) continue
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) { bestD2 = d2; best = kr }
        }
        return best
    }

    // ---------------------------------------------------------------
    // Long-press alternates popup
    // ---------------------------------------------------------------

    private fun fireLongPress() {
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
        val items = kr.key.popup
        // A GRID, Gboard-style, not a strip: the period key alone carries
        // sixteen marks, and a sixteen-wide strip runs off both screen
        // edges. Rows of up to POPUP_COLUMNS, the FIRST row nearest the key
        // so item 0 sits under the finger and the rest fan out from there.
        // Keys on the right half fan LEFTWARD (each row laid out reversed)
        // so the strip has room instead of being clamped against the edge
        // with item 0 far from the finger.
        val leftward = kr.rect.centerX() > width / 2f
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.popupBackground)
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
        // instead of the digit — the digit is item 0 precisely because it is
        // meant to be the default. Item 0's horizontal position inside the
        // container depends on the fan direction, so it is read back after
        // the measure pass rather than assumed to be the left edge.
        val first = popupLabels[0]
        val item0Centre = if (leftward) {
            container.measuredWidth - container.paddingRight - first.measuredWidth / 2f
        } else {
            container.paddingLeft + first.measuredWidth / 2f
        }
        val x = (r.centerX() - item0Centre)
            .roundToInt()
            .coerceIn(0, max(0, width - container.measuredWidth))
        val yOff = (r.top - container.measuredHeight - dp(6)).toInt()
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, yOff + locationOnScreenY())
    }

    private fun locationOnScreenY(): Int {
        getLocationOnScreen(locScratch)
        return locScratch[1]
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
        val kr = pressedKey ?: return
        // CRASH FIX: coerceIn(0, size - 1) builds an EMPTY range when the key
        // has no popup entries at all (size 0 -> max = -1), which throws
        //   IllegalArgumentException: Cannot coerce value to an empty range
        // Reachable whenever a long-press lands on a key without alternates.
        if (kr.key.popup.isEmpty()) return
        val idx = popupSelected.coerceIn(0, kr.key.popup.size - 1)
        val text = kr.key.popup.getOrNull(idx) ?: return
        val out = if (model.isLetters && shiftState != ShiftState.OFF) text.uppercase() else text
        listener?.onText(out)
        if (shiftState == ShiftState.ON) shiftState = ShiftState.OFF
    }

    private fun dismissPopup() {
        popupWindow?.dismiss()
        popupWindow = null
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
        handler.removeCallbacksAndMessages(null)
        dismissPopup()
        // Three view-sized bitmaps are worth releasing when the keyboard goes
        // away: the IME process is long-lived and usually idle, and held
        // bitmaps are memory pressure on the entire system.
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
    }

    private companion object {
        /** Gboard's popup width: the period key's sixteen marks are two
         *  rows of eight. */
        private const val POPUP_COLUMNS = 8

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
