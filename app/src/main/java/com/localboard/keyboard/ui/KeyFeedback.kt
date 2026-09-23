package com.localboard.keyboard.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.localboard.keyboard.R

/** Per-key feedback with user-tunable vibration intensity and sound packs.
 *  The click sounds are tiny generated assets in res/raw, played through a
 *  SoundPool so volume is independent of the system ringer. Everything
 *  degrades silently: no vibrator, no decoded sound — typing still works. */
class KeyFeedback(context: Context) {
    private val appContext = context.applicationContext

    var vibLevel = 2      // 0=off 1=light 2=medium 3=strong
    var soundPack = 0     // 0=off 1=soft 2=tactile 3=bubble
    var soundVolume = 70  // 0-100

    /** Merge haptic pulses that land within [COALESCE_MS] of each other. */
    var coalesce = true

    private var lastVibrateAt = 0L

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) { null }
    }

    private val loadedIds = HashSet<Int>()

    private val soundPool: SoundPool? by lazy {
        try {
            SoundPool.Builder().setMaxStreams(3)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ).build().also { pool ->
                    // P1: SoundPool.load() is ASYNC — play() before the
                    // decode finishes silently drops the press (the first
                    // dozen clicks after choosing a pack never sounded).
                    // Only ids the listener confirms are ever played.
                    pool.setOnLoadCompleteListener { _, sampleId, status ->
                        synchronized(loadedIds) {
                            if (status == 0) loadedIds += sampleId
                        }
                    }
                }
        } catch (_: Throwable) { null }
    }
    private val soundIds = HashMap<Int, Int>()

    private fun soundIdFor(pack: Int): Int? {
        soundIds[pack]?.let { id ->
            return if (synchronized(loadedIds) { loadedIds.contains(id) }) id else null
        }
        val res = when (pack) {
            1 -> R.raw.key_soft
            2 -> R.raw.key_tactile
            3 -> R.raw.key_bubble
            else -> return null
        }
        val sp = soundPool ?: return null
        return try {
            val id = sp.load(appContext, res, 1)
            soundIds[pack] = id
            // This press stays silent; the load listener makes the NEXT one sound.
            null
        } catch (_: Throwable) { null }
    }

    /** One key press: vibrate at the chosen intensity and/or play the chosen
     *  click at the chosen volume. */
    fun keyDown() {
        if (vibLevel > 0) {
            val now = android.os.SystemClock.uptimeMillis()
            // COALESCING. The linear resonant actuator is one of the most
            // energy-expensive things a keypress can trigger, and during a fast
            // burst the pulses overlap into one indistinguishable buzz anyway —
            // the motor is still ringing down from the previous key. Merging
            // them costs nothing perceptually and skips real motor work.
            if (coalesce && now - lastVibrateAt < COALESCE_MS) {
                playClick()
                return
            }
            lastVibrateAt = now
            try {
                val v = vibrator
                if (v != null) {
                    // Every level maps to one of the vendor's own predefined
                    // waveforms (tick / click / heavy click), which are tuned
                    // for this actuator and end crisply — a hand-picked
                    // one-shot of 14-24 ms on a linear resonant actuator
                    // rings past its duration and reads as a mushy buzz, the
                    // "keys don't feel like Gboard" complaint. Gboard's default
                    // is the platform KEYBOARD_TAP, i.e. the click waveform.
                    // The one-shot durations remain only as a fallback for a
                    // vibrator that reports no support for the predefined
                    // effect. USAGE_TOUCH lets the platform apply its power
                    // and do-not-disturb policy.
                    val effect = effectFor(v, vibLevel)
                    if (Build.VERSION.SDK_INT >= 33) {
                        v.vibrate(
                            effect,
                            android.os.VibrationAttributes.createForUsage(
                                android.os.VibrationAttributes.USAGE_TOUCH
                            )
                        )
                    } else {
                        v.vibrate(effect)
                    }
                }
            } catch (_: Throwable) { }
        }
        playClick()
    }

    /** Resolved once per level: the predefined waveform when the vibrator
     *  supports it, else a one-shot of similar weight. */
    private val effectCache = arrayOfNulls<VibrationEffect>(4)

    private fun effectFor(v: Vibrator, level: Int): VibrationEffect {
        effectCache[level]?.let { return it }
        val predefined = when (level) {
            1 -> VibrationEffect.EFFECT_TICK
            2 -> VibrationEffect.EFFECT_CLICK
            else -> VibrationEffect.EFFECT_HEAVY_CLICK
        }
        val supported = try {
            v.areAllEffectsSupported(predefined) != Vibrator.VIBRATION_EFFECT_SUPPORT_NO
        } catch (_: Throwable) { true }
        val effect = if (supported) {
            VibrationEffect.createPredefined(predefined)
        } else {
            val (dur, amp) = when (level) {
                1 -> 8L to 50
                2 -> 14L to 120
                else -> 24L to 220
            }
            val a = if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE
            VibrationEffect.createOneShot(dur, a)
        }
        effectCache[level] = effect
        return effect
    }

    private fun playClick() {
        if (soundPack <= 0) return
        val sp = soundPool ?: return
        val id = soundIdFor(soundPack) ?: return
        val vol = (soundVolume / 100f).coerceIn(0f, 1f)
        try { sp.play(id, vol, vol, 1, 0, 1f) } catch (_: Throwable) { }
    }

    fun release() {
        try { soundPool?.release() } catch (_: Throwable) { }
        // A released pool's sample ids are dead: drop them so this instance
        // can never hand out a stale id (a re-load would mint fresh ones).
        soundIds.clear()
        synchronized(loadedIds) { loadedIds.clear() }
    }

    private companion object {
        /** Two keys inside this window share one haptic pulse. Tuned to sit
         *  just above a fast typist's inter-key interval (~60-90 ms) so normal
         *  typing still buzzes per key, and only bursts merge. */
        const val COALESCE_MS = 40L
    }
}
