package com.localboard.keyboard.ime

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.engine.ContextTokens
import com.localboard.keyboard.engine.correction.WordShape
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SENTENCE_END
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.SENTENCE_MARK
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.WORD_SPLIT
import com.localboard.keyboard.ui.KeyboardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the keyboard learns from: words, sentences, the message log.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class LearningState {
    /** The entries of committedWords that came from an accepted draft, known by identity and not
     *  by spelling, because the person may well use the same word themselves. */
    val draftWords = ArrayList<String>()

    /** A moved caret waiting out its debounce before the words before it are read. */
    var caretMoveCheck: Job? = null

    /** The run touching the caret that the last read of the field left off the trail, because nothing says it is
     *  finished. While the caret still touches it, it is what the next word would follow. */
    var keptRun: String? = null
}

/** Long enough for a run of caret reports to settle into one read. */
private const val CARET_MOVE_DEBOUNCE_MS = 120L

/** Enough to hold the word a commit just finished, with nothing else of the field in it. */
private const val FINISHED_WORD_WINDOW = 48

internal fun LLMKeyboardService.onShowFlushMessageLog(info: EditorInfo, restarting: Boolean, emptyOnRestart: Boolean) {
    learningState.caretMoveCheck?.cancel()
    // B3: a leftover accumulator from the previous field was (almost
    // always) a sent message — log it against the OLD package before
    // currentAppPackage/hint change under us.
    if (!restarting) {
        flushMessageLog("field-change")
    } else if ((messageWords.isNotEmpty() || composing.isNotEmpty()) && emptyOnRestart) {
        // An app clearing its field by setText restarts input and suppresses the selection
        // update handleSelectionUpdate would have logged this from. It was a sent message
        // all the same, maybe with its last word still composing.
        flushMessageLog("field-cleared", harvestComposing = true)
    }
}

/** Undo a learning mistake: remove a word the engine just picked up — but
 *  ONLY while its user frequency is still at/below the promotion
 *  threshold. removeWordEverywhere suppresses the word outright (base
 *  dictionary included), so without this guard a heuristic revert could
 *  banish a genuine, established word. [prev1] and [prev2] are the words it was learned after, because a
 *  wrong word whose pair stayed behind would come back more likely on the retry. */
internal fun LLMKeyboardService.unlearnIfRecent(word: String, prev1: String, prev2: String) {
    if (incognito || !prefs.learningEnabled) return
    val w = word.trim().lowercase()
    if (w.length < 2) return
    // One occurrence back out of the personal model — never a removal
    // from the dictionary, never a suppression (see FastEngine.unlearnRecent).
    contextEngine.unlearnRecent(w, prev1, prev2)
}

// ------------------------------------------------------------------
// Learning helpers
// ------------------------------------------------------------------

internal fun LLMKeyboardService.learnWord(word: String, selected: Boolean = false) {
    // Never learn from password / sensitive fields (predictions disabled
    // there), nor from a field that asked for incognito.
    if (!predictionsEnabled) return
    if (incognito) {
        // Remembered nowhere, but the next word in this field still follows it: this stays in memory
        // and never joins the message being written, which is what could reach the typing log.
        addToTrailAsTyped(word)
        return
    }
    val clean = word.trim().lowercase()
    if (prefs.learningEnabled && clean.length >= 2) {
        val prev1 = committedWords.lastOrNull() ?: ""
        val prev2 = committedWords.dropLast(1).lastOrNull() ?: ""
        if ('@' in clean && clean.any { it.isDigit() }) {
            // ContextEngine.learnWord still drops any token containing a
            // digit, and email addresses keep theirs — so digit-bearing
            // addresses feed the engine directly (in-memory; they are
            // re-learned quickly and never reach the persisted model).
            fastEngine.learn(clean, prev1, prev2, selected)
        } else {
            // Digit-free emails pass ContextEngine's filter untouched and
            // are persisted like any other learned word.
            contextEngine.learnWord(clean, prev1, prev2, currentAppPackage, selected)
        }
        // Track it for the backspace-re-entry unlearn (see DELETE handler).
        lastLearnedWord = clean
        lastLearnedAt = SystemClock.uptimeMillis()
    }
    // skipNgramLearn: the unigram/n-gram side of this word was JUST
    // counted by learnWord above. Letting recordWords -> onTextCommitted
    // count it again doubled every typed word (promotion at 2 effective
    // taps instead of 4). appVocab/style tracking still runs there.
    recordWords(word, skipNgramLearn = true)
}

