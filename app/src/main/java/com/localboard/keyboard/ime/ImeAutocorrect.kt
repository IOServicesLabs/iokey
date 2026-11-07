package com.localboard.keyboard.ime

import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.InputConnection
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.PronounCase
import com.localboard.keyboard.engine.correction.CommitLearning
import com.localboard.keyboard.engine.correction.CorrectionPolicy
import com.localboard.keyboard.engine.correction.CorrectionSession
import com.localboard.keyboard.engine.correction.EditorPort
import com.localboard.keyboard.engine.correction.FieldCorrection
import com.localboard.keyboard.engine.correction.LearningPort
import com.localboard.keyboard.engine.correction.Provenance
import com.localboard.keyboard.engine.correction.RejectionMemory
import com.localboard.keyboard.engine.correction.WordShape
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SENTENCE_MARK
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.WORD_SPLIT
import com.localboard.keyboard.util.LocalStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Autocorrect decisions, the space-press commit, and its undo.
 *
 * What the user has refused is decided in [CorrectionSession], which is plain
 * Kotlin so it can run on a JVM. This file does the input-connection calls and
 * the engine reads for it, and tells it what happened.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class AutocorrectState {
    var session: CorrectionSession? = null

    /** What the engine made of the composing word on the last prediction pass,
     *  so Space gets the same answer however fast it comes. Null whenever the
     *  word has changed since. */
    var cachedVerdict: StockVerdict? = null

    /** True once the first read of what the user refused in earlier runs has finished, however it went. */
    var rejectionsRestored = false

    /** True once the stored refusals have really been read. Nothing may replace that row before then. */
    var rejectionsRead = false

    /** A read of the stored refusals is on its way. */
    var rejectionsReading = false

    /** How far this field lets correction go. The prediction pass reads it off the main thread. */
    @Volatile
    var fieldCorrection = FieldCorrection.AUTO

    /** A typed word has just got its capital at Space or a full stop, and the next key can still take it back. */
    var capitalMade: CapitalMade? = null

    /** The word one Backspace just took the capital off, which stays as typed if it is finished unchanged. */
    var keepAsTypedOnce: String? = null

    /** A capital made at Space, put by while the letters typed straight after it can still be erased back to it. */
    var capitalSetAside: CapitalMade? = null

    /** [out] is what the field got for [raw], and [separator] is what followed it. [cameBack] says letters typed after it have been erased. */
    class CapitalMade(val raw: String, val out: String, val separator: String, val cameBack: Boolean = false)
}

/** The engine's answer for one composing word. [fix] may commit at Space; [hint]
 *  only ever goes on the bar. */
internal class StockVerdict(val raw: String, val fix: String?, val hint: String?)

internal val LLMKeyboardService.correctionSession: CorrectionSession
    get() = autocorrectState.session ?: startCorrectionSession()

private val LLMKeyboardService.correctionMayLearn: Boolean
    get() = predictionsEnabled && prefs.learningEnabled && !incognito

private fun LLMKeyboardService.startCorrectionSession(): CorrectionSession {
    val memory = RejectionMemory(now = System::currentTimeMillis)
    val session = CorrectionSession(
        correctionEditorPort(), correctionLearningPort(memory), memory,
        RejectionMemory(now = System::currentTimeMillis)
    )
    autocorrectState.session = session
    // Refusals are about the user, not one app, so erasing one app's data
    // leaves them. The engine outlives this service, and onDestroy cancels the
    // scope, so that is when the listener has to leave.
    val onErased = ContextEngine.EraseListener { packageName ->
        if (packageName == null) {
            session.onEverythingErased()
            // The stored row went too, so a failed read has nothing left to protect.
            autocorrectState.rejectionsRead = true
        }
    }
    contextEngine.addEraseListener(onErased)
    val onForgotten = ContextEngine.WordForgottenListener { word -> session.onWordForgotten(word) }
    contextEngine.addWordForgottenListener(onForgotten)
    serviceScope.coroutineContext[Job]?.invokeOnCompletion {
        contextEngine.removeEraseListener(onErased)
        contextEngine.removeWordForgottenListener(onForgotten)
    }
    readStoredRejections(memory)
    return session
}

