package com.localboard.keyboard.util

import android.graphics.Paint

/**
 * The emoji the panel actually shows: [EmojiData]'s Unicode-set, filtered to
 * what this device can draw.
 *
 * WHY FILTER AT RUNTIME
 * ---------------------
 * "Latest emoji" is not a property of the keyboard — it is a property of the
 * system font. Unicode 18 characters on a phone whose font stops at 15.1 draw
 * as tofu boxes, and a grid of empty rectangles is worse than a shorter list.
 * [Paint.hasGlyph] asks the font directly, so the panel shows exactly what
 * this device can render and silently drops the rest. Ship generously, render
 * honestly.
 *
 * [EmojiData] is generated from emoji-test.txt by tools/emoji/generate.py and
 * already contains every fully-qualified sequence, so there is nothing to
 * merge here anymore; this file is the renderability filter and nothing else.
 */
object EmojiCatalog {

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
     * Device-renderable categories, in [EmojiData]'s Unicode order, built once.
     */
    val categories: List<Pair<String, List<String>>> by lazy {
        EmojiData.categories.map { (name, base) ->
            name to base.filter { renderable(it) }
        }
    }
}
