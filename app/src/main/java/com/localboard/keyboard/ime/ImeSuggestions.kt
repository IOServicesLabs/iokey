package com.localboard.keyboard.ime

import android.app.AlertDialog
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.R
import com.localboard.keyboard.engine.PronounCase
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.correction.WordShape
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.BURST_DEBOUNCE_MS
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.BURST_GAP_MS
import com.localboard.keyboard.ui.KeyboardView
import com.localboard.keyboard.ui.PredictionBarView
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The suggestion bar: the prediction pass, picks, and word commits.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class SuggestionState {
    /** A bar that came up before the first load, waiting to ask again once it is over. */
    var refreshAfterFirstLoad: Job? = null
}

/** How often a word must have been typed to lead the bar over a correction hint. */
private const val LEARNED_OVER_HINT_MIN_USES = 2

/** The markers of the word as typed and of the way back from a correction, which are the person's own spelling. */
private const val AS_TYPED_SCORE = 0.0
private const val REVERT_CHIP_SCORE = -1.0

/**
 * A word on the bar as it should land, so that a chip reading i'm or i, which the keyboard made itself, commits a
 * capital. The raw word and the revert chip keep the person's own spelling, so the raw chip is still a way to commit a
 * lower case i. Everything on the bar passes through here, whichever source it came from.
 */
internal fun LLMKeyboardService.pronounCased(word: Suggestion): Suggestion {
    if (word.score == AS_TYPED_SCORE || word.score == REVERT_CHIP_SCORE) return word
    if (!PronounCase.mayCaseOffered(autocorrectState.fieldCorrection)) return word
    val cased = PronounCase.casedTokens(word.text)
    return if (cased == word.text) word else word.copy(text = cased)
}

internal fun LLMKeyboardService.onShowRefreshPredictions(info: EditorInfo, restarting: Boolean) {
    reseedContextFromField(info, restarting)
    refreshPredictions()
    refreshAfterFirstLoad()
}

/**
 * A show in a new process comes before the word tables are in, so that pass has nothing to offer for the words the
 * field already holds, and with no key pressed nothing would ask again. A glide waiting for its table is left alone:
 * it refreshes when it lands, and a refresh under it would look like a key pressed since its lift.
 */
private fun LLMKeyboardService.refreshAfterFirstLoad() {
    suggestionState.refreshAfterFirstLoad?.cancel()
    if (contextEngine.isInitialized) return
    suggestionState.refreshAfterFirstLoad = serviceScope.launch {
        contextEngine.awaitFirstLoad()
        if (isInputViewShown && glideState.holdJob?.isActive != true) refreshPredictions()
    }
}

/** P2: long-press a suggestion → confirm, then banish the word from the
 *  learned model (and from base predictions via suppression). */
internal fun LLMKeyboardService.confirmRemoveSuggestion(text: String) {
    val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
    val dialog = AlertDialog.Builder(ctx)
        .setMessage("Remove “$text” from suggestions?")
        .setPositiveButton("Remove") { _, _ ->
            contextEngine.removeWordEverywhere(text)
            correctionSession.onWordForgotten(text)
            refreshPredictions()
        }
        .setNegativeButton("Cancel", null)
        .create()
    showOverIme(dialog)
}

internal fun LLMKeyboardService.wirePredictionBar() {
    predictionBar.listener = object : PredictionBarView.Listener {
        override fun onSuggestionPicked(text: String) = acceptSuggestion(text)
        override fun onSuggestionLongPressed(text: String) = confirmRemoveSuggestion(text)
        override fun onClipboardChipTapped() = pasteClipboardChip()
        override fun onClipboardChipLongPressed() = showClipboardHistory()
        override fun onWifmRequested() = startWifm()
        override fun onWifmLongPressed() = showQuickPrompt()
        override fun onWifmConfirmed() = confirmWifm()
        override fun onWifmDiscarded() = discardWifm()
        override fun onVoiceRequested() = handleVoiceTap()
        override fun onVoiceStopTapped() = voiceInput?.stop() ?: Unit
    }
}