/** Reads the stored refusals into [memory], then writes back what changed meanwhile. The store answers
 *  null both for no row and for a read that failed, so a null counts only from a store with no trouble.
 *  Otherwise the row stays as it is, and the next write tries the read again. */
private fun LLMKeyboardService.readStoredRejections(memory: RejectionMemory) {
    val state = autocorrectState
    if (state.rejectionsRead || state.rejectionsReading) return
    state.rejectionsReading = true
    serviceScope.launch {
        try {
            val row = contextEngine.styleStat(RejectionMemory.STYLE_STATS_KEY)
            if (row != null || contextEngine.storageProblem == null) {
                row?.let(memory::mergeStored)
                state.rejectionsRead = true
                if (memory.dirty) writeRejections(memory)
            }
        } finally {
            state.rejectionsReading = false
            state.rejectionsRestored = true
        }
    }
}

private fun LLMKeyboardService.writeRejections(memory: RejectionMemory) {
    contextEngine.putStyleStat(RejectionMemory.STYLE_STATS_KEY, memory.serialize())
    memory.markPersisted()
}

private fun LLMKeyboardService.correctionEditorPort(): EditorPort = object : EditorPort {
    private val ic get() = currentInputConnection
    override fun textBefore(n: Int): String = ic?.getTextBeforeCursor(n, 0)?.toString() ?: ""
    override fun setComposing(text: String) { ic?.setComposingText(text, 1) }
    override fun deleteBefore(n: Int) { ic?.deleteSurroundingText(n, 0) }
    override fun commit(text: String) { ic?.commitText(text, 1) }
    override fun batch(block: () -> Unit) {
        val connection = ic
        beginSelfEdit()
        connection?.beginBatchEdit()
        try {
            block()
        } finally {
            connection?.endBatchEdit()
            endSelfEdit()
        }
    }
}

private fun LLMKeyboardService.correctionLearningPort(memory: RejectionMemory): LearningPort = object : LearningPort {
    override val canLearn: Boolean get() = correctionMayLearn
    override fun pushContext(word: String) { pushWordContext(word) }
    override fun popContext(word: String) { popWordContext(word) }
    override fun swapContext(fix: String, raw: String) {
        val marked = committedWords.lastOrNull() == SENTENCE_MARK
        if (marked) committedWords.removeLast()
        popWordContext(fix)
        pushWordContext(raw)
        if (marked) committedWords.addLast(SENTENCE_MARK)
    }
    override fun learn(word: String, prev1: String, prev2: String, selected: Boolean) {
        val w = word.trim().lowercase()
        if (w.length < 2) return
        contextEngine.learnWord(w, prev1, prev2, currentAppPackage, selected)
        contextEngine.onTextCommitted(w, currentAppPackage, skipNgramLearn = true)
        // No context push, since the word went in when it was applied. No
        // lastLearnedWord either: going back into a fix that stood is the
        // session's business.
    }
    override fun unlearn(word: String) { unlearnIfRecent(word, "", "") }
    override fun unconfirm(word: String) { contextEngine.unconfirm(word) }
    override fun noteTypo(word: String) { contextEngine.noteTypo(word, currentAppPackage) }
    override fun clearTypoVerdict(word: String) { contextEngine.clearTypoVerdict(word) }
    override fun tapSignal(raw: String, fix: String) {}
    override fun persistRejections() {
        // Before the row has been read, memory just stays dirty and the read writes it.
        if (autocorrectState.rejectionsRead) writeRejections(memory) else readStoredRejections(memory)
    }
    override fun correctionRefused() { LocalStats.bump(LocalStats.Counter.CORRECTION_REVERTED) }
}

/** False until a correction walk is safe and a refusal from an earlier run can be
 *  honoured. The walk reads a trie the first load is still changing, and a
 *  correction fired before the refusals are back would repeat what was refused. */
internal fun LLMKeyboardService.correctionReady(): Boolean =
    contextEngine.isInitialized && autocorrectState.rejectionsRestored

/** The context trail, and the message being written, gain [word]. */
internal fun LLMKeyboardService.pushWordContext(word: String) {
    learningState.keptRun = null
    for (w in WORD_SPLIT.split(word)) {
        val clean = w.trim().lowercase()
        if (clean.isEmpty()) continue
        committedWords.addLast(clean)
        while (committedWords.size > 40) committedWords.removeFirst()
        messageWords.add(clean)
        draftState.typedSinceSeed = GlideRules.countAfterAdding(draftState.typedSinceSeed)
    }
    while (messageWords.size > 240) messageWords.removeAt(0)
}

