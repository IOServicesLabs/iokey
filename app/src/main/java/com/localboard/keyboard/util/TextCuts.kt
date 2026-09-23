package com.localboard.keyboard.util

/** takeLast that never slices a message in half: when the size cap would
 *  land mid-line, the leading partial line is dropped. A transcript whose
 *  most recent visible fragment is half a sentence from the wrong speaker
 *  is worse context than slightly less context — that tail position is
 *  exactly what a small model anchors on. (B6) */
fun takeLastWholeLines(s: String, maxChars: Int): String {
    if (s.length <= maxChars) return s
    val cut = s.length - maxChars
    val nl = s.indexOf('\n', cut)
    if (nl in 0 until s.length - 1) return s.substring(nl + 1)
    // P5: no line boundary AFTER the cut (the tail is one long line) — the
    // raw slice would open mid-message and the model anchors on that
    // fragment. Fall back to the LAST newline BEFORE the cut instead:
    // keeping slightly over budget from a clean boundary beats a sliver of
    // someone else's sentence. Only a transcript with no newlines at all
    // still slices raw.
    val before = s.lastIndexOf('\n', cut - 1)
    return if (before >= 0) s.substring(before + 1) else s.substring(cut)
}
