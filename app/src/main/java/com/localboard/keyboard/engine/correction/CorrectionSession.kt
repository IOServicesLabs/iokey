package com.localboard.keyboard.engine.correction

/** The input connection, reduced to what undo needs. The service implements it. */
interface EditorPort {
    fun textBefore(n: Int): String
    fun setComposing(text: String)
    fun deleteBefore(n: Int)
    fun commit(text: String)
    fun batch(block: () -> Unit)   // beginSelfEdit + beginBatchEdit ... endBatchEdit + endSelfEdit
}

/** Everything a correction may teach or take back. The service implements it. */
interface LearningPort {
    /** predictionsEnabled && prefs.learningEnabled && !incognito */
    val canLearn: Boolean
    fun pushContext(word: String)  // committedWords and messageWords only
    fun popContext(word: String)
    /** [fix] becomes [raw] on the trail. A sentence mark after it stays after it, so the next word still follows the mark. */
    fun swapContext(fix: String, raw: String) { popContext(fix); pushContext(raw) }
    fun learn(word: String, prev1: String, prev2: String, selected: Boolean)
    fun unlearn(word: String)
    fun unconfirm(word: String)
    fun noteTypo(word: String)
    fun clearTypoVerdict(word: String)
    fun tapSignal(raw: String, fix: String)
    fun persistRejections()
    /** The user took back one automatic correction. Called once per correction, whichever way it went. */
    fun correctionRefused() {}
}

data class AppliedFix(val raw: String, val fix: String, val separator: String, val prev1: String, val prev2: String)

/**
 * What finishComposingWord does with a word that just left composing. This says how the word counts,
 * not whether it may: the caller still checks [LearningPort.canLearn] before teaching the model.
 */
enum class CommitLearning { NORMAL, SELECTED, CONTEXT_ONLY, NOTHING }

/** Returned when an undo ran. Non-null [composing] must be mirrored into the service's buffer. */
data class UndoResult(val composing: String?)

/**
 * Remembers what happened to the word being typed and to the last correction, so that taking a
 * correction back sticks. Main-thread only. The service does the input-connection calls and
 * forwards its events here; the decisions live here so they can run on a JVM with a fake editor.
 *
 * A correction teaches nothing until it has stood: the fix is learned by [settle], when the next
 * word commits or the keyboard hides. So an undo has nothing to roll back, and a fix older than the
 * most recent one, which has taught the model, is rolled back only if it is refused later.
 *
 * [onAutoCorrected] and [onWordCommitted] settle first, for that reason: the previous fix is
 * accepted before the next is recorded, and [tryUndo] only ever touches the most recent one.
 */