/** Takes [word] back off the context trail and the message being written, when it
 *  is what they end with. */
internal fun LLMKeyboardService.popWordContext(word: String) {
    learningState.keptRun = null
    for (w in WORD_SPLIT.split(word).reversed()) {
        val clean = w.trim().lowercase()
        if (committedWords.lastOrNull() == clean) {
            committedWords.removeLast()
            draftState.typedSinceSeed = GlideRules.countAfterRemoving(draftState.typedSinceSeed)
        }
        if (messageWords.lastOrNull() == clean) messageWords.removeAt(messageWords.size - 1)
    }
}

/** The space-press autocorrect DECISION for [raw]: a pure read of the engine, with
 *  no editor mutation, no learning and no LLM. Runs inside the debounced
 *  prediction pass, off the main thread, and as a rare synchronous fallback on
 *  space, so the commit path itself seldom pays for the walk.
 *
 *  What the user has refused is not looked at here, because that may only be read
 *  on the main thread: [allowedBySession] applies it at the moment the answer is
 *  used. */
internal fun LLMKeyboardService.stockVerdict(raw: String, prev1: String, prev2: String): StockVerdict {
    if (!prefs.autoCorrect || autocorrectState.fieldCorrection == FieldCorrection.OFF) return StockVerdict(raw, null, null)
    val rawLower = WordShape.normalized(raw.lowercase())
    // The contraction hard map fires BEFORE the length/known-word
    // gates — "im" is 2 chars and "dont" is (wrongly) a dictionary word,
    // so the classic path could never reach them.
    var fix = FastEngine.contractionFixFor(rawLower, fastEngine.userFrequency(rawLower))
    if (raw.length < 2 && fix == null) return StockVerdict(raw, null, null)
    val firstCap = raw.first().isUpperCase()
    val tail = if (firstCap) raw.substring(1) else raw
    // Only fix plain lowercase words, in which an apostrophe is fine. Skip
    // inner capitals/digits (this also keeps assembled email tokens out of the
    // corrector entirely).
    if (!tail.all { it.isLowerCase() || it == '\'' || it == '’' }) return StockVerdict(raw, fix, null)
    var hint: String? = null
    if (fix == null) {
        // The level still gates how SHORT a word may be before we touch
        // it. Short (2-3 letter) and real dictionary words now flow into
        // the engine at balanced/bold — the context-override rule inside
        // correctionProbe only commits those on an overwhelming context
        // win, and otherwise hands its best candidate back as the hint.
        // Light stays hands-off below 4 letters, as before.
        val minLen = when (prefs.autoCorrectLevel) {
            1 -> 4      // light
            else -> 2   // balanced / bold
        }
        if (rawLower.length >= minLen) {
            // The committed words before this one resolve exact
            // geometric ties ("i wnat" -> want) — and, for known/short
            // words, decide whether a correction is justified at all.
            val probe = fastEngine.correctionProbe(rawLower, prev1, prev2, prefs.autoCorrectLevel)
            fix = probe.first?.word
            hint = probe.second
        }
        // A missed space bar is not an edit-distance error, so the walk
        // above can't see it: "thisis" is nowhere near a real word and
        // returns nothing, leaving one unknown super-word. Consulted whenever
        // the walk gave nothing to commit, even if it did hand back a hint.
        if (fix == null) {
            fix = fastEngine.splitRunOn(rawLower, prev1 = prev1, capitalized = firstCap)
                ?.let { (a, b) -> "$a $b" }
        }
    }
    if (fix == rawLower) return StockVerdict(raw, null, hint)
    return StockVerdict(raw, fix, hint)
}

/** What the session lets through of [verdict]: first the fix Space may commit, then
 *  what the bar may offer besides. A word typed straight through may change by
 *  itself. A word the user has been at, one ending in a closing quote, or one in a
 *  field that takes suggestions only, gets the answer as a chip at most. A protected
 *  word gets nothing. Main thread only. */
