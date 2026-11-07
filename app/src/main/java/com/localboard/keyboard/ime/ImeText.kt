package com.localboard.keyboard.ime

import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Intent
import android.graphics.PointF
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.R
import com.localboard.keyboard.engine.PronounCase
import com.localboard.keyboard.engine.QuietCheck
import com.localboard.keyboard.engine.SelectionReport
import com.localboard.keyboard.engine.correction.FieldCorrection
import com.localboard.keyboard.engine.correction.FieldPolicy
import com.localboard.keyboard.engine.correction.WordShape
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.AUTOCORRECT_PUNCT
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.QUESTION_OPENERS
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SENTENCE_END
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SENTENCE_MARK
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SPACE_SWAP_PUNCT
import com.localboard.keyboard.ui.KeyType
import com.localboard.keyboard.ui.KeyboardView
import kotlinx.coroutines.cancel

/**
 * The text pipeline: key input, composing, delete, the caret, voice results.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class TextState {
    /** The word composing when the editor's region went away, so the later look can tell if it is still that word. */
    var regionGoneWord = ""
}

/**
 * A real snapshot — an immutable COPY, not the live list.
 *
 * CRASH FIX: this used to hand out `composingTrace` itself. The correction
 * pass read it while the main thread kept appending tap points as the user
 * typed, so `trace.size` could grow past the word it was matched against
 * and `w[i]` walked off the end:
 *   StringIndexOutOfBoundsException: length=8; index=8
 *      at FastEngine.spatialCorrect
 * That was 9 of the 14 crashes on the device. Copying is cheap (a word is
 * a handful of points) and makes the race structurally impossible.
 *
 * Nothing reads it yet, because today's correction decision takes no trace.
 * The trace is maintained per-word so that a decision which does can be handed
 * THIS copy, never the live list. An apostrophe's point is NaN: skip non-finite
 * points.
 */
internal fun LLMKeyboardService.composingTraceSnapshot(): List<android.graphics.PointF>? {
    if (!composingTraceValid || composing.isEmpty()) return null
    if (composingTrace.size != composing.length) return null
    return ArrayList(composingTrace)
}

internal fun LLMKeyboardService.beginSelfEdit() {
    selfEditHandler.removeCallbacks(selfEditReset)
    selfEditing = true
}

internal fun LLMKeyboardService.endSelfEdit() {
    selfEditHandler.postDelayed(selfEditReset, 150)
}

internal fun LLMKeyboardService.onShowResetFieldState(info: EditorInfo, restarting: Boolean, emptyOnRestart: Boolean) {
    onShowCheckStore()
    forgetCapitalMade()
    // A correction still pending from the previous field is judged under that
    // field's flags, so this comes before they change below.
    val session = correctionSession
    if (!restarting) session.settle()
    // The word still composing goes away below. An app clearing its field by setText restarts input with
    // no hide and no selection update, so a refusal left standing there is only heard of here.
    if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
    // Only an empty field: a formatter calling setText, or a rotation, leaves the correction where
    // Backspace can still take it back.
    if (emptyOnRestart) session.onExternalCursorMove()
    // The same rule for the words of the last glide, which the bar would otherwise show in an empty field.
    if (!restarting || emptyOnRestart) forgetGlideAlternates()
    predictionsEnabled = when (info.inputType and InputType.TYPE_MASK_VARIATION) {
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> false
        else -> (info.inputType and InputType.TYPE_MASK_CLASS) ==
            InputType.TYPE_CLASS_TEXT || info.inputType == InputType.TYPE_NULL
    }
    isEmailField = when (info.inputType and InputType.TYPE_MASK_VARIATION) {
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
        else -> false
    }
    autocorrectState.fieldCorrection = FieldPolicy.classify(info.inputType)

    // INCOGNITO. Signal, DuckDuckGo, browser private tabs and others set
    // IME_FLAG_NO_PERSONALIZED_LEARNING to say "type here, but remember
    // nothing". We were ignoring it outright — a privacy-first keyboard
    // that quietly learns from Signal is exactly backwards.
    //
    // Predictions still work (they only read); everything that WRITES is
    // suppressed for the session: dictionary learning, the encrypted
    // typing log, per-app vocabulary, glide bias and clipboard capture.
    //
    // A per-app "never learn my words here" rule means exactly the same
    // thing as the incognito flag, so it folds into the same switch and
    // inherits every write gate rather than needing its own set.
    val editorPkg = info.packageName ?: ""
    val wasIncognito = incognito
    incognito = (info.imeOptions and
        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
        !prefs.mayLearnIn(editorPkg)
    // A private field's words stayed in memory for its own next word. An app can lift the flag across a restart,
    // and from there on the trail teaches and the message gets logged.
    if (wasIncognito && !incognito) {
        committedWords.clear()
        messageWords.clear()
        draftState.typedSinceSeed = 0
    }
    if (!restarting) {
        com.localboard.keyboard.util.PhoneStats.noteField(if (incognito) "" else editorPkg, info.inputType, info.imeOptions)
    }
    aiAllowedHere = prefs.mayUseAiIn(editorPkg)
    com.localboard.keyboard.context.ScreenBuffer.noScreenPackages = prefs.noScreenApps

    // Fields that ask for numbers get the Gboard-style number pad.
    numberMode = when (info.inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    // Seed the caret from the editor's own report; onUpdateSelection keeps
    // it current from here on. Only on a new connection: hide then tap, and a
    // configuration change, deliver the first show's offsets again, old by now.
    if (trackedEditor.connectionIsNew) {
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
    }

    currentAppPackage = info.packageName ?: ""
    // P0: publish the editor-hint thread key so ContextEngine readers
    // (voice examples, recent-typed fallback) can bucket per
    // conversation even when the screen stream is blind. (The
    // framework updates currentInputEditorInfo before this callback.)
    contextEngine.setHintThreadKey(hintThreadKey())
    // A configuration restart does not close the connection, so the region stays, and the next
    // letter's setComposingText would replace what was typed. Back then tap has no such problem:
    // the framework finishes the region as the view goes down.
    if (restarting && composing.isNotEmpty()) currentInputConnection?.finishComposingText()
    composing.clear()
    composingTrace.clear()
    composingTraceValid = true
    // A mere restart (rotation, resize) keeps the sentence context — the
    // user is still mid-thought in the same field.
    if (!restarting) committedWords.clear()
    if (!restarting) session.onFieldChanged()
    if (!restarting) typedThisField = false
    showingEmoji = false
    showingSelection = false
    selectionAnchor = -1
    symbolsPage = 0
    trackedEditor.connectionIsNew = false
}

internal fun LLMKeyboardService.onShowApplyAutoCaps(info: EditorInfo, restarting: Boolean) {
    if (prefs.autoCaps) {
        keyboardView.shiftState = KeyboardView.ShiftState.ON
    } else {
        keyboardView.shiftState = KeyboardView.ShiftState.OFF
    }
}

// ------------------------------------------------------------------
// Wiring
// ------------------------------------------------------------------

internal fun LLMKeyboardService.wireKeyboard() {
    keyboardView.listener = object : KeyboardView.KeyboardListener {
        override fun onText(text: String) {
            endDraftForTyping()
            handleText(text)
            com.localboard.keyboard.util.PhoneStats.noteCommitted()
        }
        override fun onKey(type: KeyType) {
            // Backspace acts on touch-down, ahead of the hook that clears a notice for other keys.
            if (type == KeyType.DELETE) dismissNotice()
            if (type.changesText()) endDraftForTyping()
            handleSpecialKey(type)
            if (type == KeyType.DELETE) com.localboard.keyboard.util.PhoneStats.noteCommitted()
        }
        override fun onSwipePath(points: List<PointF>) = handleSwipe(points)
        override fun onSwipeProgress(points: List<PointF>) = handleSwipeProgress(points)
        override fun onSwipeStarted() {
            endDraftForTyping()
            // A glide is the one thing on this keyboard that genuinely
            // benefits from a high refresh rate — the trail follows the
            // finger. Everything else is static, so the rate goes back
            // down the moment the gesture ends.
            applyRefreshRatePolicy(gliding = true)
            // New gesture: drop what the last one left, so that an old decode can't be taken for this one's.
            // A slide up can still be aiming at the alternate the bar is showing, so that is kept to one side.
            glideState.alternateAtStrokeStart = glideAlternates
            glideAlternates = null
            lastSwipeResults = null
            lastSwipeDecodeEnd = null
            lastSwipeDecodeCount = 0
            lastSwipeDecodeAt = 0L
            lastBarHoverSlot = -1
        }
        override fun onSwipeBarHover(xFraction: Float) = handleSwipeBarHover(xFraction)
        override fun onSwipeBarPick(xFraction: Float) = handleSwipeBarPick(xFraction)
        override fun onSwipeEnded() {
            // A path too short to decode, or a cancelled touch, comes here and not through the lift's decode.
            // A preview landing after this would repaint the bar for a gesture that is over.
            swipePreviewToken++
            previewJob?.cancel()
            applyRefreshRatePolicy(gliding = false)
            com.localboard.keyboard.util.FrameStats.gestures++
            // Gesture over: any bar highlight is stale.
            if (lastBarHoverSlot >= 0) {
                lastBarHoverSlot = -1
                if (predictionBarInitialized) predictionBar.setGestureHighlight(-1)
            }
            settleBarAfterStroke()
        }
        override fun onWordDelete() {
            endDraftForTyping()
            deleteWordBackward()
        }
        override fun onSpaceLongPress() = showImePicker()
        override fun onEnterLongPress() {
            val ic = currentInputConnection ?: return
            finishComposingWord(mayCapitalise = true)
            val info = currentInputEditorInfo
            val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE
            // Deliberately ignores enterShouldAct(): holding Enter IS the
            // explicit "do the action" gesture, so the newline-preferring
            // rules that apply to a tap must not veto it here.
            if (action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                flushMessageLog("enter-long-press")
                ic.performEditorAction(action)
            } else {
                // No action to perform: fall back to a real Enter key so
                // holding it still does something in fields that listen
                // for the key event rather than declaring an action.
                ic.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                )
                ic.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                )
            }
        }
        override fun onSpaceCursorMove(delta: Int) = scrubCursor(delta)
    }
}

