package com.localboard.keyboard.engine

/**
 * An editor to write into: a text, a caret, a composing region and an optional length
 * limit. It follows BaseInputConnection and TextView as read from their sources: each
 * write outside a batch and each outer batch end reports once, a report goes out only
 * when the selection or the region changed, and a filter says nothing about what it
 * dropped. Reports wait [echoDelayMs] before they are due, as a slow editor's would.
 *
 * It knows nothing of the keyboard. A test decides when a report is delivered and to whom.
 */
class FakeEditor(
    initialText: String = "",
    caret: Int = initialText.length,
    private val maxLength: Int = Int.MAX_VALUE,
    private val echoDelayMs: Long = 0L,
    private val nowMs: () -> Long = { 0L },
) {

    class Report(
        val oldStart: Int,
        val oldEnd: Int,
        val newStart: Int,
        val newEnd: Int,
        val candidatesStart: Int,
        val candidatesEnd: Int,
        val dueAt: Long,
    )

    private val content = StringBuilder(initialText)

    val text: String get() = content.toString()

    var selStart = caret
        private set
    var selEnd = caret
        private set
    var regionStart = -1
        private set
    var regionEnd = -1
        private set

    /** False from when the connection dies: writes then return false and reads return null. */
    var connected = true

    /** Reads this editor has answered or refused, each one a binder round trip on a phone. */
    var reads = 0
        private set

    private var batchDepth = 0
    private var reportedStart = caret
    private var reportedEnd = caret
    private var reportedRegionStart = -1
    private var reportedRegionEnd = -1
    private val queue = ArrayDeque<Report>()

    fun setComposingText(text: CharSequence): Boolean = edit {
        val (a, b) = replaceRange()
        val kept = insert(a, b, text)
        selStart = a + kept
        selEnd = selStart
        // A text view's filter hands back a copy, and the framework's composing mark is not copied,
        // so a text the filter cut or emptied has no span left to mark a region with.
        val region = kept > 0 && kept == text.length
        regionStart = if (region) a else -1
        regionEnd = if (region) a + kept else -1
    }

    fun commitText(text: CharSequence): Boolean = edit {
        val (a, b) = replaceRange()
        val kept = insert(a, b, text)
        selStart = a + kept
        selEnd = selStart
        regionStart = -1
        regionEnd = -1
    }

    /** Takes text before the union of the selection and the region, and stops at the field's start. */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int = 0): Boolean = edit {
        var a = minOf(selStart, selEnd)
        var b = maxOf(selStart, selEnd)
        if (regionStart >= 0) {
            a = minOf(a, regionStart)
            b = maxOf(b, regionEnd)
        }
        val cut = minOf(beforeLength, a)
        content.delete(a - cut, a)
        selStart -= cut
        selEnd -= cut
        if (regionStart >= 0) {
            regionStart -= cut
            regionEnd -= cut
        }
        val end = minOf(b - cut + afterLength, content.length)
        content.delete(b - cut, end)
    }

    fun finishComposingText(): Boolean = edit {
        regionStart = -1
        regionEnd = -1
    }

    fun setSelection(start: Int, end: Int): Boolean = edit {
        selStart = start.coerceIn(0, content.length)
        selEnd = end.coerceIn(0, content.length)
    }

    fun beginBatchEdit(): Boolean {
        if (!connected) return false
        batchDepth++
        return true
    }

    fun endBatchEdit(): Boolean {
        if (!connected) return false
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) report()
        return batchDepth > 0
    }

    fun getTextBeforeCursor(n: Int): CharSequence? {
        reads++
        if (!connected) return null
        val end = minOf(selStart, selEnd)
        return content.substring(maxOf(0, end - n), end)
    }

    fun getTextAfterCursor(n: Int): CharSequence? {
        reads++
        if (!connected) return null
        val start = maxOf(selStart, selEnd)
        return content.substring(start, minOf(content.length, start + n))
    }

    /**
     * A tap in the field. A text view moves only the selection, so a composing region stays where it
     * is and goes out with the report, and ending the word is left to the keyboard.
     */
    fun tapMovesCaret(start: Int, end: Int = start) {
        selStart = start
        selEnd = end
        report()
    }

    /** The app putting the caret somewhere and ending the composing span with it, as replacing its text does. */
    fun appMovesCaret(start: Int, end: Int = start) {
        regionStart = -1
        regionEnd = -1
        tapMovesCaret(start, end)
    }

    /** The app emptying its field in place, as a chat app's Send can. */
    fun appClearsField() {
        content.setLength(0)
        appMovesCaret(0)
    }

    /** The app ending the composing span and leaving the text. */
    fun appDropsRegion() {
        regionStart = -1
        regionEnd = -1
        report()
    }

    /** Reports due by now, oldest first, gone from the queue. */
    fun takeDue(): List<Report> {
        val now = nowMs()
        val due = ArrayList<Report>()
        while (queue.isNotEmpty() && queue.first().dueAt <= now) due += queue.removeFirst()
        return due
    }

    /** Every report still queued, as the one report an editor that coalesces them would send. */
    fun takeMerged(): Report? {
        if (queue.isEmpty()) return null
        val first = queue.first()
        val last = queue.last()
        queue.clear()
        return Report(
            first.oldStart, first.oldEnd, last.newStart, last.newEnd,
            last.candidatesStart, last.candidatesEnd, last.dueAt,
        )
    }

    val pendingReports: Int get() = queue.size

    private fun edit(block: () -> Unit): Boolean {
        if (!connected) return false
        block()
        if (batchDepth == 0) report()
        return true
    }

    /** A region when there is one, otherwise the selection. */
    private fun replaceRange(): Pair<Int, Int> =
        if (regionStart >= 0) regionStart to regionEnd else minOf(selStart, selEnd) to maxOf(selStart, selEnd)

    /** Puts in as much of [text] as the limit lets through, and returns how much that was. */
    private fun insert(a: Int, b: Int, text: CharSequence): Int {
        val room = (maxLength - (content.length - (b - a))).coerceAtLeast(0)
        val kept = minOf(room, text.length)
        content.replace(a, b, text.subSequence(0, kept).toString())
        return kept
    }

    private fun report() {
        if (selStart == reportedStart && selEnd == reportedEnd &&
            regionStart == reportedRegionStart && regionEnd == reportedRegionEnd
        ) {
            return
        }
        queue.addLast(Report(reportedStart, reportedEnd, selStart, selEnd, regionStart, regionEnd, nowMs() + echoDelayMs))
        reportedStart = selStart
        reportedEnd = selEnd
        reportedRegionStart = regionStart
        reportedRegionEnd = regionEnd
    }
}