internal fun LLMKeyboardService.allowedBySession(verdict: StockVerdict, prev1: String): Pair<String?, String?> {
    val session = correctionSession
    val lower = verdict.raw.lowercase()
    val provenance = session.effectiveProvenance(lower, prev1)
    if (session.isProtected(lower)) return null to null
    val banned = session.bannedFixes(lower)
    val fix = verdict.fix?.takeIf { it.lowercase() !in banned }
    val hint = verdict.hint?.takeIf { it.lowercase() !in banned }
    if (provenance != Provenance.TYPED || WordShape.stem(verdict.raw) != verdict.raw ||
        autocorrectState.fieldCorrection != FieldCorrection.AUTO
    ) {
        return null to (fix ?: hint)
    }
    return fix to hint
}

/** Space-bar autocorrect: fix the in-flight word if it's a clear typo. The caller
 *  wraps this in one batch with the [separator] that follows, so the editor applies
 *  both at once and the cursor never visibly moves. */
internal fun LLMKeyboardService.maybeAutoCorrectComposing(separator: String) {
    val ic = currentInputConnection ?: return
    // Password/sensitive fields: no autocorrect, no typo signal, no LLM —
    // nothing here may ever see that text.
    if (!predictionsEnabled || !prefs.autoCorrect) return
    if (!correctionReady()) return
    val raw = composing.toString()
    if (raw.isEmpty()) return
    val prev1 = committedWords.lastOrNull() ?: ""
    val prev2 = committedWords.dropLast(1).lastOrNull() ?: ""
    // The verdict was precomputed by the debounced prediction pass; only the
    // rare miss (typed + slammed space within 16ms) computes it here.
    val stock = autocorrectState.cachedVerdict?.takeIf { it.raw == raw } ?: stockVerdict(raw, prev1, prev2)
    val fix = allowedBySession(stock, prev1).first ?: return
    // DRIFT CHECK FIRST: verify the editor still holds exactly what our
    // buffer claims BEFORE touching the composing region. Clearing and
    // finishing first committed a word that might not even be there and
    // silently dropped our copy of it.
    val before = (ic.getTextBeforeCursor(raw.length + 1, 0) ?: "").toString()
    if (!before.endsWith(raw, ignoreCase = true)) return
    val charBefore = before.getOrNull(before.length - raw.length - 1)
    val charAfter = ic.getTextAfterCursor(1, 0)?.firstOrNull()
    if (!CorrectionPolicy.boundaryOk(charBefore, charAfter)) return
    // A walk can land on a word in the table, as i'vr does on i've. This one is a real correction, so its undo gives the raw word.
    val out = PronounCase.casedTokens(if (raw.first().isUpperCase()) fix.replaceFirstChar { it.uppercase() } else fix)
    // Atomic in-place replace: commit the raw word, delete it, insert the
    // fix — no composing-region churn, no underline flicker.
    composing.clear()
    ic.finishComposingText()
    ic.deleteSurroundingText(raw.length, 0)
    ic.commitText(out, 1)
    correctionSession.onAutoCorrected(raw, out, separator, prev1, prev2)
    LocalStats.bump(LocalStats.Counter.CORRECTION_FIRED)
}

/**
 * What a typed [word] that is ending right now should read, or null to leave it as typed. A change of case and not a
 * correction, so the session never hears of it and it does not wait for the dictionary. The table comes first, so that
 * an ordinary word costs no pref read and no binder call. Then the same checks a correction makes of the field, because
 * the buffer may have drifted from it, and an address is kept by what stands either side of the word.
 */
internal fun LLMKeyboardService.typedPronounCase(ic: InputConnection, word: String): String? {
    val cased = PronounCase.cased(word) ?: return null
    if (!predictionsEnabled || !PronounCase.mayCaseTyped(prefs.autoCorrect, autocorrectState.fieldCorrection)) return null
    val before = (ic.getTextBeforeCursor(word.length + 1, 0) ?: "").toString()
    if (!before.endsWith(word, ignoreCase = true)) return null
    val charBefore = before.getOrNull(before.length - word.length - 1)
    val charAfter = ic.getTextAfterCursor(1, 0)?.firstOrNull()
    if (!CorrectionPolicy.boundaryOk(charBefore, charAfter)) return null
    return cased
}