internal fun LLMKeyboardService.recordWords(
    text: String,
    aiOrigin: Boolean = false,
    skipNgramLearn: Boolean = false
) {
    // PASSWORD FIELDS. learnWord() already refuses to run here, but two
    // callers reach recordWords() without going through it — the voice
    // transcription result and snippet expansion — so dictating or
    // expanding a snippet into a password box used to write it to the
    // encrypted typing log, the per-app vocab and the style profile.
    // predictionsEnabled is false for every password variation.
    if (!predictionsEnabled) return
    // Feed the encrypted typing log — the AI's memory of what the user
    // has said in this app. THE context lifeline: until 1.4.6 only
    // AI-drafted text reached it, so the model always drafted "blind"
    // (the "pre-canned response" bug).
    if (aiOrigin) {
        // P0: an accepted AI draft is NOT the user's voice. It must not
        // teach the n-gram model, the per-app vocab, or the style
        // profile (onTextCommitted does all three), and it must not
        // join messageWords — those rows flush as human-typed messages.
        // Log it as one AI-tagged row instead: the privacy dashboard
        // still shows what the keyboard wrote, while every style-recall
        // query filters origin = 1 out.
        // Incognito applies here too. flushMessageLog() and learnWord()
        // both honour it; this path did not, so accepting an AI draft in
        // Signal persisted it to the encrypted log — exactly what
        // IME_FLAG_NO_PERSONALIZED_LEARNING asks us not to do. The draft
        // still updates in-memory prev-word context below (that dies with
        // the session); it just never reaches disk.
        if (!incognito) {
            val threadKey = com.localboard.keyboard.context.ScreenBuffer
                .currentScreenKey(currentAppPackage)
                .ifBlank { hintThreadKey() }
            contextEngine.onMessageCommitted(
                text, currentAppPackage, threadKey, aiOrigin = true
            )
        }
        // The draft is still conversation reality once sent, so it does
        // update prev-word context (what the user types NEXT benefits) —
        // just never the learning streams above.
        for (token in ContextTokens.tokenize(text)) {
            if (addToTrail(token) && token != SENTENCE_MARK) learningState.draftWords.add(token)
        }
        while (learningState.draftWords.size > 40) learningState.draftWords.removeAt(0)
        return
    }
    // n-grams, per-app vocab and style refinement all flow from here —
    // none of it may happen in an incognito field, and none of it when
    // the user has turned learning off altogether.
    if (!incognito && prefs.learningEnabled) {
        contextEngine.onTextCommitted(
            text, currentAppPackage, skipNgramLearn = skipNgramLearn
        )
    }
    addToTrailAsTyped(text)
    // The message keeps its punctuation, as the typing log has always had it.
    for (w in WORD_SPLIT.split(text)) {
        val clean = w.trim().lowercase()
        if (clean.isNotEmpty()) messageWords.add(clean)
    }
    // Pathological safety: never accumulate a novel.
    while (messageWords.size > 240) messageWords.removeAt(0)
}

/** One token joins the trail, unless it would be a second mark in a row. True when it did. */
private fun LLMKeyboardService.addToTrail(token: String): Boolean {
    learningState.keptRun = null
    if (token == SENTENCE_MARK && committedWords.lastOrNull() == SENTENCE_MARK) return false
    committedWords.addLast(token)
    while (committedWords.size > 40) committedWords.removeFirst()
    return true
}

