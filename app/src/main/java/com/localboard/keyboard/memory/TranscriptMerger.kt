package com.localboard.keyboard.memory

data class MergeResult(
    /** Lines older than anything held: store before the current head. */
    val older: List<Turn>,
    /** Lines newer than anything held: append. */
    val newer: List<Turn>,
    val overlap: Int,
    val disjoint: Boolean = false
)

/**
 * Aligns a capture of the visible chat against the stored tail BY POSITION, so a
 * repeated "ok" stays two messages. Comparing lines as a set is exactly how a fresh
 * "ok" gets thrown away for having been said once before.
 */
object TranscriptMerger {
    /**
     * [atBottom] is false when the list node can still scroll forward (user scrolled up).
     *
     * Repeats can make more than one alignment fit, and where the list sits says which to
     * believe. Scrolled up, the capture is history, so it is read first as something already
     * held or as lines above it. At the bottom its last line is the newest message, so a
     * look-alike higher up in what is stored must not swallow it.
     */
    fun merge(stored: List<Turn>, capture: List<Turn>, atBottom: Boolean = true): MergeResult {
        if (capture.isEmpty()) return MergeResult(emptyList(), emptyList(), 0)
        if (stored.isEmpty()) return MergeResult(emptyList(), capture, 0)
        val a = Aligner(stored, capture)
        if (!atBottom) (a.heldInside() ?: a.aboveHead())?.let { return it }
        a.spansStored()?.let { return it }
        a.overlapsTail()?.let { return it }
        if (atBottom) a.heldInside()?.let { return it }
        a.tailFurtherIn()?.let { return it }
        if (atBottom) a.aboveHead()?.let { return it }
        // Nothing in common. Only a capture at the bottom of the list is "now".
        return MergeResult(emptyList(), if (atBottom) capture else emptyList(), 0, disjoint = true)
    }

    /** One merge's worth of comparisons, with each line's comparison key worked out once. */
    private class Aligner(private val stored: List<Turn>, private val capture: List<Turn>) {
        private val n = stored.size
        private val m = capture.size
        private val storedKeys = stored.map { TextNorm.cmp(it.text) }
        private val captureKeys = capture.map { TextNorm.cmp(it.text) }

        private fun same(s: Int, c: Int): Boolean {
            if (storedKeys[s] != captureKeys[c]) return false
            val a = stored[s].speaker
            val b = capture[c].speaker
            return a == b || a == Speaker.UNKNOWN || b == Speaker.UNKNOWN
        }

        private fun matchAt(sFrom: Int, cFrom: Int, len: Int): Boolean {
            for (i in 0 until len) if (!same(sFrom + i, cFrom + i)) return false
            return true
        }

        /** A one-line overlap on "ok" is a coincidence; on a real sentence it is not. */
        private fun strong(sFrom: Int, len: Int): Boolean =
            len == n || len >= 2 || stored[sFrom].text.length >= 12

        /** The whole capture is already held: nothing new. */
        fun heldInside(): MergeResult? {
            if (m > n) return null
            for (s in n - m downTo 0) {
                if (matchAt(s, 0, m)) return MergeResult(emptyList(), emptyList(), m)
            }
            return null
        }

        /** Everything held lines up inside the capture: what surrounds it is new both ways. */
        fun spansStored(): MergeResult? {
            if (n > m) return null
            for (i in 0..(m - n)) {
                if (matchAt(0, i, n)) {
                    return MergeResult(capture.subList(0, i), capture.subList(i + n, m), n)
                }
            }
            return null
        }

        /** The capture opens on the stored tail: the ordinary overlap of a chat that has
         *  moved on since the last capture. */
        fun overlapsTail(): MergeResult? {
            for (k in minOf(n - 1, m) downTo 1) {
                if (strong(n - k, k) && matchAt(n - k, 0, k)) {
                    return MergeResult(emptyList(), capture.subList(k, m), k)
                }
            }
            return null
        }

        /** The stored tail turns up further into the capture, with the lines before it not
         *  matching what is held: an older message was edited or deleted. */
        fun tailFurtherIn(): MergeResult? {
            for (k in minOf(n - 1, m - 1) downTo 1) {
                if (!strong(n - k, k)) continue
                for (i in 1..(m - k)) {
                    if (matchAt(n - k, i, k)) {
                        return MergeResult(emptyList(), capture.subList(i + k, m), k)
                    }
                }
            }
            return null
        }

        /** The capture's tail lines up with the stored head: scrolled above what we hold. */
        fun aboveHead(): MergeResult? {
            for (k in minOf(n, m) downTo 1) {
                if (strong(0, k) && matchAt(0, m - k, k)) {
                    return MergeResult(capture.subList(0, m - k), emptyList(), k)
                }
            }
            return null
        }
    }
}
