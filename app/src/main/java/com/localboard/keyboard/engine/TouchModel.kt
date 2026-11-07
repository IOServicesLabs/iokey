package com.localboard.keyboard.engine

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where this user's finger lands relative to the DRAWN centre of each letter key,
 * in dp. The output is a hit-target offset only: nothing here can move a drawn key.
 *
 * Estimator, per axis, for a key k in row r:
 *
 *     rowMean = sum(r) / (n(r) + ROW_PSEUDO)
 *     offset  = (sum(k) + KAPPA * rowMean) / (n(k) + KAPPA)     clamped to the cap
 *
 * A key with no taps takes the shrunk row mean. A key with two taps barely leaves
 * it. A key with hundreds of taps converges on its own mean. Calibration samples
 * live in their own slot, count for CALIBRATION_WEIGHT of a live tap, and are
 * REPLACED by every run, so running the check twice cannot stack.
 *
 * Not thread safe. Call from the main thread only.
 */
class TouchModel(private val store: Store, letterRows: List<String>) {

    /** One string of storage. In the app this is one SharedPreferences key per layout. */
    interface Store {
        fun read(): String
        fun write(value: String)
    }

    class Sample(val letter: Char, val dxDp: Float, val dyDp: Float)

    // Three floats per letter: n, sum of dx, sum of dy.
    private val live = FloatArray(LETTERS * 3)
    private val calib = FloatArray(LETTERS * 3)
    private val effX = FloatArray(LETTERS)
    private val effY = FloatArray(LETTERS)
    private val rowOf = IntArray(LETTERS) { -1 }
    private val rowCount = letterRows.size
    private var stale = true
    private var dirty = false

    init {
        for ((r, row) in letterRows.withIndex()) {
            for (c in row) {
                val i = c.lowercaseChar() - 'a'
                if (i in 0 until LETTERS) rowOf[i] = r
            }
        }
        load()
    }

    fun observe(letter: Char, dxDp: Float, dyDp: Float) {
        val i = letter.lowercaseChar() - 'a'
        if (i !in 0 until LETTERS) return
        if (!add(live, i, dxDp, dyDp)) return
        // Forgetting: a habit from a year ago should not outvote this month.
        if (live[i * 3] >= MAX_N) {
            live[i * 3] /= 2f
            live[i * 3 + 1] /= 2f
            live[i * 3 + 2] /= 2f
        }
        stale = true
        dirty = true
    }

    /** Replaces the previous calibration wholesale. Idempotent by construction. */
    fun setCalibration(samples: List<Sample>) {
        calib.fill(0f)
        for (s in samples) {
            val i = s.letter.lowercaseChar() - 'a'
            if (i in 0 until LETTERS) add(calib, i, s.dxDp, s.dyDp)
        }
        stale = true
        dirty = true
        persist()
    }

    fun offsetXDp(letter: Char): Float {
        val i = letter.lowercaseChar() - 'a'
        if (i !in 0 until LETTERS) return 0f
        if (stale) recompute()
        return effX[i]
    }

    fun offsetYDp(letter: Char): Float {
        val i = letter.lowercaseChar() - 'a'
        if (i !in 0 until LETTERS) return 0f
        if (stale) recompute()
        return effY[i]
    }

    /** Live taps still carrying weight. Forgetting halves a key's count, so this is
     *  what the model is working from rather than a lifetime total. */
    fun liveTapCount(): Int {
        var total = 0f
        for (i in 0 until LETTERS) total += live[i * 3]
        return total.roundToInt()
    }

    /** How far the largest learned adjustment moves a letter's target, in dp. */
    fun largestOffsetDp(): Float {
        if (stale) recompute()
        var largest = 0f
        for (i in 0 until LETTERS) {
            val d = hypot(effX[i], effY[i])
            if (d > largest) largest = d
        }
        return largest
    }

    /** Every array is zeroed. Reusing a slot can never inherit an old value. */
    fun clear() {
        live.fill(0f)
        calib.fill(0f)
        effX.fill(0f)
        effY.fill(0f)
        stale = false
        dirty = false
        store.write("")
    }

