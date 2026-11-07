import android.graphics.PointF
import android.graphics.RectF
import com.localboard.keyboard.engine.DwellMask
import com.localboard.keyboard.engine.KeyLayoutMath
import com.localboard.keyboard.engine.KeyboardGeometry
import com.localboard.keyboard.touch.DeviceLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The board and the fingers the offline numbers are measured on. Every finger here is
 * assumed: none of it was fitted to a person or to recorded traces, so the results compare
 * decoders and say nothing about how a real hand will do.
 */

class Board(
    val geom: KeyboardGeometry,
    val centre: Map<Char, PointF>,
    val rects: Map<Char, RectF>,
    val keyW: Float,
    val pitchX: Float
)

/** The letter keys where the keyboard draws them. The layout comes from KeyLayoutMath, the
 *  one copy of those numbers, so a change to the layout reaches these results by itself. */
fun deviceBoard(m: KeyLayoutMath.Metrics = DeviceLayout.METRICS): Board {
    val g = KeyboardGeometry()
    g.clear()
    val centre = HashMap<Char, PointF>()
    val rects = HashMap<Char, RectF>()
    for (row in DeviceLayout.hitRows(m)) for (k in row) {
        if (k.symbol !in 'a'..'z') continue
        val r = RectF(k.left, k.top, k.right, k.bottom)
        g.register(k.symbol, r)
        centre[k.symbol] = PointF(r.centerX(), r.centerY())
        rects[k.symbol] = r
    }
    g.finishLayout()
    return Board(g, centre, rects, g.averageKeyWidth, centre.getValue('w').x - centre.getValue('q').x)
}

class Noise(val pos: Float, val cut: Float, val jitter: Float, val dwellP: Float, val name: String)

val NOISES = listOf(
    Noise(0.18f, 0.25f, 0.02f, 0.7f, "clean"),
    Noise(0.28f, 0.45f, 0.03f, 0.5f, "normal"),
    Noise(0.38f, 0.6f, 0.04f, 0.3f, "sloppy")
)

/** What 1.0 of a noise amount means in px. Fixed, because a finger's error is a physical
 *  size and does not shrink when the keys do. */
const val NOISE_X = 104f
const val NOISE_Y = 133f

/** Px the view keeps between path points: dp(2) on this board. */
const val THIN_PX = 6f

fun gauss(rnd: Random, s: Float): Float =
    (rnd.nextDouble() * 2 - 1 + rnd.nextDouble() * 2 - 1 + rnd.nextDouble() * 2 - 1).toFloat() * s * 0.8f

/** A systematic habit, as opposed to noise, which averages out. */
class Finger(
    val name: String,
    val yBiasPx: Float = 0f,
    val shrink: Float = 1f,
    /** The path ends this far short of the last letter. */
    val earlyLiftPx: Float = 0f,
    /** Lands anywhere on the first key rather than around its centre. */
    val landAnywhere: Boolean = false,
    /** Extra offset for each keyboard row, top to bottom. A thumb misses the rows it
     *  stretches for by more than the ones near it, so the error is not one rigid shift. */
    val rowXPx: FloatArray? = null,
    val rowYPx: FloatArray? = null,
    /** How much of the word's height survives, with its width untouched. */
    val squashY: Float = 1f,
    /** Length of the small hook at landing and at lift. Timed traces only. */
    val hookPx: Float = 0f
)

val IDEAL = Finger("ideal")
val REAL_FINGER = Finger("real-finger", yBiasPx = 28f, shrink = 0.9f, earlyLiftPx = 40f, landAnywhere = true)
val OFFSET_FINGER = Finger("offset+20dp", yBiasPx = 56f)
/** The decoder's shape channel centres a gesture and scales it by its larger side, which cancels
 *  [REAL_FINGER]'s shift and shrink by construction. None of this one's habits cancel that way. */
val HOOKED_FINGER = Finger(
    "hooked",
    rowXPx = floatArrayOf(-14f, 0f, 16f),
    rowYPx = floatArrayOf(38f, 20f, 6f),
    squashY = 0.8f,
    hookPx = 30f
)

