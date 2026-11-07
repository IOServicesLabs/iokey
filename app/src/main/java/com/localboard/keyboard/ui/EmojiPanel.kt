package com.localboard.keyboard.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.localboard.keyboard.R
import com.localboard.keyboard.util.EmojiCatalog
import com.localboard.keyboard.util.EmojiData
import com.localboard.keyboard.util.EmojiTone
import com.localboard.keyboard.util.Prefs

/**
 * Emoji panel, built entirely in code (no XML, no RecyclerView), to Gboard's
 * pattern:
 *
 * - header row: back arrow, "Emoji" title, a search field, then the category
 *   quick-tabs on the right. A tab tap scrolls the grid to that category.
 * - one continuous vertical grid, nine columns, with a floating section
 *   header ("SMILEYS AND EMOTION") that follows the scroll position.
 * - bottom bar: ABC on the left, the category icons in the middle, the skin
 *   tone chip and Backspace on the right.
 *
 * SEARCH. Tapping the search field swaps the grid for a results area and
 * asks the host to put the letter keyboard underneath (Gboard's pattern:
 * an IME's own window cannot host a working soft-input field — soft-key
 * taps would still commit to the editor being typed in — so the panel's
 * keystrokes are DIVERTED at the host's KeyboardListener while search is
 * open: letters and space append to the query, Backspace edits it, and
 * nothing reaches the editor). Results come from the keyword map in
 * EmojiData plus a category-name index built from the palette, filtered by
 * what this device can draw (EmojiCatalog.renderable).
 *
 * The panel instance is kept alive and re-attached across open/close cycles
 * by the host; [refresh] re-renders the recents section after picks made
 * while the panel was closed.
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
        /** Search field tapped: the host swaps the letter keyboard in under
         *  the results and diverts keystrokes to the panel. */
        fun onEmojiSearchOpened()
        /** Search's back arrow: the host puts the panel back to full height
         *  and restores normal key handling. */
        fun onEmojiSearchClosed()
    }

    private data class Section(
        val name: String,
        val header: TextView,
        val body: LinearLayout,
    )

    /** Gboard's column count for the emoji grid. */
    private val cols = 9
    private val headerH = dp(48)
    private val bottomBarH = dp(48)
    private val gridCellH = dp(36)
    private val resultCellH = dp(48)

    /** Panel height while search is open: header + results (the bottom bar
     *  hides for the duration — Gboard's pattern — so the letter keyboard
     *  underneath keeps as much height as possible). */
    fun searchChromeHeightPx(): Int =
        dp(48 + 96).coerceAtMost(keyboardHeightPx)

    private val gridContent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val gridScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        addView(gridContent)
    }
    private val stickyHeader = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.textSecondary)
        setBackgroundColor(theme.background)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(6), dp(14), dp(6))
        visibility = View.GONE
    }
    private val middle = FrameLayout(context).apply {
        addView(gridScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(stickyHeader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private val resultsBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val resultsScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        addView(resultsBody)
    }

    private val tabsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabButtons = mutableListOf<ImageButton>()
    private val sections = mutableListOf<Section>()
    private lateinit var recentsHeader: TextView
    private lateinit var recentsBody: LinearLayout
    private lateinit var bottomIconRow: LinearLayout
    private lateinit var bottomBar: LinearLayout

    private val queryView = TextView(context).apply {
        textSize = 15f
        setTextColor(theme.text)
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
        // Starts empty; setSearchQuery toggles this with the hint.
        visibility = View.GONE
    }
    private val hintView = TextView(context).apply {
        text = "Search"
        textSize = 15f
        setTextColor(theme.textSecondary)
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val clearBtn = ImageButton(context).apply {
        setImageResource(R.drawable.ic_close)
        imageTintList = ColorStateList.valueOf(theme.textSecondary)
        setBackgroundColor(0)
        contentDescription = "Clear search"
        visibility = View.GONE
        setOnClickListener { setSearchQuery("") }
    }

    private var searchMode = false
    private var searchQuery = ""

    init {
        setBackgroundColor(theme.background)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ── Header: back, title, search field, category tabs ────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val backBtn = ImageButton(context).apply {
            setImageResource(R.drawable.emoji_back)
            imageTintList = ColorStateList.valueOf(theme.text)
            setBackgroundColor(0)
            contentDescription = "Back"
            setOnClickListener {
                if (searchMode) exitSearch() else callback.onCloseEmojiPanel()
            }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val title = TextView(context).apply {
            text = "Emoji"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.text)
            setPadding(dp(6), 0, dp(2), 0)
        }
        header.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val searchPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(theme.key)
                cornerRadius = dp(19).toFloat()
            }
            setPadding(dp(12), 0, dp(6), 0)
            setOnClickListener { if (!searchMode) enterSearch() }
        }
        val searchIcon = ImageView(context).apply {
            setImageResource(R.drawable.emoji_search)
            imageTintList = ColorStateList.valueOf(theme.textSecondary)
        }
        searchPill.addView(searchIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
        searchPill.addView(queryView, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(8), 0, dp(4), 0)
        })
        searchPill.addView(hintView, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(8), 0, dp(4), 0)
        })
        searchPill.addView(clearBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.addView(searchPill, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })
        searchPill.minimumWidth = dp(96)
        // Gboard shows about four category chips here; without a cap the
        // strip measures at all ten icons and squeezes the search field
        // out of the header entirely.
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabsRow)
        }
        header.addView(tabScroll, LinearLayout.LayoutParams(dp(150), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, headerH))

        root.addView(middle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(resultsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Bottom bar: ABC, category icons, tone chip, backspace ───────
        bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val abc = TextView(context).apply {
            text = "ABC"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.text)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = "ABC"
            setOnClickListener { callback.onCloseEmojiPanel() }
        }
        bottomBar.addView(abc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        val iconScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val iconRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomIconRow = iconRow
        iconScroll.addView(iconRow)
        bottomBar.addView(iconScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        val tone = TextView(context).apply {
            text = EmojiTone.toneSwatch(prefs.emojiSkinTone)
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = "Skin tone"
            setOnClickListener {
                prefs.emojiSkinTone = (prefs.emojiSkinTone + 1) % 6
                text = EmojiTone.toneSwatch(prefs.emojiSkinTone)
                buildGrid()
                updateResults()
            }
        }
        bottomBar.addView(tone, LinearLayout.LayoutParams(dp(36), dp(38)))
        val backspace = ImageButton(context).apply {
            setImageResource(R.drawable.ic_backspace)
            imageTintList = ColorStateList.valueOf(theme.text)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(theme.key)
                cornerRadius = dp(6).toFloat()
            }
            contentDescription = "backspace"
            setOnClickListener { callback.onEmojiBackspace() }
        }
        bottomBar.addView(backspace, LinearLayout.LayoutParams(dp(44), dp(38)).apply {
            setMargins(dp(6), 0, dp(4), 0)
        })
        root.addView(bottomBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, bottomBarH))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ── Grid + tabs ─────────────────────────────────────────────────
        buildGrid()
        buildTabs()
        gridScroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateSticky(scrollY) }
        gridScroll.post { updateSticky(gridScroll.scrollY) }
    }

    /** True while the search field owns the header and results fill the grid area. */
    fun searchActive(): Boolean = searchMode

    /**
     * Re-render the recents section. The host calls this when re-showing a
     * reused panel so picks made while it was closed appear.
     */
    fun refresh() {
        refreshRecents()
        if (!searchMode) updateSticky(gridScroll.scrollY)
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** Called by the host for every diverted keystroke while search is open. */
    fun setSearchQuery(q: String) {
        searchQuery = q
        queryView.text = q
        queryView.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        hintView.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
        clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        updateResults()
    }

    private fun enterSearch() {
        searchMode = true
        setSearchQuery("")
        middle.visibility = View.GONE
        resultsScroll.visibility = View.VISIBLE
        stickyHeader.visibility = View.GONE
        // The category bar gives its 48dp to the keyboard underneath while
        // search is open (Gboard does the same); the header back arrow and
        // the keyboard's own backspace cover navigation.
        bottomBar.visibility = View.GONE
        callback.onEmojiSearchOpened()
    }

    private fun exitSearch() {
        searchMode = false
        // setSearchQuery also resets the pill (hint back, query and clear
        // button hidden) — clearing searchQuery alone would leave the last
        // query rendered in the header after closing search.
        setSearchQuery("")
        resultsScroll.visibility = View.GONE
        middle.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        callback.onEmojiSearchClosed()
    }

    private fun updateResults() {
        resultsBody.removeAllViews()
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            resultsBody.addView(placeholder("Type to search emoji"))
            return
        }
        val results = filterEmoji(q)
        if (results.isEmpty()) {
            resultsBody.addView(placeholder("No emoji found"))
            return
        }
        fillSection(resultsBody, results, resultCellH)
    }

    /** Keyword map first (exact, then prefix), then category-name matches. */
    private fun filterEmoji(q: String): List<String> {
        val out = LinkedHashSet<String>()
        EmojiData.wordToEmoji[q]?.let(out::add)
        for ((word, emoji) in EmojiData.wordToEmoji) {
            if (word.startsWith(q)) out.add(emoji)
        }
        for ((name, emoji) in searchIndex) {
            if (name.contains(q)) out.add(emoji)
        }
        return out.filter { EmojiCatalog.renderable(it) }.take(90)
    }

    /** Lowercased category label per emoji, for keyword-less searches. */
    private val searchIndex: List<Pair<String, String>> by lazy {
        EmojiData.categories.flatMap { (label, list) ->
            val name = label.lowercase().replace("&", " ")
            list.map { name to it }
        }
    }

    // ------------------------------------------------------------------
    // Grid
    // ------------------------------------------------------------------

    private fun displayName(label: String): String =
        label.replace("&", "and").uppercase()

    private fun makeSectionHeader(name: String): TextView = TextView(context).apply {
        text = name
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.textSecondary)
        setBackgroundColor(theme.background)
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }

    private fun buildGrid() {
        gridContent.removeAllViews()
        sections.clear()
        recentsHeader = makeSectionHeader("RECENTS")
        recentsBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        gridContent.addView(recentsHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        gridContent.addView(recentsBody, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        sections.add(Section("RECENTS", recentsHeader, recentsBody))
        for ((label, emojis) in EmojiCatalog.categories) {
            val header = makeSectionHeader(displayName(label))
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fillSection(body, EmojiTone.applyTone(emojis, prefs.emojiSkinTone), gridCellH)
            gridContent.addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            gridContent.addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            sections.add(Section(displayName(label), header, body))
        }
        refreshRecents()
    }

    private fun refreshRecents() {
        val recents = prefs.recentEmojis
        recentsHeader.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        recentsBody.removeAllViews()
        if (recents.isEmpty()) {
            recentsBody.addView(placeholder("No recent emoji yet"))
        } else {
            fillSection(recentsBody, EmojiTone.applyTone(recents, prefs.emojiSkinTone), gridCellH)
        }
    }

    private fun placeholder(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(theme.textSecondary)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun fillSection(body: LinearLayout, emoji: List<String>, cellH: Int) {
        var row: LinearLayout? = null
        var count = 0
        for (e in emoji) {
            if (row == null || count == cols) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                body.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, cellH))
                count = 0
            }
            row.addView(makeCell(e), LinearLayout.LayoutParams(0, cellH, 1f))
            count++
        }
    }

    private fun makeCell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        textSize = 24f
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

    /** Floating category header + tab highlight, driven by the scroll. */
    private fun updateSticky(scrollY: Int) {
        var current: Section? = null
        for (s in sections) {
            if (s.header.visibility == View.VISIBLE && s.header.top <= scrollY + dp(4)) {
                current = s
            }
        }
        if (current == null) {
            stickyHeader.visibility = View.GONE
        } else {
            if (stickyHeader.text != current.name) stickyHeader.text = current.name
            stickyHeader.visibility = View.VISIBLE
        }
        updateTabs(sections.indexOf(current))
    }

    // ------------------------------------------------------------------
    // Tabs (header quick-tabs and bottom-bar icons share the drawables)
    // ------------------------------------------------------------------

    private fun tabIcons(): List<Int> = listOf(
        R.drawable.emoji_recent,
        R.drawable.emoji_smileys,
        R.drawable.emoji_people,
        R.drawable.emoji_animals,
        R.drawable.emoji_food,
        R.drawable.emoji_travel,
        R.drawable.emoji_activities,
        R.drawable.emoji_objects,
        R.drawable.emoji_symbols,
        R.drawable.emoji_flags,
    )

    private fun buildTabs() {
        tabsRow.removeAllViews()
        tabButtons.clear()
        val names = listOf("Recent") + EmojiCatalog.categories.map { it.first }
        val icons = tabIcons()
        names.forEachIndexed { index, name ->
            val icon = icons.getOrElse(index) { R.drawable.emoji_symbols }
            val btn = ImageButton(context).apply {
                setImageResource(icon)
                contentDescription = name
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener {
                    val section = sections.getOrNull(index) ?: return@setOnClickListener
                    gridScroll.post {
                        gridScroll.smoothScrollTo(0, (section.header.top - dp(2)).coerceAtLeast(0))
                    }
                }
            }
            tabsRow.addView(btn, LinearLayout.LayoutParams(dp(40), dp(40)))
            tabButtons.add(btn)
        }
        updateTabs(if (prefs.recentEmojis.isEmpty()) 1 else 0)

        // Bottom-bar category icons (same set, same order, one style).
        names.forEachIndexed { index, name ->
            val icon = icons.getOrElse(index) { R.drawable.emoji_symbols }
            val btn = ImageButton(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(theme.textSecondary)
                setBackgroundColor(0)
                contentDescription = name
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener {
                    val section = sections.getOrNull(index) ?: return@setOnClickListener
                    gridScroll.post {
                        gridScroll.smoothScrollTo(0, (section.header.top - dp(2)).coerceAtLeast(0))
                    }
                }
            }
            bottomIconRow.addView(btn, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
    }

    private fun updateTabs(active: Int) {
        tabButtons.forEachIndexed { index, btn ->
            val on = index == active
            btn.imageTintList = ColorStateList.valueOf(
                if (on) theme.accent else theme.textSecondary)
            btn.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (on) theme.accent and 0x00FFFFFF or (0x33 shl 24) else 0)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