    fun persist() {
        if (!dirty) return
        store.write("v2\n" + encode(live) + "\n" + encode(calib))
        dirty = false
    }

    /** False for a non-finite sample, which would poison the sums for good: the
     *  connector point in a word's trace is NaN on purpose. */
    private fun add(a: FloatArray, i: Int, dx: Float, dy: Float): Boolean {
        if (!dx.isFinite() || !dy.isFinite()) return false
        a[i * 3] += 1f
        a[i * 3 + 1] += dx.coerceIn(-SAMPLE_CLIP_DP, SAMPLE_CLIP_DP)
        a[i * 3 + 2] += dy.coerceIn(-SAMPLE_CLIP_DP, SAMPLE_CLIP_DP)
        return true
    }

    private fun recompute() {
        val rowN = FloatArray(rowCount)
        val rowX = FloatArray(rowCount)
        val rowY = FloatArray(rowCount)
        for (i in 0 until LETTERS) {
            val r = rowOf[i]
            if (r < 0) continue
            rowN[r] += n(i)
            rowX[r] += sumX(i)
            rowY[r] += sumY(i)
        }
        for (i in 0 until LETTERS) {
            val r = rowOf[i]
            val meanX = if (r >= 0) rowX[r] / (rowN[r] + ROW_PSEUDO) else 0f
            val meanY = if (r >= 0) rowY[r] / (rowN[r] + ROW_PSEUDO) else 0f
            effX[i] = ((sumX(i) + KAPPA * meanX) / (n(i) + KAPPA)).coerceIn(-CAP_X_DP, CAP_X_DP)
            effY[i] = ((sumY(i) + KAPPA * meanY) / (n(i) + KAPPA)).coerceIn(-CAP_Y_DP, CAP_Y_DP)
        }
        stale = false
    }

    private fun n(i: Int) = live[i * 3] + CALIBRATION_WEIGHT * calib[i * 3]
    private fun sumX(i: Int) = live[i * 3 + 1] + CALIBRATION_WEIGHT * calib[i * 3 + 1]
    private fun sumY(i: Int) = live[i * 3 + 2] + CALIBRATION_WEIGHT * calib[i * 3 + 2]

    private fun encode(a: FloatArray): String {
        val sb = StringBuilder()
        for (i in 0 until LETTERS) {
            if (a[i * 3] <= 0f) continue
            if (sb.isNotEmpty()) sb.append(';')
            sb.append('a' + i).append(':')
            sb.append(String.format(Locale.US, "%.2f,%.2f,%.2f", a[i * 3], a[i * 3 + 1], a[i * 3 + 2]))
        }
        return sb.toString()
    }

    /** Anything that is not this exact format loads as empty, so the data the old
     *  tap model left behind can never be read as if it were this model's. */
    private fun load() {
        val lines = store.read().split('\n')
        if (lines.size != 3 || lines[0] != "v2") return
        decode(lines[1], live)
        decode(lines[2], calib)
        stale = true
    }

    private fun decode(line: String, into: FloatArray) {
        if (line.isEmpty()) return
        for (entry in line.split(';')) {
            val kv = entry.split(':')
            if (kv.size != 2 || kv[0].length != 1) continue
            val i = kv[0][0] - 'a'
            if (i !in 0 until LETTERS) continue
            val p = kv[1].split(',')
            if (p.size != 3) continue
            val n = p[0].toFloatOrNull() ?: continue
            val x = p[1].toFloatOrNull() ?: continue
            val y = p[2].toFloatOrNull() ?: continue
            if (!n.isFinite() || !x.isFinite() || !y.isFinite() || n <= 0f) continue
            into[i * 3] = n
            into[i * 3 + 1] = x
            into[i * 3 + 2] = y
        }
    }

    companion object {
        private const val LETTERS = 26
        const val KAPPA = 12f
        const val ROW_PSEUDO = 30f
        const val CAP_X_DP = 4f
        const val CAP_Y_DP = 5f
        const val SAMPLE_CLIP_DP = 15f
        const val MAX_N = 400f
        const val CALIBRATION_WEIGHT = 0.25f
    }
}