/** Any key ends the way back to a capital, though the key itself may be what takes it. */
internal fun LLMKeyboardService.takeCapitalMade(): AutocorrectState.CapitalMade? =
    autocorrectState.capitalMade.also { autocorrectState.capitalMade = null }

internal fun LLMKeyboardService.forgetCapitalMade() {
    autocorrectState.capitalMade = null
    autocorrectState.capitalSetAside = null
    autocorrectState.keepAsTypedOnce = null
}

/**
 * Backspace has gone back over a letter of the word typed straight after 'I '. Someone who hit Space early and typed on
 * before noticing erases that word and then means the same as Backspace straight after the Space, or they would carry on
 * from the capital to 'Is'. So the record waits while letters are left, and with the last one gone it can be taken again.
 */
internal fun LLMKeyboardService.keepCapitalSetAside(made: AutocorrectState.CapitalMade?) {
    if (composing.isNotEmpty()) {
        autocorrectState.capitalSetAside = made
    } else if (made != null) {
        autocorrectState.capitalMade = AutocorrectState.CapitalMade(made.raw, made.out, made.separator, cameBack = true)
    }
}

/**
 * Backspace right after a capital made at Space takes the capital back, because someone who hit Space early on 'in' or
 * 'is' must be able to carry on from 'i', and an undone change must stay undone. So the word goes back into composing as
 * typed, and stays that way if it is finished unchanged. Emoji, drafts, voice and paste send no key through here, so the
 * real read is what notices them. False leaves Backspace to do its usual work.
 *
 * A record that came back through erased letters holds nothing, because a held Backspace can stop one repeat late on the
 * i, and that says nothing against its capital. Space there capitalises again, and a letter still carries on to 'is'.
 */
internal fun LLMKeyboardService.undoCapitalByBackspace(ic: InputConnection, made: AutocorrectState.CapitalMade): Boolean {
    if (made.separator != " ") return false
    val expected = made.out + " "
    if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return false
    beginSelfEdit()
    ic.beginBatchEdit()
    try {
        ic.deleteSurroundingText(1, 0)
        reenterWord(ic, made.raw)
    } finally {
        ic.endBatchEdit()
        endSelfEdit()
    }
    if (!made.cameBack) autocorrectState.keepAsTypedOnce = made.raw
    updateAutoCaps()
    refreshPredictions()
    return true
}

/**
 * A letter right after 'I.' was typed with nothing between, so that was never the end of a sentence, as in i.e., and the
 * capital goes back. The trail needs nothing, since it has held the word in lower case all along. Space or another mark
 * here leaves it, so 'i. ' stays 'I. '.
 */
internal fun LLMKeyboardService.undoCapitalBeforeALetter(ic: InputConnection, made: AutocorrectState.CapitalMade) {
    if (made.separator != ".") return
    val expected = made.out + "."
    if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return
    beginSelfEdit()
    ic.beginBatchEdit()
    try {
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(made.raw + ".", 1)
    } finally {
        ic.endBatchEdit()
        endSelfEdit()
    }
}

// LLM-assisted autocorrect used to live here: when the classic corrector
// found nothing, the quick model was asked to pick a replacement and the
// word was rewritten AFTER the space, asynchronously — text changing
// under the user a beat after they had moved on, with no revert cue, and
// the 0.6B model's pick was wrong often enough that the net effect was a
// keyboard that felt unpredictable. Removed outright: autocorrect is
// synchronous, geometric and n-gram-driven only, and a word the classic
// path will not touch is left exactly as typed.

/** Backspace right after an autocorrection restores the word as typed. Nothing was
 *  learned from the fix yet, so there is nothing to roll back. False when the
 *  correction is out of reach and Backspace should do its usual work. */
internal fun LLMKeyboardService.undoCorrectionByBackspace(): Boolean {
    val undone = correctionSession.tryUndo(byChip = false) ?: return false
    composing.clear()
    undone.composing?.let { composing.append(it) }
    // Restored text has no taps.
    composingTrace.clear()
    composingTraceValid = false
    updateAutoCaps()
    refreshPredictions()
    return true
}

/** The bar's revert chip. True when [text] was that chip, even if the correction
 *  could no longer be found: such a tap must do nothing, not commit the word. */
