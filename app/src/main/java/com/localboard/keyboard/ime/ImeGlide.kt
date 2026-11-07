package com.localboard.keyboard.ime

import android.graphics.PointF
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.localboard.keyboard.engine.PronounCase
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.SwipeDecoder
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.AI_SWIPE_TIE_MARGIN
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SENTENCE_MARK
import com.localboard.keyboard.ui.KeyboardView
import com.localboard.keyboard.util.LocalStats
import com.localboard.keyboard.util.PhoneStats
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Glide typing: live preview, the decode at lift, and learned bias.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class GlideState {
    /** Strokes lifted before a decode could read them, oldest first. */
    val heldGlides = ArrayDeque<HeldGlide>()
    var holdJob: Job? = null

    /** The one alternate on the bar when this stroke began, because the stroke's start takes it off. */
    var alternateAtStrokeStart: List<Suggestion>? = null

    /** The event time the finger came onto the slot of the bar it is over. */
    var barSlotEnteredAt = 0L

    /** The stroke under way has put a word down, or is holding one to. Its end is told apart from a lost stroke by this. */
    var strokeHasWord = false

    /** Whether the last word a gesture landed was learned, so that taking it back knows if there is anything to give back. */
    var lastGestureLearned = false

    /** The bias as the decoder got it, with each word's stored value and day, and the text last read or written. */
    var biasLoaded: Map<String, GlideRules.BiasEntry> = emptyMap()
    var biasText: String? = null
}

/** A stroke kept until the table can read it. [atLift] is set for the one whose turn began at the lift. */
internal class HeldGlide(val points: List<PointF>, val dwellMask: Int?, val atLift: GlideRules.HoldSnapshot?)

/** How long a stroke waits for a table, and how often it looks. */
private const val GLIDE_HOLD_LIMIT_MS = 1_500L
private const val GLIDE_HOLD_POLL_MS = 50L

private const val GLIDE_LOST_NOTICE = "That glide didn't land. Try again."

private const val MS_PER_DAY = 86_400_000L

internal fun LLMKeyboardService.onShowPrepareSwipeDecoder(info: EditorInfo, restarting: Boolean) {
    applyPendingGlideReset()
    if (swipeDecoder == null && keyboardViewInitialized) {
        swipeDecoder = SwipeDecoder(fastEngine, keyboardView.geometry)
        restoreSwipeBias()
    }
    // A cold first glide can come before the keys have a width, and then only this can start its table.
    if (keyboardViewInitialized) keyboardView.onGeometryReady = { swipeDecoder?.warmUp() }
    // P1: pre-warm the swipe bucket table off-thread at show time (no-op
    // when the table is already fresh).
    swipeDecoder?.let { d ->
        d.doubleLetterEnabled = prefs.swipeDoubleLetter
        d.learningEnabled = prefs.swipeLearning
        d.warmUp()
    }
}

/**
 * Settings asked for glide learning to be forgotten. That screen cannot reach the decoder,
 * and the decoder's next save would write everything it holds straight back, so the copy in
 * memory goes here. The stored copy goes as well, because a save may have landed since the
 * request. With no decoder yet, clearing the stored copy leaves it nothing to load.
 */
private fun LLMKeyboardService.applyPendingGlideReset() {
    if (!prefs.swipeBiasResetPending) {
        // A restore in Settings does not reach the running decoder either, and its next save would overwrite what
        // was restored. What is stored differing from what this decoder last read or wrote is how that shows.
        if (swipeDecoder != null && prefs.swipeBiasData != glideState.biasText) {
            biasPersistJob?.cancel()
            restoreSwipeBias()
        }
        return
    }
    swipeDecoder?.restoreBias(emptyMap())
    prefs.swipeBiasData = ""
    prefs.swipeBiasResetPending = false
    glideState.biasLoaded = emptyMap()
    glideState.biasText = ""
}

/** The last gesture's words are over. The bar stops holding them, and a tap on the bar is no longer
 *  a correction of that gesture. */
