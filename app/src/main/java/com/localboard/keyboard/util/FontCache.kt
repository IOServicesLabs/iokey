package com.localboard.keyboard.util

import android.content.Context
import android.graphics.Typeface
import com.localboard.keyboard.R

/**
 * Resolves a font id to a [Typeface], once.
 *
 * KeyboardTheme.typeface() is called from view code that has no Context, and
 * loading a font is far too expensive to repeat — Typeface.createFromFile
 * parses the whole file. So the application Context is parked here at startup
 * and every resolved face is cached for the process lifetime.
 *
 * Failing to load a font must never break typing: every path falls back to the
 * system face rather than throwing.
 */
object FontCache {

    @Volatile
    private var appContext: Context? = null

    // ConcurrentHashMap: resolve() is called from the draw path and from
    // settings at the same time, and a plain HashMap's get/put race there.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Absolute path of the user's imported font, when they have one. */
    fun customFontFile(context: Context): java.io.File =
        java.io.File(context.filesDir, "custom_key_font.ttf")

    fun hasCustomFont(context: Context): Boolean =
        customFontFile(context).let { it.exists() && it.length() > 0 }

    fun resolve(fontId: String): Typeface {
        cache[fontId]?.let { return it }
        val face = load(fontId)
        cache[fontId] = face
        return face
    }

    /** Drop a cached face — call after importing a new custom font. */
    fun invalidate(fontId: String) {
        cache.remove(fontId)
        availableCache = null
    }

    @Volatile
    private var availableCache: List<Pair<String, String>>? = null

    /**
     * The fonts this device can actually draw differently, for the picker.
     *
     * Android resolves an unknown font family silently: ask for
     * "sans-serif-rounded" on a ROM that has no rounded face and you get plain
     * Roboto back, with no error and no way to tell from the Typeface. The
     * picker listed every candidate, so on a typical phone four of the seven
     * entries rendered identically and choosing between them did nothing —
     * which is exactly how it looked.
     *
     * So each candidate is measured and dropped if an earlier one already
     * produced the same result. Comparing advance widths of a probe string
     * catches families that alias, and comparing vertical metrics catches
     * weights that differ only in colour on a name. The probe covers the
     * shapes that actually distinguish these faces: round letters, ascenders,
     * descenders, digits and the l/I/1 group.
     *
     * Same idea as EmojiCatalog filtering by Paint.hasGlyph: offer what the
     * device can render, not what the constant list hopes it can.
     */
    fun availableFonts(): List<Pair<String, String>> {
        availableCache?.let { return it }
        val probe = "agoOQ0lI1nmWxyj"
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 64f
        }
        val widths = FloatArray(probe.length)
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>(com.localboard.keyboard.ui.ThemeSpec.FONTS.size)

        for ((id, label) in com.localboard.keyboard.ui.ThemeSpec.FONTS) {
            // Always offered: importing a font is an action, not a face to
            // compare, and its whole point is that it is not one of these.
            if (id == com.localboard.keyboard.ui.ThemeSpec.FONT_CUSTOM) {
                out.add(id to label)
                continue
            }
            val face = try { resolve(id) } catch (_: Throwable) { continue }
            val signature = try {
                paint.typeface = face
                paint.getTextWidths(probe, widths)
                val fm = paint.fontMetrics
                buildString {
                    for (w in widths) { append(w.toInt()); append(',') }
                    append('|')
                    append(fm.ascent.toInt()); append(',')
                    append(fm.descent.toInt()); append(',')
                    append(fm.top.toInt()); append(',')
                    append(fm.bottom.toInt())
                }
            } catch (_: Throwable) {
                continue
            }
            if (seen.add(signature)) out.add(id to label)
        }
        // Belt and braces: never hand back an empty picker. If measurement
        // somehow collapsed everything, the bundled face is still real.
        val result = if (out.size <= 1) com.localboard.keyboard.ui.ThemeSpec.FONTS else out
        availableCache = result
        return result
    }

    private fun load(fontId: String): Typeface {
        val ctx = appContext
        return try {
            when (fontId) {
                com.localboard.keyboard.ui.ThemeSpec.FONT_ATKINSON ->
                    if (ctx != null) {
                        androidx.core.content.res.ResourcesCompat
                            .getFont(ctx, R.font.atkinson_hyperlegible) ?: Typeface.DEFAULT
                    } else Typeface.DEFAULT

                com.localboard.keyboard.ui.ThemeSpec.FONT_CUSTOM -> {
                    val f = ctx?.let { customFontFile(it) }
                    if (f != null && f.exists() && f.length() > 0) {
                        Typeface.createFromFile(f)
                    } else Typeface.DEFAULT
                }

                com.localboard.keyboard.ui.ThemeSpec.FONT_MEDIUM ->
                    Typeface.create("sans-serif-medium", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_ROUNDED ->
                    Typeface.create("sans-serif-rounded", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_CONDENSED ->
                    Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_LIGHT ->
                    Typeface.create("sans-serif-light", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_THIN ->
                    Typeface.create("sans-serif-thin", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_BLACK ->
                    Typeface.create("sans-serif-black", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_CONDENSED_LIGHT ->
                    Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_SMALLCAPS ->
                    Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_CASUAL ->
                    Typeface.create("casual", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_CURSIVE ->
                    Typeface.create("cursive", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_SERIF_MONO ->
                    Typeface.create("serif-monospace", Typeface.NORMAL)
                com.localboard.keyboard.ui.ThemeSpec.FONT_MONO -> Typeface.MONOSPACE
                com.localboard.keyboard.ui.ThemeSpec.FONT_SERIF -> Typeface.SERIF
                else -> Typeface.DEFAULT
            }
        } catch (_: Throwable) {
            // A corrupt imported font, or a resource that failed to decode.
            Typeface.DEFAULT
        }
    }
}
