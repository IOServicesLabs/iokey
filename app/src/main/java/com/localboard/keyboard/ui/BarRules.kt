package com.localboard.keyboard.ui

import com.localboard.keyboard.engine.Suggestion

/**
 * What the suggestion bar's slots mean and what a press on one does, kept apart from the view so that one answer
 * serves the drawing, the tap, the glide's slide up and the screen reader. Those disagreeing is how a tap lands a word
 * nobody aimed at.
 */
internal object BarRules {

    /** Slot position to list position: the centre slot shows the top pick. */
    private val SLOT_ORDER = intArrayOf(1, 0, 2)

    /** The word as typed. Not unique to it: an engine word can score exactly this as well, which is why position counts. */
    private const val RAW_SCORE = 0.0

    /** The chip that undoes an automatic correction. */
    private const val REVERT_SCORE = -1.0

    /** A list that changes sooner after the last one than this is typing, and is drawn at once: words fade only after stillness. */
    private const val TYPING_GAP_MS = 1000L

    /**
     * Where the word as typed is in [list], or -1. While a word is being typed the list is [centre, raw, right], or
     * exactly [raw] when there is nothing to put in the centre.
     */
    fun rawIndex(list: List<Suggestion>): Int = when {
        list.size >= 2 && list[1].score == RAW_SCORE -> 1
        list.size == 1 && list[0].score == RAW_SCORE -> 0
        else -> -1
    }

    /** The word as typed and nothing else. It draws on the left, where that word always is, and the centre stays empty. */
    fun isRawOnly(list: List<Suggestion>): Boolean = rawIndex(list) == 0

    /** The list position shown in [slot], which may be past the end of the list, or -1. */
    fun indexInSlot(list: List<Suggestion>, slot: Int): Int = when {
        slot !in SLOT_ORDER.indices -> -1
        isRawOnly(list) -> if (slot == 0) 0 else -1
        else -> SLOT_ORDER[slot]
    }

    fun wordInSlot(list: List<Suggestion>, slot: Int): Suggestion? = list.getOrNull(indexInSlot(list, slot))

    /** What a finger coming down on [slot] saw. A list can change under a finger before it lifts. */
    class Press(
        val slot: Int,
        val word: Suggestion?,
        val wasRaw: Boolean,
        val listHadRaw: Boolean,
        val listWasEmpty: Boolean
    )

    /**
     * [composing] is what was being typed as the finger came down. A lone word with nothing being typed is the one way
     * back a glide leaves on the left. It only shares the raw word's score and place, and it is a word like any other.
     */
    fun pressOn(list: List<Suggestion>, slot: Int, composing: String): Press {
        val index = indexInSlot(list, slot)
        val raw = if (isRawOnly(list) && composing.isEmpty()) -1 else rawIndex(list)
        return Press(
            slot = slot,
            word = list.getOrNull(index),
            wasRaw = raw >= 0 && index == raw,
            listHadRaw = raw >= 0,
            listWasEmpty = list.isEmpty()
        )
    }

    /** A show means another field or a restart, so a press from before it could land in a password box, and a change of
     *  mode puts a different button under the finger. Either way nothing happens, in any slot. */
    fun pressStands(showSinceDown: Boolean, modeChanged: Boolean): Boolean = !showSinceDown && !modeChanged

    /**
     * The word a lift on a word slot commits, or null for none. The aim is the word that was under the finger at
     * the press, unless the field has moved on so far that committing it would do harm. [composing] is what is being
     * typed at the lift, because the list can be a pass behind it.
     */
    fun wordToCommit(
        press: Press,
        latest: List<Suggestion>,
        showSinceDown: Boolean,
        modeChanged: Boolean,
        revertLive: Boolean,
        composing: String
    ): String? {
        if (!pressStands(showSinceDown, modeChanged)) return null
        if (latest.isEmpty()) return null
        val word = press.word ?: return null
        return when {
            // A chip whose fix has gone would commit the typo as a chosen word, and teach it.
            word.score == REVERT_SCORE ->
                word.text.takeIf { revertLive && latest.any { it.score == REVERT_SCORE && it.text == word.text } }
            // The word being typed began or ended under the finger: it would double a word or replace a letter.
            press.listHadRaw != composing.isNotEmpty() -> null
            // The word as typed may have grown under the finger, and the old letters would lose the newest one.
            press.wasRaw -> composing
            else -> word.text
        }
    }

    /** The pointer says "Write it for me", so a lift on it starts that, and only if it was there all along. */
    fun pointerStartsDraft(
        press: Press,
        latest: List<Suggestion>,
        showSinceDown: Boolean,
        modeChanged: Boolean,
        chipShowing: Boolean,
        draftingAvailable: Boolean
    ): Boolean =
        pressStands(showSinceDown, modeChanged) && press.slot == 1 && press.listWasEmpty &&
            latest.isEmpty() && !chipShowing && draftingAvailable

    /** A list that only replaces the last one a moment later is typing, and a fade would only hide the words from it. */
    fun animates(now: Long, lastChangeAt: Long, animLevel: Int): Boolean =
        animLevel > 0 && now - lastChangeAt >= TYPING_GAP_MS

    /**
     * Whether a show takes up the stored answer about the sparkle. A restart keeps the answer it has, because a yes that
     * arrived from a background download would shift the slots under a thumb still aiming at them.
     */
    fun readsStoredAnswer(restarting: Boolean, answerHeld: Boolean?): Boolean = !restarting || answerHeld == null
}