internal fun LLMKeyboardService.forgetGlideAlternates() {
    glideAlternates = null
    glideState.alternateAtStrokeStart = null
    lastSwipeTop = null
    lastSwipeResults = null
}

/** Mid-glide finger-over-bar: light up the word under the finger so the
 *  user sees exactly what a lift would commit. */
internal fun LLMKeyboardService.handleSwipeBarHover(xFraction: Float) {
    if (!predictionBarInitialized) return
    // The words are about to be picked from, so a preview still on its way must not swap them.
    previewJob?.cancel()
    swipePreviewToken++
    val slot = predictionBar.slotForX(xFraction * predictionBar.width)
    val valid = if (slot >= 0 && predictionBar.suggestionInSlot(slot) != null) slot else -1
    if (valid != lastBarHoverSlot) {
        lastBarHoverSlot = valid
        glideState.barSlotEnteredAt = keyboardView.barTouchAt
        predictionBar.setGestureHighlight(valid)
        if (valid >= 0 && keyboardView.hapticEnabled) {
            keyboardView.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
    }
}

/** Finger lifted over the bar: commit that word. False = nothing under the
 *  finger (gap / empty slot) — caller falls back to decoding the path. */
internal fun LLMKeyboardService.handleSwipeBarPick(xFraction: Float): Boolean {
    if (!predictionBarInitialized) return false
    val slot = predictionBar.slotForX(xFraction * predictionBar.width)
    val picked = if (slot >= 0) predictionBar.suggestionInSlot(slot) else null
    // A finger still on the slot of its last report. One reaching a new slot only with the lift never stayed there.
    val stayed = slot == lastBarHoverSlot && GlideRules.stayedOnChip(glideState.barSlotEnteredAt, keyboardView.barTouchAt)
    predictionBar.setGestureHighlight(-1)
    lastBarHoverSlot = -1
    if (picked == null) return false
    // A slide-up takes whatever the slot holds, and it lands as any word on the bar does.
    val word = pronounCased(picked).text
    val kept = glideState.alternateAtStrokeStart
    val isKept = kept != null && kept.any { it.text.equals(word, ignoreCase = true) }
    // These two take a word back. A stroke that overshoots the top edge can end over either with no such wish, and would
    // lose the word it traced and teach against the one it took back, so with no stay the lift decodes the frozen path.
    // After a stay they teach as a tap on them does, or a deliberate undo would be corrected again next time.
    if (!stayed && (isKept || picked.text.equals(correctionSession.lastFix?.raw, ignoreCase = true))) return false
    glideState.strokeHasWord = true
    // The revert chip asks for the same thing however the finger reached it.
    if (revertChipTapped(picked.text)) return true
    // The one alternate left by the last glide does as a tap on it does: it replaces that glide's word, and nothing is added after it.
    if (isKept) {
        glideAlternates = kept
        lastSwipeResults = null
        acceptSuggestion(word)
        return true
    }
    // A preview leaves the bar alone while a word is being typed, so what is there was offered for that
    // word, and taking it replaces the word, as a tap there does.
    val replacesComposing = composing.isNotEmpty()
    lastSwipeResults = null
    commitWordFromSuggestion(word, slidUp = !replacesComposing)
    return true
}

/** The two words a stroke follows. With nothing composing, a run the caret still touches stands where a word composing would. */
private fun LLMKeyboardService.wordsBeforeGlide(): GlideRules.WordsBefore {
    val run = learningState.keptRun
    return GlideRules.wordsBefore(if (composing.isEmpty() && run != null) run else composing, committedWords)
}

/**
 * Whether this stroke's word would land with a space after a typed sentence end, which gives it a capital that Shift cannot
 * know of, so its preview has to say the same. The field is read only when the trail says a sentence has just ended.
 */
private fun LLMKeyboardService.strokeOpensASentence(): Boolean {
    if (composing.isNotEmpty() || !prefs.autoSpace || !prefs.autoCaps || committedWords.lastOrNull() != SENTENCE_MARK) return false
    return GlideRules.endsSentence(currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull())
}

/** Live glide preview: decode mid-swipe and stream candidates to the bar. */
internal fun LLMKeyboardService.handleSwipeProgress(points: List<PointF>) {
    if (!predictionsEnabled || !prefs.liveSwipePreview) return
    val decoder = swipeDecoder ?: return
    // The view dispatches at ~14 Hz; the decoder is two-stage and
    // costs a few milliseconds, so every dispatch can afford a decode.
    // Skip only when the finger barely moved since the last one (that's
    // the "hold at the end" dwell, and its result is already on the bar).
    val now = SystemClock.uptimeMillis()
    if (now - lastSwipeDecodeAt < 40) return
    val end = points.last()
    val keyW = keyboardView.geometry.averageKeyWidth
    lastSwipeDecodeEnd?.let { prev ->
        val dx = end.x - prev.x; val dy = end.y - prev.y
        if (kotlin.math.sqrt(dx * dx + dy * dy) < keyW * 0.3f &&
            points.size - lastSwipeDecodeCount < 4
        ) return
    }
    lastSwipeDecodeAt = now
    lastSwipeDecodeCount = points.size
    lastSwipeDecodeEnd = end
    // Off the main thread: this fires ~8 times during a single glide, and
    // its result only feeds the suggestion bar — nothing downstream needs
    // it synchronously (unlike the finger-up decode, which must decide
    // tap-fallback). The decoder is pure reads over a snapshot table.
    val snapshot = ArrayList(points)
    val before = wordsBeforeGlide()
    // The view's samples are for the main thread, and a decode of this stroke takes its answer along.
    val dwellMask = keyboardView.swipeDwellMask()
    val shift = keyboardView.shiftState
    val leadAfterSentenceEnd = strokeOpensASentence()
    val token = ++swipePreviewToken
    previewJob?.cancel()
    previewJob = serviceScope.launch {
        val results = withContext(com.localboard.keyboard.util.TypingDispatcher.glide) {
            decoder.decode(snapshot, 4, before.prev1, before.prev2, dwellMask)
        }
        // A newer preview (or the finger-up commit) has superseded us.
        if (token != swipePreviewToken || !isActive) return@launch
        if (results.isNotEmpty()) {
            val ranked = rerankSwipeWithAi(results)
            lastSwipeResults = ranked
            // With a word being typed, this stroke may be a slide up to what the bar offers for that word,
            // which has to be there when the finger arrives. The word over the fingertip is enough here.
            if (composing.isEmpty()) {
                val live = ranked.take(3).map { it.copy(text = caseForGlide(it.text, shift, leadAfterSentenceEnd)) }
                predictionBar.showSuggestions(
                    GlideRules.barDuringStroke(live, glideState.alternateAtStrokeStart?.firstOrNull(), correctionSession.lastFix?.raw)
                )
            }
            // Same word, floating above the fingertip.
            keyboardView.setGlideWord(caseForGlide(ranked.first().text, shift, leadAfterSentenceEnd))
        }
    }
}

/** How a decoded word is written here. Everything that shows or lands a glide's word comes through this. */
internal fun LLMKeyboardService.caseForGlide(
    word: String,
    shift: KeyboardView.ShiftState,
    leadAfterSentenceEnd: Boolean = false,
): String {
    val cased = GlideRules.caseWord(word, shift.forRules(), addressField = isPartOfAddress(""), leadAfterSentenceEnd)
    // Whatever already took a capital is not in the table, so Shift and caps lock stand.
    return if (PronounCase.mayCaseOffered(autocorrectState.fieldCorrection)) PronounCase.cased(cased) ?: cased else cased
}

private fun KeyboardView.ShiftState.forRules(): GlideRules.Shift = when (this) {
    KeyboardView.ShiftState.OFF -> GlideRules.Shift.OFF
    KeyboardView.ShiftState.ON -> GlideRules.Shift.ON
    KeyboardView.ShiftState.CAPS_LOCK -> GlideRules.Shift.CAPS_LOCK
}

/** A word lands with its spaces in one edit, so that no app sees it half in place. A word composing is replaced. */
internal fun LLMKeyboardService.landWord(ic: InputConnection, word: String, lead: Boolean, tail: Boolean) {
    beginSelfEdit()
    ic.beginBatchEdit()
    try {
        if (composing.isNotEmpty()) ic.setComposingText(word, 1) else ic.commitText(if (lead) " $word" else word, 1)
        composing.clear()
        ic.finishComposingText()
        if (tail) ic.commitText(" ", 1)
    } finally {
        ic.endBatchEdit()
        endSelfEdit()
    }
}

/**
 * A glide's word lands the way a typed word would: capitalised and spaced for where it goes. [alternate] is the one way
 * back the bar then offers. Shift is read first, because finishing what is composing can change what the check behind
 * it would say.
 */
internal fun LLMKeyboardService.commitGlideWord(word: String, alternate: Suggestion?) {
    val ic = currentInputConnection ?: return
    val shift = keyboardView.shiftState
    typedThisField = true
    val notAWord = isPartOfAddress(composing)
    // Read before the word is picked, because that finishes what is composing.
    val wasComposing = composing.isNotEmpty()
    noteWordPicked(word, fromSwipe = true)
    val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
    val after = ic.getTextAfterCursor(1, 0)?.firstOrNull()
    val glued = landsGluedInAddress(wasComposing)
    val lead = GlideRules.wantsLeadSpace(prefs.autoSpace, glued, wasComposing && notAWord, before)
    val tail = GlideRules.wantsTailSpace(prefs.autoSpace, glued, after)
    // Auto-caps off means no capital the person did not ask for, wherever this lands.
    val leadAfterSentenceEnd = lead && prefs.autoCaps && GlideRules.endsSentence(before)
    val cased = caseForGlide(word, shift, leadAfterSentenceEnd)
    glideAlternates = alternate?.let { listOf(it.copy(text = caseForGlide(it.text, shift, leadAfterSentenceEnd))) }

    endKeptRunBeforeWord(spaced = lead)
    landWord(ic, cased, lead, tail)

    lastGestureCommit = cased to tail
    lastGestureWasGlide = true
    glideState.lastGestureLearned = !notAWord
    autoSpacePending = tail
    // A piece of an address is not a word at all, and whatever is in an address field may be a piece of one.
    if (!notAWord) learnWord(cased)
    LocalStats.bump(LocalStats.Counter.GLIDE_COMMITTED)
    // The next glide reads Shift as well. Caps lock holds until the person ends it. With auto-caps off nothing else
    // would ever turn off a Shift tapped for this one word.
    if (shift != KeyboardView.ShiftState.CAPS_LOCK) {
        if (prefs.autoCaps) updateAutoCaps() else keyboardView.shiftState = KeyboardView.ShiftState.OFF
    }
    refreshPredictions()
}

/**
 * Let the LLM's next-word predictions (when present for the current
 * context) break near-ties among glide candidates. A gesture that scores
 * "here" 0.8 above "hete" is a geometry call; one that scores "there"
 * 0.8 above "three" after "see you" is a language call, and the model
 * that read the whole message is the better judge of that. Only
 * candidates within [AI_SWIPE_TIE_MARGIN] of the top are eligible, so the
 * model can never override a clear shape.
 */
internal fun LLMKeyboardService.rerankSwipeWithAi(results: List<Suggestion>): List<Suggestion> {
    // With a word still composing, the model's guesses are for that word's place, not the glide's.
    if (results.size < 2 || composing.isNotEmpty()) return results
    val snapshot = committedWords.takeLast(10).joinToString(" ")
    val ai = cachedAiWords?.takeIf { it.first == snapshot }?.second ?: return results
    if (ai.isEmpty()) return results
    val top = results[0]
    if (ai.any { it.equals(top.text, ignoreCase = true) }) return results
    val promoted = results.drop(1).firstOrNull { cand ->
        top.score - cand.score <= AI_SWIPE_TIE_MARGIN &&
            ai.any { it.equals(cand.text, ignoreCase = true) }
    } ?: return results
    return listOf(promoted) + results.filter { it !== promoted }
}

/** Returns true when the path decoded or is being held; false lets the view fall back to a tap. */
internal fun LLMKeyboardService.handleSwipe(points: List<PointF>): Boolean {
    if (!predictionsEnabled) return false
    // The lift decides from here on, so a preview still on its way must not paint the bar afterwards.
    swipePreviewToken++
    previewJob?.cancel()
    lastSwipeResults = null
    val longPath = GlideRules.isLongPath(pathLength(points), keyboardView.width)
    val decoder = swipeDecoder
    if (decoder == null || currentInputConnection == null) return glideLandedNothing(longPath)
    val holding = glideState.holdJob?.isActive == true
    if (holding || !decoder.tableServes) {
        // A short stroke that cannot be read yet may only be a slip on a key, so it stays a tap. Behind a stroke
        // already held there is no tap to fall back to: the letter would land ahead of the word before it.
        if (!longPath && !holding) return false
        holdGlide(decoder, points)
        glideState.strokeHasWord = true
        return true
    }
    val before = wordsBeforeGlide()
    // Finger-up decode stays synchronous: its boolean return decides
    // whether the view falls back to treating the gesture as a tap, so it
    // cannot be deferred. It is now allocation-free, so the cost is a few
    // ms rather than the ~1M-object churn that made glide feel laggy.
    val startedAt = SystemClock.uptimeMillis()
    val decoded = decoder.decode(points, 4, before.prev1, before.prev2, keyboardView.swipeDwellMask())
    PhoneStats.noteLiftDecode(SystemClock.uptimeMillis() - startedAt)
    val results = rerankSwipeWithAi(decoded)
    // Nothing composing has been finished or learned yet, so a tap falling back from here still joins that word.
    if (results.isEmpty()) return glideLandedNothing(longPath)
    glideState.strokeHasWord = true
    landDecodedGlide(results)
    return true
}

/**
 * A stroke is over. One that put no word down changes nothing: its start took the alternate off the bar and its preview
 * repainted the words there, so both go back, and the left chip still replaces the glide before it. A tap falling back
 * from the stroke has refreshed already, and a refresh under a held word would look like a key pressed since its lift.
 */
internal fun LLMKeyboardService.settleBarAfterStroke() {
    val state = glideState
    val hasWord = state.strokeHasWord
    state.strokeHasWord = false
    if (!hasWord && state.holdJob?.isActive != true) putBarBackAfterLostStroke()
}

private fun LLMKeyboardService.putBarBackAfterLostStroke() {
    glideState.alternateAtStrokeStart?.let { glideAlternates = it }
    if (composing.isEmpty() && isInputViewShown) refreshPredictions()
}

private fun pathLength(points: List<PointF>): Float {
    var length = 0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        length += kotlin.math.sqrt(dx * dx + dy * dy)
    }
    return length
}

