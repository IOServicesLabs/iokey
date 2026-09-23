package com.localboard.keyboard.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.localboard.keyboard.R
import com.localboard.keyboard.util.Prefs

data class KeyboardTheme(
    val background: Int,
    val key: Int,
    val keyFunctional: Int,
    val text: Int,
    val textSecondary: Int,
    val accent: Int,
    val divider: Int,
    val trail: Int,
    val popupBackground: Int,
    val isDark: Boolean,
    /** Key corner roundness; rounder = softer, organic (mid-century) look. */
    val keyRadiusDp: Float = 8f,
    /** P4: label color ON keys when it must differ from bar text (high
     *  contrast: black legends on white keys, white text on the black
     *  board). Null = same as [text]. */
    val keyText: Int? = null,
    /** Key-label typeface id — see [ThemeSpec.FONTS]. */
    val fontId: String = ThemeSpec.FONT_DEFAULT
) {
    /** The typeface for key labels. Resolved and cached by FontCache — the
     *  bundled and imported fonts need a Context and a file parse, neither of
     *  which belongs on a draw path. */
    fun typeface(): android.graphics.Typeface =
        com.localboard.keyboard.util.FontCache.resolve(fontId)

    companion object {

        data class Palette(
            val id: String,
            val label: String,
            val bg: Int, val key: Int, val keyFunc: Int,
            val text: Int, val textSec: Int, val accent: Int, val popupBg: Int,
            val radiusDp: Float = 10f,
            val keyText: Int? = null
        )

        /** Built-in palettes (dark/light read from resources to preserve the original look). */
        val EXTRA_PALETTES = listOf(
            Palette("midcentury", "Mid-Century",
                0xFFEFE6D5.toInt(), 0xFFF8F1E3.toInt(), 0xFFE4D6BE.toInt(),
                0xFF3E3232.toInt(), 0xFF8A7A68.toInt(), 0xFFC96F4A.toInt(), 0xFFF1E7D3.toInt(),
                radiusDp = 14f),
            Palette("amoled", "AMOLED Black",
                0xFF000000.toInt(), 0xFF17181C.toInt(), 0xFF101114.toInt(),
                0xFFEAEAEA.toInt(), 0xFF8A8F98.toInt(), 0xFF66D1FF.toInt(), 0xFF1C1D22.toInt()),
            Palette("ocean", "Ocean",
                0xFF0B2545.toInt(), 0xFF13315C.toInt(), 0xFF0E2A4F.toInt(),
                0xFFEAF4FF.toInt(), 0xFF8DA9C4.toInt(), 0xFF4CC9F0.toInt(), 0xFF13315C.toInt()),
            Palette("forest", "Forest",
                0xFF10231A.toInt(), 0xFF1B3527.toInt(), 0xFF152A20.toInt(),
                0xFFE8F5E9.toInt(), 0xFF8FBC9A.toInt(), 0xFF66BB6A.toInt(), 0xFF1B3527.toInt()),
            Palette("rose", "Rosé",
                0xFF2B1720.toInt(), 0xFF3E2230.toInt(), 0xFF331B27.toInt(),
                0xFFFFE4EC.toInt(), 0xFFC48B9F.toInt(), 0xFFF48FB1.toInt(), 0xFF3E2230.toInt()),
            Palette("mono", "Mono",
                0xFF121212.toInt(), 0xFF2A2A2A.toInt(), 0xFF1F1F1F.toInt(),
                0xFFF5F5F5.toInt(), 0xFFA0A0A0.toInt(), 0xFFFFFFFF.toInt(), 0xFF2A2A2A.toInt()),
            Palette("sunset", "Sunset",
                0xFF241423.toInt(), 0xFF38203A.toInt(), 0xFF2D1A30.toInt(),
                0xFFFFF3E0.toInt(), 0xFFCE93D8.toInt(), 0xFFFFB74D.toInt(), 0xFF38203A.toInt()),
            // P4: WCAG-style maximum separation for low-vision users —
            // pure black board, pure white keys, black legends, yellow focus.
            Palette("contrast", "High Contrast",
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8E8E8.toInt(),
                0xFFFFFFFF.toInt(), 0xFFBFBFBF.toInt(), 0xFFFFD600.toInt(), 0xFF000000.toInt(),
                radiusDp = 6f, keyText = 0xFF000000.toInt())
        )

        private fun fromPalette(p: Palette) = KeyboardTheme(
            background = p.bg, key = p.key, keyFunctional = p.keyFunc,
            text = p.text, textSecondary = p.textSec, accent = p.accent,
            divider = p.textSec, trail = p.accent, popupBackground = p.popupBg,
            isDark = isColorDark(p.bg), keyRadiusDp = p.radiusDp,
            keyText = p.keyText
        )

        private fun isColorDark(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (0.299 * r + 0.587 * g + 0.114 * b) < 128
        }

        private fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        /** Cheap identity of everything [load] reads. When this is unchanged
         *  the previous result is still correct, so the keyboard-show path
         *  can skip re-resolving ~15 prefs and a dozen resource colours. */
        fun signature(context: Context, prefs: Prefs, appPackage: String = ""): String =
            "${prefs.paletteId}|${prefs.activeThemeCode}|${prefs.keyRadiusDp}|" +
                "${prefs.customBg}|${prefs.customKey}|${prefs.customText}|" +
                "${prefs.customAccent}|${prefs.keyFontId}|${isNight(context)}|" +
                "${prefs.themeCodeForApp(appPackage) ?: ""}"

        @Volatile private var cachedSig: String? = null
        @Volatile private var cached: KeyboardTheme? = null

        /**
         * [load] with memoisation on [signature].
         *
         * [appPackage] enables per-app themes: when the user has assigned a
         * theme to the app being typed in, that wins over the global one. The
         * package is part of the cache signature, so switching apps repaints.
         */
        fun loadCached(
            context: Context,
            prefs: Prefs,
            appPackage: String = ""
        ): KeyboardTheme {
            val sig = signature(context, prefs, appPackage)
            cached?.let { if (sig == cachedSig) return it }
            val perApp = prefs.themeCodeForApp(appPackage)
                ?.let { ThemeSpec.decode(it) }
                ?.toTheme()
            val t = perApp ?: load(context, prefs)
            cached = t
            cachedSig = sig
            return t
        }

        /** Drop the memo after a theme edit so the next show repaints. */
        fun invalidateCache() {
            cachedSig = null
            cached = null
        }

        fun load(context: Context, prefs: Prefs): KeyboardTheme {
            fun c(id: Int) = ContextCompat.getColor(context, id)

            // A saved/shared custom theme wins outright: it already defines
            // every colour, so none of the palette logic below applies.
            if (prefs.paletteId == "custom") {
                ThemeSpec.decode(prefs.activeThemeCode)?.let { spec ->
                    var t = spec.toTheme()
                    if (prefs.keyRadiusDp >= 0f) {
                        t = t.copy(keyRadiusDp = prefs.keyRadiusDp.coerceIn(0f, 20f))
                    }
                    return t
                }
            }

            var base: KeyboardTheme = when (prefs.paletteId) {
                // P3: follow the system day/night mode — the resource dark
                // and light palettes, switched by the live configuration.
                "system" -> if (isNight(context)) {
                    KeyboardTheme(
                        background = c(R.color.kb_dark_bg), key = c(R.color.kb_dark_key),
                        keyFunctional = c(R.color.kb_dark_key_function), text = c(R.color.kb_dark_text),
                        textSecondary = c(R.color.kb_dark_text_secondary), accent = c(R.color.kb_dark_accent),
                        divider = c(R.color.kb_dark_divider), trail = c(R.color.kb_dark_trail),
                        popupBackground = c(R.color.kb_dark_popup_bg), isDark = true
                    )
                } else {
                    KeyboardTheme(
                        background = c(R.color.kb_light_bg), key = c(R.color.kb_light_key),
                        keyFunctional = c(R.color.kb_light_key_function), text = c(R.color.kb_light_text),
                        textSecondary = c(R.color.kb_light_text_secondary), accent = c(R.color.kb_light_accent),
                        divider = c(R.color.kb_light_divider), trail = c(R.color.kb_light_trail),
                        popupBackground = c(R.color.kb_light_popup_bg), isDark = false
                    )
                }
                // P3: Material You — wallpaper-derived dynamic colors
                // (API 31+, always present on our minSdk).
                "material" -> if (isNight(context)) {
                    KeyboardTheme(
                        background = c(android.R.color.system_neutral1_900),
                        key = c(android.R.color.system_neutral2_800),
                        keyFunctional = c(android.R.color.system_neutral1_800),
                        text = c(android.R.color.system_neutral1_50),
                        textSecondary = c(android.R.color.system_neutral2_300),
                        accent = c(android.R.color.system_accent1_300),
                        divider = c(android.R.color.system_neutral2_300),
                        trail = c(android.R.color.system_accent1_300),
                        popupBackground = c(android.R.color.system_neutral1_800),
                        isDark = true, keyRadiusDp = 12f
                    )
                } else {
                    KeyboardTheme(
                        background = c(android.R.color.system_neutral2_100),
                        key = c(android.R.color.system_neutral1_0),
                        keyFunctional = c(android.R.color.system_neutral2_200),
                        text = c(android.R.color.system_neutral1_900),
                        textSecondary = c(android.R.color.system_neutral2_600),
                        accent = c(android.R.color.system_accent1_600),
                        divider = c(android.R.color.system_neutral2_600),
                        trail = c(android.R.color.system_accent1_600),
                        popupBackground = c(android.R.color.system_neutral1_50),
                        isDark = false, keyRadiusDp = 12f
                    )
                }
                "light" -> KeyboardTheme(
                    background = c(R.color.kb_light_bg), key = c(R.color.kb_light_key),
                    keyFunctional = c(R.color.kb_light_key_function), text = c(R.color.kb_light_text),
                    textSecondary = c(R.color.kb_light_text_secondary), accent = c(R.color.kb_light_accent),
                    divider = c(R.color.kb_light_divider), trail = c(R.color.kb_light_trail),
                    popupBackground = c(R.color.kb_light_popup_bg), isDark = false
                )
                // No palette chosen: the mid-century look is the default.
                "" -> fromPalette(EXTRA_PALETTES.first { it.id == "midcentury" })
                "dark" -> KeyboardTheme(
                    background = c(R.color.kb_dark_bg), key = c(R.color.kb_dark_key),
                    keyFunctional = c(R.color.kb_dark_key_function), text = c(R.color.kb_dark_text),
                    textSecondary = c(R.color.kb_dark_text_secondary), accent = c(R.color.kb_dark_accent),
                    divider = c(R.color.kb_dark_divider), trail = c(R.color.kb_dark_trail),
                    popupBackground = c(R.color.kb_dark_popup_bg), isDark = true
                )
                else -> EXTRA_PALETTES.firstOrNull { it.id == prefs.paletteId }
                    ?.let(::fromPalette)
                    ?: fromPalette(EXTRA_PALETTES[0])
            }

            // Radius override beats the palette's own roundness.
            if (prefs.keyRadiusDp >= 0f) {
                base = base.copy(keyRadiusDp = prefs.keyRadiusDp.coerceIn(0f, 20f))
            }

            // User custom overrides beat palette colors.
            if (prefs.customBg != Int.MIN_VALUE || prefs.customKey != Int.MIN_VALUE ||
                prefs.customText != Int.MIN_VALUE || prefs.customAccent != Int.MIN_VALUE
            ) {
                base = base.copy(
                    background = if (prefs.customBg != Int.MIN_VALUE) prefs.customBg else base.background,
                    key = if (prefs.customKey != Int.MIN_VALUE) prefs.customKey else base.key,
                    text = if (prefs.customText != Int.MIN_VALUE) prefs.customText else base.text,
                    accent = if (prefs.customAccent != Int.MIN_VALUE) prefs.customAccent else base.accent,
                    popupBackground = if (prefs.customBg != Int.MIN_VALUE) prefs.customBg else base.popupBackground,
                    trail = if (prefs.customAccent != Int.MIN_VALUE) prefs.customAccent else base.trail,
                    isDark = isColorDark(if (prefs.customBg != Int.MIN_VALUE) prefs.customBg else base.background)
                )
            }
            // Font is orthogonal to palette — a user can put a rounded face
            // on any preset without leaving it.
            if (prefs.keyFontId != ThemeSpec.FONT_SYSTEM) {
                base = base.copy(fontId = prefs.keyFontId)
            }
            return base
        }
    }
}
