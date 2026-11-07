package com.localboard.keyboard.ime

import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.R
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.AI_PREDICT_ABANDON_MS
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.AI_PREDICT_CONTEXT_CHARS
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.AI_PREDICT_DEBOUNCE_MS
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.AI_PREDICT_FRAME
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.BLANK_CONTEXT_INSTRUCTION
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.RETRY_INSTRUCTION
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.WORD_SPLIT
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.appToneFor
import com.localboard.keyboard.llm.GenerateRequest
import com.localboard.keyboard.llm.LocalStyleComposer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * On-device AI: next-word predictions, Write It For Me, quick prompts.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class DraftState {
    /** Moves on whenever a run starts or is dropped. Native generation cannot be interrupted,
     *  so a dropped run goes on calling back for a while, and this is how those calls are told
     *  they are too late. Read on the decode thread. */
    @Volatile
    var generation = 0L

    /** A finished draft is in the bar, waiting for the check or the cross. */
    var inReview = false

    /** The bar is saying a run is slow, and a tap on those words is the way out. */
    var slowOffered = false

    /** The running request, so what has streamed so far can get the same review. */
    var request: GenerateRequest? = null

    /** Typing pushed this finished draft out of the bar, and it can still be brought back. */
    var setAside: SetAsideDraft? = null

    /** A draft is waiting for the app's field to come back from a box or a dialog. That closing
     *  looks just like the keyboard hiding, and must not stop it. */
    var awaitingField = false

    /** The app whose screen a draft may have read for a field that asked not to be remembered. */
    var screenReadFor: String? = null

    /** What the quick prompt's box was told to do, when no field took it up as the box closed. */
    var heldInstruction: HeldInstruction? = null

    /** What the box told the run under way or just failed to do. The run is the only place it is now, and a hide can end that. */
    var runningInstruction: HeldInstruction? = null

    /** Words the person has typed in this field since the trail was read from it. A trail read from the field is
     *  not something they have just typed, so the next-word path asks nothing while this is 0. Written where a
     *  word enters or leaves the trail, and only read here. */
    var typedSinceSeed: Int = 0

    /** The next prediction pass is the one a show starts, and the next-word path sits it out. */
    var skipNextWordsOnce = false

    /** Whether a model able to draft is on the phone, as of the last show that was not a restart. Null before any show. */
    var draftingReady: Boolean? = null

    /** Held here because preferences hold their listeners weakly. It goes when this does. */
    var activeChoiceWatch: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    val mainHandler = Handler(Looper.getMainLooper())
}

/**
 * A finished draft that typing pushed out of the bar. It goes back only to the field it was written for, and
 * [textBefore] is what stood before the cursor then: an app can reuse one box, and one id, for every chat.
 */
internal class SetAsideDraft(
    val text: String,
    val replaceText: String?,
    val pkg: String?,
    val fieldId: Int,
    val until: Long,
    val textBefore: String
)

/**
 * An instruction typed into the quick prompt's box that no field took up. The box was the only other place it
 * existed, and it is gone. Kept in memory for a moment and for that app only, to offer back the next time the
 * keyboard comes up there. With the keyboard down nothing can be said before then: the system drops a keyboard's toasts.
 */
internal class HeldInstruction(
    val instruction: String,
    val replaceText: String?,
    val pkg: String,
    val until: Long
)

/** How often the watchdog looks at a run. */
private const val WATCHDOG_TICK_MS = 1_000L

/** Tokens arrive faster than the eye reads. About 11 redraws a second is plenty. */
private const val STREAM_PUSH_MS = 90L

/** How long a closed dialog takes to hand the window focus back to the field. */
private const val FIELD_RETURN_MS = 300L

/** Tries, 200 ms apart, to find the app's field again after the quick prompt's box closes. */
private const val FIELD_WAIT_ATTEMPTS = 25

/** Every so many of those tries, ask for the keyboard, for a phone that left it down. */
private const val SHOW_KEYBOARD_EVERY = 5

/** A week: long enough to count as an answer, and not so long that it is forgotten as a choice. */
private const val SCREEN_OFFER_QUIET_MS = 7 * 24 * 60 * 60_000L

/** How long the sparkle can still bring back a draft that typing pushed out of the bar. */
private const val SET_ASIDE_MS = 60_000L

/** How long an instruction no field took up is kept to offer back. */
private const val HELD_INSTRUCTION_MS = 60_000L

/** How often a download started from the keyboard is looked at. */
private const val DOWNLOAD_WATCH_MS = 1_000L

/** A text longer than this is not read for a rewrite, because only what is read gets replaced. */
private const val REWRITE_MAX_CHARS = 1_200

/** Extra characters read back, so a few spaces after a rewrite's source still let it be found. */
private const val REWRITE_SPARE_CHARS = 64

/** Enough of the field to spot a draft that starts by repeating the end of it. */
private const val INSERT_LOOKBACK_CHARS = 300

/** Native finish codes, as named by AiDiagLog.finishLabel. */
private const val FINISH_TOKEN_CAP = 1
private const val FINISH_CANCELLED = 2

private const val DRAFT_STARTING = "✨ …"
private const val DRAFT_SLOW = "✨ Still working. Tap here to stop and pick a faster model."
private const val DRAFT_NO_FIELD = "Can't reach this text field. Tap it and try again."
private const val DRAFT_RULED_OUT = "Drafts can't go in this field."
private const val HELD_NO_FIELD = "Couldn't get back to that field. Tap it within a minute to use your prompt."
private const val HELD_RULED_OUT = "Drafts can't go in this field. Tap a message box within a minute to use your prompt."
private const val HELD_OFFER_PROMPT = "Your prompt didn't run. Tap to run it: "
private const val HELD_OFFER_REWRITE = "Your rewrite didn't run. Tap to run it."

internal fun LLMKeyboardService.onShowMirrorRedaction(info: EditorInfo, restarting: Boolean) {
    // Mirror the redaction preference into the capture path each time the
    // keyboard comes up, so toggling it in Settings takes effect at once.
    com.localboard.keyboard.context.ScreenBuffer.redactEnabled = prefs.redactScreenText
}

internal fun LLMKeyboardService.onShowStartScreenCapture(info: EditorInfo, restarting: Boolean) {
    // First, because a finger still down was aimed at what was here before, and this may be a password box.
    predictionBar.dropPress(restarting)
    // The prediction pass this show ends with is the first one since the field changed. A restart keeps
    // the words typed so far, so only this stops it reading the field and loading a model at every show.
    draftState.skipNextWordsOnce = true
    // Screen reading only works while the keyboard is up: that's the only
    // moment the AI can use it, and skipping background capture removes
    // the accessibility service's biggest battery cost by far. A field that
    // asked not to be remembered, or that holds a secret, gets the same
    // treatment as a hidden keyboard: the keyboard coming up there reads nothing.
    // A sparkle tap in such a field is a request, and reads the screen for that
    // one draft, which clears what it read as soon as it has it.
    val secret = com.localboard.keyboard.engine.correction.FieldPolicy.isPassword(info.inputType)
    prepareBarForShow(restarting)
    predictionBar.setDraftingAvailable(
        draftState.draftingReady == true && draftingAllowedIn(info) && !isQuickPromptBox(info)
    )
    // This app's own fields, the quick prompt's box first of all. What goes in that box is an
    // instruction, and it must not come back later as an example of how this person writes.
    if (info.packageName == packageName) incognito = true
    val capture = !secret && !incognito
    com.localboard.keyboard.context.ScreenBuffer.imeVisible = capture
    val service = com.localboard.keyboard.context.ScreenContextService.instance
    if (capture) service?.captureSoon() else service?.cancelPending()
    onShowCheckWhatIsHeld(info, restarting)
}

/**
 * What the bar takes from the service, and whether it has a sparkle at all. The answer is one stored boolean, worked
 * out again away from the main thread for the next show, so a show here makes no file check and no binder call, and a
 * changed answer never moves the slots while someone is aiming at them.
 */
