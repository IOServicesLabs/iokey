package com.localboard.keyboard.engine

/**
 * Where the selection should be, worked out from what this keyboard itself told
 * the editor to do.
 *
 * onUpdateSelection arrives asynchronously, so the only honest answer to "is this
 * update my own edit coming back, or did something else move the cursor?" is to
 * compare it against where the keyboard's own edits should have left things. Any
 * guess by clock fails both ways: a slow editor's echo looks like an outside
 * move, which splits the word being typed, and a real change inside the window
 * goes unseen, which duplicates text.
 *
 * Offsets are UTF-16 positions, as the editor reports them. Every edit is assumed
 * to place the cursor just after the inserted text, a new cursor position of 1,
 * which is the only form the keyboard uses. Key events and context-menu actions
 * cannot be predicted and are not fed in, so they correctly show up as real moves.
 *
 * Comparing with the final expectation alone is not enough, because own writes that
 * reverse direction outside a batch would read as real, so each write also leaves the
 * position it expects to hear about. [isOwnEcho] asks those first. Those entries have
 * limits, all rare and inferred from reading: a real tap landing exactly on an entry
 * reads as an echo for up to 2 s, and an editor that merges a reversal into one report
 * leaves entries behind until they age out. The worst of either is one pull-back of the
 * caret. An editor that ignored batches and reported inside one would read as a real
 * move, which TextView, Chrome and Flutter are not known to do.
 */
class EditorTracker(private val nowMs: () -> Long = { 0L }) {

    var expStart = -1
        private set
    var expEnd = -1
        private set

    /** Start of the composing region, or -1 when nothing is composing. */
    var composingStart = -1
        private set

    /** False for an editor that does not report offsets. Nothing can be predicted
     *  there, and every edit below leaves the state alone. */
    val known: Boolean get() = expStart >= 0

    // Where the writes still owed an echo should have left the selection, oldest first.
    // Fixed arrays, because this runs on every key.
    private val pendingStart = IntArray(MAX_PENDING)
    private val pendingEnd = IntArray(MAX_PENDING)
    private val pendingAt = LongArray(MAX_PENDING)
    private var pendingCount = 0

    private var batchDepth = 0
    private var wroteInBatch = false

    /** A position the editor itself reported, which overrides any expectation. Its
     *  composing region counts too: forgetting a word still alive leaves the expectation
     *  ahead of the caret, and then a tap just past it reads as an echo. */
    fun reset(start: Int, end: Int, candidatesStart: Int = -1) {
        expStart = start
        expEnd = end
        composingStart = candidatesStart
        pendingCount = 0
    }

    /** An editor applies a batch as one change and reports it once, at the outer end. */
    fun beginBatch() {
        batchDepth++
    }

    fun endBatch() {
        if (batchDepth == 0) return
        batchDepth--
        if (batchDepth == 0 && wroteInBatch) {
            wroteInBatch = false
            if (known) remember()
        }
    }

    /** A batch left open by a connection that went away ends with it. */
    fun clearBatch() {
        batchDepth = 0
        wroteInBatch = false
    }

    /** setComposingText with text of [len]: replaces the composing region, or the
     *  selection when nothing is composing. */
    fun onSetComposing(len: Int) {
        if (!known) return
        val base = editStart()
        composingStart = if (len > 0) base else -1
        expStart = base + len
        expEnd = expStart
        wrote()
    }

    /** commitText with text of [len]: same replacement rule, and it ends composing. */
    fun onCommit(len: Int) {
        if (!known) return
        val base = editStart()
        composingStart = -1
        expStart = base + len
        expEnd = expStart
        wrote()
    }

    /** The caret does not move, so no position is owed for this. */
    fun onFinishComposing() {
        composingStart = -1
    }

    /** deleteSurroundingText([n], 0). The framework removes text before the union
     *  of the composing region and the selection, and stops at the field's start. */
    fun onDeleteBefore(n: Int) {
        if (!known) return
        val cut = minOf(n, editStart())
        // Nothing before the caret to take: the editor has nothing to report.
        if (cut == 0) return
        if (composingStart >= 0) composingStart -= cut
        expStart -= cut
        expEnd -= cut
        wrote()
    }

    fun onSetSelection(start: Int, end: Int) {
        expStart = start
        expEnd = end
        wrote()
    }

    /**
     * True when an update to [newStart]..[newEnd] is this keyboard's own write coming
     * back. A position a write left behind answers first, and it and everything older
     * have then been heard. Otherwise [isBelated] decides.
     */
    fun isOwnEcho(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): Boolean {
        forgetAged()
        for (i in 0 until pendingCount) {
            if (pendingStart[i] == newStart && pendingEnd[i] == newEnd) {
                dropOldest(i + 1)
                return true
            }
        }
        return isBelated(oldStart, oldEnd, newStart, newEnd)
    }

    /** True when no echo is owed and [start]..[end] is where the writes should have ended up. */
    fun atRest(start: Int, end: Int): Boolean {
        forgetAged()
        return pendingCount == 0 && expStart == start && expEnd == end
    }

    /**
     * True when an update from [oldStart]..[oldEnd] to [newStart]..[newEnd] is this
     * keyboard's own edit coming back, possibly one of several still on their way.
     *
     * Two things it cannot see. An echo running against the latest edit, a letter's
     * coming back after the backspace that followed it, reads as a real move. And an
     * editor that truncates reports nothing for what it dropped, so the expectation
     * stays ahead of the text until the next real move.
     */
    fun isBelated(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int): Boolean {
        // Exactly where the edits should have ended up.
        if (expStart == newStart && expEnd == newEnd) return true
        // Everything had already landed, then the selection moved anyway. Only
        // something other than this keyboard could have done that.
        if (expStart == oldStart && expEnd == oldEnd &&
            (oldStart != newStart || oldEnd != newEnd)
        ) {
            return false
        }
        // The editor is behind on a burst of edits: a cursor somewhere on the way
        // from the old position to the expected one.
        return newStart == newEnd &&
            (newStart - oldStart) * (expStart - newStart) >= 0 &&
            (newEnd - oldEnd) * (expEnd - newEnd) >= 0
    }

    private fun editStart(): Int =
        if (composingStart >= 0) composingStart else minOf(expStart, expEnd)

    private fun wrote() {
        if (batchDepth > 0) wroteInBatch = true else remember()
    }

    private fun remember() {
        if (pendingCount == MAX_PENDING) dropOldest(1)
        pendingStart[pendingCount] = expStart
        pendingEnd[pendingCount] = expEnd
        pendingAt[pendingCount] = nowMs()
        pendingCount++
    }

    private fun forgetAged() {
        val now = nowMs()
        var aged = 0
        while (aged < pendingCount && now - pendingAt[aged] > PENDING_MAX_AGE_MS) aged++
        if (aged > 0) dropOldest(aged)
    }

    private fun dropOldest(count: Int) {
        val left = pendingCount - count
        System.arraycopy(pendingStart, count, pendingStart, 0, left)
        System.arraycopy(pendingEnd, count, pendingEnd, 0, left)
        System.arraycopy(pendingAt, count, pendingAt, 0, left)
        pendingCount = left
    }

    private companion object {
        const val MAX_PENDING = 16
        const val PENDING_MAX_AGE_MS = 2_000L
    }
}
