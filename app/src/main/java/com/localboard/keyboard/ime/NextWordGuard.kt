package com.localboard.keyboard.ime

/**
 * Whether the next-word path may go on to look for a model and read the field. A person's switch for that path
 * survives an upgrade, so this is what stands between the first day on a new build and a model that loads by itself.
 */
internal object NextWordGuard {

    /**
     * All of these must hold, because each one rules out a different thing. [fieldAi] stays true in a field that only
     * turned suggestions off, which is why [fieldSuggestions] is asked as well. [typedSinceSeed] is 0 until the person
     * has typed in this field, so a trail read from the field never starts anything. [skipOnce] is the first pass of
     * a show, restarts included, when nobody has asked for anything yet.
     */
    fun mayAsk(
        hasField: Boolean,
        aiAllowedHere: Boolean,
        fieldAi: Boolean,
        fieldSuggestions: Boolean,
        typedSinceSeed: Int,
        skipOnce: Boolean
    ): Boolean =
        hasField && aiAllowedHere && fieldAi && fieldSuggestions && typedSinceSeed > 0 && !skipOnce
}