private fun LLMKeyboardService.prepareBarForShow(restarting: Boolean) {
    val models = com.localboard.keyboard.llm.ModelRegistry
    if (com.localboard.keyboard.ui.BarRules.readsStoredAnswer(restarting, draftState.draftingReady)) {
        draftState.draftingReady = models.storedDraftingAnswer(this)
        models.refreshDraftingAnswer(this)
    }
    if (draftState.activeChoiceWatch == null) draftState.activeChoiceWatch = models.watchActiveChoice(this)
    val bar = predictionBar
    bar.animLevel = keyboardView.animLevel
    bar.onPillDown = {
        // As a key does, so the person's own level and sound pack apply. Both off is the cheap common case.
        val feedback = keyboardView.keyFeedback
        if (feedback.vibLevel > 0 || feedback.soundPack > 0) feedback.keyDown()
    }
    // The chip can outlast its fix by one pass, and a tap then would commit the typo as a chosen word.
    bar.revertChipLive = { correctionSession.lastFix != null }
    // A key can land under a finger on the bar, ahead of the list, so a lift goes by what is being typed by then.
    bar.composingWord = { composing.toString() }
}

/** The keyboard has come up on a field, and things kept from before may or may not belong on it. */
private fun LLMKeyboardService.onShowCheckWhatIsHeld(info: EditorInfo, restarting: Boolean) {
    closeSheetLeftOnAnotherField(info)
    // Only a restart gets here with a draft set aside, because any other start finishes the old input
    // first. It can mean another chat in a box the app reuses, and the text before the cursor can only
    // tell chats apart if there was some. Two empty boxes look the same.
    if (restarting && draftState.setAside?.textBefore?.isEmpty() == true) draftState.setAside = null
    offerHeldInstruction(info)
}

/** Holds on to what was typed into the box, because nothing else does. Nothing was typed if [instruction] is null. */
private fun LLMKeyboardService.holdInstruction(instruction: String?, replaceText: String?, fieldPackage: String?): Boolean {
    draftState.heldInstruction = instructionToKeep(instruction, replaceText, fieldPackage) ?: return false
    return true
}

private fun instructionToKeep(instruction: String?, replaceText: String?, fieldPackage: String?): HeldInstruction? {
    if (instruction == null || fieldPackage == null) return null
    return HeldInstruction(instruction, replaceText, fieldPackage, SystemClock.elapsedRealtime() + HELD_INSTRUCTION_MS)
}

/** Each time the keyboard comes up, because a key press or a restart can take a notice away before anyone reads it. */
private fun LLMKeyboardService.offerHeldInstruction(info: EditorInfo) {
    val held = draftState.heldInstruction ?: return
    if (SystemClock.elapsedRealtime() > held.until) {
        draftState.heldInstruction = null
        return
    }
    if (info.packageName != held.pkg || !draftingAllowedIn(info) || wifmJob?.isActive == true) return
    val offer = if (held.replaceText != null) HELD_OFFER_REWRITE else "$HELD_OFFER_PROMPT“${held.instruction}”"
    // It goes when a draft starts and not here: a tap that a gate turns away has not used it.
    showNotice(offer) { startWifm(held.instruction, replaceText = held.replaceText) }
}

// ------------------------------------------------------------------
// AI next-word predictions (opt-in)
// ------------------------------------------------------------------

/** Full invalidation (job + generation + cache): field changes, page
 *  switches and the emoji panel — moments where even a snapshot match
 *  would be coincidence, not continuity. Keystrokes are handled cheaper
 *  in refreshPredictions (job + token only; the cache key does the rest). */
internal fun LLMKeyboardService.cancelAiSuggestion() {
    aiSuggestJob?.cancel()
    aiSuggestToken++
    cachedAiWords = null
}

/**
 * Opt-in "AI predictions": after every committed word, ask the quick-lane
 * model what comes next given the WHOLE message so far, and put its
 * answers at the front of the bar.
 *
 * This replaced the instruction-prompted "continue this message in 3-8
 * words" phrase chip. That lane rebuilt its prompt from scratch on every
 * pause (hundreds of milliseconds of prompt processing), asked a 0.6B
 * model to follow an instruction it mostly parroted, and fired only
 * after a 700 ms pause — so in practice it was rarely seen. The predictor
 * instead keeps the message's tokens resident in the model's KV cache and
 * decodes only the word just typed, then reads the next-word
 * distribution straight off the logits: typically well under 150 ms on
 * the 0.6B model, and it answers "how are" with "you" where a bigram
 * table can only offer "the".
 *
 * Same discipline as the other quick-lane users: password/incognito
 * fields never reach the model, the power gate applies, and a result is
 * dropped if anything changed since the request.
 *
 * The first lines are in the order of what they cost, so that someone with
 * the switch off pays nothing per key, and a show pays no model lookup, no
 * power check and no field read.
 */
internal fun LLMKeyboardService.maybeLlmNextWords() {
    val skipOnce = draftState.skipNextWordsOnce
    draftState.skipNextWordsOnce = false
    if (!predictionsEnabled || incognito || showingEmoji) return
    // Nobody can see the words with the keyboard down, and a pass that runs then must not leave a model in memory.
    if (!isInputViewShown) return
    if (!prefs.llmSuggestions) return
    // With a revert chip up, a model's words could push it off the bar: the merge keeps three. So
    // nothing is asked, and an answer already on hand goes before the pass that would read it.
    if (correctionSession.lastFix != null) {
        cancelAiSuggestion()
        return
    }
    val field = currentInputEditorInfo
    val policy = field?.let {
        com.localboard.keyboard.engine.correction.FieldPolicy
            .from(it.inputType, it.imeOptions, mayLearnInApp = false)
    }
    if (!NextWordGuard.mayAsk(
            hasField = field != null,
            aiAllowedHere = aiAllowedHere,
            fieldAi = policy?.ai == true,
            fieldSuggestions = policy?.suggestions == true,
            typedSinceSeed = draftState.typedSinceSeed,
            skipOnce = skipOnce
        )
    ) return
    if (composing.isNotEmpty() || committedWords.isEmpty()) return
    val provider = llamaProvider() ?: return
    // Only the QUICK model is needed here (isAvailable would also demand
    // the drafting model, which this never touches).
    if (!com.localboard.keyboard.llm.ModelRegistry.isReady(
            this, com.localboard.keyboard.llm.ModelRegistry.quick(prefs)
        )
    ) return
    if (com.localboard.keyboard.util.PowerGate.blockReason(this, prefs) != null) return
    val snapshot = committedWords.takeLast(10).joinToString(" ")
    // Already answered (or failed) for this exact context.
    if (cachedAiWords?.first == snapshot) return
    val ic = currentInputConnection ?: return
    // The model sees the real field text — punctuation, capitalisation,
    // earlier sentences — not just the word list. Read it NOW, on the
    // main thread, before the debounce: an InputConnection call from a
    // worker can stall behind the app.
    val fieldText = (ic.getTextBeforeCursor(AI_PREDICT_CONTEXT_CHARS, 0) ?: "")
        .toString().trimEnd()
    if (fieldText.isBlank()) return
    val token = ++aiSuggestToken
    aiSuggestJob?.cancel()
    aiSuggestJob = serviceScope.launch {
        // Short debounce: the n-gram bar is already up; this only has to
        // outwait a burst of rapid commits (space, punctuation, space).
        delay(AI_PREDICT_DEBOUNCE_MS)
        if (!isActive || token != aiSuggestToken) return@launch
        // Deliberately NOT abandoned when the user has started the next
        // word meanwhile: the answer is keyed to the committed context
        // and doubles as a completion of whatever is being typed.
        val startedAt = SystemClock.uptimeMillis()
        val words = try {
            provider.predictNextWords(AI_PREDICT_FRAME + fieldText, 3)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emptyList()
        }
        // The native call is not cooperatively cancellable, so staleness
        // is decided HERE, on landing.
        if (!isActive || token != aiSuggestToken) return@launch
        val slow = SystemClock.uptimeMillis() - startedAt > AI_PREDICT_ABANDON_MS
        val cleaned = words.asSequence()
            .map { it.first.lowercase() }
            .filter { w -> w.length in 1..20 && w.all { it.isLetter() || it == '\'' } }
            .filter { w -> com.localboard.keyboard.engine.OffensiveWords.allowedFor(w, fastEngine.userFrequency(w)) }
            .filter { w -> !fastEngine.isSuppressed(w) }
            .distinct()
            .take(3)
            .toList()
        cachedAiWords = snapshot to (if (slow) emptyList() else cleaned)
        if (!slow && cleaned.isNotEmpty()) {
            // Re-run the (cheap, debounced) prediction pass so the bar
            // picks the words up through the normal render path. The
            // cache key now matches the snapshot, so this does NOT
            // schedule another model call.
            refreshPredictions()
        }
    }
}

/** Long-press on the sparkle: a small prompt box; the active model drafts.
 *  When the field already holds text, a third choice appears: rewrite it
 *  (rephrase an email, fix tone…) — the draft replaces the old text only
 *  once the user accepts it, so a failed run never eats what they wrote. */