// ------------------------------------------------------------------
// Suggestions
// ------------------------------------------------------------------

internal fun LLMKeyboardService.refreshPredictions() {
    // CRASH FIX: the framework calls onUpdateSelection() — which lands
    // here — before onCreateInputView() has run, so these lateinits are
    // not yet assigned:
    //   UninitializedPropertyAccessException: lateinit property
    //   predictionBar has not been initialized
    // There is nothing to refresh before the views exist; the next real
    // input event repaints anyway.
    if (!predictionBarInitialized || !keyboardViewInitialized) return
    if (learningState.keptRun != null) settleKeptRun()
    if (!predictionsEnabled || showingEmoji ||
        predictionBar.currentMode() == PredictionBarView.Mode.WIFM_CONFIRM
    ) {
        if (!predictionsEnabled) predictionBar.showSuggestions(emptyList())
        updateClipboardChip()
        return
    }
    predictJob?.cancel()
    // A commit (composing empty) makes any pending AI run stale: the
    // context it was asked about has changed. A letter mid-word does
    // not — the run's answer is keyed to the committed-word context and
    // will serve as a completion of the word being typed. The cache is
    // never cleared here — it is keyed by the exact context snapshot and
    // can only display while that snapshot is still current.
    if (composing.isEmpty()) {
        aiSuggestJob?.cancel()
        aiSuggestToken++
    }
    // A fresh keystroke invalidates the previous precomputed verdict.
    autocorrectState.cachedVerdict = null
    predictionBar.pendingCorrection = false
    // ADAPTIVE DEBOUNCE. A flat one-frame delay means a fast typist starts
    // (and immediately cancels) a full correction walk on every keystroke —
    // the engine explores up to 120k trie nodes, and during a burst nobody
    // ever reads the intermediate results because the next key lands first.
    // Stretching the wait during a burst coalesces those into one useful
    // pass, and the moment typing pauses the next pass runs at 16 ms.
    //
    // Measured HERE, not inside the coroutine: the launch below is
    // cancelled by the next keystroke, often before its first line runs, so
    // a timestamp written in there would simply go missing and every gap
    // would look like a pause.
    val nowMs = SystemClock.uptimeMillis()
    val debounce = if (nowMs - lastKeystrokeAt < BURST_GAP_MS) BURST_DEBOUNCE_MS else 16L
    lastKeystrokeAt = nowMs
    predictJob = serviceScope.launch {
        delay(debounce)
        // A run the caret still touches is what a word landing next would follow.
        val run = learningState.keptRun?.takeIf { composing.isEmpty() }
        val prev1 = run ?: committedWords.lastOrNull() ?: ""
        val prev2 = (if (run != null) committedWords.lastOrNull() else committedWords.dropLast(1).lastOrNull()) ?: ""
        // Snapshot the AI next-word predictions are keyed to (last ~10 words).
        val ctxSnapshot = committedWords.takeLast(10).joinToString(" ")
        val raw = composing.toString()
        val capitalize = keyboardView.shiftState != KeyboardView.ShiftState.OFF &&
            composing.isEmpty()
        // Precompute the space-press autocorrect verdict NOW, in the
        // inter-keystroke lull — space then applies the cached verdict
        // instead of running the walk on the commit path (the last remaining
        // per-keystroke stall on big words). The verdict ALSO builds the bar
        // — the center slot shows what space will insert.
        //
        // Both of these are pure reads of the engine, and the correction
        // walk explores up to 120k trie nodes — far too much to sit on the
        // main thread between keystrokes. The engine's personal-model maps
        // are ConcurrentHashMap precisely so this can run off-main; only
        // the UI update below comes back to the main thread, and so does
        // what the user has refused, which only the main thread may read.
        //
        // No walk before the first load has finished: it is still changing
        // the trie, and the trie is a plain map.
        val ready = correctionReady()
        val (verdict, suggestions) = withContext(com.localboard.keyboard.util.TypingDispatcher.io) {
            val v = if (raw.isNotEmpty() && isActive && ready) stockVerdict(raw, prev1, prev2) else null
            val s = router.route(
                prefix = WordShape.normalized(raw),
                prev1 = prev1,
                prev2 = prev2,
                appPackage = currentAppPackage,
                capitalize = capitalize
            )
            v to s
        }
        val stillThisWord = composing.toString() == raw
        if (stillThisWord) autocorrectState.cachedVerdict = verdict
        val (fix, fixHint) =
            if (stillThisWord && verdict != null) allowedBySession(verdict, prev1) else (null to null)
        // Learned email completions, surfaced in email context only. The
        // predictionsEnabled early-return above already keeps these out
        // of password/sensitive fields.
        val emailSugs = if (isActive && (isEmailField || raw.contains('@'))) {
            withContext(com.localboard.keyboard.util.TypingDispatcher.io) {
                fastEngine.emailCompletions(raw, limit = 2)
            }
        } else emptyList()
        val revertWord = correctionSession.lastFix?.raw
        // While composing, the layout is Gboard-style — center = the
        // pending correction (or top completion), LEFT = always the raw
        // word as typed (one tap commits exactly it), right = next best.
        // Slot order in the view is [1, 0, 2] (left, center, right).
        val display: List<Suggestion> = if (raw.isNotEmpty()) {
            val pool = suggestions.filter { !it.text.equals(raw, ignoreCase = true) }
            val centerText = fix?.let { f ->
                if (raw.first().isUpperCase()) f.replaceFirstChar { it.uppercase() } else f
            }
            // Non-committed correction candidate ("id" -> "it"): the most
            // useful center chip when the auto-commit verdict was "leave
            // it alone" — a tap still applies it.
            val hintText = fixHint?.takeIf { !it.equals(raw, ignoreCase = true) }?.let { h ->
                if (raw.first().isUpperCase()) h.replaceFirstChar { it.uppercase() } else h
            }
            // The LLM's next-word guesses for the context this word
            // started in are still valid while it is being typed: one
            // that begins with what has been typed so far is the best
            // completion there is ("how are y" -> "you"). Free — no
            // model call, the words were computed after the last space.
            val aiCompletion = cachedAiWords?.takeIf { it.first == ctxSnapshot }?.second
                ?.firstOrNull { it.length > raw.length && it.startsWith(raw.lowercase()) }
                ?.let { w -> if (raw.first().isUpperCase()) w.replaceFirstChar { it.uppercase() } else w }
            // Going back into a correction offers the spelling it replaced.
            val refusedChip = correctionSession.refusedRaw?.takeIf { it != raw.lowercase() }?.let { r ->
                if (raw.first().isUpperCase()) r.replaceFirstChar { it.uppercase() } else r
            }
            // A word this user has already written, and that finishes what is typed, is a
            // better guess than a correction of it: the letters so far are not a mistake.
            // The hint stays one tap away. It only displaces a hint, so that context can
            // still lead an ordinary completion, and a fix Space would apply still leads.
            val typedLower = WordShape.normalized(raw).lowercase()
            val learnedOverHint = hintText?.let {
                pool.firstOrNull { s ->
                    val word = s.text.lowercase()
                    word.length > typedLower.length && word.startsWith(typedLower) &&
                        fastEngine.userFrequency(word) >= LEARNED_OVER_HINT_MIN_USES
                }
            }
            val center = centerText?.let { Suggestion(it, 1.0) }
                ?: emailSugs.firstOrNull()?.let { Suggestion(it, 1.0) }
                ?: refusedChip?.let { Suggestion(it, 1.0) }
                ?: learnedOverHint
                ?: hintText?.let { Suggestion(it, 1.0) }
                ?: aiCompletion?.let { Suggestion(it, 1.0) }
                ?: pool.firstOrNull()
            val right = aiCompletion?.takeIf { !it.equals(center?.text, ignoreCase = true) }
                ?.let { Suggestion(it, 1.0) }
                ?: hintText?.takeIf { center === learnedOverHint && !it.equals(center?.text, ignoreCase = true) }
                    ?.let { Suggestion(it, 1.0) }
                ?: pool.firstOrNull { it.text != center?.text }
            listOfNotNull(center, Suggestion(raw, 0.0), right)
        } else if (!typedThisField && revertWord == null) {
            // Nothing typed in this field yet — no starter-word guesses,
            // no email chips, no AI words. The bar stays empty until the
            // first keystroke. (revertWord implies an autocorrect, which
            // implies typing, so that chip is exempt.)
            emptyList()
        } else {
            // After an autocorrect, offer the word as typed for one-tap
            // revert (kept inside the 3-slot window — appending a 4th
            // silently dropped it whenever the route returned 3).
            val base = if (revertWord != null) {
                suggestions.take(2) + Suggestion(revertWord, -1.0)
            } else if (emailSugs.isNotEmpty()) {
                // Empty email field: lead with the user's learned
                // addresses, then the ordinary next-word predictions.
                (emailSugs.map { Suggestion(it, 1.0) } + suggestions)
                    .distinctBy { it.text }.take(3)
            } else suggestions
            // LLM next-word predictions for exactly this context: they
            // lead, the n-gram engine's best keeps a slot, then the
            // model's runner-up. The model reads the whole message; the
            // n-gram side carries the user's own two-word habits — the
            // interleave keeps both voices on the bar.
            val aiWords = cachedAiWords?.takeIf {
                it.first == ctxSnapshot && it.second.isNotEmpty()
            }?.second
            if (aiWords != null) {
                val cased = aiWords.map { w ->
                    Suggestion(if (capitalize) w.replaceFirstChar { it.uppercase() } else w, 3.0)
                }
                val merged = ArrayList<Suggestion>(4)
                merged += cased[0]
                base.firstOrNull { !it.text.equals(cased[0].text, ignoreCase = true) }?.let { merged += it }
                for (c in cased.drop(1)) if (merged.none { it.text.equals(c.text, ignoreCase = true) }) merged += c
                for (b in base) if (merged.none { it.text.equals(b.text, ignoreCase = true) }) merged += b
                merged.take(3)
            } else base
        }
        predictionBar.pendingCorrection =
            raw.isNotEmpty() && fix != null && composing.toString() == raw
        // Just after a glide the bar offers what comes next, with one way back in case the glide was wrong.
        val afterGlide = if (raw.isEmpty()) GlideRules.barAfterGlide(display, glideAlternates?.firstOrNull(), lastSwipeTop) else display
        predictionBar.showSuggestions(afterGlide.map { pronounCased(it) })
        updateClipboardChip()
    }
    // Opt-in AI next-word prediction: scheduled AFTER the normal pass so
    // the n-gram bar is up first and the model only refines it.
    // Self-gates on pref/model/power/context; no-op when mid-word.
    maybeLlmNextWords()
}

