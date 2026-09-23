package com.localboard.keyboard.util

import android.graphics.Paint

/**
 * The emoji the panel actually shows: [EmojiData]'s original set plus
 * everything added to Unicode since, minus anything this device cannot draw.
 *
 * WHY A SEPARATE FILE
 * -------------------
 * EmojiData.kt is dense with surrogate pairs and was once truncated to zero
 * bytes by a careless rewrite. It is treated as read-only; additions live
 * here and are merged at runtime. Nothing needs to edit that file again.
 *
 * WHY FILTER AT RUNTIME
 * ---------------------
 * "Latest emoji" is not a property of the keyboard — it is a property of the
 * system font. Unicode 16 characters on a phone whose font stops at 15.1 draw
 * as tofu boxes, and a grid of empty rectangles is worse than a shorter list.
 * [Paint.hasGlyph] asks the font directly, so the panel shows exactly what
 * this device can render and silently drops the rest. Ship generously, render
 * honestly.
 */
object EmojiCatalog {

    /**
     * Additions by category name, newest Unicode blocks included. Names match
     * EmojiData.categories exactly; anything unmatched is ignored rather than
     * creating a stray category.
     */
    private val ADDITIONS: Map<String, List<String>> = mapOf(
        "Smileys" to listOf(
            "🥲", "🥸", "🥱", "🫠", "🫢", "🫣", "🫡", "🫥", "🫤", "🥹", "🫨", "🙂", "🩵"
        ),
        "People" to listOf(
            "🤌", "🤏", "🫰", "🫱", "🫲", "🫳", "🫴", "🫵", "🫶", "🫂",
            "🫃", "🫄", "🧌", "🧑", "🩷", "🩶", "🫆"
        ),
        "Animals & Nature" to listOf(
            "🦭", "🦬", "🦣", "🦫", "🪶", "🪱", "🪰", "🪲", "🪳", "🪴",
            "🪸", "🪹", "🪺", "🫎", "🫏", "🪼", "🪽", "🪾"
        ),
        "Food & Drink" to listOf(
            "🫐", "🫒", "🫑", "🫓", "🫔", "🫕", "🫖", "🧋", "🫘", "🫗",
            "🫙", "🫛", "🫚", "🧉", "🧊"
        ),
        "Activities" to listOf(
            "🪀", "🪁", "🛝", "🛞", "🪩", "🪅", "🪆", "🥏", "🪇", "🪈"
        ),
        "Travel & Places" to listOf(
            "🛻", "🛼", "🛖", "🛗", "🪝", "🛞", "🏔", "🪐"
        ),
        "Objects" to listOf(
            "🪄", "🪞", "🪟", "🪠", "🪤", "🪥", "🪒", "🩼", "🩻", "🫧",
            "🪫", "🪪", "🪬", "🪮", "🪭", "🪯", "🛗"
        ),
        "Symbols" to listOf(
            "🩷", "🩵", "🩶", "🫱", "♾", "🔯", "🪯"
        )
    )

    private val glyphPaint by lazy { Paint() }
    // ConcurrentHashMap: the panel build and the quick-bar draw hit this from
    // different threads; getOrPut on a plain HashMap was not safe there.
    private val glyphCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>(512)

    /** True when the system font can actually draw [s]. */
    fun renderable(s: String): Boolean = glyphCache[s] ?: synchronized(glyphPaint) {
        try {
            glyphPaint.hasGlyph(s)
        } catch (_: Throwable) {
            true    // never hide an emoji because the check itself failed
        }
    }.also { glyphCache[s] = it }

    /**
     * Merged, de-duplicated, device-renderable categories. Built once.
     *
     * The original entries come first so the familiar emoji stay where muscle
     * memory expects them; newer additions land at the end of their category.
     */
    val categories: List<Pair<String, List<String>>> by lazy {
        EmojiData.categories.map { (name, base) ->
            val seen = LinkedHashSet<String>(base.size + 32)
            for (e in base) if (renderable(e)) seen.add(e)
            ADDITIONS[name]?.forEach { if (renderable(it)) seen.add(it) }
            name to seen.toList()
        }
    }

    /** How many additions this device could not draw — shown in diagnostics
     *  so "why is there no new emoji" has an answer other than a shrug. */
    fun unsupportedCount(): Int =
        ADDITIONS.values.sumOf { list -> list.count { !renderable(it) } }
}
