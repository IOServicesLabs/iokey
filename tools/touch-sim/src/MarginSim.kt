import com.localboard.keyboard.engine.HitKey
import com.localboard.keyboard.engine.HitMap
import com.localboard.keyboard.touch.DeviceLayout
import java.util.Locale
import java.util.Random
import kotlin.math.abs

/**
 * What the margin defect costs on its own: the five-step findKey the keyboard shipped
 * with, against the plain HitMap, on the same simulated taps. No fit and no tap model
 * on either side, so nothing here depends on the typing test.
 *
 * All of this is simulated. The finger is assumed: Gaussian noise of 9 dp, a bias per
 * user and per key, English letter frequencies. It says how the two maps compare on
 * that finger and nothing about a real hand.
 */
object MarginSim {

    /** Share per letter of running text, space separately. */
    private val LETTER_FREQ = linkedMapOf(
        'e' to 12.02, 't' to 9.10, 'a' to 8.12, 'o' to 7.68, 'i' to 7.31, 'n' to 6.95, 's' to 6.28,
        'r' to 6.02, 'h' to 5.92, 'd' to 4.32, 'l' to 3.98, 'u' to 2.88, 'c' to 2.71, 'm' to 2.61,
        'f' to 2.30, 'y' to 2.11, 'w' to 2.09, 'g' to 2.03, 'p' to 1.82, 'b' to 1.49, 'v' to 1.11,
        'k' to 0.69, 'x' to 0.17, 'q' to 0.11, 'j' to 0.10, 'z' to 0.07
    )
    private const val SPACE_SHARE = 0.18
    private const val SIGMA_DP = 9f
    private const val TAU_DP = 1.5f
    private const val TAPS_PER_KEY = 20_000

    private val rows = DeviceLayout.hitRows()
    private val keys: Array<HitKey> = rows.flatten().toTypedArray()
    private val keyGap = DeviceLayout.METRICS.keyGap
    private val rowGap = DeviceLayout.METRICS.rowGap
    private val keyHeight = DeviceLayout.METRICS.keyHeight