internal fun LLMKeyboardService.revertChipTapped(text: String): Boolean {
    val session = correctionSession
    val fix = session.lastFix ?: return false
    if (!text.equals(fix.raw, ignoreCase = true)) return false
    session.tryUndo(byChip = true)
    updateAutoCaps()
    refreshPredictions()
    return true
}

/** Tells the session a bar word or a glide is about to replace what is composing. */
internal fun LLMKeyboardService.noteWordPicked(word: String, fromSwipe: Boolean) {
    autocorrectState.capitalMade = null
    autocorrectState.capitalSetAside = null
    val session = correctionSession
    val rawBefore = composing.toString()
    val stock = autocorrectState.cachedVerdict?.takeIf { it.raw == rawBefore }
    val (fix, hint) =
        if (stock != null) allowedBySession(stock, committedWords.lastOrNull() ?: "") else (null to null)
    val refused = session.refusedRaw
    when {
        fromSwipe -> {
            finishComposingForGlide()
            session.onChipReplacedWord()
        }
        rawBefore.isNotEmpty() && word.equals(rawBefore, ignoreCase = true) ->
            session.onRawChipKept(rawBefore, fix ?: hint, fastEngine.isConfirmed(word))
        refused != null && word.equals(refused, ignoreCase = true) ->
            session.onRefusedRawChosen(fastEngine.isConfirmed(word))
        else -> {
            // Taking the pending correction from the bar is the same verdict
            // as letting Space apply it: the raw composing word is a typo.
            if (correctionMayLearn && rawBefore.isNotEmpty() && fix != null && fix.equals(word, ignoreCase = true)) {
                contextEngine.noteTypo(rawBefore, currentAppPackage)
            }
            if (rawBefore.isNotEmpty()) session.onFixPicked(rawBefore, word)
            session.onChipReplacedWord()
        }
    }
}

/** True when [text] belongs to an address and not to a sentence: the field takes one, or what has been
 *  typed has started to look like one. No space goes inside an address, and its pieces are not words. */
internal fun LLMKeyboardService.isPartOfAddress(text: CharSequence): Boolean {
    return isUrlField() || isEmailField || text.any { it == '@' || it == '.' || it == '/' || it == ':' }
}

