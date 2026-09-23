package com.localboard.keyboard.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.localboard.keyboard.R
import com.localboard.keyboard.util.EmojiData
import com.localboard.keyboard.util.EmojiTone
import com.localboard.keyboard.util.Prefs

/**
 * Emoji panel, built entirely in code (no XML, no RecyclerView).
 *
 * The panel is exactly [keyboardHeightPx] tall — the height the keyboard had
 * on screen — because the container is wrap_content: without an explicit
 * height the panel would measure its scroll content unbounded and blow the
 * IME window up (the "keyboard crashes after picking an emoji" failure).
 *
 * Emoji are laid out in fixed-height columns inside a HorizontalScrollView:
 * swipe sideways through the active category, tabs switch categories.
 */
class EmojiPanel(
    context: Context,
    private val prefs: Prefs,
    private val theme: KeyboardTheme,
    private val callback: Callback,
    private val keyboardHeightPx: Int
) : FrameLayout(context) {

    interface Callback {
        fun onEmojiSelected(emoji: String)
        fun onEmojiBackspace()
        fun onEmojiSpace()
        fun onEmojiEnter()
        fun onCloseEmojiPanel()
    }

    private val columnsRow: LinearLayout
    private val tabsRow: LinearLayout
    private var selectedTab = Int.MIN_VALUE
    init {
        setBackgroundColor(theme.background)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Category tabs are built here but attached LOWER DOWN — see the
        // strip above the bottom row. Emoji get the top of the panel, the way
        // letters do on the normal keyboard.
        tabsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabsRow)
        }

        // NO SEARCH FIELD. An IME cannot sensibly host an editable field: the
        // keyboard is already the input method for somebody else's editor, so
        // typing into a box inside the keyboard's own window does not work.
        // The skin-tone chip that used to live beside it moved onto the tab
        // strip, where it is still one tap away.

        // Emoji columns (horizontal paging).
        columnsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(columnsRow)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom bar: the keyboard's own bottom row — ABC, space, backspace,
        // enter — styled like real keys, so the emoji page feels like a page
        // of the keyboard (not a separate widget) and never strands you
        // without a space bar.
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }
        fun keyBg() = android.graphics.drawable.GradientDrawable().apply {
            setColor(theme.key)
            cornerRadius = dp(6).toFloat()
        }
        fun textKey(label: String, size: Float, onClick: () -> Unit) =
            TextView(context).apply {
                text = label
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(theme.text)
                background = keyBg()
                contentDescription = label
                setOnClickListener { onClick() }
            }
        fun lp(weight: Float) = LinearLayout.LayoutParams(0, dp(46), weight).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
        // ── Tab strip + backspace ────────────────────────────────────────
        //
        // This sits directly above the bottom row, which puts BACKSPACE in the
        // same place it occupies on the letter keyboard: right-hand end of the
        // row above the space bar, same 1.5 weight. It used to live inside the
        // bottom row itself, so switching to emoji moved backspace sideways and
        // your thumb had to relearn it. The tab strip fills the rest of the row
        // instead of costing a row of its own at the top.
        val tabStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tone = TextView(context).apply {
            text = EmojiTone.toneSwatch(prefs.emojiSkinTone)
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = "Skin tone"
            setOnClickListener {
                prefs.emojiSkinTone = (prefs.emojiSkinTone + 1) % 6
                text = EmojiTone.toneSwatch(prefs.emojiSkinTone)
                val keep = selectedTab
                selectedTab = Int.MIN_VALUE
                showCategory(keep)
            }
        }
        tabStrip.addView(tone, LinearLayout.LayoutParams(dp(38), dp(40)))
        tabStrip.addView(
            tabScroll,
            LinearLayout.LayoutParams(0, dp(40), 1f)
        )
        tabStrip.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_backspace)
                contentDescription = "backspace"
                setColorFilter(theme.text)
                background = keyBg()
                setOnClickListener { callback.onEmojiBackspace() }
            },
            LinearLayout.LayoutParams(dp(56), dp(40)).apply {
                setMargins(dp(3), dp(2), dp(4), dp(2))
            }
        )
        root.addView(tabStrip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        // ── Bottom row ───────────────────────────────────────────────────
        //
        // Mode key left, enter right, space filling everything between —
        // matching the letter keyboard's anchors, at the same total width, so
        // nothing shifts under your thumb when you switch to emoji.
        //
        // NO PERIOD KEY. It was here briefly because the letter keyboard has
        // one in that slot, which was mirroring the layout without asking what
        // the key is FOR: nobody types a full stop while choosing an emoji.
        // Its width goes to the space bar instead, the same way switching off
        // an optional bottom-row key widens space on the main keyboard.
        bottom.addView(textKey("ABC", 14f) { callback.onCloseEmojiPanel() }, lp(1.4f))
        bottom.addView(textKey(" ", 14f) { callback.onEmojiSpace() }, lp(7.6f))
        bottom.addView(textKey("↵", 18f) { callback.onEmojiEnter() }, lp(1.6f))
        root.addView(bottom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildTabs()
        showCategory(if (prefs.recentEmojis.isEmpty()) 0 else RECENTS)
    }

    private fun buildTabs() {
        tabsRow.removeAllViews()
        val icons = listOf("🕘", "😀", "🧑", "🐻", "🍕", "⚽", "✈️", "💡", "❤️")
        icons.forEachIndexed { index, icon ->
            val tv = TextView(context).apply {
                text = icon
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { showCategory(index - 1) }
            }
            tabsRow.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * Rebuild the visible category. The panel instance is kept alive and
     * re-shown across open/close cycles (showCategory itself is idempotent,
     * so re-attach alone never breaks it) — but recents picked while the
     * panel was closed would never appear. Call this when re-showing.
     */
    fun refresh() {
        val keep = selectedTab
        selectedTab = Int.MIN_VALUE
        showCategory(if (keep == Int.MIN_VALUE) 0 else keep)
    }

    private fun showCategory(index: Int) {
        if (index == selectedTab) return
        selectedTab = index
        for (i in 0 until tabsRow.childCount) {
            tabsRow.getChildAt(i).alpha = if (i == index + 1) 1f else 0.5f
        }
        val raw: List<String> = when (index) {
            RECENTS -> prefs.recentEmojis
            else -> com.localboard.keyboard.util.EmojiCatalog.categories.getOrNull(index)?.second ?: emptyList()
        }
        val list = EmojiTone.applyTone(raw, prefs.emojiSkinTone)
        columnsRow.removeAllViews()

        // Cells per column so the grid fits the keyboard height exactly.
        // Compact sizing: more emoji on screen at once.
        // Chrome is the tab strip (dp 44) + the bottom row (dp 52) = dp 96;
        // this was 88, so the grid thought it had 8dp more than it does.
        val contentH = keyboardHeightPx - dp(96)
        val cellH = dp(40)
        val rowsPerCol = (contentH / cellH).coerceIn(3, 7)

        if (index == RECENTS && list.isEmpty()) {
            columnsRow.addView(TextView(context).apply {
                text = "No recent emoji yet"
                setTextColor(theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(dp(24), 0, dp(24), 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            return
        }

        var col: LinearLayout? = null
        var count = 0
        for (emoji in list) {
            if (col == null || count == rowsPerCol) {
                col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                columnsRow.addView(col, LinearLayout.LayoutParams(
                    dp(46), LinearLayout.LayoutParams.MATCH_PARENT))
                count = 0
            }
            val cell = TextView(context).apply {
                text = emoji
                textSize = 20f
                gravity = Gravity.CENTER
                val outValue = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
                setOnClickListener {
                    try {
                        callback.onEmojiSelected(emoji)
                    } catch (t: Throwable) {
                        android.util.Log.e("EmojiPanel", "emoji insert failed", t)
                    }
                }
            }
            col?.addView(cell, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            count++
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val RECENTS = -1
    }
}
