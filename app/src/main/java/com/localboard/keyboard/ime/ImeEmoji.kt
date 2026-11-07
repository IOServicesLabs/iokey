package com.localboard.keyboard.ime

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.localboard.keyboard.R
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.DEFAULT_EMOJIS
import com.localboard.keyboard.ui.EmojiPanel
import com.localboard.keyboard.ui.KeyboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * State only this file needs. The service holds one instance, so adding a
 * field leaves it untouched.
 */
internal class EmojiState {
    /** Search field tapped: the letter keyboard sits under the results and
     *  keystrokes are diverted to the panel instead of the editor. */
    var searchOpen = false

    /** How the panel is currently attached (full-height vs search chrome). */
    var attachedAsSearch = false

    /** The keyboard listener swapped out while search diverts keys. */
    var savedListener: com.localboard.keyboard.ui.KeyboardView.KeyboardListener? = null
    var savedSwipeEnabled = true
    var searchListener: com.localboard.keyboard.ui.KeyboardView.KeyboardListener? = null

    /** What the panel's search field holds, so the view can be rebuilt. */
    var searchQuery = ""

    /** Keyboard height captured at full size; a squished search relayout
     *  must not become the new "full" height. */
    var lastKeyboardHeightPx = 0
}

/**
 * Forget what belonged to the input view being replaced. The service outlives it, so
 * without this the panel keeps the first height, theme and context it was built with,
 * which is wrong after a rotation or a resolution change, and the quick row of the new
 * view stays empty because it looks like the row already drawn.
 */
internal fun LLMKeyboardService.resetEmojiViewState() {
    emojiPanel = null
    lastEmojiRowShown = false
    lastEmojiRowItems = emptyList()
    lastEmojiRowBg = 0
    // The old keyboard view is discarded with the input view; the new one
    // arrives with its own listener and swipeEnabled, so nothing here must
    // be restored — only the bookkeeping resets.
    emojiState.searchOpen = false
    emojiState.attachedAsSearch = false
    emojiState.savedListener = null
    emojiState.searchQuery = ""
    emojiState.lastKeyboardHeightPx = 0
}

internal fun LLMKeyboardService.warmEmojiCatalog() {
    // The emoji catalog's lazy build is big; warm it off the main thread
    // so the first panel open doesn't hitch.
    serviceScope.launch(Dispatchers.Default) {
        try { com.localboard.keyboard.util.EmojiCatalog.categories } catch (_: Throwable) { }
    }
}

// ------------------------------------------------------------------
// Emoji panel
// ------------------------------------------------------------------

internal fun LLMKeyboardService.createEmojiCallback(): EmojiPanel.Callback = object : EmojiPanel.Callback {
    override fun onEmojiSelected(emoji: String) {
        try {
            val ic = currentInputConnection ?: return
            beginSelfEdit()
            ic.beginBatchEdit()
            try {
                finishComposingWord()
                ic.commitText(emoji, 1)
            } finally {
                ic.endBatchEdit()
                endSelfEdit()
            }
            prefs.addRecentEmoji(emoji)
            refreshEmojiRow()
            emojiPanel?.refresh()
        } catch (t: Throwable) {
            android.util.Log.e("LLMKeyboard", "emoji select failed", t)
        }
    }

    override fun onEmojiSearchOpened() {
        emojiState.searchOpen = true
        divertKeysForEmojiSearch()
        // Re-layout: panel shrinks to header + results, letter keyboard under.
        showEmojiPanel()
    }

    override fun onEmojiSearchClosed() {
        emojiState.searchOpen = false
        restoreKeysAfterEmojiSearch()
        showEmojiPanel()
    }

    override fun onEmojiBackspace() {
        val ic = currentInputConnection ?: return
        if (deleteSelectionIfAny(ic)) return
        if (!composing.isEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            onComposingDelete()
            beginSelfEdit()
            ic.setComposingText(composing.toString(), 1)
            endSelfEdit()
            refreshPredictions()
            return
        }
        onDeleteWithEmptyComposing()
        val before = (ic.getTextBeforeCursor(24, 0) ?: "").toString()
        if (before.isEmpty()) return
        beginSelfEdit()
        ic.deleteSurroundingText(deleteUnitsFor(before), 0)
        endSelfEdit()
    }

    override fun onCloseEmojiPanel() = closeEmojiPanel()

    override fun onEmojiSpace() {
        handleText(" ")
        // Same convention as the symbols page: space means "back to
        // words" — close the panel too.
        closeEmojiPanel()
    }

    override fun onEmojiEnter() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            flushMessageLog("emoji-enter-action")
            ic.performEditorAction(action)
        } else {
            beginSelfEdit()
            ic.commitText("\n", 1)
            endSelfEdit()
        }
    }
}