/** A long stroke must never go silently, and the view swallows one rather than type a stray letter. */
private fun LLMKeyboardService.glideLandedNothing(longPath: Boolean): Boolean {
    if (!longPath) return false
    showNotice(GLIDE_LOST_NOTICE)
    return true
}

private fun LLMKeyboardService.landDecodedGlide(results: List<Suggestion>) {
    lastSwipeTop = results.first().text
    lastSwipeResults = results
    commitGlideWord(results.first().text, results.getOrNull(1))
}

private fun LLMKeyboardService.holdSnapshot(): GlideRules.HoldSnapshot {
    val before = wordsBeforeGlide()
    val info = currentInputEditorInfo
    return GlideRules.HoldSnapshot(
        before.prev1,
        before.prev2,
        keyboardView.shiftState.forRules(),
        lastKeystrokeAt,
        info?.packageName.orEmpty(),
        info?.fieldId ?: 0,
        composing.toString(),
    )
}

/**
 * A stroke lifted before its table is ready waits here for one, rather than being lost. The first stroke's turn begins
 * at its lift, so a key tapped during the wait is seen and does not end up as 'w hello '. One behind it waits for the word
 * ahead of it to land, or that word's own landing would fail its check.
 */
private fun LLMKeyboardService.holdGlide(decoder: SwipeDecoder, points: List<PointF>) {
    val state = glideState
    val holding = state.holdJob?.isActive == true
    state.heldGlides.addLast(HeldGlide(ArrayList(points), keyboardView.swipeDwellMask(), if (holding) null else holdSnapshot()))
    if (!holding) state.holdJob = serviceScope.launch { runGlideHold(decoder) }
}

