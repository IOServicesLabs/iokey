package com.localboard.keyboard.ui

import com.localboard.keyboard.R

/**
 * User-controllable shape of the bottom row and the symbols pages.
 *
 * Every optional bottom-row key that is switched OFF hands its width to the
 * space bar, so this is also the direct answer to "the keyboard feels
 * cramped": fewer, wider keys instead of more, narrower ones.
 */
data class LayoutOptions(
    val numberRow: Boolean = false,
    val digitPopups: Boolean = true,
    val emojiKey: Boolean = true,
    val commaKey: Boolean = true,
    val apostropheKey: Boolean = false,
    /** 0 = roomy (10 keys per symbols row), 1 = complete (everything on page 1). */
    val symbolsDensity: Int = 0,
    /** Letter arrangement id — see [KeyboardLayouts.letterRows]. */
    val layoutId: String = "qwerty",
    /** Cursor/selection pad key on the bottom row.
     *
     *  false, to match Prefs.selectionKey. Every default in this class MUST
     *  agree with the corresponding pref default, because LayoutOptions() is
     *  what an unconfigured KeyboardView renders. This one said true while the
     *  pref said false, so any code path that failed to call setKeyboard()
     *  showed a keyboard with an extra cursor key on the bottom row rather
     *  than simply showing nothing — a wrong layout that looked deliberate
     *  instead of an obvious failure. */
    val selectionKey: Boolean = false,
    /** Where the < > [ ] { } key sits on the symbols page. */
    val bracketPos: Int = KeyboardLayouts.BRACKET_LEFT
) {
    companion object {
        fun from(prefs: com.localboard.keyboard.util.Prefs) = LayoutOptions(
            numberRow = prefs.numberRow,
            digitPopups = prefs.digitPopups,
            emojiKey = prefs.bottomEmojiKey,
            commaKey = prefs.bottomCommaKey,
            apostropheKey = prefs.bottomApostropheKey,
            symbolsDensity = prefs.symbolsDensity,
            layoutId = prefs.layoutId,
            selectionKey = prefs.selectionKey,
            bracketPos = prefs.symbolsBracketPos
        )
    }

    /** Identity used by the IME to decide whether the key grid must be
     *  rebuilt. Every field that changes the geometry belongs here. */
    fun cacheKey(): String =
        "$numberRow:$digitPopups:$emojiKey:$commaKey:$apostropheKey:$symbolsDensity:" +
            "$layoutId:$selectionKey:$bracketPos"
}

/**
 * Gboard's layouts, key for key.
 *
 * The letters page, the ?123 page, the =\< page and the bottom row are
 * Gboard's (English, US), including its "long press for symbols" hints —
 * every letter key's long-press yields the symbol that sits in the same seat
 * on the ?123 page (a→@, s→#, … z→*, x→"), the top row yields digits, and
 * the =\< page swaps the bottom row's comma and period for < and >. A user
 * coming from Gboard should find every key where their thumb expects it,
 * which is the whole point: a keyboard whose symbols are one seat off from
 * the one you used for years feels wrong long before you can say why.
 *
 * The options in [LayoutOptions] all DEVIATE from that baseline (a bracket
 * key on ?123, a number row, an apostrophe key…) and default to off.
 */
object KeyboardLayouts {

    /** Bracket-key placement on the symbols page. */
    const val BRACKET_LEFT = 0
    const val BRACKET_RIGHT = 1
    const val BRACKET_HIDDEN = 2

    val BRACKET_CHOICES: List<Pair<Int, String>> = listOf(
        BRACKET_LEFT to "Left, next to =\\<",
        BRACKET_RIGHT to "Right, next to backspace",
        BRACKET_HIDDEN to "Hidden (on the =\\< page)"
    )

