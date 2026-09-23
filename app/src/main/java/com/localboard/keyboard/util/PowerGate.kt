package com.localboard.keyboard.util

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Should the phone be asked to run an LLM right now?
 *
 * Drafting is the only genuinely expensive thing this keyboard does — seconds
 * of sustained compute on every core. On a hot or nearly-flat phone that is a
 * bad trade the user did not ask for, and it is the kind of cost that gets a
 * keyboard uninstalled ("it eats my battery") without them ever connecting it
 * to the AI feature.
 *
 * Typing, autocorrect, swipe and predictions are NEVER gated here. They are
 * cheap, and a keyboard that stops correcting at 14% battery is broken.
 */
object PowerGate {

    /** Human-readable reason to skip AI work, or null to proceed. */
    fun blockReason(context: Context, prefs: Prefs): String? {
        if (!prefs.powerAwareAi) return null
        return reason(context, prefs)
    }

    /**
     * True when the phone is low or hot, regardless of the AI preference.
     *
     * Used to drop the decorative work — the animated background — as the
     * battery drains. A keyboard that keeps painting drifting gradients at 8%
     * is spending the user's last few minutes of phone on decoration.
     */
    fun isLowPower(context: Context, prefs: Prefs): Boolean =
        reason(context, prefs) != null

    private fun reason(context: Context, prefs: Prefs): String? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        try {
            // SEVERE is the point at which the system is already throttling
            // the CPU; piling an LLM on top makes the phone hotter and the
            // draft slower at the same time.
            if (pm != null && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                return "phone is too warm"
            }
        } catch (_: Throwable) {
            // Not all OEMs implement thermal status; absence is not a block.
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        // On charge, heat is the only concern and that is handled above.
        if (bm.isCharging) return null
        val level = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) { -1 }
        if (level in 0 until prefs.aiBatteryFloor) return "battery at $level%"
        return null
    }
}
