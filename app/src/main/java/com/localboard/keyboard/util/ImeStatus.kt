package com.localboard.keyboard.util

import android.content.ComponentName
import android.content.Context
import android.view.inputmethod.InputMethodManager
import com.localboard.keyboard.ime.LLMKeyboardService

/**
 * What the system actually reports about our IME registration.
 * Used by both onboarding (first-run guidance) and Settings (diagnostic line).
 */
object ImeStatus {

    private fun imm(ctx: Context) =
        ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun myId(ctx: Context) = ComponentName(
        ctx.packageName,
        LLMKeyboardService::class.java.name
    ).flattenToString()

    /** True if the system has indexed IOKey as an input method at all. */
    fun isVisible(ctx: Context): Boolean =
        imm(ctx).inputMethodList.any { it.id == myId(ctx) }

    /** True if the user has toggled IOKey on in system keyboard settings. */
    fun isEnabled(ctx: Context): Boolean =
        imm(ctx).enabledInputMethodList.any { it.id == myId(ctx) }

    /**
     * Deep diagnostic for the "system doesn't list the keyboard" case.
     * Distinguishes the failure layer:
     *  - pmResolves=false: PackageManager doesn't even resolve our service
     *    (manifest indexing / package-state problem).
     *  - pmResolves=true but isVisible=false: PM knows the service but the
     *    input-method subsystem dropped it (meta-data/permission parse
     *    rejection — or OEM suppression).
     */
    fun diagnosticDetails(ctx: Context): String {
        val pm = ctx.packageManager
        val cn = ComponentName(ctx.packageName, LLMKeyboardService::class.java.name)
        @Suppress("DEPRECATION")
        val resolvedAll = pm.queryIntentServices(
            android.content.Intent("android.view.InputMethod"),
            android.content.pm.PackageManager.MATCH_ALL
        )
        val ours = resolvedAll.any { it.serviceInfo.packageName == ctx.packageName }
        val compState = try {
            when (pm.getComponentEnabledSetting(cn)) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled(!)"
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disabled by user(!)"
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disabled-until-used(!)"
                else -> "default"
            }
        } catch (t: Throwable) {
            "error: ${t.javaClass.simpleName}"
        }
        val others = resolvedAll.mapNotNull { it.serviceInfo.packageName }
            .filter { it != ctx.packageName }.distinct().take(3)
        return buildString {
            append("Diagnostic (share this if asking for help):\n")
            append("• PM resolves our IME service: ${if (ours) "yes" else "NO"}\n")
            append("• Our service component state: $compState\n")
            append("• IMEs the system lets us see: ${resolvedAll.size}")
            if (others.isNotEmpty()) append(" (e.g. ${others.joinToString()})")
            append("\n• App debuggable: ${(ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
        }
    }
}