    private val POPUPS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "ø", "ō"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "y" to listOf("ý", "ÿ"),
        "z" to listOf("ž", "ź", "ż")
    )

    private fun charKey(c: String): Key =
        Key(c, c, KeyType.CHAR, popup = POPUPS[c] ?: emptyList())

    /** Gboard's "long press for symbols": the ?123 page's second and third
     *  rows, seat for seat, on the home and bottom letter rows. */
    private val HOME_ROW_SYMBOLS = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
    private val BOTTOM_ROW_SYMBOLS = listOf("*", "\"", "'", ":", ";", "!", "?")

    /** Gboard's long-press alternates on the ?123 and =\< pages. Keys not
     *  listed have none. The cap shows no hint for these — Gboard's symbol
     *  pages are plain — so [Key.hint] is "" throughout. */
    private val SYMBOL_POPUPS = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼", "⅛"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴"),
        "5" to listOf("⅝"),
        "7" to listOf("⅞"),
        "0" to listOf("ⁿ", "∅"),
        "$" to listOf("¢", "£", "€", "¥", "₹", "₱"),
        "-" to listOf("_", "–", "—", "·"),
        "+" to listOf("±"),
        "(" to listOf("<", "{", "["),
        ")" to listOf(">", "}", "]"),
        "*" to listOf("†", "‡", "★"),
        "\"" to listOf("\u201C", "\u201D", "\u201E", "\u201F", "«", "»"),
        "'" to listOf("\u2018", "\u2019", "\u201A", "\u201B", "‹", "›"),
        "!" to listOf("¡"),
        "?" to listOf("¿"),
        "•" to listOf("♪", "♥", "♠", "♦", "♣"),
        "π" to listOf("Π"),
        "=" to listOf("≠", "≈", "∞"),
        "^" to listOf("↑", "↓", "←", "→"),
        "°" to listOf("′", "″"),
        "%" to listOf("‰", "℅"),
        "<" to listOf("‹", "≤", "«"),
        ">" to listOf("›", "≥", "»")
    )

    private fun symKey(c: String, weight: Float = 1f): Key =
        Key(c, c, KeyType.CHAR, weight = weight, popup = SYMBOL_POPUPS[c] ?: emptyList(), hint = "")

    /** Gboard's period popup: sixteen punctuation marks, comma first. */
    private val PERIOD_POPUP = listOf(
        ",", "?", "!", "#", ")", "(", "/", ";", "'", "@", ":", "-", "\"", "+", "%", "&"
    )

    /**
     * The letter arrangements, top row first.
     *
     * QWERTY/QWERTZ/COLEMAK keep the familiar 10/9-or-10/7 shape. AZERTY is
     * genuinely 10/10/6 — the M sits at the end of the home row — so its
     * bottom row has fewer, wider letter keys, which is correct rather than a
     * mistake. Dvorak is the real 10/10/10 including its punctuation keys;
     * its bottom row is therefore denser than the others, which is simply
     * what Dvorak looks like.
     *
     * These strings are also the source of truth for autocorrect's fat-finger
     * adjacency — see FastEngine.setKeyboardRows.
     */
    fun letterRows(layoutId: String): List<String> = when (layoutId) {
        "qwertz" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
        "azerty" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        "colemak" -> listOf("qwfpgjluy;", "arstdhneio", "zxcvbkm")
        "dvorak" -> listOf("',.pyfgcrl", "aoeuidhtns", ";qjkxbmwvz")
        // "gboard" was a separate id while its symbol pages differed; every
        // page is Gboard's now, so it is simply QWERTY (kept for stored prefs).
        else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }

    /** Human labels for the settings picker. */
    val LAYOUT_CHOICES: List<Pair<String, String>> = listOf(
        "qwerty" to "QWERTY (Gboard)",
        "qwertz" to "QWERTZ",
        "azerty" to "AZERTY",
        "colemak" to "Colemak",
        "dvorak" to "Dvorak (denser bottom row)"
    )

    fun qwerty(opts: LayoutOptions = LayoutOptions()): KeyboardModel {
        val rows = mutableListOf<RowModel>()
        val letters = letterRows(opts.layoutId)

        if (opts.numberRow) {
            rows += RowModel((1..9).map { charKey(it.toString()) } + charKey("0"))
        }

        // Gboard's long-press hints. Top row: the digit in the same seat
        // (first key→1 … tenth→0). Home and bottom rows: the symbol in the
        // same seat on the ?123 page. The hinted character leads the popup so
        // hold-and-release types it; accent alternates follow. Non-letter
        // keys in a layout (Dvorak's ' and .) get no hint — it would land on
        // a key that already prints punctuation.
        fun hinted(c: String, hint: String?): Key =
            if (!opts.digitPopups || hint == null || !c[0].isLetter()) charKey(c)
            else Key(c, c, KeyType.CHAR, popup = listOf(hint) + (POPUPS[c] ?: emptyList()))
        rows += RowModel(
            letters[0].map { it.toString() }.mapIndexed { i, c -> hinted(c, "${(i + 1) % 10}") }
        )
        // The 9-key home row is inset so its keys stay letter-width; a 10-key
        // home row (AZERTY, Colemak, Dvorak) already fills the width.
        rows += RowModel(
            letters[1].map { it.toString() }.mapIndexed { i, c ->
                hinted(c, HOME_ROW_SYMBOLS.getOrNull(i))
            },
            edgePadding = if (letters[1].length < 10) 0.05f else 0f
        )
        rows += RowModel(
            buildList {
                add(Key("", "", KeyType.SHIFT, weight = 1.5f, icon = R.drawable.ic_shift, functional = true))
                addAll(letters[2].map { it.toString() }.mapIndexed { i, c ->
                    hinted(c, BOTTOM_ROW_SYMBOLS.getOrNull(i))
                })
                add(Key("", "", KeyType.DELETE, weight = 1.5f, icon = R.drawable.ic_backspace, functional = true))
            }
        )
        rows += bottomRow(isLetters = true, opts = opts)
        return KeyboardModel(rows, isLetters = true)
    }

    /**
     * Cursor and selection pad — the thing you currently have to leave the
     * keyboard for. Arrow keys move the caret; SELECT_TOGGLE holds the anchor
     * so the arrows extend a selection instead of moving; the rest are the
     * operations people actually reach for mid-message.
     */
    fun selectionPad(): KeyboardModel = KeyboardModel(
        listOf(
            RowModel(
                listOf(
                    Key("Select", "", KeyType.SELECT_TOGGLE, weight = 1.6f, functional = true),
                    Key("All", "", KeyType.SELECT_ALL, weight = 1.2f, functional = true),
                    Key("Cut", "", KeyType.CUT, weight = 1.2f, functional = true),
                    Key("Copy", "", KeyType.COPY, weight = 1.2f, functional = true),
                    Key("Paste", "", KeyType.PASTE, weight = 1.4f, functional = true)
                )
            ),
            RowModel(
                listOf(
                    Key("⇱", "", KeyType.CURSOR_HOME, weight = 1.2f, functional = true),
                    Key("↑", "", KeyType.CURSOR_UP, weight = 1.4f, functional = true),
                    Key("⇲", "", KeyType.CURSOR_END, weight = 1.2f, functional = true),
                    Key("", "", KeyType.DELETE, weight = 1.4f,
                        icon = R.drawable.ic_backspace, functional = true)
                ),
                edgePadding = 0.08f
            ),
            RowModel(
                listOf(
                    Key("←", "", KeyType.CURSOR_LEFT, weight = 1.4f, functional = true),
                    Key("↓", "", KeyType.CURSOR_DOWN, weight = 1.4f, functional = true),
                    Key("→", "", KeyType.CURSOR_RIGHT, weight = 1.4f, functional = true)
                ),
                edgePadding = 0.12f
            ),
            RowModel(
                listOf(
                    Key("ABC", "", KeyType.MODE_LETTERS, weight = 1.6f, functional = true),
                    Key("", " ", KeyType.SPACE, weight = 4.2f),
                    Key("", "\n", KeyType.ENTER, weight = 1.6f,
                        icon = R.drawable.ic_enter, functional = true)
                )
            )
        )
    )

    /** Gboard-style number pad, shown when the field asks for numeric input
     *  (TYPE_CLASS_NUMBER / PHONE / DATETIME): a 3x4 digit grid with the
     *  extras those fields legitimately need — minus, decimal point,
     *  backspace and enter. ABC returns to letters. */
    fun numberPad(opts: LayoutOptions = LayoutOptions()): KeyboardModel {
        return KeyboardModel(
            listOf(
                RowModel(listOf("1", "2", "3").map(::charKey)),
                RowModel(listOf("4", "5", "6").map(::charKey)),
                RowModel(listOf("7", "8", "9").map(::charKey)),
                RowModel(
                    buildList {
                        add(Key("ABC", "", KeyType.MODE_LETTERS, weight = 1.3f, functional = true))
                        add(Key("-", "-", KeyType.CHAR, weight = 1f, popup = listOf("+", "×", "÷")))
                        add(Key(".", ".", KeyType.CHAR, weight = 1f, popup = listOf(",", ":")))
                        add(Key("0", "0", KeyType.CHAR, weight = 1.1f))
                        add(Key("", "", KeyType.DELETE, weight = 1.5f, icon = R.drawable.ic_backspace, functional = true))
                        add(Key("", "\n", KeyType.ENTER, weight = 1.6f, icon = R.drawable.ic_enter, functional = true))
                    }
                )
            ),
            isLetters = false
        )
    }

    /**
     * Gboard's ?123 page:
     *
     *   1 2 3 4 5 6 7 8 9 0
     *   @ # $ _ & - + ( ) /
     *   =\< * " ' : ; ! ? ⌫        (1.5 + 7 + 1.5 = the letter z-row's shape)
     *   ABC , 😊 [space] . ⏎
     *
     * Gboard has no bracket key here — < { [ hang off ( and ) — so the
     * optional bracket key ([LayoutOptions.bracketPos]) is a deviation for
     * people who want one, off by default. "Complete" density spells the
     * bracket family out as four keys; that row is then 11 symbols wide and
     * every key ~30% narrower than the letters above it.
     */
    fun symbols1(opts: LayoutOptions = LayoutOptions()): KeyboardModel {
        val bracketKey = Key("<", "<", KeyType.CHAR, popup = listOf(">", "[", "]", "{", "}"))
        val middle = BOTTOM_ROW_SYMBOLS.map { symKey(it) }
        val row3: List<Key> = if (opts.symbolsDensity == 0) {
            buildList {
                add(Key("=\\<", "", KeyType.MODE_SYMBOLS2, weight = 1.5f, functional = true))
                if (opts.bracketPos == BRACKET_LEFT) add(bracketKey)
                addAll(middle)
                if (opts.bracketPos == BRACKET_RIGHT) add(bracketKey)
                add(Key("", "", KeyType.DELETE, weight = 1.5f, icon = R.drawable.ic_backspace, functional = true))
            }
        } else {
            val brackets = listOf("<", ">", "[", "]").map { Key(it, it, KeyType.CHAR) }
            buildList {
                add(Key("=\\<", "", KeyType.MODE_SYMBOLS2, weight = 1.5f, functional = true))
                if (opts.bracketPos != BRACKET_RIGHT) addAll(brackets)
                addAll(middle)
                if (opts.bracketPos == BRACKET_RIGHT) addAll(brackets)
                add(Key("", "", KeyType.DELETE, weight = 1.5f, icon = R.drawable.ic_backspace, functional = true))
            }
        }
        return KeyboardModel(
            listOf(
                RowModel(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map { symKey(it) }),
                RowModel(HOME_ROW_SYMBOLS.map { symKey(it) }),
                RowModel(row3),
                bottomRow(isLetters = false, opts = opts)
            )
        )
    }

    /**
     * Gboard's =\< page:
     *
     *   ~ ` | • √ π ÷ × ¶ ∆
     *   £ ¢ € ¥ ^ ° = { } \
     *   ?123 % © ® ™ ✓ [ ] ⌫
     *   ABC < 😊 [space] > ⏎      (comma and period become < and >)
     */
    fun symbols2(opts: LayoutOptions = LayoutOptions()): KeyboardModel = KeyboardModel(
        listOf(
            RowModel(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { symKey(it) }),
            RowModel(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { symKey(it) }),
            RowModel(
                buildList {
                    add(Key("?123", "", KeyType.MODE_SYMBOLS, weight = 1.5f, functional = true))
                    addAll(listOf("%", "©", "®", "™", "✓", "[", "]").map { symKey(it) })
                    add(Key("", "", KeyType.DELETE, weight = 1.5f, icon = R.drawable.ic_backspace, functional = true))
                }
            ),
            bottomRow(isLetters = false, opts = opts, angleBrackets = true)
        )
    )

    /**
     * Gboard's bottom row: ?123 (1.5) , (1) 😊 (1) [space] (4) . (1) ⏎ (1.5)
     * — ten units, the same as every letter row, so comma, emoji and period
     * are exactly letter-width and the space bar spans four seats.
     *
     * The optional keys are the user's to shape. Fixed seats: mode switch,
     * period, enter, space. Optional seats: comma, emoji, apostrophe,
     * selection. Turning one off hands its unit to the space bar, so the row
     * keeps its ten-unit total and every other key keeps its width; turning
     * a THIRD or fourth on takes it back out of space (down to a floor), so
     * the row grows past ten units only when the space bar cannot give any
     * more.
     *
     * The letters and symbols bottom rows are geometrically identical, so
     * ABC/?123, comma, emoji, space, period and enter occupy the same pixels
     * whichever page is showing — the thumb's learned seats never move.
     *
     * [angleBrackets]: the =\< page, where Gboard puts < and > in the
     * comma and period seats.
     */
    private fun bottomRow(
        isLetters: Boolean,
        opts: LayoutOptions,
        angleBrackets: Boolean = false
    ): RowModel {
        val keys = ArrayList<Key>(8)
        var optionalUsed = 0f

        if (isLetters) {
            keys += Key("?123", "", KeyType.MODE_SYMBOLS, weight = 1.5f, functional = true)
        } else {
            keys += Key("ABC", "", KeyType.MODE_LETTERS, weight = 1.5f, functional = true)
        }

        if (opts.commaKey) {
            keys += if (angleBrackets) symKey("<") else {
                // The apostrophe leads the comma popup whenever it has no key
                // of its own. No hint: Gboard's comma cap is plain.
                val popup = if (opts.apostropheKey) listOf("!", "?", ";", ":")
                else listOf("'", "!", "?", ";", ":")
                Key(",", ",", KeyType.COMMA, weight = 1f, popup = popup, hint = "")
            }
            optionalUsed += 1f
        }
        if (opts.apostropheKey) {
            keys += Key("'", "'", KeyType.CHAR, weight = 1f, popup = listOf("\"", "\u2019", "-"), hint = "")
            optionalUsed += 1f
        }
        if (opts.emojiKey) {
            keys += Key("", "", KeyType.EMOJI, weight = 1f, icon = R.drawable.ic_emoji, functional = true)
            optionalUsed += 1f
        }
        if (opts.selectionKey) {
            keys += Key("⌶", "", KeyType.MODE_SELECT, weight = 1f, functional = true)
            optionalUsed += 1f
        }

        // Gboard's two default optionals (comma, emoji) leave space at four
        // units; each one switched off widens space by one, each extra one
        // switched on narrows it by one down to a floor of three.
        keys += Key("", " ", KeyType.SPACE, weight = (4f + (2f - optionalUsed)).coerceAtLeast(3f))

        keys += if (angleBrackets) symKey(">")
        else Key(".", ".", KeyType.PERIOD, weight = 1f, popup = PERIOD_POPUP, hint = "")
        keys += Key("", "\n", KeyType.ENTER, weight = 1.5f, icon = R.drawable.ic_enter, functional = true)
        return RowModel(keys)
    }
}
