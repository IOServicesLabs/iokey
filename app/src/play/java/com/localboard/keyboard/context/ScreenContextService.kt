package com.localboard.keyboard.context

/**
 * Play build: there is no screen reader.
 *
 * The real implementation in the full source set is an AccessibilityService
 * that reads on-screen conversation text so the AI can draft from the actual
 * exchange. Google Play treats using the accessibility APIs for anything
 * other than accessibility as a policy violation, and "read the screen to
 * feed a language model" is precisely the pattern it is enforced against.
 *
 * So the Play build does not ship a disabled version of it — it ships no
 * version of it. There is no service in the manifest and no permission to
 * grant. This stub exists purely so the handful of call sites in the IME
 * compile unchanged in both flavours; every method is a no-op and [instance]
 * is permanently null.
 *
 * The AI still works. [ScreenBuffer] simply stays empty, and context falls
 * back to the editor's own text via ContextEngine.recentTypedContext — a path
 * that already existed for apps which do not expose their content anyway.
 */
class ScreenContextService {

    /**
     * Signature matches the foss service exactly so the drafting path
     * compiles unchanged — it awaits this with a timeout. Never actually
     * called (see [instance]); resolves to "no data" if it ever were.
     */
    fun captureSoon(): kotlinx.coroutines.CompletableDeferred<Boolean> =
        kotlinx.coroutines.CompletableDeferred(false)

    fun cancelPending() = Unit

    companion object {
        /** Always null here: the service is never instantiated. */
        val instance: ScreenContextService? = null

        /** False, so settings can hide the toggle rather than offer a
         *  switch that could never do anything. */
        const val AVAILABLE = false
    }
}
