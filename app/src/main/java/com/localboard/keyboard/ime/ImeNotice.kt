package com.localboard.keyboard.ime

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import com.localboard.keyboard.R
import com.localboard.keyboard.ui.KeyType

/**
 * A short message from the keyboard, shown inside its own window.
 *
 * Toasts cannot do this job: the system suppresses a toast from a package that may not post
 * notifications unless that package is in the foreground, and a keyboard never is. Every failure
 * reported that way simply went unseen, and asking for the notification permission only to get
 * them back would be the wrong trade.
 *
 * This is a real view so that screen readers announce it and the accessibility tree holds its
 * text. It lies on top of the prediction bar and takes no space of its own, so showing it cannot
 * move a key or change the height of the window.
 */
internal class NoticeState {
    var view: TextView? = null
    var onTap: (() -> Unit)? = null
    var showing = false
    val handler = Handler(Looper.getMainLooper())
    var hide: Runnable? = null
    var reader: ScrollView? = null
    var readerText: TextView? = null
}

/** About three rows of keys. A long rewrite scrolls. */
private const val READER_BAR_HEIGHTS = 3

private const val NOTICE_SHORT_MS = 4_000
private const val NOTICE_LONG_MS = 7_000
/** Something to act on stays longer. It only lies on the bar, and any key takes it away. */
private const val NOTICE_TAPPABLE_MS = 12_000

/**
 * Puts the notice into the input view, once per view. It stays laid out while hidden, so showing
 * it asks for no new layout pass unless the bar's buttons have changed its width.
 */
internal fun LLMKeyboardService.attachNoticeView() {
    if (!predictionBarInitialized || !keyboardViewInitialized) return
    val state = layoutState.notice
    val root = predictionBar.parent as? ViewGroup ?: return
    if (state.view?.parent === root) return
    state.handler.removeCallbacksAndMessages(null)
    state.showing = false

    val density = resources.displayMetrics.density
    val pad = (12 * density).toInt()
    val height = resources.getDimensionPixelSize(R.dimen.prediction_bar_height)
    val notice = TextView(this).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 13f
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(pad, 0, pad, 0)
        visibility = View.INVISIBLE
        // Above the bar and its neighbours, for drawing and for touches. No shadow: this is a
        // message lying on the bar, not a sheet floating over it.
        translationZ = density
        outlineProvider = null
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener {
            val tap = state.onTap
            dismissNotice()
            tap?.invoke()
        }
        // With nothing to do on a tap, the touch takes the notice away and goes on to the bar underneath by
        // itself, since this is not clickable. It could not see the words under the notice, so the bar is told.
        setOnTouchListener { _, event ->
            if (state.onTap == null && event.actionMasked == MotionEvent.ACTION_DOWN) {
                predictionBar.noticeTakenAwayBy(event.downTime)
                dismissNotice()
            }
            false
        }
    }
    // First in the column with a negative margin as tall as itself: it adds nothing to the
    // column's height, and it stays at the top when the emoji panel takes the bar away.
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    lp.bottomMargin = -height
    root.addView(notice, 0, lp)
    state.view = notice
    attachDraftReader(root, height)

    val before = keyboardView.onKeyPressed
    keyboardView.onKeyPressed = { type ->
        before?.invoke(type)
        // Backspace has already acted by now, and may have just said something of its own.
        if (type != KeyType.DELETE) dismissNotice()
    }
}

/**
 * The notice stops where the bar's buttons start. "Tap the sparkle" has to leave the sparkle there to
 * see, and a tap aimed at the check mark must not run the notice's action instead. The notice adds
 * nothing to the column's height, so a change of width moves nothing else.
 */
private fun LLMKeyboardService.leaveBarButtonsUncovered(notice: TextView) {
    val lp = notice.layoutParams as? ViewGroup.MarginLayoutParams ?: return
    val margin = if (predictionBar.visibility == View.VISIBLE) predictionBar.actionButtonsWidth() else 0
    if (lp.rightMargin != margin) {
        lp.rightMargin = margin
        notice.layoutParams = lp
    }
}

/**
 * Shows [text] over the prediction bar until it times out, a key is pressed or it is tapped.
 * With [onTap] the whole message is a button, and its text should say what a tap does. Without
 * one a touch takes the notice away and passes through to the bar, where it lands nothing the notice was hiding.
 * Safe from any thread. A newer notice replaces an older one.
 */
