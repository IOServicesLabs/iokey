package com.localboard.keyboard.util

/**
 * Emoji skin-tone helpers.
 *
 * Deliberately a separate file from [EmojiData]: that one is a dense wall of
 * literal emoji, and any tool that rewrites it risks mangling surrogate pairs.
 * Keeping the logic here means the data file is only ever read.
 *
 * (A keyword search() once lived here too; it was removed with the search
 * field that called it — [EmojiData.wordToEmoji] still serves the prediction
 * bar directly.)
 */
object EmojiTone {

    // ── Skin tone ────────────────────────────────────────────────────────

    /** Fitzpatrick modifiers U+1F3FB..U+1F3FF, index 1..5. 0 = leave as-is. */
    private val TONES = arrayOf(
        "",
        String(Character.toChars(0x1F3FB)),
        String(Character.toChars(0x1F3FC)),
        String(Character.toChars(0x1F3FD)),
        String(Character.toChars(0x1F3FE)),
        String(Character.toChars(0x1F3FF))
    )

    /** Raised-hand swatch shown on the tone chip. */
    fun toneSwatch(tone: Int): String {
        val hand = String(Character.toChars(0x270B))
        return if (tone <= 0) hand else hand + TONES[tone.coerceIn(1, 5)]
    }

    /**
     * Codepoints that accept a Fitzpatrick modifier — hands, faces, people.
     * Appending a tone to, say, a pizza produces a broken glyph, so this is an
     * allowlist rather than a blanket append.
     *
     * The table is generated from the Emoji_Modifier_Base property in
     * emoji-data.txt by tools/emoji/generate.py; do not edit by hand.
     */
    private fun acceptsTone(cp: Int): Boolean =
        cp in 0x261D..0x261D || cp in 0x26F9..0x26F9 || cp in 0x270A..0x270D ||
            cp in 0x1F385..0x1F385 || cp in 0x1F3C2..0x1F3C4 || cp in 0x1F3C7..0x1F3C7 ||
            cp in 0x1F3CA..0x1F3CC || cp in 0x1F442..0x1F443 || cp in 0x1F446..0x1F450 ||
            cp in 0x1F466..0x1F478 || cp in 0x1F47C..0x1F47C || cp in 0x1F481..0x1F483 ||
            cp in 0x1F485..0x1F487 || cp in 0x1F48F..0x1F48F || cp in 0x1F491..0x1F491 ||
            cp in 0x1F4AA..0x1F4AA || cp in 0x1F574..0x1F575 || cp in 0x1F57A..0x1F57A ||
            cp in 0x1F590..0x1F590 || cp in 0x1F595..0x1F596 || cp in 0x1F645..0x1F647 ||
            cp in 0x1F64B..0x1F64F || cp in 0x1F6A3..0x1F6A3 || cp in 0x1F6B4..0x1F6B6 ||
            cp in 0x1F6C0..0x1F6C0 || cp in 0x1F6CC..0x1F6CC || cp in 0x1F90C..0x1F90C ||
            cp in 0x1F90F..0x1F90F || cp in 0x1F918..0x1F91F || cp in 0x1F926..0x1F926 ||
            cp in 0x1F930..0x1F939 || cp in 0x1F93C..0x1F93E || cp in 0x1F977..0x1F977 ||
            cp in 0x1F9B5..0x1F9B6 || cp in 0x1F9B8..0x1F9B9 || cp in 0x1F9BB..0x1F9BB ||
            cp in 0x1F9CD..0x1F9CF || cp in 0x1F9D1..0x1F9DD || cp in 0x1FAC3..0x1FAC5 ||
            cp in 0x1FAF0..0x1FAFA

    /** Re-render a list with the chosen skin tone applied where it is valid. */
    fun applyTone(list: List<String>, tone: Int): List<String> {
        if (tone <= 0) return list
        val mod = TONES[tone.coerceIn(1, 5)]
        return list.map { e ->
            if (e.isEmpty()) return@map e
            val cp = e.codePointAt(0)
            // Single-codepoint emoji only: multi-part ZWJ sequences need the
            // modifier inserted per component, which risks rendering tofu.
            if (Character.charCount(cp) == e.length && acceptsTone(cp)) e + mod else e
        }
    }
}