internal fun LLMKeyboardService.showQuickPrompt() {
    if (wifmJob?.isActive == true) return
    if (!keyboardViewInitialized) return
    if (draftingRuledOutHere()) return
    if (offerProIfLocked()) return
    // Instructions need a neural model: the box would open, accept an
    // instruction and have nothing to run it. Say what unlocks it instead.
    if (registry.active() is LocalStyleComposer) {
        offerModelSetup()
        return
    }
    draftBlockReason()?.let { why ->
        showNotice(why, long = true)
        return
    }
    val icNow = currentInputConnection ?: run {
        showNotice(DRAFT_NO_FIELD)
        return
    }
    // A word still being composed may yet be corrected, and a rewrite has to
    // find exactly what it read when it comes back to replace it.
    finishComposingWord()
    val fieldBefore = (icNow.getTextBeforeCursor(REWRITE_MAX_CHARS + 1, 0) ?: "").toString()
    val tooLongToRewrite = fieldBefore.length > REWRITE_MAX_CHARS
    val fieldText = fieldBefore.trim()
    val canRewrite = fieldText.isNotEmpty() && icNow.getSelectedText(0).isNullOrEmpty()
    val modelName = com.localboard.keyboard.llm.ModelRegistry.activeName(prefs)
    // The same things that decide whether a capture happens at all.
    val seesChat = com.localboard.keyboard.context.ScreenBuffer.serviceRunning &&
        prefs.screenStreamEnabled && prefs.mayReadScreenIn(currentAppPackage)
    val context = if (seesChat) "$modelName uses the conversation on screen as context."
        else "$modelName can't see the conversation, so say what it needs to know."
    // Where to go back to. The box is a field of this app's, and takes the keyboard while it is open.
    val origin = currentInputEditorInfo?.packageName
    QuickPromptActivity.pending = QuickPromptRequest(
        message = if (!canRewrite) "Say what to write. $context"
            else "Say what to write, or rewrite the text in the field. $context",
        hint = if (!canRewrite) "e.g. reply that I'm running 10 min late"
            else "e.g. make it shorter / more formal — or say what to write",
        canRewrite = canRewrite,
        tooLongToRewrite = tooLongToRewrite,
        onWrite = { instr -> startWifmWhenReady(instr, fieldPackage = origin) },
        onRewrite = { note ->
            startWifmWhenReady(
                if (note.isNotEmpty()) "Rewrite the message below according to this request: $note."
                else "Rewrite the message below so it reads well.",
                replaceText = fieldText,
                fieldPackage = origin
            )
        },
        onCancel = { bringKeyboardBack(origin) }
    )
    try {
        startActivity(
            Intent(this, QuickPromptActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    } catch (t: Throwable) {
        QuickPromptActivity.pending = null
        recordCaughtError(t, "quick prompt")
        showNotice("Couldn't open the prompt box here.", long = true)
    }
}

private fun LLMKeyboardService.isQuickPromptBox(info: EditorInfo): Boolean =
    info.packageName == packageName && info.privateImeOptions == QuickPromptActivity.BOX_OPTION

/**
 * The app's field, not the box, has the keyboard's connection again. The info stays behind when input
 * ends, so on its own it would still say so after the person has gone to the launcher. With input
 * started and the keyboard up, onStartInputView has run on this field since the box closed.
 */
private fun LLMKeyboardService.fieldIsBack(fieldPackage: String): Boolean {
    val info = currentInputEditorInfo ?: return false
    return currentInputStarted && info.packageName == fieldPackage && !isQuickPromptBox(info) &&
        currentInputConnection != null
}

/** The box was closed with nothing to draft. Whether the keyboard comes back with the app's field
 *  is up to the phone, and someone who only cancelled should not have to tap the field again. */
private fun LLMKeyboardService.bringKeyboardBack(fieldPackage: String?, attempts: Int = 0) {
    if (fieldPackage == null || attempts > FIELD_WAIT_ATTEMPTS) return
    draftState.mainHandler.postDelayed({
        val back = fieldIsBack(fieldPackage)
        if (!back || !isInputViewShown) {
            if (back && attempts % SHOW_KEYBOARD_EVERY == SHOW_KEYBOARD_EVERY - 1) requestShowSelf(0)
            bringKeyboardBack(fieldPackage, attempts + 1)
        }
    }, if (attempts == 0) FIELD_RETURN_MS else 200L)
}

// ------------------------------------------------------------------
// Gates, in the order they are asked
// ------------------------------------------------------------------

/** A password never reaches a model, numeric ones included, and an address or a filter box is no
 *  place for a draft, so the sparkle is not offered in any of them. A field that merely turns
 *  suggestions off has not ruled a draft out. */
private fun draftingAllowedIn(info: EditorInfo): Boolean =
    com.localboard.keyboard.engine.correction.FieldPolicy
        .from(info.inputType, info.imeOptions, mayLearnInApp = false).ai

private fun LLMKeyboardService.draftingRuledOutHere(): Boolean =
    currentInputEditorInfo?.let { !draftingAllowedIn(it) } ?: false

/** Asked before anything else: no one should download a model to find out that drafting costs money.
 *  True when it said so, with a way through to the upgrade screen. */
private fun LLMKeyboardService.offerProIfLocked(): Boolean {
    val feature = com.localboard.keyboard.billing.ProFeature.AI_DRAFTING
    if (com.localboard.keyboard.billing.Pro.has(feature)) return false
    showNotice("AI writing is part of Pro. Tap to see the upgrade.") {
        com.localboard.keyboard.billing.Pro.require(this, feature)
    }
    return true
}

/** What stops drafting in this field right now, in the words to show. Asked once, before anything
 *  runs: a block found mid-run looked like a failed draft and earned a second, misleading message. */
private fun LLMKeyboardService.draftBlockReason(): String? = when {
    !aiAllowedHere -> "AI is turned off for this app"
    else -> com.localboard.keyboard.util.PowerGate.blockReason(this, prefs)
        ?.let { why -> "AI paused: $why" }
}

/**
 * Nothing able to draft is active. The offline composer is not used in its place: it strings
 * learned words together, and that landing in someone's field looks like the model has gone mad.
 */
internal fun LLMKeyboardService.offerModelSetup() {
    val models = com.localboard.keyboard.llm.ModelRegistry
    val onPhone = models.bestReady(this, prefs)
    if (onPhone != null) {
        // Chosen in Settings, or a model is on the phone and nothing has picked it up yet.
        showNotice("Writing is set to the basic composer. Tap to use the ${shortName(onPhone)} model.") {
            prefs.activeModelId = onPhone.id
            prefs.activeProviderId = com.localboard.keyboard.llm.LlamaCppProvider.PROVIDER_ID
            showNotice("Now using the ${shortName(onPhone)} model. Tap the sparkle to draft.")
        }
        return
    }
    val downloading = models.all(prefs).firstOrNull {
        models.state(this, it).status == com.localboard.keyboard.llm.ModelRegistry.Status.DOWNLOADING
    }
    if (downloading != null) {
        val percent = models.state(this, downloading).progress
        showNotice(
            "The ${shortName(downloading)} model is still downloading ($percent%). " +
                "You can keep typing.",
            long = true
        )
        return
    }
    showNotice("AI writing needs a model on this phone. Tap to set up.") { showModelSetup() }
}

private fun shortName(spec: com.localboard.keyboard.llm.ModelSpec): String =
    spec.displayName.substringBefore(" (")

private fun LLMKeyboardService.showModelSetup() {
    if (!keyboardViewInitialized) return
    val models = com.localboard.keyboard.llm.ModelRegistry
    val spec = models.recommended(this)
    val lastError = models.state(this, spec).error
    val message = buildString {
        append("AI writing runs on your phone, so it needs a model there. ")
        append(shortName(spec)).append(" is ").append(spec.sizeLabel)
        append(", one download.")
        if (!lastError.isNullOrBlank()) append("\n\nThe last download failed: ").append(lastError)
    }
    val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_LocalBoard))
        .setTitle("Set up AI writing")
        .setMessage(message)
        .setPositiveButton("Download") { _, _ ->
            models.startDownload(applicationContext, spec)
            watchSetupDownload(spec)
        }
        .setNeutralButton("Choose another") { _, _ -> openModelSettings() }
        .setNegativeButton("Not now", null)
        .create()
    showOverIme(dialog)
}

