package com.localboard.keyboard.util

import android.content.Context
import com.localboard.keyboard.ui.KeyboardLayouts.BRACKET_HIDDEN as KeyboardLayoutsBracketDefault
import android.content.SharedPreferences

/**
 * Non-sensitive user preferences. Learned language data lives in the
 * encrypted Room database (see data/db), never here.
 */
class Prefs(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("localboard_prefs", Context.MODE_PRIVATE)

    /** Cached: without a stored haptic pref this default is consulted on
     *  every haptic decision, and each read is a ContentResolver round-trip. */
    @Volatile
    private var systemHapticsOnCache: Boolean? = null

    /** P1: the system-wide "touch feedback / vibration" switch. A user who
     *  turned haptics OFF for the whole phone should find this keyboard
     *  quiet by default too — the old unconditional `true` default buzzed
     *  on first launch against their explicit system preference. */
    private val systemHapticsOn: Boolean
        get() = systemHapticsOnCache ?: try {
            (android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
            ) != 0).also { systemHapticsOnCache = it }
        } catch (_: Throwable) { true }

    /** Everything currently stored, for [BackupManager]. */
    fun snapshotAll(): Map<String, Any?> = prefs.all

    /**
     * Write restored values back, one key at a time by type. Merges rather
     * than replaces — see BackupManager.restorePrefs for why.
     */
    fun restoreAll(map: Map<String, Any?>) {
        val e = prefs.edit()
        for ((k, v) in map) {
            when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Double -> e.putFloat(k, v.toFloat())   // JSON numbers arrive as Double
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                else -> Unit
            }
        }
        e.apply()
    }

    enum class Theme { DARK, LIGHT }

    var theme: Theme
        get() = if (prefs.getString(KEY_THEME, "dark") == "light") Theme.LIGHT else Theme.DARK
        set(v) = prefs.edit().putString(KEY_THEME, if (v == Theme.LIGHT) "light" else "dark").apply()

    var numberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(v) = prefs.edit().putBoolean(KEY_NUMBER_ROW, v).apply()

    /** Long-press top letter row for digits (q→1 … p→0). */
    var digitPopups: Boolean
        get() = prefs.getBoolean(KEY_DIGIT_POPUPS, true)
        set(v) = prefs.edit().putBoolean(KEY_DIGIT_POPUPS, v).apply()

    // ── Bottom-row layout ────────────────────────────────────────────────
    // Every key removed from the bottom row hands its width straight to the
    // SPACE BAR — the row's total weight is fixed, so the remaining keys keep
    // the width they had. These are not the "the keyboard feels cramped" fix
    // they were once described as: turning the emoji key off widens the comma
    // and period by about 1%, all of it from the one gap that went with it.
    // The lever for key SIZE is keySpacing.

    /** Dedicated emoji key in the bottom row. Off = reach emoji by
     *  long-pressing the enter/comma popup; space bar grows. */
    var bottomEmojiKey: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_EMOJI, true)
        set(v) = prefs.edit().putBoolean(KEY_BOTTOM_EMOJI, v).apply()

    /** Dedicated comma key (its long-press popup carries ' ! ? ; :). */
    var bottomCommaKey: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_COMMA, true)
        set(v) = prefs.edit().putBoolean(KEY_BOTTOM_COMMA, v).apply()

    /** Dedicated apostrophe key — English types more apostrophes than
     *  commas, and contractions are now first-class. Off by default so the
     *  bottom row keeps its stock shape until the user asks for it. */
    var bottomApostropheKey: Boolean
        get() = prefs.getBoolean(KEY_BOTTOM_APOS, false)
        set(v) = prefs.edit().putBoolean(KEY_BOTTOM_APOS, v).apply()

    /** Symbols-page density: 0 = roomy (10 keys per row, letter-key width),
     *  1 = complete (everything on page 1, narrower keys). Roomy is the
     *  default — the 13-key row shipped in 1.6.7 made symbol keys ~30%
     *  narrower than letter keys, which is what "cramped" feels like. */
    var symbolsDensity: Int
        get() = prefs.getInt(KEY_SYMBOLS_DENSITY, 0)
        set(v) = prefs.edit().putInt(KEY_SYMBOLS_DENSITY, v.coerceIn(0, 1)).apply()

    /** Multiplier on the gap between keys (0.4 = tight/bigger keys,
     *  1.8 = airy/smaller keys). 1.0 is the stock 4.5dp. */
    var keySpacing: Float
        get() = prefs.getFloat(KEY_SPACING, 1f)
        set(v) = prefs.edit().putFloat(KEY_SPACING, v.coerceIn(0.4f, 1.8f)).apply()

    // ── Glide typing behaviour ───────────────────────────────────────────

    /** Feed rejected-top / picked-alternate glides back into the personal
     *  model, so gestures get more accurate for THIS user over time.
     *  Default on: it only strengthens words the user explicitly chose. */
    var swipeLearning: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_LEARN, true)
        set(v) = prefs.edit().putBoolean(KEY_SWIPE_LEARN, v).apply()

    /** Detect a dwell or small loop on a key as a doubled letter, so
     *  "hello"/"food" stop relying on arc length alone. Default on. */
    var swipeDoubleLetter: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_DOUBLE, true)
        set(v) = prefs.edit().putBoolean(KEY_SWIPE_DOUBLE, v).apply()

    /** Learned glide bias: one "word\tweight" per line. */
    var swipeBiasData: String
        get() = prefs.getString(KEY_SWIPE_BIAS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SWIPE_BIAS, v).apply()

    // ── Clipboard ────────────────────────────────────────────────────────

    /** Keep a short history of copied text, reachable from the bar.
     *  Default ON but the history is RAM+prefs only and expires — see
     *  clipboardExpiryMinutes. */
    var clipboardHistory: Boolean
        get() = prefs.getBoolean(KEY_CLIP_HISTORY, true)
        set(v) = prefs.edit().putBoolean(KEY_CLIP_HISTORY, v).apply()

    /** Unpinned clips are dropped after this many minutes (0 = never). */
    var clipboardExpiryMinutes: Int
        get() = prefs.getInt(KEY_CLIP_EXPIRY, 60)
        set(v) = prefs.edit().putInt(KEY_CLIP_EXPIRY, v.coerceIn(0, 1440)).apply()

    /** Serialized clip history: one entry per line, "pinned\tts\ttext". */
    var clipboardEntries: String
        get() = prefs.getString(KEY_CLIP_ENTRIES, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CLIP_ENTRIES, v).apply()

    // ── Text snippets ────────────────────────────────────────────────────

    /** Expand user-defined shortcuts on commit (";addr" -> full address). */
    var snippetsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SNIPPETS_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_SNIPPETS_ON, v).apply()

    /** Serialized snippets: one per line, "shortcut\texpansion". */
    var snippetData: String
        get() = prefs.getString(KEY_SNIPPETS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SNIPPETS, v).apply()

    // ── Per-app themes ───────────────────────────────────────────────────

    /** Allow a different theme per app. Off by default — it's a power
     *  feature, and a keyboard that changes colour unexpectedly is worse
     *  than one that doesn't until you ask for it. */
    var perAppThemes: Boolean
        get() = prefs.getBoolean(KEY_PER_APP_THEME_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_PER_APP_THEME_ON, v).apply()

    /** package -> theme share code, one per line "pkg\tLB1…". */
    var perAppThemeMap: String
        get() = prefs.getString(KEY_PER_APP_THEMES, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PER_APP_THEMES, v).apply()

    fun themeCodeForApp(pkg: String): String? {
        if (!perAppThemes || pkg.isEmpty()) return null
        for (line in perAppThemeMap.split('\n')) {
            val t = line.indexOf('\t')
            if (t > 0 && line.substring(0, t) == pkg) return line.substring(t + 1)
        }
        return null
    }

    fun setThemeForApp(pkg: String, code: String?) {
        val kept = perAppThemeMap.split('\n')
            .filter { it.isNotBlank() && it.substringBefore('\t') != pkg }
        perAppThemeMap = (if (code == null) kept else kept + "$pkg\t$code")
            .joinToString("\n")
    }

    // ── One-handed / floating mode ───────────────────────────────────────

    /** 0 = full width, 1 = docked right, 2 = docked left. */
    var oneHandedMode: Int
        get() = prefs.getInt(KEY_ONE_HANDED, 0)
        set(v) = prefs.edit().putInt(KEY_ONE_HANDED, v.coerceIn(0, 2)).apply()

    /** Fraction of the screen the keyboard occupies in one-handed mode. */
    var oneHandedWidth: Float
        get() = prefs.getFloat(KEY_ONE_HANDED_W, 0.82f)
        set(v) = prefs.edit().putFloat(KEY_ONE_HANDED_W, v.coerceIn(0.6f, 1f)).apply()

    // ── Emoji ────────────────────────────────────────────────────────────

    /** Search field at the top of the emoji panel. */
    var emojiSearch: Boolean
        get() = prefs.getBoolean(KEY_EMOJI_SEARCH, true)
        set(v) = prefs.edit().putBoolean(KEY_EMOJI_SEARCH, v).apply()

    /** Fitzpatrick skin tone: 0 = default/yellow, 1..5 = light..dark. */
    var emojiSkinTone: Int
        get() = prefs.getInt(KEY_EMOJI_SKIN, 0)
        set(v) = prefs.edit().putInt(KEY_EMOJI_SKIN, v.coerceIn(0, 5)).apply()

    // ── Custom themes ────────────────────────────────────────────────────

    /** Share code of the theme in use when paletteId == "custom". */
    var activeThemeCode: String
        get() = prefs.getString(KEY_ACTIVE_THEME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ACTIVE_THEME, v).apply()

    /** Key-label typeface id (see ThemeSpec.FONTS). Orthogonal to palette. */
    var keyFontId: String
        get() = prefs.getString(KEY_FONT, com.localboard.keyboard.ui.ThemeSpec.FONT_DEFAULT)
            ?: com.localboard.keyboard.ui.ThemeSpec.FONT_DEFAULT
        set(v) = prefs.edit().putString(KEY_FONT, v).apply()

    /** The user's saved themes, newest first, as share codes. One slot per
     *  theme rather than a single overwritten "custom" — people iterate on a
     *  look and want the previous one back. */
    var savedThemes: List<String>
        get() = (prefs.getString(KEY_SAVED_THEMES, "") ?: "")
            .split('\n').filter { it.isNotBlank() }
        set(v) = prefs.edit()
            .putString(KEY_SAVED_THEMES, v.distinct().take(24).joinToString("\n"))
            .apply()

    /** Save (or move to front) one theme code. */
    fun saveTheme(code: String) {
        if (code.isBlank()) return
        savedThemes = listOf(code) + savedThemes.filter { it != code }
    }

    fun deleteTheme(code: String) {
        savedThemes = savedThemes.filter { it != code }
    }

    // ── First-run tutorial / settings presentation ───────────────────────

    /** The quick tutorial has been completed or skipped at least once. */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(v) = prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, v).apply()

    /** Settings shows only the essentials; everything else is one tap away. */
    var settingsSimple: Boolean
        get() = prefs.getBoolean(KEY_SETTINGS_SIMPLE, true)
        set(v) = prefs.edit().putBoolean(KEY_SETTINGS_SIMPLE, v).apply()

    /** Section keys the user has expanded (persisted across visits). */
    var expandedSections: Set<String>
        get() = prefs.getStringSet(KEY_EXPANDED_SECTIONS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_EXPANDED_SECTIONS, v).apply()

    /** User-picked keyboard wallpaper (file in app storage); empty = none. */
    var wallpaperPath: String
        get() = prefs.getString(KEY_WALLPAPER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_WALLPAPER, v).apply()

    /** Scrim strength over the wallpaper so keys stay readable (0..0.85). */
    var wallpaperDim: Float
        get() = prefs.getFloat(KEY_WALLPAPER_DIM, 0.45f)
        set(v) = prefs.edit().putFloat(KEY_WALLPAPER_DIM, v.coerceIn(0f, 0.85f)).apply()

    /** Slow drifting gradient behind the keys ("live" background). */
    var liveBackground: Boolean
        get() = prefs.getBoolean(KEY_LIVE_BG, false)
        set(v) = prefs.edit().putBoolean(KEY_LIVE_BG, v).apply()

    var swipeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SWIPE, true)
        set(v) = prefs.edit().putBoolean(KEY_SWIPE, v).apply()

    var haptic: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, systemHapticsOn)
        set(v) = prefs.edit().putBoolean(KEY_HAPTIC, v).apply()

    var sound: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    // ── Typing-feel customization ────────────────────────────────────────
    /** Vibration intensity: 0=off, 1=light, 2=medium, 3=strong. Defaults to
     *  medium when the legacy haptic switch was on, off otherwise. */
    var vibLevel: Int
        get() = prefs.getInt(KEY_VIB_LEVEL, if (haptic) 2 else 0)
        set(v) = prefs.edit().putInt(KEY_VIB_LEVEL, v.coerceIn(0, 3)).apply()

    /** Key sound pack: 0=off, 1=soft, 2=tactile, 3=bubble. Defaults to soft
     *  when the legacy sound switch was on, off otherwise. */
    var soundPack: Int
        get() = prefs.getInt(KEY_SOUND_PACK, if (sound) 1 else 0)
        set(v) = prefs.edit().putInt(KEY_SOUND_PACK, v.coerceIn(0, 3)).apply()

    /** Key sound volume, 0-100. */
    var soundVolume: Int
        get() = prefs.getInt(KEY_SOUND_VOLUME, 70)
        set(v) = prefs.edit().putInt(KEY_SOUND_VOLUME, v.coerceIn(0, 100)).apply()

    /** Animation level: 0=off, 1=reduced, 2=full. */
    var animLevel: Int
        get() = prefs.getInt(KEY_ANIM_LEVEL, 2)
        set(v) = prefs.edit().putInt(KEY_ANIM_LEVEL, v.coerceIn(0, 2)).apply()

    var autoSpace: Boolean
        get() = prefs.getBoolean(KEY_AUTOSPACE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSPACE, v).apply()

    var autoCorrect: Boolean
        get() = prefs.getBoolean(KEY_AUTOCORRECT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOCORRECT, v).apply()

    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_SPACE, true)
        set(v) = prefs.edit().putBoolean(KEY_DOUBLE_SPACE, v).apply()

    /** Glide arming distance multiplier (0.6 = hair-trigger, 1.4 = very deliberate). */
    var swipeSensitivity: Float
        get() = prefs.getFloat(KEY_SWIPE_SENS, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_SWIPE_SENS, v.coerceIn(0.6f, 1.4f)).apply()

    var swipeTrail: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_TRAIL, true)
        set(v) = prefs.edit().putBoolean(KEY_SWIPE_TRAIL, v).apply()

    var liveSwipePreview: Boolean
        get() = prefs.getBoolean(KEY_LIVE_PREVIEW, true)
        set(v) = prefs.edit().putBoolean(KEY_LIVE_PREVIEW, v).apply()

    var longPressMs: Int
        get() = prefs.getInt(KEY_LONGPRESS, 380)
        set(v) = prefs.edit().putInt(KEY_LONGPRESS, v.coerceIn(250, 600)).apply()

    /** Autocorrect boldness: 1 = light, 2 = balanced, 3 = bold. */
    var autoCorrectLevel: Int
        get() = prefs.getInt(KEY_AC_LEVEL, 2)
        set(v) = prefs.edit().putInt(KEY_AC_LEVEL, v.coerceIn(1, 3)).apply()

    /** Serialized per-key touch offsets (TapLearningModel). */
    var tapModelData: String
        get() = prefs.getString(KEY_TAP_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TAP_MODEL, v).apply()

    /** Offline voice typing: show the mic button in the suggestion bar. */
    var voiceTypingEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_TYPING, true)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_TYPING, v).apply()

    /** Show the tinkerer rows in settings (manual offsets, experiments). */
    var showAdvanced: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED, false)
        set(v) = prefs.edit().putBoolean(KEY_ADVANCED, v).apply()

    /** Finger-fit calibration: horizontal shift of the whole key grid, in dp. */
    var keyOffsetXDp: Float
        get() = prefs.getFloat(KEY_OFFSET_X, 0f)
        set(v) = prefs.edit().putFloat(KEY_OFFSET_X, v.coerceIn(-60f, 60f)).apply()

    /** Finger-fit calibration: vertical shift of the whole key grid, in dp. */
    var keyOffsetYDp: Float
        get() = prefs.getFloat(KEY_OFFSET_Y, 0f)
        set(v) = prefs.edit().putFloat(KEY_OFFSET_Y, v.coerceIn(-60f, 60f)).apply()

    var autoCaps: Boolean
        get() = prefs.getBoolean(KEY_AUTOCAPS, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOCAPS, v).apply()

    var learningEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEARNING, true)
        set(v) = prefs.edit().putBoolean(KEY_LEARNING, v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDING, v).apply()

    var activeProviderId: String
        get() = prefs.getString(KEY_PROVIDER, "qwen3_06b") ?: "qwen3_06b"
        set(v) = prefs.edit().putString(KEY_PROVIDER, v).apply()

    /** Keyboard key-height scale, 0.80..1.35 (default 1.0). */
    var heightScale: Float
        get() = prefs.getFloat(KEY_HEIGHT_SCALE, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_HEIGHT_SCALE, v.coerceIn(0.6f, 1.5f)).apply()

    /** Key label text scale, 0.80..1.35 (default 1.0). */
    var keyTextScale: Float
        get() = prefs.getFloat(KEY_TEXT_SCALE, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_TEXT_SCALE, v.coerceIn(0.7f, 1.4f)).apply()

    /** Theme palette id (see ui/KeyboardTheme.PALETTES). "" = legacy dark/light toggle. */
    var paletteId: String
        get() = prefs.getString(KEY_PALETTE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PALETTE, v).apply()

    /** Key corner radius override in dp; -1 = the palette's own radius. */
    var keyRadiusDp: Float
        get() = prefs.getFloat(KEY_KEY_RADIUS, -1f)
        set(v) = prefs.edit().putFloat(KEY_KEY_RADIUS, v).apply()

    /** Key press effect: 0=Lift, 1=Bounce, 2=Glow, 3=None. */
    var pressEffect: Int
        get() = prefs.getInt(KEY_PRESS_EFFECT, 0)
        set(v) = prefs.edit().putInt(KEY_PRESS_EFFECT, v.coerceIn(0, 3)).apply()

    /** Custom color overrides; Int.MIN_VALUE = use palette color. */
    var customBg: Int
        get() = prefs.getInt(KEY_CUSTOM_BG, Int.MIN_VALUE)
        set(v) = prefs.edit().putInt(KEY_CUSTOM_BG, v).apply()
    var customKey: Int
        get() = prefs.getInt(KEY_CUSTOM_KEY, Int.MIN_VALUE)
        set(v) = prefs.edit().putInt(KEY_CUSTOM_KEY, v).apply()
    var customText: Int
        get() = prefs.getInt(KEY_CUSTOM_TEXT, Int.MIN_VALUE)
        set(v) = prefs.edit().putInt(KEY_CUSTOM_TEXT, v).apply()
    var customAccent: Int
        get() = prefs.getInt(KEY_CUSTOM_ACCENT, Int.MIN_VALUE)
        set(v) = prefs.edit().putInt(KEY_CUSTOM_ACCENT, v).apply()

    /** Show the press-preview bubble above tapped keys. */
    var popupPreview: Boolean
        get() = prefs.getBoolean(KEY_POPUP_PREVIEW, true)
        set(v) = prefs.edit().putBoolean(KEY_POPUP_PREVIEW, v).apply()

    /** Dedicated emoji quick bar above the keyboard. */
    var emojiRow: Boolean
        get() = prefs.getBoolean(KEY_EMOJI_ROW, false)
        set(v) = prefs.edit().putBoolean(KEY_EMOJI_ROW, v).apply()

    /**
     * User-written style instructions for the AI composer. When non-blank this
     * OVERRIDES the auto-distilled style block in the Qwen system prompt.
     */
    var customStylePrompt: String
        get() = prefs.getString(KEY_CUSTOM_STYLE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CUSTOM_STYLE, v).apply()

    /** LLM context window (tokens) the model is loaded with. Bigger windows
     *  remember more of the conversation/input text but cost RAM and slow
     *  prompt processing on CPU. 0 = use the model's built-in default. */
    var llmCtxSize: Int
        get() = prefs.getInt(KEY_LLM_CTX, 2048)
        set(v) = prefs.edit().putInt(KEY_LLM_CTX, v.coerceIn(0, 8192)).apply()

    /** Whether the auto-learned style profile (measured habits + distilled
     *  personality notes) is included in AI drafting prompts. A bad sample
     *  makes drafts worse than no profile at all — the test lab's A/B pass
     *  shows the difference. */
    var llmUseStyleProfile: Boolean
        get() = prefs.getBoolean(KEY_LLM_USE_PROFILE, true)
        set(v) = prefs.edit().putBoolean(KEY_LLM_USE_PROFILE, v).apply()

    /** The QUICK lane model id (autocorrect + fast tasks). Empty → the
     *  registry default (Qwen3 0.6B). */
    var quickModelId: String
        get() = prefs.getString(KEY_QUICK_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_QUICK_MODEL, v).apply()

    var recentEmojis: List<String>
        get() = (prefs.getString(KEY_RECENT_EMOJI, "") ?: "")
            .split(' ').filter { it.isNotBlank() }
        set(v) = prefs.edit()
            .putString(KEY_RECENT_EMOJI, v.distinct().take(24).joinToString(" "))
            .apply()

    fun addRecentEmoji(emoji: String) {
        val current = recentEmojis.toMutableList()
        current.remove(emoji)
        current.add(0, emoji)
        recentEmojis = current
    }

    fun clearLearned() {
        prefs.edit().remove(KEY_RECENT_EMOJI).apply()
    }

    /** Factory reset: every preference — learned style, finger-fit
     *  calibration, offsets, recent emoji, settings — returns to defaults.
     *  Paired with the database wipe in "erase all data". Downloaded model
     *  FILES stay on disk (large re-downloads, and not personal data). */
    fun clearAll() {
        // commit(), not apply(): the caller wipes the database immediately
        // after this, and the factory reset must be durably on disk before
        // that happens — apply() would still be in flight.
        prefs.edit().clear().commit()
    }

    // ------------------------------------------------------------------
    // Privacy, typing log, nightly distillation
    // ------------------------------------------------------------------

    /** Save everything typed into the encrypted typing log (default on). */
    var typingLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_TYPING_LOG, true)
        set(v) = prefs.edit().putBoolean(KEY_TYPING_LOG, v).apply()

    /** P5: master switch for the SCREEN context stream. Independent of the
     *  system accessibility toggle — the service can stay enabled (for
     *  thread titles) while capture for AI prompts is paused here. */
    var screenStreamEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_STREAM, true)
        set(v) = prefs.edit().putBoolean(KEY_SCREEN_STREAM, v).apply()

    /** P5: master switch for the FIELD-TEXT context stream — whether the
     *  text already in the input field may feed AI drafting prompts. */
    var fieldTextEnabled: Boolean
        get() = prefs.getBoolean(KEY_FIELD_TEXT, true)
        set(v) = prefs.edit().putBoolean(KEY_FIELD_TEXT, v).apply()

    /** Nightly on-charger distillation of the typing log into the profile. */
    var overnightDistillation: Boolean
        get() = prefs.getBoolean(KEY_OVERNIGHT_DISTILL, true)
        set(v) = prefs.edit().putBoolean(KEY_OVERNIGHT_DISTILL, v).apply()

    /** Opt-in AI next-word predictions (quick lane; default off).
     *
     *  Reset to off ONCE by the 1.2 build: the feature had been switched on
     *  to try, and small-model guesses leading the bar were making the
     *  ordinary n-gram predictions look worse than they were. It stays
     *  available; it just has to be chosen again. */
    var llmSuggestions: Boolean
        get() {
            if (!prefs.getBoolean(KEY_LLM_SUGGESTIONS_RESET, false)) {
                prefs.edit()
                    .putBoolean(KEY_LLM_SUGGESTIONS, false)
                    .putBoolean(KEY_LLM_SUGGESTIONS_RESET, true)
                    .apply()
            }
            return prefs.getBoolean(KEY_LLM_SUGGESTIONS, false)
        }
        set(v) = prefs.edit().putBoolean(KEY_LLM_SUGGESTIONS, v).apply()

    /** Packages the user doesn't want ANY context saved from. */
    var contextBlockedApps: Set<String>
        get() = prefs.getStringSet(KEY_BLOCKED_APPS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_BLOCKED_APPS, v).apply()

    fun isAppBlocked(packageName: String): Boolean =
        packageName.isNotEmpty() && contextBlockedApps.contains(packageName)

    fun setAppBlocked(packageName: String, blocked: Boolean) {
        val s = contextBlockedApps.toMutableSet()
        if (blocked) s.add(packageName) else s.remove(packageName)
        contextBlockedApps = s
    }

    // ------------------------------------------------------------------
    // Per-app privacy profiles
    //
    // contextBlockedApps above is the blunt instrument ("save nothing from
    // this app"). These are the three separable capabilities, so a user can
    // say e.g. "read the screen in Slack but never learn my words there".
    // Absent from the set = allowed, so the defaults stay permissive and no
    // migration is needed for existing installs.
    // ------------------------------------------------------------------

    /** Apps that must never teach the dictionary / n-gram model. */
    var noLearnApps: Set<String>
        get() = prefs.getStringSet(KEY_NO_LEARN_APPS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_NO_LEARN_APPS, v).apply()

    /** Apps whose screen text must never be captured. */
    var noScreenApps: Set<String>
        get() = prefs.getStringSet(KEY_NO_SCREEN_APPS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_NO_SCREEN_APPS, v).apply()

    /** Apps where AI drafting/rewriting is disabled outright. */
    var noAiApps: Set<String>
        get() = prefs.getStringSet(KEY_NO_AI_APPS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_NO_AI_APPS, v).apply()

    fun mayLearnIn(pkg: String) = pkg.isEmpty() || (!isAppBlocked(pkg) && pkg !in noLearnApps)
    fun mayReadScreenIn(pkg: String) = pkg.isEmpty() || (!isAppBlocked(pkg) && pkg !in noScreenApps)
    fun mayUseAiIn(pkg: String) = pkg.isEmpty() || (!isAppBlocked(pkg) && pkg !in noAiApps)

    private fun toggleIn(current: Set<String>, pkg: String, on: Boolean): Set<String> {
        val s = current.toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        return s
    }

    fun setNoLearn(pkg: String, on: Boolean) { noLearnApps = toggleIn(noLearnApps, pkg, on) }
    fun setNoScreen(pkg: String, on: Boolean) { noScreenApps = toggleIn(noScreenApps, pkg, on) }
    fun setNoAi(pkg: String, on: Boolean) { noAiApps = toggleIn(noAiApps, pkg, on) }

    /** Every package the user has set any privacy rule for — the list the
     *  settings screen shows without having to enumerate installed apps. */
    fun appsWithPrivacyRules(): Set<String> =
        contextBlockedApps + noLearnApps + noScreenApps + noAiApps

    // ------------------------------------------------------------------
    // Privacy controls
    // ------------------------------------------------------------------

    /** Strip OTPs / card numbers / key-shaped tokens from captured screen
     *  text before it is stored. On by default. */
    var redactScreenText: Boolean
        get() = prefs.getBoolean(KEY_REDACT_SCREEN, true)
        set(v) = prefs.edit().putBoolean(KEY_REDACT_SCREEN, v).apply()

    /** Master network switch. When false, model downloads refuse to open a
     *  socket. Default true only because a first run needs to fetch a model;
     *  once the user has one, turning this off makes the privacy promise
     *  structural rather than a matter of trust. */
    var networkAllowed: Boolean
        get() = prefs.getBoolean(KEY_NETWORK_ALLOWED, true)
        set(v) = prefs.edit().putBoolean(KEY_NETWORK_ALLOWED, v).apply()

    /** Append-only record of every outbound request, newest first.
     *  Format per line: epochMillis  host  outcome */
    var egressLog: String
        get() = prefs.getString(KEY_EGRESS_LOG, "") ?: ""
        set(v) = prefs.edit().putString(KEY_EGRESS_LOG, v).apply()

    /** Require biometric/device unlock before showing clipboard history and
     *  the learned-data viewers. */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, v).apply()

    // ------------------------------------------------------------------
    // Typing features
    // ------------------------------------------------------------------

    /** Import contact names into the dictionary so proper nouns stop being
     *  autocorrected into oblivion. Opt-in: it needs READ_CONTACTS. */
    var learnContactNames: Boolean
        get() = prefs.getBoolean(KEY_LEARN_CONTACTS, false)
        set(v) = prefs.edit().putBoolean(KEY_LEARN_CONTACTS, v).apply()

    /** When contacts were last imported (epoch millis, 0 = never). */
    var contactsSyncedAt: Long
        get() = prefs.getLong(KEY_CONTACTS_SYNCED, 0L)
        set(v) = prefs.edit().putLong(KEY_CONTACTS_SYNCED, v).apply()

    /** Where the < > [ ] { } key sits on the symbols page:
     *  0 = left (default), 1 = right, 2 = hidden.
     *
     *  Default changed to LEFT in 1.7.0. It used to sit in the last slot
     *  before backspace, which is the seat a thumb reaches for "?" — so
     *  aiming for punctuation typed "<". */
    var symbolsBracketPos: Int
        get() = prefs.getInt(KEY_BRACKET_POS, KeyboardLayoutsBracketDefault)
        set(v) = prefs.edit().putInt(KEY_BRACKET_POS, v.coerceIn(0, 2)).apply()

    /** Show the arrow/selection pad key on the bottom row.
     *
     *  Defaults OFF as of 1.7.5. It shipped on, which added a key to the
     *  bottom row of every user who never asked for one — the opposite of the
     *  "fewer, wider keys" direction the rest of the layout work went in. */
    var selectionKey: Boolean
        get() = prefs.getBoolean(KEY_SELECTION_KEY, false)
        set(v) = prefs.edit().putBoolean(KEY_SELECTION_KEY, v).apply()

    /** Letter arrangement: qwerty | qwertz | azerty | dvorak | colemak | gboard.
     *  "gboard" also switches the symbols pages and number pad to Gboard's
     *  shapes — see KeyboardLayouts.gboardSymbols1/2 and gboardNumberPad. */
    var layoutId: String
        get() = prefs.getString(KEY_LAYOUT_ID, "qwerty") ?: "qwerty"
        set(v) = prefs.edit().putString(KEY_LAYOUT_ID, v).apply()

    // ── Animated background + render efficiency ──────────────────────────

    /** Live background style id — see LiveBackground.Style. "none" = flat.
     *
     *  Migration: the pre-1.6.9 feature was a single on/off switch backed by
     *  [liveBackground]. Anyone who had it on gets the equivalent style rather
     *  than silently losing the effect. */
    var liveBgStyle: String
        get() {
            val stored = prefs.getString(KEY_LIVE_BG_STYLE, null)
            if (stored != null) return stored
            return if (liveBackground) "blobs" else "none"
        }
        set(v) = prefs.edit().putString(KEY_LIVE_BG_STYLE, v).apply()

    /** Frames per second for the animated background. 15 is visually
     *  identical to 60 for animations that move over tens of seconds, and
     *  costs a quarter as much. */
    var liveBgFps: Int
        get() = prefs.getInt(KEY_LIVE_BG_FPS, 15)
        set(v) = prefs.edit().putInt(KEY_LIVE_BG_FPS, v.coerceIn(4, 60)).apply()

    /** Animate only during a typing burst, then stop drawing entirely.
     *
     *  Defaults OFF. It shipped ON in 1.7.2, which meant picking a style and
     *  looking at the keyboard showed a completely static background — the
     *  animation only ran in the 1.6 s after a keystroke, i.e. exactly when
     *  the user is looking at the text field instead. An animated background
     *  that does not visibly animate reads as broken, so the battery-saving
     *  mode is now something you opt into. */
    var liveBgWhileTyping: Boolean
        get() = prefs.getBoolean(KEY_LIVE_BG_TYPING, false)
        set(v) = prefs.edit().putBoolean(KEY_LIVE_BG_TYPING, v).apply()

    /** Strength of the animated background, 0..1. */
    var liveBgIntensity: Float
        get() = prefs.getFloat(KEY_LIVE_BG_INTENSITY, 1f)
        set(v) = prefs.edit().putFloat(KEY_LIVE_BG_INTENSITY, v.coerceIn(0f, 1f)).apply()

    /**
     * Per-key layout fit from the calibration typing test — "key:dx,dy;…" in
     * dp, same format as [tapModelData].
     *
     * These MOVE THE DRAWN KEYS, where the tap model only moves the hit
     * target. Baking a fit here clears the tap model, because applying both to
     * the same measurement would shift a key twice.
     */
    var keyFitData: String
        get() = prefs.getString(KEY_KEY_FIT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_KEY_FIT, v).apply()

    /** Parsed form of [keyFitData]; empty when the user has never calibrated. */
    fun keyFitMap(): Map<String, Pair<Float, Float>> {
        val raw = keyFitData
        if (raw.isBlank()) return emptyMap()
        val out = HashMap<String, Pair<Float, Float>>()
        for (entry in raw.split(';')) {
            val kv = entry.split(':')
            if (kv.size != 2 || kv[0].isEmpty()) continue
            val parts = kv[1].split(',')
            if (parts.size != 2) continue
            val dx = parts[0].toFloatOrNull() ?: continue
            val dy = parts[1].toFloatOrNull() ?: continue
            out[kv[0]] = dx to dy
        }
        return out
    }

    // ── The strip behind the navigation bar ──────────────────────────────
    // API 35+ lays the IME window out edge to edge, so the keys are padded up
    // clear of the navigation bar. Something has to fill what is left or the
    // app underneath shows through the bottom of the keyboard.

    /** 0 = match the keyboard background, 1 = solid colour, 2 = animated.
     *
     *  Defaults to MATCH: the strip exists because of a window-geometry
     *  change, not because anyone asked for a band under their keyboard, so
     *  out of the box it should be invisible. */
    var navStripMode: Int
        get() = prefs.getInt(KEY_NAV_STRIP_MODE, 0)
        set(v) = prefs.edit().putInt(KEY_NAV_STRIP_MODE, v.coerceIn(0, 3)).apply()

    /** Colour for navStripMode == 1. */
    var navStripColor: Int
        get() = prefs.getInt(KEY_NAV_STRIP_COLOR, android.graphics.Color.BLACK)
        set(v) = prefs.edit().putInt(KEY_NAV_STRIP_COLOR, v).apply()

    /** LiveBackground style id for navStripMode == 2. */
    var navStripStyle: String
        get() = prefs.getString(KEY_NAV_STRIP_STYLE, "blobs") ?: "blobs"
        set(v) = prefs.edit().putString(KEY_NAV_STRIP_STYLE, v).apply()

    /** Draw key outlines instead of filled key faces. On an OLED panel the
     *  lit-pixel count is what costs power, and an outline is a fraction of a
     *  fill. Off by default: it is a visible style change. */
    var outlineKeys: Boolean
        get() = prefs.getBoolean(KEY_OUTLINE_KEYS, false)
        set(v) = prefs.edit().putBoolean(KEY_OUTLINE_KEYS, v).apply()

    /** Ask the display for 60 Hz while typing, full rate only during a glide.
     *  On by default — static typing has nothing to show at 120 Hz. */
    var throttleRefreshRate: Boolean
        get() = prefs.getBoolean(KEY_THROTTLE_HZ, true)
        set(v) = prefs.edit().putBoolean(KEY_THROTTLE_HZ, v).apply()

    /** Merge haptic pulses during fast typing (one buzz per ~40 ms). */
    var coalesceHaptics: Boolean
        get() = prefs.getBoolean(KEY_COALESCE_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_COALESCE_HAPTICS, v).apply()

    /** Show the render/efficiency counters in Settings. */
    var showFrameStats: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FRAME_STATS, false)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_FRAME_STATS, v).apply()

    /** Add sentence punctuation to voice transcripts. */
    var voicePunctuation: Boolean
        get() = prefs.getBoolean(KEY_VOICE_PUNCT, true)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_PUNCT, v).apply()

    /** Skip AI drafting when the battery is low or the phone is hot. */
    var powerAwareAi: Boolean
        get() = prefs.getBoolean(KEY_POWER_AWARE, true)
        set(v) = prefs.edit().putBoolean(KEY_POWER_AWARE, v).apply()

    /** Battery percentage below which AI drafting is skipped (unless charging). */
    var aiBatteryFloor: Int
        get() = prefs.getInt(KEY_AI_BATTERY_FLOOR, 15)
        set(v) = prefs.edit().putInt(KEY_AI_BATTERY_FLOOR, v.coerceIn(0, 50)).apply()

    // ------------------------------------------------------------------
    // AI models
    // ------------------------------------------------------------------

    /** Id of the currently selected model (built-in id or user-added "custom:N"). */
    var activeModelId: String
        get() = prefs.getString(KEY_ACTIVE_MODEL, "qwen3_06b") ?: "qwen3_06b"
        set(v) = prefs.edit().putString(KEY_ACTIVE_MODEL, v).apply()

    /** JSON array describing user-added models (file/URL imports). */
    var customModelsJson: String
        get() = prefs.getString(KEY_CUSTOM_MODELS, "[]") ?: "[]"
        set(v) = prefs.edit().putString(KEY_CUSTOM_MODELS, v).apply()

    companion object {
        private const val KEY_NO_LEARN_APPS = "no_learn_apps"
        private const val KEY_NO_SCREEN_APPS = "no_screen_apps"
        private const val KEY_NO_AI_APPS = "no_ai_apps"
        private const val KEY_REDACT_SCREEN = "redact_screen_text"
        private const val KEY_NETWORK_ALLOWED = "network_allowed"
        private const val KEY_EGRESS_LOG = "egress_log"
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock"
        private const val KEY_LEARN_CONTACTS = "learn_contact_names"
        private const val KEY_CONTACTS_SYNCED = "contacts_synced_at"
        private const val KEY_SELECTION_KEY = "selection_key"
        private const val KEY_BRACKET_POS = "symbols_bracket_pos"
        private const val KEY_LAYOUT_ID = "layout_id"
        private const val KEY_LIVE_BG_STYLE = "live_bg_style"
        private const val KEY_LIVE_BG_FPS = "live_bg_fps"
        private const val KEY_LIVE_BG_TYPING = "live_bg_while_typing"
        private const val KEY_LIVE_BG_INTENSITY = "live_bg_intensity"
        private const val KEY_KEY_FIT = "key_layout_fit"
        private const val KEY_NAV_STRIP_MODE = "nav_strip_mode"
        private const val KEY_NAV_STRIP_COLOR = "nav_strip_color"
        private const val KEY_NAV_STRIP_STYLE = "nav_strip_style"
        private const val KEY_OUTLINE_KEYS = "outline_keys"
        private const val KEY_THROTTLE_HZ = "throttle_refresh_rate"
        private const val KEY_COALESCE_HAPTICS = "coalesce_haptics"
        private const val KEY_SHOW_FRAME_STATS = "show_frame_stats"
        private const val KEY_VOICE_PUNCT = "voice_punctuation"
        private const val KEY_POWER_AWARE = "power_aware_ai"
        private const val KEY_AI_BATTERY_FLOOR = "ai_battery_floor"
        private const val KEY_THEME = "theme"
        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_DIGIT_POPUPS = "digit_popups"
        private const val KEY_BOTTOM_EMOJI = "bottom_emoji_key"
        private const val KEY_BOTTOM_COMMA = "bottom_comma_key"
        private const val KEY_BOTTOM_APOS = "bottom_apostrophe_key"
        private const val KEY_SYMBOLS_DENSITY = "symbols_density"
        private const val KEY_SPACING = "key_spacing"
        private const val KEY_SWIPE_LEARN = "swipe_learning"
        private const val KEY_SWIPE_DOUBLE = "swipe_double_letter"
        private const val KEY_SWIPE_BIAS = "swipe_bias_data"
        private const val KEY_CLIP_HISTORY = "clipboard_history"
        private const val KEY_CLIP_EXPIRY = "clipboard_expiry_min"
        private const val KEY_CLIP_ENTRIES = "clipboard_entries"
        private const val KEY_SNIPPETS_ON = "snippets_enabled"
        private const val KEY_SNIPPETS = "snippet_data"
        private const val KEY_PER_APP_THEME_ON = "per_app_themes_on"
        private const val KEY_PER_APP_THEMES = "per_app_theme_map"
        private const val KEY_ONE_HANDED = "one_handed_mode"
        private const val KEY_ONE_HANDED_W = "one_handed_width"
        private const val KEY_EMOJI_SEARCH = "emoji_search"
        private const val KEY_EMOJI_SKIN = "emoji_skin_tone"
        private const val KEY_ACTIVE_THEME = "active_theme_code"
        private const val KEY_SAVED_THEMES = "saved_themes"
        private const val KEY_FONT = "key_font_id"
        private const val KEY_TUTORIAL_SEEN = "tutorial_seen"
        private const val KEY_SETTINGS_SIMPLE = "settings_simple"
        private const val KEY_EXPANDED_SECTIONS = "expanded_sections"
        private const val KEY_WALLPAPER = "wallpaper_path"
        private const val KEY_WALLPAPER_DIM = "wallpaper_dim"
        private const val KEY_LIVE_BG = "live_bg"
        private const val KEY_SWIPE = "swipe_enabled"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_SOUND = "sound"
        private const val KEY_VIB_LEVEL = "vib_level"
        private const val KEY_SOUND_PACK = "sound_pack"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_ANIM_LEVEL = "anim_level"
        private const val KEY_AUTOSPACE = "auto_space"
        private const val KEY_AUTOCORRECT = "auto_correct"
        private const val KEY_DOUBLE_SPACE = "double_space_period"
        private const val KEY_SWIPE_SENS = "swipe_sensitivity"
        private const val KEY_SWIPE_TRAIL = "swipe_trail"
        private const val KEY_LIVE_PREVIEW = "live_swipe_preview"
        private const val KEY_LONGPRESS = "longpress_ms"
        private const val KEY_AC_LEVEL = "autocorrect_level"
        private const val KEY_TAP_MODEL = "tap_model_data"
        private const val KEY_VOICE_TYPING = "voice_typing_enabled"
        private const val KEY_ADVANCED = "show_advanced"
        private const val KEY_OFFSET_X = "key_offset_x"
        private const val KEY_OFFSET_Y = "key_offset_y"
        private const val KEY_AUTOCAPS = "auto_caps"
        private const val KEY_LEARNING = "learning_enabled"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_PROVIDER = "active_provider"
        private const val KEY_RECENT_EMOJI = "recent_emojis"
        private const val KEY_HEIGHT_SCALE = "height_scale"
        private const val KEY_TEXT_SCALE = "text_scale"
        private const val KEY_PALETTE = "palette_id"
        private const val KEY_CUSTOM_BG = "custom_bg"
        private const val KEY_CUSTOM_KEY = "custom_key"
        private const val KEY_CUSTOM_TEXT = "custom_text"
        private const val KEY_CUSTOM_ACCENT = "custom_accent"
        private const val KEY_POPUP_PREVIEW = "popup_preview"
        private const val KEY_EMOJI_ROW = "emoji_row"
        private const val KEY_CUSTOM_STYLE = "custom_style_prompt"
        private const val KEY_LLM_CTX = "llm_ctx_size"
        private const val KEY_LLM_USE_PROFILE = "llm_use_style_profile"
        private const val KEY_QUICK_MODEL = "llm_quick_model_id"
        private const val KEY_KEY_RADIUS = "kb_key_radius_dp"
        private const val KEY_PRESS_EFFECT = "kb_press_effect"
        private const val KEY_TYPING_LOG = "typing_log_enabled"
        private const val KEY_SCREEN_STREAM = "screen_stream_enabled"
        private const val KEY_FIELD_TEXT = "field_text_enabled"
        private const val KEY_OVERNIGHT_DISTILL = "overnight_distillation"
        private const val KEY_LLM_SUGGESTIONS = "llm_suggestions"
        private const val KEY_LLM_SUGGESTIONS_RESET = "llm_suggestions_reset_v2"
        private const val KEY_BLOCKED_APPS = "context_blocked_apps"
        private const val KEY_ACTIVE_MODEL = "active_model_id"
        private const val KEY_CUSTOM_MODELS = "custom_models_json"
    }
}