private suspend fun LLMKeyboardService.runGlideHold(decoder: SwipeDecoder) {
    val state = glideState
    val since = SystemClock.uptimeMillis()
    while (!decoder.tableServes) {
        if (SystemClock.uptimeMillis() - since >= GLIDE_HOLD_LIMIT_MS) {
            state.heldGlides.clear()
            holdLostItsWord()
            return
        }
        // Nothing else asks for a table while the first load is still on its way.
        decoder.warmUp()
        delay(GLIDE_HOLD_POLL_MS)
    }
    while (true) {
        val glide = state.heldGlides.removeFirstOrNull() ?: return
        val atTurn = glide.atLift ?: holdSnapshot()
        val decoded = withContext(com.localboard.keyboard.util.TypingDispatcher.glide) {
            decoder.decode(glide.points, 4, atTurn.prev1, atTurn.prev2, glide.dwellMask)
        }
        // A key, a Backspace or another field since this stroke's turn began, and the word has no place to go.
        // What is behind it was aimed after it, so that goes too.
        if (!GlideRules.holdMayCommit(atTurn, holdSnapshot(), isInputViewShown)) {
            state.heldGlides.clear()
            holdLostItsWord()
            return
        }
        val results = rerankSwipeWithAi(decoded)
        if (results.isEmpty() || currentInputConnection == null) {
            holdLostItsWord()
        } else {
            landDecodedGlide(results)
        }
    }
}

