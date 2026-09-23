package com.localboard.keyboard.util

/**
 * Per-key touch model that slowly adapts to where THIS user's finger
 * actually lands on each key. Every tap records its offset from the key
 * center; a running mean (with a floor learning rate so it keeps adapting)
 * becomes a small per-key hit-target shift. Complements the one-time
 * calibration game with continuous micro-adjustment. Persisted as a compact
 * string in shared prefs.
 */
class TapLearningModel(private val prefs: Prefs) {

    // Packed per-key offsets: each key maps to a slot in the two FloatArrays,
    // so the per-tap record() path stores primitives in place instead of
    // boxing a fresh Pair<Float, Float> for every keystroke.
    private val slots = HashMap<String, Int>() // key -> slot index
    private var offsetX = FloatArray(64)       // slot -> dxDp
    private var offsetY = FloatArray(64)       // slot -> dyDp
    private var size = 0
    private val counts = HashMap<String, Int>()
    private var dirty = false

    init {
        load()
    }

    /** The slot for [key], growing the backing arrays when needed. */
    private fun slotFor(key: String): Int {
        slots[key]?.let { return it }
        if (size == offsetX.size) {
            offsetX = offsetX.copyOf(offsetX.size * 2)
            offsetY = offsetY.copyOf(offsetY.size * 2)
        }
        val slot = size++
        slots[key] = slot
        return slot
    }

    @Synchronized
    fun record(keyOutput: String, dxDp: Float, dyDp: Float) {
        if (keyOutput.length != 1) return
        val n = (counts[keyOutput] ?: 0) + 1
        counts[keyOutput] = n
        // Running mean; the 0.06 floor keeps it drifting with habit changes.
        val a = (1f / n).coerceAtLeast(0.06f)
        val s = slotFor(keyOutput)
        offsetX[s] = (offsetX[s] + a * (dxDp - offsetX[s])).coerceIn(-CAP_DP, CAP_DP)
        offsetY[s] = (offsetY[s] + a * (dyDp - offsetY[s])).coerceIn(-CAP_DP, CAP_DP)
        dirty = true
        if (n % 25 == 0) persist()
    }

    /** High-confidence samples: the guided typing test (the intended letter
     *  is KNOWN) and correction-driven signals (autocorrect / quick retype
     *  tell us which key was meant vs hit). These converge fast — a stubborn
     *  "q when I meant w" habit should die within a few corrections. */
    @Synchronized
    fun recordGuided(keyOutput: String, dxDp: Float, dyDp: Float) {
        if (keyOutput.length != 1) return
        val n = (counts[keyOutput] ?: 0) + 2
        counts[keyOutput] = n
        // Gentle: this is a nudge that accrues, never a snap. A stubborn
        // habit fades over days of normal typing instead of needing a fix.
        val a = 0.12f
        val s = slotFor(keyOutput)
        offsetX[s] = (offsetX[s] + a * (dxDp - offsetX[s])).coerceIn(-GUIDED_CAP_DP, GUIDED_CAP_DP)
        offsetY[s] = (offsetY[s] + a * (dyDp - offsetY[s])).coerceIn(-GUIDED_CAP_DP, GUIDED_CAP_DP)
        dirty = true
    }

    fun hasOffsets(): Boolean = size > 0

    fun offsetFor(keyOutput: String): Pair<Float, Float>? {
        val s = slots[keyOutput] ?: return null
        return offsetX[s] to offsetY[s]
    }

    /** Snapshot of the learned offsets, for baking into the drawn layout. */
    @Synchronized
    fun snapshot(): Map<String, Pair<Float, Float>> {
        val out = HashMap<String, Pair<Float, Float>>(slots.size)
        for ((k, s) in slots) out[k] = offsetX[s] to offsetY[s]
        return out
    }

    /**
     * Forget everything, because the offsets have been baked into the key
     * geometry instead.
     *
     * This must happen whenever a fit is applied to the layout: the drawn key
     * has moved to where the finger lands, so continuing to also shift the hit
     * target by the same amount would apply the correction twice and push the
     * effective target past the key. From here the model relearns residual
     * drift from the new positions, which is what it is for.
     */
    @Synchronized
    fun clear() {
        slots.clear()
        size = 0
        counts.clear()
        dirty = true
        persist()
    }

    @Synchronized
    fun persist() {
        if (!dirty) return
        // Locale.US, not the default locale: the field separator here is a
        // comma and half the world formats 1.5 as "1,5", which made every
        // entry unparseable by load() below. The model then silently reset
        // itself to empty on every persist-then-reload cycle, so taps landed
        // on nominal centres with no personal correction at all.
        prefs.tapModelData = slots.entries.joinToString(";") { (k, s) ->
            "$k:" + "%.1f,%.1f".format(java.util.Locale.US, offsetX[s], offsetY[s])
        }
        dirty = false
    }

    private fun load() {
        val raw = prefs.tapModelData
        if (raw.isBlank()) return
        for (entry in raw.split(';')) {
            val kv = entry.split(':')
            if (kv.size != 2 || kv[0].isEmpty()) continue
            val parts = kv[1].split(',')
            if (parts.size != 2) continue
            val dx = parts[0].toFloatOrNull() ?: continue
            val dy = parts[1].toFloatOrNull() ?: continue
            val s = slotFor(kv[0])
            offsetX[s] = dx
            offsetY[s] = dy
            counts[kv[0]] = 50 // treat persisted state as established
        }
    }

    private companion object {
        const val CAP_DP = 14f
        /** Guided (high-confidence) offsets cap tighter than raw ones. */
        private const val GUIDED_CAP_DP = 10f
    }
}
