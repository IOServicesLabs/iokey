package com.localboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.localboard.keyboard.R
import com.localboard.keyboard.engine.Suggestion

/**
 * The strip above the keys: three suggestion slots, a sparkle button for
 * "Write It For Me" when idle, and a confirm/cancel mode while a draft is
 * being composed.
 */
class PredictionBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onSuggestionPicked(text: String)
        fun onWifmRequested()
        fun onWifmConfirmed()
        fun onWifmDiscarded()
        /** Long-press on the sparkle: open the quick-prompt box. */
        fun onWifmLongPressed() {}
        /** Mic button: start dictation. */
        fun onVoiceRequested() {}
        /** Tap while listening/transcribing: stop and transcribe. */
        fun onVoiceStopTapped() {}
        /** P2: long-press on a word slot — the "remove this suggestion"
         *  gesture (Gboard-style drag-to-trash, simplified to a dialog). */
        fun onSuggestionLongPressed(text: String) {}
        /** P2: tap on the clipboard paste chip. */
        fun onClipboardChipTapped() {}
        /** Long-press the paste chip: open the full clip history. */
        fun onClipboardChipLongPressed() {}
    }

    enum class Mode { SUGGESTIONS, WIFM_CONFIRM, VOICE }

    var listener: Listener? = null

    private var theme: KeyboardTheme = KeyboardTheme.load(context, com.localboard.keyboard.util.Prefs(context))
    private var suggestions: List<Suggestion> = emptyList()
    private var mode: Mode = Mode.SUGGESTIONS
    private var wifmStreamingText: String = ""
    private var prevSuggestions: List<Suggestion> = emptyList()
    private var suggestionsChangedAt = 0L

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dividerPaint = Paint()
    private val bgPaint = Paint()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()
    private val slotRects = Array(3) { RectF() }
    /** Slot lit up by the swipe-up-to-select gesture; -1 = none. */
    private var gestureHighlightSlot = -1
    private val actionRect = RectF()   // sparkle (SUGGESTIONS) or ✓ (WIFM_CONFIRM) or ■ (VOICE)
    private val cancelRect = RectF()   // ✗ (WIFM_CONFIRM only)
    private val micRect = RectF()      // mic (SUGGESTIONS, when voice is enabled)
    private var pressedSlot = -1       // 0..2 suggestions, 3 action, 4 cancel, 5 mic
    private var voiceVisible = false
    private var voiceRecording = false
    private var voiceStatus = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var wifmLongFired = false
    private var clipLongFired = false
    private val clipLongRunnable = Runnable {
        if (pressedSlot == 6) {
            clipLongFired = true
            listener?.onClipboardChipLongPressed()
        }
    }

    private val wifmLongRunnable = Runnable {
        if (pressedSlot == 3 && mode == Mode.SUGGESTIONS) {
            wifmLongFired = true
            listener?.onWifmLongPressed()
        }
    }

    /** P2: clipboard paste chip — a one-line preview of the clip when it's
     *  fresh; occupies the left slot (idle when the user is composing).
     *  The service owns freshness and clears it after a paste. */
    private var clipboardPreview: String? = null

    fun setClipboardChip(preview: String?) {
        if (clipboardPreview != preview) {
            clipboardPreview = preview
            invalidate()
        }
    }

    /** P2: long-press on a word slot → "remove this suggestion". */
    private var suggLongFired = false
    private val suggLongRunnable = Runnable {
        val slot = pressedSlot
        if (mode == Mode.SUGGESTIONS && slot in 0..2) {
            val s = suggestions.getOrNull(SLOT_ORDER[slot])
            // score == 0.0 is the service's marker for the RAW composing word
            // echo (LLMKeyboardService inserts Suggestion(raw, 0.0) in the left
            // slot while typing; real engine suggestions score > 0, the
            // autocorrect-revert chip scores -1.0). Banishing a word the user
            // is still mid-typing would be permanent and wrong — skip it.
            if (s != null && s.score != 0.0) {
                suggLongFired = true
                listener?.onSuggestionLongPressed(s.text)
            }
        }
    }

    private val sparkleIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_sparkle)
    private val checkIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_check)
    private val closeIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_close)
    private val micIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_mic)
    private val stopIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_stop)

    /** Show the mic button in the bar (voice engine present + user enabled). */
    fun setVoiceVisible(v: Boolean) {
        if (v == voiceVisible) return
        voiceVisible = v
        invalidate()
    }

    /** Voice session state: status null returns the bar to suggestions. */
    fun setVoiceState(recording: Boolean, status: String?) {
        if (status == null) {
            mode = Mode.SUGGESTIONS
            voiceRecording = false
            voiceStatus = ""
        } else {
            mode = Mode.VOICE
            voiceRecording = recording
            voiceStatus = status
        }
        invalidate()
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        // A theme carries a typeface, and the memoised shrink-to-fit sizes are
        // measured against the OLD one. Keeping them would let a suggestion
        // overflow its slot after a font change.
        fitCache.clear()
        invalidate()
    }

    /** P2: true while the center slot is a pending autocorrection that the
     *  next space press will apply — the slot gets an accent pill so the
     *  commit is never a surprise. Set by the service before
     *  showSuggestions; resets on every other mode. */
    var pendingCorrection: Boolean = false

    /** Value of [pendingCorrection] as of the last repaint, so the no-op
     *  suppression in showSuggestions can tell when the pill must change. */
    private var lastDrawnPendingCorrection: Boolean = false

    fun showSuggestions(list: List<Suggestion>) {
        // Element-wise compare instead of take(3) + two map passes: those
        // allocated three lists on every keystroke even when nothing changed.
        val n = minOf(list.size, 3)
        var sameWords = suggestions.size == n
        if (sameWords) {
            for (i in 0 until n) {
                if (list[i].text != suggestions[i].text) { sameWords = false; break }
            }
        }
        // pendingCorrection is a plain field set by the service just BEFORE
        // this call, and it draws an accent pill on the centre slot. It can
        // flip while the three words stay identical ("teh" -> pill, then a
        // context that yields the same words with no pending fix), so it has
        // to be part of the comparison or the pill goes stale.
        val sameState = sameWords &&
            mode == Mode.SUGGESTIONS &&
            gestureHighlightSlot == -1 &&
            pendingCorrection == lastDrawnPendingCorrection
        mode = Mode.SUGGESTIONS
        gestureHighlightSlot = -1
        // No copy when the list already fits the three slots. Assigned even
        // when the words match: the raw-word marker (score 0.0, used by the
        // long-press guard) is a property of the object, not the text.
        val next = if (n == list.size) list else list.subList(0, n)
        if (!sameWords) {
            prevSuggestions = suggestions
            suggestionsChangedAt = System.currentTimeMillis()
        }
        suggestions = next
        // Repaint only when something visible actually changed. The prediction
        // pass runs on every keystroke AND on every cursor update, and it very
        // often produces the identical three words — redrawing the bar for that
        // is a whole extra frame per keystroke for no pixels changed.
        if (!sameState) {
            lastDrawnPendingCorrection = pendingCorrection
            invalidate()
        }
    }

    /** Slot (0..2) containing bar-local [x]; -1 when outside the word slots. */
    fun slotForX(x: Float): Int {
        layoutRects()
        if (mode != Mode.SUGGESTIONS) return -1
        for (i in 0..2) {
            if (x >= slotRects[i].left && x < slotRects[i].right) return i
        }
        return -1
    }

    /** The suggestion shown in [slot], null when that slot is empty. */
    fun suggestionInSlot(slot: Int): Suggestion? {
        if (slot !in 0..2 || mode != Mode.SUGGESTIONS) return null
        return suggestions.getOrNull(SLOT_ORDER[slot])
    }

    /** Highlight a slot from the swipe-up gesture (-1 clears). */
    fun setGestureHighlight(slot: Int) {
        val s = slot.coerceIn(-1, 2)
        if (s == gestureHighlightSlot) return
        gestureHighlightSlot = s
        invalidate()
    }

    fun setWifmStreaming(text: String) {
        mode = Mode.WIFM_CONFIRM
        wifmStreamingText = text
        invalidate()
    }

    fun currentMode(): Mode = mode

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        layoutRects()

        when (mode) {
            Mode.SUGGESTIONS -> drawSuggestions(canvas)
            Mode.WIFM_CONFIRM -> drawWifmConfirm(canvas)
            Mode.VOICE -> drawVoice(canvas)
        }
    }

    private fun layoutRects() {
        val actionW = dp(48).toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        // Reusable rects mutated in place — this runs on every onDraw, which
        // during suggestion fades means every frame.
        actionRect.set(w - actionW, 0f, w, h)
        micRect.set(w - actionW * 2, 0f, w - actionW, h)
        val showMic = voiceVisible && mode == Mode.SUGGESTIONS
        var slotsWidth = w - actionW - if (mode == Mode.WIFM_CONFIRM) actionW else 0f
        if (showMic) slotsWidth -= actionW
        val slotW = slotsWidth / 3f
        for (i in 0..2) {
            slotRects[i].set(i * slotW, 0f, (i + 1) * slotW, h)
        }
        cancelRect.set(w - actionW * 2, 0f, w - actionW, h)
    }

    private fun drawSuggestions(canvas: Canvas) {
        // Three slots: [2nd] [top] [3rd] — top suggestion centered & bold, Gboard-style.
        val t = ((System.currentTimeMillis() - suggestionsChangedAt) / 140f).coerceIn(0f, 1f)
        // Swipe-up gesture highlight: a pill behind the slot under the finger.
        if (gestureHighlightSlot in 0..2 &&
            suggestionInSlot(gestureHighlightSlot) != null
        ) {
            val r = slotRects[gestureHighlightSlot]
            tmpRect.set(r.left + dp(4), r.top + dp(4), r.right - dp(4), r.bottom - dp(4))
            highlightPaint.color = theme.accent
            highlightPaint.alpha = 72
            canvas.drawRoundRect(tmpRect, dp(10), dp(10), highlightPaint)
            highlightPaint.alpha = 255
        }
        // P2: a pending autocorrection (space WILL apply it) wears a quiet
        // accent pill — distinct from an ordinary centered suggestion, so
        // the automatic commit never surprises.
        if (pendingCorrection &&
            suggestions.getOrNull(SLOT_ORDER[1]) != null
        ) {
            val r = slotRects[1]
            tmpRect.set(r.left + dp(4), r.top + dp(4), r.right - dp(4), r.bottom - dp(4))
            highlightPaint.color = theme.accent
            highlightPaint.alpha = 38
            canvas.drawRoundRect(tmpRect, dp(10), dp(10), highlightPaint)
            highlightPaint.alpha = 255
        }
        if (t < 1f && prevSuggestions.isNotEmpty()) {
            // Outgoing set fades; incoming set fades in and rises slightly.
            for (slot in 0..2) {
                val suggestion = prevSuggestions.getOrNull(SLOT_ORDER[slot]) ?: continue
                textPaint.color = if (slot == 1) theme.accent else theme.text
                textPaint.textSize = sp(if (slot == 1) 17 else 16)
                textPaint.isFakeBoldText = slot == 1
                textPaint.alpha = ((1f - t) * 255).toInt()
                drawTextFit(canvas, suggestion.text, slotRects[slot], textPaint)
            }
            // P1: no postInvalidateDelayed here — the single scheduler below
            // covers the transition; this one made every transition frame
            // draw TWICE (double CPU on every suggestion change).
        }
        for (slot in 0..2) {
            if (slot == 0 && clipboardPreview != null) continue // paste chip owns this slot
            val suggestion = suggestions.getOrNull(SLOT_ORDER[slot]) ?: continue
            tmpRect.set(slotRects[slot])
            if (t < 1f) tmpRect.offset(0f, dp(6) * (1f - t))
            textPaint.color = if (slot == 1) theme.accent else theme.text
            textPaint.textSize = sp(if (slot == 1) 17 else 16)
            textPaint.isFakeBoldText = slot == 1
            textPaint.alpha = (t * 255).toInt()
            drawTextFit(canvas, suggestion.text, tmpRect, textPaint)
        }
        textPaint.alpha = 255
        // P3: a totally blank bar looked broken ("are predictions dead?") —
        // show a quiet pointer at the one action that's always available.
        if (suggestions.isEmpty() && clipboardPreview == null) {
            textPaint.color = theme.textSecondary
            textPaint.alpha = 120
            textPaint.textSize = sp(14)
            textPaint.isFakeBoldText = false
            val align = textPaint.textAlign
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "✨  Write it for me",
                width / 2f,
                height / 2f - (textPaint.descent() + textPaint.ascent()) / 2,
                textPaint
            )
            textPaint.textAlign = align
            textPaint.alpha = 255
        }
        // P2: the clipboard paste chip — quiet accent pill in the left slot.
        clipboardPreview?.let { preview ->
            val r = slotRects[0]
            tmpRect.set(r.left + dp(4), r.top + dp(4), r.right - dp(4), r.bottom - dp(4))
            highlightPaint.color = theme.accent
            highlightPaint.alpha = if (pressedSlot == 6) 96 else 34
            canvas.drawRoundRect(tmpRect, dp(10), dp(10), highlightPaint)
            highlightPaint.alpha = 255
            textPaint.color = theme.text
            textPaint.textSize = sp(14)
            textPaint.isFakeBoldText = false
            drawTextFit(canvas, "📋 $preview", r, textPaint)
        }
        if (t < 1f) postInvalidateDelayed(16)
        // Dividers between slots.
        dividerPaint.color = theme.divider
        for (i in 0..1) {
            val x = slotRects[i].right
            canvas.drawRect(x, height * 0.28f, x + dp(1), height * 0.72f, dividerPaint)
        }
        // Sparkle action (+ mic when voice typing is enabled).
        if (voiceVisible) drawIcon(canvas, micIcon, micRect, theme.textSecondary, pressedSlot == 5)
        drawIcon(canvas, sparkleIcon, actionRect, theme.accent, pressedSlot == 3)
    }

    private fun drawVoice(canvas: Canvas) {
        val textArea = tmpRect
        textArea.set(
            dp(12).toFloat(), 0f,
            actionRect.left - dp(8), height.toFloat()
        )
        var textLeft = textArea.left
        if (voiceRecording) {
            // Pulsing red record dot.
            val pulse = (kotlin.math.sin(System.currentTimeMillis() / 300.0) + 1.0) / 2.0
            highlightPaint.color = 0xFFE5534B.toInt()
            highlightPaint.alpha = (120 + 135 * pulse).toInt()
            canvas.drawCircle(textArea.left + dp(8), textArea.centerY(), dp(6), highlightPaint)
            highlightPaint.alpha = 255
            textLeft += dp(20)
            postInvalidateDelayed(120)
        }
        textPaint.color = theme.text
        textPaint.textSize = sp(15)
        textPaint.isFakeBoldText = false
        textPaint.textAlign = Paint.Align.LEFT
        val baseline = textArea.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(voiceStatus, textLeft, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        drawIcon(canvas, stopIcon, actionRect, theme.accent, pressedSlot == 3)
    }

    private fun drawWifmConfirm(canvas: Canvas) {
        // Streaming text occupies the left area; ✗ and ✓ on the right.
        val textArea = tmpRect
        textArea.set(
            dp(12).toFloat(), 0f,
            cancelRect.left - dp(8), height.toFloat()
        )
        textPaint.color = theme.text
        textPaint.textSize = sp(15)
        textPaint.isFakeBoldText = false
        textPaint.textAlign = Paint.Align.LEFT
        val shown = if (wifmStreamingText.length > 90)
            "…" + wifmStreamingText.takeLast(89) else wifmStreamingText
        val baseline = textArea.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(shown, textArea.left, baseline, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        drawIcon(canvas, closeIcon, cancelRect, theme.textSecondary, pressedSlot == 4)
        drawIcon(canvas, checkIcon, actionRect, theme.accent, pressedSlot == 3)
    }

    /**
     * Shrink-to-fit text size, memoised.
     *
     * The loop below calls measureText once per point of shrink, for each of
     * three suggestions, on every frame — and the bar redraws on every
     * keystroke. Text measurement walks font metrics and is one of the more
     * expensive things available on a draw path. The answer only changes when
     * the string, the slot width or the base size changes, so cache on those.
     */
    private val fitCache = HashMap<Int, Float>(16)

    private fun fittedSize(text: String, maxW: Float, basePaint: Paint): Float {
        // Int hash instead of a concatenated String: the old key allocated
        // per slot per frame. String.hashCode is cached by the String itself,
        // so this costs nothing on repeat lookups. A collision just yields a
        // near-enough size from another entry — still bounded by the slot.
        var key = text.hashCode()
        key = 31 * key + maxW.toInt()
        key = 31 * key + basePaint.textSize.toInt()
        fitCache[key]?.let { return it }
        val base = basePaint.textSize
        var size = base
        // Measuring is what costs; mutate a scratch size on the same paint and
        // restore it afterwards so callers see no side effect.
        while (size > sp(10)) {
            basePaint.textSize = size
            if (basePaint.measureText(text) <= maxW) break
            size -= 1f
        }
        basePaint.textSize = base
        // Bounded: suggestion vocabulary in one session is small, but a long
        // session should not accumulate forever.
        if (fitCache.size > 256) fitCache.clear()
        fitCache[key] = size
        return size
    }

    private fun drawTextFit(canvas: Canvas, text: String, r: RectF, paint: Paint) {
        val padding = dp(8).toFloat()
        val maxW = r.width() - padding * 2
        paint.textSize = fittedSize(text, maxW, paint)
        val baseline = r.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, r.centerX(), baseline, paint)
    }

    /** Last tint applied to each icon, so drawIcon doesn't re-tint (and
     *  dirty the drawable's internal state) on every frame. */
    private val iconTints = HashMap<Drawable, Int>(8)

    private fun drawIcon(canvas: Canvas, icon: Drawable?, r: RectF, tint: Int, pressed: Boolean) {
        icon ?: return
        val effective = if (pressed) theme.text else tint
        if (iconTints[icon] != effective) {
            icon.setTint(effective)
            iconTints[icon] = effective
        }
        val size = (24 * resources.displayMetrics.density).toInt()
        val cx = r.centerX().toInt()
        val cy = r.centerY().toInt()
        // bounds is the drawable's own Rect (no copy), so this is a free
        // compare; setBounds only when the layout actually moved it.
        val b = icon.bounds
        val l = cx - size / 2; val t = cy - size / 2
        if (b.left != l || b.top != t || b.right != l + size || b.bottom != t + size) {
            icon.setBounds(l, t, l + size, t + size)
        }
        icon.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedSlot = hitSlot(event.x)
                downX = event.x; downY = event.y
                wifmLongFired = false
                suggLongFired = false
                clipLongFired = false
                if (pressedSlot == 3 && mode == Mode.SUGGESTIONS) {
                    handler.postDelayed(wifmLongRunnable, WIFM_LONG_MS)
                } else if (pressedSlot in 0..2 && mode == Mode.SUGGESTIONS) {
                    handler.postDelayed(suggLongRunnable, WIFM_LONG_MS)
                } else if (pressedSlot == 6) {
                    handler.postDelayed(clipLongRunnable, WIFM_LONG_MS)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (dx * dx + dy * dy > dp(14) * dp(14)) {
                    handler.removeCallbacks(wifmLongRunnable)
                handler.removeCallbacks(clipLongRunnable)
                    handler.removeCallbacks(suggLongRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(wifmLongRunnable)
                handler.removeCallbacks(clipLongRunnable)
                handler.removeCallbacks(suggLongRunnable)
                val slot = hitSlot(event.x)
                if (!wifmLongFired && !suggLongFired && !clipLongFired &&
                    slot == pressedSlot && slot >= 0) dispatchTap(slot)
                pressedSlot = -1
                wifmLongFired = false
                suggLongFired = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(wifmLongRunnable)
                handler.removeCallbacks(clipLongRunnable)
                handler.removeCallbacks(suggLongRunnable)
                pressedSlot = -1
                wifmLongFired = false
                suggLongFired = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitSlot(x: Float): Int {
        if (actionRect.contains(x, 0f)) return 3
        if (mode == Mode.WIFM_CONFIRM && cancelRect.contains(x, 0f)) return 4
        // While dictating, a tap anywhere means "stop and type it".
        if (mode == Mode.VOICE) return 3
        if (voiceVisible && mode == Mode.SUGGESTIONS && micRect.contains(x, 0f)) return 5
        if (mode == Mode.SUGGESTIONS) {
            // The paste chip owns the left slot while it's showing.
            if (clipboardPreview != null && slotRects[0].contains(x, 0f)) return 6
            for (i in 0..2) if (slotRects[i].contains(x, 0f)) return i
        }
        return -1
    }

    private fun dispatchTap(slot: Int) {
        when (slot) {
            3 -> when (mode) {
                Mode.SUGGESTIONS -> listener?.onWifmRequested()
                Mode.WIFM_CONFIRM -> listener?.onWifmConfirmed()
                Mode.VOICE -> listener?.onVoiceStopTapped()
            }
            4 -> listener?.onWifmDiscarded()
            5 -> listener?.onVoiceRequested()
            6 -> listener?.onClipboardChipTapped()
            else -> {
                suggestions.getOrNull(SLOT_ORDER[slot])
                    ?.let { listener?.onSuggestionPicked(it.text) }
            }
        }
    }

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        /** Slot position → suggestion index: center slot shows the top pick. */
        private val SLOT_ORDER = intArrayOf(1, 0, 2)
        /** Hold-to-prompt delay: short enough to feel like a quick gesture. */
        private const val WIFM_LONG_MS = 400L
    }
}