/** The trail gains what [text] holds, as this person's own words, so each one counts. 'tomorrow.' goes on as a
 *  word and a mark, and the next word then follows the mark. */
private fun LLMKeyboardService.addToTrailAsTyped(text: String) {
    for (token in ContextTokens.tokenize(text)) {
        if (addToTrail(token) && token != SENTENCE_MARK) {
            draftState.typedSinceSeed = GlideRules.countAfterAdding(draftState.typedSinceSeed)
        }
    }
}

/**
 * A field can hold words before this keyboard has typed any: a message left half written, a reply already open. The
 * bar and the next glide carry on from those. The gate comes before any read, so nothing is read from a password,
 * private or address field, and what is read joins the trail alone. It never joins the message being written, so it
 * cannot reach the typing log as if it had been typed here.
 */
internal fun LLMKeyboardService.reseedContextFromField(info: EditorInfo, restarting: Boolean) {
    // The field changed, so the trail has just been emptied, whatever comes of the read.
    if (!restarting) draftState.typedSinceSeed = 0
    val keptBefore = learningState.keptRun
    learningState.keptRun = null
    val pkg = info.packageName.orEmpty()
    if (GlideRules.mayReadField(info.inputType, info.imeOptions, prefs.mayLearnIn(pkg), pkg == packageName)) {
        val read = readBeforeCaretForContext()
        if (read != null && GlideRules.showReplacesTrail(restarting, read, committedWords, info.initialSelStart)) {
            replaceTrailWithField(read)
        } else if (read != null && restarting && keptBefore == ContextTokens.runAtCursor(read)) {
            // A rotation keeps the trail, and that goes for the word it was waiting on too.
            learningState.keptRun = keptBefore
        }
        // Shift went on above without a look at the field, and chips and glides now follow it, so a return to the middle
        // of a sentence would come back capitalised. This one read serves both, so that a show waits on the app once, and
        // a read that gave up leaves Shift as it is.
        if (predictionsEnabled && read != null) updateAutoCaps(read)
    } else if (predictionsEnabled) {
        // Nothing was read above. This read is four characters and stores nothing.
        updateAutoCaps()
    }
}

/** Not a cached read: an app clearing its field by setText sends no selection update, so a cached
 *  copy would bring the message just sent back as context. */
private fun LLMKeyboardService.readBeforeCaretForContext(): CharSequence? =
    try {
        currentInputConnection?.getTextBeforeCursor(ContextTokens.FIELD_WINDOW, 0)
    } catch (_: Exception) {
        null
    }

private fun LLMKeyboardService.replaceTrailWithField(read: CharSequence) {
    val tokens = ContextTokens.fromFieldWindow(read).takeLast(40)
    committedWords.clear()
    committedWords.addAll(tokens)
    learningState.draftWords.clear()
    learningState.keptRun = ContextTokens.runAtCursor(read)
    draftState.typedSinceSeed = 0
    // An empty field and one holding only half a word both give a lone mark, and keep their blank bar.
    if (tokens.any { it != SENTENCE_MARK }) typedThisField = true
}

/**
 * A word finished after a return: 'tomor' was left in the field, and the reseed rightly passed it over as unfinished.
 * Typing 'row ' now commits a fragment the session never counts, so the word whole is read back. The trail needs it,
 * as what the next word follows. Nothing here teaches, and it was not typed here in full, so it is not counted.
 * True when the trail gained it.
 */