internal fun LLMKeyboardService.showEmojiPanel() {
    try {
        // Already open in this layout: applyKeyboardModel() re-enters here on
        // every model refresh; recreating the panel mid-scroll would reset
        // position. A search open/close is a layout change and re-attaches.
        val existing = emojiPanel
        if (existing?.parent === container &&
            emojiState.attachedAsSearch == emojiState.searchOpen
        ) return
        // The container is wrap_content: capture the keyboard's height
        // BEFORE removing it, so the panel matches it exactly and the IME
        // window doesn't collapse or balloon (that blanked the keyboard).
        // Only a full-size layout is a trustworthy source: in search mode
        // the keyboard is re-attached squished, and that height must not
        // become the new reference — neither while search is open nor on
        // the way out (attachedAsSearch still true from the search layout).
        val kbHeight = if (keyboardView.parent === container &&
            !emojiState.attachedAsSearch
        ) {
            keyboardView.height.takeIf { it > 0 }
                ?: emojiState.lastKeyboardHeightPx.takeIf { it > 0 }
                ?: (resources.displayMetrics.density * 264).toInt()
        } else {
            emojiState.lastKeyboardHeightPx.takeIf { it > 0 }
                ?: (resources.displayMetrics.density * 264).toInt()
        }
        emojiState.lastKeyboardHeightPx = kbHeight
        // THE KEYBOARD MUST NOT CHANGE HEIGHT.
        //
        // Hiding the suggestion bar frees a row, but the IME window is
        // wrap_content: hiding it ALONE made the whole keyboard shorter
        // when emoji opened and taller again on the way out. That jump is
        // what made the emoji page feel like a different surface instead
        // of another page of the same keyboard.
        //
        // So the freed row goes to the PANEL rather than back to the
        // window: panel height = keyboard height + bar height, leaving the
        // total exactly as it was, with the grid genuinely a row taller.
        val barHeight = if (predictionBarInitialized &&
            predictionBar.visibility == View.VISIBLE
        ) {
            predictionBar.height.takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(R.dimen.prediction_bar_height)
        } else 0
        if (predictionBarInitialized) predictionBar.visibility = View.GONE
        val panelHeight = kbHeight + barHeight
        // ONE panel instance, re-attached on each open instead of rebuilt:
        // construction walks the whole emoji catalog and lays out the
        // grid, which is pure churn on every open/close cycle. The layout
        // params are re-set on each attach, so a height change (rotation)
        // still lands.
        val reused = existing != null
        val panel = existing ?: EmojiPanel(
            this, prefs, theme ?: KeyboardTheme.load(this, prefs),
            emojiCallback, panelHeight
        ).also { emojiPanel = it }
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        container.removeAllViews()
        if (emojiState.searchOpen && keyboardViewInitialized) {
            // Search: the panel keeps header + results, the letter keyboard
            // takes the rest, the total window height is exactly as before.
            // The keyboard needs ~132dp for its four rows at the shortest
            // allowed key height; everything above that may go to search.
            // The floor is only the 48dp header, never a fixed 96dp: on a
            // short window (small landscape) a stubborn 96dp pushed the
            // board under its floor and the window clipped its bottom rows.
            val density = resources.displayMetrics.density
            val minBoardPx = (density * 132).toInt()
            val minSearchPx = (density * 48).toInt()
            val searchH = panel.searchChromeHeightPx()
                .coerceIn(minSearchPx, (panelHeight - minBoardPx).coerceAtLeast(minSearchPx))
            // KeyboardView measures its own intrinsic height and ignores
            // the height spec; without this cap it would draw full-size,
            // the column would overflow panelHeight, and the system would
            // clip the keyboard's bottom rows (the search chrome stole
            // their space). The cap shortens the keys to fit instead.
            keyboardView.maxBoardHeightPx = panelHeight - searchH
            val column = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            column.addView(panel, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, searchH))
            column.addView(keyboardView, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelHeight - searchH))
            container.addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, panelHeight))
        } else {
            keyboardView.maxBoardHeightPx = Int.MAX_VALUE
            container.addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelHeight
                )
            )
        }
        emojiState.attachedAsSearch = emojiState.searchOpen
        // A reused panel was built before any recents picked while it was
        // closed — refresh so the recents tab isn't stale on reopen.
        if (reused) panel.refresh()
    } catch (t: Throwable) {
        android.util.Log.e("LLMKeyboard", "emoji panel failed", t)
        showNotice("Emoji panel failed to open")
        showingEmoji = false
        emojiPanel = null
        applyKeyboardModel()
    }
}