    /** KeyboardView.findKey as it shipped, with no tap model, so step 3 never runs. */
    fun findKeyAsShipped(x: Float, y: Float): Int {
        for (i in keys.indices) {
            val k = keys[i]
            if (x >= k.left && x < k.right && y >= k.top && y < k.bottom) return i
        }
        val halfX = keyGap / 2f
        val halfY = rowGap / 2f
        var gapBest = -1
        var gapBestD = Float.MAX_VALUE
        for (i in keys.indices) {
            val k = keys[i]
            if (x >= k.left - halfX && x <= k.right + halfX && y >= k.top - halfY && y <= k.bottom + halfY) {
                val dx = x - k.cx
                val dy = y - k.cy
                val d = dx * dx + dy * dy
                if (d < gapBestD) {
                    gapBestD = d
                    gapBest = i
                }
            }
        }
        if (gapBest >= 0) return gapBest
        for (i in keys.indices) {
            val k = keys[i]
            if (x >= k.left && x <= k.right && abs(y - k.cy) < keyHeight * 0.75f) return i
        }
        var best = -1
        var bestD2 = Float.MAX_VALUE
        val maxDx = keyGap * 0.9f
        for (i in keys.indices) {
            val k = keys[i]
            val dx = maxOf(k.left - x, 0f, x - k.right)
            val dy = maxOf(k.top - y, 0f, y - k.bottom)
            if (dx > maxDx) continue
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) {
                bestD2 = d2
                best = i
            }
        }
        return best
    }

    class Tally {
        val wrong = HashMap<Char, Long>()
        var deleteFromL = 0L
        var shiftFromA = 0L

        fun record(aimed: Char, want: Int, got: Int, shift: Int, delete: Int) {
            if (got != want) wrong[aimed] = (wrong[aimed] ?: 0L) + 1L
            if (aimed == 'l' && got == delete) deleteFromL++
            if (aimed == 'a' && got == shift) shiftFromA++
        }
    }

    class Outcome(val weighted: Double, val tally: Tally, val perKey: Long) {
        fun keyPct(c: Char) = 100.0 * (tally.wrong[c] ?: 0L) / perKey
        val weightedPct get() = 100.0 * weighted
        val deleteFromLPct get() = 100.0 * tally.deleteFromL / perKey
        val shiftFromAPct get() = 100.0 * tally.shiftFromA / perKey
    }

    class Scenario(val asShipped: Outcome, val map: Outcome, val asShippedOnScreen: Outcome, val mapOnScreen: Outcome)

    /** Bias noise for [count] users nobody has looked at before, in the same shape as [ReferenceUsers]. */
    fun freshUsers(count: Int, seed: Long): List<Map<Char, Pair<Float, Float>>> {
        val rng = Random(seed)
        val aimed = LETTER_FREQ.keys.toList() + ' '
        return List(count) {
            aimed.associateWith {
                (rng.nextGaussian().toFloat() * TAU_DP) to (rng.nextGaussian().toFloat() * TAU_DP)
            }
        }
    }

    /**
     * Taps can fall outside the view, because the noise is not bounded by the screen.
     * The first pair of numbers leaves them where they fell, which is how the reference
     * figures were produced: the shipped map finds no key out there and counts an error,
     * and the HitMap gets unbounded edges so an edge key still owns the tap. The second
     * pair clamps every tap to the screen, because a real touch can only land on it.
     * Both are reported so the difference stays visible.
     */
    fun scenario(
        biasX: Float,
        biasY: Float,
        users: List<Map<Char, Pair<Float, Float>>> = ReferenceUsers.draws,
        tapsPerKey: Int = TAPS_PER_KEY
    ): Scenario {
        val d = DeviceLayout.DENSITY
        val w = DeviceLayout.WIDTH_PX.toFloat()
        val h = DeviceLayout.HEIGHT_PX.toFloat()
        val unbounded = HitMap(rows, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        val onScreen = HitMap(rows, 0f, w)
        val index = HashMap<Char, Int>()
        for ((i, k) in keys.withIndex()) if (k.scorable) index[k.symbol] = i
        val shift = keys.indexOf(rows[2].first())
        val delete = keys.indexOf(rows[2].last())
        val aimed = LETTER_FREQ.keys.toList() + ' '

        val shipped = Tally()
        val mapped = Tally()
        val shippedClamped = Tally()
        val mappedClamped = Tally()
        for ((u, noise) in users.withIndex()) {
            val rng = Random(300L + u)
            for (c in aimed) {
                val want = index.getValue(c)
                val k = keys[want]
                val side = if (k.cx < w / 2f) -1f else 1f
                val (nx, ny) = noise.getValue(c)
                val bx = side * biasX + nx
                val by = biasY + ny
                repeat(tapsPerKey) {
                    val x = k.cx + (bx + rng.nextGaussian().toFloat() * SIGMA_DP) * d
                    val y = k.cy + (by + rng.nextGaussian().toFloat() * SIGMA_DP) * d
                    shipped.record(c, want, findKeyAsShipped(x, y), shift, delete)
                    mapped.record(c, want, unbounded.keyAt(x, y), shift, delete)
                    val cx = x.coerceIn(0f, w - 1f)
                    val cy = y.coerceIn(0f, h - 1f)
                    shippedClamped.record(c, want, findKeyAsShipped(cx, cy), shift, delete)
                    mappedClamped.record(c, want, onScreen.keyAt(cx, cy), shift, delete)
                }
            }
        }
        val perKey = users.size.toLong() * tapsPerKey
        return Scenario(
            outcome(shipped, perKey),
            outcome(mapped, perKey),
            outcome(shippedClamped, perKey),
            outcome(mappedClamped, perKey)
        )
    }

    private fun outcome(t: Tally, perKey: Long): Outcome {
        val total = LETTER_FREQ.values.sum()
        var weighted = 0.0
        for ((c, f) in LETTER_FREQ) {
            weighted += (f / total * (1.0 - SPACE_SHARE)) * (t.wrong[c] ?: 0L) / perKey
        }
        weighted += SPACE_SHARE * (t.wrong[' '] ?: 0L) / perKey
        return Outcome(weighted, t, perKey)
    }

    fun print(title: String, s: Scenario) {
        fun line(name: String, shipped: Outcome, map: Outcome) {
            println(
                String.format(
                    Locale.US,
                    "  %-26s weighted error %.2f%% -> %.2f%% (%+.1f%%)",
                    name, shipped.weightedPct, map.weightedPct,
                    100 * (map.weighted / shipped.weighted - 1)
                )
            )
            println(
                "    per key: " + "aqzlpm".map { c ->
                    String.format(Locale.US, "%c %.1f->%.1f", c, shipped.keyPct(c), map.keyPct(c))
                }.joinToString("  ")
            )
            println(
                String.format(
                    Locale.US,
                    "    aimed at l and fired DELETE %.2f%% -> %.2f%%; aimed at a and toggled SHIFT %.2f%% -> %.2f%%",
                    shipped.deleteFromLPct, map.deleteFromLPct, shipped.shiftFromAPct, map.shiftFromAPct
                )
            )
        }
        println(title)
        line("taps where they fell", s.asShipped, s.map)
        line("taps clamped to the screen", s.asShippedOnScreen, s.mapOnScreen)
    }
}