internal fun LLMKeyboardService.addFinishedWordFromField(): Boolean {
    val info = currentInputEditorInfo ?: return false
    val pkg = info.packageName.orEmpty()
    if (!GlideRules.mayReadField(info.inputType, info.imeOptions, prefs.mayLearnIn(pkg), pkg == packageName)) return false
    val ic = currentInputConnection ?: return false
    val before = try {
        if (WordShape.continuesWord(ic.getTextAfterCursor(1, 0)?.firstOrNull())) return false
        ic.getTextBeforeCursor(FINISHED_WORD_WINDOW, 0) ?: return false
    } catch (_: Exception) {
        return false
    }
    // Only a word touching the caret. A run holding a digit gives none, and the word before it is not the one finished.
    val word = ContextTokens.runAtCursor(before, FINISHED_WORD_WINDOW) ?: return false
    return addToTrail(word)
}

/** A word in the dictionary can be taken for finished. Anything else may be half of one. */
private fun LLMKeyboardService.keptRunIsKnown(run: String): Boolean =
    contextEngine.isInitialized && fastEngine.isKnownWord(run)

/** What stands before a half word is not what comes after it either, so nothing is taught across it. */
private fun LLMKeyboardService.forgetTrailAcrossRun() {
    committedWords.clear()
    learningState.draftWords.clear()
}

/**
 * A word is about to land after the run the trail left out. With a space before it, that ends the run. A run the
 * dictionary knows is then the word this one follows, uncounted, as it was not typed here. With no space the two are one
 * run nobody can read as words, and what is learned from that follows nothing.
 */
internal fun LLMKeyboardService.endKeptRunBeforeWord(spaced: Boolean) {
    val run = learningState.keptRun ?: return
    learningState.keptRun = null
    if (!(spaced && keptRunIsKnown(run) && addFinishedWordFromField())) forgetTrailAcrossRun()
}

/**
 * Whether anything has ended the run the trail left out, which only the field can say: a Space or a comma lands no word of
 * this keyboard's. Letters typed on to it make a new word, which is read back whole when it ends. Only reads with a run
 * kept, so typing pays nothing for this.
 */