private fun rowOf(c: Char): Int = when (c) {
    in "qwertyuiop" -> 0
    in "asdfghjkl" -> 1
    else -> 2
}

class Targets(val px: FloatArray, val py: FloatArray, val dwell: BooleanArray)

fun targets(word: String, b: Board, nz: Noise, f: Finger, rnd: Random): Targets {
    val n = word.length
    val tx = FloatArray(n)
    val ty = FloatArray(n)
    for (i in 0 until n) {
        val c = b.centre.getValue(word[i])
        tx[i] = c.x + gauss(rnd, nz.pos * NOISE_X) + (f.rowXPx?.get(rowOf(word[i])) ?: 0f)
        ty[i] = c.y + gauss(rnd, nz.pos * NOISE_Y) + f.yBiasPx + (f.rowYPx?.get(rowOf(word[i])) ?: 0f)
    }
    if (f.landAnywhere) {
        val r = b.rects.getValue(word[0])
        tx[0] = r.left + rnd.nextFloat() * (r.right - r.left)
        ty[0] = r.top + rnd.nextFloat() * (r.bottom - r.top)
    }
    if (f.shrink != 1f && n > 1) {
        val mx = tx.average().toFloat()
        val my = ty.average().toFloat()
        for (i in 0 until n) {
            tx[i] = mx + (tx[i] - mx) * f.shrink
            ty[i] = my + (ty[i] - my) * f.shrink
        }
    }
    if (f.squashY != 1f && n > 1) {
        val my = ty.average().toFloat()
        for (i in 0 until n) ty[i] = my + (ty[i] - my) * f.squashY
    }
    // Corners get rounded, by as much as the turn allows: a straight-back reversal cannot be cut.
    for (i in 1 until n - 1) {
        if (word[i] == word[i - 1] || word[i] == word[i + 1]) continue
        val ax = tx[i] - tx[i - 1]
        val ay = ty[i] - ty[i - 1]
        val bx = tx[i + 1] - tx[i]
        val by = ty[i + 1] - ty[i]
        val la = sqrt(ax * ax + ay * ay)
        val lb = sqrt(bx * bx + by * by)
        if (la < 1f || lb < 1f) continue
        val cross = abs(ax * by - ay * bx) / (la * lb)
        val ff = rnd.nextFloat() * nz.cut * cross
        tx[i] += ((tx[i - 1] + tx[i + 1]) / 2f - tx[i]) * ff
        ty[i] += ((ty[i - 1] + ty[i + 1]) / 2f - ty[i]) * ff
    }
    // A doubled letter is one target, remembered as a place the finger may pause.
    val px = ArrayList<Float>()
    val py = ArrayList<Float>()
    val dwell = ArrayList<Boolean>()
    for (i in 0 until n) {
        if (i > 0 && word[i] == word[i - 1]) {
            dwell[dwell.size - 1] = true
            continue
        }
        px += tx[i]
        py += ty[i]
        dwell += false
    }
    return Targets(px.toFloatArray(), py.toFloatArray(), dwell.toBooleanArray())
}

private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
}

private fun truncateEnd(pts: List<PointF>, dropPx: Float): List<PointF> {
    if (dropPx <= 0f || pts.size < 3) return pts
    var left = dropPx
    var i = pts.size - 1
    while (i > 1) {
        val d = sqrt((pts[i].x - pts[i - 1].x) * (pts[i].x - pts[i - 1].x) + (pts[i].y - pts[i - 1].y) * (pts[i].y - pts[i - 1].y))
        if (d >= left) break
        left -= d
        i--
    }
    return pts.subList(0, i + 1)
}

/** One gesture as an untimed path. The quick form, with the pause drawn in as extra points,
 *  which the view's thinning would remove from a real finger: only [timedGesture] tells a pause. */
