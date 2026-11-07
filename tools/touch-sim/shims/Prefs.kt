// LayoutOptions.from names this class. Nothing under test calls it: the fields exist so
// the real KeyboardLayouts.kt compiles without the real, Android-bound Prefs.
package com.localboard.keyboard.util

class Prefs {
    val numberRow = false
    val digitPopups = true
    val bottomEmojiKey = true
    val bottomCommaKey = true
    val bottomApostropheKey = false
    val symbolsDensity = 0
    val layoutId = "qwerty"
    val selectionKey = false
    val symbolsBracketPos = 0

    companion object {
        // PrefsMigrationsTest asks for these, so here it only proves that the test compiles.
        // The Gradle run of the same test reads the real set.
        val ERASE_KEEPS = setOf("active_model_id", "active_provider", "llm_quick_model_id", "geometry_schema", "screen_offer_never")
    }
}