/** Letters, space and punctuation come through onText. Shift, the page keys, emoji and the
 *  cursor pad leave the text alone, and with it any draft in the bar. */
private fun KeyType.changesText(): Boolean =
    this == KeyType.DELETE || this == KeyType.ENTER || this == KeyType.CUT || this == KeyType.PASTE

/** P2: long-press space — the system IME switcher. */
internal fun LLMKeyboardService.showImePicker() {
    try {
        (getSystemService(INPUT_METHOD_SERVICE) as
            android.view.inputmethod.InputMethodManager).showInputMethodPicker()
    } catch (_: Throwable) { }
}

/** P2: space-bar slide — move the editor cursor [delta] chars. DPAD
 *  events are the only cursor control every editor honors (raw
 *  setSelection breaks on apps that track their own selection). */
internal fun LLMKeyboardService.scrubCursor(delta: Int) {
    currentInputConnection ?: return
    val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
    repeat(kotlin.math.abs(delta).coerceAtMost(24)) { sendDownUpKeyEvents(code) }
}

/** Mic button visibility: engine shipped in this build + user enabled. */
internal fun LLMKeyboardService.refreshVoiceVisibility() {
    if (!predictionBarInitialized) return
    predictionBar.setVoiceVisible(
        com.localboard.keyboard.llm.VoiceBridge.available && prefs.voiceTypingEnabled
    )
}