fun gesture(word: String, b: Board, nz: Noise, f: Finger, rnd: Random): List<PointF> {
    val t = targets(word, b, nz, f, rnd)
    val px = t.px
    val py = t.py
    val m = px.size
    val out = ArrayList<PointF>()
    repeat(3) { out += PointF(px[0] + gauss(rnd, 1f), py[0] + gauss(rnd, 1f)) }
    if (m == 1) {
        repeat(4) { out += PointF(px[0] + gauss(rnd, 2f), py[0] + gauss(rnd, 2f)) }
        return out
    }
    for (i in 0 until m - 1) {
        val x0 = px[max(0, i - 1)]
        val y0 = py[max(0, i - 1)]
        val x1 = px[i]
        val y1 = py[i]
        val x2 = px[i + 1]
        val y2 = py[i + 1]
        val x3 = px[min(m - 1, i + 2)]
        val y3 = py[min(m - 1, i + 2)]
        val segLen = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))
        val steps = max(2, (segLen / (0.08f * NOISE_X)).toInt())
        for (s in 0 until steps) {
            val u = s.toFloat() / steps
            out += PointF(cr(x0, x1, x2, x3, u) + gauss(rnd, nz.jitter * NOISE_X), cr(y0, y1, y2, y3, u) + gauss(rnd, nz.jitter * NOISE_X))
        }
        if (t.dwell[i] && i > 0 && rnd.nextFloat() < nz.dwellP) {
            val at = out.size - steps
            val ins = ArrayList<PointF>()
            repeat(7) { ins += PointF(px[i] + gauss(rnd, 2.5f), py[i] + gauss(rnd, 2.5f)) }
            out.addAll(at, ins)
        }
    }
    out += PointF(px[m - 1], py[m - 1])
    repeat(3) { out += PointF(px[m - 1] + gauss(rnd, 1.5f), py[m - 1] + gauss(rnd, 1.5f)) }
    val cut = truncateEnd(out, f.earlyLiftPx)
    val thin = ArrayList<PointF>()
    for (p in cut) {
        if (thin.isEmpty()) {
            thin += p
            continue
        }
        val l = thin.last()
        if (sqrt((p.x - l.x) * (p.x - l.x) + (p.y - l.y) * (p.y - l.y)) >= THIN_PX) thin += p
    }
    if (thin.last() !== cut.last()) thin += cut.last()
    return thin
}

/** One raw touch sample. */
class Sample(val t: Double, val x: Float, val y: Float)

private fun progress(tau: Double): Double {
    val minJerk = 10 * tau * tau * tau - 15 * tau * tau * tau * tau + 6 * tau * tau * tau * tau * tau
    return 0.45 * tau + 0.55 * minJerk
}

/** A 120 Hz digitizer trace: the finger slows at each letter without quite stopping, and
 *  stays put for 140 ms on a doubled letter when it pauses there at all. */
