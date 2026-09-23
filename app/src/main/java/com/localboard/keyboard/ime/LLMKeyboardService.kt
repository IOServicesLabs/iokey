package com.localboard.keyboard.ime

import android.app.AlertDialog
import android.content.Intent
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.localboard.keyboard.R
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.Suggestion
import com.localboard.keyboard.engine.HybridRouter
import com.localboard.keyboard.engine.SwipeDecoder
import com.localboard.keyboard.llm.GenerateRequest
import com.localboard.keyboard.llm.LlamaCppProvider
import com.localboard.keyboard.llm.LocalStyleComposer
import com.localboard.keyboard.llm.ProviderRegistry
import com.localboard.keyboard.settings.SettingsActivity
import com.localboard.keyboard.ui.EmojiPanel
import com.localboard.keyboard.ui.KeyboardLayouts
import com.localboard.keyboard.ui.KeyboardModel
import com.localboard.keyboard.ui.KeyboardTheme
import com.localboard.keyboard.ui.KeyboardView
import com.localboard.keyboard.ui.KeyType
import com.localboard.keyboard.ui.PredictionBarView
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The input method itself. Owns the input-connection pipeline, composing
 * state, prediction scheduling, swipe commits, the Write-It-For-Me flow,
 * and all learning hooks.
 */
class LLMKeyboardService : InputMethodService() {

    private lateinit var prefs: Prefs
    private val snippets by lazy { com.localboard.keyboard.util.Snippets(prefs) }
    private lateinit var fastEngine: FastEngine
    private lateinit var contextEngine: ContextEngine
    private lateinit var router: HybridRouter
    private lateinit var registry: ProviderRegistry
    private var swipeDecoder: SwipeDecoder? = null

    private lateinit var keyboardView: KeyboardView
    private lateinit var predictionBar: PredictionBarView
    private lateinit var container: FrameLayout
    private lateinit var emojiRow: android.widget.LinearLayout
    private lateinit var navStrip: com.localboard.keyboard.ui.NavStripView
    private var emojiPanel: EmojiPanel? = null
    private var theme: KeyboardTheme? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var predictJob: Job? = null
    private var wifmJob: Job? = null
    private var unloadJob: Job? = null

    // --- Input state ---
    private val composing = StringBuilder()
    /** A4: raw tap points, index-aligned with [composing]. Valid only when
     *  every letter arrived with a point (plain taps — never voice/paste). */
    private val composingTrace = ArrayList<android.graphics.PointF>()
    private var composingTraceValid = true
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
     * Currently unused: the commit decision moved to the engine's
     * decisive-winner API (autoCorrectWithMargin), which takes no trace. The
     * trace is still maintained per-word; if a trace-aware decision API
     * returns, hand it THIS copy, never the live list.
     */
    private fun composingTraceSnapshot(): List<android.graphics.PointF>? {
        if (!composingTraceValid || composing.isEmpty()) return null
        if (composingTrace.size != composing.length) return null
        return ArrayList(composingTrace)
    }
    private val committedWords = ArrayDeque<String>()
    /** App-label lookups are binder calls — cache them (few entries, session-lived). */
    private val appLabelCache = HashMap<String, String>(8) // recent committed words
    private var lastSpaceCommitAt = 0L
    private lateinit var tapModel: com.localboard.keyboard.util.TapLearningModel

    /** The [Prefs.keyFitData] string [tapModel] was last built against. Null
     *  until the first show, so the first one always reloads — the calibration
     *  test can finish between the input view being created and being shown. */
    private var appliedKeyFitData: String? = null
    /** Last autocorrection (raw -> fixed) eligible for one-tap/backspace undo. */
    private var lastAutoFix: Pair<String, String>? = null

    /** Word committed by a swipe decode or a prediction tap (plus whether an
     *  auto-space followed it). Backspace right after wipes the whole word —
     *  char-by-char deletion of a wrong gesture word leaves misleading
     *  fragments ("becahse" -> "becahs") that poison predictions. */
    private var lastGestureCommit: Pair<String, Boolean>? = null

    /** An automatic space (after a glide, a prediction tap, an AI phrase, or
     *  a punctuation swap) sits right before the cursor and nothing has been
     *  typed since. Punctuation typed now swaps in front of it, Gboard-style
     *  ("hello ," never happens), and a redundant manual space is absorbed.
     *  Always re-verified against the field text before acting. */
    private var autoSpacePending = false

    /**
     * Alternates for the glide word just committed, held on the bar until
     * the user does anything else. Without this hold the alternates were
     * on screen for one frame: commitWordFromSuggestion schedules the
     * ordinary (debounced) prediction pass, which then painted next-word
     * predictions over them 16-45 ms later, so a wrong glide could never be
     * fixed with a tap — the single most common glide interaction.
     */
    private var glideAlternates: List<Suggestion>? = null

    /** Letter just backspaced out of the composing word (with when) — the
     *  "hit q, meant w" self-correction signal for hand calibration. */
    private var lastDeletedComposingChar: Char? = null
    private var lastDeletedComposingAt = 0L

    /** The word learning most recently picked up (and when). Backspacing back
     *  INTO it within a few seconds means it was a typo being fixed, not a
     *  word worth keeping — see the DELETE handler. */
    private var lastLearnedWord: String? = null
    private var lastLearnedAt = 0L

    /** Set from onDestroy so a pending WIFM retry (keyboardView.postDelayed)
     *  dies with the service instead of firing into a torn-down IME. */
    private var wifmRetryStopped = false

    /** Correction-driven hand calibration: the finger landed on [hit] but the
     *  user meant [meant] (proven by an autocorrect substitution or a quick
     *  delete-and-retype). Teaches the per-key model where [meant]'s finger
     *  actually lands. Only adjacent keys count — anything else is a real
     *  typo, not a hand-position issue. */
    private fun teachMisTap(hit: Char, meant: Char) {
        if (!::tapModel.isInitialized || !::keyboardView.isInitialized) return
        if (!fastEngine.keysAdjacent(hit, meant)) return
        val hc = keyboardView.keyCenterDp(hit) ?: return
        val mc = keyboardView.keyCenterDp(meant) ?: return
        tapModel.recordGuided(meant.toString(), hc.x - mc.x, hc.y - mc.y)
    }
    /** Words the user reverted this session: never re-correct them. */
    private val suppressedAutoFix = HashSet<String>()
    private var predictionsEnabled = true
    /** The field asked not to be learned from (IME_FLAG_NO_PERSONALIZED_LEARNING).
     *  Reads are fine; every write path checks this. */
    private var incognito = false
    /** Current field wants an email address (TYPE_TEXT_VARIATION_EMAIL_ADDRESS /
     *  WEB_EMAIL_ADDRESS). There, address characters (.@_- and digits) extend
     *  the composing token instead of committing word fragments, and learned
     *  email completions are surfaced on the bar. */
    private var isEmailField = false
    private var currentAppPackage = ""
    private var showingEmoji = false

    /** Cursor/selection pad is up instead of the letter grid. */
    private var showingSelection = false

    /** Per-app rule: may the AI draft/rewrite in the current field? */
    private var aiAllowedHere = true

    /** Refresh rate currently requested for the keyboard window, 0 = system. */
    private var requestedHz = 0f

    /**
     * Ask the display for a refresh rate that matches what is actually moving.
     *
     * A 120 Hz panel composites the keyboard window 120 times a second whether
     * or not anything changed. Static typing has nothing to show at that rate —
     * a keypress is one repaint — so the window asks for 60 while typing and
     * the panel's full rate only during a glide, where the trail really does
     * follow the finger frame by frame.
     *
     * Best-effort: `preferredRefreshRate` is a hint, and some OEM compositors
     * ignore it. It costs nothing when ignored.
     */
    private fun applyRefreshRatePolicy(gliding: Boolean) {
        if (!prefs.throttleRefreshRate) {
            if (requestedHz != 0f) setWindowRefreshRate(0f)
            return
        }
        val target = if (gliding) maxDisplayHz() else 60f
        if (target != requestedHz) setWindowRefreshRate(target)
    }