internal fun LLMKeyboardService.acceptSuggestion(text: String) {
    // Tapping the "as typed" revert chip undoes the last autocorrection.
    if (revertChipTapped(text)) return
    // GLIDE LEARNING: the bar is showing alternates from a gesture we
    // just decoded, and the user reached past our first choice. That is
    // the single most informative correction signal the decoder ever
    // gets — the geometry was ambiguous and this user resolves it THIS
    // way. Record it before the state is cleared below.
    noteSwipeCorrection(text)
    // Picking an alternate REPLACES the glide word that was just
    // committed (plus its auto-space) — it does not append a second
    // word after it. The wrong word also loses the count it was learned
    // with a moment ago.
    val alternates = glideAlternates
    glideAlternates = null
    if (alternates != null && alternates.any { it.text.equals(text, ignoreCase = true) }) {
        val pending = lastGestureCommit
        val ic = currentInputConnection
        if (pending != null && ic != null) {
            val (word, hadSpace) = pending
            val probe = word.length + 1
            val before = (ic.getTextBeforeCursor(probe, 0) ?: "").toString()
            val matches = if (hadSpace) before == "$word " else before.endsWith(word)
            if (matches) {
                beginSelfEdit()
                ic.deleteSurroundingText(if (hadSpace) word.length + 1 else word.length, 0)
                endSelfEdit()
                popWordContext(word)
                if (lastGestureWasGlide && prefs.swipeLearning && !incognito) swipeDecoder?.noteRejectedCommit(word)
                if (glideState.lastGestureLearned) {
                    unlearnIfRecent(word, committedWords.lastOrNull().orEmpty(), committedWords.dropLast(1).lastOrNull().orEmpty())
                }
            }
        }
        lastGestureCommit = null
    }
    if (looksLikeEmoji(text)) {
        // Emoji suggestion: just insert, don't touch the composing word.
        val ic = currentInputConnection ?: return
        finishComposingWord()
        beginSelfEdit()
        ic.commitText(text, 1)
        endSelfEdit()
        prefs.addRecentEmoji(text)
        refreshEmojiRow()
        refreshPredictions()
        return
    }
    commitWordFromSuggestion(text)
}

