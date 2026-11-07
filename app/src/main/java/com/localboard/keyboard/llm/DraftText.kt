package com.localboard.keyboard.llm

/**
 * How a finished draft goes into text that is already there. A model carrying on from an
 * unfinished message often repeats that message first, and the field has no space waiting.
 *
 * No Android in here, so the rules can be tested off the device.
 */
object DraftText {
    /** Text that needs no space after it. Straight quotes can open or close, so they are not here. */
    private const val OPENERS = "([{“‘"

    /** Marks that sit against the word before them. */
    private const val CLOSERS = ",.!?;:)]}’”…%"

    /** How much of the end of a long text can go to Backspace and it still count as the same text. */
    private const val DELETED_FROM_LONG_TEXT = 64

    /**
     * What to put at the cursor so that [draft] reads correctly after [before] and ahead of
     * [after]. Empty when the field already ends with everything the draft says.
     */
    fun forInsertion(before: String, draft: String, after: String = ""): String {
        val body = withoutRepeatOf(before, draft.trim())
        if (body.isEmpty()) return ""
        val lead = if (needsSpaceBetween(before, body)) " " else ""
        val trail = if (after.firstOrNull()?.isLetterOrDigit() == true) " " else ""
        return lead + body + trail
    }

    /**
     * [draft] without the end of [before] that it starts by repeating. Only whole words count,
     * so "so" does not eat the start of "sounds good".
     */
    fun withoutRepeatOf(before: String, draft: String): String {
        val tail = before.trimEnd()
        for (len in minOf(tail.length, draft.length) downTo 1) {
            if (!tail.regionMatches(tail.length - len, draft, 0, len, ignoreCase = true)) continue
            val startsAtWord = tail.length == len || tail[tail.length - len - 1].isWhitespace()
            val endsAtWord = draft.length == len || !draft[len].isLetterOrDigit()
            if (startsAtWord && endsAtWord) return draft.substring(len).trimStart()
        }
        return draft
    }

    private fun needsSpaceBetween(before: String, text: String): Boolean {
        val last = before.lastOrNull() ?: return false
        val first = text.firstOrNull() ?: return false
        return !last.isWhitespace() && last !in OPENERS && !first.isWhitespace() && first !in CLOSERS
    }

    /**
     * How many characters before the cursor to delete so a rewrite replaces [source], or null
     * when the field no longer ends with it. Spaces after the source go with it. [before] must
     * be read with room to spare: a text that came back cut short does not match.
     */
    fun charsToReplace(before: String, source: String): Int? {
        val trimmed = before.trimEnd()
        if (source.isEmpty() || !trimmed.endsWith(source)) return null
        return source.length + (before.length - trimmed.length)
    }

    /**
     * Whether [before], the text before the cursor now, can still be the text that [kept] was read
     * from, which was cut to its last [limit] characters. Typing on after it, or deleting back into
     * it, is the same text. Anything else means the field holds something else by now, such as
     * another chat in a box the app reuses. Nothing can be said about an empty [kept], so it passes.
     * [before] must be read with room for what was typed since.
     */
    fun stillFollows(before: String, kept: String, limit: Int): Boolean {
        // Cut to the limit, it can start anywhere in what is read now, and only its end can have gone.
        if (kept.length >= limit) return before.contains(kept.dropLast(DELETED_FROM_LONG_TEXT))
        return before.startsWith(kept) || (before.isNotEmpty() && kept.startsWith(before))
    }
}