internal fun LLMKeyboardService.closeEmojiPanel() {
    showingEmoji = false
    restoreKeysAfterEmojiSearch()
    emojiState.searchOpen = false
    emojiPanel?.let { panel ->
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
    }
    // The instance stays alive (see showEmojiPanel); only the attachment
    // goes. applyKeyboardModel re-adds the keyboard view below.
    applyKeyboardModel()
    refreshEmojiRow()
}

// ------------------------------------------------------------------
// Emoji search key diversion
// ------------------------------------------------------------------
//
// While the panel's search field is open, the letter keyboard is back on
// screen and every keystroke must reach the search query, not the editor.
// The KeyboardListener the service installed belongs to another file, so
// instead of editing it this file wraps it: the wrapper delegates every
// event 1:1 while search is inactive, and routes text, space and backspace
// to the panel while it is. Gliding is disabled for the duration — a
// decoded word has nowhere sensible to go mid-search.

private fun LLMKeyboardService.divertKeysForEmojiSearch() {
    if (emojiState.savedListener != null) return
    emojiState.savedListener = keyboardView.listener
    emojiState.savedSwipeEnabled = keyboardView.swipeEnabled
    emojiState.searchQuery = ""
    val listener = emojiState.searchListener
        ?: createEmojiSearchListener().also { emojiState.searchListener = it }
    keyboardView.listener = listener
    keyboardView.swipeEnabled = false
}

private fun LLMKeyboardService.restoreKeysAfterEmojiSearch() {
    val saved = emojiState.savedListener ?: return
    if (keyboardViewInitialized) {
        keyboardView.listener = saved
        keyboardView.swipeEnabled = emojiState.savedSwipeEnabled
        // Defense in depth: showEmojiPanel's normal branch resets the cap
        // too, but a restore without a re-layout (rotation mid-search)
        // must not leave shortened keys behind.
        keyboardView.maxBoardHeightPx = Int.MAX_VALUE
    }
    emojiState.savedListener = null
    emojiState.searchQuery = ""
}