fun timedGesture(word: String, b: Board, nz: Noise, f: Finger, rnd: Random, speed: Double = 1.0): List<Sample> {
    val tt = targets(word, b, nz, f, rnd)
    val px = tt.px
    val py = tt.py
    val m = px.size
    val out = ArrayList<Sample>()
    val dt = 1000.0 / 120.0
    var now = 0.0
    fun emit(x: Float, y: Float) {
        out += Sample(now, x + gauss(rnd, 0.6f), y + gauss(rnd, 0.6f))
        now += dt
    }
    // Drawn from the generator only for a finger that hooks, so the others keep their gestures.
    if (f.hookPx > 0f) {
        val a = rnd.nextDouble() * 2.0 * PI
        for (k in 6 downTo 1) emit(px[0] + (cos(a) * f.hookPx * k / 6.0).toFloat(), py[0] + (sin(a) * f.hookPx * k / 6.0).toFloat())
    }
    repeat(5) { emit(px[0], py[0]) }
    for (i in 0 until m - 1) {
        val x0 = px[max(0, i - 1)]
        val y0 = py[max(0, i - 1)]
        val x1 = px[i]
        val y1 = py[i]
        val x2 = px[i + 1]
        val y2 = py[i + 1]
        val x3 = px[min(m - 1, i + 2)]
        val y3 = py[min(m - 1, i + 2)]
        val nFine = 64
        val fx = FloatArray(nFine + 1)
        val fy = FloatArray(nFine + 1)
        val fa = FloatArray(nFine + 1)
        for (k in 0..nFine) {
            val u = k.toFloat() / nFine
            fx[k] = cr(x0, x1, x2, x3, u)
            fy[k] = cr(y0, y1, y2, y3, u)
            if (k > 0) fa[k] = fa[k - 1] + sqrt((fx[k] - fx[k - 1]) * (fx[k] - fx[k - 1]) + (fy[k] - fy[k - 1]) * (fy[k] - fy[k - 1]))
        }
        val total = fa[nFine]
        val segMs = (100.0 + 28.0 * total / b.pitchX) / speed
        if (tt.dwell[i] && rnd.nextFloat() < nz.dwellP) {
            repeat((140.0 / dt).toInt()) { emit(x1, y1) }
        }
        val nSteps = max(1, (segMs / dt).toInt())
        var k = 0
        for (s in 0 until nSteps) {
            val target = (progress(s.toDouble() / nSteps) * total).toFloat()
            while (k < nFine && fa[k + 1] < target) k++
            val seg = fa[min(k + 1, nFine)] - fa[k]
            val w = if (seg > 0f) (target - fa[k]) / seg else 0f
            val kk = min(k + 1, nFine)
            emit(fx[k] + (fx[kk] - fx[k]) * w, fy[k] + (fy[kk] - fy[k]) * w)
        }
    }
    repeat(3) { emit(px[m - 1], py[m - 1]) }
    if (f.hookPx > 0f) {
        val a = rnd.nextDouble() * 2.0 * PI
        for (k in 1..6) emit(px[m - 1] + (cos(a) * f.hookPx * k / 6.0).toFloat(), py[m - 1] + (sin(a) * f.hookPx * k / 6.0).toFloat())
    }
    if (f.earlyLiftPx > 0f) {
        var left = f.earlyLiftPx
        while (out.size > 3 && left > 0f) {
            val a = out[out.size - 1]
            val c = out[out.size - 2]
            left -= sqrt((a.x - c.x) * (a.x - c.x) + (a.y - c.y) * (a.y - c.y))
            out.removeAt(out.size - 1)
        }
    }
    return out
}

/**
 * The path the view hands over at lift for this trace: nothing until the finger has
 * travelled 0.7 of a key, then the whole thinned path from finger-down, and the lift point
 * whatever its spacing. Null when the gesture never armed, which the view treats as a tap.
 * A copy of KeyboardView's rules until that logic can be shared.
 */
fun capture(raw: List<Sample>, b: Board): List<PointF>? {
    val arm = max(8f * DeviceLayout.DENSITY * 2f, b.keyW * 0.7f)
    fun d(a: PointF, x: Float, y: Float) = sqrt((a.x - x) * (a.x - x) + (a.y - y) * (a.y - y))
    val down = raw[0]
    val points = ArrayList<PointF>()
    points += PointF(down.x, down.y)
    var armed = false
    for (i in 1 until raw.size) {
        val s = raw[i]
        if (!armed) {
            val dx = s.x - down.x
            val dy = s.y - down.y
            armed = dx * dx + dy * dy > arm * arm
        }
        if (d(points.last(), s.x, s.y) >= THIN_PX) points += PointF(s.x, s.y)
    }
    if (!armed) return null
    val lift = raw.last()
    points += PointF(lift.x, lift.y)
    return points
}

/**
 * A dwell mask worked out from raw samples, by the same code the view runs on its unthinned
 * touch stream: 96 ms in which the finger travelled under a quarter of a key, away from the ends
 * of the gesture, on a key.
 */
fun dwellMaskOf(raw: List<Sample>, b: Board): Int {
    val n = raw.size
    return DwellMask.of(
        FloatArray(n) { raw[it].x },
        FloatArray(n) { raw[it].y },
        LongArray(n) { raw[it].t.toLong() },
        n,
        b.keyW,
        FloatArray(26) { b.centre['a' + it]?.x ?: Float.NaN },
        FloatArray(26) { b.centre['a' + it]?.y ?: Float.NaN }
    )
}
