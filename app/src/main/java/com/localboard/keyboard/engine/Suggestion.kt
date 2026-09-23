package com.localboard.keyboard.engine

/** A single suggestion in the prediction bar. */
data class Suggestion(
    /** Text inserted when tapped (already capitalization-adjusted). */
    val text: String,
    /** Combined engine score, higher is better. */
    val score: Double,
    val kind: Kind = Kind.WORD
) {
    enum class Kind { WORD, EMOJI, PHRASE }
}