internal fun LLMKeyboardService.handleVoiceTap() {
    if (!com.localboard.keyboard.llm.VoiceBridge.available) return
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        showNotice("Microphone access is needed for voice typing")
        startActivity(
            Intent(this, com.localboard.keyboard.settings.VoicePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    if (!voiceModels.isReady) {
        showNotice("Download the voice model first — Settings → Voice typing", long = true)
        return
    }
    if (voiceInput == null) {
        voiceInput = VoiceInputManager(
            serviceScope,
            com.localboard.keyboard.llm.VoiceBridge,
            { voiceModels.modelFile.absolutePath },
            onState = { st -> keyboardView.post { onVoiceState(st) } },
            onResult = { text -> keyboardView.post { onVoiceResult(text) } }
        )
    }
    voiceInput?.start()
}

internal fun LLMKeyboardService.onVoiceState(st: VoiceInputManager.State) {
    if (!predictionBarInitialized) return
    when (st) {
        VoiceInputManager.State.RECORDING ->
            predictionBar.setVoiceState(true, "Listening… tap ■ to stop")
        VoiceInputManager.State.TRANSCRIBING ->
            predictionBar.setVoiceState(false, "Transcribing…")
        VoiceInputManager.State.IDLE ->
            predictionBar.setVoiceState(false, null)
    }
}

/**
 * whisper-tiny emits bare lowercase words with no terminal punctuation,
 * so dictation lands as "are you free tomorrow" mid-paragraph. This does
 * the two things a person would do by hand: capitalise the opening word
 * when starting a sentence, and close with the right mark.
 *
 * Deliberately minimal. Anything cleverer (comma placement, clause
 * splitting) needs a model, and a wrong comma is more annoying than a
 * missing one.
 */
internal fun LLMKeyboardService.punctuateVoice(text: String, before: String): String {
    if (text.isEmpty()) return text
    var out = text.trim()
    if (out.isEmpty()) return text

    // Sentence start = nothing before, or the previous char ends a
    // sentence. Only then is capitalising correct; mid-sentence dictation
    // ("...and then " + "we left") must stay lowercase.
    val prev = before.trimEnd().lastOrNull()
    val atSentenceStart = prev == null || prev in ".!?\n"
    if (atSentenceStart && out[0].isLowerCase()) {
        out = out[0].uppercaseChar() + out.substring(1)
    }

    if (out.last() !in ".!?,;:") {
        val firstWord = out.substringBefore(' ').lowercase()
        val question = firstWord in QUESTION_OPENERS
        out += if (question) "?" else "."
    }
    return out
}

internal fun LLMKeyboardService.onVoiceResult(text: String) {
    val t = text.trim()
    // Whisper emits bracketed noise markers for silence/music — no text.
    if (t.isEmpty() || (t.startsWith("[") && t.endsWith("]")) ||
        (t.startsWith("(") && t.endsWith(")") && t.length < 24)
    ) {
        showNotice("Didn't catch that — try again")
        return
    }
    val ic = currentInputConnection ?: return
    val before = (ic.getTextBeforeCursor(1, 0) ?: "").toString()
    val punctuated = if (prefs.voicePunctuation) punctuateVoice(t, before) else t
    val spaced =
        if (before.isNotEmpty() && !before[0].isWhitespace()) " $punctuated" else punctuated
    beginSelfEdit()
    ic.commitText(spaced, 1)
    endSelfEdit()
    recordWords(punctuated)
    updateAutoCaps()
    refreshPredictions()
}

/**
 * Should Enter fire the field's editor action, or type a newline?
 *
 * The old rule was "fire it whenever one is declared", which is why Enter
 * dismissed the keyboard in note and chat composers instead of starting a
 * new line. Two things were missing:
 *
 *  IME_FLAG_NO_ENTER_ACTION is the framework's explicit "Enter must NOT
 *  perform the action here" and was never checked. TextView sets it
 *  automatically on every multi-line field, so honouring it fixes the
 *  common case by itself.
 *
 *  IME_ACTION_DONE is not a submit. It means "close the keyboard", which
 *  is precisely the reported symptom, and a field that accepts multiple
 *  lines wants a newline from Enter far more than it wants dismissal.
 *  Real submits — GO, SEARCH, SEND, NEXT, PREVIOUS — still fire, because
 *  in a chat composer Enter-to-send is the whole point.
 *
 * Long-pressing Enter performs the action regardless, so submitting is
 * always reachable even where Enter now types a newline.
 */
internal fun LLMKeyboardService.enterShouldAct(info: EditorInfo?, action: Int): Boolean {
    if (action == EditorInfo.IME_ACTION_NONE ||
        action == EditorInfo.IME_ACTION_UNSPECIFIED
    ) return false
    if (info == null) return true
    if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
    val multiline = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    if (multiline && action == EditorInfo.IME_ACTION_DONE) return false
    return true
}

// ------------------------------------------------------------------
// Text input pipeline
// ------------------------------------------------------------------

internal fun LLMKeyboardService.handleText(text: String) {
    val capitalMade = takeCapitalMade()
    val ic = currentInputConnection ?: return
    // A separator kills the pending/shown AI suggestion outright. A
    // LETTER does not: the LLM's next-word guesses are keyed to the
    // committed-word context, which typing a letter leaves unchanged,
    // and they go on serving as completions of the word being typed.
    val isLetter = text.length == 1 && text[0].isLetter()
    // An apostrophe after a letter stays in the word: "don't" is one word,
    // not "don" followed by "t".
    val isConnector = WordShape.isConnector(text, composing)
    if (!isLetter && !isConnector) {
        cancelAiSuggestion()
        autocorrectState.capitalSetAside = null
    }
    forgetGlideAlternates()

    if (isLetter || isConnector) {
        typedThisField = true
        lastGestureCommit = null
        autoSpacePending = false
        // Consume the tap point the view stashed for this keystroke.
        // The trace stays index-aligned with `composing`; any letter that
        // arrives WITHOUT a point (voice, popup pick, paste) invalidates
        // the whole trace — a partial trace is worse than none. An apostrophe
        // gets a NaN point instead: it says nothing about where a letter was
        // aimed, and the letters around it are still good evidence.
        val tapPt = if (isConnector) PointF(Float.NaN, Float.NaN) else keyboardView.lastCharTapPoint
        keyboardView.lastCharTapPoint = null
        // A NEW word starts here, so the previous word's trace state has
        // no bearing on it. Resetting at the word boundary is what makes
        // the invalid flag per-word instead of per-session.
        //
        // It was per-session, and that was the bug: every path that clears
        // `composing` without clearing `composingTrace` — accepting an
        // autocorrect, tapping a suggestion, committing a glide — left a
        // stale trace behind, the length check below then failed on the
        // next letter, and composingTraceValid went false. Nothing set it
        // back to true except onStartInputView. So the FIRST correction of
        // a session permanently disabled joint spatial decoding, and every
        // word after it was corrected by edit distance alone, with no idea
        // where the finger had actually landed.
        if (composing.isEmpty()) {
            composingTrace.clear()
            composingTraceValid = true
            capitalMade?.let { undoCapitalBeforeALetter(ic, it) }
            autocorrectState.capitalSetAside = capitalMade?.takeIf { it.separator == " " }
            onWordStart()
        }
        // Extend the composing word.
        beginSelfEdit()
        composing.append(text)
        if (composingTraceValid && tapPt != null &&
            composingTrace.size == composing.length - 1
        ) {
            composingTrace.add(tapPt)
        } else {
            composingTraceValid = false
            composingTrace.clear()
        }
        ic.setComposingText(composing, 1)
        endSelfEdit()
        if (keyboardView.shiftState == KeyboardView.ShiftState.ON) {
            keyboardView.shiftState = KeyboardView.ShiftState.OFF
        }
        refreshPredictions()
    } else if (isEmailContinuation(text)) {
        // EMAIL ADDRESS ASSEMBLY. In an email field an address would
        // otherwise be committed as fragments at every '.' and '@'
        // ("john", "doe", "gmail", "com") — never learnable as the one
        // token the user actually retypes. Here those characters extend
        // the composing word, so the full address commits (and learns)
        // whole, and completions can finish the domain mid-token.
        // No tap trace for these: they arrive from the symbols page and
        // the trace is letters-only.
        composingTraceValid = false
        composingTrace.clear()
        beginSelfEdit()
        composing.append(text)
        ic.setComposingText(composing, 1)
        endSelfEdit()
        refreshPredictions()
    } else {
        // Separator / punctuation / space / enter: finish the current word.
        // With no word, this goes in right after the last correction, which
        // neither Backspace nor the chip can reach from here on.
        if (composing.isEmpty()) correctionSession.onTypedPastFix()
        if (text == " ") {
            // A manual space right after an automatic one: absorb it
            // rather than leave two spaces, but arm the double-tap
            // period so a second quick tap still ends the sentence.
            if (autoSpacePending && composing.isEmpty() && autoSpaceIsBeforeCursor(ic)) {
                autoSpacePending = false
                lastSpaceCommitAt = SystemClock.uptimeMillis()
                lastGestureCommit = null
                updateAutoCaps()
                refreshPredictions()
                return
            }
            // Double-tap space → period + space (Gboard behavior).
            val now = SystemClock.uptimeMillis()
            if (prefs.doubleSpacePeriod && composing.isEmpty() &&
                now - lastSpaceCommitAt in 1..450
            ) {
                val before = (ic.getTextBeforeCursor(2, 0) ?: "").toString()
                if (before.length == 2 && before[1] == ' ' &&
                    (before[0].isLetterOrDigit() || ",;:!?)]}\"'".contains(before[0]))
                ) {
                    lastSpaceCommitAt = 0L
                    beginSelfEdit()
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    endSelfEdit()
                    maybeLearnSentence(".")
                    updateAutoCaps()
                    refreshPredictions()
                    return
                }
            }
            lastSpaceCommitAt = now
        }
        // Punctuation right after a glide's auto-space: the space moves
        // to AFTER the mark ("hello, " not "hello ,"). This is most of
        // what makes glide typing feel finished rather than fiddly.
        val swapSpace = text.length == 1 && text[0] in SPACE_SWAP_PUNCT &&
            autoSpacePending && composing.isEmpty() && autoSpaceIsBeforeCursor(ic)
        autoSpacePending = false
        beginSelfEdit()
        ic.beginBatchEdit()
        try {
            // Autocorrect + word commit + the space itself in ONE batch:
            // the editor applies them atomically, so the cursor never
            // dances and no intermediate underlined state is visible.
            // Word-separating punctuation ends a word exactly like space
            // does, so "teh," is corrected before the comma lands.
            // A quote does not correct, and an apostrophe after a letter
            // never gets here: it stays in the word. An address, a search
            // term or a name is not a typo, so the field has its say first.
            if (autocorrectState.fieldCorrection == FieldCorrection.AUTO &&
                (text == " " || (text.length == 1 && text[0] in AUTOCORRECT_PUNCT))
            ) {
                maybeAutoCorrectComposing(text)
            }
            // When the corrector fired nothing is composing, so the capital adds nothing to a real fix.
            val cased = finishComposingWord(mayCapitalise = PronounCase.capitalisesAt(text))
            lastGestureCommit = null
            if (swapSpace) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(text, 1)
                if (prefs.autoSpace) {
                    ic.commitText(" ", 1)
                    autoSpacePending = true
                }
            } else {
                ic.commitText(text, 1)
                if (cased != null && (text == " " || text == ".")) {
                    autocorrectState.capitalMade = AutocorrectState.CapitalMade(PronounCase.lowered(cased), cased, text)
                }
            }
        } finally {
            ic.endBatchEdit()
            endSelfEdit()
        }
        if (text == "\n" || text == " " || SENTENCE_END.contains(text)) {
            maybeLearnSentence(text)
        }
        updateAutoCaps()
        refreshPredictions()
    }
}

/** True when [text] should EXTEND the composing token as part of an email
 *  address instead of committing the word: an address character in an
 *  email field, or anything after a '@' already in the composing token
 *  (which only gets there from an email field). A second '@' is never
 *  part of one address, so it falls through to the separator path. */
internal fun LLMKeyboardService.isEmailContinuation(text: String): Boolean {
    if (text.length != 1 || composing.isEmpty()) return false
    val c = text[0]
    if (c != '@' && c != '.' && c != '_' && c != '-' && !c.isDigit()) return false
    if (!isEmailField && !composing.contains('@')) return false
    if (c == '@' && composing.contains('@')) return false
    return true
}

internal fun LLMKeyboardService.handleSpecialKey(type: KeyType) {
    val capitalMade = takeCapitalMade()
    val capitalSetAside = autocorrectState.capitalSetAside
    autocorrectState.capitalSetAside = null
    val ic = currentInputConnection ?: return
    // Same rule as handleText: any key event invalidates the AI words.
    cancelAiSuggestion()
    forgetGlideAlternates()
    when (type) {
        KeyType.DELETE -> {
            if (deleteSelectionIfAny(ic)) return
            if (composing.isEmpty()) onDeleteWithEmptyComposing()
            if (composing.isEmpty() && undoSnippetIfPending()) return
            if (composing.isEmpty() && undoCorrectionByBackspace()) return
            if (composing.isEmpty() && capitalMade != null && undoCapitalByBackspace(ic, capitalMade)) return
            beginSelfEdit()
            if (composing.isNotEmpty()) {
                composing.deleteCharAt(composing.length - 1)
                if (composingTrace.isNotEmpty()) composingTrace.removeAt(composingTrace.size - 1)
                // setComposingText even when now empty: it replaces the
                // field's composing region, so the LAST letter deletes on
                // a single press. finishComposingText() alone would COMMIT
                // that letter instead — that was the double-backspace bug.
                ic.setComposingText(composing, 1)
                if (composing.isEmpty()) ic.finishComposingText()
                onComposingDelete()
                keepCapitalSetAside(capitalSetAside)
            } else if (wipeGestureCommitIfPending(ic)) {
                // Whole wrong swipe/prediction word removed in one go.
            } else {
                // Delete whole emoji graphemes (surrogate pairs, VS16,
                // ZWJ sequences, keycaps, flags) instead of half a pair.
                // One binder read serves both the grapheme math and the
                // fragment re-entry below: the tail after deletion is just
                // this string minus the deleted units.
                val before = (ic.getTextBeforeCursor(48, 0) ?: "").toString()
                val units = deleteUnitsFor(before)
                // Backspacing into a plain-typed word re-enters composing
                // on the remaining fragment, so predictions follow the
                // edit ("worl" -> "world") instead of guessing blindly
                // from the stale last word. Text right after the caret means
                // this is only part of a word, so it stays where it is.
                val fragment = WordShape.reentryFragment(before.dropLast(units))
                    ?.takeUnless { WordShape.continuesWord(ic.getTextAfterCursor(1, 0)?.firstOrNull()) }
                // One batch, so the editor never reports the word gone and
                // back again.
                ic.beginBatchEdit()
                try {
                    ic.deleteSurroundingText(units, 0)
                    // Deleting the full stop or the line break reopens the sentence:
                    // drop the boundary marker so the previous word steers again.
                    // Not while another one stands in front of it, as in "fine.\n".
                    if (units == 1 && before.isNotEmpty() && endsSentence(before.last()) &&
                        before.dropLast(1).trimEnd(' ').lastOrNull()?.let(::endsSentence) != true &&
                        committedWords.lastOrNull() == SENTENCE_MARK
                    ) {
                        committedWords.removeLast()
                    }
                    if (fragment != null) {
                        reenterWord(ic, fragment)
                        // A word of the capital's table still in lower case here was left that way, and one more Space
                        // and Backspace must not bring back a capital already taken back. A new word clears this.
                        if (PronounCase.cased(fragment) != null) autocorrectState.keepAsTypedOnce = fragment
                    }
                } finally {
                    ic.endBatchEdit()
                }
            }
            endSelfEdit()
            updateAutoCaps()
            refreshPredictions()
        }
        KeyType.SHIFT -> {
            val now = SystemClock.uptimeMillis()
            val doubleTap = now - lastShiftTapMs < 450
            lastShiftTapMs = now
            keyboardView.shiftState = when {
                keyboardView.shiftState == KeyboardView.ShiftState.ON && doubleTap ->
                    KeyboardView.ShiftState.CAPS_LOCK
                keyboardView.shiftState == KeyboardView.ShiftState.OFF ->
                    KeyboardView.ShiftState.ON
                else -> KeyboardView.ShiftState.OFF
            }
        }
        KeyType.ENTER -> {
            if (composing.isEmpty()) correctionSession.onTypedPastFix()
            // A change of case, unlike a correction, belongs here too: 'so do i' then Send must go out right.
            finishComposingWord(mayCapitalise = true)
            val info = currentInputEditorInfo
            val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE
            if (enterShouldAct(info, action)) {
                // SEND/GO/DONE: the message leaves — log it whole. (B3)
                flushMessageLog("editor-action")
                ic.performEditorAction(action)
            } else {
                beginSelfEdit()
                ic.commitText("\n", 1)
                endSelfEdit()
                // A newline inside a MULTI-LINE field continues the same
                // message; in a single-line field Enter ends it. (B3)
                val multiline = info != null &&
                    (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
                if (!multiline) flushMessageLog("single-line-enter")
            }
            maybeLearnSentence("\n")
            updateAutoCaps()
            refreshPredictions()
        }
        KeyType.SPACE -> {
            handleText(" ")
            // Gboard convention: space from a symbols page returns to
            // letters — you almost always want a word next.
            if (symbolsPage > 0) {
                symbolsPage = 0
                applyKeyboardModel()
            }
        }
        KeyType.MODE_SYMBOLS -> { symbolsPage = 1; applyKeyboardModel() }
        KeyType.MODE_SYMBOLS2 -> { symbolsPage = 2; applyKeyboardModel() }
        KeyType.MODE_LETTERS -> {
            symbolsPage = 0
            numberMode = false
            showingSelection = false
            selectionAnchor = -1
            applyKeyboardModel()
        }
        KeyType.EMOJI -> {
            showingEmoji = !showingEmoji
            applyKeyboardModel()
            refreshEmojiRow()
        }
        KeyType.MODE_SELECT -> {
            finishComposingWord()
            showingSelection = true
            selectionAnchor = -1
            applyKeyboardModel()
        }
        KeyType.CURSOR_LEFT -> moveCursor(-1)
        KeyType.CURSOR_RIGHT -> moveCursor(1)
        KeyType.CURSOR_UP -> sendCursorKey(android.view.KeyEvent.KEYCODE_DPAD_UP)
        KeyType.CURSOR_DOWN -> sendCursorKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        KeyType.CURSOR_HOME -> sendCursorKey(android.view.KeyEvent.KEYCODE_MOVE_HOME)
        KeyType.CURSOR_END -> sendCursorKey(android.view.KeyEvent.KEYCODE_MOVE_END)
        KeyType.SELECT_TOGGLE -> toggleSelectionAnchor()
        KeyType.SELECT_ALL -> {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }
        KeyType.CUT -> currentInputConnection?.performContextMenuAction(android.R.id.cut)
        KeyType.COPY -> currentInputConnection?.performContextMenuAction(android.R.id.copy)
        KeyType.PASTE -> currentInputConnection?.performContextMenuAction(android.R.id.paste)
        else -> Unit
    }
}

// ---------------------------------------------------------------
// Cursor / selection pad
//
// Selection is expressed by moving the caret while holding an anchor,
// because InputConnection has no "extend selection" primitive: we read
// where the caret is, then set a region from the anchor to the new spot.
// ---------------------------------------------------------------

/**
 * Where the caret is, from the offsets the framework already pushes to us.
 *
 * The obvious implementation — getTextBeforeCursor(HUGE) and take its
 * length — pulls the entire field across a binder transaction on every
 * arrow key, and throws TransactionTooLargeException on a long document.
 * onUpdateSelection hands us the same number for free.
 */
internal fun LLMKeyboardService.caretPos(): Int = if (selStart >= 0) selStart else -1

internal fun LLMKeyboardService.toggleSelectionAnchor() {
    selectionAnchor = if (selectionAnchor >= 0) -1 else caretPos()
    keyboardView.invalidate()
}

internal fun LLMKeyboardService.moveCursor(delta: Int) {
    val ic = currentInputConnection ?: return
    val pos = caretPos()
    if (pos < 0) {
        // No text access (some password/webview fields): fall back to the
        // raw key event, which still moves the caret even when we cannot
        // read where it is.
        sendCursorKey(
            if (delta < 0) android.view.KeyEvent.KEYCODE_DPAD_LEFT
            else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        )
        return
    }
    val target = (pos + delta).coerceAtLeast(0)
    if (selectionAnchor >= 0) ic.setSelection(selectionAnchor, target)
    else ic.setSelection(target, target)
    // Advance our own copy immediately. onUpdateSelection confirms it a
    // few milliseconds later, but repeated arrow taps arrive faster than
    // that round trip and would otherwise all move from the same offset.
    selStart = target
    selEnd = if (selectionAnchor >= 0) selectionAnchor else target
    // The tracker knows this setSelection for its own, so its echo stops there. The caret
    // has still moved somewhere new, and the bar, undo and the word before it must follow.
    applyExternalChange()
}

internal fun LLMKeyboardService.sendCursorKey(keyCode: Int) {
    val ic = currentInputConnection ?: return
    // Shift held = extend the selection, which is what every text widget
    // already implements for arrow keys.
    val meta = if (selectionAnchor >= 0) android.view.KeyEvent.META_SHIFT_ON else 0
    val now = android.os.SystemClock.uptimeMillis()
    ic.sendKeyEvent(
        android.view.KeyEvent(
            now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta
        )
    )
    ic.sendKeyEvent(
        android.view.KeyEvent(
            now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0, meta
        )
    )
}

/** UTF-16 units to delete so an emoji grapheme dies whole: surrogate
 *  pairs, variation selectors, ZWJ sequences, keycaps, RI flags. */
internal fun LLMKeyboardService.deleteUnitsFor(before: String): Int {
    if (before.isEmpty()) return 1
    fun Char.isVS() = this == '\uFE0E' || this == '\uFE0F'
    fun isRI(hi: Char, lo: Char) = hi == '\uD83C' && lo in '\uDDE6'..'\uDDFF'
    val end = before.length
    var count = when {
        end >= 2 && before[end - 1].isLowSurrogate() &&
            before[end - 2].isHighSurrogate() -> {
            if (end >= 4 && isRI(before[end - 4], before[end - 3]) &&
                isRI(before[end - 2], before[end - 1])
            ) 4 else 2
        }
        before[end - 1] == '\u20E3' && end >= 3 -> 3 // keycap: base + VS16 + U+20E3
        before[end - 1].isVS() -> {
            if (end >= 3 && before[end - 2].isLowSurrogate() &&
                before[end - 3].isHighSurrogate()
            ) 3 else 2
        }
        else -> 1
    }
    // ZWJ sequences: eat the joiner and the previous cluster too.
    var rest = end - count
    while (rest >= 1 && before[rest - 1] == '\u200D') {
        count += 1 + deleteUnitsFor(before.substring(0, rest - 1))
        rest = end - count
    }
    return count
}

/** True when the character before the cursor is the space an automatic
 *  insertion left there. Cheap, and the only safe way to honour
 *  [autoSpacePending]: the app may have moved the cursor or edited the
 *  field without telling us. */
internal fun LLMKeyboardService.autoSpaceIsBeforeCursor(ic: android.view.inputmethod.InputConnection): Boolean {
    val before = (ic.getTextBeforeCursor(2, 0) ?: "").toString()
    return before.endsWith(" ") && !before.endsWith("  ")
}

/** Backspace right after a swipe/prediction commit: delete the whole word
 *  (and its auto-space) in one step. Returns false when the cursor has
 *  moved on or something else was typed since. */
internal fun LLMKeyboardService.wipeGestureCommitIfPending(ic: android.view.inputmethod.InputConnection): Boolean {
    autoSpacePending = false
    val (word, hadSpace) = lastGestureCommit ?: return false
    val probe = word.length + 1
    val before = (ic.getTextBeforeCursor(probe, 0) ?: "").toString()
    val matches = if (hadSpace) before == "$word " else before.endsWith(word)
    if (!matches) return false
    lastGestureCommit = null
    ic.deleteSurroundingText(if (hadSpace) word.length + 1 else word.length, 0)
    popWordContext(word)
    // Wiping the word the decoder just committed is a REJECTION — give the
    // glide decoder the negative signal and pull the word back out of the
    // learned model (guarded: only while it's still lightly learned). A bar
    // tap wiped says nothing about the decoder, so it must not press that
    // word down in the glide bias.
    if (lastGestureWasGlide) {
        com.localboard.keyboard.util.LocalStats.bump(com.localboard.keyboard.util.LocalStats.Counter.GLIDE_WIPED)
        if (prefs.swipeLearning && !incognito) {
            swipeDecoder?.noteRejectedCommit(word)
            persistSwipeBias()
        }
    }
    // What was never learned has nothing to give back, and a real word's count must not pay for it.
    if (glideState.lastGestureLearned) {
        unlearnIfRecent(word, committedWords.lastOrNull().orEmpty(), committedWords.dropLast(1).lastOrNull().orEmpty())
    }
    correctionSession.onGestureWiped(word)
    return true
}

private const val LONGEST_SELECTION_READ = 10_000

/** How long a report of no composing region must stand before it counts. */
private const val REGION_GONE_CHECK_MS = 120L

/** How long after the last write the field is compared with what the keyboard believes. */
private const val QUIET_CHECK_MS = 400L

/** What leaves a sentence mark on the context trail. */
private fun endsSentence(c: Char): Boolean = c == '\n' || SENTENCE_END.contains(c.toString())

/**
 * With text selected, Backspace removes exactly that text. True when it did. The input connection's
 * delete works around a selection, never on it, so the caret goes to the end of the range first.
 *
 * The offsets are only the editor's last report: a letter or a space may have replaced the selection
 * since, and deleting by them would eat text nobody selected. So the editor is asked first, a round
 * trip that is only paid for when a selection is thought to be there.
 */
internal fun LLMKeyboardService.deleteSelectionIfAny(ic: android.view.inputmethod.InputConnection): Boolean {
    val a = selStart
    val b = selEnd
    if (a < 0 || b < 0 || a == b) return false
    // A very long selection is too much to pull across a binder call. It is taken on the editor's word.
    val selected = if (kotlin.math.abs(b - a) > LONGEST_SELECTION_READ) ""
    else ic.getSelectedText(0)?.toString()?.takeIf { it.isNotEmpty() } ?: return false
    val start = minOf(a, b)
    val end = maxOf(a, b)
    // A selection made right after a key can arrive with the word still composing.
    if (composing.isNotEmpty()) correctionSession.onComposingAbandoned(composing.toString())
    beginSelfEdit()
    ic.beginBatchEdit()
    try {
        composing.clear()
        composingTrace.clear()
        composingTraceValid = true
        ic.finishComposingText()
        ic.setSelection(end, end)
        ic.deleteSurroundingText(end - start, 0)
    } finally {
        ic.endBatchEdit()
        endSelfEdit()
    }
    // A key repeat can come before the editor's echo, and must not delete the range a second time.
    selStart = start
    selEnd = start
    selectionAnchor = -1
    lastGestureCommit = null
    lastSnippet = null
    autoSpacePending = false
    forgetGlideAlternates()
    // The trail mirrors the field, and nothing of a message taken back in full may be logged. A fix
    // removed this way was never accepted, the same as one taken out by a word delete.
    if (fieldIsEmpty()) {
        committedWords.clear()
        messageWords.clear()
        draftState.typedSinceSeed = 0
    }
    if (selected.trimEnd(' ').lastOrNull()?.let(::endsSentence) == true && committedWords.lastOrNull() == SENTENCE_MARK) {
        committedWords.removeLast()
    }
    val deleted = WordShape.contextForm(selected.trim())
    popWordContext(deleted)
    correctionSession.onWordDeleted(deleted, committedWords.lastOrNull() ?: "")
    correctionSession.onSelectionRemoved(selected)
    updateAutoCaps()
    refreshPredictions()
    return true
}

/** Backspace held down: delete the previous whole word at once. */
internal fun LLMKeyboardService.deleteWordBackward() {
    forgetCapitalMade()
    val ic = currentInputConnection ?: return
    if (deleteSelectionIfAny(ic)) return
    beginSelfEdit()
    if (composing.isNotEmpty()) {
        composing.clear()
        ic.setComposingText("", 1)
        onComposingDelete()
    } else {
        onDeleteWithEmptyComposing()
        val before = (ic.getTextBeforeCursor(48, 0) ?: "").toString()
        val trimmed = before.trimEnd(' ')
        val cut = trimmed.lastIndexOf(' ')
        val deleteCount = before.length - if (cut >= 0) cut + 1 else 0
        if (deleteCount > 0) {
            ic.deleteSurroundingText(deleteCount, 0)
            // The sentence-context trail mirrors the field: dropping a
            // whole word drops it there too, or stale prev-words keep
            // steering predictions toward what was just erased.
            val deletedWord = WordShape.contextForm(trimmed.substringAfterLast(' '))
            // "the." goes as one chunk, and the sentence mark goes with it.
            val endedSentence = trimmed.lastOrNull()?.let(::endsSentence) == true
            if (endedSentence && committedWords.lastOrNull() == SENTENCE_MARK) committedWords.removeLast()
            popWordContext(deletedWord)
            correctionSession.onWordDeleted(deletedWord, committedWords.lastOrNull() ?: "")
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }
    endSelfEdit()
    refreshPredictions()
}

/**
 * The composing word is done. With [mayCapitalise], a word in the capital's table lands with its capital, and that
 * cased word is returned so that the caller can offer a way back. Otherwise null. Learning and the session still see the
 * word as typed: its capital is not a correction, and they spell it in lower case anyway.
 */
internal fun LLMKeyboardService.finishComposingWord(beforeGlide: Boolean = false, mayCapitalise: Boolean = false): String? {
    if (composing.isEmpty()) return null
    val ic = currentInputConnection
    val word = composing.toString()
    onWordAboutToFinish(word)
    composing.clear()
    composingTrace.clear()
    composingTraceValid = true
    val keptAsTyped = autocorrectState.keepAsTypedOnce
    autocorrectState.keepAsTypedOnce = null

    // SNIPPETS: swap the trigger for its expansion at the moment the word
    // is committed. Done here rather than in autocorrect so it survives
    // every commit route (space, punctuation, enter). The corrector runs
    // before this on space and punctuation, so a trigger it takes for a
    // typo never gets here.
    val expansion = snippets.expand(word)
    if (expansion != null && ic != null) {
        beginSelfEdit()
        ic.beginBatchEdit()
        try {
            // Replace the composing region outright — no delete/insert
            // dance, so the cursor never visibly jumps.
            ic.setComposingText(expansion, 1)
            ic.finishComposingText()
        } finally {
            ic.endBatchEdit()
            endSelfEdit()
        }
        lastSnippet = word to expansion
        correctionSession.onChipReplacedWord()
        recordWords(expansion)
        updateAutoCaps()
        return null
    }

    val cased = if (mayCapitalise && ic != null && word != keptAsTyped) typedPronounCase(ic, word) else null
    beginSelfEdit()
    if (cased != null && ic != null) {
        ic.beginBatchEdit()
        try {
            // As the corrector does it, because a composing write lands at the caret if the editor has dropped the
            // region, and would leave 'iI'. The read in typedPronounCase has just seen the word there.
            ic.finishComposingText()
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(cased, 1)
        } finally {
            ic.endBatchEdit()
        }
    } else {
        ic?.finishComposingText()
    }
    endSelfEdit()
    accountForCommittedWord(word, beforeGlide)
    return cased
}

/** Backspace right after an expansion restores the trigger. Without this
 *  an accidental expansion costs the user a long manual delete. */
internal fun LLMKeyboardService.undoSnippetIfPending(): Boolean {
    val (trigger, expansion) = lastSnippet ?: return false
    val ic = currentInputConnection ?: return false
    lastSnippet = null
    val before = (ic.getTextBeforeCursor(expansion.length, 0) ?: "").toString()
    if (before != expansion) return false
    beginSelfEdit()
    ic.deleteSurroundingText(expansion.length, 0)
    composing.clear()
    composing.append(trigger)
    composingTraceValid = false
    ic.setComposingText(composing, 1)
    endSelfEdit()
    refreshPredictions()
    return true
}

internal fun LLMKeyboardService.updateAutoCaps() {
    if (!prefs.autoCaps) return
    val ic = currentInputConnection ?: return
    // A failed read says nothing about the caret. Only text that really is empty means the
    // start of the field, or a read that timed out would capitalise the middle of a sentence.
    setShiftFromTextBefore(ic.getTextBeforeCursor(4, 0) ?: return)
}

/** The same with [before] already read from the field, so that a show asks the app once. */
internal fun LLMKeyboardService.updateAutoCaps(before: CharSequence) {
    if (prefs.autoCaps) setShiftFromTextBefore(before)
}

private fun LLMKeyboardService.setShiftFromTextBefore(before: CharSequence) {
    val shouldCap = before.isEmpty() || before.endsWith(". ") ||
        before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")
    keyboardView.shiftState =
        if (shouldCap) KeyboardView.ShiftState.ON else KeyboardView.ShiftState.OFF
}

internal fun LLMKeyboardService.handleSelectionUpdate(
    oldSelStart: Int, oldSelEnd: Int,
    newSelStart: Int, newSelEnd: Int,
    candidatesStart: Int, candidatesEnd: Int
) {
    // Track the caret unconditionally — including during our own edits, so
    // the cursor pad never works from a stale offset after a commit.
    selStart = newSelStart
    selEnd = newSelEnd
    when (
        trackedEditor.onSelectionReported(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, selfEditing, composing.isNotEmpty()
        )
    ) {
        SelectionReport.ECHO -> return
        SelectionReport.ECHO_REGION_GONE -> {
            scheduleRegionGoneCheck()
            return
        }
        SelectionReport.REAL -> Unit
    }
    // A genuine external change drops composing state: the editor lost its
    // composing region (candidatesStart < 0), the user selected a range, OR
    // the cursor was deliberately moved away from the end of the composing
    // word (a tap elsewhere in the field). The last case is the "cursor
    // lock" bug: the composing span stayed alive, so the next
    // setComposingText() yanked the cursor BACK to the old word — typing
    // only landed where the user tapped after spacebar ended the word.
    // Committing the word where it stands (finishComposingText) releases
    // the span, so the next keystroke starts fresh exactly at the tap.
    val selectionMoved = oldSelStart != newSelStart || oldSelEnd != newSelEnd
    val cursorLeftComposing = candidatesStart >= 0 && newSelStart == newSelEnd &&
        selectionMoved && newSelStart != candidatesEnd
    val externalChange =
        candidatesStart < 0 || newSelStart != newSelEnd || cursorLeftComposing
    if (externalChange) applyExternalChange()
}

/** The caret or the text is no longer where this keyboard's own writes left it, so what was
 *  composing and what the bar and Backspace were holding no longer apply. */
private fun LLMKeyboardService.applyExternalChange() {
    // B3: the send BUTTON (a view click — no editor action) just cleared
    // the field: accumulated words were the message; log it whole. Only
    // fires on external changes with an empty field, so our own commits
    // and mid-typing cursor moves never false-trigger. A real read, or a
    // one-word reply would be lost from the log, and messageWords would run
    // on into the next message.
    // A one-word reply never reached messageWords: it is still composing.
    if ((messageWords.isNotEmpty() || composing.isNotEmpty()) && fieldIsEmpty()) {
        flushMessageLog("field-cleared", harvestComposing = true)
    }
    if (composing.isNotEmpty()) {
        // Not learned as a word: a tap-away can leave a half-typed
        // fragment, and fragments must not pollute the dictionary.
        correctionSession.onComposingAbandoned(composing.toString())
        composing.clear()
        composingTrace.clear()
        composingTraceValid = true
        beginSelfEdit()
        try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
        endSelfEdit()
    }
    onExternalCursorMove()
    lastGestureCommit = null
    lastSnippet = null
    autoSpacePending = false
    forgetGlideAlternates()
    // After an in-place clear the next message would stay lower case. updateAutoCaps reads
    // the field and overwrites caps lock, so this keeps out of a password field, an incognito
    // one and caps lock. With the keyboard down the next show sets Shift anyway, and the flags
    // here are the last shown field's, which need not be the field that just changed.
    if (keyboardViewInitialized && isInputViewShown && predictionsEnabled && !incognito &&
        keyboardView.shiftState != KeyboardView.ShiftState.CAPS_LOCK
    ) {
        updateAutoCaps()
    }
    refreshPredictions()
}

/**
 * The keyboard came up, on this connection or a new one. First on the show path, so that nothing
 * after it reads a position from the field before this. Reading the property is what notices a new
 * connection.
 */
internal fun LLMKeyboardService.onShowTrackEditor(info: EditorInfo) {
    currentInputConnection
    trackedEditor.onShow(info.initialSelStart, info.initialSelEnd)
    cancelEditorChecks()
}

/** Nothing either check looks at means the same once the keyboard has gone down or come up again. */
internal fun LLMKeyboardService.cancelEditorChecks() {
    selfEditHandler.removeCallbacks(regionGoneCheck)
    selfEditHandler.removeCallbacks(quietCheck)
}

/**
 * The editor reports no composing region with the caret where it should be. Acting on that at once
 * could itself split words: an editor may report that between two states, and whether Chrome does
 * cannot be told from here. So this waits a moment, and only a report that still stands counts.
 * The worst case of a wrong call is a split word and no lost text, since finishing keeps the text.
 */
private fun LLMKeyboardService.scheduleRegionGoneCheck() {
    selfEditHandler.removeCallbacks(regionGoneCheck)
    textState.regionGoneWord = composing.toString()
    selfEditHandler.postDelayed(regionGoneCheck, REGION_GONE_CHECK_MS)
}

/** Any newer write or a report with a region since then means the word is alive. Otherwise the next
 *  letter would write the whole word again behind what is already there. */
internal fun LLMKeyboardService.checkRegionGone() {
    if (composing.contentEquals(textState.regionGoneWord) && trackedEditor.regionStillGone()) applyExternalChange()
}

internal fun LLMKeyboardService.scheduleQuietCheck() {
    selfEditHandler.removeCallbacks(quietCheck)
    selfEditHandler.postDelayed(quietCheck, QUIET_CHECK_MS)
}

/**
 * The first quiet moment after a write. A length filter reports nothing for what it dropped, so
 * composing stays long and Backspace takes letters that are not there. Reads nothing when the echoes
 * have landed. Allowed in a password field: it is the same read Backspace does, sized to the word,
 * and nothing keeps it.
 */
internal fun LLMKeyboardService.checkAfterQuiet() {
    val ic = currentInputConnection ?: return
    val found = trackedEditor.afterQuiet(composing) { n ->
        try { ic.getTextBeforeCursor(n, 0) } catch (_: Exception) { null }
    }
    if (found == QuietCheck.LOST) applyExternalChange()
}

/** True when the field holds no text at all. False when it cannot be told. A selection is never empty,
 *  though with all of a field selected there is nothing on either side of it to read. */
internal fun LLMKeyboardService.fieldIsEmpty(): Boolean {
    val ic = currentInputConnection ?: return false
    if (selStart != selEnd) return false
    return try {
        ic.getTextBeforeCursor(1, 0)?.isEmpty() == true && ic.getTextAfterCursor(1, 0)?.isEmpty() == true
    } catch (_: Throwable) {
        false
    }
}

internal fun LLMKeyboardService.onHideResetTextState() {
    cancelEditorChecks()
    onHideCheckStore()
    voiceInput?.cancel()
    if (predictionBarInitialized) predictionBar.setVoiceState(false, null)
    lastGestureCommit = null
    autoSpacePending = false
    forgetGlideAlternates()
    forgetCapitalMade()
    val session = correctionSession
    if (composing.isNotEmpty()) session.onComposingAbandoned(composing.toString())
    // The next show starts from nothing anyway. Done here, the framework ending the region as the view goes down reads
    // as an echo, so no check reads the field or runs a prediction pass for a keyboard nobody can see.
    composing.clear()
    composingTrace.clear()
    composingTraceValid = true
    session.onHide()
}

// ------------------------------------------------------------------
// Editing events
//
// The pipeline in this file calls these at the moments only it can see. Each
// subsystem that cares has its own hook in its own file, so reacting to an
// edit never means changing the pipeline.
// ------------------------------------------------------------------

/** The caret or the text changed and no write of this keyboard's accounts for it, or the
 *  cursor pad moved the caret because a key asked for that. */
internal fun LLMKeyboardService.onExternalCursorMove() {
    autocorrectOnExternalCursorMove()
    learningOnExternalCursorMove()
    touchOnExternalCursorMove()
}

/** A new composing word is about to start: its first letter is not in yet.
 *  Going back into a committed word (Backspace re-entry, undo) starts no word,
 *  so those paths do not come here. */
internal fun LLMKeyboardService.onWordStart() {
    autocorrectOnWordStart()
    touchOnWordStart()
}

/** [word] is still in composing, about to commit. Snippet expansion runs after
 *  this, so it may not commit as typed. A word the corrector replaced does not
 *  come here. */
internal fun LLMKeyboardService.onWordAboutToFinish(word: String) {
    autocorrectOnWordAboutToFinish(word)
    touchOnWordAboutToFinish(word)
}

/** A delete took characters out of composing, which may now be empty. The
 *  field has been updated too, except on the emoji page's Backspace, where it
 *  follows straight after. */
internal fun LLMKeyboardService.onComposingDelete() {
    autocorrectOnComposingDelete()
    touchOnComposingDelete()
}

/** A delete arrived with nothing composing, so it acts on committed text. */
internal fun LLMKeyboardService.onDeleteWithEmptyComposing() {
    autocorrectOnDeleteWithEmptyComposing()
    touchOnDeleteWithEmptyComposing()
}