/**
 * Says how a download started here goes. "Downloading" waits for the site to answer, because the
 * network switch or a dead connection refuses in a moment. A failure says so if the keyboard is up
 * to tell, or it would stay unknown until the next sparkle tap.
 */
private fun LLMKeyboardService.watchSetupDownload(spec: com.localboard.keyboard.llm.ModelSpec) {
    val models = com.localboard.keyboard.llm.ModelRegistry
    serviceScope.launch {
        var announced = false
        while (isActive) {
            val st = models.state(this@watchSetupDownload, spec)
            when (st.status) {
                com.localboard.keyboard.llm.ModelRegistry.Status.DOWNLOADING -> {
                    if (!announced && st.totalBytes != 0L) {
                        announced = true
                        if (isInputViewShown) {
                            showNotice("Downloading the ${shortName(spec)} model. You can keep typing.", long = true)
                        }
                    }
                }
                com.localboard.keyboard.llm.ModelRegistry.Status.ERROR -> {
                    if (isInputViewShown) {
                        showNotice("The ${shortName(spec)} download failed. Tap to see why.") {
                            showModelSetup()
                        }
                    }
                    return@launch
                }
                com.localboard.keyboard.llm.ModelRegistry.Status.READY -> {
                    if (isInputViewShown) {
                        showNotice("The ${shortName(spec)} model is ready. Tap the sparkle to draft.", long = true)
                    }
                    return@launch
                }
                com.localboard.keyboard.llm.ModelRegistry.Status.NOT_DOWNLOADED -> return@launch
            }
            delay(DOWNLOAD_WATCH_MS)
        }
    }
}

/** Settings, with the AI model section open. Settings has no way to be opened at a section, so this
 *  leaves the scrolling to the person, but the list they came for is not folded away. */
internal fun LLMKeyboardService.openModelSettings() {
    prefs.expandedSections = prefs.expandedSections + getString(R.string.sec_ai_model).trim()
    openSettings()
}

/**
 * Whether a reply draft should stop and ask for screen reading first. Without it the prompt has
 * no incoming message, so the model is answering nothing. Someone who said "not now" means it:
 * for a while the sparkle goes straight to a draft from what they have typed.
 */
private fun LLMKeyboardService.shouldOfferScreenReading(): Boolean =
    !com.localboard.keyboard.context.ScreenBuffer.serviceRunning &&
        !prefs.screenOfferNever &&
        System.currentTimeMillis() >= prefs.screenOfferQuietUntil

/** False when it could not open, which must not stand between anyone and their draft. */
private fun LLMKeyboardService.showScreenReadingOffer(): Boolean {
    if (!keyboardViewInitialized) return false
    var shown: AlertDialog? = null
    // A dialog takes the window focus, and the keyboard can start over on the field when it closes.
    val origin = currentInputEditorInfo?.packageName
    val builder = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_LocalBoard))
    if (com.localboard.keyboard.context.ScreenContextService.AVAILABLE) {
        builder.setTitle("Screen reading is off")
            .setMessage(
                "I can't see the messages you're replying to, so a draft could only use " +
                    "what you typed. With screen reading on, I read the chat while this " +
                    "keyboard is open."
            )
            .setView(
                restrictedSettingsGuidance(
                    leaving = { shown?.dismiss() },
                    neverAsk = {
                        prefs.screenOfferNever = true
                        shown?.dismiss()
                        startWifmWhenReady(null, fieldPackage = origin)
                    }
                )
            )
            .setPositiveButton("Turn on") { _, _ ->
                openSystemSettings(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
    } else {
        builder.setTitle("I can't see their messages here")
            .setMessage(
                "This version can't read the screen. Copy their message and paste it here, " +
                    "or say what to write."
            )
            .setPositiveButton("Paste their message") { _, _ ->
                val copied = cachedClipText?.takeIf { it.isNotBlank() }
                if (copied != null) startWifmWhenReady(null, pastedMessage = copied, fieldPackage = origin)
                else showNoticeOnceDialogHasClosed("Nothing is copied yet. To say what to write, hold the sparkle.")
            }
    }
    builder.setNeutralButton("Say what to write") { _, _ ->
        // The dialog held the window focus. Give the field a moment to come back.
        keyboardView.postDelayed({ showQuickPrompt() }, FIELD_RETURN_MS)
    }
    builder.setNegativeButton("Not now") { _, _ ->
        prefs.screenOfferQuietUntil = System.currentTimeMillis() + SCREEN_OFFER_QUIET_MS
        startWifmWhenReady(null, fieldPackage = origin)
    }
    val dialog = builder.create()
    shown = dialog
    val opened = showOverIme(dialog)
    // This version can never read the screen, so one explanation is all that is worth giving.
    if (opened && !com.localboard.keyboard.context.ScreenContextService.AVAILABLE) {
        prefs.screenOfferNever = true
    }
    return opened
}

/** One UI and recent Android grey out the accessibility switch for an app installed from outside
 *  a store, until it has been tried once and allowed in App info. */
private fun LLMKeyboardService.restrictedSettingsGuidance(
    leaving: () -> Unit,
    neverAsk: () -> Unit
): android.view.View {
    val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
    val pad = (20 * resources.displayMetrics.density).toInt()
    return android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, 0)
        addView(android.widget.TextView(ctx).apply {
            text = "If the switch is greyed out: try it once, then open App info, tap the " +
                "three-dot menu and choose Allow restricted settings."
        })
        addView(android.widget.Button(ctx).apply {
            text = "Open App info"
            setOnClickListener {
                leaving()
                openSystemSettings(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)
                    )
                )
            }
        })
        addView(android.widget.Button(ctx).apply {
            text = "Don't ask again"
            setOnClickListener { neverAsk() }
        })
    }
}

private fun LLMKeyboardService.openSystemSettings(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: Throwable) {
        showNotice("Couldn't open that settings page on this phone.", long = true)
    }
}

// ------------------------------------------------------------------
// Write It For Me
// ------------------------------------------------------------------

/** The quick prompt's box takes the keyboard while it's open, which kills the
 *  app's InputConnection, and generating into the OLD one silently dropped
 *  every token (the "quick prompt does nothing" bug). A connection alone proves
 *  nothing here: right after the box closes it may still be the box's, and the
 *  run started on it would be stopped by the app's field coming back. A dialog
 *  over the keyboard has a smaller version of the same trouble. So with a
 *  [fieldPackage] to return to, this waits for that field and for the keyboard
 *  to be up on it, asking for the keyboard if the phone left it down. */
internal fun LLMKeyboardService.startWifmWhenReady(
    instruction: String?,
    attempts: Int = 0,
    replaceText: String? = null,
    pastedMessage: String? = null,
    fieldPackage: String? = null
) {
    if (!keyboardViewInitialized) return
    if (attempts == 0) {
        wifmRetryStopped = false
        draftState.awaitingField = fieldPackage != null
    }
    draftState.mainHandler.postDelayed({
        if (wifmRetryStopped) {
            draftState.awaitingField = false
            return@postDelayed
        }
        val back = if (fieldPackage == null) currentInputConnection != null else fieldIsBack(fieldPackage)
        when {
            back && isInputViewShown -> {
                draftState.awaitingField = false
                if (draftingRuledOutHere()) {
                    // Focus landed on an address bar or the like in that app. Its next field can take this.
                    val held = holdInstruction(instruction, replaceText, fieldPackage)
                    showNotice(if (held) HELD_RULED_OUT else DRAFT_RULED_OUT, long = true)
                } else {
                    startWifm(instruction, replaceText = replaceText, pastedMessage = pastedMessage)
                }
            }
            attempts < (if (fieldPackage != null) FIELD_WAIT_ATTEMPTS else 5) -> {
                if (back && fieldPackage != null && attempts % SHOW_KEYBOARD_EVERY == SHOW_KEYBOARD_EVERY - 1) {
                    requestShowSelf(0)
                }
                startWifmWhenReady(instruction, attempts + 1, replaceText, pastedMessage, fieldPackage)
            }
            else -> {
                draftState.awaitingField = false
                // With the keyboard down there is nowhere to say it. What was typed is kept, and
                // the keyboard offers it back when it next comes up in that app.
                val held = holdInstruction(instruction, replaceText, fieldPackage)
                if (isInputViewShown) showNotice(if (held) HELD_NO_FIELD else DRAFT_NO_FIELD)
            }
        }
    }, if (attempts == 0) FIELD_RETURN_MS else 200L)
}