class CorrectionSession(
    private val editor: EditorPort,
    private val learning: LearningPort,
    val memory: RejectionMemory,
    private val volatileMemory: RejectionMemory,
) {
    // --- State of the word being composed.
    var provenance = Provenance.TYPED; private set
    /** The fix the user refused, if this word is a response to a correction. */
    var refusedFix: String? = null; private set
    /** The original spelling behind [refusedFix]: offered as the centre chip while RESUMED. */
    var refusedRaw: String? = null; private set
    private var explicit = false
    private var midWord = false
    private var fixSettled = false
    private var resumedFrom: String? = null
    private var unlearnedAtReentry = false
    private var ringHit: RejectionMemory.Recent? = null
    private var reenteredFix: RejectionMemory.Recent? = null
    /** What the trail ended with when this word started, while a retype of it may still need spotting. */
    private var startedAfter: String? = null
    /** A recent fix that a selection took out of the field. The next word goes in where it was. */
    private var removedFix: RejectionMemory.Recent? = null

    // --- State of the last correction.
    var lastFix: AppliedFix? = null; private set
    private var pending: AppliedFix? = null
    private var pendingRecent: RejectionMemory.Recent? = null

    // --- State of the last bar tap that kept a word as typed, and what that word had before it.
    private class ChipKeep(val word: String, val before: RejectionMemory.WordRecord?, val confirmedNew: Boolean)
    private var chipKept: ChipKeep? = null

    private fun active() = if (learning.canLearn) memory else volatileMemory

    fun isProtected(w: String) = memory.isProtected(w) || volatileMemory.isProtected(w)
    fun bannedFixes(w: String) = memory.bannedFixes(w) + volatileMemory.bannedFixes(w)

    private fun resetWord() {
        provenance = Provenance.TYPED
        refusedFix = null; refusedRaw = null
        explicit = false; midWord = false; fixSettled = false
        resumedFrom = null; unlearnedAtReentry = false; ringHit = null; reenteredFix = null
        startedAfter = null; removedFix = null
    }

    /** First letter of a new composing word, with what the trail ends with as [prev1]. */
    fun onWordStart(charBefore: Char?, charAfter: Char?, prev1: String) {
        lastFix = null
        chipKept = null
        val removed = removedFix
        resetWord()
        removedFix = removed
        startedAfter = prev1
        if (isWordChar(charBefore) || isWordChar(charAfter)) {
            provenance = Provenance.RESUMED
            midWord = true
        }
    }

    fun onComposingBackspace(nowEmpty: Boolean) {
        if (nowEmpty) {
            // Going back into a fix and keeping it erases nothing, so it counts only once all of it is gone.
            reenteredFix?.erased = true
            resetWord()
        } else if (ringHit != null) {
            // The retyped spelling was edited by hand. Left as a ring hit it would fall back to TYPED
            // at the next letter, and the edited word would be corrected.
            ringHit = null
            provenance = Provenance.EDITED
            refusedFix = null; refusedRaw = null; explicit = false; fixSettled = false
        } else if (provenance == Provenance.TYPED) provenance = Provenance.EDITED
    }

    /**
     * Backspace re-entered a committed word. Call after popping [popped] from committedWords,
     * with [unlearned] true when the caller already took back its last count.
     */
    fun onFragmentReentered(fragment: String, prev1: String, popped: String?, unlearned: Boolean) {
        resetWord()
        provenance = Provenance.RESUMED
        resumedFrom = popped?.lowercase()
        unlearnedAtReentry = unlearned
        val hit = memory.matchFragment(WordShape.normalized(fragment.lowercase()), prev1) ?: return
        reenteredFix = hit
        refusedFix = hit.fix
        refusedRaw = hit.raw
        fixSettled = hit.settled
        if (pending?.fix?.lowercase() == hit.fix) { pending = null; pendingRecent = null; fixSettled = false }
    }

    /**
     * Provenance for the policy snapshot. Idempotent: call it from the refresh, at commit, and
     * from Space. The ring turns "erased and typed the same thing again" into a refusal.
     */
    fun effectiveProvenance(typedLower: String, prev1: String): Provenance {
        if (provenance != Provenance.TYPED && ringHit == null) return provenance
        val hit = memory.matchRetype(typedLower, prev1) ?: removedFix?.takeIf { it.raw == typedLower }
        if (hit != null) {
            ringHit = hit
            provenance = Provenance.REVERTED
            refusedFix = hit.fix
            refusedRaw = hit.raw
            explicit = hit.undone
            fixSettled = hit.settled
        } else if (ringHit != null) {
            // The word has moved on from the retyped spelling.
            ringHit = null
            provenance = Provenance.TYPED
            refusedFix = null; refusedRaw = null; explicit = false; fixSettled = false
        }
        return provenance
    }

    fun onAutoCorrected(raw: String, out: String, separator: String, prev1: String, prev2: String) {
        settle()
        resetWord()
        val f = AppliedFix(raw, out, separator, prev1, prev2)
        lastFix = f
        pending = f
        pendingRecent = memory.noteAutoCorrected(raw, out, prev1)
        learning.pushContext(out)
    }

    /** Backspace (byChip = false) or the revert chip (byChip = true). Null when nothing was undone. */
    fun tryUndo(byChip: Boolean): UndoResult? {
        val f = lastFix ?: return null
        val probe = f.fix.length + f.separator.length
        lastFix = null
        if (editor.textBefore(probe) != f.fix + f.separator) return null
        pending = null                         // nothing was learned, so nothing to roll back
        pendingRecent?.undone = true
        countRefusal(pendingRecent)
        pendingRecent = null
        var composing: String? = null
        editor.batch {
            editor.deleteBefore(probe)
            if (f.separator == " " && !byChip) {
                learning.popContext(f.fix)
                resetWord()
                provenance = Provenance.REVERTED
                refusedFix = f.fix.lowercase(); refusedRaw = f.raw.lowercase(); explicit = true
                editor.setComposing(f.raw)
                composing = f.raw
            } else {
                editor.commit(f.raw + f.separator)
                learning.swapContext(f.fix, f.raw)
                recordRefusal(f.raw.lowercase(), f.raw.lowercase(), f.fix.lowercase(), true)
                if (learning.canLearn) learning.learn(f.raw, f.prev1, f.prev2, true)
            }
        }
        return UndoResult(composing)
    }

    /** A word left composing. Tells finishComposingWord how to account for it. */
    fun onWordCommitted(word: String, prev1: String): CommitLearning {
        settle()
        val w = word.lowercase()
        effectiveProvenance(w, prev1)
        val prov = provenance
        val fix = refusedFix
        val raw = refusedRaw
        val wasExplicit = explicit
        val settled = fixSettled
        val mid = midWord
        val from = resumedFrom
        val unlearned = unlearnedAtReentry
        val retyped = ringHit
        val reentered = reenteredFix
        resetWord()
        active().touch(w)
        return when (prov) {
            Provenance.TYPED, Provenance.EDITED -> CommitLearning.NORMAL
            Provenance.REVERTED -> {
                if (fix == null || w == fix) return CommitLearning.NORMAL  // typed the fix by hand after all
                if (retyped != null && !wasExplicit) {
                    return if (noteRetype(w, fix, settled, retyped)) CommitLearning.NORMAL else CommitLearning.CONTEXT_ONLY
                }
                countRefusal(retyped)
                // A different spelling can be a slip made while changing the word, so it stays on
                // screen this once and is not kept. The cost: a word meant that way may be corrected
                // next time, and one more undo protects it then.
                val kept = wasExplicit && w == raw
                recordRefusal(w, raw, fix, kept)
                if (kept) CommitLearning.SELECTED else CommitLearning.NORMAL
            }
            Provenance.RESUMED -> when {
                mid -> CommitLearning.NOTHING                 // part of something longer: never a word
                fix != null && w == raw -> {
                    countRefusal(reentered)
                    if (settled) rollBackSettled(raw, fix)
                    recordRefusal(w, raw, fix, true)
                    CommitLearning.SELECTED
                }
                from == w -> if (unlearned) CommitLearning.NORMAL else CommitLearning.CONTEXT_ONLY
                else -> CommitLearning.NORMAL
            }
        }
    }

    /** The left chip committed the composing word exactly as typed, with [candidate] on offer. */
    fun onRawChipKept(word: String, candidate: String?, wasConfirmed: Boolean) {
        val w = word.lowercase()
        val refused = refusedFix ?: candidate?.lowercase()
        settle()
        resetWord()
        keepByChip(w, refused, wasConfirmed)
    }

    /** A re-entered fix was composing, the chip offered the original spelling, and the user took it. */
    fun onRefusedRawChosen(wasConfirmed: Boolean) {
        val raw = refusedRaw ?: return
        val fix = refusedFix ?: return
        val settled = fixSettled
        val reentered = reenteredFix
        settle()
        resetWord()
        countRefusal(reentered)
        if (settled) rollBackSettled(raw, fix)
        keepByChip(raw, fix, wasConfirmed)
    }

    private fun keepByChip(w: String, refused: String?, wasConfirmed: Boolean) {
        val before = active().recordOf(w)
        recordRefusal(w, w, refused, true)
        chipKept = ChipKeep(w, before, confirmedNew = !wasConfirmed)
    }

    /** Any other bar word replaced the composing word, or a snippet expanded. */
    fun onChipReplacedWord() { settle(); resetWord(); lastFix = null; chipKept = null }

    /** A bar word replaced [raw]. If the user had refused exactly that fix, they want it after all. */
    fun onFixPicked(raw: String, fix: String) {
        if (active().liftBan(raw, fix) && learning.canLearn) learning.persistRejections()
    }

    /** The user removed [word] from what the keyboard knows, which takes what they refused about it too. */
    fun onWordForgotten(word: String) {
        memory.forget(word)
        volatileMemory.forget(word)
        if (chipKept?.word == word.lowercase()) chipKept = null
        // Taking something out teaches nothing, so this is written whatever the current field allows.
        if (memory.dirty) learning.persistRejections()
    }

    /**
     * Backspace right after a bar tap wiped the word: that tap was a slip, so take it back, and only
     * it. Whatever the word had before stands, or one slip would bring back a refused correction.
     */
    fun onGestureWiped(word: String) {
        val tap = chipKept ?: return
        if (tap.word != word.lowercase()) return
        active().restoreWord(tap.word, tap.before)
        if (tap.confirmedNew && learning.canLearn) learning.unconfirm(tap.word)
        if (learning.canLearn) learning.persistRejections()
        chipKept = null
    }

    /** Word delete erased [deleted], leaving [prev1] before the caret. A fix that goes this way was never accepted. */
    fun onWordDeleted(deleted: String, prev1: String) {
        lastFix = null
        val erased = memory.noteWordErased(WordShape.normalized(deleted.lowercase()), prev1)
        if (pending?.fix.equals(deleted, ignoreCase = true) || (erased != null && erased === pendingRecent)) {
            pending = null; pendingRecent = null
        }
    }

    /**
     * A selection is leaving the field: the next letter replaces it, or Backspace took it. If it held
     * exactly a recent fix, that fix is erased and was never accepted, as with a word delete. It can sit
     * anywhere, and the trail cannot say what came before it there, so the word typed next is matched
     * against the fix itself.
     */
    fun onSelectionRemoved(selected: String) {
        // Inside a larger selection it goes just the same, and text taken out teaches nothing.
        if (pending?.fix?.let { selected.contains(it, ignoreCase = true) } == true) { pending = null; pendingRecent = null }
        val erased = memory.noteFixRemoved(WordShape.contextForm(selected.trim()).lowercase()) ?: return
        removedFix = erased
        if (erased === pendingRecent) { pending = null; pendingRecent = null }
    }

    fun onExternalCursorMove() { lastFix = null; removedFix = null }

    /** Something went in right after the fix, so neither Backspace nor the chip can reach it now. */
    fun onTypedPastFix() { lastFix = null }

    /**
     * The word stopped composing with no commit: the keyboard hid, or the app or a tap took it away.
     * A send button does this. A correction the user took back and left standing is still refused, or
     * the same correction comes back next time. Nothing is learned, since anything else here may be
     * half a word, and a refusal is only about the exact spelling the correction replaced. A retype
     * where the fix was erased counts as it does at Space.
     */
    fun onComposingAbandoned(word: String) {
        val w = word.lowercase()
        // A send button can beat the bar's pass, which would otherwise have spotted the retype by now.
        startedAfter?.let { effectiveProvenance(w, it) }
        val fix = refusedFix
        val raw = refusedRaw
        val deliberate = explicit || provenance == Provenance.RESUMED
        val settled = fixSettled
        val reentered = reenteredFix
        val retyped = ringHit
        resetWord()
        if (fix == null || w != raw) return
        if (!deliberate) {
            if (w != fix) noteRetype(w, fix, settled, retyped)  // equal only for a fix that changed nothing but case
            return
        }
        countRefusal(reentered)
        if (settled) rollBackSettled(raw, fix)
        recordRefusal(w, raw, fix, true)
    }

    /** The user erased what the keyboard has learned. A pending fix must not teach anything after that. */
    fun onEverythingErased() {
        pending = null
        pendingRecent = null
        lastFix = null
        chipKept = null
        resetWord()
        memory.clear()
        memory.markPersisted()  // the store was emptied along with it
        volatileMemory.clear()
    }

    /** The correction stood: now it may teach. */
    fun settle() {
        val f = pending ?: return
        pending = null
        pendingRecent?.settled = true
        pendingRecent = null
        if (!learning.canLearn) return
        var p1 = f.prev1
        var p2 = f.prev2
        for (part in f.fix.split(' ')) {
            if (part.isBlank()) continue
            learning.learn(part, p1, p2, false)
            p2 = p1; p1 = part.lowercase()
        }
        if (' ' !in f.fix) {
            learning.noteTypo(f.raw)
            learning.tapSignal(f.raw, f.fix)
        }
    }

    fun onHide() {
        settle()
        lastFix = null
        if (memory.dirty && learning.canLearn) learning.persistRejections()
    }

    fun onFieldChanged() {
        settle()
        lastFix = null
        chipKept = null
        resetWord()
        memory.clearRecent()
        volatileMemory.clear()
    }

    /** True the first time this correction is refused. */
    private fun countRefusal(recent: RejectionMemory.Recent?): Boolean {
        if (recent == null || recent.refusalCounted) return false
        recent.refusalCounted = true
        learning.correctionRefused()
        return true
    }

    /**
     * The typo came back where its fix was erased, with no undo, and it stays as typed. A fix erased
     * before it stood was never accepted, so that is a refusal at once. One that stood means the person
     * had moved on, and a thumb habit can retype the typo after erasing for some other reason, so the
     * first time is only counted and bans nothing. False when it was only counted. What a fix taught goes
     * back once, however many times it is refused, because it only taught once.
     */
    private fun noteRetype(w: String, fix: String, settled: Boolean, retyped: RejectionMemory.Recent?): Boolean {
        if (countRefusal(retyped) && settled) rollBackSettled(w, fix)
        val onlyCounted = settled && active().softCount(w) == 0
        recordRefusal(w, w, fix.takeUnless { onlyCounted }, false)
        return !onlyCounted
    }

    private fun rollBackSettled(raw: String?, fix: String) {
        if (!learning.canLearn) return
        for (part in fix.split(' ')) if (part.isNotBlank()) learning.unlearn(part)
        raw?.let { learning.clearTypoVerdict(it) }
    }

    private fun recordRefusal(w: String, raw: String?, fix: String?, isExplicit: Boolean) {
        val m = active()
        if (raw != null && fix != null && raw != w) m.banPair(raw, fix)
        if (!isExplicit) {
            m.noteSoft(w, fix)
            if (learning.canLearn) {
                if (m.isProtected(w)) learning.clearTypoVerdict(w)
                learning.persistRejections()
            }
            return
        }
        m.noteKept(w, fix)
        if (learning.canLearn) {
            learning.clearTypoVerdict(w)
            learning.persistRejections()
        }
    }

    private fun isWordChar(c: Char?) = c != null && (c.isLetterOrDigit() || c == '\'' || c == '’' || c == '_')
}