private fun LLMKeyboardService.isUrlField(): Boolean {
    val inputType = currentInputEditorInfo?.inputType ?: 0
    return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
        (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_URI
}

/** How far back a URL field is read for what the run touching the caret holds. */
private const val ADDRESS_RUN_WINDOW = 128

/** Whether a word landing now goes with no space around it. In an email field it always does. In a URL field, which a
 *  browser's bar also uses for searches, only when it finishes what was [wasComposing] or follows something that reads
 *  as an address, so two words stay apart. Only a URL field is read, so ordinary typing pays nothing for this. */
internal fun LLMKeyboardService.landsGluedInAddress(wasComposing: Boolean): Boolean {
    if (isEmailField) return true
    if (!isUrlField()) return false
    return wasComposing || GlideRules.uriRunIsAddress(currentInputConnection?.getTextBeforeCursor(ADDRESS_RUN_WINDOW, 0))
}

/** A glide's word has decoded and is about to land, and it is a word of its own, so whatever is composing
 *  is finished as typed. That keeps a word an undo restored and records its refusal. It waits for this
 *  moment because the gesture may yet fall back to a tap, whose letter belongs in the word being typed. */
internal fun LLMKeyboardService.finishComposingForGlide() {
    if (composing.isEmpty()) return
    val address = isPartOfAddress(composing)
    finishComposingWord(beforeGlide = true, mayCapitalise = true)
    if (!address && prefs.autoSpace) {
        beginSelfEdit()
        currentInputConnection?.commitText(" ", 1)
        endSelfEdit()
    }
}

/** The word in composing has been committed as typed. The session knows how it got
 *  there, so it says how it counts. A glide can cut a word short or come right after a typo, and the
 *  user has not said the word is done, so then it counts only if the dictionary already has it. */
internal fun LLMKeyboardService.accountForCommittedWord(word: String, beforeGlide: Boolean = false) {
    val stem = WordShape.normalized(WordShape.stem(word))
    val counts = correctionSession.onWordCommitted(stem, committedWords.lastOrNull() ?: "")
    if (beforeGlide && isPartOfAddress(stem)) return
    when (counts) {
        CommitLearning.NORMAL ->
            if (beforeGlide && !(contextEngine.isInitialized && fastEngine.isKnownWord(stem))) pushWordContext(stem)
            else learnWord(stem)
        CommitLearning.SELECTED -> learnWord(stem, selected = true)
        CommitLearning.CONTEXT_ONLY -> pushWordContext(stem)
        CommitLearning.NOTHING -> addFinishedWordFromField()
    }
}

/** Backspace reached the end of a committed word, so [fragment] goes back into
 *  composing. The caller holds the batch edit. */
internal fun LLMKeyboardService.reenterWord(ic: InputConnection, fragment: String) {
    learningState.keptRun = null
    val popped = committedWords.lastOrNull()
        ?.takeIf { it.startsWith(WordShape.normalized(WordShape.stem(fragment.lowercase()))) }
    // Backspacing into the word we JUST learned (seconds ago) means it was a
    // typo being fixed — unlearn it before it re-enters composing, or the
    // correction loop re-teaches the mistake. Only that word: ordinary edits to
    // older/established words never unlearn.
    var unlearned = false
    val learned = lastLearnedWord
    if (!incognito && learned != null && learned == popped &&
        SystemClock.uptimeMillis() - lastLearnedAt < 10_000
    ) {
        lastLearnedWord = null
        // The word itself is still the trail's last entry here, and what it followed comes before it.
        unlearnIfRecent(
            learned,
            committedWords.getOrNull(committedWords.size - 2).orEmpty(),
            committedWords.getOrNull(committedWords.size - 3).orEmpty(),
        )
        unlearned = true
    }
    ic.deleteSurroundingText(fragment.length, 0)
    composing.append(fragment)
    // No trace for re-entered text: its taps are gone.
    composingTrace.clear()
    composingTraceValid = false
    ic.setComposingText(composing, 1)
    if (popped != null) {
        committedWords.removeLast()
        draftState.typedSinceSeed = GlideRules.countAfterRemoving(draftState.typedSinceSeed)
        if (messageWords.lastOrNull() == popped) messageWords.removeAt(messageWords.size - 1)
    }
    correctionSession.onFragmentReentered(fragment, committedWords.lastOrNull() ?: "", popped, unlearned)
}

// Editing events, called from ImeText. Empty where this file has nothing to do at
// that moment.
internal fun LLMKeyboardService.autocorrectOnExternalCursorMove() {
    if (selStart >= 0) forgetCapitalMade()
    val session = correctionSession
    // An editor that reports no offsets gives nothing to tell the echo of the
    // correction's own commit by, and reading it as the user moving the caret
    // would quietly end Backspace's undo.
    if (session.lastFix != null && selStart < 0) return
    session.onExternalCursorMove()
}

internal fun LLMKeyboardService.autocorrectOnWordStart() {
    // A word erased and started again is a fresh word, so it gets its capital.
    autocorrectState.keepAsTypedOnce = null
    val ic = currentInputConnection
    noteSelectionTypedOver()
    correctionSession.onWordStart(
        ic?.getTextBeforeCursor(1, 0)?.lastOrNull(),
        ic?.getTextAfterCursor(1, 0)?.firstOrNull(),
        committedWords.lastOrNull() ?: ""
    )
}

/** The letter on its way replaces any selection. The trail mirrors the field, and what goes can be
 *  a recent correction the user is taking out. Only a short one is read: no fix is long, and a
 *  selection can be the whole field, which is a lot to pull across a binder call. */
private fun LLMKeyboardService.noteSelectionTypedOver() {
    if (!predictionsEnabled || selStart == selEnd || kotlin.math.abs(selEnd - selStart) > 64) return
    val selected = currentInputConnection?.getSelectedText(0)?.toString() ?: return
    popWordContext(WordShape.contextForm(selected.trim()))
    correctionSession.onSelectionRemoved(selected)
}

internal fun LLMKeyboardService.autocorrectOnWordAboutToFinish(word: String) {}

internal fun LLMKeyboardService.autocorrectOnComposingDelete() {
    correctionSession.onComposingBackspace(composing.isEmpty())
}

internal fun LLMKeyboardService.autocorrectOnDeleteWithEmptyComposing() {}
