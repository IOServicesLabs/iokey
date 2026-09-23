package com.localboard.keyboard.ui

import android.graphics.Color
import android.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A complete, user-editable theme definition — every colour the keyboard
 * draws, plus corner radius and key font.
 *
 * Two ways to get one:
 *  - [auto]: the user picks a **background** and an **accent**; everything
 *    else is derived as matching pastels with guaranteed text contrast.
 *  - hand-editing any individual field in the theme studio.
 *
 * [encode]/[decode] round-trip a spec through a short opaque share code so
 * themes can be passed between people.
 */
data class ThemeSpec(
    val name: String,
    val background: Int,
    val key: Int,
    val keyFunctional: Int,
    val text: Int,
    val keyText: Int,
    val textSecondary: Int,
    val accent: Int,
    val divider: Int,
    val popupBackground: Int,
    val trail: Int,
    val radiusDp: Float = 10f,
    val fontId: String = FONT_DEFAULT
) {

    fun toTheme(): KeyboardTheme = KeyboardTheme(
        background = background,
        key = key,
        keyFunctional = keyFunctional,
        text = text,
        textSecondary = textSecondary,
        accent = accent,
        divider = divider,
        trail = trail,
        popupBackground = popupBackground,
        isDark = luminance(background) < 0.5,
        keyRadiusDp = radiusDp,
        keyText = keyText,
        fontId = fontId
    )

    /**
     * A compact share code: `LB1` + base64 of a `|`-joined record. Short
     * enough to paste into a chat message, opaque enough that people treat it
     * as a token instead of hand-editing it into something unreadable.
     */
    fun encode(): String {
        val body = listOf(
            name.replace('|', ' ').take(24),
            hex(background), hex(key), hex(keyFunctional), hex(text), hex(keyText),
            hex(textSecondary), hex(accent), hex(divider), hex(popupBackground), hex(trail),
            ((radiusDp * 10).roundToInt()).toString(),
            fontId
        ).joinToString("|")
        return "LB1" + Base64.encodeToString(
            body.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
        )
    }

    companion object {
        const val FONT_SYSTEM = "system"
        const val FONT_ROUNDED = "rounded"
        const val FONT_MONO = "mono"
        const val FONT_CONDENSED = "condensed"
        const val FONT_SERIF = "serif"
        const val FONT_MEDIUM = "medium"

        // Families that are genuinely different shapes rather than different
        // weights of Roboto. "rounded" and "medium" above are why the list
        // used to look samey: sans-serif-rounded does not exist on most
        // devices and silently resolves to plain Roboto, and medium is one
        // weight step. These are distinct designs on any AOSP-derived system.
        const val FONT_LIGHT = "light"
        const val FONT_THIN = "thin"
        const val FONT_BLACK = "black"
        const val FONT_CONDENSED_LIGHT = "condensed_light"
        const val FONT_CASUAL = "casual"
        const val FONT_CURSIVE = "cursive"
        const val FONT_SERIF_MONO = "serif_mono"
        const val FONT_SMALLCAPS = "smallcaps"

        /** Atkinson Hyperlegible — designed by the Braille Institute for low
         *  vision, with letterforms deliberately hard to confuse (I/l/1, O/0).
         *  Bundled under the SIL Open Font License 1.1; see
         *  res/font/OFL_Atkinson_Hyperlegible.txt. The keyboard default. */
        const val FONT_ATKINSON = "atkinson"

        /** A .ttf/.otf the user imported themselves. */
        const val FONT_CUSTOM = "custom"

        /** What a theme uses when it does not name a font. */
        const val FONT_DEFAULT = FONT_ATKINSON

        /**
         * Every font the keyboard can offer, in preference order.
         *
         * NOT the list to show a user: several of these resolve to the same
         * face on any given device — sans-serif-rounded exists on almost no
         * shipping ROM and falls back to plain Roboto without saying so, which
         * is why the picker used to show four entries that looked identical.
         * FontCache.availableFonts() measures each one and drops the
         * duplicates, so the picker shows what this device can actually draw.
         */
        val FONTS: List<Pair<String, String>> = listOf(
            FONT_ATKINSON to "Atkinson Hyperlegible",
            FONT_SYSTEM to "System",
            FONT_LIGHT to "Light",
            FONT_THIN to "Thin",
            FONT_MEDIUM to "Medium",
            FONT_BLACK to "Heavy",
            FONT_CONDENSED to "Condensed",
            FONT_CONDENSED_LIGHT to "Condensed light",
            FONT_ROUNDED to "Rounded",
            FONT_SMALLCAPS to "Small caps",
            FONT_SERIF to "Serif",
            FONT_SERIF_MONO to "Serif mono",
            FONT_MONO to "Mono",
            FONT_CASUAL to "Casual",
            FONT_CURSIVE to "Handwriting",
            FONT_CUSTOM to "My own font…"
        )

        private fun hex(c: Int): String = String.format("%06X", c and 0xFFFFFF)

        fun decode(code: String): ThemeSpec? {
            val raw = code.trim()
            if (!raw.startsWith("LB1") || raw.length < 8) return null
            return try {
                val body = String(
                    Base64.decode(
                        raw.substring(3),
                        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
                    ),
                    Charsets.UTF_8
                )
                val p = body.split('|')
                if (p.size < 13) return null
                fun col(i: Int) = (0xFF000000.toInt() or p[i].toInt(16))
                ThemeSpec(
                    name = p[0].ifBlank { "Shared theme" },
                    background = col(1), key = col(2), keyFunctional = col(3),
                    text = col(4), keyText = col(5), textSecondary = col(6),
                    accent = col(7), divider = col(8), popupBackground = col(9),
                    trail = col(10),
                    radiusDp = (p[11].toFloatOrNull() ?: 100f) / 10f,
                    fontId = p[12].ifBlank { FONT_DEFAULT }
                )
            } catch (_: Throwable) {
                null
            }
        }

        fun fromTheme(name: String, t: KeyboardTheme): ThemeSpec = ThemeSpec(
            name = name,
            background = t.background, key = t.key, keyFunctional = t.keyFunctional,
            text = t.text, keyText = t.keyText ?: t.text, textSecondary = t.textSecondary,
            accent = t.accent, divider = t.divider, popupBackground = t.popupBackground,
            trail = t.trail, radiusDp = t.keyRadiusDp, fontId = t.fontId
        )

        // ── The auto-palette generator ───────────────────────────────────
        //
        // Pick a background and an accent; everything else follows. The rules
        // are deliberately conservative — soft, low-saturation surfaces and a
        // text colour that is pushed until it actually passes contrast — so
        // that *any* two colours a user pokes at still produce a keyboard
        // that can be read. That matters more than looking clever: this is
        // the surface people type on all day.

        /**
         * Derive a full palette from a [background] and an [accent].
         * Surfaces stay within a few percent lightness of the background so
         * the result reads as one soft family rather than stripes.
         */
        fun auto(
            name: String,
            background: Int,
            accent: Int,
            radiusDp: Float = 10f,
            fontId: String = FONT_DEFAULT
        ): ThemeSpec {
            val dark = luminance(background) < 0.5
            val bgHsl = toHsl(background)
            val hue = bgHsl[0]
            // Surfaces borrow the background's hue but are held to pastel
            // saturation, so a neon background still yields usable keys.
            val surfSat = min(bgHsl[1], if (dark) 0.28f else 0.34f)

            // Keys sit ABOVE the board in both light and dark themes — that's
            // the convention every phone keyboard uses, and it survives being
            // viewed at an angle in sunlight.
            val keyL = shift(bgHsl[2], if (dark) +0.085f else +0.075f)
            val funcL = shift(bgHsl[2], if (dark) +0.040f else +0.028f)
            val popL = shift(bgHsl[2], if (dark) +0.115f else +0.100f)

            val key = fromHsl(hue, surfSat, keyL)
            val keyFunctional = fromHsl(hue, surfSat * 0.92f, funcL)
            val popupBackground = fromHsl(hue, surfSat * 0.9f, popL)

            // Text: start from a tinted near-white / near-black, then push it
            // away from the key colour until it clears 4.5:1. A theme you
            // can't read is not a theme.
            var text = fromHsl(hue, if (dark) 0.10f else 0.30f, if (dark) 0.95f else 0.14f)
            text = ensureContrast(text, key, 4.5f, preferLighter = dark)
            // The bar sits on the background, so verify against that too.
            val barText = ensureContrast(text, background, 4.5f, preferLighter = dark)

            val textSecondary = mix(barText, key, 0.45f)
            val divider = mix(barText, key, 0.74f)

            // The accent must survive on both the key face and the board.
            val safeAccent = ensureContrast(accent, key, 3.0f, preferLighter = dark)

            return ThemeSpec(
                name = name,
                background = background,
                key = key,
                keyFunctional = keyFunctional,
                text = barText,
                keyText = text,
                textSecondary = textSecondary,
                accent = safeAccent,
                divider = divider,
                popupBackground = popupBackground,
                trail = safeAccent,
                radiusDp = radiusDp,
                fontId = fontId
            )
        }

        // ── colour maths ─────────────────────────────────────────────────

        /** Relative luminance (WCAG), 0..1. */
        fun luminance(c: Int): Float {
            fun ch(v: Int): Float {
                val s = v / 255f
                return if (s <= 0.03928f) s / 12.92f
                else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * ch(Color.red(c)) +
                0.7152f * ch(Color.green(c)) +
                0.0722f * ch(Color.blue(c))
        }

        /** WCAG contrast ratio between two opaque colours (1..21). */
        fun contrast(a: Int, b: Int): Float {
            val la = luminance(a)
            val lb = luminance(b)
            val hi = max(la, lb)
            val lo = min(la, lb)
            return (hi + 0.05f) / (lo + 0.05f)
        }

        /**
         * Walk [fg]'s lightness away from [bg] until it clears [target].
         * Gives up gracefully at pure white/black rather than looping.
         */
        fun ensureContrast(fg: Int, bg: Int, target: Float, preferLighter: Boolean): Int {
            if (contrast(fg, bg) >= target) return fg
            val hsl = toHsl(fg)
            var l = hsl[2]
            val step = if (preferLighter) 0.03f else -0.03f
            var best = fg
            var guard = 0
            while (guard++ < 40) {
                l = (l + step).coerceIn(0f, 1f)
                val cand = fromHsl(hsl[0], hsl[1], l)
                best = cand
                if (contrast(cand, bg) >= target) return cand
                if (l <= 0f || l >= 1f) break
            }
            // Still short: fall back to whichever pole actually contrasts.
            val white = Color.WHITE
            val black = Color.BLACK
            return if (contrast(white, bg) >= contrast(black, bg)) white else black
        }

        /** Linear blend: t=0 -> [a], t=1 -> [b]. */
        fun mix(a: Int, b: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            fun m(x: Int, y: Int) = (x + (y - x) * f).roundToInt().coerceIn(0, 255)
            return Color.rgb(
                m(Color.red(a), Color.red(b)),
                m(Color.green(a), Color.green(b)),
                m(Color.blue(a), Color.blue(b))
            )
        }

        /** Nudge lightness, reflecting at the ends so extremes still move. */
        private fun shift(l: Float, by: Float): Float {
            val out = l + by
            if (out in 0f..1f) return out
            return (l - by).coerceIn(0f, 1f)
        }

        /** [hue 0..360, sat 0..1, light 0..1] */
        fun toHsl(c: Int): FloatArray {
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val l = (mx + mn) / 2f
            if (mx == mn) return floatArrayOf(0f, 0f, l)
            val d = mx - mn
            val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            val h = when (mx) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } * 60f
            return floatArrayOf(h, s, l)
        }

        fun fromHsl(h: Float, s: Float, l: Float): Int {
            val hh = ((h % 360f) + 360f) % 360f
            val ss = s.coerceIn(0f, 1f)
            val ll = l.coerceIn(0f, 1f)
            if (ss == 0f) {
                val v = (ll * 255).roundToInt().coerceIn(0, 255)
                return Color.rgb(v, v, v)
            }
            val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss
            val p = 2 * ll - q
            fun hue2rgb(t0: Float): Float {
                var t = t0
                if (t < 0) t += 1f
                if (t > 1) t -= 1f
                return when {
                    t < 1f / 6f -> p + (q - p) * 6f * t
                    t < 1f / 2f -> q
                    t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                    else -> p
                }
            }
            val hk = hh / 360f
            return Color.rgb(
                (hue2rgb(hk + 1f / 3f) * 255).roundToInt().coerceIn(0, 255),
                (hue2rgb(hk) * 255).roundToInt().coerceIn(0, 255),
                (hue2rgb(hk - 1f / 3f) * 255).roundToInt().coerceIn(0, 255)
            )
        }

        /** Suggested accent for a background, when the user hasn't picked one:
         *  a complementary-ish hue at a readable lightness. */
        fun suggestAccent(background: Int): Int {
            val hsl = toHsl(background)
            val dark = luminance(background) < 0.5
            return fromHsl(
                hsl[0] + 165f,
                max(0.55f, min(0.85f, hsl[1] + 0.35f)),
                if (dark) 0.66f else 0.44f
            )
        }

        /** True when two colours are close enough to read as the same. */
        fun nearlyEqual(a: Int, b: Int): Boolean =
            abs(Color.red(a) - Color.red(b)) < 6 &&
                abs(Color.green(a) - Color.green(b)) < 6 &&
                abs(Color.blue(a) - Color.blue(b)) < 6
    }
}
