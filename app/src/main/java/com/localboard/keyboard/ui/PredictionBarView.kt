package com.localboard.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.localboard.keyboard.R
import com.localboard.keyboard.engine.Suggestion

/**
 * The strip above the keys: three suggestion slots, a sparkle button for
 * "Write It For Me" when idle, and a confirm/cancel mode while a draft is
 * being composed.
 *
 * One chrome row, double duty, the way Gboard runs it: while there is
 * composing text or candidates the row shows the candidates; when idle — and
 * on every page but the letters page — the same row shows toolbar icons
 * (clipboard, settings, theme, mic). The left apps icon toggles between the
 * two while candidates are up.
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
    /** The draft is finished and waits for the check or the cross. Until then the same two
     *  buttons mean "stop and use what is there so far" and "stop". */
    private var wifmReviewReady = false
    private val draftPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var draftLayout: StaticLayout? = null
    private var draftLayoutText: String? = null
    private var draftLayoutWidth = 0
    private var prevSuggestions: List<Suggestion> = emptyList()
    /** When the running fade started. 0 means no fade: the words are simply there. */
    private var suggestionsChangedAt = 0L
    /** When the list last changed, whether or not that change faded. A skipped change must count, or
     *  the next one would see a long gap and every other key would fade. */
    private var lastListChangeAt = 0L

    /** The person's animation level. 0 means nothing on the bar moves. */
    var animLevel = 2

    /** The press under way, or null. What it is aimed at comes from here and not from a fresh look at the list. */
    private var press: BarRules.Press? = null
    private var pressMode = Mode.SUGGESTIONS
    private var pressShow = 0
    /** Moves on with every show, so a finger that came down before it can tell. */
    private var showsSeen = 0

    /** A finger has come down on a word. */
    var onPillDown: (() -> Unit)? = null

    /** Whether an automatic correction can still be undone. The chip can outlast it by one pass. */
    var revertChipLive: (() -> Boolean)? = null

    /** What is being typed right now. The list can be a pass behind it. */
    var composingWord: (() -> String)? = null

    /** The down time of a touch that has just taken a plain notice away and is on its way here. */
    private var noticeTouchDownTime = -1L

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
    private val appsRect = RectF()     // left toggle between candidates and toolbar icons
    private val toolbarRects = Array(TOOLBAR_SLOT_COUNT) { RectF() }
    /** False on the symbols pages, the number pad and the selection pad — the
     *  row shows toolbar icons there, never lexical candidates. */
    private var lettersPage = true
    /** The apps icon was tapped while candidates were up: hold the toolbar
     *  until real candidates arrive (typing resumes) or the icon is tapped
     *  again. */
    private var toolbarPinned = false
    private var keyboardView: KeyboardView? = null
    private var pressedSlot = -1       // 0..2 suggestions, 3 action, 4 cancel, 5 mic, 7 draft text, 9..12 toolbar
    private var voiceVisible = false
    private var voiceRecording = false
    private var voiceStatus = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var wifmLongFired = false
    private var clipLongFired = false
    private val clipLongRunnable = Runnable {
        if (pressedSlot == 6 || pressedSlot == NODE_CLIPBOARD) {
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
            if (mode == Mode.SUGGESTIONS) {
                // The paste chip occupying the empty row keeps the candidates
                // look; clearing it with nothing else on the bar brings the
                // toolbar back. Snapshot before the flip so it crossfades.
                val wasToolbar = toolbarVisible()
                val willBeToolbar = !lettersPage || toolbarPinned ||
                    (suggestions.isEmpty() && preview == null)
                if (wasToolbar != willBeToolbar) captureForSwapFade()
            }
            clipboardPreview = preview
            invalidate()
            nodesChanged()
        }
    }

    /** P2: long-press on a word slot → "remove this suggestion". */
    private var suggLongFired = false
    private val suggLongRunnable = Runnable {
        val slot = pressedSlot
        if (mode == Mode.SUGGESTIONS && slot in 0..2) {
            val index = BarRules.indexInSlot(suggestions, slot)
            val s = suggestions.getOrNull(index)
            // The word as typed sits at a known place in the list with score 0.0.
            // The score alone would not do: an engine word can also score exactly
            // 0.0, and the autocorrect-revert chip scores -1.0. Banishing a word
            // the user is still mid-typing would be permanent and wrong — skip it.
            if (s != null && index != BarRules.rawIndex(suggestions)) {
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
    private val appsIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_apps)
    private val clipboardIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_clipboard)
    private val settingsIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_settings)
    private val paletteIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_palette)

    /**
     * The chrome row reads the page from the sibling [KeyboardView] instead of
     * keeping a page state of its own — the service owns the page, the view
     * only renders it. Wired here rather than from the service so the strip's
     * data path stays untouched.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachPageSource()
    }

    override fun onDetachedFromWindow() {
        keyboardView?.onModelApplied = null
        keyboardView = null
        endSwapFade()
        super.onDetachedFromWindow()
    }

    private fun attachPageSource() {
        val root = parent as? ViewGroup ?: return
        for (i in 0 until root.childCount) {
            val container = root.getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until container.childCount) {
                val kv = container.getChildAt(j) as? KeyboardView ?: continue
                keyboardView = kv
                kv.onModelApplied = {
                    if (mode == Mode.SUGGESTIONS) {
                        val wasToolbar = toolbarVisible()
                        val willBeToolbar = !kv.isLettersPage || toolbarPinned ||
                            (suggestions.isEmpty() && clipboardPreview == null)
                        if (wasToolbar != willBeToolbar) captureForSwapFade()
                    }
                    lettersPage = kv.isLettersPage
                    invalidate()
                    nodesChanged()
                }
                lettersPage = kv.isLettersPage
                return
            }
        }
    }

    /** False with no model able to draft, or in a field the AI must never touch, such as a password:
     *  no sparkle, no pointer to it, and the words get its room. */
    private var draftingAvailable = false

    // ── Candidates <-> toolbar swap fade ─────────────────────────────────
    //
    // The row is one surface with two contents; when candidates replace the
    // toolbar icons (or back) the swap crossfades over ~100 ms instead of
    // cutting. The previous appearance is rendered into [swapSnapshot] BEFORE
    // the state flip, and onDraw dissolves it out over the new content.
    // Skipped entirely when the user's animation level is off or the system
    // animator scale is 0 — an instant cut, as before.
    private var swapSnapshot: android.graphics.Bitmap? = null
    private var swapFadeStartMs = 0L
    private var swapFadeDurationMs = 0L
    private val swapFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** System animator duration scale — the "remove animations" accessibility
     *  setting. 0 means motion is off and every animation cuts instantly. */
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

    /** Snapshot the row's current appearance. Call BEFORE the state change
     *  that swaps candidates and toolbar, and only when [toolbarVisible] is
     *  actually about to flip — an identical snapshot would dissolve over an
     *  identical frame, invisible but not free. */
    private fun captureForSwapFade() {
        endSwapFade()
        if (width <= 0 || height <= 0) return
        if (animLevel == 0) return
        val durationMs = (CHROME_SWAP_MS * animatorScale()).toLong()
        if (durationMs <= 0L) return
        val bmp = try {
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { return }
        val c = Canvas(bmp)
        bgPaint.color = theme.background
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        layoutRects()
        if (toolbarVisible()) drawToolbar(c) else drawSuggestions(c)
        swapSnapshot = bmp
        swapFadeStartMs = SystemClock.uptimeMillis()
        swapFadeDurationMs = durationMs
        invalidate()
    }

    private fun endSwapFade() {
        swapSnapshot?.let { if (!it.isRecycled) it.recycle() }
        swapSnapshot = null
    }

    /** Remaining snapshot opacity 0..1, or -1 when no fade is running. */
    private fun swapFadeAlpha(): Float {
        val bmp = swapSnapshot ?: return -1f
        val raw = (SystemClock.uptimeMillis() - swapFadeStartMs).toFloat() / swapFadeDurationMs
        if (raw >= 1f) { endSwapFade(); return -1f }
        return 1f - raw.coerceIn(0f, 1f)
    }

    fun setDraftingAvailable(available: Boolean) {
        if (available == draftingAvailable) return
        draftingAvailable = available
        invalidate()
        nodesChanged()
    }

    /** Show the mic button in the bar (voice engine present + user enabled). */
    fun setVoiceVisible(v: Boolean) {
        if (v == voiceVisible) return
        voiceVisible = v
        invalidate()
        nodesChanged()
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
        nodesChanged()
    }

    /**
     * Forgets a finger that is down, for a show. Whatever it was aimed at belonged to the field before: nothing
     * may land in a new one, and it could be a password box. The words belonged there too, unless this is a restart,
     * which is the same field. Left here, the first list of the new field would fade them out over it.
     */
    fun dropPress(restarting: Boolean) {
        showsSeen++
        handler.removeCallbacks(wifmLongRunnable)
        handler.removeCallbacks(clipLongRunnable)
        handler.removeCallbacks(suggLongRunnable)
        if (pressedSlot != -1) {
            pressedSlot = -1
            invalidate()
        }
        if (!restarting) {
            toolbarPinned = false
            suggestionsChangedAt = 0L
            prevSuggestions = emptyList()
            suggestions = emptyList()
            invalidate()
            nodesChanged()
        }
    }

    /** The touch with this down time has just taken a plain notice away, and it comes here next. */
    fun noticeTakenAwayBy(downTime: Long) {
        noticeTouchDownTime = downTime
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        // A theme carries a typeface, and the memoised shrink-to-fit sizes are
        // measured against the OLD one. Keeping them would let a suggestion
        // overflow its slot after a font change.
        fitCache.clear()
        draftLayout = null
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
        // Candidates arriving on the letters page replace the toolbar icons
        // (and an empty list brings them back): snapshot first so the swap
        // crossfades. A non-empty list also releases the toolbar pin below,
        // so the pin survives this call only when the list is empty.
        if (mode == Mode.SUGGESTIONS) {
            val pinnedAfter = toolbarPinned && n == 0
            val wasToolbar = toolbarVisible()
            val willBeToolbar = !lettersPage || pinnedAfter ||
                (n == 0 && clipboardPreview == null)
            if (wasToolbar != willBeToolbar) captureForSwapFade()
        }
        // The same word draws in another slot once it is the word as typed and nothing else.
        var sameWords = suggestions.size == n && BarRules.isRawOnly(list) == BarRules.isRawOnly(suggestions)
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
        // Real candidates arriving end a toolbar pin: typing resumes, the
        // words matter more than the icons.
        if (n > 0 && toolbarPinned) {
            toolbarPinned = false
            nodesChanged()
        }
        if (!sameWords) {
            prevSuggestions = suggestions
            // No fade while typing: words that fade and rise at every key are words nobody can
            // read in time to tap. A person reading the bar has paused, and sees the change.
            val now = SystemClock.uptimeMillis()
            suggestionsChangedAt = if (BarRules.animates(now, lastListChangeAt, animLevel)) now else 0L
            lastListChangeAt = now
        }
        suggestions = next
        // Repaint only when something visible actually changed. The prediction
        // pass runs on every keystroke AND on every cursor update, and it very
        // often produces the identical three words — redrawing the bar for that
        // is a whole extra frame per keystroke for no pixels changed.
        if (!sameState) {
            lastDrawnPendingCorrection = pendingCorrection
            invalidate()
            nodesChanged()
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
        return BarRules.wordInSlot(suggestions, slot)
    }

    /** Highlight a slot from the swipe-up gesture (-1 clears). */
    fun setGestureHighlight(slot: Int) {
        val s = slot.coerceIn(-1, 2)
        if (s == gestureHighlightSlot) return
        gestureHighlightSlot = s
        invalidate()
    }

    fun setWifmStreaming(text: String) {
        if (mode != Mode.WIFM_CONFIRM) nodesChanged()
        mode = Mode.WIFM_CONFIRM
        wifmStreamingText = text
        wifmReviewReady = false
        invalidate()
    }

    /** The finished draft: it stays here, and out of the field, until the check or the cross. */
    fun setWifmReview(text: String) {
        if (mode != Mode.WIFM_CONFIRM) nodesChanged()
        mode = Mode.WIFM_CONFIRM
        wifmStreamingText = text
        wifmReviewReady = true
        invalidate()
    }

    /** The bar holds two lines of a draft that can run to many more. */
    var onDraftTextTapped: (() -> Unit)? = null

    fun currentMode(): Mode = mode

    /** How much of the right edge the bar's buttons take, for what lies on top of the bar. */
    fun actionButtonsWidth(): Int {
        if (restingBar()) return if (voiceVisible) dp(48).toInt() else 0
        val two = mode == Mode.WIFM_CONFIRM || (mode == Mode.SUGGESTIONS && voiceVisible)
        return (dp(48) * if (two) 2 else 1).toInt()
    }

    /** Words with no sparkle to make room for. Voice and a draft keep their rects: they draw in them whatever the flag says. */
    private fun restingBar(): Boolean = mode == Mode.SUGGESTIONS && !draftingAvailable

    /** Toolbar icons instead of words: off the letters page, while idle, or while the apps-icon pin holds.
     *  A ready draft hides the toolbar: the sparkle takes the right edge instead of the mic. */
    private fun toolbarVisible(): Boolean =
        mode == Mode.SUGGESTIONS && !draftingAvailable &&
            (!lettersPage || toolbarPinned || (suggestions.isEmpty() && clipboardPreview == null))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = theme.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        layoutRects()

        when (mode) {
            Mode.SUGGESTIONS -> if (toolbarVisible()) drawToolbar(canvas) else drawSuggestions(canvas)
            Mode.WIFM_CONFIRM -> drawWifmConfirm(canvas)
            Mode.VOICE -> drawVoice(canvas)
        }

        // Candidates <-> toolbar swap: the previous appearance dissolves out
        // over the new one (~100 ms). Drawn last so it sits on top; the next
        // frame is requested only while the fade runs.
        val swapFade = swapFadeAlpha()
        if (swapFade >= 0f) {
            swapFadePaint.alpha = (swapFade * 255).toInt()
            canvas.drawBitmap(swapSnapshot!!, 0f, 0f, swapFadePaint)
            postInvalidateDelayed(16)
        }
    }

    private fun layoutRects() {
        val actionW = dp(48).toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        // Reusable rects mutated in place — this runs on every onDraw, which
        // during suggestion fades means every frame.
        if (toolbarVisible()) {
            appsRect.set(0f, 0f, actionW, h)
            actionRect.setEmpty()
            cancelRect.setEmpty()
            if (voiceVisible) micRect.set(w - actionW, 0f, w, h) else micRect.set(w, 0f, w, h)
            val middleRight = if (voiceVisible) w - actionW else w
            val slotW = (middleRight - actionW) / TOOLBAR_SLOT_COUNT
            for (i in 0 until TOOLBAR_SLOT_COUNT) {
                toolbarRects[i].set(actionW + i * slotW, 0f, actionW + (i + 1) * slotW, h)
            }
            for (i in 0..2) slotRects[i].set(0f, 0f, 0f, 0f)
            return
        }
        for (i in 0 until TOOLBAR_SLOT_COUNT) toolbarRects[i].set(0f, 0f, 0f, 0f)
        if (mode == Mode.SUGGESTIONS) {
            // Candidates: left apps icon, three words between it and the
            // right-edge buttons, Gboard's pattern.
            appsRect.set(0f, 0f, actionW, h)
            val showMic = voiceVisible
            val showSparkle = draftingAvailable
            if (showSparkle) actionRect.set(w - actionW, 0f, w, h) else actionRect.set(w, 0f, w, h)
            when {
                showMic && showSparkle -> micRect.set(w - actionW * 2, 0f, w - actionW, h)
                showMic -> micRect.set(w - actionW, 0f, w, h)
                else -> micRect.set(w, 0f, w, h)
            }
            cancelRect.setEmpty()
            val slotsRight = if (showMic) micRect.left else if (showSparkle) actionRect.left else w
            val slotW = (slotsRight - actionW) / 3f
            for (i in 0..2) {
                slotRects[i].set(actionW + i * slotW, 0f, actionW + (i + 1) * slotW, h)
            }
        } else {
            // A draft or a voice session: text on the left, buttons on the right.
            appsRect.setEmpty()
            actionRect.set(w - actionW, 0f, w, h)
            if (mode == Mode.WIFM_CONFIRM) {
                cancelRect.set(w - actionW * 2, 0f, w - actionW, h)
                micRect.setEmpty()
            } else {
                cancelRect.setEmpty()
                micRect.set(w, 0f, w, h)
            }
            val buttonsW = if (mode == Mode.WIFM_CONFIRM) actionW * 2 else actionW
            val slotW = (w - buttonsW) / 3f
            for (i in 0..2) {
                slotRects[i].set(i * slotW, 0f, (i + 1) * slotW, h)
            }
        }
    }

    private fun drawSuggestions(canvas: Canvas) {
        // Three slots: [2nd] [top] [3rd] — top suggestion centered & bold, Gboard-style.
        // Uptime, because a clock set back would give t = 0: invisible words, redrawn until the next list.
        val t = ((SystemClock.uptimeMillis() - suggestionsChangedAt) / 140f).coerceIn(0f, 1f)
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
        // P2: a pending autocorrection (space WILL apply it) is simply the
        // bold middle word, Gboard-style — plain text, no chip: an accent
        // pill in the middle of the strip read as an error, not a selection.
        // A word under a finger shows it, as a key does. The list may have moved on, so the press decides.
        if (pressedSlot in 0..2 && press?.word != null) {
            val r = slotRects[pressedSlot]
            tmpRect.set(r.left + dp(4), r.top + dp(4), r.right - dp(4), r.bottom - dp(4))
            highlightPaint.color = theme.accent
            highlightPaint.alpha = 96
            canvas.drawRoundRect(tmpRect, dp(10), dp(10), highlightPaint)
            highlightPaint.alpha = 255
        }
        if (t < 1f && prevSuggestions.isNotEmpty()) {
            // Outgoing set fades; incoming set fades in and rises slightly.
            for (slot in 0..2) {
                val suggestion = BarRules.wordInSlot(prevSuggestions, slot) ?: continue
                textPaint.color = theme.text
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
            val suggestion = BarRules.wordInSlot(suggestions, slot) ?: continue
            tmpRect.set(slotRects[slot])
            if (t < 1f) tmpRect.offset(0f, dp(6) * (1f - t))
            textPaint.color = theme.text
            textPaint.textSize = sp(if (slot == 1) 17 else 16)
            textPaint.isFakeBoldText = slot == 1
            textPaint.alpha = (t * 255).toInt()
            drawTextFit(canvas, suggestion.text, tmpRect, textPaint)
        }
        textPaint.alpha = 255
        // P3: a totally blank bar looked broken ("are predictions dead?") —
        // show a quiet pointer at the one action that's always available.
        val blank = suggestions.isEmpty() && clipboardPreview == null
        if (blank && draftingAvailable && !toolbarVisible()) {
            textPaint.color = theme.textSecondary
            textPaint.alpha = 120
            textPaint.textSize = sp(14)
            textPaint.isFakeBoldText = false
            val align = textPaint.textAlign
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "✨  Write it for me",
                // In the centre slot, the part of the bar that answers a tap on these words.
                slotRects[1].centerX(),
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
        // Dividers between slots. An empty bar has no slots to tell apart, and
        // they would cut straight through the pointer above.
        if (!blank && !toolbarVisible()) {
            dividerPaint.color = theme.divider
            for (i in 0..1) {
                val x = slotRects[i].right
                canvas.drawRect(x, height * 0.28f, x + dp(1), height * 0.72f, dividerPaint)
            }
        }
        // Left toggle (apps icon) + Sparkle action (+ mic when voice typing is enabled).
        drawIcon(canvas, appsIcon, appsRect, if (toolbarPinned) theme.accent else theme.textSecondary, pressedSlot == NODE_APPS)
        if (voiceVisible) {
            drawMicChip(canvas)
            drawIcon(canvas, micIcon, micRect, theme.textSecondary, pressedSlot == 5)
        }
        if (draftingAvailable) drawIcon(canvas, sparkleIcon, actionRect, theme.accent, pressedSlot == 3)
    }

    /**
     * The toolbar half of the chrome row: the apps toggle at the left, the
     * working feature slots between it and the mic chip - clipboard, settings,
     * theme - and nothing else. A slot with no backing feature is not
     * rendered (text-editing waits for an ime-side wire), and the row lays
     * out the slots it has.
     */
    private fun drawToolbar(canvas: Canvas) {
        drawIcon(canvas, appsIcon, appsRect,
            if (toolbarPinned) theme.accent else theme.textSecondary, pressedSlot == NODE_APPS)
        drawIcon(canvas, clipboardIcon, toolbarRects[0], theme.textSecondary, pressedSlot == NODE_CLIPBOARD)
        drawIcon(canvas, settingsIcon, toolbarRects[1], theme.textSecondary, pressedSlot == NODE_SETTINGS)
        drawIcon(canvas, paletteIcon, toolbarRects[2], theme.textSecondary, pressedSlot == NODE_STYLE)
        if (voiceVisible) {
            drawMicChip(canvas)
            drawIcon(canvas, micIcon, micRect, theme.textSecondary, pressedSlot == 5)
        }
    }

    /** Gboard's mic sits in a rounded chip at the right of the row. */
    private fun drawMicChip(canvas: Canvas) {
        if (micRect.width() <= 0f) return
        tmpRect.set(micRect.left + dp(4), micRect.top + dp(6), micRect.right - dp(4), micRect.bottom - dp(6))
        highlightPaint.color = theme.key
        canvas.drawRoundRect(tmpRect, dp(14), dp(14), highlightPaint)
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
        val layout = draftLayoutFor(textArea.width().toInt())
        if (layout != null) {
            canvas.save()
            canvas.translate(textArea.left, (height - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }

        // The accent behind the check is what says "this is ready, it is your call". A finished
        // draft sitting in the bar looked as if nothing had happened.
        if (wifmReviewReady) {
            tmpRect.set(actionRect.left + dp(4), actionRect.top + dp(4), actionRect.right - dp(4), actionRect.bottom - dp(4))
            highlightPaint.color = theme.accent
            highlightPaint.alpha = 64
            canvas.drawRoundRect(tmpRect, dp(10), dp(10), highlightPaint)
            highlightPaint.alpha = 255
        }
        drawIcon(canvas, closeIcon, cancelRect, theme.textSecondary, pressedSlot == 4)
        drawIcon(canvas, checkIcon, actionRect, theme.accent, pressedSlot == 3)
    }

    /**
     * The draft as two lines at most. Drawn as one line it ran on under the buttons, and a draft
     * is there to be read before it is accepted. While it streams the newest words matter, so
     * a long text shows its end; a finished one shows its start.
     */
    private fun draftLayoutFor(width: Int): StaticLayout? {
        if (width <= 0) return null
        val text = wifmStreamingText
        val reused = draftLayout
        if (reused != null && draftLayoutText == text && draftLayoutWidth == width) return reused
        draftPaint.color = theme.text
        draftPaint.textSize = sp(15)
        val oneLine = draftPaint.measureText(text) <= width
        if (!oneLine) draftPaint.textSize = sp(13)
        val roomFor = (2 * width / draftPaint.measureText("n")).toInt()
        val shown = if (!wifmReviewReady && text.length > roomFor) "…" + text.takeLast(roomFor - 1) else text
        val built = StaticLayout.Builder.obtain(shown, 0, shown.length, draftPaint, width)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        draftLayout = built
        draftLayoutText = text
        draftLayoutWidth = width
        return built
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
                // A touch that took a notice away never saw what the notice was hiding, so it lands none of it,
                // word or clip. An empty bar hides nothing to land, and its pointer still answers.
                if (event.downTime == noticeTouchDownTime &&
                    (pressedSlot == 6 || (pressedSlot in 0..2 && suggestions.isNotEmpty()))
                ) {
                    pressedSlot = -1
                }
                noticeTouchDownTime = -1L
                // Read now. Reading at the lift lands whatever a late pass has just put in the slot.
                press = BarRules.pressOn(suggestions, pressedSlot, composingWord?.invoke().orEmpty())
                pressMode = mode
                pressShow = showsSeen
                if (press?.word != null) onPillDown?.invoke()
                downX = event.x; downY = event.y
                wifmLongFired = false
                suggLongFired = false
                clipLongFired = false
                if (pressedSlot == 3 && mode == Mode.SUGGESTIONS) {
                    handler.postDelayed(wifmLongRunnable, WIFM_LONG_MS)
                } else if (pressedSlot in 0..2 && mode == Mode.SUGGESTIONS) {
                    handler.postDelayed(suggLongRunnable, WIFM_LONG_MS)
                } else if (pressedSlot == 6 || pressedSlot == NODE_CLIPBOARD) {
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
                val held = press
                if (held != null && !wifmLongFired && !suggLongFired && !clipLongFired &&
                    slot == held.slot && slot >= 0) {
                    val showSinceDown = pressShow != showsSeen
                    val modeChanged = pressMode != mode
                    if (slot in 0..2) liftOnWordSlot(held, showSinceDown, modeChanged)
                    else if (BarRules.pressStands(showSinceDown, modeChanged)) dispatchTap(slot)
                }
                pressedSlot = -1
                press = null
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
                press = null
                wifmLongFired = false
                suggLongFired = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitSlot(x: Float): Int {
        // The rects are otherwise set by a draw, and a show can change them just before a touch.
        layoutRects()
        if (mode == Mode.SUGGESTIONS && toolbarVisible()) {
            if (appsRect.contains(x, 0f)) return NODE_APPS
            for (i in 0 until TOOLBAR_SLOT_COUNT) {
                if (toolbarRects[i].contains(x, 0f)) return NODE_CLIPBOARD + i
            }
            if (voiceVisible && micRect.contains(x, 0f)) return 5
            return -1
        }
        if (mode == Mode.SUGGESTIONS && appsRect.contains(x, 0f)) return NODE_APPS
        if (actionRect.contains(x, 0f)) {
            return if (mode == Mode.SUGGESTIONS && !draftingAvailable) -1 else 3
        }
        if (mode == Mode.WIFM_CONFIRM) return if (cancelRect.contains(x, 0f)) 4 else 7
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
            NODE_APPS -> {
                if (mode == Mode.SUGGESTIONS) {
                    val wasToolbar = toolbarVisible()
                    val willBeToolbar = !lettersPage || !toolbarPinned ||
                        (suggestions.isEmpty() && clipboardPreview == null)
                    if (wasToolbar != willBeToolbar) captureForSwapFade()
                }
                toolbarPinned = !toolbarPinned
                invalidate()
                nodesChanged()
            }
            NODE_CLIPBOARD -> listener?.onClipboardChipTapped()
            NODE_SETTINGS -> openActivity(com.localboard.keyboard.settings.SettingsActivity::class.java)
            NODE_STYLE -> openActivity(com.localboard.keyboard.settings.StyleActivity::class.java)
            3 -> when (mode) {
                Mode.SUGGESTIONS -> listener?.onWifmRequested()
                Mode.WIFM_CONFIRM -> listener?.onWifmConfirmed()
                Mode.VOICE -> listener?.onVoiceStopTapped()
            }
            4 -> listener?.onWifmDiscarded()
            5 -> listener?.onVoiceRequested()
            6 -> listener?.onClipboardChipTapped()
            7 -> onDraftTextTapped?.invoke()
        }
    }

    /** A toolbar slot that opens an app screen. Fails soft: a start restriction
     *  or a launcher-less build leaves the slot a no-op rather than a crash. */
    private fun openActivity(cls: Class<*>) {
        try {
            context.startActivity(
                android.content.Intent(context, cls)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Nothing to do: the screen simply does not open.
        }
    }

    private fun liftOnWordSlot(held: BarRules.Press, showSinceDown: Boolean, modeChanged: Boolean) {
        val word = BarRules.wordToCommit(
            held, suggestions, showSinceDown, modeChanged,
            revertLive = revertChipLive?.invoke() == true,
            composing = composingWord?.invoke().orEmpty()
        )
        if (word != null) {
            listener?.onSuggestionPicked(word)
        } else if (BarRules.pointerStartsDraft(
                held, suggestions, showSinceDown, modeChanged, clipboardPreview != null, draftingAvailable
            )
        ) {
            // The pointer says "Write it for me", so a tap on it should. It sits in the
            // centre slot, and a thumb landing short of the keys in a side slot meant nothing.
            listener?.onWifmRequested()
        }
    }

    // ------------------------------------------------------------------
    // What a screen reader, or any accessibility service, can reach
    // ------------------------------------------------------------------

    private val nodesChangedTick = Runnable { barTouchHelper.invalidateRoot() }

    /**
     * The list changes per key, and screen reading is on for anyone using the service that reads the chat, so an event
     * per key would be paid while typing. One event after the bar has been still for a moment says the same.
     */
    private fun nodesChanged() {
        handler.removeCallbacks(nodesChangedTick)
        handler.postDelayed(nodesChangedTick, NODES_CHANGED_DELAY_MS)
    }

    /**
     * Every node is a Button with a description and no text, so that nothing looking for what the bar's messages and
     * the dialogs say takes a word on the bar for one. The left word has no node while the paste chip is on it,
     * because a tap there pastes.
     */
    private val barTouchHelper = object : ExploreByTouchHelper(this) {
        private fun isShown(id: Int): Boolean = when (id) {
            in 0..2 -> !toolbarVisible() && !(id == 0 && clipboardPreview != null) && BarRules.wordInSlot(suggestions, id) != null
            NODE_APPS -> true
            NODE_CLIPBOARD, NODE_SETTINGS, NODE_STYLE -> toolbarVisible()
            NODE_SPARKLE -> draftingAvailable
            NODE_POINTER -> !toolbarVisible() && draftingAvailable && suggestions.isEmpty() && clipboardPreview == null
            NODE_MIC -> voiceVisible
            NODE_CHIP -> clipboardPreview != null
            else -> false
        }

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (mode != Mode.SUGGESTIONS) return HOST_ID
            val slot = hitSlot(x)
            val id = if (slot == 1 && isShown(NODE_POINTER)) NODE_POINTER else slot
            return if (isShown(id)) id else HOST_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (mode != Mode.SUGGESTIONS) return
            layoutRects()
            for (id in NODE_ORDER) if (isShown(id)) virtualViewIds.add(id)
        }


        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            layoutRects()
            // The list can change between the walk that listed this node and this call, and a node
            // with no description or no bounds makes the helper throw, so both are always set.
            val rect = when (virtualViewId) {
                in 0..2 -> slotRects[virtualViewId]
                NODE_APPS -> appsRect
                NODE_CLIPBOARD, NODE_SETTINGS, NODE_STYLE -> toolbarRects[virtualViewId - NODE_CLIPBOARD]
                NODE_SPARKLE -> actionRect
                NODE_POINTER -> slotRects[1]
                NODE_MIC -> micRect
                else -> slotRects[0]
            }
            node.className = "android.widget.Button"
            node.contentDescription = when (virtualViewId) {
                in 0..2 -> BarRules.wordInSlot(suggestions, virtualViewId)?.text.orEmpty()
                NODE_APPS -> if (toolbarPinned) "Show suggestions" else "Show toolbar"
                NODE_CLIPBOARD -> "Paste"
                NODE_SETTINGS -> "Settings"
                NODE_STYLE -> "Change theme"
                NODE_SPARKLE -> context.getString(R.string.wifm_content_desc)
                NODE_POINTER -> "Write it for me, empty bar"
                NODE_MIC -> "Voice typing"
                else -> "Paste"
            }
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.setBoundsInParent(
                android.graphics.Rect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            )
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (mode != Mode.SUGGESTIONS || !isShown(virtualViewId)) return false
            when (virtualViewId) {
                in 0..2 -> liftOnWordSlot(
                    BarRules.pressOn(suggestions, virtualViewId, composingWord?.invoke().orEmpty()), false, false
                )
                NODE_POINTER -> listener?.onWifmRequested()
                else -> dispatchTap(virtualViewId)
            }
            return true
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, barTouchHelper)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        barTouchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun dp(v: Int): Float = v * resources.displayMetrics.density
    private fun sp(v: Int): Float = v * resources.displayMetrics.scaledDensity

    companion object {
        /** Hold-to-prompt delay: short enough to feel like a quick gesture. */
        private const val WIFM_LONG_MS = 400L
        /** A proposal, about the length of a pause in typing. What an event per key would cost was not measured. */
        private const val NODES_CHANGED_DELAY_MS = 200L
        /** Toolbar slot count: clipboard, settings, theme. The row lays out
         *  what it has; a slot without a backing feature is never added. */
        private const val TOOLBAR_SLOT_COUNT = 3
        /** Candidates <-> toolbar swap crossfade. At ~100 ms it reads as one
         *  motion, not a blink; scaled by the system animator duration scale
         *  at swap time. */
        private const val CHROME_SWAP_MS = 100f
        /** Node ids. 0 to 2 are the word slots, and the buttons use their numbers from the bar's touch handling. */
        private const val NODE_SPARKLE = 3
        private const val NODE_MIC = 5
        private const val NODE_CHIP = 6
        private const val NODE_POINTER = 8
        private const val NODE_APPS = 9
        private const val NODE_CLIPBOARD = 10
        private const val NODE_SETTINGS = 11
        private const val NODE_STYLE = 12
        /** The order a screen reader reaches them in: as they lie, left to right. */
        private val NODE_ORDER = intArrayOf(NODE_APPS, NODE_CHIP, 0, 1, NODE_POINTER, 2, NODE_MIC, NODE_SPARKLE, NODE_CLIPBOARD, NODE_SETTINGS, NODE_STYLE)
    }
}