/**
 * The sparkle, and everything else that drafts. The gates come first and in this order: nobody
 * should fetch a model to learn that drafting costs money, without a model nothing else matters,
 * and sending someone off to turn on screen reading would be for nothing. Each one says why and
 * stops, having touched neither the field nor the bar. The screen-reading offer is the exception
 * if it cannot open: it is only a question, and it must not stand in the way of the draft.
 */
internal fun LLMKeyboardService.startWifm(
    instruction: String? = null,
    targetIc: android.view.inputmethod.InputConnection? = null,
    replaceText: String? = null,
    pastedMessage: String? = null
) {
    if (wifmJob?.isActive == true) return
    if (draftingRuledOutHere()) return
    if (offerProIfLocked()) return
    val plainTap = instruction == null && replaceText == null && pastedMessage == null
    if (plainTap && restoreSetAsideDraft()) return
    if (registry.active() is LocalStyleComposer) {
        offerModelSetup()
        return
    }
    if (plainTap && shouldOfferScreenReading() && showScreenReadingOffer()) return
    draftBlockReason()?.let { why ->
        showNotice(why, long = true)
        return
    }
    val ic = targetIc ?: currentInputConnection ?: run {
        showNotice(DRAFT_NO_FIELD)
        return
    }
    dismissNotice()
    draftState.setAside = null
    draftState.heldInstruction = null
    draftState.runningInstruction = instructionToKeep(instruction, replaceText, currentInputEditorInfo?.packageName)
    forgetGlideAlternates()
    // Before reading the field: finishing a word can change it.
    finishComposingWord()
    val rewriting = replaceText != null
    // Rich context: grab ALL the text the field will give us before the
    // cursor (long drafts are exactly what continuation needs) plus
    // whatever follows (subject lines, quoted replies, the rest of the
    // draft). Editors cap how much they return; the prompt-fitting pass
    // downstream trims to the actual context window.
    // P5: the field-text stream has its own switch — when it's off the
    // model drafts from the screen + typing-log streams alone. A rewrite's
    // field text is its source, and goes in once, as that.
    var contextText = if (prefs.fieldTextEnabled && !rewriting) {
        (ic.getTextBeforeCursor(4000, 0) ?: "").toString()
    } else ""
    val afterText = if (prefs.fieldTextEnabled && !rewriting) {
        (ic.getTextAfterCursor(500, 0) ?: "").toString().trim()
    } else ""

    val generation = ++draftState.generation
    draftState.inReview = false
    // A prediction pass under way, finishing the word above starts one, would land in a moment
    // and take the bar back from the draft.
    predictJob?.cancel()
    predictionBar.setWifmStreaming(DRAFT_STARTING)
    lastWifmDraft = ""
    wifmReplaceText = replaceText
    // Freshen the screen buffer NOW: the last periodic capture may be
    // seconds old, and the draft should see the message that just
    // arrived. The capture walks the view tree on the service's worker
    // thread — give it a beat before assembly reads the buffer.
    // A field that asked not to be remembered gets this too, because the tap
    // is a request, but only for this draft: see forgetDraftScreenRead.
    draftState.screenReadFor = if (incognito) currentAppPackage else null
    val freshCapture = if (pastedMessage == null) {
        com.localboard.keyboard.context.ScreenBuffer.allowRead(currentAppPackage)
        com.localboard.keyboard.context.ScreenContextService.instance?.captureSoon()
    } else null
    wifmJob = serviceScope.launch {
        try {
            // B5: wait for the ACTUAL capture to land (a populated chat
            // routinely walks past the old fixed 280 ms sleep, so the ✨
            // drafted against a seconds-stale buffer). 600 ms cap keeps a
            // wedged service from stalling drafting; no service = no wait.
            if (freshCapture != null) {
                kotlinx.coroutines.withTimeoutOrNull(600) { freshCapture.await() }
            }
            // The model drafts from every context stream available,
            // always merged — and LABELED BY SPEAKER, so it knows which
            // side is the user. An unlabeled screen dump made the model
            // "agree with" the last message on screen ("yes! let's…")
            // instead of writing as the user.
            //   screen:  the app's VISIBLE conversation, both sides
            //            (accessibility service, if the user enabled it)
            //   typing:  what this keyboard watched the user type in
            //            this app (encrypted log) — always the USER's words
            //   field:   the text in the input field itself
            //   app+field-name: folded into the tone line (enrichedAppTone)
            val onScreen = if (pastedMessage != null) {
                "Them: " + pastedMessage.replace('\n', ' ')
            } else {
                com.localboard.keyboard.context.ScreenBuffer.get(currentAppPackage, 1400)
            }
            forgetDraftScreenRead()
            // Screen-blind apps (Signal's screen security, banking apps):
            // the accessibility stream is empty by
            // design, so the user's OWN recent messages in this app
            // become the primary context — give them real depth. When
            // the screen stream works they stay a 500-char side note.
            val screenBlind = onScreen.isBlank()
            val remembered = if (rewriting) "" else contextEngine.recentTypedContext(
                currentAppPackage, if (screenBlind) 1200 else 500
            )
            val mine = mergeContextWindows(contextText, remembered)
            // Diagnostics: how much context each stream contributed.
            com.localboard.keyboard.llm.AiDiagLog.lastScreenChars = onScreen.length
            com.localboard.keyboard.llm.AiDiagLog.lastFieldChars = contextText.length
            com.localboard.keyboard.llm.AiDiagLog.lastTypedChars = remembered.length
            contextText = buildString {
                if (onScreen.isNotBlank()) {
                    append("Chat on my screen:\n")
                    // B6: whole speaker-labeled lines — the B4 "You:"/
                    // "Them:" attribution must survive truncation.
                    append(
                        com.localboard.keyboard.util.takeLastWholeLines(onScreen, 1200)
                    ).append('\n')
                }
                if (mine.isNotBlank()) {
                    // In a blind app the other side is unknowable — say
                    // so, or the model invents the friend's messages.
                    append(
                        if (screenBlind && onScreen.isBlank())
                            "My recent messages in this chat (only my side is visible):\n"
                        else
                            "Messages I sent recently:\n"
                    ).append(mine)
                }
            }.trim()

            // Blank context (empty field, stingy editor, nothing logged
            // yet): the few-shot "Conversation:" prompt has nothing to
            // anchor to and parrots its own examples. The instruction
            // prompt shape has no examples — nothing to parrot.
            val firstInstr = instruction
                ?: if (contextText.isBlank()) BLANK_CONTEXT_INSTRUCTION else null

            // Tokens stream to the prediction bar only, never into the field.
            // A rejected run used to erase text the user had just watched
            // appear ("it wrote, then deleted itself"), and now a failed run
            // touches nothing at all.
            var run = runGeneration(generation, contextText, afterText, firstInstr, replaceText,
                temperature = if (instruction != null) 0.55f else 0.65f)

            // A run that left nothing, or only a parroted prompt line that the
            // sanitizer killed: one more try against the instruction-shaped
            // prompt, because a new shape breaks the parrot pattern and a new
            // seed may not. A short answer is not one of these: "sounds good"
            // is a real reply. Never after a timeout or a cancel, which would
            // only send the phone back into the run it just escaped.
            if (run is DraftRun.Empty && !run.stopped && instruction == null && !rewriting) {
                lastWifmDraft = ""
                predictionBar.setWifmStreaming(DRAFT_STARTING)
                run = runGeneration(generation, contextText, afterText,
                    firstInstr ?: RETRY_INSTRUCTION, null,
                    temperature = 0.8f)
            }
            finishDraftRun(run, generation)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // A genuine crash mid-run: nothing reached the field. Say so.
            if (generation == draftState.generation) {
                leaveDraftUi()
                showNotice("Drafting failed here. Try again.")
            }
        }
    }
}

/** What one run came to. */
private sealed class DraftRun {
    data class Written(val text: String, val finish: Int) : DraftRun()

    /** Nothing usable. [rejected] says what the model wrote, when it wrote anything. */
    data class Empty(
        val rejected: com.localboard.keyboard.llm.OutputSanitizer.Rejected?,
        val stopped: Boolean
    ) : DraftRun()

    data class TimedOut(
        val model: String,
        val stage: com.localboard.keyboard.llm.GenerateStage,
        val afterSeconds: Long
    ) : DraftRun()
    data class CouldNotLoad(val model: String) : DraftRun()
    object TooLong : DraftRun()
    data class Failed(val cause: Throwable) : DraftRun()
}