internal fun LLMKeyboardService.settleKeptRun() {
    val state = learningState
    val run = state.keptRun ?: return
    if (composing.isNotEmpty()) {
        state.keptRun = null
        return
    }
    val info = currentInputEditorInfo
    val pkg = info?.packageName.orEmpty()
    val read = if (info != null && GlideRules.mayReadField(info.inputType, info.imeOptions, prefs.mayLearnIn(pkg), pkg == packageName)) {
        try {
            currentInputConnection?.getTextBeforeCursor(FINISHED_WORD_WINDOW, 0)
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    if (read != null && ContextTokens.runAtCursor(read, FINISHED_WORD_WINDOW) == run) return
    state.keptRun = null
    if (read == null || ContextTokens.runBeforeSeparators(read, FINISHED_WORD_WINDOW) != run) return
    if (keptRunIsKnown(run)) addToTrail(run) else forgetTrailAcrossRun()
}

/** A message boundary: SEND/GO/DONE, Enter in a single-line field, the
 *  field emptying after having content, or the keyboard/field going away.
 *  The accumulated words become ONE typing-log row tagged with the
 *  thread key — this is what "messages I sent recently" actually means. */
internal fun LLMKeyboardService.flushMessageLog(reason: String, harvestComposing: Boolean = false) {
    // Incognito: drop whatever accumulated rather than persisting it.
    if (incognito) { messageWords.clear(); return }
    // P0: an external field-clear (send BUTTON) can leave the last word
    // still composing — never space-terminated, never in messageWords.
    // That tail was part of the sent message; harvest it before logging.
    if (harvestComposing && composing.isNotEmpty()) {
        val tail = composing.toString().trim()
        if (tail.length >= 2 && tail.all { it.isLetter() || it == '\'' }) {
            messageWords.add(tail)
        }
    }
    if (messageWords.isEmpty()) return
    val msg = messageWords.joinToString(" ").trim()
    messageWords.clear()
    if (msg.length < 2) return
    val threadKey = com.localboard.keyboard.context.ScreenBuffer
        .currentScreenKey(currentAppPackage)
        .ifBlank { hintThreadKey() }
    contextEngine.onMessageCommitted(msg, currentAppPackage, threadKey)
}

/** "Message John" / "Message to John" — the editor's own hint names the
 *  thread when the accessibility stream can't (screen-blind apps). */
internal fun LLMKeyboardService.hintThreadKey(): String {
    val h = currentInputEditorInfo?.hintText?.toString()?.trim() ?: return ""
    val stripped = h.removePrefix("Message to ").removePrefix("Message ").trim()
    return if (stripped.length in 2..40) "hint:$stripped" else ""
}

/** Style-level learning when a sentence ends (n-grams already learned per word). */
internal fun LLMKeyboardService.maybeLearnSentence(separator: String) {
    // What this separator ended goes on the trail ahead of any mark.
    if (learningState.keptRun != null) settleKeptRun()
    val ends = SENTENCE_END.contains(separator) || separator == "\n"
    if (ends && prefs.learningEnabled && !incognito && committedWords.isNotEmpty()) {
        // An accepted draft's words stay above as context for the next word,
        // but they say nothing about how this person writes.
        val own = committedWords.filter { w -> learningState.draftWords.none { it === w } }
        GlideRules.sentenceToLearn(own, draftState.typedSinceSeed)?.let { contextEngine.onSentenceCommitted(it) }
    }
    // The sentence is over: the word before the full stop must not go
    // on steering next-word prediction ("tomorrow." was offering
    // "morning"). A boundary token takes its place as prev1, so the bar
    // falls back to sentence starters — and, because the boundary is
    // learned as an ordinary prev-word, the user's own openers ("lol",
    // "ok", "i") take over from the generic list within a few sentences.
    if (ends) learningState.keptRun = null
    if (ends && committedWords.lastOrNull() != SENTENCE_MARK) {
        committedWords.addLast(SENTENCE_MARK)
        while (committedWords.size > 40) committedWords.removeFirst()
    }
}

internal fun LLMKeyboardService.onHideFlushMessageLog() {
    learningState.caretMoveCheck?.cancel()
    cancelGlideHold()
    // B3: last chance to log whatever was composed before the field went
    // away (some flows dismiss the keyboard on send without an action).
    flushMessageLog("kbd-hide")
}

/**
 * The caret moved with no write of this keyboard's behind it, so the next word follows what is before it now. The bar
 * has already refreshed for the old position by the time this runs, so it refreshes again. Nothing cached says which
 * field this is when the check fires, because the connection may already be the next field's and the flags still
 * the last one's, so everything is asked of the editor at that moment.
 */
internal fun LLMKeyboardService.learningOnExternalCursorMove() {
    learningState.keptRun = null
    val scheduled = currentInputEditorInfo ?: return
    val state = learningState
    val atSchedule = GlideRules.MoveState(scheduled.packageName.orEmpty(), scheduled.fieldId, draftState.typedSinceSeed)
    state.caretMoveCheck?.cancel()
    state.caretMoveCheck = serviceScope.launch {
        delay(CARET_MOVE_DEBOUNCE_MS)
        val info = currentInputEditorInfo ?: return@launch
        val pkg = info.packageName.orEmpty()
        val now = GlideRules.MoveState(pkg, info.fieldId, draftState.typedSinceSeed)
        if (!GlideRules.moveMayAct(atSchedule, now, composing, info.inputType)) return@launch
        if (GlideRules.mayReadField(info.inputType, info.imeOptions, prefs.mayLearnIn(pkg), pkg == packageName)) {
            readBeforeCaretForContext()?.let { replaceTrailWithField(it) }
        }
        // updateAutoCaps would end caps lock, and moving the caret never has.
        if (keyboardViewInitialized && keyboardView.shiftState != KeyboardView.ShiftState.CAPS_LOCK) updateAutoCaps()
        refreshPredictions()
    }
}