/** Every refresh waits while a word is held, so with none landing the bar would keep its pre-load words until the next key. */
private fun LLMKeyboardService.holdLostItsWord() {
    showNotice(GLIDE_LOST_NOTICE)
    putBarBackAfterLostStroke()
}

/** The keyboard went down with strokes still held. They were aimed at a place that is going away, and the person can see why. */
internal fun LLMKeyboardService.cancelGlideHold() {
    glideState.holdJob?.cancel()
    glideState.heldGlides.clear()
}

/**
 * Feed a rejected-top glide back to the decoder. Only the one alternate the bar kept counts: every other word there
 * is a next word, and tapping one of those corrects nothing, whatever the decoder had as its third guess.
 */
internal fun LLMKeyboardService.noteSwipeCorrection(picked: String) {
    if (!prefs.swipeLearning || incognito) return
    val top = lastSwipeTop ?: return
    val alternate = glideAlternates?.firstOrNull() ?: return
    if (picked.equals(top, ignoreCase = true)) return
    if (!alternate.text.equals(picked, ignoreCase = true)) return
    swipeDecoder?.noteChosenAlternate(picked)
    lastSwipeTop = null
    persistSwipeBias()
}

/** Debounced: a burst of corrections writes once. */
internal fun LLMKeyboardService.persistSwipeBias() {
    biasPersistJob?.cancel()
    biasPersistJob = serviceScope.launch {
        delay(4_000)
        val snap = swipeDecoder?.biasSnapshot() ?: return@launch
        val state = glideState
        // Something else wrote here since, so a restore in Settings wins over what this decoder holds.
        if (prefs.swipeBiasData != state.biasText) {
            restoreSwipeBias()
            return@launch
        }
        val entries = GlideRules.biasToStore(snap, state.biasLoaded, System.currentTimeMillis() / MS_PER_DAY)
        val text = GlideRules.formatBias(entries)
        prefs.swipeBiasData = text
        state.biasLoaded = entries
        state.biasText = text
    }
}

internal fun LLMKeyboardService.restoreSwipeBias() {
    val d = swipeDecoder ?: return
    val stored = prefs.swipeBiasData
    val loaded = GlideRules.loadBias(stored, System.currentTimeMillis() / MS_PER_DAY)
    d.restoreBias(loaded.mapValues { it.value.worth })
    glideState.biasLoaded = loaded
    glideState.biasText = stored
}