private fun LLMKeyboardService.finishDraftRun(run: DraftRun, generation: Long) {
    if (generation != draftState.generation) return
    draftState.request = null
    // The rest never got an answer out of the model, so a hide may still keep what they were told to do.
    if (run !is DraftRun.CouldNotLoad && run !is DraftRun.TimedOut && run !is DraftRun.Failed) {
        draftState.runningInstruction = null
    }
    when (run) {
        is DraftRun.Written -> when {
            // Cut off at the token limit, a rewrite has lost the end of what it is replacing.
            wifmReplaceText != null && run.finish == FINISH_TOKEN_CAP -> {
                leaveDraftUi()
                showNotice("The rewrite ran too long and got cut off, so your text was left alone.", long = true)
            }
            else -> {
                // A draft that lands by itself is one more thing to delete by hand when it is
                // wrong, so it waits in the bar. The accent on the check says it is ready.
                lastWifmDraft = run.text
                draftState.inReview = true
                predictionBar.setWifmReview(run.text)
                predictionBar.announceForAccessibility("Draft ready: ${run.text}")
            }
        }
        is DraftRun.Empty -> {
            leaveDraftUi()
            val raw = run.rejected?.raw
            val showable = run.rejected != null && !raw.isNullOrBlank() &&
                run.rejected.reason != com.localboard.keyboard.llm.OutputSanitizer.Reason.VOICE_LEAK
            if (showable) {
                showNotice(noDraftMessage(run.rejected) + " Tap to see what it wrote.") {
                    showWhatItWrote(raw)
                }
            } else {
                showNotice(noDraftMessage(run.rejected), long = true)
            }
        }
        is DraftRun.TimedOut -> {
            com.localboard.keyboard.util.LocalStats.bump(
                com.localboard.keyboard.util.LocalStats.Counter.DRAFT_TIMED_OUT
            )
            leaveDraftUi()
            // "Stopped waiting" and not "stopped it": a prompt being read cannot be interrupted, and
            // the phone goes on working until it ends. The seconds are this stage's, not the run's.
            when (run.stage) {
                com.localboard.keyboard.llm.GenerateStage.WAITING -> showNotice(
                    "The last draft is still stopping, so this one didn't start. Try again in a moment.",
                    long = true
                )
                com.localboard.keyboard.llm.GenerateStage.LOADING -> showNotice(
                    "${run.model} was still loading after ${run.afterSeconds} s, so I stopped waiting. Tap for model settings."
                ) { openModelSettings() }
                com.localboard.keyboard.llm.GenerateStage.READING -> showNotice(
                    "${run.model} was still reading after ${run.afterSeconds} s, so I stopped waiting. Tap for a faster model."
                ) { openModelSettings() }
                com.localboard.keyboard.llm.GenerateStage.WRITING -> showNotice(
                    "${run.model} went quiet for ${run.afterSeconds} s, so I stopped waiting. Tap for a faster model."
                ) { openModelSettings() }
            }
        }
        is DraftRun.CouldNotLoad -> {
            leaveDraftUi()
            val models = com.localboard.keyboard.llm.ModelRegistry
            val other = models.bestReady(this, prefs)?.takeIf { it.id != models.active(prefs).id }
            if (other != null) {
                showNotice("Couldn't load ${run.model}. Tap to use the ${shortName(other)} model.") {
                    prefs.activeModelId = other.id
                    prefs.activeProviderId = com.localboard.keyboard.llm.LlamaCppProvider.PROVIDER_ID
                    showNotice("Now using the ${shortName(other)} model. Tap the sparkle to draft.")
                }
            } else {
                showNotice("Couldn't load ${run.model}: low memory or a damaged file. Tap for model settings.") {
                    openModelSettings()
                }
            }
        }
        DraftRun.TooLong -> {
            leaveDraftUi()
            showNotice("That is too long for this model. Nothing changed.", long = true)
        }
        is DraftRun.Failed -> {
            leaveDraftUi()
            showNotice("Drafting failed here. Try again.")
        }
    }
}

private fun LLMKeyboardService.noDraftMessage(
    rejected: com.localboard.keyboard.llm.OutputSanitizer.Rejected?
): String {
    val models = com.localboard.keyboard.llm.ModelRegistry
    // Repeated failures on a tiny model say more about the model than the chat.
    if (models.isTinyModel(models.active(prefs))) {
        return "This small model often can't draft. ${shortName(models.recommended(this))} " +
            "does better (Settings, AI model)."
    }
    return when (rejected?.reason) {
        com.localboard.keyboard.llm.OutputSanitizer.Reason.ECHO -> "The model only repeated the chat."
        com.localboard.keyboard.llm.OutputSanitizer.Reason.SCAFFOLDING -> "The model wrote out its instructions."
        com.localboard.keyboard.llm.OutputSanitizer.Reason.VOICE_LEAK ->
            "The draft copied one of your other chats, so I dropped it."
        else -> "The model came back with nothing. Try again."
    }
}

private fun LLMKeyboardService.showWhatItWrote(raw: String) {
    if (!keyboardViewInitialized) return
    val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_LocalBoard))
        .setTitle("What the model wrote")
        .setMessage(raw)
        .setPositiveButton(android.R.string.ok, null)
        .create()
    showOverIme(dialog)
}

/**
 * What a draft read off the screen for a field that asked not to be remembered goes as soon as
 * the draft has it, and on every way out before that. The pending walk is called off first so it
 * cannot add it back, and the title goes too, or the next thing typed would be logged under it.
 */
private fun LLMKeyboardService.forgetDraftScreenRead() {
    val pkg = draftState.screenReadFor ?: return
    draftState.screenReadFor = null
    com.localboard.keyboard.context.ScreenContextService.instance?.cancelPending()
    com.localboard.keyboard.context.ScreenBuffer.forget(pkg)
}

/** A dialog closing can start the keyboard over on the field, and that clears any notice. So what a
 *  dialog's button has to say waits until that would have happened. */
internal fun LLMKeyboardService.showNoticeOnceDialogHasClosed(text: CharSequence) {
    draftState.mainHandler.postDelayed({ if (isInputViewShown) showNotice(text, long = true) }, FIELD_RETURN_MS)
}

/** Back to the ordinary bar, with no draft held anywhere. */
private fun LLMKeyboardService.leaveDraftUi(refresh: Boolean = true) {
    forgetDraftScreenRead()
    dismissDraftReader()
    draftState.inReview = false
    draftState.slowOffered = false
    draftState.request = null
    lastWifmDraft = ""
    wifmReplaceText = null
    predictionBar.showSuggestions(emptyList())
    if (refresh) {
        updateAutoCaps()
        refreshPredictions()
    }
}

/** B7: register from observed signal when the package map draws a blank. */
internal fun LLMKeyboardService.derivedToneFallback(): String {
    val info = currentInputEditorInfo
    if (info != null) {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) return "a polished, friendly email"
        val hint = info.hintText?.toString()?.lowercase() ?: ""
        if ("subject" in hint || "email" in hint || "e-mail" in hint) {
            return "a polished, friendly email"
        }
        if ("search" in hint) return "a short search query"
    }
    return "a chat message"
}

/** Merge the field's visible text with the keyboard's own typing log,
 *  removing the overlap — the log usually ends exactly where the field
 *  text began, so a naive concat would show the model the same line
 *  twice. */
internal fun LLMKeyboardService.mergeContextWindows(screen: String, remembered: String): String {
    val s = screen.trim()
    val r = remembered.trim()
    if (r.isEmpty()) return s
    if (s.isEmpty()) return r
    if (r.contains(s)) return r
    if (s.contains(r)) return s
    val maxK = minOf(r.length, s.length, 400)
    var k = maxK
    while (k >= 24) {
        if (r.regionMatches(r.length - k, s, 0, k, ignoreCase = false)) {
            // B6: drop whole messages from the front, never mid-message.
            return com.localboard.keyboard.util.takeLastWholeLines(
                r + s.substring(k), 2400
            )
        }
        k--
    }
    return com.localboard.keyboard.util.takeLastWholeLines("$r\n$s", 2400)
}

/** The venue for the draft, folded into the tone line of the system
 *  prompt: the app's human-readable name plus the input field's own
 *  hint ("Message John", "Subject", "Search"). Tiny models anchor
 *  noticeably better when told WHERE they are writing instead of
 *  guessing it — and the system block is never context-truncated. */
