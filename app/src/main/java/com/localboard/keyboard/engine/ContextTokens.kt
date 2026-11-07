package com.localboard.keyboard.engine

/**
 * The one reading of "what words came before" that predictions, glide and
 * everything else working from field text agree on.
 *
 * A word is letters and combining marks, joined by an apostrophe or a hyphen
 * when a letter follows it, so "don't" and "well-known" stay whole while the
 * quote in "dogs'" does not stick to the word. Tokens are lowercase with a
 * straight apostrophe, the same spelling the n-gram maps are keyed on.
 *
 * A run holding a digit is not a word at all. Keeping the letters would hand
 * back "pm" for "3pm" or "mp" for "mp3", and those fragments would go on to be
 * learned as the previous word of whatever comes next.
 *
 * Between words there can be [SENTENCE_MARK]: the word after it starts a
 * sentence, and the words before it are no context for that word.
 */
object ContextTokens {

    /** "A sentence ended here", spelled as the service spells it in its context
     *  deque, so seeded and typed context look the same to everything reading
     *  that deque. */
    const val SENTENCE_MARK = "."

    /** How many characters before the cursor a reseed should ask the editor for. */
    const val FIELD_WINDOW = 240

    /** Every token in [text], for text whose last word really is finished:
     *  dictated phrases, inserted drafts, pasted or stored messages. */
    fun tokenize(text: CharSequence): List<String> {
        val out = ArrayList<String>()
        scan(text, 0, text.length, out)
        return out
    }

    /**
     * The context a cursor sits in, from [text] as read back from the editor.
     * Take any composing tail off first. [window] is the length that was asked
     * for.
     *
     * The run touching the cursor is left out. Nothing says it is finished: the
     * cursor may be mid-word, and the next letter typed will join it, so "wor"
     * must not become the previous word of "world".
     *
     * A read shorter than [window] reached the start of the field, which is a
     * sentence start, so the result opens with [SENTENCE_MARK] and an empty field
     * gives just the mark. A read that filled [window] was cut off somewhere
     * unknown instead: its first run may be half a word, so that goes too, and
     * no mark is claimed.
     */
    fun fromFieldWindow(text: CharSequence, window: Int = FIELD_WINDOW): List<String> {
        val reachedStart = text.length < window
        val end = text.length - trailingRunLength(text)
        val start = if (reachedStart) 0 else minOf(leadingRunLength(text), end)
        val out = ArrayList<String>()
        if (reachedStart) out += SENTENCE_MARK
        scan(text, start, end, out)
        return out
    }

    /**
     * The word [fromFieldWindow] leaves out, the run touching the cursor. Null when that run gives no one word: there is
     * none, it holds a digit, or it fills a read of [window] and so may have been cut off.
     */
    fun runAtCursor(text: CharSequence, window: Int = FIELD_WINDOW): String? {
        val length = trailingRunLength(text)
        if (length == 0 || (length == text.length && text.length >= window)) return null
        val out = ArrayList<String>()
        scan(text, text.length - length, text.length, out)
        return out.singleOrNull()
    }

    /** As [runAtCursor], for a cursor something has since been written up to: a space, a comma, a full stop. */
    fun runBeforeSeparators(text: CharSequence, window: Int = FIELD_WINDOW): String? {
        var end = text.length
        while (end > 0) {
            val cp = Character.codePointBefore(text, end)
            if (isRunCharacter(cp)) break
            end -= Character.charCount(cp)
        }
        return runAtCursor(text.subSequence(0, end), window)
    }

    /** Tokens from [start] until [end]. What follows a character is still read
     *  from the whole of [text], past [end], because a dot before a fragment
     *  that got cut off ("google.co") is still not the end of a sentence. */
    private fun scan(text: CharSequence, start: Int, end: Int, out: MutableList<String>) {
        val word = StringBuilder()
        var hasDigit = false

        fun flush() {
            if (word.isNotEmpty() && !hasDigit) out += word.toString().lowercase()
            word.setLength(0)
            hasDigit = false
        }

        fun endSentence() {
            flush()
            if (out.lastOrNull() != SENTENCE_MARK) out += SENTENCE_MARK
        }

        var i = start
        while (i < end) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            val next = if (i < text.length) Character.codePointAt(text, i) else NONE
            when {
                Character.isLetter(cp) -> word.appendCodePoint(cp)
                // Only ever extends a word. Emoji carry marks of their own (the
                // variation selector), and those must not open a token.
                isMark(cp) -> if (word.isNotEmpty()) word.appendCodePoint(cp)
                Character.isDigit(cp) -> hasDigit = true
                isApostrophe(cp) && word.isNotEmpty() && isLetter(next) -> word.append('\'')
                cp == HYPHEN && word.isNotEmpty() && isLetter(next) -> word.append('-')
                // Digits first, as in 90's or 3-way: no word to join yet, but the run
                // goes on, and flushing here would let its letters start clean.
                (isApostrophe(cp) || cp == HYPHEN) && hasDigit && isLetterOrDigit(next) -> Unit
                // Followed by a letter or digit it is 3.5, google.com or ?q=1.
                isSentencePunctuation(cp) && !isLetterOrDigit(next) -> endSentence()
                cp == NEWLINE || cp == CARRIAGE_RETURN -> endSentence()
                // An emoji closes the thought the way a full stop would.
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt() -> endSentence()
                else -> flush()
            }
        }
        flush()
    }

    private fun trailingRunLength(text: CharSequence): Int {
        var i = text.length
        while (i > 0) {
            val cp = Character.codePointBefore(text, i)
            if (!isRunCharacter(cp)) break
            i -= Character.charCount(cp)
        }
        return text.length - i
    }

    private fun leadingRunLength(text: CharSequence): Int {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (!isRunCharacter(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    /** Everything that can belong to one unbroken run of typing. */
    private fun isRunCharacter(cp: Int): Boolean =
        isLetterOrDigit(cp) || isMark(cp) || isApostrophe(cp) || cp == HYPHEN

    private fun isLetter(cp: Int): Boolean = cp != NONE && Character.isLetter(cp)

    private fun isLetterOrDigit(cp: Int): Boolean = cp != NONE && Character.isLetterOrDigit(cp)

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }

    private fun isApostrophe(cp: Int): Boolean = cp == STRAIGHT_APOSTROPHE || cp == CURLY_APOSTROPHE

    private fun isSentencePunctuation(cp: Int): Boolean =
        cp == FULL_STOP || cp == EXCLAMATION || cp == QUESTION || cp == ELLIPSIS

    /** No code point: what comes after the last character of the text. */
    private const val NONE = -1
    private const val STRAIGHT_APOSTROPHE = '\''.code
    private const val CURLY_APOSTROPHE = '’'.code
    private const val HYPHEN = '-'.code
    private const val FULL_STOP = '.'.code
    private const val EXCLAMATION = '!'.code
    private const val QUESTION = '?'.code
    private const val ELLIPSIS = '…'.code
    private const val NEWLINE = '\n'.code
    private const val CARRIAGE_RETURN = '\r'.code
}
