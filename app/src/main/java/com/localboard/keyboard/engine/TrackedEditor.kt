package com.localboard.keyboard.engine

/** What a selection report turned out to be. */
enum class SelectionReport {
    /** This keyboard's own write coming back. */
    ECHO,

    /** The caret is where the writes left it, with nothing owed, and the editor no longer
     *  has the word composing though the keyboard does. Only time tells whether the app
     *  dropped the region or the editor is between two states. */
    ECHO_REGION_GONE,

    /** Something other than this keyboard moved the caret or changed the text. */
    REAL,
}

/** What a look at the field after a quiet moment found. */
enum class QuietCheck {
    /** Nothing to do. The field holds the word, or there was nothing to look at. */
    IN_STEP,

    /** The word is not there as the keyboard believes it is. */
    LOST,
}

/**
 * What this keyboard has written and what the editor has said back, for one connection
 * at a time. It is plain Kotlin, so a JVM test can run it: the wrapper around the real
 * connection only forwards, and a class extending an android.jar one cannot run there.
 *
 * It makes no binder call of its own. Every write function calls [write], which goes to
 * the editor, and moves the tracker only if that returned true and [write] came through
 * the current connection. False means the connection is gone, so nothing landed. A stale
 * wrapper's id is an old one, so it cannot feed the position of the field in front now.
 */
class TrackedEditor(nowMs: () -> Long) {

    val tracker = EditorTracker(nowMs)

    /** Called after each tracked write when the position is known. Keep it cheap: it runs on every key. */
    var onWrite: (() -> Unit)? = null

    /** The connection changed and the show path has not dealt with that yet. */
    var connectionIsNew = false

    private var currentId = 0

    /** The selection and composing start the editor last reported, or its first offsets at a show. */
    var lastStart = -1
        private set
    var lastEnd = -1
        private set
    var lastRegion = -1
        private set

    // An editor that never reports a region gives no sign when one goes.
    private var regionSeen = false

    /** A different connection means nothing known about the last one holds. Returns the id its writes carry. */
    fun onNewConnection(): Int {
        tracker.reset(-1, -1)
        tracker.clearBatch()
        lastStart = -1
        lastEnd = -1
        lastRegion = -1
        regionSeen = false
        connectionIsNew = true
        return ++currentId
    }

    /**
     * The keyboard came up. A new connection brings the editor's own offsets. On the same
     * connection those are old, because hide then tap, and a configuration change, deliver
     * the first show's offsets again, so the position stays. A region did not survive: the
     * framework ends it as the view goes down, and the show path ends it on a restart.
     */
    fun onShow(initialStart: Int, initialEnd: Int) {
        if (connectionIsNew) {
            tracker.reset(initialStart, initialEnd)
            lastStart = initialStart
            lastEnd = initialEnd
            lastRegion = -1
        } else {
            tracker.onFinishComposing()
            tracker.clearBatch()
        }
    }

    // [length] is taken by the caller before the write, because the text can be the live
    // composing buffer.
    fun setComposingText(id: Int, length: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (done && id == currentId) {
            tracker.onSetComposing(length)
            wrote()
        }
        return done
    }

    fun commitText(id: Int, length: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (done && id == currentId) {
            tracker.onCommit(length)
            wrote()
        }
        return done
    }

    fun deleteSurroundingText(id: Int, beforeLength: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (done && id == currentId) {
            tracker.onDeleteBefore(beforeLength)
            wrote()
        }
        return done
    }

    fun finishComposingText(id: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (done && id == currentId) {
            tracker.onFinishComposing()
            wrote()
        }
        return done
    }

    fun setSelection(id: Int, start: Int, end: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (done && id == currentId) {
            tracker.onSetSelection(start, end)
            wrote()
        }
        return done
    }

    fun beginBatchEdit(id: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (done && id == currentId) tracker.beginBatch()
        return done
    }

    /** The documented result of ending a batch is whether one is still open, so false says nothing about the connection. */
    fun endBatchEdit(id: Int, write: () -> Boolean): Boolean {
        val done = write()
        if (id == currentId) tracker.endBatch()
        return done
    }

    /** Key events, menu actions and the like, whose effect on the text cannot be known here. Their
     *  reports read as real because no write left the position they arrive at. */
    fun unpredictable(id: Int, write: () -> Boolean): Boolean = write()

    private fun wrote() {
        if (tracker.known) onWrite?.invoke()
    }

    /**
     * Sorts one selection report. [selfEditing] is the old timer, and only an editor that
     * reports no offsets still needs it, because nothing can be predicted there.
     */
    fun onSelectionReported(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        candidatesStart: Int,
        selfEditing: Boolean,
        composingNow: Boolean,
    ): SelectionReport {
        lastStart = newStart
        lastEnd = newEnd
        lastRegion = candidatesStart
        if (candidatesStart >= 0) regionSeen = true
        if (tracker.known && tracker.isOwnEcho(oldStart, oldEnd, newStart, newEnd)) {
            val regionGone = composingNow && candidatesStart < 0 && regionSeen && tracker.atRest(newStart, newEnd)
            return if (regionGone) SelectionReport.ECHO_REGION_GONE else SelectionReport.ECHO
        }
        if (!tracker.known && selfEditing) return SelectionReport.ECHO
        tracker.reset(newStart, newEnd, candidatesStart)
        return SelectionReport.REAL
    }

    /** True while the last report still says the region is gone, with no write since. */
    fun regionStillGone(): Boolean = lastRegion < 0 && tracker.atRest(lastStart, lastEnd)

    /**
     * For a quiet moment after the last write. A filter that drops text reports nothing for
     * it, so the tracker stays ahead and composing stays long, and only the text can tell.
     * A text view's filter also takes the composing region with it, because what a filter
     * hands back is a copy and the framework's mark is not copied. With no region the word
     * is plain text and the next composing write lands at the caret, so there is nothing
     * left to shorten. Any word not there as believed counts as lost, region or none: one
     * wrong read would make shortening erase a typed letter, and ending the word keeps them all.
     * Reads are ordered after writes, so a merely slow editor always shows the word here
     * and is left alone. With every echo landed this costs no binder call. With composing
     * empty, text cannot tell a truncating editor from a slow one, so the tracker stays
     * ahead until the next real move, which is safe because nothing answers from it alone.
     */
    fun afterQuiet(composing: CharSequence, read: (Int) -> CharSequence?): QuietCheck {
        if (!tracker.known || lastStart < 0 || composing.isEmpty()) return QuietCheck.IN_STEP
        if (tracker.expStart == lastStart && tracker.expEnd == lastEnd) return QuietCheck.IN_STEP
        val text = read(composing.length + 1) ?: return QuietCheck.IN_STEP
        if (text.endsWith(composing, ignoreCase = true)) return QuietCheck.IN_STEP
        tracker.reset(lastStart, lastEnd, lastRegion)
        return QuietCheck.LOST
    }
}