    private fun maxDisplayHz(): Float = try {
        val display = (getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
    } catch (_: Throwable) { 120f }

    private fun setWindowRefreshRate(hz: Float) {
        try {
            val w = window?.window ?: return
            val lp = w.attributes
            lp.preferredRefreshRate = hz
            w.attributes = lp
            requestedHz = hz
        } catch (_: Throwable) {
            // Never let a display hint break text input.
        }
    }
    private var symbolsPage = 0 // 0 letters, 1 symbols1, 2 symbols2
    private var numberMode = false
    private var selfEditing = false // guard for onUpdateSelection
    private var lastShiftTapMs = 0L

    // BUGFIX (text erased while typing): editors deliver the onUpdateSelection
    // echo of our own edits ASYNCHRONOUSLY (posted to the IME main thread after
    // the IC call returns). Resetting selfEditing synchronously let that echo
    // wipe the composing buffer, so the next keystroke replaced the whole word
    // with one letter. We now hold the flag for a short window instead.
    private val selfEditHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val selfEditReset = Runnable { selfEditing = false }

    private fun beginSelfEdit() {
        selfEditHandler.removeCallbacks(selfEditReset)
        selfEditing = true
    }

    private fun endSelfEdit() {
        selfEditHandler.postDelayed(selfEditReset, 150)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        fastEngine = FastEngine.shared(this)
        contextEngine = ContextEngine(this, fastEngine)
        router = HybridRouter(fastEngine)
        registry = ProviderRegistry(prefs, LocalStyleComposer(fastEngine))
        registry.register(LlamaCppProvider(this))

        serviceScope.launch(Dispatchers.IO) {
            try {
                contextEngine.initialize()
            } catch (t: Throwable) {
                // In-memory mode: base dictionary still powers predictions.
            }
        }

        // Overnight on-charger style/personality distillation (user-toggleable
        // in settings; WorkManager keeps it across reboots).
        try {
            if (prefs.overnightDistillation) {
                com.localboard.keyboard.llm.NightlyDistillWorker.schedule(this)
            }
        } catch (_: Throwable) { }

        // Clipboard reads are binder transactions; the prediction refresh used
        // to make two of them per keystroke lull. The change listener keeps a
        // local cache hot instead, and the refresh path only ever reads that.
        clipboardManager =
            getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        try {
            clipboardManager?.addPrimaryClipChangedListener(clipChangeListener)
            refreshClipCache()
        } catch (_: Throwable) { }

        // The emoji catalog's lazy build is big; warm it off the main thread
        // so the first panel open doesn't hitch.
        serviceScope.launch(Dispatchers.Default) {
            try { com.localboard.keyboard.util.EmojiCatalog.categories } catch (_: Throwable) { }
        }
    }

    /** P3: never go fullscreen-extract in landscape — the keyboard has its
     *  own suggestion bar and AI affordances, and the OS extract bar hid
     *  them all behind a cramped one-line field. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /** The input view, kept so [onComputeInsets] can measure it. */
    private var inputRoot: View? = null

    /** Last navigation-bar inset the window was told about. Only ever used as
     *  a VALUE to apply — never to decide whether applying is necessary, which
     *  is what made the old cache go stale across a rotation. */
    private var navBarBottomPx = 0

    /** Whether [navBarBottomPx] came from a real inset dispatch, as opposed to
     *  never having been written. Zero is a legitimate answer — an immersive
     *  app has no navigation bar — so "0" and "don't know yet" have to be
     *  distinguishable, or the fallback below would fight a correct value. */
    private var navBarInsetKnown = false

    /**
     * Ask the window manager how tall the navigation bar is right now.
     *
     * The inset LISTENER is the primary source and this is not a replacement
     * for it — but it is the only source, it fires only when the framework
     * chooses to dispatch, and getting nothing is indistinguishable from
     * getting zero. When that happens the strip stays the 0dp it was inflated
     * with, the IME window keeps the full screen height it is given from API
     * 35, and the bottom key row is laid out underneath the navigation bar:
     * the keyboard looks like it has slid off the bottom of the screen.
     *
     * This is a direct query with no dispatch involved, so it answers even on
     * a show where no inset callback ever arrives. It honours visibility, so
     * a genuinely immersive app still gets no strip.
     */
    private fun windowNavBarBottom(): Int = try {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        wm.currentWindowMetrics.windowInsets
            .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
    } catch (_: Throwable) {
        0
    }

    /**
     * Size the strip behind the navigation bar.
     *
     * Always compares against the strip's own current height, so a freshly
     * inflated strip (0dp from the layout file) is always corrected no matter
     * what the window last reported.
     *
     * Mode 3 is "reclaim": the strip collapses and the IME window gets that
     * much shorter, handing the space back to the app above. The keys then
     * extend under the navigation bar, which is a real trade — see the
     * settings copy — but it is the user's to make.
     */
    private fun applyNavStripHeight() {
        if (!::navStrip.isInitialized) return
        val bar = if (navBarInsetKnown) navBarBottomPx else windowNavBarBottom()
        val target = if (prefs.navStripMode == 3) 0 else bar
        val lp = navStrip.layoutParams ?: return
        if (lp.height != target) {
            lp.height = target
            navStrip.layoutParams = lp
        }
    }

    /**
     * Which part of the window accepts touches.
     *
     * P3: with gesture navigation the default insets leave a dead strip under
     * the space row — taps fall into the nav zone and vanish — so the frame is
     * touchable rather than just the content.
     *
     * From API 35 the IME window is laid out edge to edge and extends BEHIND
     * the navigation bar, and a touchable frame then covers the bar's own
     * controls: the chevron that dismisses the keyboard stopped responding,
     * because the keyboard was claiming those touches. So the frame is
     * touchable minus exactly the strip the navigation bar occupies.
     *
     * The strip's own height is the measurement, read live from the view.
     * Caching it in a field here was a bug: the service outlives its input
     * view, so after a rotation the field still held the old bar height while
     * the new strip was still 0 tall — which both let the keys draw over the
     * bar AND cut the bottom key row out of the touchable region, so the
     * bottom row stopped responding. The view knows its own height; nothing
     * else needs to remember it.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = inputRoot
        val strip = if (::navStrip.isInitialized) navStrip.height else 0
        if (strip > 0 && root != null && root.width > 0 && root.height > strip) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, 0, root.width, root.height - strip)
        } else {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        }
    }

    override fun onCreateInputView(): View {
        val root = LayoutInflater.from(this).inflate(R.layout.keyboard_root, null)
        inputRoot = root
        predictionBar = root.findViewById(R.id.prediction_bar)
        container = root.findViewById(R.id.keyboard_container)
        emojiRow = root.findViewById(R.id.emoji_row)
        navStrip = root.findViewById(R.id.nav_strip)

        // API 35+ draws this window edge to edge, so without this the bottom
        // key row sits underneath the navigation bar. Before 35 the system
        // reserved that space for us; giving the strip the same height restores
        // the old geometry rather than changing it, so the keyboard keeps the
        // height it has always had.
        //
        // It is a real view rather than padding on the root because the strip
        // has to be PAINTED — an empty gap in a transparent window shows the
        // app underneath, which reads as a hole in the keyboard. Padding could
        // only be filled by giving the root a background, and that would
        // repaint the entire keyboard area underneath the keys for the sake of
        // a 24dp strip.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val navType = androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            // getInsetsIgnoringVisibility while the bar is SHOWING, plain
            // getInsets when it is not.
            //
            // getInsets alone reports 0 for a bar that is hidden — including
            // one that is merely mid-animation, or hidden because the app on
            // screen went immersive for a moment. A dispatch that lands in
            // that window used to be cached as "there is no navigation bar",
            // and since the cache is re-asserted on every keyboard show it
            // stayed cached: the strip collapsed and the bottom key row was
            // laid out under a bar that had come back. The stable height is
            // the right answer whenever the bar is actually there, and 0 is
            // still the right answer when it genuinely is not.
            val nav = if (insets.isVisible(navType)) {
                insets.getInsetsIgnoringVisibility(navType)
            } else {
                insets.getInsets(navType)
            }
            val cutout = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            // Landscape moves both the navigation bar and the camera cutout to
            // the SIDES on many devices, where a bottom-only inset does nothing
            // and the outer key columns end up under them. Take whichever
            // intrudes further on each edge.
            val left = maxOf(nav.left, cutout.left)
            val right = maxOf(nav.right, cutout.right)
            if (v.paddingLeft != left || v.paddingRight != right) {
                v.setPadding(left, v.paddingTop, right, v.paddingBottom)
            }
            navBarBottomPx = nav.bottom
            navBarInsetKnown = true
            applyNavStripHeight()
            insets
        }
        // requestApplyInsets on a DETACHED view is a no-op: its whole body is
        // `if (mParent != null) mParent.requestFitSystemWindows()`, and
        // InputMethodService does not attach the input view until after this
        // method returns. Asking once the view is actually in the window is
        // the difference between requesting insets and only appearing to.
        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    androidx.core.view.ViewCompat.requestApplyInsets(v)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            }
        )
        if (root.isAttachedToWindow) androidx.core.view.ViewCompat.requestApplyInsets(root)

        theme = KeyboardTheme.loadCached(this, prefs).also { t ->
            predictionBar.setTheme(t)
        }

        tapModel = com.localboard.keyboard.util.TapLearningModel(prefs)
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
    private var typedThisField = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // B3: a leftover accumulator from the previous field was (almost
        // always) a sent message — log it against the OLD package before
        // currentAppPackage/hint change under us.
        if (!restarting) flushMessageLog("field-change")
        // Keyboard is back: the model stays resident while there is a user
        // to draft for. Any pending idle-unload is off.
        unloadJob?.cancel()
        // Field (re)opened: any AI phrase suggestion belonged to the old context.
        cancelAiSuggestion()

        // Re-apply prefs each time the keyboard is shown (settings may have changed).
        theme = KeyboardTheme.loadCached(this, prefs, info.packageName ?: "").also { t ->
            keyboardView.setTheme(t)
            predictionBar.setTheme(t)
        }
        keyboardView.swipeEnabled = prefs.swipeEnabled
        keyboardView.hapticEnabled = prefs.haptic
        keyboardView.soundEnabled = prefs.sound
        keyboardView.keyFeedback.vibLevel = prefs.vibLevel
        keyboardView.keyFeedback.soundPack = prefs.soundPack
        keyboardView.keyFeedback.soundVolume = prefs.soundVolume
        keyboardView.keyFeedback.coalesce = prefs.coalesceHaptics
        keyboardView.outlineKeys = prefs.outlineKeys
        // Re-read the insets on every show. InputMethodService CACHES the
        // input view, so a hide/show does not re-create it and does not by
        // itself guarantee another dispatch — which is why a keyboard that
        // came up wrong could stay wrong. Asking here means the navigation-bar
        // height is re-derived each time the keyboard appears, at the one
        // moment the answer is both knowable and needed.
        inputRoot?.let { r ->
            if (r.isAttachedToWindow) androidx.core.view.ViewCompat.requestApplyInsets(r)
        }
        applyLiveBackground()
        applyRefreshRatePolicy(gliding = false)
        keyboardView.animLevel = prefs.animLevel
        keyboardView.pressEffect = prefs.pressEffect
        keyboardView.previewEnabled = prefs.popupPreview
        keyboardView.setMetrics(prefs.heightScale, prefs.keyTextScale)
        keyboardView.setKeySpacing(prefs.keySpacing)
        keyboardView.setOneHanded(prefs.oneHandedMode, prefs.oneHandedWidth)
        keyboardView.setKeyOffsets(prefs.keyOffsetXDp, prefs.keyOffsetYDp)
        // Per-key fit from the calibration test: this moves the KEYS, so it is
        // re-read here rather than only at view creation — finishing the test
        // and coming straight back should show the new layout.
        val fitData = prefs.keyFitData
        keyboardView.keyFit = prefs.keyFitMap()
        // The fit and the tap model describe the SAME measurement, so applying
        // both shifts every key twice. CalibrateActivity does clear the model
        // when it bakes a fit — but it clears ITS OWN instance, and this
        // service's instance outlives that whole activity. So the service kept
        // nudging hit targets that had already moved, and worse, the next
        // keystroke marked the stale model dirty and persist() wrote those
        // offsets straight back over the cleared prefs: the clear never
        // survived, and a second calibration inherited the first instead of
        // measuring from the new positions.
        //
        // Reload only when the fit itself changed — running the test, or
        // Settings -> Reset finger fit, both of which rewrite keyFitData.
        // Reloading on every show would instead throw away whatever the model
        // has learned since its last persist (it batches every 25 taps), which
        // would quietly cap the thing at 25 taps of memory.
        if (fitData != appliedKeyFitData) {
            appliedKeyFitData = fitData
            tapModel = com.localboard.keyboard.util.TapLearningModel(prefs)
        }
        keyboardView.setSwipeActivationScale(prefs.swipeSensitivity)
        keyboardView.setTrailEnabled(prefs.swipeTrail)
        keyboardView.setLongPressMs(prefs.longPressMs)
        keyboardView.tapModel = tapModel
        applyKeyboardBackground()

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
        incognito = (info.imeOptions and
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ||
            !prefs.mayLearnIn(editorPkg)
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
        // it current from here on.
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd

        currentAppPackage = info.packageName ?: ""
        // P0: publish the editor-hint thread key so ContextEngine readers
        // (voice examples, recent-typed fallback) can bucket per
        // conversation even when the screen stream is blind. (The
        // framework updates currentInputEditorInfo before this callback.)
        contextEngine.setHintThreadKey(hintThreadKey())
        composing.clear()
        composingTrace.clear()
        composingTraceValid = true
        // A mere restart (rotation, resize) keeps the sentence context — the
        // user is still mid-thought in the same field.
        if (!restarting) committedWords.clear()
        if (!restarting) typedThisField = false
        showingEmoji = false
        showingSelection = false
        selectionAnchor = -1
        symbolsPage = 0
        // Mirror the redaction preference into the capture path each time the
        // keyboard comes up, so toggling it in Settings takes effect at once.
        com.localboard.keyboard.context.ScreenBuffer.redactEnabled = prefs.redactScreenText
        // Note: the applied-layout identity survives on the KeyboardView —
        // re-focusing a field with the same layout must NOT re-attach and
        // re-lay-out the keyboard (pure churn). Prefs that change the layout
        // are part of the key itself. It does NOT survive the view being
        // recreated, which is the point of storing it there.
        applyKeyboardModel()
        refreshEmojiRow()
        if (swipeDecoder == null && ::keyboardView.isInitialized) {
            swipeDecoder = SwipeDecoder(fastEngine, keyboardView.geometry)
            restoreSwipeBias()
        }
        // P1: pre-warm the swipe bucket table off-thread at show time (no-op
        // when the table is already fresh).
        swipeDecoder?.let { d ->
            d.doubleLetterEnabled = prefs.swipeDoubleLetter
            d.learningEnabled = prefs.swipeLearning
            d.warmUp()
        }
        updateClipboardChip()

        // Screen reading only works while the keyboard is up: that's the only
        // moment the AI can use it, and skipping background capture removes
        // the accessibility service's biggest battery cost by far.
        com.localboard.keyboard.context.ScreenBuffer.imeVisible = true
        com.localboard.keyboard.context.ScreenContextService.instance?.captureSoon()
        refreshVoiceVisibility()

        if (prefs.autoCaps) {
            keyboardView.shiftState = KeyboardView.ShiftState.ON
        } else {
            keyboardView.shiftState = KeyboardView.ShiftState.OFF
        }

        serviceScope.launch(Dispatchers.IO) {
            contextEngine.loadAppContext(currentAppPackage)
        }
        refreshPredictions()
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private fun wireKeyboard() {
        keyboardView.listener = object : KeyboardView.KeyboardListener {
            override fun onText(text: String) = handleText(text)
            override fun onKey(type: KeyType) = handleSpecialKey(type)
            override fun onSwipePath(points: List<PointF>) = handleSwipe(points)
            override fun onSwipeProgress(points: List<PointF>) = handleSwipeProgress(points)
            override fun onSwipeStarted() {
                // A glide is the one thing on this keyboard that genuinely
                // benefits from a high refresh rate — the trail follows the
                // finger. Everything else is static, so the rate goes back
                // down the moment the gesture ends.
                applyRefreshRatePolicy(gliding = true)
                // New gesture: drop any stale preview so an old decode can't
                // be mistaken for this swipe's instant-commit candidate.
                glideAlternates = null
                lastSwipeResults = null
                lastSwipeDecodeEnd = null
                lastSwipeResultEnd = null
                lastSwipeDecodeCount = 0
                lastSwipeDecodeAt = 0L
                lastBarHoverSlot = -1
            }
            override fun onSwipeBarHover(xFraction: Float) = handleSwipeBarHover(xFraction)
            override fun onSwipeBarPick(xFraction: Float) = handleSwipeBarPick(xFraction)
            override fun onSwipeEnded() {
                applyRefreshRatePolicy(gliding = false)
                com.localboard.keyboard.util.FrameStats.gestures++
                // Gesture over: any bar highlight is stale.
                if (lastBarHoverSlot >= 0) {
                    lastBarHoverSlot = -1
                    if (::predictionBar.isInitialized) predictionBar.setGestureHighlight(-1)
                }
            }
            override fun onWordDelete() = deleteWordBackward()
            override fun onSpaceLongPress() = showImePicker()
            override fun onEnterLongPress() {
                val ic = currentInputConnection ?: return
                finishComposingWord()
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

    /** P2: long-press space — the system IME switcher. */
    private fun showImePicker() {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as
                android.view.inputmethod.InputMethodManager).showInputMethodPicker()
        } catch (_: Throwable) { }
    }

    /** P2: space-bar slide — move the editor cursor [delta] chars. DPAD
     *  events are the only cursor control every editor honors (raw
     *  setSelection breaks on apps that track their own selection). */
    private fun scrubCursor(delta: Int) {
        currentInputConnection ?: return
        val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(delta).coerceAtMost(24)) { sendDownUpKeyEvents(code) }
    }

    /** Slot under the finger during a swipe-up word pick; -1 = none. */
    private var lastBarHoverSlot = -1

    /** Mid-glide finger-over-bar: light up the word under the finger so the
     *  user sees exactly what a lift would commit. */
    private fun handleSwipeBarHover(xFraction: Float) {
        if (!::predictionBar.isInitialized) return
        val slot = predictionBar.slotForX(xFraction * predictionBar.width)
        val valid = if (slot >= 0 && predictionBar.suggestionInSlot(slot) != null) slot else -1
        if (valid != lastBarHoverSlot) {
            lastBarHoverSlot = valid
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
    private fun handleSwipeBarPick(xFraction: Float): Boolean {
        if (!::predictionBar.isInitialized) return false
        val slot = predictionBar.slotForX(xFraction * predictionBar.width)
        val picked = if (slot >= 0) predictionBar.suggestionInSlot(slot) else null
        predictionBar.setGestureHighlight(-1)
        lastBarHoverSlot = -1
        if (picked == null) return false
        lastSwipeResults = null
        commitWordFromSuggestion(picked.text, fromSwipe = true)
        return true
    }

    /** P2: full clipboard text behind the bar's paste chip ("" = no chip). */
    private var clipboardFullText: String = ""

    /** Clipboard cache, maintained by [clipChangeListener] so the prediction
     *  refresh path never touches the binder. Updated on every primary-clip
     *  change and once at service start. */
    private var clipboardManager: android.content.ClipboardManager? = null
    private var cachedClipText: String? = null
    private var cachedClipTs: Long = 0L

    private val clipChangeListener =
        android.content.ClipboardManager.OnPrimaryClipChangedListener {
            refreshClipCache()
            // A fresh copy while the keyboard is up should surface the chip
            // without waiting for the next keystroke.
            if (::predictionBar.isInitialized) updateClipboardChip()
        }

    private fun refreshClipCache() {
        val cm = clipboardManager ?: return
        cachedClipText = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()?.trim()
        } catch (_: Throwable) { null }
        cachedClipTs = try {
            cm.primaryClipDescription?.timestamp ?: 0L
        } catch (_: Throwable) { 0L }
    }

    /** Re-evaluate the paste chip: only when the field is safe (predictions
     *  on — never over passwords), the user isn't mid-word, and the clip is
     *  real and fresh (≤ 2 min old, Gboard-style). Reads only the listener-
     *  maintained cache — no binder call on the prediction path. */
    private fun updateClipboardChip() {
        if (!::predictionBar.isInitialized) return
        if (!predictionsEnabled || composing.isNotEmpty()) {
            clipboardFullText = ""
            predictionBar.setClipboardChip(null)
            return
        }
        val text = cachedClipText
        val ts = cachedClipTs
        val fresh = !text.isNullOrEmpty() && text.length >= 2 &&
            (ts <= 0L || System.currentTimeMillis() - ts < 120_000)
        // Feed the history whenever a NEW clip appears, regardless of chip
        // freshness — the chip is a 2-minute affordance, the history is the
        // durable one.
        if (!incognito && !text.isNullOrEmpty() && text != lastRecordedClip) {
            lastRecordedClip = text
            clipHistory.record(text)
        }
        clipboardFullText = if (fresh) text!! else ""
        predictionBar.setClipboardChip(
            if (fresh) text!!.replace('\n', ' ').take(24) else null
        )
    }

    private val clipHistory by lazy { com.localboard.keyboard.util.ClipHistory(prefs) }
    private var lastRecordedClip: String? = null

    /**
     * The clipboard history sheet: everything copied recently, newest first,
     * pinned entries on top. Long-press pins or deletes. Opened by
     * long-pressing the paste chip, so the common case (paste the last thing)
     * stays a single tap.
     */
    private fun showClipboardHistory() {
        if (!prefs.clipboardHistory) {
            Toast.makeText(this, R.string.clip_history_off, Toast.LENGTH_SHORT).show()
            return
        }
        val clips = clipHistory.entries()
        if (clips.isEmpty()) {
            Toast.makeText(this, R.string.clip_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = clips.map { c ->
            (if (c.pinned) "📌  " else "") + c.text.replace('\n', ' ').take(60)
        }.toTypedArray()
        val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.clip_history_title)
            .setItems(labels) { _, i ->
                val ic = currentInputConnection ?: return@setItems
                beginSelfEdit()
                ic.commitText(clips[i].text, 1)
                endSelfEdit()
                updateAutoCaps()
                refreshPredictions()
            }
            .setNeutralButton(R.string.clip_history_manage) { _, _ -> showClipManage(clips) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showOverIme(dialog)
    }

    private fun showClipManage(clips: List<com.localboard.keyboard.util.ClipHistory.Clip>) {
        val labels = clips.map { c ->
            (if (c.pinned) "📌 " else "☆ ") + c.text.replace('\n', ' ').take(50)
        }.toTypedArray()
        val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.clip_history_manage)
            .setItems(labels) { _, i ->
                val c = clips[i]
                clipHistory.setPinned(c.text, !c.pinned)
                showClipboardHistory()
            }
            .setPositiveButton(R.string.clip_history_clear) { _, _ ->
                clipHistory.clear()
                Toast.makeText(this, R.string.clip_history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showOverIme(dialog)
    }

    /** Dialogs from an IME need the keyboard's own window token, or WMS
     *  throws BadTokenException. Shared by every sheet we raise. */
    private fun showOverIme(dialog: AlertDialog) {
        try {
            dialog.window?.let { w ->
                val lp = w.attributes
                lp.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
                lp.token = window?.window?.attributes?.token ?: keyboardView.windowToken
                w.attributes = lp
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            dialog.show()
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.clip_history_no_window, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteClipboardChip() {
        val ic = currentInputConnection ?: return
        val text = clipboardFullText
        if (text.isNotEmpty()) {
            beginSelfEdit()
            ic.commitText(text, 1)
            endSelfEdit()
            // Pasted text is not necessarily the user's own writing — it
            // never enters recordWords / the learning streams.
        }
        clipboardFullText = ""
        predictionBar.setClipboardChip(null)
        updateAutoCaps()
        refreshPredictions()
    }

    /** P2: long-press a suggestion → confirm, then banish the word from the
     *  learned model (and from base predictions via suppression). */
    private fun confirmRemoveSuggestion(text: String) {
        val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
        AlertDialog.Builder(ctx)
            .setMessage("Remove “$text” from suggestions?")
            .setPositiveButton("Remove") { _, _ ->
                contextEngine.removeWordEverywhere(text)
                refreshPredictions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wirePredictionBar() {
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
    // Offline voice typing (whisper.cpp, fully on-device)
    // ------------------------------------------------------------------

    private val voiceModels by lazy {
        com.localboard.keyboard.llm.VoiceModelManager(applicationContext)
    }
    private var voiceInput: VoiceInputManager? = null

    /** Mic button visibility: engine shipped in this build + user enabled. */
    private fun refreshVoiceVisibility() {
        if (!::predictionBar.isInitialized) return
        predictionBar.setVoiceVisible(
            com.localboard.keyboard.llm.VoiceBridge.available && prefs.voiceTypingEnabled
        )
    }

    private fun handleVoiceTap() {
        if (!com.localboard.keyboard.llm.VoiceBridge.available) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone access is needed for voice typing", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, com.localboard.keyboard.settings.VoicePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (!voiceModels.isReady) {
            Toast.makeText(
                this,
                "Download the voice model first — Settings → Voice typing",
                Toast.LENGTH_LONG
            ).show()
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

    private fun onVoiceState(st: VoiceInputManager.State) {
        if (!::predictionBar.isInitialized) return
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
    private fun punctuateVoice(text: String, before: String): String {
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

    private fun onVoiceResult(text: String) {
        val t = text.trim()
        // Whisper emits bracketed noise markers for silence/music — no text.
        if (t.isEmpty() || (t.startsWith("[") && t.endsWith("]")) ||
            (t.startsWith("(") && t.endsWith(")") && t.length < 24)
        ) {
            Toast.makeText(this, "Didn't catch that — try again", Toast.LENGTH_SHORT).show()
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

    /** Push the user's wallpaper (down-sampled) or live-gradient choice to
     *  the keyboard view. The decode runs once and is cached — re-decoding a
     *  1080px bitmap on every keyboard show was the costliest thing on the
     *  show path. */
    private var wallpaperCacheKey: String? = null
    private var wallpaperCacheBmp: android.graphics.Bitmap? = null

    /**
     * Push the animated-background settings to the view.
     *
     * Single entry point on purpose: this was set in onStartInputView and then
     * set AGAIN by applyKeyboardBackground a few lines later, which silently
     * discarded the low-power override.
     */
    private fun applyLiveBackground() {
        if (!::keyboardView.isInitialized) return
        // Decoration is the first thing to go when the phone is low or hot.
        val lowPower = prefs.powerAwareAi &&
            com.localboard.keyboard.util.PowerGate.isLowPower(this, prefs)
        // Animated backgrounds are Pro. Free keeps the flat theme colour —
        // which is also, conveniently, the cheapest thing to draw.
        val bgAllowed = com.localboard.keyboard.billing.Pro.has(
            com.localboard.keyboard.billing.ProFeature.ANIMATED_BACKGROUND
        )
        keyboardView.setLiveBackground(
            if (lowPower || !bgAllowed) "none" else prefs.liveBgStyle,
            prefs.liveBgFps, prefs.liveBgWhileTyping, prefs.liveBgIntensity
        )
        applyNavStrip(lowPower, bgAllowed)
    }

    /**
     * Fill the strip behind the navigation bar.
     *
     * Shares the animated background's Pro gate and low-power override rather
     * than having its own: they are the same cost in the same place, and a
     * "decoration off" state that leaves one animation running would be a
     * strange thing to explain. Falling back to MATCH rather than to nothing
     * matters — nothing means a see-through hole under the keys, which is the
     * defect this whole strip exists to fix, so it must never be the fallback.
     */
    private fun applyNavStrip(lowPower: Boolean, bgAllowed: Boolean) {
        if (!::navStrip.isInitialized) return
        // The mode decides whether the strip takes any height at all, so this
        // has to run whenever prefs are re-read, not only on inset changes.
        applyNavStripHeight()
        val t = theme
        val bg = t?.background ?: android.graphics.Color.BLACK
        navStrip.matchColor = bg
        navStrip.fillColor = prefs.navStripColor
        val wantsAnimation = prefs.navStripMode == 2 && !lowPower && bgAllowed
        navStrip.mode = when {
            wantsAnimation -> com.localboard.keyboard.ui.NavStripView.Mode.ANIMATED
            prefs.navStripMode == 1 -> com.localboard.keyboard.ui.NavStripView.Mode.SOLID
            else -> com.localboard.keyboard.ui.NavStripView.Mode.MATCH
        }
        if (wantsAnimation) {
            navStrip.style =
                com.localboard.keyboard.ui.LiveBackground.Style.fromId(prefs.navStripStyle)
            navStrip.setAnimationTuning(
                prefs.liveBgFps, prefs.liveBgIntensity, prefs.liveBgWhileTyping
            )
            if (t != null) {
                navStrip.setAnimationColors(t.background, t.accent, t.trail, t.keyFunctional)
            }
        }
    }

    private fun applyKeyboardBackground() {
        val path = prefs.wallpaperPath
        if (path.isNotEmpty()) {
            val f = java.io.File(path)
            if (f.exists()) {
                val key = "$path@${f.lastModified()}"
                if (key == wallpaperCacheKey && wallpaperCacheBmp != null) {
                    keyboardView.setWallpaper(wallpaperCacheBmp, prefs.wallpaperDim)
                    return
                }
                // Cold cache (first open after force-stop): decode OFF the
                // main thread — a multi-megabyte photo decode used to block
                // the keyboard's first appearance for hundreds of ms.
                val dim = prefs.wallpaperDim
                serviceScope.launch(Dispatchers.IO) {
                    val bmp = decodeSampled(f, 1080)
                    wallpaperCacheKey = if (bmp != null) key else null
                    wallpaperCacheBmp = bmp
                    withContext(Dispatchers.Main) {
                        if (!::keyboardView.isInitialized) return@withContext
                        if (bmp != null) {
                            keyboardView.setWallpaper(bmp, dim)
                        } else {
                            // Missing/corrupt file: clear the pref so we stop trying.
                            prefs.wallpaperPath = ""
                            keyboardView.setWallpaper(null, prefs.wallpaperDim)
                            applyLiveBackground()
                        }
                    }
                }
                return
            }
            // Missing/corrupt file: clear the pref so we stop trying.
            prefs.wallpaperPath = ""
            wallpaperCacheKey = null
            wallpaperCacheBmp = null
        }
        keyboardView.setWallpaper(null, prefs.wallpaperDim)
        applyLiveBackground()
    }

    private fun decodeSampled(f: java.io.File, maxDim: Int): android.graphics.Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim &&
                bounds.outHeight / (sample * 2) >= maxDim
            ) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (t: Throwable) {
            null
        }
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
    private fun enterShouldAct(info: EditorInfo?, action: Int): Boolean {
        if (action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
        ) return false
        if (info == null) return true
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
        val multiline = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (multiline && action == EditorInfo.IME_ACTION_DONE) return false
        return true
    }

    private fun applyKeyboardModel() {
        // The layout options are part of the cache identity: without them a
        // bottom-row or symbols-density change in Settings would not reach a
        // keyboard that is already attached with the same page.
        val opts = com.localboard.keyboard.ui.LayoutOptions.from(prefs)
        val key = when {
            showingSelection -> "sel"
            // The number pad's shape depends on the layout option too (the
            // Gboard variant), so it cannot keep the bare "num" identity.
            numberMode -> "num:${opts.cacheKey()}"
            symbolsPage == 1 -> "s1:${opts.cacheKey()}"
            symbolsPage == 2 -> "s2:${opts.cacheKey()}"
            else -> "q:${opts.cacheKey()}"
        }
        if (showingEmoji) {
            keyboardView.appliedModelKey = null
            showEmojiPanel()
            return
        }
        // The cache identity is read from the VIEW, not from a field here: the
        // service survives configuration changes and its input view does not,
        // so anything remembered here would describe a view that is gone. A
        // freshly created KeyboardView has no applied key and is always
        // configured. See KeyboardView.appliedModelKey.
        if (keyboardView.parent === container && key == keyboardView.appliedModelKey) return
        keyboardView.appliedModelKey = key
        val model: KeyboardModel = when {
            showingSelection -> KeyboardLayouts.selectionPad()
            numberMode -> KeyboardLayouts.numberPad(opts)
            symbolsPage == 1 -> KeyboardLayouts.symbols1(opts)
            symbolsPage == 2 -> KeyboardLayouts.symbols2(opts)
            else -> KeyboardLayouts.qwerty(opts)
        }
        // Autocorrect's fat-finger model must describe the keys actually on
        // screen, not the QWERTY it was born with.
        com.localboard.keyboard.engine.FastEngine.setKeyboardRows(
            KeyboardLayouts.letterRows(opts.layoutId)
        )
        // Coming back from emoji: the suggestion bar returns with the keys.
        if (::predictionBar.isInitialized) predictionBar.visibility = View.VISIBLE
        container.removeAllViews()
        container.addView(keyboardView)
        keyboardView.setKeyboard(model)
    }

    // ------------------------------------------------------------------
    // Text input pipeline
    // ------------------------------------------------------------------

    private fun handleText(text: String) {
        val ic = currentInputConnection ?: return
        // A separator kills the pending/shown AI suggestion outright. A
        // LETTER does not: the LLM's next-word guesses are keyed to the
        // committed-word context, which typing a letter leaves unchanged,
        // and they go on serving as completions of the word being typed.
        if (!(text.length == 1 && text[0].isLetter())) cancelAiSuggestion()
        glideAlternates = null

        if (text.length == 1 && text[0].isLetter()) {
            typedThisField = true
            lastAutoFix = null
            lastGestureCommit = null
            autoSpacePending = false
            // Deleted a letter and instantly retyped an adjacent one? That's
            // a self-corrected mis-tap — feed the per-key hand model.
            val deleted = lastDeletedComposingChar
            lastDeletedComposingChar = null
            if (deleted != null &&
                SystemClock.uptimeMillis() - lastDeletedComposingAt < 1800
            ) {
                teachMisTap(deleted, text[0].lowercaseChar())
            }
            // A4: consume the tap point the view stashed for this keystroke.
            // The trace stays index-aligned with `composing`; any letter that
            // arrives WITHOUT a point (voice, popup pick, paste) invalidates
            // the whole trace — a partial trace is worse than none.
            val tapPt = keyboardView.lastCharTapPoint
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
            lastDeletedComposingChar = null
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
            lastDeletedComposingChar = null
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
            var revertWord: String? = null
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
                // Apostrophe/quote are excluded — they attach to the word.
                revertWord =
                    if (text == " " || (text.length == 1 && text[0] in AUTOCORRECT_PUNCT)) {
                        maybeAutoCorrectComposing()
                    } else null
                finishComposingWord()
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
                }
            } finally {
                ic.endBatchEdit()
                endSelfEdit()
            }
            if (text == "\n" || text == " " || SENTENCE_END.contains(text)) {
                maybeLearnSentence(text)
            }
            updateAutoCaps()
            refreshPredictions(revertWord)
        }
    }

    /** True when [text] should EXTEND the composing token as part of an email
     *  address instead of committing the word: an address character in an
     *  email field, or anything after a '@' already in the composing token
     *  (which only gets there from an email field). A second '@' is never
     *  part of one address, so it falls through to the separator path. */
    private fun isEmailContinuation(text: String): Boolean {
        if (text.length != 1 || composing.isEmpty()) return false
        val c = text[0]
        if (c != '@' && c != '.' && c != '_' && c != '-' && !c.isDigit()) return false
        if (!isEmailField && !composing.contains('@')) return false
        if (c == '@' && composing.contains('@')) return false
        return true
    }

    private fun handleSpecialKey(type: KeyType) {
        val ic = currentInputConnection ?: return
        // Same rule as handleText: any key event invalidates the AI phrase.
        cancelAiSuggestion()
        glideAlternates = null
        when (type) {
            KeyType.DELETE -> {
                if (composing.isEmpty() && undoSnippetIfPending()) return
                if (composing.isEmpty() && undoAutoFixIfPending()) return
                beginSelfEdit()
                if (composing.isNotEmpty()) {
                    lastDeletedComposingChar = composing.last()
                    lastDeletedComposingAt = SystemClock.uptimeMillis()
                    composing.deleteCharAt(composing.length - 1)
                    if (composingTrace.isNotEmpty()) composingTrace.removeAt(composingTrace.size - 1)
                    // setComposingText even when now empty: it replaces the
                    // field's composing region, so the LAST letter deletes on
                    // a single press. finishComposingText() alone would COMMIT
                    // that letter instead — that was the double-backspace bug.
                    ic.setComposingText(composing, 1)
                    if (composing.isEmpty()) ic.finishComposingText()
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
                    ic.deleteSurroundingText(units, 0)
                    // Deleting the full stop reopens the sentence: drop the
                    // boundary marker so the previous word steers again.
                    if (units == 1 && before.isNotEmpty() &&
                        SENTENCE_END.contains(before.last().toString()) &&
                        committedWords.lastOrNull() == SENTENCE_MARK
                    ) {
                        committedWords.removeLast()
                    }
                    // Backspacing into a plain-typed word re-enters composing
                    // on the remaining fragment, so predictions follow the
                    // edit ("worl" -> "world") instead of guessing blindly
                    // from the stale last word.
                    val afterDelete = before.dropLast(units)
                    val fragment = afterDelete.takeLastWhile { it.isLetter() }
                    if (fragment.isNotEmpty() &&
                        (afterDelete.length == fragment.length || afterDelete.dropLast(fragment.length).last() == ' ')
                    ) {
                        // Backspacing into a word we JUST learned (seconds ago)
                        // means it was a typo being fixed — unlearn the raw
                        // form before the fragment re-enters composing, or the
                        // correction loop re-teaches the mistake. Guarded to
                        // prefix matches of the tracked word only, so ordinary
                        // edits to older/established words never unlearn.
                        val learned = lastLearnedWord
                        if (!incognito && learned != null &&
                            SystemClock.uptimeMillis() - lastLearnedAt < 10_000 &&
                            learned.startsWith(fragment.lowercase())
                        ) {
                            lastLearnedWord = null
                            unlearnIfRecent(learned)
                        }
                        ic.deleteSurroundingText(fragment.length, 0)
                        composing.append(fragment)
                        // No trace for re-entered text: its taps are gone. (A4)
                        composingTrace.clear()
                        composingTraceValid = false
                        ic.setComposingText(composing, 1)
                        committedWords.lastOrNull()?.let { last ->
                            if (last.startsWith(fragment.lowercase())) committedWords.removeLast()
                        }
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
                finishComposingWord()
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

    /** Caret offset the selection is being dragged FROM; -1 = not selecting. */
    private var selectionAnchor: Int = -1

    /** Live selection, mirrored from onUpdateSelection / EditorInfo. -1 = unknown
     *  (an editor that reports nothing, e.g. some WebViews). */
    private var selStart: Int = -1
    private var selEnd: Int = -1

    /** Timestamp of the last prediction request, for burst detection. */
    private var lastKeystrokeAt: Long = 0L

    /**
     * Where the caret is, from the offsets the framework already pushes to us.
     *
     * The obvious implementation — getTextBeforeCursor(HUGE) and take its
     * length — pulls the entire field across a binder transaction on every
     * arrow key, and throws TransactionTooLargeException on a long document.
     * onUpdateSelection hands us the same number for free.
     */
    private fun caretPos(): Int = if (selStart >= 0) selStart else -1

    private fun toggleSelectionAnchor() {
        selectionAnchor = if (selectionAnchor >= 0) -1 else caretPos()
        keyboardView.invalidate()
    }

    private fun moveCursor(delta: Int) {
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
    }

    private fun sendCursorKey(keyCode: Int) {
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

    /** The space-press autocorrect DECISION for [raw]: pure — no editor
     *  mutation, no learning, no LLM. Runs inside the debounced prediction
     *  pass (and as a rare synchronous fallback on space) so the commit
     *  path itself never pays for the DL walk / spatial decode.
     *
     *  The commit decision goes through the engine's decisive-winner API:
     *  it returns null unless the top candidate beats the runner-up by a
     *  clear margin. A null here is NOT a failure — the caller leaves the
     *  word as typed and the top candidate stays on the bar as an ordinary
     *  suggestion (pendingCorrection stays false), so a borderline call is
     *  never silently committed. */
    private fun decideAutoFix(raw: String): String? = decideAutoFixProbe(raw).first

    /** Like [decideAutoFix], but also returns the engine's best candidate
     *  when the win was NOT decisive (second of the pair) — the bar shows it
     *  as an ordinary, non-committed suggestion. This matters most for the
     *  newly reachable cases: short (2-3 letter) words and real dictionary
     *  words, where the context-override rule inside the engine declines to
     *  auto-commit unless the context win is overwhelming. */
    private fun decideAutoFixProbe(raw: String): Pair<String?, String?> {
        if (!prefs.autoCorrect) return null to null
        val rawLower = raw.lowercase()
        if (suppressedAutoFix.contains(rawLower)) return null to null
        // A3: the contraction hard map fires BEFORE the length/known-word
        // gates — "im" is 2 chars and "dont" is (wrongly) a dictionary word,
        // so the classic path could never reach them.
        var fix = com.localboard.keyboard.engine.FastEngine.contractionFixFor(
            rawLower, fastEngine.userFrequency(rawLower)
        )
        if (raw.length < 2 && fix == null) return null to null
        val firstCap = raw.first().isUpperCase()
        val tail = if (firstCap) raw.substring(1) else raw
        // Only fix plain lowercase words — skip inner capitals/digits (this
        // also keeps assembled email tokens out of the corrector entirely).
        if (!tail.all { it.isLowerCase() }) return fix to null
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
                // A2: the committed words before this one resolve exact
                // geometric ties ("i wnat" -> want) — and, for known/short
                // words, decide whether a correction is justified at all.
                val prev1 = committedWords.lastOrNull() ?: ""
                val prev2 = committedWords.dropLast(1).lastOrNull() ?: ""
                val probe = fastEngine.correctionProbe(rawLower, prev1, prev2, prefs.autoCorrectLevel)
                fix = probe.first?.word
                hint = probe.second
            }
            // A missed space bar is not an edit-distance error, so the walk
            // above can't see it: "thisis" is nowhere near a real word and
            // returns nothing, leaving one unknown super-word. Only consulted
            // when the walk DID return nothing, so an ordinary typo is never
            // reinterpreted as a run-on.
            if (fix == null) {
                fix = fastEngine.splitRunOn(rawLower, prev1 = committedWords.lastOrNull() ?: "",
                    capitalized = firstCap)
                    ?.let { (a, b) -> "$a $b" }
            }
        }
        if (fix == rawLower) return null to hint
        return fix to hint
    }

    /** P1: the latest autocorrect verdict for the CURRENT composing word
     *  (raw-lowercase -> fix; a null fix means "leave it alone"). Computed
     *  by the debounced prediction pass; null whenever the word changed
     *  since the last pass. */
    private var cachedAutoFix: Pair<String, String?>? = null

    /** The engine's best correction candidate when [cachedAutoFix] found no
     *  decisive fix (raw-lowercase -> candidate). Shown as a plain bar
     *  suggestion — never auto-committed. This is where short-word and
     *  real-word corrections ("id" -> "it") surface when context alone
     *  doesn't justify rewriting what was typed. */
    private var cachedCorrectionHint: Pair<String, String>? = null

    /** Space-bar autocorrect: fix the in-flight word if it's a clear typo.
     *  Returns the original word when a correction was applied (so the bar
     *  can offer a one-tap revert), null otherwise. */
    private fun maybeAutoCorrectComposing(): String? {
        val ic = currentInputConnection ?: return null
        // Password/sensitive fields: no autocorrect, no typo signal, no LLM —
        // nothing here may ever see that text.
        if (!predictionsEnabled) return null
        if (!prefs.autoCorrect) return null
        val raw = composing.toString()
        // P1: the verdict was precomputed by the debounced prediction pass;
        // only the rare miss (typed + slammed space within 16ms) recomputes.
        val cached = cachedAutoFix
        val fix = if (cached != null && cached.first == raw.lowercase()) {
            cached.second
        } else {
            decideAutoFix(raw)
        }
        if (fix == null) return null
        if (raw.isEmpty()) return null
        val firstCap = raw.first().isUpperCase()
        val out = if (firstCap) fix.replaceFirstChar { it.uppercase() } else fix
        // DRIFT CHECK FIRST: verify the editor still holds exactly what our
        // buffer claims BEFORE touching the composing region. Clearing and
        // finishing first committed a word that might not even be there and
        // silently dropped our copy of it.
        val before = (ic.getTextBeforeCursor(raw.length, 0) ?: "").toString()
        if (!before.equals(raw, ignoreCase = true)) {
            // Editor state drifted from our buffer; skip rather than damage text.
            return null
        }
        // Atomic in-place replace: commit the raw word, delete it, insert the
        // fix — no composing-region churn, no underline flicker, and the
        // cursor never visibly moves (the caller wraps this in one batch
        // with the following space, so the editor applies it atomically).
        composing.clear()
        ic.finishComposingText()
        ic.deleteSurroundingText(raw.length, 0)
        ic.commitText(out, 1)
        // A run-on fix commits TWO words ("thisis" -> "this is"). Learning the
        // whole string would put "this is" into the dictionary as a single
        // entry, which then competes with real words and can never be typed.
        val wasSplit = out.indexOf(' ') >= 0
        if (wasSplit) {
            for (part in out.split(' ')) if (part.isNotBlank()) learnWord(part)
        } else {
            learnWord(out)
        }
        // The user accepted a correction AWAY from the raw word: it's a
        // confirmed typo — blacklist it so it can never reach the dictionary
        // (repeated finger-slips used to flood it with misspellings).
        //
        // NOT for a run-on split. The token that reaches splitRunOn is by
        // definition one the dictionary does not know — a name, a handle, a
        // brand — and noteTypo does two things that are exactly wrong for it:
        // it deletes the userUnigram count that is the ONLY thing keeping that
        // word from being split again, and on the second strike it suppresses
        // the word outright for thirty days. So every personal word the
        // splitter mis-cut was blacklisted by the act of mis-cutting it, twice
        // over, and the keyboard got monotonically worse at the vocabulary
        // this particular user actually types. A split is a guess about word
        // BOUNDARIES; it is never evidence that the letters were wrong.
        if (!wasSplit && !incognito) contextEngine.noteTypo(raw, currentAppPackage)
        // Autocorrect is a proven mis-tap signal when exactly one letter
        // changed to an adjacent key ("qhen" -> "when": teaches w's spot).
        if (raw.length == fix.length) {
            var diffAt = -1
            var diffs = 0
            for (i in raw.indices) {
                if (raw[i] != fix[i]) { diffs++; diffAt = i }
            }
            if (diffs == 1) {
                teachMisTap(raw[diffAt].lowercaseChar(), fix[diffAt].lowercaseChar())
            }
        }
        lastAutoFix = raw to out
        return raw
    }

    // LLM-assisted autocorrect used to live here: when the classic corrector
    // found nothing, the quick model was asked to pick a replacement and the
    // word was rewritten AFTER the space, asynchronously — text changing
    // under the user a beat after they had moved on, with no revert cue, and
    // the 0.6B model's pick was wrong often enough that the net effect was a
    // keyboard that felt unpredictable. Removed outright: autocorrect is
    // synchronous, geometric and n-gram-driven only, and a word the classic
    // path will not touch is left exactly as typed.

    // ------------------------------------------------------------------
    // AI phrase suggestions (opt-in): continue the message on a pause
    // ------------------------------------------------------------------

    private var aiSuggestJob: kotlinx.coroutines.Job? = null

    /** Generation guard, same idea as [swipePreviewToken]: bumped on every
     *  keystroke/commit/cancel so a late-landing result is dropped even when
     *  the job itself could not be interrupted mid-native-call. */
    private var aiSuggestToken = 0

    /** Context snapshot (last ~10 committed words) -> suggested phrase. An
     *  EMPTY phrase marks a failed/abandoned attempt for that snapshot, so
     *  one pause never re-fires generation in a loop. Keyed by snapshot
     *  rather than cleared on every refresh: any real typing changes the
     *  snapshot, which is what actually invalidates a suggestion. */
    private var cachedAiPhrase: Pair<String, String>? = null

    /** Full invalidation (job + generation + cache): field changes, page
     *  switches and the emoji panel — moments where even a snapshot match
     *  would be coincidence, not continuity. Keystrokes are handled cheaper
     *  in refreshPredictions (job + token only; the cache key does the rest). */
    private fun cancelAiSuggestion() {
        aiSuggestJob?.cancel()
        aiSuggestToken++
        cachedAiPhrase = null
        cachedAiWords = null
    }

    /** Context snapshot -> LLM next-word candidates (lowercase), best
     *  first. EMPTY marks a failed/abandoned attempt for that snapshot so
     *  one context never re-fires the model in a loop. */
    private var cachedAiWords: Pair<String, List<String>>? = null

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
     */
    private fun maybeLlmNextWords() {
        if (!predictionsEnabled || incognito || showingEmoji) return
        if (!prefs.llmSuggestions) return
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

    /** Tapping the ✨ chip: commit the phrase and tag it AI-origin, so the
     *  typing log records it for the privacy dashboard while n-gram/style
     *  learning never mistakes model output for the user's voice — the same
     *  treatment an accepted Write-It-For-Me draft gets. */
    private fun commitAiPhraseSuggestion(phrase: String) {
        val ic = currentInputConnection ?: return
        // Stale chip: the user typed since it rendered. Drop the commit
        // rather than splice a phrase into a half-typed word.
        if (composing.isNotEmpty()) return
        cancelAiSuggestion()
        beginSelfEdit()
        ic.commitText(phrase, 1)
        if (prefs.autoSpace) ic.commitText(" ", 1)
        endSelfEdit()
        autoSpacePending = prefs.autoSpace
        // aiOrigin=true logs one origin=1 row and updates in-memory prev-word
        // context, but skips n-gram, per-app vocab and style learning.
        recordWords(phrase, aiOrigin = true)
        if (prefs.autoSpace) updateAutoCaps()
        refreshPredictions()
    }

    /** UTF-16 units to delete so an emoji grapheme dies whole: surrogate
     *  pairs, variation selectors, ZWJ sequences, keycaps, RI flags. */
    private fun deleteUnitsFor(before: String): Int {
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
    private fun autoSpaceIsBeforeCursor(ic: android.view.inputmethod.InputConnection): Boolean {
        val before = (ic.getTextBeforeCursor(2, 0) ?: "").toString()
        return before.endsWith(" ") && !before.endsWith("  ")
    }

    /** Backspace right after a swipe/prediction commit: delete the whole word
     *  (and its auto-space) in one step. Returns false when the cursor has
     *  moved on or something else was typed since. */
    private fun wipeGestureCommitIfPending(ic: android.view.inputmethod.InputConnection): Boolean {
        autoSpacePending = false
        val (word, hadSpace) = lastGestureCommit ?: return false
        val probe = word.length + 1
        val before = (ic.getTextBeforeCursor(probe, 0) ?: "").toString()
        val matches = if (hadSpace) before == "$word " else before.endsWith(word)
        if (!matches) return false
        lastGestureCommit = null
        ic.deleteSurroundingText(if (hadSpace) word.length + 1 else word.length, 0)
        committedWords.lastOrNull()?.let { last ->
            if (last.equals(word, ignoreCase = true)) committedWords.removeLast()
        }
        // Wiping the word the decoder just committed is a REJECTION — give the
        // glide decoder the negative signal and pull the word back out of the
        // learned model (guarded: only while it's still lightly learned).
        if (prefs.swipeLearning && !incognito) {
            swipeDecoder?.noteRejectedCommit(word)
            persistSwipeBias()
        }
        unlearnIfRecent(word)
        return true
    }

    /** Undo a learning mistake: remove a word the engine just picked up — but
     *  ONLY while its user frequency is still at/below the promotion
     *  threshold. removeWordEverywhere suppresses the word outright (base
     *  dictionary included), so without this guard a heuristic revert could
     *  banish a genuine, established word. */
    private fun unlearnIfRecent(word: String) {
        if (incognito) return
        val w = word.trim().lowercase()
        if (w.length < 2) return
        // One occurrence back out of the personal model — never a removal
        // from the dictionary, never a suppression (see FastEngine.unlearnRecent).
        contextEngine.unlearnRecent(w)
    }

    /** Backspace / revert-chip right after an autocorrection restores the word
     *  exactly as typed and stops re-correcting it this session. */
    private fun undoAutoFixIfPending(): Boolean {
        val pair = lastAutoFix ?: return false
        val ic = currentInputConnection ?: return false
        val (raw, fixed) = pair
        val before = (ic.getTextBeforeCursor(fixed.length + 1, 0) ?: "").toString()
        lastAutoFix = null
        if (before != "$fixed ") return false
        suppressedAutoFix += raw.lowercase()
        // Reverting a correction is the strongest "this IS my word" signal
        // there is — promote it immediately (selection-weight learning).
        if (prefs.learningEnabled && !incognito) {
            contextEngine.learnWord(
                raw.lowercase(), "", "", currentAppPackage, selected = true
            )
        }
        // The rejected fix was LEARNED when it was applied — undo that too,
        // or the word the user just said "no" to keeps its boost (and for a
        // run-on fix, each half got its own entry). Guarded so an established
        // word is never removed over a single revert.
        if (fixed.indexOf(' ') >= 0) {
            for (part in fixed.split(' ')) if (part.isNotBlank()) unlearnIfRecent(part)
        } else {
            unlearnIfRecent(fixed)
        }
        beginSelfEdit()
        ic.deleteSurroundingText(fixed.length + 1, 0)
        composing.clear()
        composing.append(raw)
        ic.setComposingText(composing, 1)
        endSelfEdit()
        refreshPredictions()
        return true
    }

    // Progressive swipe-decode state: the preview runs mid-gesture, and a
    // finger-up that lands where a recent preview landed commits instantly.
    private var lastSwipeDecodeAt = 0L
    private var lastSwipeDecodeCount = 0
    private var lastSwipeDecodeEnd: PointF? = null
    private var lastSwipeResults: List<Suggestion>? = null
    /** Where the path that produced [lastSwipeResults] ended. Paired with
     *  the results when they LAND, not when the decode was dispatched, so
     *  the instant-commit check never matches a newer path against an
     *  older answer. */
    private var lastSwipeResultEnd: PointF? = null
    private var lastSwipeResultAt = 0L

    /** Live glide preview: decode mid-swipe and stream candidates to the bar. */
    private fun handleSwipeProgress(points: List<PointF>) {
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
        val prev1 = committedWords.lastOrNull()
        val token = ++swipePreviewToken
        previewJob?.cancel()
        previewJob = serviceScope.launch {
            val results = withContext(com.localboard.keyboard.util.TypingDispatcher.glide) {
                decoder.decode(snapshot, 4, prev1)
            }
            // A newer preview (or the finger-up commit) has superseded us.
            if (token != swipePreviewToken || !isActive) return@launch
            if (results.isNotEmpty()) {
                lastSwipeResults = rerankSwipeWithAi(results)
                lastSwipeResultEnd = snapshot.last()
                lastSwipeResultAt = SystemClock.uptimeMillis()
                predictionBar.showSuggestions(lastSwipeResults!!.take(3))
                // Same word, floating above the fingertip.
                keyboardView.setGlideWord(lastSwipeResults!!.first().text)
            }
        }
    }

    /** Guards against an older preview decode landing after a newer one. */
    private var swipePreviewToken = 0
    private var previewJob: Job? = null

    /** Long-press on the sparkle: a small prompt box; the active model drafts.
     *  When the field already holds text, a third choice appears: rewrite it
     *  (rephrase an email, fix tone…) — the draft replaces the old text only
     *  on success, so a failed run never eats what the user wrote. */
    private fun showQuickPrompt() {
        if (wifmJob?.isActive == true) return
        if (!::keyboardView.isInitialized) return
        // P5: instructions need a neural model — with the n-gram composer
        // active (none installed) the box would open, accept an
        // instruction, and fail at run time. Say what unlocks it instead.
        if (registry.active() is com.localboard.keyboard.llm.LocalStyleComposer) {
            Toast.makeText(
                this,
                "Quick prompts need a neural model — free download in Settings → AI model",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val icNow = currentInputConnection ?: return
        val ctx = ContextThemeWrapper(this, R.style.Theme_LocalBoard)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val fieldText = (icNow.getTextBeforeCursor(600, 0) ?: "").toString().trim()
        val input = EditText(ctx).apply {
            hint = if (fieldText.isEmpty()) "e.g. reply that I'm running 10 min late"
                   else "e.g. make it shorter / more formal — or say what to write"
            setPadding(pad, pad / 2, pad, 0)
        }
        val modelName = com.localboard.keyboard.llm.ModelRegistry.activeName(prefs)
        val builder = AlertDialog.Builder(ctx)
            .setTitle("✨ Quick prompt")
            .setMessage(
                if (fieldText.isEmpty())
                    "Say what to write — $modelName uses the conversation on screen as context."
                else
                    "Say what to write, or rewrite the text in the field — " +
                        "$modelName uses the conversation on screen as context."
            )
            .setView(input)
            .setPositiveButton("Write") { _, _ ->
                val instr = input.text.toString().trim()
                if (instr.isNotEmpty()) startWifmWhenReady(instr)
            }
            .setNegativeButton("Cancel", null)
        if (fieldText.isNotEmpty()) {
            builder.setNeutralButton("Rewrite this text") { _, _ ->
                val note = input.text.toString().trim()
                val instr = buildString {
                    append("Rewrite the following message")
                    if (note.isNotEmpty()) append(" according to this request: ").append(note)
                    append(". Output only the rewritten message, nothing else:\n")
                    append(fieldText.take(400))
                }
                startWifmWhenReady(instr, replaceText = fieldText)
            }
        }
        val dialog = builder.create()
        try {
            dialog.window?.let { w ->
                val lp = w.attributes
                lp.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
                // WMS expects the IME's own window token for input-method
                // dialogs; a wrong token throws BadTokenException at show().
                lp.token = window?.window?.attributes?.token ?: keyboardView.windowToken
                w.attributes = lp
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            dialog.show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Couldn't open the prompt box here", Toast.LENGTH_SHORT).show()
        }
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
    private fun rerankSwipeWithAi(results: List<Suggestion>): List<Suggestion> {
        if (results.size < 2) return results
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

    /** Returns true when the path decoded; false lets the view fall back to a tap. */
    private fun handleSwipe(points: List<PointF>): Boolean {
        if (!predictionsEnabled) return false
        val decoder = swipeDecoder ?: return false
        val prev1 = committedWords.lastOrNull()
        // Instant commit: the finger lifted right where a recent live preview
        // landed (the "swipe, then hold at the end" pattern). The preview
        // already decoded this exact path — commit its winner instead of
        // paying for a second full decode.
        val recent = lastSwipeResults
        val previewEnd = lastSwipeResultEnd
        if (recent != null && previewEnd != null &&
            SystemClock.uptimeMillis() - lastSwipeResultAt < 200
        ) {
            val end = points.last()
            val dx = end.x - previewEnd.x; val dy = end.y - previewEnd.y
            if (kotlin.math.sqrt(dx * dx + dy * dy) <
                keyboardView.geometry.averageKeyWidth * 0.6f
            ) {
                lastSwipeTop = recent.first().text
                lastSwipeResults = recent
                glideAlternates = recent.drop(1).take(3).takeIf { it.isNotEmpty() }
                commitWordFromSuggestion(recent.first().text, fromSwipe = true)
                glideAlternates?.let { predictionBar.showSuggestions(it) }
                return true
            }
        }
        lastSwipeResults = null
        // Finger-up decode stays synchronous: its boolean return decides
        // whether the view falls back to treating the gesture as a tap, so it
        // cannot be deferred. It is now allocation-free, so the cost is a few
        // ms rather than the ~1M-object churn that made glide feel laggy.
        swipePreviewToken++          // invalidate any in-flight preview
        val results = rerankSwipeWithAi(decoder.decode(points, 4, prev1))
        if (results.isEmpty()) return false
        lastSwipeTop = results.first().text
        lastSwipeResults = results
        // Offer alternates in the bar for quick correction — and HOLD them
        // there (see glideAlternates) through the prediction refresh the
        // commit schedules.
        glideAlternates = results.drop(1).take(3).takeIf { it.isNotEmpty() }
        commitWordFromSuggestion(results.first().text, fromSwipe = true)
        glideAlternates?.let { predictionBar.showSuggestions(it) }
        return true
    }

    /** Backspace held down: delete the previous whole word at once. */
    private fun deleteWordBackward() {
        val ic = currentInputConnection ?: return
        beginSelfEdit()
        if (composing.isNotEmpty()) {
            composing.clear()
            ic.setComposingText("", 1)
        } else {
            val before = (ic.getTextBeforeCursor(48, 0) ?: "").toString()
            val trimmed = before.trimEnd(' ')
            val cut = trimmed.lastIndexOf(' ')
            val deleteCount = before.length - if (cut >= 0) cut + 1 else 0
            if (deleteCount > 0) {
                ic.deleteSurroundingText(deleteCount, 0)
                // The sentence-context trail mirrors the field: dropping a
                // whole word drops it there too, or stale prev-words keep
                // steering predictions toward what was just erased.
                val deletedWord = trimmed.substringAfterLast(' ')
                committedWords.lastOrNull()?.let { last ->
                    if (last.equals(deletedWord, ignoreCase = true)) {
                        committedWords.removeLast()
                    }
                }
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
        endSelfEdit()
        refreshPredictions()
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    private fun refreshPredictions(revertWord: String? = null) {
        // CRASH FIX: the framework calls onUpdateSelection() — which lands
        // here — before onCreateInputView() has run, so these lateinits are
        // not yet assigned:
        //   UninitializedPropertyAccessException: lateinit property
        //   predictionBar has not been initialized
        // There is nothing to refresh before the views exist; the next real
        // input event repaints anyway.
        if (!::predictionBar.isInitialized || !::keyboardView.isInitialized) return
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
        cachedAutoFix = null
        cachedCorrectionHint = null
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
            val prev1 = committedWords.lastOrNull() ?: ""
            val prev2 = committedWords.dropLast(1).lastOrNull() ?: ""
            // Snapshot the AI phrase suggestion is keyed to (last ~10 words).
            val ctxSnapshot = committedWords.takeLast(10).joinToString(" ")
            val raw = composing.toString()
            val capitalize = keyboardView.shiftState != KeyboardView.ShiftState.OFF &&
                composing.isEmpty()
            // P1: precompute the space-press autocorrect verdict NOW, in the
            // inter-keystroke lull — space then applies the cached verdict
            // instead of running the DL walk + spatial decode on the commit
            // path (the last remaining per-keystroke stall on big words).
            // P2: the verdict ALSO builds the bar — the center slot shows
            // what space will insert.
            //
            // Both of these are pure reads of the engine, and the correction
            // walk explores up to 120k trie nodes — far too much to sit on the
            // main thread between keystrokes. The engine's personal-model maps
            // are ConcurrentHashMap precisely so this can run off-main; only
            // the UI update below comes back to the main thread.
            val (fixAndHint, suggestions) = withContext(com.localboard.keyboard.util.TypingDispatcher.io) {
                val f = if (raw.isNotEmpty() && isActive) decideAutoFixProbe(raw)
                    else (null to null)
                val s = router.route(
                    prefix = raw,
                    prev1 = prev1,
                    prev2 = prev2,
                    appPackage = currentAppPackage,
                    capitalize = capitalize
                )
                f to s
            }
            val fix = fixAndHint.first
            val fixHint = fixAndHint.second
            // Learned email completions, surfaced in email context only. The
            // predictionsEnabled early-return above already keeps these out
            // of password/sensitive fields.
            val emailSugs = if (isActive && (isEmailField || raw.contains('@'))) {
                withContext(com.localboard.keyboard.util.TypingDispatcher.io) {
                    fastEngine.emailCompletions(raw, limit = 2)
                }
            } else emptyList()
            if (composing.toString() == raw) {
                cachedAutoFix = raw.lowercase() to fix
                if (fixHint != null && fix == null) {
                    cachedCorrectionHint = raw.lowercase() to fixHint
                }
            }
            // P2: while composing, the layout is Gboard-style — center = the
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
                val center = centerText?.let { Suggestion(it, 1.0) }
                    ?: emailSugs.firstOrNull()?.let { Suggestion(it, 1.0) }
                    ?: hintText?.let { Suggestion(it, 1.0) }
                    ?: aiCompletion?.let { Suggestion(it, 1.0) }
                    ?: pool.firstOrNull()
                val right = aiCompletion?.takeIf { !it.equals(center?.text, ignoreCase = true) }
                    ?.let { Suggestion(it, 1.0) }
                    ?: pool.firstOrNull { it.text != center?.text }
                listOfNotNull(center, Suggestion(raw, 0.0), right)
            } else if (!typedThisField && revertWord == null) {
                // Nothing typed in this field yet — no starter-word guesses,
                // no email chips, no AI phrase. The bar stays empty until the
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
                // AI phrase suggestion (opt-in): only shown while the exact
                // context it was generated for is still current — any typing
                // since changes the snapshot and the chip vanishes. First in
                // the list = the bar's leading (center) slot; the "✨ " marker
                // doubles as the tap-route tag in acceptSuggestion.
                val aiPhrase = cachedAiPhrase?.takeIf {
                    it.first == ctxSnapshot && it.second.isNotEmpty()
                }?.second
                // LLM next-word predictions for exactly this context: they
                // lead, the n-gram engine's best keeps a slot, then the
                // model's runner-up. The model reads the whole message; the
                // n-gram side carries the user's own two-word habits — the
                // interleave keeps both voices on the bar.
                val aiWords = cachedAiWords?.takeIf {
                    it.first == ctxSnapshot && it.second.isNotEmpty()
                }?.second
                if (aiPhrase != null) {
                    (listOf(Suggestion(AI_PHRASE_MARK + aiPhrase, 2.0)) + base)
                        .distinctBy { it.text }.take(3)
                } else if (aiWords != null) {
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
            // A just-committed glide keeps its alternates on the bar; the
            // next-word predictions computed above still primed the caches.
            val held = glideAlternates
            predictionBar.showSuggestions(if (held != null && raw.isEmpty()) held else display)
            updateClipboardChip()
        }
        // Opt-in AI next-word prediction: scheduled AFTER the normal pass so
        // the n-gram bar is up first and the model only refines it.
        // Self-gates on pref/model/power/context; no-op when mid-word.
        maybeLlmNextWords()
    }

    private fun acceptSuggestion(text: String) {
        // AI phrase chip: strip the display marker and commit as AI-origin
        // text (never feeds style learning). Checked before anything else —
        // the marker can never collide with a real word or an emoji.
        if (text.startsWith(AI_PHRASE_MARK)) {
            commitAiPhraseSuggestion(text.removePrefix(AI_PHRASE_MARK))
            return
        }
        // Tapping the "as typed" revert chip undoes the last autocorrection.
        val pair = lastAutoFix
        if (pair != null && text.equals(pair.first, ignoreCase = true) &&
            undoAutoFixIfPending()
        ) {
            return
        }
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
                    committedWords.lastOrNull()?.let { last ->
                        if (last.equals(word, ignoreCase = true)) committedWords.removeLast()
                    }
                    if (prefs.swipeLearning && !incognito) swipeDecoder?.noteRejectedCommit(word)
                    unlearnIfRecent(word)
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
        commitWordFromSuggestion(text, fromSwipe = false)
    }

    /** The word the last gesture decoded to, so we can tell "the user picked
     *  an alternate" apart from "the user tapped an ordinary prediction". */
    private var lastSwipeTop: String? = null

    /**
     * Feed a rejected-top glide back to the decoder. Only fires when the bar
     * is still showing that gesture's alternates and the pick differs from
     * what we committed — a plain prediction tap is not a swipe correction.
     */
    private fun noteSwipeCorrection(picked: String) {
        if (!prefs.swipeLearning || incognito) return
        val top = lastSwipeTop ?: return
        val alternates = lastSwipeResults
        if (alternates == null || alternates.isEmpty()) return
        if (picked.equals(top, ignoreCase = true)) return
        if (alternates.none { it.text.equals(picked, ignoreCase = true) }) return
        swipeDecoder?.noteChosenAlternate(picked)
        lastSwipeTop = null
        persistSwipeBias()
    }

    private var biasPersistJob: Job? = null

    /** Debounced: a burst of corrections writes once. */
    private fun persistSwipeBias() {
        biasPersistJob?.cancel()
        biasPersistJob = serviceScope.launch {
            delay(4_000)
            val snap = swipeDecoder?.biasSnapshot() ?: return@launch
            prefs.swipeBiasData = snap.entries
                .joinToString("\n") { "${it.key}\t${it.value}" }
        }
    }

    private fun restoreSwipeBias() {
        val d = swipeDecoder ?: return
        val m = HashMap<String, Int>()
        for (line in prefs.swipeBiasData.split('\n')) {
            val t = line.indexOf('\t')
            if (t <= 0) continue
            val v = line.substring(t + 1).toIntOrNull() ?: continue
            m[line.substring(0, t)] = v
        }
        d.restoreBias(m)
    }

    private fun looksLikeEmoji(text: String): Boolean =
        text.any { it.isHighSurrogate() || it in '\u2600'..'\u27BF' }

    private fun commitWordFromSuggestion(word: String, fromSwipe: Boolean) {
        val ic = currentInputConnection ?: return
        typedThisField = true
        // Accepting the cached pending correction by tap is the same verdict
        // as accepting an autocorrect: the raw composing word is a typo.
        // The classic space path records this in maybeAutoCorrectComposing;
        // the center-chip route used to skip it.
        val rawBefore = composing.toString()
        val pending = cachedAutoFix
        if (!incognito && rawBefore.isNotEmpty() && pending != null &&
            pending.first == rawBefore.lowercase() &&
            pending.second != null && pending.second.equals(word, ignoreCase = true) &&
            !word.equals(rawBefore, ignoreCase = true)
        ) {
            contextEngine.noteTypo(rawBefore, currentAppPackage)
        }
        beginSelfEdit()
        if (composing.isNotEmpty()) {
            ic.setComposingText(word, 1)
        } else {
            ic.commitText(word, 1)
        }
        composing.clear()
        ic.finishComposingText()
        val suffix = if (prefs.autoSpace) " " else ""
        if (suffix.isNotEmpty()) ic.commitText(suffix, 1)
        endSelfEdit()

        lastGestureCommit = word to suffix.isNotEmpty()
        autoSpacePending = suffix.isNotEmpty()
        // A prediction-bar tap is explicit "yes, this is my word" intent —
        // it counts heavily toward dictionary promotion. A swipe commit is
        // deliberate too, but gesture errors exist, so it counts normally.
        learnWord(word, selected = !fromSwipe)
        // An explicit bar tap also confirms the word to the engine directly —
        // the strongest "the user meant exactly this" signal there is.
        if (!fromSwipe && !incognito && predictionsEnabled) contextEngine.noteConfirmed(word)
        if (prefs.autoSpace) updateAutoCaps()
        refreshPredictions()
    }

    // ------------------------------------------------------------------
    // Write It For Me
    // ------------------------------------------------------------------

    /** The quick-prompt dialog steals focus while it's open, which kills the
     *  app's InputConnection — generating into the OLD one silently dropped
     *  every token (the "quick prompt does nothing" bug). Wait for the
     *  dialog to close and the app field to come back with a FRESH
     *  connection, then generate. */
    private fun startWifmWhenReady(
        instruction: String,
        attempts: Int = 0,
        replaceText: String? = null
    ) {
        if (!::keyboardView.isInitialized) return
        if (attempts == 0) wifmRetryStopped = false
        keyboardView.postDelayed({
            if (wifmRetryStopped) return@postDelayed
            if (currentInputConnection != null) {
                startWifm(instruction, replaceText = replaceText)
            } else if (attempts < 5) {
                startWifmWhenReady(instruction, attempts + 1, replaceText)
            }
        }, if (attempts == 0) 300L else 200L)
    }

    private fun startWifm(
        instruction: String? = null,
        targetIc: android.view.inputmethod.InputConnection? = null,
        replaceText: String? = null
    ) {
        if (wifmJob?.isActive == true) return
        val ic = targetIc ?: currentInputConnection ?: return
        // Rich context: grab ALL the text the field will give us before the
        // cursor (long drafts are exactly what continuation needs) plus
        // whatever follows (subject lines, quoted replies, the rest of the
        // draft). Editors cap how much they return; the prompt-fitting pass
        // downstream trims to the actual context window.
        // P5: the field-text stream has its own switch — when it's off the
        // model drafts from the screen + typing-log streams alone.
        var contextText = if (prefs.fieldTextEnabled) {
            (ic.getTextBeforeCursor(4000, 0) ?: "").toString()
        } else ""
        val afterText = if (prefs.fieldTextEnabled) {
            (ic.getTextAfterCursor(500, 0) ?: "").toString().trim()
        } else ""
        finishComposingWord()

        predictionBar.setWifmStreaming("✨ …")
        lastWifmDraft = ""
        wifmPushedLen = 0
        wifmReplaceText = replaceText
        // Freshen the screen buffer NOW: the last periodic capture may be
        // seconds old, and the draft should see the message that just
        // arrived. The capture walks the view tree on the service's worker
        // thread — give it a beat before assembly reads the buffer.
        val freshCapture = com.localboard.keyboard.context
            .ScreenContextService.instance?.captureSoon()
        wifmJob = serviceScope.launch {
            val buffer = StringBuilder()
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
                val onScreen = com.localboard.keyboard.context.ScreenBuffer
                    .get(currentAppPackage, 1400)
                // Screen-blind apps (Signal's screen security, banking apps,
                // incognito fields): the accessibility stream is empty by
                // design, so the user's OWN recent messages in this app
                // become the primary context — give them real depth. When
                // the screen stream works they stay a 500-char side note.
                val screenBlind = onScreen.isBlank()
                val remembered = contextEngine.recentTypedContext(
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

                // PREVIEW-THEN-COMMIT: tokens stream to the prediction bar
                // only — never into the field. A rejected run used to erase
                // text the user had just watched appear ("it wrote, then
                // deleted itself"); now a failed run touches nothing, and a
                // successful one lands its validated text in one commit.
                var cleaned = runGeneration(ic, buffer, contextText, afterText, firstInstr,
                    temperature = if (instruction != null) 0.55f else 0.65f,
                    streamToField = false)

                // Degenerate run — blank output, "yep"-class one-liner, or a
                // parroted prompt line that the sanitizer killed: retry once,
                // warmer, against the instruction-shaped prompt (switching
                // prompt SHAPE breaks the parrot pattern, not just the seed).
                if (WORD_SPLIT.split(cleaned).filter { it.isNotBlank() }.size <= 2 &&
                    instruction == null
                ) {
                    buffer.setLength(0)
                    beginSelfEdit()
                    ic.setComposingText("", 1)
                    endSelfEdit()
                    cleaned = runGeneration(ic, buffer, contextText, afterText,
                        firstInstr ?: RETRY_INSTRUCTION,
                        temperature = 0.8f,
                        streamToField = false)
                    // The warmer retry ALSO came back degenerate ("yep!") —
                    // the model is stuck on this context. NEVER auto-commit a
                    // two-word shrug into the user's text: blanking here routes
                    // to the failure path below, which clears the stream and
                    // says so. (Instruction runs keep short outputs — "say ok"
                    // legitimately produces one word.)
                    if (WORD_SPLIT.split(cleaned).filter { it.isNotBlank() }.size <= 2) {
                        cleaned = ""
                    }
                }

                if (cleaned.isNotBlank()) {
                    // AUTO-COMMIT the finished draft: a draft left sitting in
                    // the composing region looks like "it wrote something and
                    // then did nothing with it". The text is final, editable,
                    // and learned from — exactly as if the user typed it.
                    // Streaming pushes are throttled, so the field may lag the
                    // buffer: ALWAYS land the final text before committing.
                    beginSelfEdit()
                    val replace = wifmReplaceText
                    if (replace != null) {
                        // Rewrite mode: swap old text for the draft — but only
                        // after verifying the field still holds exactly what we
                        // promised to replace. A failed run never touches it.
                        val before = (ic.getTextBeforeCursor(replace.length, 0) ?: "").toString()
                        if (before == replace) ic.deleteSurroundingText(replace.length, 0)
                        ic.commitText(cleaned, 1)
                    } else {
                        ic.setComposingText(cleaned, 1)
                        ic.finishComposingText()
                    }
                    endSelfEdit()
                    wifmPushedLen = 0
                    wifmReplaceText = null
                    recordWords(cleaned, aiOrigin = true)
                    predictionBar.showSuggestions(emptyList())
                    updateAutoCaps()
                    refreshPredictions()
                } else {
                    // Nothing usable — clear whatever streamed in and SAY so,
                    // instead of the text silently vanishing. (Rewrite mode
                    // streamed only to the bar, so the field is untouched.)
                    beginSelfEdit()
                    if (wifmReplaceText == null) {
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                    }
                    endSelfEdit()
                    wifmPushedLen = 0
                    wifmReplaceText = null
                    predictionBar.showSuggestions(emptyList())
                    // Steer tiny-model users toward a model that can draft:
                    // repeated "sanitized to empty"/"echo rejected" runs mean
                    // the model, not the context.
                    val tinyActive = com.localboard.keyboard.llm.ModelRegistry
                        .isTinyModel(com.localboard.keyboard.llm.ModelRegistry.active(prefs))
                    val composerActive = registry.active() is com.localboard.keyboard.llm.LocalStyleComposer
                    Toast.makeText(
                        this@LLMKeyboardService,
                        when {
                            // B9: n-grams can't follow instructions — say what
                            // actually unlocks quick prompts.
                            composerActive && instruction != null ->
                                "Quick prompts need a neural model — free download in " +
                                    "Settings → AI model"
                            composerActive ->
                                "Drafting needs a neural model for this one — " +
                                    "Settings → AI model"
                            tinyActive ->
                                "Couldn't draft this one — the 230M/360M models struggle " +
                                    "here; Qwen3 0.6B drafts much better (Settings → AI model)"
                            else -> "Couldn't draft this one — try again"
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // A genuine crash mid-run: clear the partial stream and say so.
                beginSelfEdit()
                if (wifmReplaceText == null) {
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                }
                endSelfEdit()
                wifmPushedLen = 0
                wifmReplaceText = null
                predictionBar.showSuggestions(emptyList())
                Toast.makeText(
                    this@LLMKeyboardService,
                    "Drafting failed here — try again",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** B7: register from observed signal when the package map draws a blank. */
    private fun derivedToneFallback(): String {
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
    private fun mergeContextWindows(screen: String, remembered: String): String {
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
    private fun enrichedAppTone(): String {
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

    /** Streams one generation into the field; returns the cleaned final text.
     *  Bounded by a hard timeout: a model that hangs (or a first load that
     *  takes forever) used to leave "✨ …" spinning forever — now it cancels,
     *  clears, and the caller reports the failure. */
    private suspend fun runGeneration(
        ic: android.view.inputmethod.InputConnection,
        buffer: StringBuilder,
        contextText: String,
        afterText: String,
        instruction: String?,
        temperature: Float,
        streamToField: Boolean = true
    ): String {
        // Three gates, all cheap, all checked at the single point every
        // generation passes through rather than at each of the callers.
        //
        // Pro is checked HERE rather than at the ✨ button because every
        // drafting path — button, quick prompt, rewrite — funnels through this
        // function, so there is no second entry point to forget about.
        if (!com.localboard.keyboard.billing.Pro.has(
                com.localboard.keyboard.billing.ProFeature.AI_DRAFTING
            )
        ) {
            Toast.makeText(this, "AI writing is part of Pro", Toast.LENGTH_SHORT).show()
            return ""
        }
        if (!aiAllowedHere) {
            Toast.makeText(this, "AI is turned off for this app", Toast.LENGTH_SHORT).show()
            return ""
        }
        com.localboard.keyboard.util.PowerGate.blockReason(this, prefs)?.let { why ->
            Toast.makeText(this, "AI paused — $why", Toast.LENGTH_SHORT).show()
            return ""
        }
        var live = true
        // Tokens arrive faster than the eye reads; pushing every single one
        // into the editor is per-token binder + layout churn for nothing.
        // Push at ~11 Hz; the final text always lands when the run ends.
        var lastPushAt = 0L
        val cleaned = try {
            kotlinx.coroutines.withTimeoutOrNull(GEN_TIMEOUT_MS) {
                registry.active().generate(
                    GenerateRequest(
                        contextBefore = contextText,
                        // The auto-learned profile is gated: a skewed sample
                        // (e.g. a typing log full of one-word replies) makes
                        // every draft a mumble — measurably worse than no
                        // profile. The test lab's A/B pass shows it live.
                        styleProfile = contextEngine.profile
                            .takeIf { prefs.llmUseStyleProfile },
                        maxWords = if (instruction != null) 60 else 36,
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
                        else null
                    )
                ) { token ->
                    if (live) {
                        buffer.append(token)
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPushAt >= 90) {
                            lastPushAt = now
                            val snapshot = buffer.toString()
                            lastWifmDraft = snapshot
                            // Rewrite mode never streams into the field — the
                            // old text stays put until a successful finish.
                            if (streamToField) {
                                beginSelfEdit()
                                ic.setComposingText(snapshot, 1)
                                endSelfEdit()
                                wifmPushedLen = snapshot.length
                            }
                            predictionBar.setWifmStreaming(snapshot)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // User cancel (discard/confirm tap) is NOT a failure — let it
            // propagate so no "couldn't draft" toast fires on a discard.
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
        } finally {
            live = false
            // The full buffer is the source of truth (pushes lag behind it).
            lastWifmDraft = buffer.toString()
        }
        if (cleaned == null) {
            // Timed out (or threw): stop the native run and report failure.
            registry.active().cancel()
            com.localboard.keyboard.llm.AiDiagLog.add(
                com.localboard.keyboard.llm.AiDiagLog.Entry(
                    at = System.currentTimeMillis(),
                    model = "",
                    purpose = if (instruction != null) "quick prompt" else "draft",
                    promptTokens = 0, reusedTokens = 0, promptMs = 0,
                    genTokens = 0, genMs = 0, finish = -1,
                    note = "timeout/engine error — run aborted after ${GEN_TIMEOUT_MS / 1000}s"
                )
            )
            return ""
        }
        // generate() returns the echo-stripped text; if it differs from
        // what streamed into the field, fix the field now.
        if (streamToField && cleaned.length != buffer.length && cleaned.length <= buffer.length) {
            lastWifmDraft = cleaned
            beginSelfEdit()
            ic.setComposingText(cleaned, 1)
            endSelfEdit()
            wifmPushedLen = cleaned.length
            predictionBar.setWifmStreaming(cleaned)
        }
        return cleaned
    }

    private fun confirmWifm() {
        val ic = currentInputConnection ?: return
        wifmJob?.cancel()
        registry.active().cancel()
        val drafted = predictionBarTextSnapshot()
        val replace = wifmReplaceText
        beginSelfEdit()
        if (replace != null && drafted.isNotEmpty()) {
            // Confirming a rewrite mid-stream: same verified swap as the
            // auto-commit path.
            val before = (ic.getTextBeforeCursor(replace.length, 0) ?: "").toString()
            if (before == replace) ic.deleteSurroundingText(replace.length, 0)
            ic.commitText(drafted, 1)
        } else {
            // Streaming pushes are throttled — land the latest snapshot before
            // committing so a fast confirm doesn't clip the draft's tail.
            if (drafted.isNotEmpty() && drafted.length != wifmPushedLen) {
                ic.setComposingText(drafted, 1)
            }
            ic.finishComposingText()
        }
        endSelfEdit()
        wifmPushedLen = 0
        wifmReplaceText = null
        if (drafted.isNotBlank()) {
            recordWords(drafted, aiOrigin = true)
        }
        predictionBar.showSuggestions(emptyList())
        updateAutoCaps()
        refreshPredictions()
    }

    private fun discardWifm() {
        val ic = currentInputConnection ?: return
        wifmJob?.cancel()
        registry.active().cancel()
        beginSelfEdit()
        ic.finishComposingText()
        // Remove the streamed draft that was left as committed/composing text.
        // setComposingText marked the region; finishing leaves it committed,
        // so delete what was ACTUALLY pushed — the snapshot can be ahead of
        // the field, and deleting its length would eat the user's own text.
        // (Rewrite mode never pushed anything, so there is nothing to remove.)
        if (wifmPushedLen > 0) ic.deleteSurroundingText(wifmPushedLen, 0)
        endSelfEdit()
        wifmPushedLen = 0
        wifmReplaceText = null
        predictionBar.showSuggestions(emptyList())
        refreshPredictions()
    }

    private var lastWifmDraft: String = ""
    /** Characters of the draft currently present in the editor's composing
     *  region (streaming pushes are throttled, so this can lag the buffer). */
    private var wifmPushedLen = 0
    /** Field text a rewrite run must replace on success (null = append mode). */
    private var wifmReplaceText: String? = null
    private fun predictionBarTextSnapshot(): String = lastWifmDraft

    // ------------------------------------------------------------------
    // Learning helpers
    // ------------------------------------------------------------------

    private fun learnWord(word: String, selected: Boolean = false) {
        // Never learn from password / sensitive fields (predictions disabled
        // there), nor from a field that asked for incognito.
        if (!predictionsEnabled || incognito) return
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

    /** B3: words committed since the last message boundary. The encrypted
     *  typing log stores whole MESSAGES now — one row per send — instead of
     *  one row per word (which starved voice examples, recent context and
     *  the nightly distiller simultaneously). */
    private val messageWords = ArrayList<String>()

    private fun recordWords(
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
            for (w in WORD_SPLIT.split(text)) {
                val clean = w.trim().lowercase()
                if (clean.isNotEmpty()) {
                    committedWords.addLast(clean)
                    while (committedWords.size > 40) committedWords.removeFirst()
                }
            }
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
        for (w in WORD_SPLIT.split(text)) {
            val clean = w.trim().lowercase()
            if (clean.isNotEmpty()) {
                committedWords.addLast(clean)
                while (committedWords.size > 40) committedWords.removeFirst()
                messageWords.add(clean)
            }
        }
        // Pathological safety: never accumulate a novel.
        while (messageWords.size > 240) messageWords.removeAt(0)
    }

    /** A message boundary: SEND/GO/DONE, Enter in a single-line field, the
     *  field emptying after having content, or the keyboard/field going away.
     *  The accumulated words become ONE typing-log row tagged with the
     *  thread key — this is what "messages I sent recently" actually means. */
    private fun flushMessageLog(reason: String, harvestComposing: Boolean = false) {
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
    private fun hintThreadKey(): String {
        val h = currentInputEditorInfo?.hintText?.toString()?.trim() ?: return ""
        val stripped = h.removePrefix("Message to ").removePrefix("Message ").trim()
        return if (stripped.length in 2..40) "hint:$stripped" else ""
    }

    private fun finishComposingWord() {
        if (composing.isEmpty()) return
        val ic = currentInputConnection
        val word = composing.toString()
        composing.clear()
        composingTrace.clear()
        composingTraceValid = true

        // SNIPPETS: swap the trigger for its expansion at the moment the word
        // is committed. Done here rather than in autocorrect so it survives
        // every commit route (space, punctuation, enter) and cannot be
        // second-guessed by the corrector.
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
            recordWords(expansion)
            updateAutoCaps()
            return
        }

        beginSelfEdit()
        ic?.finishComposingText()
        endSelfEdit()
        learnWord(word)
    }

    /** Last expansion, so backspace immediately after can undo it. */
    private var lastSnippet: Pair<String, String>? = null

    /** Backspace right after an expansion restores the trigger. Without this
     *  an accidental expansion costs the user a long manual delete. */
    private fun undoSnippetIfPending(): Boolean {
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

    /** Style-level learning when a sentence ends (n-grams already learned per word). */
    private fun maybeLearnSentence(separator: String) {
        val ends = SENTENCE_END.contains(separator) || separator == "\n"
        if (ends && prefs.learningEnabled && !incognito && committedWords.isNotEmpty()) {
            contextEngine.onSentenceCommitted(
                committedWords.takeLast(24).joinToString(" ")
            )
        }
        // The sentence is over: the word before the full stop must not go
        // on steering next-word prediction ("tomorrow." was offering
        // "morning"). A boundary token takes its place as prev1, so the bar
        // falls back to sentence starters — and, because the boundary is
        // learned as an ordinary prev-word, the user's own openers ("lol",
        // "ok", "i") take over from the generic list within a few sentences.
        if (ends && committedWords.lastOrNull() != SENTENCE_MARK) {
            committedWords.addLast(SENTENCE_MARK)
            while (committedWords.size > 40) committedWords.removeFirst()
        }
    }

    private fun updateAutoCaps() {
        if (!prefs.autoCaps) return
        val ic = currentInputConnection ?: return
        val before = (ic.getTextBeforeCursor(4, 0) ?: "").toString()
        val shouldCap = before.isEmpty() || before.endsWith(". ") ||
            before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")
        keyboardView.shiftState =
            if (shouldCap) KeyboardView.ShiftState.ON else KeyboardView.ShiftState.OFF
    }

    // ------------------------------------------------------------------
    // Emoji panel
    // ------------------------------------------------------------------

    private val emojiCallback = object : EmojiPanel.Callback {
        override fun onEmojiSelected(emoji: String) {
            try {
                val ic = currentInputConnection ?: return
                beginSelfEdit()
                ic.beginBatchEdit()
                try {
                    if (!composing.isEmpty()) {
                        ic.commitText(composing.toString(), 1)
                        composing.setLength(0)
                    }
                    ic.commitText(emoji, 1)
                } finally {
                    ic.endBatchEdit()
                    endSelfEdit()
                }
                prefs.addRecentEmoji(emoji)
                refreshEmojiRow()
            } catch (t: Throwable) {
                android.util.Log.e("LLMKeyboard", "emoji select failed", t)
            }
        }

        override fun onEmojiBackspace() {
            val ic = currentInputConnection ?: return
            if (!composing.isEmpty()) {
                composing.deleteCharAt(composing.length - 1)
                beginSelfEdit()
                ic.setComposingText(composing.toString(), 1)
                endSelfEdit()
                refreshPredictions()
                return
            }
            val before = (ic.getTextBeforeCursor(24, 0) ?: "").toString()
            if (before.isEmpty()) return
            beginSelfEdit()
            ic.deleteSurroundingText(deleteUnitsFor(before), 0)
            endSelfEdit()
        }

        override fun onCloseEmojiPanel() = closeEmojiPanel()

        override fun onEmojiSpace() {
            handleText(" ")
            // Same convention as the symbols page: space means "back to
            // words" — close the panel too.
            closeEmojiPanel()
        }

        override fun onEmojiEnter() {
            val ic = currentInputConnection ?: return
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE
            if (action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                flushMessageLog("emoji-enter-action")
                ic.performEditorAction(action)
            } else {
                beginSelfEdit()
                ic.commitText("\n", 1)
                endSelfEdit()
            }
        }
    }

    private fun showEmojiPanel() {
        try {
            // Already open: applyKeyboardModel() re-enters here on every model
            // refresh; recreating the panel mid-scroll would reset position.
            if (emojiPanel?.parent === container) return
            // The container is wrap_content: capture the keyboard's height
            // BEFORE removing it, so the panel matches it exactly and the IME
            // window doesn't collapse or balloon (that blanked the keyboard).
            val kbHeight = keyboardView.height.takeIf { it > 0 }
                ?: container.height.takeIf { it > 0 }
                ?: (resources.displayMetrics.density * 264).toInt()
            // THE KEYBOARD MUST NOT CHANGE HEIGHT.
            //
            // Hiding the suggestion bar frees a row, but the IME window is
            // wrap_content: hiding it ALONE made the whole keyboard shorter
            // when emoji opened and taller again on the way out. That jump is
            // what made the emoji page feel like a different surface instead
            // of another page of the same keyboard.
            //
            // So the freed row goes to the PANEL rather than back to the
            // window: panel height = keyboard height + bar height, leaving the
            // total exactly as it was, with the grid genuinely a row taller.
            val barHeight = if (::predictionBar.isInitialized &&
                predictionBar.visibility == View.VISIBLE
            ) {
                predictionBar.height.takeIf { it > 0 }
                    ?: resources.getDimensionPixelSize(R.dimen.prediction_bar_height)
            } else 0
            if (::predictionBar.isInitialized) predictionBar.visibility = View.GONE
            val panelHeight = kbHeight + barHeight
            // ONE panel instance, re-attached on each open instead of rebuilt:
            // construction walks the whole emoji catalog and lays out the
            // grid, which is pure churn on every open/close cycle. The layout
            // params are re-set on each attach, so a height change (rotation)
            // still lands.
            val reused = emojiPanel != null
            val panel = emojiPanel ?: EmojiPanel(
                this, prefs, theme ?: KeyboardTheme.load(this, prefs),
                emojiCallback, panelHeight
            ).also { emojiPanel = it }
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            container.removeAllViews()
            container.addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    panelHeight
                )
            )
            // A reused panel was built before any recents picked while it was
            // closed — refresh so the recents tab isn't stale on reopen.
            if (reused) panel.refresh()
        } catch (t: Throwable) {
            android.util.Log.e("LLMKeyboard", "emoji panel failed", t)
            Toast.makeText(this, "Emoji panel failed to open", Toast.LENGTH_SHORT).show()
            showingEmoji = false
            emojiPanel = null
            applyKeyboardModel()
        }
    }

    private fun closeEmojiPanel() {
        showingEmoji = false
        emojiPanel?.let { panel ->
            (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        }
        // The instance stays alive (see showEmojiPanel); only the attachment
        // goes. applyKeyboardModel re-adds the keyboard view below.
        applyKeyboardModel()
        refreshEmojiRow()
    }

    // ------------------------------------------------------------------
    // Emoji quick row (optional, above the keyboard)
    // ------------------------------------------------------------------

    /** What the quick row last rendered (and whether it was visible), so a
     *  refresh with no change doesn't rebuild nine TextViews. This runs on
     *  every keyboard show and every emoji insert. */
    private var lastEmojiRowShown = false
    private var lastEmojiRowItems: List<String> = emptyList()
    private var lastEmojiRowBg = 0

    private fun refreshEmojiRow() {
        val show = prefs.emojiRow && !showingEmoji
        val items = if (show) {
            (prefs.recentEmojis + DEFAULT_EMOJIS).distinct().take(9)
        } else {
            emptyList()
        }
        val bg = theme?.background ?: 0
        if (show == lastEmojiRowShown && items == lastEmojiRowItems &&
            (!show || bg == lastEmojiRowBg)
        ) {
            return
        }
        lastEmojiRowShown = show
        lastEmojiRowItems = items
        lastEmojiRowBg = bg
        emojiRow.removeAllViews()
        if (!show) {
            emojiRow.visibility = View.GONE
            return
        }
        emojiRow.visibility = View.VISIBLE
        emojiRow.setBackgroundColor(bg)
        val ctx = this
        for (e in items) {
            val tv = android.widget.TextView(ctx).apply {
                text = e
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                setOnClickListener { insertEmojiFromRow(e) }
            }
            emojiRow.addView(tv)
        }
        // Trailing "open full panel" affordance.
        val more = android.widget.TextView(ctx).apply {
            text = "⋯"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setTextColor(theme?.textSecondary ?: 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            setOnClickListener {
                showingEmoji = true
                cancelAiSuggestion()
                applyKeyboardModel()
                refreshEmojiRow()
            }
        }
        emojiRow.addView(more)
        // Fixed comfortable height.
        emojiRow.post {
            val h = (resources.displayMetrics.density * 44).toInt()
            if (emojiRow.height != h) {
                emojiRow.layoutParams = emojiRow.layoutParams.apply { height = h }
            }
        }
    }

    private fun insertEmojiFromRow(emoji: String) {
        val ic = currentInputConnection ?: return
        finishComposingWord()
        beginSelfEdit()
        ic.commitText(emoji, 1)
        endSelfEdit()
        prefs.addRecentEmoji(emoji)
        refreshEmojiRow()
        refreshPredictions()
    }

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
        // Track the caret unconditionally — including during our own edits, so
        // the cursor pad never works from a stale offset after a commit.
        selStart = newSelStart
        selEnd = newSelEnd
        if (selfEditing) return
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
        // B3: the send BUTTON (a view click — no editor action) just cleared
        // the field: accumulated words were the message; log it whole. Only
        // fires on external changes with an empty field, so our own commits
        // and mid-typing cursor moves never false-trigger.
        if (externalChange && messageWords.isNotEmpty()) {
            val stillThere = try {
                currentInputConnection?.getTextBeforeCursor(2, 0)?.isNotEmpty() == true
            } catch (_: Throwable) { true }
            if (!stillThere) flushMessageLog("field-cleared", harvestComposing = true)
        }
        if (externalChange) {
            if (composing.isNotEmpty()) {
                // Not learned as a word: a tap-away can leave a half-typed
                // fragment, and fragments must not pollute the dictionary.
                composing.clear()
                composingTrace.clear()
                composingTraceValid = true
                beginSelfEdit()
                try { currentInputConnection?.finishComposingText() } catch (_: Exception) {}
                endSelfEdit()
            }
            lastGestureCommit = null
            autoSpacePending = false
            glideAlternates = null
            refreshPredictions()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // B3: last chance to log whatever was composed before the field went
        // away (some flows dismiss the keyboard on send without an action).
        flushMessageLog("kbd-hide")
        wifmJob?.cancel()
        cancelAiSuggestion()
        voiceInput?.cancel()
        if (::predictionBar.isInitialized) predictionBar.setVoiceState(false, null)
        lastAutoFix = null
        lastGestureCommit = null
        autoSpacePending = false
        suppressedAutoFix.clear()
        if (::tapModel.isInitialized) tapModel.persist()
        // Keyboard hidden: screen capture stops, and anything buffered for the
        // encrypted store (typing log, usage counters, n-grams) goes to disk
        // in one batch instead of trickling out per word while typing.
        com.localboard.keyboard.context.ScreenBuffer.imeVisible = false
        com.localboard.keyboard.context.ScreenContextService.instance?.cancelPending()
        serviceScope.launch(Dispatchers.IO) {
            // checkpoint() flushes first, then folds the write-ahead log back
            // into the database. Doing it here puts that work in the one window
            // where nobody is waiting on a prediction.
            try { contextEngine.checkpoint() } catch (_: Throwable) { }
        }
        scheduleModelUnload()
    }

    // ------------------------------------------------------------------
    // Memory pressure: the #1 keyboard-killer
    // ------------------------------------------------------------------
    //
    // A resident GGUF model (0.4–2.5 GB) + KV cache makes this process the
    // system's favourite thing to kill the moment the keyboard hides. Every
    // kill reads as "the keyboard doesn't pop up for a couple of seconds"
    // (cold start on next tap) and, under pressure, as a crash loop. So the
    // model only stays resident while the keyboard is actually up; it frees
    // itself shortly after hide, and instantly under real memory pressure.

    private fun llamaProvider(): com.localboard.keyboard.llm.LlamaCppProvider? =
        registry.all().filterIsInstance<com.localboard.keyboard.llm.LlamaCppProvider>()
            .firstOrNull()

    /** Grace period after the keyboard hides: quick app-switching keeps the
     *  model warm (drafts stay instant); genuinely leaving frees the RAM. */
    private fun scheduleModelUnload(delayMs: Long = MODEL_IDLE_UNLOAD_MS) {
        unloadJob?.cancel()
        unloadJob = serviceScope.launch(Dispatchers.IO) {
            delay(delayMs)
            val provider = llamaProvider() ?: return@launch
            if (provider.idleSince(android.os.SystemClock.elapsedRealtime()) >= delayMs) {
                provider.releaseModel("idle ${delayMs / 60000} min after keyboard hide")
            }
            // Whisper is smaller but still a resident chunk; dictation
            // reloads fast enough that hiding always frees it.
            try { com.localboard.keyboard.llm.VoiceBridge.freeModel() } catch (_: Throwable) { }
        }
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
        try {
            clipboardManager?.removePrimaryClipChangedListener(clipChangeListener)
        } catch (_: Throwable) { }
        serviceScope.cancel()
    }

    // ------------------------------------------------------------------
    // Settings shortcut
    // ------------------------------------------------------------------

    fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    companion object {
        private val SENTENCE_END = setOf(".", "!", "?")
        /** Stand-in previous word after a sentence ends (see maybeLearnSentence). */
        private const val SENTENCE_MARK = "."
        /** Punctuation that ends a word and triggers space-style autocorrect.
         *  Apostrophe/quote deliberately absent: they bind to the word. */
        private val AUTOCORRECT_PUNCT = setOf(',', '.', '!', '?', ':', ';')
        /** Marks that hop in front of a pending automatic space. */
        private val SPACE_SWAP_PUNCT = setOf(',', '.', '!', '?', ':', ';', ')')
        private val WORD_SPLIT = Regex("\\s+")
        private val DEFAULT_EMOJIS = listOf("😂", "❤️", "👍", "🔥", "😊", "🙏", "😍", "👏", "🎉")

        /** Used when the ✨ runs with no visible/remembered context at all. */
        private const val BLANK_CONTEXT_INSTRUCTION =
            "Write a short, friendly text message to send in this chat."
        /** Used for the retry after a degenerate first run (blank, "yep",
         *  or parroted prompt text). */
        private const val RETRY_INSTRUCTION =
            "Continue this conversation with a short, natural reply."

        /** Hard cap on one generation run, model load included. */
        private const val GEN_TIMEOUT_MS = 60_000L

        /** Keystrokes closer together than this count as a typing burst. */
        private const val BURST_GAP_MS = 130L

        /** Debounce used inside a burst. Long enough to coalesce several keys,
         *  short enough that the bar never feels like it lags behind. */
        private const val BURST_DEBOUNCE_MS = 45L

        /** Pause length before the quick model may propose a phrase
         *  continuation. Long enough that normal typing never triggers it. */
        private const val AI_SUGGEST_DEBOUNCE_MS = 700L
        /** LLM next-word prediction: debounce after a commit, abandon budget,
         *  how much field text the model sees, and the framing that tells a
         *  chat-tuned model this is a message being composed. */
        private const val AI_PREDICT_DEBOUNCE_MS = 120L
        /** Glide candidates within this many score points of the top can be
         *  promoted by an LLM next-word match (decoder score units: one
         *  nat of frequency ≈ 1.0, one missed letter ≈ 3-6). */
        private const val AI_SWIPE_TIE_MARGIN = 2.5
        private const val AI_PREDICT_ABANDON_MS = 1_500L
        private const val AI_PREDICT_CONTEXT_CHARS = 400
        private const val AI_PREDICT_FRAME = "A text message I am typing on my phone:\n\n"

        /** A quick-lane phrase run slower than this is abandoned on landing
         *  (the native call itself cannot be interrupted mid-decode). */
        private const val AI_SUGGEST_ABANDON_MS = 3_000L

        /** Bar marker for AI-phrase chips (the bar renders plain text);
         *  doubles as the tap-route tag in acceptSuggestion. */
        private const val AI_PHRASE_MARK = "✨ "

        /** Openers that make a dictated sentence a question. Kept small and
         *  unambiguous — "do you" is a question, "so" is not. */
        private val QUESTION_OPENERS = setOf(
            "who", "what", "when", "where", "why", "how", "which", "whose",
            "is", "are", "was", "were", "am", "do", "does", "did",
            "can", "could", "will", "would", "should", "shall", "have", "has",
            "had", "may", "might", "any", "anyone", "anybody"
        )

        /** Grace period after the keyboard hides before the LLM model frees
         *  itself. Short enough that background kills stop being a pattern,
         *  long enough that switching apps to copy something and coming
         *  straight back keeps drafts instant. */
        private const val MODEL_IDLE_UNLOAD_MS = 3 * 60_000L

        /** Per-app tone profiles for the AI composer (package → tone hint). */
        private val APP_TONES = mapOf(
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
