package com.localboard.keyboard.ui

enum class KeyType {
    CHAR,           // ordinary character, participates in swipe + popups
    SHIFT,
    DELETE,
    ENTER,
    MODE_SYMBOLS,   // ?123
    MODE_SYMBOLS2,  // =\<
    MODE_LETTERS,   // ABC
    EMOJI,
    SPACE,
    PERIOD,         // '.' with popup punctuation
    COMMA,

    // Cursor / selection pad. Reaching for the screen to place a caret is the
    // most common reason to stop using the keyboard mid-message.
    MODE_SELECT,    // open the pad
    CURSOR_LEFT,
    CURSOR_RIGHT,
    CURSOR_UP,
    CURSOR_DOWN,
    CURSOR_HOME,
    CURSOR_END,
    SELECT_TOGGLE,  // arrows extend the selection instead of moving the caret
    SELECT_ALL,
    CUT,
    COPY,
    PASTE
}

data class Key(
    val label: String,
    val output: String,
    val type: KeyType = KeyType.CHAR,
    /** Width relative to a standard 1.0 key. */
    val weight: Float = 1f,
    /** Long-press alternates (accented chars, punctuation variants). */
    val popup: List<String> = emptyList(),
    /** Drawable resource for icon keys; 0 = draw [label] as text. */
    val icon: Int = 0,
    /** Key is visually "functional" (darker background). */
    val functional: Boolean = false,
    /** Small legend in the key's top-right corner. null = the first popup
     *  entry (the long-press default); "" = none, for keys whose popup is a
     *  convenience rather than something Gboard advertises on the cap. */
    val hint: String? = null
) {
    /** What the cap actually shows as its long-press hint, if anything. */
    val hintText: String get() = hint ?: (popup.firstOrNull() ?: "")
}

data class RowModel(
    val keys: List<Key>,
    /** Fraction of total keyboard width that is padding on each side. */
    val edgePadding: Float = 0f
)

data class KeyboardModel(
    val rows: List<RowModel>,
    val isLetters: Boolean = false
)
