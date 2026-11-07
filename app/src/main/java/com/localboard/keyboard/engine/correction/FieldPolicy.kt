package com.localboard.keyboard.engine.correction

/**
 * What the keyboard may do in a field. Each attribute of the field switches off what it rules out,
 * so a field that combines them, an email field that also asks for no suggestions, gets the
 * intersection rather than whichever description happened to match first.
 */
data class FieldCapabilities(
    /** Letters go through setComposingText. Off means each one commits as it is typed. */
    val composing: Boolean,
    /** The bar and its learned-address chips. */
    val suggestions: Boolean,
    val correction: FieldCorrection,
    /** A space after a bar pick or a glide. */
    val autoSpace: Boolean,
    /** Punctuation swaps with the space an auto-space left before it. */
    val smartPunct: Boolean,
    /** Double space becomes a period. */
    val doubleSpace: Boolean,
    /** May use Shift on its own at all. The field's CAP_* flags still decide when. */
    val autoCaps: Boolean,
    val learn: Boolean,
    val glide: Boolean,
    val ai: Boolean,
    val voice: Boolean,
    val snippets: Boolean,
    /** TYPE_NULL: there is no real editor behind the connection, so send key events instead. */
    val rawKeys: Boolean,
    /** Letters assemble an address, and only whole addresses are learned. */
    val email: Boolean,
) {
    val autocorrect: Boolean get() = correction == FieldCorrection.AUTO
}

/** Pure. Values are the public android.text.InputType and EditorInfo constants. */
object FieldPolicy {
    /**
     * AOSP and HeliBoard leave a single-line field without AUTO_CORRECT to suggestions only. Held
     * off until the flags that real apps send have been read on a phone: a chat app that sets no
     * flags on its one-line input would silently lose autocorrect. This is the one switch.
     */
    const val SINGLE_LINE_NEEDS_AUTO_CORRECT = false

    private const val TYPE_NULL = 0
    private const val MASK_CLASS = 0x0000000f
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private const val MASK_VARIATION = 0x00000ff0
    private const val V_NUMBER_PASSWORD = 0x10
    private const val V_URI = 0x10
    private const val V_EMAIL = 0x20
    private const val V_PERSON_NAME = 0x60
    private const val V_POSTAL = 0x70
    private const val V_PASSWORD = 0x80
    private const val V_VISIBLE_PASSWORD = 0x90
    private const val V_WEB_EDIT_TEXT = 0xa0
    private const val V_FILTER = 0xb0
    private const val V_PHONETIC = 0xc0
    private const val V_WEB_EMAIL = 0xd0
    private const val V_WEB_PASSWORD = 0xe0
    private const val F_AUTO_CORRECT = 0x8000
    private const val F_AUTO_COMPLETE = 0x10000
    private const val F_MULTI_LINE = 0x20000
    private const val F_NO_SUGGESTIONS = 0x80000
    private const val IME_NO_PERSONALIZED_LEARNING = 0x1000000

    private fun isText(t: Int) = (t and MASK_CLASS) == CLASS_TEXT
    private fun has(t: Int, flag: Int) = (t and flag) != 0

    /**
     * The check for anything that must never touch a secret: the variation is read with its class,
     * because a variation number means something else in another class (0x10 is a URL in text, a
     * PIN in numbers and a date in dates).
     */
    fun isPassword(t: Int): Boolean {
        val v = t and MASK_VARIATION
        return when (t and MASK_CLASS) {
            CLASS_TEXT -> v == V_PASSWORD || v == V_VISIBLE_PASSWORD || v == V_WEB_PASSWORD
            CLASS_NUMBER -> v == V_NUMBER_PASSWORD
            else -> false
        }
    }

    /** The bar and learning run at all. Email stays on: the bar serves learned addresses there. */
    fun showsSuggestions(t: Int): Boolean = isText(t) && !isPassword(t) && !has(t, F_NO_SUGGESTIONS)

    fun classify(
        t: Int,
        singleLineNeedsAutoCorrect: Boolean = SINGLE_LINE_NEEDS_AUTO_CORRECT,
    ): FieldCorrection {
        if (!isText(t)) return FieldCorrection.OFF        // includes TYPE_NULL
        val v = t and MASK_VARIATION
        val autoCorrect = has(t, F_AUTO_CORRECT)
        return when {
            has(t, F_NO_SUGGESTIONS) || isPassword(t) -> FieldCorrection.OFF
            v == V_URI || v == V_EMAIL || v == V_WEB_EMAIL -> FieldCorrection.OFF
            v == V_FILTER || v == V_PERSON_NAME || v == V_POSTAL || v == V_PHONETIC -> FieldCorrection.SUGGEST_ONLY
            has(t, F_AUTO_COMPLETE) -> FieldCorrection.SUGGEST_ONLY
            v == V_WEB_EDIT_TEXT && !autoCorrect -> FieldCorrection.SUGGEST_ONLY
            singleLineNeedsAutoCorrect && !autoCorrect && !has(t, F_MULTI_LINE) -> FieldCorrection.SUGGEST_ONLY
            else -> FieldCorrection.AUTO
        }
    }

    /**
     * Everything the keyboard may do in this field. [mayLearnInApp] is the per-app learning rule;
     * the field's own no-learning flag is read from [imeOptions] here.
     *
     * AI and voice stay on under NO_SUGGESTIONS: an app that turns the bar off has not ruled out
     * dictation or a draft. Passwords are the case that must never reach either one.
     */
    fun from(
        inputType: Int,
        imeOptions: Int,
        mayLearnInApp: Boolean,
        singleLineNeedsAutoCorrect: Boolean = SINGLE_LINE_NEEDS_AUTO_CORRECT,
    ): FieldCapabilities {
        val v = inputType and MASK_VARIATION
        val text = isText(inputType) && !isPassword(inputType)
        val email = text && (v == V_EMAIL || v == V_WEB_EMAIL)
        val uri = text && v == V_URI
        val filter = text && v == V_FILTER
        val noSuggestions = has(inputType, F_NO_SUGGESTIONS)
        return FieldCapabilities(
            composing = text && !noSuggestions,
            suggestions = showsSuggestions(inputType),
            correction = classify(inputType, singleLineNeedsAutoCorrect),
            autoSpace = text && !email && !uri,
            smartPunct = text && !email && !uri && !filter,
            doubleSpace = text && !email && !uri && !filter,
            autoCaps = text && !uri,
            learn = text && mayLearnInApp && !has(imeOptions, IME_NO_PERSONALIZED_LEARNING) &&
                !uri && !filter && !noSuggestions,
            glide = text && !email && !uri && !noSuggestions,
            ai = text && !email && !uri && !filter,
            voice = text && !email && !uri && !filter,
            snippets = text && !uri && !filter && !noSuggestions,
            rawKeys = inputType == TYPE_NULL,
            email = email,
        )
    }
}
