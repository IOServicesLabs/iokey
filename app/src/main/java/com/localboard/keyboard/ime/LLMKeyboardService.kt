package com.localboard.keyboard.ime

import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.localboard.keyboard.R
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.HybridRouter
import com.localboard.keyboard.engine.SwipeDecoder
import com.localboard.keyboard.engine.TrackedEditor
import com.localboard.keyboard.llm.ProviderRegistry
import com.localboard.keyboard.ui.EmojiPanel
import com.localboard.keyboard.ui.KeyboardTheme
import com.localboard.keyboard.ui.KeyboardView
import com.localboard.keyboard.ui.PredictionBarView
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The input method as the framework sees it: its fields, and the overrides
 * that call into the rest. The input-connection pipeline, composing state,
 * prediction scheduling, swipe commits, the Write-It-For-Me flow and the
 * learning hooks live in the Ime*.kt files beside this one, as extensions on
 * this class, which is why everything here is internal rather than private.
 */
class LLMKeyboardService : InputMethodService() {

    internal lateinit var prefs: Prefs
    internal val snippets by lazy { com.localboard.keyboard.util.Snippets(prefs) }
    internal lateinit var fastEngine: FastEngine
    internal lateinit var contextEngine: ContextEngine
    internal lateinit var router: HybridRouter
    internal lateinit var registry: ProviderRegistry
    internal var swipeDecoder: SwipeDecoder? = null

    internal lateinit var keyboardView: KeyboardView
    internal lateinit var predictionBar: PredictionBarView
    internal lateinit var container: FrameLayout
    internal lateinit var emojiRow: android.widget.LinearLayout
    internal lateinit var navStrip: com.localboard.keyboard.ui.NavStripView
    internal var emojiPanel: EmojiPanel? = null
    internal var theme: KeyboardTheme? = null

    // isInitialized on a lateinit only compiles where its backing field is in
    // lexical scope, which an extension in another file is not.
    internal val keyboardViewInitialized: Boolean get() = ::keyboardView.isInitialized
    internal val predictionBarInitialized: Boolean get() = ::predictionBar.isInitialized
    internal val navStripInitialized: Boolean get() = ::navStrip.isInitialized