internal fun LLMKeyboardService.enrichedAppTone(): String {
    // B7: the package map is a hint, not the law — "com.slack" was simply
    // wrong (it's com.Slack), and every unlisted mail client fell through
    // to texting register. When the map doesn't know the app, derive the
    // register from the FIELD's own signals: an email-variation inputType
    // or a "Subject" hint means email, whatever the package is called.
    val base = appToneFor(currentAppPackage) ?: derivedToneFallback()
    val bits = ArrayList<String>(2)
    if (currentAppPackage.isNotEmpty() && currentAppPackage != packageName) {
        val label = appLabelCache.getOrPut(currentAppPackage) {
            try {
                val ai = packageManager.getApplicationInfo(currentAppPackage, 0)
                packageManager.getApplicationLabel(ai).toString().trim()
            } catch (_: Throwable) { "" }
        }
        if (label.isNotEmpty() && label.length <= 40) bits.add("in $label")
    }
    val hint = currentInputEditorInfo?.hintText?.toString()?.trim()
    if (!hint.isNullOrEmpty() && hint.length <= 60) bits.add("field \"$hint\"")
    if (bits.isEmpty()) return base
    return "$base " + bits.joinToString(", ")
}

/**
 * One generation, shown in the bar and never written to the field. Returns when it finishes or
 * when the watchdog gives up on it, whichever comes first.
 *
 * The work runs beside this coroutine rather than inside it. Native code cannot be interrupted,
 * and a coroutine cannot finish before its children do, so anything waiting on a child, a timeout
 * included, waits for the native call to come back. That left the placeholder up for minutes with
 * nobody told. This way the user hears at the deadline, and the engine is told to stop: a run in
 * its writing phase stops at the next token, and one still reading the prompt stops when that ends.
 */