internal fun LLMKeyboardService.showNotice(
    text: CharSequence,
    long: Boolean = false,
    onTap: (() -> Unit)? = null
) {
    if (Looper.myLooper() !== Looper.getMainLooper()) {
        layoutState.notice.handler.post { showNotice(text, long, onTap) }
        return
    }
    attachNoticeView()
    val state = layoutState.notice
    val notice = state.view ?: return
    state.hide?.let(state.handler::removeCallbacks)
    state.onTap = onTap
    state.showing = true
    val colors = theme
    if (colors != null) {
        // Fully opaque whatever the theme: the bar's own words are right underneath.
        notice.setBackgroundColor(colors.background or (0xFF shl 24))
        notice.setTextColor(if (onTap != null) colors.accent else colors.text)
    }
    notice.text = text
    notice.isClickable = onTap != null
    leaveBarButtonsUncovered(notice)
    notice.visibility = View.VISIBLE
    val shownFor = when {
        onTap != null -> NOTICE_TAPPABLE_MS
        long -> NOTICE_LONG_MS
        else -> NOTICE_SHORT_MS
    }
    val hide = state.hide ?: Runnable { dismissNotice() }.also { state.hide = it }
    state.handler.postDelayed(hide, recommendedNoticeMs(shownFor))
}

internal fun LLMKeyboardService.showNotice(
    @StringRes text: Int,
    long: Boolean = false,
    onTap: (() -> Unit)? = null
) = showNotice(getString(text), long, onTap)

/** Runs on every key press, so it does nothing at all unless a notice is up. */
internal fun LLMKeyboardService.dismissNotice() {
    val state = layoutState.notice
    if (!state.showing) return
    state.showing = false
    state.onTap = null
    state.hide?.let(state.handler::removeCallbacks)
    state.view?.apply {
        visibility = View.INVISIBLE
        text = ""
    }
}

/**
 * The whole of a draft, for when it does not fit the bar: a draft is there to be read before it is
 * accepted, and a dialog would take the window focus and with it the connection to the field. Made
 * the same way as the notice, lying over the top of the keys with no space of its own, directly
 * under the bar so the check and the cross stay in reach.
 */
private fun LLMKeyboardService.attachDraftReader(root: ViewGroup, barHeight: Int) {
    val state = layoutState.notice
    val density = resources.displayMetrics.density
    val pad = (12 * density).toInt()
    val text = TextView(this).apply {
        textSize = 15f
        setPadding(pad, pad / 2, pad, pad / 2)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { dismissDraftReader() }
    }
    val reader = ScrollView(this).apply {
        addView(text)
        visibility = View.INVISIBLE
        translationZ = density
        outlineProvider = null
    }
    val height = barHeight * READER_BAR_HEIGHTS
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    lp.bottomMargin = -height
    // Right under the bar: the views after it start at the same place.
    root.addView(reader, root.indexOfChild(predictionBar) + 1, lp)
    state.reader = reader
    state.readerText = text
    predictionBar.onDraftTextTapped = {
        when {
            reader.visibility == View.VISIBLE -> dismissDraftReader()
            // Only once it is finished: the text still arriving has not been reviewed yet, and the
            // reader would go on showing it after the finished draft had replaced it in the bar.
            draftState.inReview -> showDraftReader(lastWifmDraft)
            draftState.slowOffered -> {
                discardWifm()
                openModelSettings()
            }
        }
    }
}

private fun LLMKeyboardService.showDraftReader(draft: String) {
    val state = layoutState.notice
    val reader = state.reader ?: return
    val text = state.readerText ?: return
    val colors = theme
    if (colors != null) {
        reader.setBackgroundColor(colors.background or (0xFF shl 24))
        text.setTextColor(colors.text)
    }
    text.text = draft
    reader.scrollTo(0, 0)
    reader.visibility = View.VISIBLE
}

/** Runs whenever a draft ends, so it does nothing at all unless the reader is open. */
internal fun LLMKeyboardService.dismissDraftReader() {
    val state = layoutState.notice
    val reader = state.reader ?: return
    if (reader.visibility != View.VISIBLE) return
    reader.visibility = View.INVISIBLE
    state.readerText?.text = ""
}

/** Honours the longer reading time someone may have asked for in the accessibility settings. */
private fun LLMKeyboardService.recommendedNoticeMs(ms: Int): Long {
    val manager = getSystemService(AccessibilityManager::class.java) ?: return ms.toLong()
    return manager.getRecommendedTimeoutMillis(
        ms,
        AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
    ).toLong()
}