/**
 * Where a word starts and stops while it is being typed. No Android here, so the service and the
 * JVM scenarios run this same code.
 */
object WordShape {
    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    /** An apostrophe right after a letter belongs to the word: "don't" is one word, not "don" and "t". */
    fun isConnector(text: CharSequence, composing: CharSequence): Boolean =
        text.length == 1 && isApostrophe(text[0]) && composing.isNotEmpty() && composing.last().isLetter()

    /** The word without a closing quote. "dogs'" stays in the field as typed, and counts as "dogs". */
    fun stem(word: String): String = word.trimEnd('\'', '’')

    /** The dictionary and the learned model spell apostrophes the straight way. */
    fun normalized(word: String): String = word.replace('’', '\'')

    /**
     * The end of [textBefore] that Backspace may put back into composing, or null when it must stay
     * committed text: only a whole word counts, so the fragment has to start at the field's start or
     * after whitespace. An opening quote is not part of the word, and it keeps the word out.
     */
    fun reentryFragment(textBefore: String): String? {
        val fragment = textBefore.takeLastWhile { it.isLetter() || isApostrophe(it) }.dropWhile(::isApostrophe)
        if (fragment.isEmpty()) return null
        val start = textBefore.length - fragment.length
        return fragment.takeIf { start == 0 || textBefore[start - 1].isWhitespace() }
    }

    /** What a word delete took out of [chunk], as the context trail spells it. */
    fun contextForm(chunk: String): String =
        normalized(stem(chunk.trimEnd { !it.isLetterOrDigit() && !isApostrophe(it) }))

    /** True when [after], the character after the caret, makes what is before it only part of a word. */
    fun continuesWord(after: Char?): Boolean =
        after != null && (after.isLetterOrDigit() || isApostrophe(after))
}