private suspend fun LLMKeyboardService.runGeneration(
    generation: Long,
    contextText: String,
    afterText: String,
    instruction: String?,
    rewriteSource: String?,
    temperature: Float
): DraftRun {
    val provider = registry.active()
    val handler = draftState.mainHandler
    val clock = com.localboard.keyboard.llm.DraftClock(SystemClock.elapsedRealtime())
    val isCurrent = { generation == draftState.generation }
    val stream = DraftStream()
    val events = object : com.localboard.keyboard.llm.GenerateEvents {
        // Not the clock's stage, which starts at loading: the first load would never get said.
        private var reported: com.localboard.keyboard.llm.GenerateStage? = null

        override fun onStage(stage: com.localboard.keyboard.llm.GenerateStage) {
            val changed = stage != reported
            reported = stage
            clock.enter(stage, SystemClock.elapsedRealtime())
            if (changed && stage != com.localboard.keyboard.llm.GenerateStage.WRITING) {
                handler.post {
                    if (stream.live && isCurrent() && lastWifmDraft.isEmpty()) {
                        draftState.slowOffered = false
                        predictionBar.setWifmStreaming(statusFor(stage, rewriteSource != null))
                    }
                }
            }
        }

        override fun onRejected(rejected: com.localboard.keyboard.llm.OutputSanitizer.Rejected) {
            stream.rejected = rejected
        }

        override fun onFinished(code: Int) {
            stream.finish = code
        }
    }
    val request = GenerateRequest(
        contextBefore = contextText,
        // The auto-learned profile is gated: a skewed sample
        // (e.g. a typing log full of one-word replies) makes
        // every draft a mumble — measurably worse than no
        // profile. The test lab's A/B pass shows it live.
        styleProfile = contextEngine.profile
            .takeIf { prefs.llmUseStyleProfile },
        maxWords = when {
            // Room to say everything the source says, and a little over.
            rewriteSource != null -> (WORD_SPLIT.split(rewriteSource).size * 3 / 2).coerceIn(30, 170)
            instruction != null -> 60
            else -> 36
        },
        temperature = temperature,
        contextAfter = afterText.ifEmpty { null },
        appTone = enrichedAppTone(),
        styleInstructions = prefs.customStylePrompt.ifBlank { null },
        instruction = instruction,
        personalityNotes = if (prefs.llmUseStyleProfile)
            contextEngine.personalityNotes.ifBlank { null } else null,
        // Same gate as the writing notes: both are distilled
        // from the private log, so one switch governs whether
        // any of it reaches a prompt.
        personaNotes = if (prefs.llmUseStyleProfile)
            contextEngine.personaNotes.ifBlank { null } else null,
        // Real recent messages as voice examples — the
        // difference between "sounds like a generic
        // assistant" and "sounds like me". Same gate as the
        // profile; they come from the same typing log.
        voiceExamples = if (prefs.llmUseStyleProfile)
            try {
                contextEngine.voiceExamples(currentAppPackage, 3)
                    .ifEmpty { null }
            } catch (t: Throwable) { null }
        else null,
        rewriteSource = rewriteSource,
        userWaiting = true,
        // What was read for this draft is forgotten as soon as the prompt has it, and that promise
        // covers the copy the diagnostics would keep.
        doNotRecordPrompt = incognito,
        events = events
    )
    draftState.request = request
    val modelName = shortName(com.localboard.keyboard.llm.ModelRegistry.active(prefs))
    val work = serviceScope.async {
        provider.generate(request) { token ->
            // On the decode thread, so the view waits for the main thread. By then this run
            // may have been dropped, and it must not put the bar back into draft mode.
            if (stream.live && isCurrent()) {
                val snapshot = stream.add(token, SystemClock.uptimeMillis())
                if (snapshot != null) {
                    handler.post {
                        if (stream.live && isCurrent()) {
                            draftState.slowOffered = false
                            lastWifmDraft = snapshot
                            predictionBar.setWifmStreaming(snapshot)
                        }
                    }
                }
            }
        }
    }
    // Once for each stage: a slow load says nothing about whether reading the chat is slow too.
    var slowIn: com.localboard.keyboard.llm.GenerateStage? = null
    val timedOut = try {
        clock.runsPastDeadline(work, WATCHDOG_TICK_MS, SystemClock::elapsedRealtime) { now ->
            val stage = clock.stage
            if (slowIn != stage && clock.isSlow(now) && lastWifmDraft.isEmpty()) {
                slowIn = stage
                draftState.slowOffered = true
                predictionBar.setWifmStreaming(DRAFT_SLOW)
            }
        }
    } catch (c: kotlinx.coroutines.CancellationException) {
        // Dropped by the user or by the keyboard hiding. Tell the engine, since nothing else will.
        stream.live = false
        provider.cancel()
        work.cancel()
        throw c
    }
    stream.live = false
    if (timedOut) {
        val stage = clock.stage
        val limitSeconds = clock.limitMs() / 1000
        provider.cancel()
        work.cancel()
        // The stopped run logs its own line when native finally lets go. This line is the event,
        // not a second run, and it is here because that one may be a long time coming.
        com.localboard.keyboard.llm.AiDiagLog.add(
            com.localboard.keyboard.llm.AiDiagLog.Entry(
                at = System.currentTimeMillis(),
                model = modelName,
                purpose = "timeout",
                promptTokens = 0, reusedTokens = 0, promptMs = 0,
                genTokens = 0, genMs = 0, finish = -1,
                note = "in ${stage.name.lowercase()} after ${limitSeconds}s: stopped, no retry"
            )
        )
        return DraftRun.TimedOut(modelName, stage, limitSeconds)
    }
    return try {
        val text = work.await()
        if (text.isBlank()) DraftRun.Empty(stream.rejected, stopped = stream.finish == FINISH_CANCELLED)
        else DraftRun.Written(text, stream.finish)
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (e: com.localboard.keyboard.llm.PromptTooLongException) {
        DraftRun.TooLong
    } catch (e: com.localboard.keyboard.llm.ModelLoadException) {
        DraftRun.CouldNotLoad(modelName)
    } catch (t: Throwable) {
        com.localboard.keyboard.llm.AiDiagLog.add(
            com.localboard.keyboard.llm.AiDiagLog.Entry(
                at = System.currentTimeMillis(),
                model = modelName,
                purpose = if (instruction != null) "quick prompt" else "draft",
                promptTokens = 0, reusedTokens = 0, promptMs = 0,
                genTokens = 0, genMs = 0, finish = -1,
                note = "engine error: ${t.javaClass.simpleName}"
            )
        )
        DraftRun.Failed(t)
    }
}

/**
 * The text so far of one run. Only the decode thread touches it, and pushes to the bar are thinned:
 * tokens arrive faster than the eye reads, and every one of them would be a redraw for nothing.
 */
private class DraftStream {
    @Volatile var live = true
    @Volatile var rejected: com.localboard.keyboard.llm.OutputSanitizer.Rejected? = null
    @Volatile var finish = -1
    private val text = StringBuilder()
    private var lastPushAt = 0L

    /** The whole text when it is time to show it, otherwise null. */
    fun add(token: String, now: Long): String? {
        text.append(token)
        if (now - lastPushAt < STREAM_PUSH_MS) return null
        lastPushAt = now
        return text.toString()
    }
}

private fun statusFor(stage: com.localboard.keyboard.llm.GenerateStage, rewriting: Boolean): String = when (stage) {
    com.localboard.keyboard.llm.GenerateStage.LOADING -> "✨ Loading the model…"
    com.localboard.keyboard.llm.GenerateStage.WAITING -> "✨ Waiting for the last draft to stop…"
    com.localboard.keyboard.llm.GenerateStage.READING -> if (rewriting) "✨ Reading your text…" else "✨ Reading the chat…"
    com.localboard.keyboard.llm.GenerateStage.WRITING -> DRAFT_STARTING
}

/** The check: into the field it goes, whether the draft is finished or still streaming. */
internal fun LLMKeyboardService.confirmWifm() {
    val ic = currentInputConnection ?: run {
        showNotice(DRAFT_NO_FIELD)
        return
    }
    val running = wifmJob?.isActive == true
    val replace = wifmReplaceText
    // Half a rewrite cannot replace the whole text.
    if (running && replace != null) {
        showNotice("Still rewriting. Wait for it to finish, or tap the cross to stop.")
        return
    }
    val request = draftState.request
    val partial = lastWifmDraft
    // What has streamed so far has not been through the checks a finished draft gets, and the
    // check mark must not be a way round them. Nothing usable yet leaves the run going: stopping
    // would throw away the wait so far, when only the cross is meant to do that.
    val drafted = if (running) {
        val usable = if (partial.isEmpty()) null else request?.review(partial)
        if (usable !is com.localboard.keyboard.llm.OutputSanitizer.Accepted) {
            showNotice("Nothing usable yet. Wait for it to finish, or tap the cross to stop.")
            return
        }
        usable.text
    } else partial
    stopDraftRun()
    forgetGlideAlternates()
    finishComposingWord()
    var landed = ""
    beginSelfEdit()
    if (drafted.isNotBlank()) {
        if (replace != null) {
            val before = (ic.getTextBeforeCursor(replace.length + REWRITE_SPARE_CHARS, 0) ?: "").toString()
            val toDelete = com.localboard.keyboard.llm.DraftText.charsToReplace(before, replace)
            if (toDelete != null) {
                ic.deleteSurroundingText(toDelete, 0)
                ic.commitText(drafted, 1)
                landed = drafted
            }
        } else {
            val before = (ic.getTextBeforeCursor(INSERT_LOOKBACK_CHARS, 0) ?: "").toString()
            val after = (ic.getTextAfterCursor(1, 0) ?: "").toString()
            landed = com.localboard.keyboard.llm.DraftText.forInsertion(before, drafted, after)
            if (landed.isNotEmpty()) ic.commitText(landed, 1)
        }
    }
    endSelfEdit()
    if (landed.isNotBlank()) {
        recordWords(landed.trim(), aiOrigin = true)
        com.localboard.keyboard.util.LocalStats.bump(
            com.localboard.keyboard.util.LocalStats.Counter.DRAFT_ACCEPTED
        )
        // The field holds a message now, so the bar goes on from it rather than offering to write
        // a first one, and a correction from before the draft is no longer there to revert.
        correctionSession.onTypedPastFix()
        typedThisField = true
    } else if (drafted.isNotBlank()) {
        showNotice(
            if (replace != null) "The text has changed since, so nothing was replaced."
            else "The draft only repeated what is already there.",
            long = true
        )
    }
    leaveDraftUi()
}

/** The cross. Nothing was written to the field, so there is nothing to take back. */
internal fun LLMKeyboardService.discardWifm() {
    countDiscard()
    stopDraftRun()
    leaveDraftUi()
}

/**
 * Text is going into the field, so the person has moved on: their next word would not be in the
 * draft anyway, and the bar is needed for predictions. A run still going is dropped. A finished
 * draft took a long wait to get, and a thumb landing a little low on a key should not cost it, so
 * it goes aside, within reach of this notice and, after that, of the sparkle.
 */
internal fun LLMKeyboardService.endDraftForTyping() {
    if (!holdingDraft()) return
    val text = lastWifmDraft
    val replaceText = wifmReplaceText
    val finished = wifmJob?.isActive != true && draftState.inReview && text.isNotEmpty()
    discardWifm()
    if (!finished) return
    val info = currentInputEditorInfo ?: return
    // The key that pushed the draft aside has not landed yet, so this is the text the draft was written after.
    val before = (currentInputConnection?.getTextBeforeCursor(INSERT_LOOKBACK_CHARS, 0) ?: "").toString()
    draftState.setAside = SetAsideDraft(
        text, replaceText, info.packageName, info.fieldId, SystemClock.elapsedRealtime() + SET_ASIDE_MS, before
    )
    showNotice("Draft set aside. Tap to bring it back.") {
        if (!restoreSetAsideDraft()) showNotice("That draft no longer fits what is in this field, so it's gone.")
    }
}

/** The bar holds the draft again, as it was, a rewrite still a rewrite. False if there is none to bring back here. */
private fun LLMKeyboardService.restoreSetAsideDraft(): Boolean {
    val kept = draftState.setAside ?: return false
    draftState.setAside = null
    val info = currentInputEditorInfo ?: return false
    if (SystemClock.elapsedRealtime() > kept.until) return false
    if (info.packageName != kept.pkg || info.fieldId != kept.fieldId) return false
    // The same box can be holding another chat by now. Read with room for what was typed since.
    val before = (currentInputConnection?.getTextBeforeCursor(INSERT_LOOKBACK_CHARS * 2, 0) ?: "").toString()
    if (!com.localboard.keyboard.llm.DraftText.stillFollows(before, kept.textBefore, INSERT_LOOKBACK_CHARS)) return false
    // What was typed meanwhile started a prediction pass, which would land and take the bar back.
    predictJob?.cancel()
    cancelAiSuggestion()
    dismissNotice()
    lastWifmDraft = kept.text
    wifmReplaceText = kept.replaceText
    draftState.inReview = true
    predictionBar.setWifmReview(kept.text)
    return true
}

private fun LLMKeyboardService.holdingDraft(): Boolean =
    wifmJob?.isActive == true || (predictionBarInitialized &&
        predictionBar.currentMode() == com.localboard.keyboard.ui.PredictionBarView.Mode.WIFM_CONFIRM)

private fun LLMKeyboardService.countDiscard() {
    if (wifmJob?.isActive == true || draftState.inReview) {
        com.localboard.keyboard.util.LocalStats.bump(
            com.localboard.keyboard.util.LocalStats.Counter.DRAFT_DISCARDED
        )
    }
}

/** Drops whatever is running. The run tells the engine itself, because only it knows whether
 *  the engine is working on anything yet. */
private fun LLMKeyboardService.stopDraftRun() {
    draftState.generation++
    draftState.runningInstruction = null
    wifmRetryStopped = true
    wifmJob?.cancel()
}

internal fun LLMKeyboardService.onHideCancelDraft() {
    // A retry must not fire into a hidden keyboard, except the one waiting out this very moment.
    if (!draftState.awaitingField) wifmRetryStopped = true
    // This stops the run, or takes away the notice saying it failed. Either way nothing has told the person
    // what became of their words, and the box they typed them into is gone. A notice that has been up and gone did tell them.
    if (wifmJob?.isActive == true || layoutState.notice.showing) {
        // A fresh copy, because the time to come back runs from leaving, and a slow run may have used it up already.
        draftState.runningInstruction?.let {
            draftState.heldInstruction = instructionToKeep(it.instruction, it.replaceText, it.pkg)
        }
    }
    draftState.runningInstruction = null
    if (holdingDraft()) {
        countDiscard()
        stopDraftRun()
        // Without this, a hidden keyboard comes back with its bar frozen on a dead draft.
        leaveDraftUi(refresh = false)
    }
    draftState.setAside = null
    forgetDraftScreenRead()
    cancelAiSuggestion()
    dismissNotice()
}

internal fun LLMKeyboardService.onHideStopScreenCapture() {
    // Keyboard hidden: screen capture stops, and anything buffered for the
    // encrypted store (typing log, usage counters, n-grams) goes to disk
    // in one batch instead of trickling out per word while typing.
    com.localboard.keyboard.context.ScreenBuffer.imeVisible = false
    com.localboard.keyboard.context.ScreenContextService.instance?.cancelPending()
}