    internal val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + coroutineCrashHandler())
    internal var predictJob: Job? = null
    internal var wifmJob: Job? = null
    internal var unloadJob: Job? = null

    // One holder per Ime*.kt file, for state only that file uses. New fields go
    // there rather than here, so this class does not have to change with them.
    internal val windowState = WindowState()
    internal val textState = TextState()
    internal val autocorrectState = AutocorrectState()
    internal val suggestionState = SuggestionState()
    internal val glideState = GlideState()
    internal val draftState = DraftState()
    internal val learningState = LearningState()
    internal val touchState = TouchState()
    internal val engineState = EngineState()
    internal val layoutState = LayoutState()
    internal val emojiState = EmojiState()
    internal val clipboardState = ClipboardState()

    // --- Input state ---

    // Everything this keyboard writes goes through here, so it knows where the caret should be.
    // This is a property of its own, and on purpose not an override of the framework's method of
    // that name. On a configuration change the framework hands that method's answer to doStartInput
    // and keeps it, and invalidateInput does nothing unless what it kept is the framework's own
    // connection class, so after a rotation or a dark mode flip an app's setText would leave the
    // keyboard typing into nothing. Code in ime/ must use this property. Calling the method by name
    // also compiles, and silently skips the tracking.
    internal val trackedEditor = TrackedEditor { SystemClock.uptimeMillis() }
    private var trackedRaw: InputConnection? = null
    private var trackedConnection: InputConnection? = null

    internal val currentInputConnection: InputConnection?
        get() {
            val raw = super.getCurrentInputConnection() ?: return null
            if (raw !== trackedRaw) {
                trackedRaw = raw
                trackedConnection = TrackedInputConnection(raw, trackedEditor, trackedEditor.onNewConnection())
            }
            return trackedConnection
        }

    internal val composing = StringBuilder()
    /** A4: raw tap points, index-aligned with [composing]. Valid only when
     *  every letter arrived with a point (plain taps — never voice/paste). */
    internal val composingTrace = ArrayList<android.graphics.PointF>()
    internal var composingTraceValid = true
    internal val committedWords = ArrayDeque<String>()
    /** App-label lookups are binder calls — cache them (few entries, session-lived). */
    internal val appLabelCache = HashMap<String, String>(8) // recent committed words
    internal var lastSpaceCommitAt = 0L

    /** Word committed by a swipe decode or a prediction tap (plus whether an
     *  auto-space followed it). Backspace right after wipes the whole word —
     *  char-by-char deletion of a wrong gesture word leaves misleading
     *  fragments ("becahse" -> "becahs") that poison predictions. */
    internal var lastGestureCommit: Pair<String, Boolean>? = null

    /** That word came from a glide and not from a tap on the bar. Only wiping a glide says
     *  anything about what the decoder got wrong. */
    internal var lastGestureWasGlide = false

    /** An automatic space (after a glide, a prediction tap, or a
     *  punctuation swap) sits right before the cursor and nothing has been
     *  typed since. Punctuation typed now swaps in front of it, Gboard-style
     *  ("hello ," never happens), and a redundant manual space is absorbed.
     *  Always re-verified against the field text before acting. */
    internal var autoSpacePending = false

    /**
     * The alternate for the glide word just committed, held on the bar beside
     * the next words until the user does anything else, so that a wrong glide
     * can be fixed with a tap — the single most common glide interaction.
     */
    internal var glideAlternates: List<Suggestion>? = null

    /** The word learning most recently picked up (and when). Backspacing back
     *  INTO it within a few seconds means it was a typo being fixed, not a
     *  word worth keeping — see the DELETE handler. */
    internal var lastLearnedWord: String? = null
    internal var lastLearnedAt = 0L

    /** Set from onDestroy so a pending WIFM retry (keyboardView.postDelayed)
     *  dies with the service instead of firing into a torn-down IME. */
    internal var wifmRetryStopped = false

    internal var predictionsEnabled = true
    /** The field asked not to be learned from (IME_FLAG_NO_PERSONALIZED_LEARNING).
     *  Reads are fine; every write path checks this. */
    internal var incognito = false
    /** Current field wants an email address (TYPE_TEXT_VARIATION_EMAIL_ADDRESS /
     *  WEB_EMAIL_ADDRESS). There, address characters (.@_- and digits) extend
     *  the composing token instead of committing word fragments, and learned
     *  email completions are surfaced on the bar. */
    internal var isEmailField = false
    internal var currentAppPackage = ""
    internal var showingEmoji = false

    /** Cursor/selection pad is up instead of the letter grid. */
    internal var showingSelection = false

    /** Per-app rule: may the AI draft/rewrite in the current field? */
    internal var aiAllowedHere = true

    /** Refresh rate currently requested for the keyboard window, 0 = system. */
    internal var requestedHz = 0f

    internal var symbolsPage = 0 // 0 letters, 1 symbols1, 2 symbols2
    internal var numberMode = false
    internal var selfEditing = false // guard for onUpdateSelection
    internal var lastShiftTapMs = 0L

    // BUGFIX (text erased while typing): editors deliver the onUpdateSelection
    // echo of our own edits ASYNCHRONOUSLY (posted to the IME main thread after
    // the IC call returns). Resetting selfEditing synchronously let that echo
    // wipe the composing buffer, so the next keystroke replaced the whole word
    // with one letter. We now hold the flag for a short window instead. An
    // editor that reports offsets is judged by the tracker and not by this
    // clock: the flag is for one that does not, where nothing can be predicted.
    internal val selfEditHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal val selfEditReset = Runnable { selfEditing = false }
    internal val regionGoneCheck = Runnable { checkRegionGone() }
    internal val quietCheck = Runnable { checkAfterQuiet() }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        trackedEditor.onWrite = { scheduleQuietCheck() }
        startEngines()
        startClipboardWatch()
        warmEmojiCatalog()
    }

    /** P3: never go fullscreen-extract in landscape — the keyboard has its
     *  own suggestion bar and AI affordances, and the OS extract bar hid
     *  them all behind a cramped one-line field. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** The input view, kept so [onComputeInsets] can measure it. */
    internal var inputRoot: View? = null

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        computeTouchableInsets(outInsets)
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.keyboard_root, null)
        inputRoot = root
        predictionBar = root.findViewById(R.id.prediction_bar)
        container = root.findViewById(R.id.keyboard_container)
        emojiRow = root.findViewById(R.id.emoji_row)
        navStrip = root.findViewById(R.id.nav_strip)

        wireWindowInsets(root)

        theme = KeyboardTheme.loadCached(this, prefs).also { t ->
            predictionBar.setTheme(t)
        }

        keyboardView = KeyboardView(this)
        keyboardView.onKeyPressed = { if (::navStrip.isInitialized) navStrip.onKeyPress() }
        // SwipeDecoder captures this view's KeyboardGeometry for its whole
        // lifetime, so it must not outlive the view. After a rotation the old
        // geometry holds key centres measured at the previous screen width and
        // every glide decodes against a keyboard that is no longer there.
        // Dropping it makes the lazy rebuild in onStartInputView bind to the
        // new view; the learned bias is restored from prefs there anyway.
        swipeDecoder = null
        container.addView(keyboardView)
        wireKeyboard()
        wirePredictionBar()
        applyKeyboardModel()
        return root
    }

    /** The bar stays empty until the user has actually typed in this field —
     *  starter-word guesses on a fresh/empty field read as noise. Set on the
     *  first real input; cleared on a field change (restarts keep it). */
    internal var typedThisField = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        val showStartedAt = SystemClock.uptimeMillis()
        super.onStartInputView(info, restarting)
        onShowTrackEditor(info)
        // One answer for the whole show, because nothing here writes to the field before the last place that asks.
        val emptyOnRestart = restarting && fieldIsEmpty()
        onShowFlushMessageLog(info, restarting, emptyOnRestart)
        onShowCancelModelUnload(info, restarting)
        // Field (re)opened: any AI words on hand belonged to the old context.
        cancelAiSuggestion()

        onShowApplyKeyboardPrefs(info, restarting)
        onShowRequestInsets(info, restarting)
        applyLiveBackground()
        applyRefreshRatePolicy(gliding = false)
        onShowApplyKeyMetrics(info, restarting)
        onShowApplyGesturePrefs(info, restarting)
        applyKeyboardBackground()

        onShowResetFieldState(info, restarting, emptyOnRestart)
        onShowMirrorRedaction(info, restarting)
        onShowApplyKeyboardModel(info, restarting)
        refreshEmojiRow()
        onShowPrepareSwipeDecoder(info, restarting)
        updateClipboardChip()

        onShowStartScreenCapture(info, restarting)
        refreshVoiceVisibility()

        onShowApplyAutoCaps(info, restarting)

        onShowLoadAppContext(info, restarting)
        onShowRefreshPredictions(info, restarting)
        com.localboard.keyboard.util.PhoneStats.noteFieldShow(SystemClock.uptimeMillis() - showStartedAt)
    }

    /** Slot under the finger during a swipe-up word pick; -1 = none. */
    internal var lastBarHoverSlot = -1

    /** P2: full clipboard text behind the bar's paste chip ("" = no chip). */
    internal var clipboardFullText: String = ""

    /** Clipboard cache, maintained by [clipChangeListener] so the prediction
     *  refresh path never touches the binder. Updated on every primary-clip
     *  change and once at service start. */
    internal var clipboardManager: android.content.ClipboardManager? = null
    internal var cachedClipText: String? = null
    internal var cachedClipTs: Long = 0L

    internal val clipChangeListener = createClipChangeListener()

    internal val clipHistory by lazy { com.localboard.keyboard.util.ClipHistory(prefs) }
    internal var lastRecordedClip: String? = null

    // ------------------------------------------------------------------
    // Offline voice typing (whisper.cpp, fully on-device)
    // ------------------------------------------------------------------

    internal val voiceModels by lazy {
        com.localboard.keyboard.llm.VoiceModelManager(applicationContext)
    }
    internal var voiceInput: VoiceInputManager? = null

    /** Push the user's wallpaper (down-sampled) or live-gradient choice to
     *  the keyboard view. The decode runs once and is cached — re-decoding a
     *  1080px bitmap on every keyboard show was the costliest thing on the
     *  show path. */
    internal var wallpaperCacheKey: String? = null
    internal var wallpaperCacheBmp: android.graphics.Bitmap? = null

    /** Caret offset the selection is being dragged FROM; -1 = not selecting. */
    internal var selectionAnchor: Int = -1

    /** Live selection, mirrored from onUpdateSelection / EditorInfo. -1 = unknown
     *  (an editor that reports nothing, e.g. some WebViews). */
    internal var selStart: Int = -1
    internal var selEnd: Int = -1

    /** Timestamp of the last prediction request, for burst detection. */
    internal var lastKeystrokeAt: Long = 0L

    internal var aiSuggestJob: kotlinx.coroutines.Job? = null

    /** Generation guard, same idea as [swipePreviewToken]: bumped on every
     *  keystroke/commit/cancel so a late-landing result is dropped even when
     *  the job itself could not be interrupted mid-native-call. */
    internal var aiSuggestToken = 0

    /** Context snapshot -> LLM next-word candidates (lowercase), best
     *  first. EMPTY marks a failed/abandoned attempt for that snapshot so
     *  one context never re-fires the model in a loop. Keyed by snapshot
     *  rather than cleared on every refresh: any real typing changes the
     *  snapshot, which is what actually invalidates a suggestion. */
    internal var cachedAiWords: Pair<String, List<String>>? = null

    // Progressive swipe-decode state: the preview runs mid-gesture and only ever feeds the bar and
    // the floating word. What lands is always a decode of the whole path, at the lift.
    internal var lastSwipeDecodeAt = 0L
    internal var lastSwipeDecodeCount = 0
    internal var lastSwipeDecodeEnd: PointF? = null
    internal var lastSwipeResults: List<Suggestion>? = null

    /** Guards against an older preview decode landing after a newer one. */
    internal var swipePreviewToken = 0
    internal var previewJob: Job? = null

    /** The word the last gesture decoded to, so we can tell "the user picked
     *  an alternate" apart from "the user tapped an ordinary prediction". */
    internal var lastSwipeTop: String? = null

    internal var biasPersistJob: Job? = null

    internal var lastWifmDraft: String = ""
    /** Field text a rewrite run must replace on success (null = append mode). */
    internal var wifmReplaceText: String? = null

    /** B3: words committed since the last message boundary. The encrypted
     *  typing log stores whole MESSAGES now — one row per send — instead of
     *  one row per word (which starved voice examples, recent context and
     *  the nightly distiller simultaneously). */
    internal val messageWords = ArrayList<String>()

    /** Last expansion, so backspace immediately after can undo it. */
    internal var lastSnippet: Pair<String, String>? = null

    internal val emojiCallback = createEmojiCallback()

    /** What the quick row last rendered (and whether it was visible), so a
     *  refresh with no change doesn't rebuild nine TextViews. This runs on
     *  every keyboard show and every emoji insert. */
    internal var lastEmojiRowShown = false
    internal var lastEmojiRowItems: List<String> = emptyList()
    internal var lastEmojiRowBg = 0

    // ------------------------------------------------------------------
    // Selection tracking (cursor moved by user, text changed externally)
    // ------------------------------------------------------------------

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        handleSelectionUpdate(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (keyboardViewInitialized) keyboardView.stopDeleteRepeat()
        onHideFlushMessageLog()
        onHideCancelDraft()
        onHideResetTextState()
        onHideStopScreenCapture()
        onHideReleaseEngines()
    }

    /** Whatever took the keyboard down, a sheet over it goes too. Here and not in [onFinishInputView],
     *  which also runs when input moves on with the keyboard still up. */
    override fun hideWindow() {
        closeKeyboardSheet()
        super.hideWindow()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            // The system is actively reclaiming background apps: drop the
            // model NOW. Reloading on next draft is seconds; being killed
            // costs the user a multi-second keyboard outage.
            level >= TRIM_MEMORY_MODERATE -> {
                unloadJob?.cancel()
                serviceScope.launch(Dispatchers.IO) {
                    llamaProvider()?.releaseModel("memory pressure (level $level)")
                    try { com.localboard.keyboard.llm.VoiceBridge.freeModel() } catch (_: Throwable) { }
                }
            }
            level >= TRIM_MEMORY_UI_HIDDEN -> scheduleModelUnload(60_000L)
        }
    }

    override fun onDestroy() {
        closeKeyboardSheet()
        super.onDestroy()
        wifmJob?.cancel()
        predictJob?.cancel()
        aiSuggestJob?.cancel()
        previewJob?.cancel()
        biasPersistJob?.cancel()
        unloadJob?.cancel()
        // Stop the WIFM wait-for-fresh-connection retry loop; its postDelayed
        // chain would otherwise fire into a destroyed service.
        wifmRetryStopped = true
        selfEditHandler.removeCallbacks(selfEditReset)
        cancelEditorChecks()
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangeListener)
        } catch (_: Throwable) { }
        serviceScope.cancel()
    }

    companion object {
        internal val SENTENCE_END = setOf(".", "!", "?")
        /** Stand-in previous word after a sentence ends (see maybeLearnSentence). */
        internal const val SENTENCE_MARK = "."
        /** Punctuation that ends a word and triggers space-style autocorrect.
         *  Apostrophe/quote deliberately absent: they bind to the word. */
        internal val AUTOCORRECT_PUNCT = setOf(',', '.', '!', '?', ':', ';')
        /** Marks that hop in front of a pending automatic space. */
        internal val SPACE_SWAP_PUNCT = setOf(',', '.', '!', '?', ':', ';', ')')
        internal val WORD_SPLIT = Regex("\\s+")
        internal val DEFAULT_EMOJIS = listOf("😂", "❤️", "👍", "🔥", "😊", "🙏", "😍", "👏", "🎉")

        /** Used when the ✨ runs with no visible/remembered context at all. */
        internal const val BLANK_CONTEXT_INSTRUCTION =
            "Write a short, friendly text message to send in this chat."
        /** Used for the retry after a degenerate first run (blank, "yep",
         *  or parroted prompt text). */
        internal const val RETRY_INSTRUCTION =
            "Continue this conversation with a short, natural reply."

        /** Keystrokes closer together than this count as a typing burst. */
        internal const val BURST_GAP_MS = 130L

        /** Debounce used inside a burst. Long enough to coalesce several keys,
         *  short enough that the bar never feels like it lags behind. */
        internal const val BURST_DEBOUNCE_MS = 45L

        /** LLM next-word prediction: debounce after a commit, abandon budget,
         *  how much field text the model sees, and the framing that tells a
         *  chat-tuned model this is a message being composed. */
        internal const val AI_PREDICT_DEBOUNCE_MS = 120L
        /** Glide candidates within this many score points of the top can be
         *  promoted by an LLM next-word match (decoder score units: one
         *  nat of frequency ≈ 1.0, one missed letter ≈ 3-6). */
        internal const val AI_SWIPE_TIE_MARGIN = 2.5
        internal const val AI_PREDICT_ABANDON_MS = 1_500L
        internal const val AI_PREDICT_CONTEXT_CHARS = 400
        internal const val AI_PREDICT_FRAME = "A text message I am typing on my phone:\n\n"

        /** Openers that make a dictated sentence a question. Kept small and
         *  unambiguous — "do you" is a question, "so" is not. */
        internal val QUESTION_OPENERS = setOf(
            "who", "what", "when", "where", "why", "how", "which", "whose",
            "is", "are", "was", "were", "am", "do", "does", "did",
            "can", "could", "will", "would", "should", "shall", "have", "has",
            "had", "may", "might", "any", "anyone", "anybody"
        )

        /** Grace period after the keyboard hides before the LLM model frees
         *  itself. Short enough that background kills stop being a pattern,
         *  long enough that switching apps to copy something and coming
         *  straight back keeps drafts instant. */
        internal const val MODEL_IDLE_UNLOAD_MS = 3 * 60_000L

        /** Per-app tone profiles for the AI composer (package → tone hint). */
        internal val APP_TONES = mapOf(
            "com.google.android.gm" to "a polished, friendly email",
            "com.android.email" to "a polished, friendly email",
            "com.microsoft.office.outlook" to "a polished, friendly email",
            "com.android.messaging" to "a casual, short text message",
            "com.textra" to "a casual, short text message",
            "com.google.android.apps.messaging" to "a casual, short text message",
            "com.whatsapp" to "a casual chat message",
            "com.facebook.orca" to "a casual chat message",
            "com.discord" to "a casual, internet-savvy chat message",
            "com.Slack" to "a concise, collegial work-chat message",
            "com.twitter.android" to "a punchy social post",
            "com.xing.android" to "a professional message",
            "com.instagram.android" to "a light, upbeat comment or caption",
            "com.reddit.frontpage" to "a conversational comment",
            "com.google.android.apps.docs" to "clear document prose",
            "com.microsoft.office.word" to "clear document prose",
            "com.notion.id" to "clear note-style prose"
        )

        fun appToneFor(pkg: String): String? = APP_TONES[pkg]
    }
}

/** `this@LLMKeyboardService` does not resolve inside an extension, so code
 *  nested under another receiver names the service through this. */
internal val LLMKeyboardService.ime: LLMKeyboardService get() = this
