package com.localboard.keyboard.util

/**
 * The one answer to "how much space does the gesture bar take under the keys?".
 *
 * The inset can be read two ways and neither is enough alone. A query at display
 * level does not depend on this window's own frame, so it is right on a cold show,
 * but it cannot see the caption bar the input method window draws for itself. An
 * inset dispatch can see that, but one computed against a window frame that is
 * still empty or left over from another geometry reports 0, indistinguishable
 * from a real 0. So whichever source reports more wins: a dispatch can raise the
 * answer and can never lower it.
 */
object BottomInsetPolicy {

    /** The nav strip mode where the user gave the space back to the app. */
    const val MODE_RECLAIM = 3

    /**
     * @param displayBottom bottom inset from the display-level query this show; null if it failed
     * @param dispatchBottom highest bottom inset any dispatch reported this show; null if none yet
     * @param lastGood last non-zero answer for the current input view; null if none
     */
    fun resolve(navStripMode: Int, displayBottom: Int?, dispatchBottom: Int?, lastGood: Int?): Int {
        if (navStripMode == MODE_RECLAIM) return 0
        val live = maxOf(displayBottom ?: 0, dispatchBottom ?: 0)
        if (live > 0) return live
        // Zero from a query that worked is a real answer. Zero because the query
        // threw says nothing, so the last known good height stands.
        return if (displayBottom == null) lastGood ?: 0 else 0
    }
}