private fun LLMKeyboardService.createEmojiSearchListener():
    com.localboard.keyboard.ui.KeyboardView.KeyboardListener =
    object : com.localboard.keyboard.ui.KeyboardView.KeyboardListener {
        private val active get() = emojiState.savedListener != null

        private fun appendQuery(text: String) {
            emojiState.searchQuery += text
            emojiPanel?.setSearchQuery(emojiState.searchQuery)
        }

        override fun onText(text: String) {
            if (active) return appendQuery(text)
            emojiState.savedListener?.onText(text)
        }

        override fun onKey(type: com.localboard.keyboard.ui.KeyType) {
            if (!active) return emojiState.savedListener?.onKey(type) ?: Unit
            when (type) {
                com.localboard.keyboard.ui.KeyType.DELETE -> {
                    emojiState.searchQuery = emojiState.searchQuery.dropLast(1)
                    emojiPanel?.setSearchQuery(emojiState.searchQuery)
                }
                com.localboard.keyboard.ui.KeyType.SPACE -> appendQuery(" ")
                // Shift, page keys, Enter: swallowed while searching.
                else -> Unit
            }
        }

        override fun onSwipePath(points: List<android.graphics.PointF>): Boolean {
            if (!active) return emojiState.savedListener?.onSwipePath(points) ?: false
            return false
        }

        override fun onSwipeStarted() {
            if (!active) emojiState.savedListener?.onSwipeStarted()
        }

        override fun onSwipeEnded() {
            if (!active) emojiState.savedListener?.onSwipeEnded()
        }

        override fun onSwipeProgress(points: List<android.graphics.PointF>) {
            if (!active) emojiState.savedListener?.onSwipeProgress(points)
        }

        override fun onSwipeBarHover(xFraction: Float) {
            if (!active) emojiState.savedListener?.onSwipeBarHover(xFraction)
        }

        override fun onSwipeBarPick(xFraction: Float): Boolean =
            if (active) false
            else emojiState.savedListener?.onSwipeBarPick(xFraction) ?: false

        override fun onWordDelete() {
            if (!active) return emojiState.savedListener?.onWordDelete() ?: Unit
            // Hold-delete clears the whole query, the search analogue of
            // deleting a word.
            emojiState.searchQuery = ""
            emojiPanel?.setSearchQuery("")
        }

        override fun onSpaceLongPress() {
            if (!active) emojiState.savedListener?.onSpaceLongPress()
        }

        override fun onEnterLongPress() {
            if (!active) emojiState.savedListener?.onEnterLongPress()
        }

        override fun onWifmLongPressed() {
            if (!active) emojiState.savedListener?.onWifmLongPressed()
        }

        override fun onSpaceCursorMove(delta: Int) {
            if (!active) emojiState.savedListener?.onSpaceCursorMove(delta)
        }
    }

// ------------------------------------------------------------------
// Emoji quick row (optional, above the keyboard)
// ------------------------------------------------------------------

internal fun LLMKeyboardService.refreshEmojiRow() {
    val show = prefs.emojiRow && !showingEmoji
    val items = if (show) {
        (prefs.recentEmojis + DEFAULT_EMOJIS).distinct().take(9)
    } else {
        emptyList()
    }
    val bg = theme?.background ?: 0
    if (show == lastEmojiRowShown && items == lastEmojiRowItems &&
        (!show || bg == lastEmojiRowBg)
    ) {
        return
    }
    lastEmojiRowShown = show
    lastEmojiRowItems = items
    lastEmojiRowBg = bg
    emojiRow.removeAllViews()
    if (!show) {
        emojiRow.visibility = View.GONE
        return
    }
    emojiRow.visibility = View.VISIBLE
    emojiRow.setBackgroundColor(bg)
    val ctx = this
    for (e in items) {
        val tv = android.widget.TextView(ctx).apply {
            text = e
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            setOnClickListener { insertEmojiFromRow(e) }
        }
        emojiRow.addView(tv)
    }
    // Trailing "open full panel" affordance.
    val more = android.widget.TextView(ctx).apply {
        text = "⋯"
        textSize = 22f
        gravity = android.view.Gravity.CENTER
        setTextColor(theme?.textSecondary ?: 0)
        layoutParams = android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
        )
        setOnClickListener {
            showingEmoji = true
            cancelAiSuggestion()
            applyKeyboardModel()
            refreshEmojiRow()
        }
    }
    emojiRow.addView(more)
    // Fixed comfortable height, set here rather than posted: this runs while the
    // keyboard is coming up, and posted, it resized the window a frame after the show.
    val h = (resources.displayMetrics.density * 44).toInt()
    if (emojiRow.layoutParams.height != h) {
        emojiRow.layoutParams = emojiRow.layoutParams.apply { height = h }
    }
}

internal fun LLMKeyboardService.insertEmojiFromRow(emoji: String) {
    val ic = currentInputConnection ?: return
    finishComposingWord()
    beginSelfEdit()
    ic.commitText(emoji, 1)
    endSelfEdit()
    prefs.addRecentEmoji(emoji)
    refreshEmojiRow()
    refreshPredictions()
}
