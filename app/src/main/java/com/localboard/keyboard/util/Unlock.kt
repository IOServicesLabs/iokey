package com.localboard.keyboard.util

import android.app.Activity
import android.os.CancellationSignal

/**
 * "Prove it is you" in front of the screens that show what the keyboard has
 * learned, and in front of a backup export.
 *
 * Uses the platform [android.hardware.biometrics.BiometricPrompt] rather than
 * androidx.biometric: minSdk here is 31, so the platform API is always
 * present and pulling in another library would buy nothing. Device credential
 * is allowed alongside biometrics so a user with no enrolled fingerprint can
 * still use their PIN — otherwise this feature would simply be unavailable to
 * them, which is worse than a slower unlock.
 *
 * If no secure lock exists at all, [require] runs the action: the alternative
 * is a permanently inaccessible settings screen on a phone with no lock.
 */
object Unlock {

    fun require(activity: Activity, title: String, enabled: Boolean, onSuccess: () -> Unit) {
        if (!enabled) { onSuccess(); return }

        val km = activity.getSystemService(android.content.Context.KEYGUARD_SERVICE)
            as? android.app.KeyguardManager
        if (km == null || !km.isDeviceSecure) { onSuccess(); return }

        try {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle("IOKey keeps this behind your lock screen")
                .apply {
                    setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                }
                .build()

            prompt.authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult
                    ) {
                        activity.runOnUiThread(onSuccess)
                    }
                }
            )
        } catch (_: Throwable) {
            // Fail closed: an OEM that refuses to show the prompt must NOT
            // silently unlock the learned-data viewers on a secured device.
            // Only a device that has since gone insecure gets a pass.
            val insecureNow = (activity.getSystemService(android.content.Context.KEYGUARD_SERVICE)
                as? android.app.KeyguardManager)?.isDeviceSecure == false
            if (insecureNow) {
                onSuccess()
            } else {
                android.widget.Toast.makeText(
                    activity, "Unlock is unavailable on this device right now",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