internal fun LLMKeyboardService.looksLikeEmoji(text: String): Boolean =
    text.any { it.isHighSurrogate() || it in '\u2600'..'\u27BF' }

/**
 * A word from the bar lands. The router has already cased it. [slidUp] is a slide from the keys onto the bar with
 * nothing composing, which can be a glide that overshot the top edge, so it teaches no more than a glide does.
 */
internal fun LLMKeyboardService.commitWordFromSuggestion(word: String, slidUp: Boolean = false) {
    val ic = currentInputConnection ?: return
    typedThisField = true
    val replacesComposing = composing.isNotEmpty()
    val addressField = isPartOfAddress("")
    val gluedToAddress = slidUp && addressField
    noteWordPicked(word, fromSwipe = slidUp)
    val glued = landsGluedInAddress(replacesComposing)
    val before = if (replacesComposing) null else ic.getTextBeforeCursor(1, 0)?.lastOrNull()
    // A word being replaced keeps its place. Anything else goes after what is there, so 'hello,' gives 'hello, how '.
    val lead = !replacesComposing && GlideRules.wantsLeadSpace(prefs.autoSpace, glued, false, before)
    val tail = if (replacesComposing) prefs.autoSpace
    else GlideRules.wantsTailSpace(prefs.autoSpace, glued, ic.getTextAfterCursor(1, 0)?.firstOrNull())
    // A space going in after . ! or ? opens a sentence, as it does for a glide. Auto-caps off means no capital nobody asked for.
    val landed = GlideRules.caseWord(word, GlideRules.Shift.OFF, addressField, lead && prefs.autoCaps && GlideRules.endsSentence(before))

    if (!replacesComposing) endKeptRunBeforeWord(spaced = lead)
    landWord(ic, landed, lead, tail)

    lastGestureCommit = landed to tail
    lastGestureWasGlide = false
    glideState.lastGestureLearned = !gluedToAddress
    autoSpacePending = tail
    // A prediction-bar tap is explicit "yes, this is my word" intent —
    // it counts heavily toward dictionary promotion. A slide is deliberate
    // too, but gesture errors exist, so it counts normally. A piece of an
    // address is not a word at all.
    if (!gluedToAddress) learnWord(landed, selected = !slidUp)
    // An explicit bar tap also confirms the word to the engine directly —
    // the strongest "the user meant exactly this" signal there is.
    if (!slidUp && !incognito && predictionsEnabled) contextEngine.noteConfirmed(word)
    if (prefs.autoSpace) updateAutoCaps()
    refreshPredictions()
}
