// The property checks and the two simulations behind the touch design, run against the real
// classes. Everything measured in percent here is simulated: the finger is assumed and the
// users are invented, so these numbers compare designs and promise nothing about a real hand.
import com.localboard.keyboard.engine.HitMap
import com.localboard.keyboard.engine.KeyLayoutMath
import com.localboard.keyboard.engine.NextCharPrior
import com.localboard.keyboard.engine.TapResolver
import com.localboard.keyboard.engine.TouchCheck
import com.localboard.keyboard.engine.TouchModel
import com.localboard.keyboard.engine.Trie
import com.localboard.keyboard.touch.DeviceLayout
import com.localboard.keyboard.touch.MemStore
import com.localboard.keyboard.util.PrefsMigrations
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.system.exitProcess

const val D = DeviceLayout.DENSITY
val W = DeviceLayout.WIDTH_PX.toFloat()
val ROWS = DeviceLayout.LETTER_ROWS

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (!ok) failures++
    println((if (ok) "PASS " else "FAIL ") + name + if (detail.isEmpty()) "" else "  [$detail]")
}

private fun near(a: Float, b: Float, tol: Float) = abs(a - b) <= tol

fun layoutTests() {
    println("--- KeyLayoutMath on the device ---")
    val m = DeviceLayout.METRICS
    val b = DeviceLayout.boxes()
    fun dp(px: Float) = px / D
    check("384 dp wide", near(W / D, 384f, 0.01f))
    check("key height 40.18 dp", near(dp(m.keyHeight), 40.18f, 0.01f), "${dp(m.keyHeight)}")
    check("gap 4.62 dp", near(dp(m.keyGap), 4.62f, 0.01f), "${dp(m.keyGap)}")
    check("row gap 9.96 dp", near(dp(m.rowGap), 9.96f, 0.01f), "${dp(m.rowGap)}")
    check("letter face 33.7 dp", near(dp(b[0][0].width), 33.7f, 0.05f), "${dp(b[0][0].width)}")
    check("pitch across 38.3 dp", near(dp(b[0][1].left - b[0][0].left), 38.3f, 0.05f), "${dp(b[0][1].left - b[0][0].left)}")
    check("pitch down 50.13 dp", near(dp(b[1][0].top - b[0][0].top), 50.13f, 0.05f), "${dp(b[1][0].top - b[0][0].top)}")
    check("keyboard 206.93 dp", near(dp(DeviceLayout.HEIGHT_PX.toFloat()), 206.93f, 0.05f), "${dp(DeviceLayout.HEIGHT_PX.toFloat())}")
    check("z row face 34.1 dp", near(dp(b[2][1].width), 34.1f, 0.05f), "${dp(b[2][1].width)}")
    check("SHIFT and DELETE 51.2 dp", near(dp(b[2][0].width), 51.2f, 0.05f) && near(dp(b[2].last().width), 51.2f, 0.05f))
    check("space 142.1 dp", near(dp(b[3][3].width), 142.1f, 0.05f), "${dp(b[3][3].width)}")
    check("home row inset 18.9 dp", near(dp(b[1][0].left - b[0][0].left), 18.9f, 0.05f), "${dp(b[1][0].left - b[0][0].left)}")
}

fun touchModelTests() {
    println("--- TouchModel ---")
    run {
        val s = MemStore()
        val m = TouchModel(s, ROWS)
        repeat(50) { m.observe('k', 6f, 6f) }
        check("the offset was really computed before the clear", m.offsetXDp('k') != 0f && m.offsetYDp('k') != 0f)
        m.clear()
        check("clear() zeroes: fresh offsets are exactly 0 after re-observing nothing", m.offsetXDp('k') == 0f && m.offsetYDp('k') == 0f)
        m.observe('a', 0f, 0f)
        check("clear() zeroes: reusing another key leaves it at 0", m.offsetXDp('a') == 0f && m.offsetXDp('k') == 0f)
        check("clear() wipes storage", s.value.isEmpty())
    }
    run {
        val m = TouchModel(MemStore(), ROWS)
        repeat(5000) { m.observe('h', 40f, -40f) }
        check("cap holds at +4/-5 under an extreme stream", m.offsetXDp('h') == 4f && m.offsetYDp('h') == -5f, "${m.offsetXDp('h')},${m.offsetYDp('h')}")
    }
    run {
        val m = TouchModel(MemStore(), ROWS)
        m.observe('z', 12f, -9f)
        m.observe('z', 12f, -9f)
        val fx = m.offsetXDp('z') / 12f
        check("two taps on an otherwise empty board stay small", fx < 0.25f, "offset is ${"%.3f".format(Locale.US, fx)} of the sample mean")
        val m2 = TouchModel(MemStore(), ROWS)
        repeat(60) { m2.observe('a', 0f, 0f); m2.observe('s', 0f, 0f); m2.observe('d', 0f, 0f) }
        m2.observe('g', 15f, 15f)
        m2.observe('g', 15f, 15f)
        check("two outlier taps on a key in a well-populated row stay small", abs(m2.offsetXDp('g')) < 2.5f, "g=${m2.offsetXDp('g')}")
    }
    run {
        val rng = Random(5)
        val samples = (0 until 180).map { TouchModel.Sample('a' + rng.nextInt(26), 4f + rng.nextGaussian().toFloat() * 7f, 6f + rng.nextGaussian().toFloat() * 7f) }
        val m = TouchModel(MemStore(), ROWS)
        m.setCalibration(samples)
        val first = ('a'..'z').map { m.offsetXDp(it) to m.offsetYDp(it) }
        repeat(5) { m.setCalibration(samples) }
        val again = ('a'..'z').map { m.offsetXDp(it) to m.offsetYDp(it) }
        check("re-running calibration with the same taps changes nothing", first == again)
        val maxAbs = first.maxOf { maxOf(abs(it.first), abs(it.second)) }
        check("a calibration alone cannot reach the cap", maxAbs < 4f, "largest offset ${"%.2f".format(Locale.US, maxAbs)}dp from 180 taps measuring (4,6)")
        val other = samples.map { TouchModel.Sample(it.letter, -it.dxDp, -it.dyDp) }
        m.setCalibration(other)
        m.setCalibration(samples)
        val back = ('a'..'z').map { m.offsetXDp(it) to m.offsetYDp(it) }
        check("a different run replaces, never stacks", first == back)
    }
    run {
        val s = MemStore()
        val m = TouchModel(s, ROWS)
        val rng = Random(6)
        repeat(4000) { m.observe('a' + rng.nextInt(26), rng.nextGaussian().toFloat() * 9f + 3f, rng.nextGaussian().toFloat() * 9f + 4f) }
        m.persist()
        val m2 = TouchModel(s, ROWS)
        val same = ('a'..'z').all { abs(m.offsetXDp(it) - m2.offsetXDp(it)) < 0.02f && abs(m.offsetYDp(it) - m2.offsetYDp(it)) < 0.02f }
        check("persist then load round-trips", same)
        check("corrupt storage loads as empty", TouchModel(MemStore("garbage;;:::\nx\ny"), ROWS).offsetXDp('a') == 0f)
        val old = TouchModel(MemStore("a:12.0,-9.0;b:1.0,1.0"), ROWS)
        check("the old tap-model format is ignored, not misread", old.offsetXDp('a') == 0f)
    }
    run {
        val m = TouchModel(MemStore(), ROWS)
        repeat(2000) { m.observe('e', 6f, 6f) }
        val before = m.offsetXDp('e')
        repeat(800) { m.observe('e', -3f, -3f) }
        val after = m.offsetXDp('e')
        check("a changed habit wins within 800 taps", before == 4f && after < 0f, "from $before to $after")
    }
    run {
        val m = TouchModel(MemStore(), ROWS)
        m.observe('k', Float.NaN, 1f)
        m.observe('k', 1f, Float.POSITIVE_INFINITY)
        check("a non-finite sample cannot poison the model", m.offsetXDp('k') == 0f && m.liveTapCount() == 0)
    }
    run {
        val letters = TouchCheck.SENTENCES.joinToString("").filter { it in 'a'..'z' }
        val m = TouchModel(MemStore(), ROWS)
        m.setCalibration(letters.map { TouchModel.Sample(it, 4f, 6f) })
        val largest = ('a'..'z').maxOf { maxOf(abs(m.offsetXDp(it)), abs(m.offsetYDp(it))) }
        println(String.format(Locale.US, "  the real sentences, every tap measuring (4,6): largest offset %.2f dp", largest))
    }
}

fun hitMapTests() {
    println("--- HitMap on the device geometry ---")
    val rows = DeviceLayout.hitRows()
    val map = HitMap(rows, 0f, W)
    val keys = map.keys
    var inside = 0
    var mismatched = 0
    var nulls = 0
    val h = DeviceLayout.HEIGHT_PX
    var y = 0
    while (y < h) {
        var x = 0
        while (x < W.toInt()) {
            val k = map.keyAt(x.toFloat(), y.toFloat())
            if (k < 0) nulls++
            for ((i, key) in keys.withIndex()) {
                if (x >= key.left && x < key.right && y >= key.top && y < key.bottom) {
                    inside++
                    if (i != k) mismatched++
                }
            }
            x++
        }
        y++
    }
    check("every pixel of the keyboard belongs to a key", nulls == 0, "$nulls unowned")
    check("every pixel inside a drawn key belongs to that key", mismatched == 0, "$inside pixels inside faces, $mismatched mismatched")
    val a = rows[1][0]
    val l = rows[1].last()
    var margin = true
    var yy = a.top
    while (yy < a.bottom) {
        for (x in 0 until a.left.toInt()) if (keys[map.keyAt(x.toFloat(), yy)].symbol != 'a') margin = false
        for (x in l.right.toInt() + 1 until W.toInt()) if (keys[map.keyAt(x.toFloat(), yy)].symbol != 'l') margin = false
        yy += 1f
    }
    check("home row margins belong to a and l, never q, p, SHIFT or DELETE", margin)
    val one = HitMap(rows, 200f, W)
    check("the one-handed gutter has no key", one.keyAt(100f, 300f) == -1 && one.keyAt(250f, 300f) >= 0)
}

fun resolverTests() {
    println("--- TapResolver properties ---")
    val rows = DeviceLayout.hitRows()
    val map = HitMap(rows, 0f, W)
    val res = TapResolver(map, D)
    val keys = map.keys
    val rng = Random(11)
    val h = DeviceLayout.HEIGHT_PX.toFloat()
    // Adversarial prior: all the mass on one random symbol, changed every tap.
    val cur = intArrayOf(0)
    val prior = TapResolver.Prior { out -> out.fill(0f); out[cur[0]] = 1f; true }
    var anchorViolations = 0
    var functionalViolations = 0
    var total = 0
    var deepestSideways = 0f
    var deepestVertical = 0f
    repeat(4_000_000) {
        val x = rng.nextFloat() * W
        val y = rng.nextFloat() * h
        cur[0] = rng.nextInt(27)
        val k0 = map.keyAt(x, y)
        val r = res.resolve(x, y, prior)
        total++
        val k = keys[k0]
        if (!k.scorable) {
            if (r != k0) functionalViolations++
        } else {
            val inAnchor = abs(x - k.cx) <= k.width / 4f && abs(y - k.cy) <= k.height * 0.3f
            if (inAnchor && r != k0) anchorViolations++
            if (r != k0 && k.symbol != ' ' && x >= k.left && x < k.right && y >= k.top && y < k.bottom) {
                val sameRow = keys[r].top == k.top
                if (sameRow) {
                    val depth = minOf(x - k.left, k.right - x)
                    if (depth > deepestSideways) deepestSideways = depth
                } else {
                    val depth = minOf(y - k.top, k.bottom - y)
                    if (depth > deepestVertical) deepestVertical = depth
                }
            }
        }
    }
    check("under an adversarial prior the anchor is never overridden", anchorViolations == 0, "$total random taps")
    check("under an adversarial prior a functional key is never overridden", functionalViolations == 0)
    val s = keys.first { it.symbol == 's' }
    check("a same-row neighbour reaches at most half way to the centre of a letter face", deepestSideways <= s.width / 4f + 0.5f,
        "deepest steal ${"%.1f".format(Locale.US, deepestSideways / D)}dp into a ${"%.1f".format(Locale.US, s.width / D)}dp face")
    check("a key in another row reaches at most 20% of the way into a letter face", deepestVertical <= s.height * 0.20f,
        "deepest steal ${"%.1f".format(Locale.US, deepestVertical / D)}dp into a ${"%.1f".format(Locale.US, s.height / D)}dp face")
    var same = true
    repeat(1_000_000) {
        val x = rng.nextFloat() * W
        val y = rng.nextFloat() * h
        if (res.resolve(x, y, null) != map.keyAt(x, y)) same = false
    }
    check("no prior and no offsets behaves exactly like the HitMap", same)
    val noOpinion = TapResolver.Prior { false }
    var same2 = true
    repeat(1_000_000) {
        val x = rng.nextFloat() * W
        val y = rng.nextFloat() * h
        if (res.resolve(x, y, noOpinion) != map.keyAt(x, y)) same2 = false
    }
    check("a prior with no opinion behaves exactly like the HitMap", same2)
}

fun trieTests() {
    println("--- Trie subtree sums ---")
    val t = Trie()
    val rng = Random(3)
    val truth = HashMap<String, Long>()
    repeat(60000) {
        val w = buildString { repeat(1 + rng.nextInt(5)) { append('a' + rng.nextInt(6)) } }
        if (rng.nextInt(4) == 0) {
            t.remove(w)
            truth.remove(w)
        } else {
            val f = 1L + rng.nextInt(1000)
            t.insert(w, f)
            truth[w] = maxOf(truth[w] ?: 0L, f)
        }
    }
    var bad = 0
    val prefixes = HashSet<String>()
    for (w in truth.keys) for (i in 0..w.length) prefixes += w.substring(0, i)
    for (p in prefixes) {
        val want = truth.entries.filter { it.key.startsWith(p) }.sumOf { it.value }
        if (t.node(p)?.subtree != want) bad++
    }
    check("subtree sums match a brute-force recount after random inserts and removes", bad == 0, "${prefixes.size} prefixes")
    val out = FloatArray(27)
    val tt = Trie()
    tt.insert("the", 90); tt.insert("then", 5); tt.insert("them", 5)
    check("prior from a known prefix", NextCharPrior.fill(tt, "the", out) && abs(out[26] - 0.9f) < 1e-4f && abs(out['n' - 'a'] - 0.05f) < 1e-4f && abs(out.sum() - 1f) < 1e-4f)
    check("unknown prefix gives no opinion", !NextCharPrior.fill(tt, "qq", out))
}

fun migrationTests() {
    println("--- PrefsMigrations ---")
    class S(val m: MutableMap<String, Any?>) : PrefsMigrations.Store {
        var commits = 0
        override fun snapshot(): Map<String, Any?> = HashMap(m)
        override fun commit(edit: PrefsMigrations.Edit): Boolean {
            commits++
            edit.remove.forEach { m.remove(it) }
            m.putAll(edit.putInts)
            m.putAll(edit.putBooleans)
            return true
        }
    }
    val notice = PrefsMigrations.KEY_KEYS_RESET_NOTICE
    val polluted = mutableMapOf<String, Any?>(
        "key_layout_fit" to "q:1.1,9.1;h:5.7,4.8", "tap_model_data" to "a:1.0,2.0",
        "key_offset_x" to 5f, "key_offset_y" to -3f, "theme" to "DARK", "key_spacing" to 1f,
        "nav_strip_mode" to 3, "swipe_bias_data" to "the\t-5", "llm_suggestions" to true,
        "llm_suggestions_reset_v2" to true
    )
    val s = S(polluted)
    check("first run does the work", PrefsMigrations.run(s))
    check("retired keys are gone", PrefsMigrations.RETIRED.none { it in polluted })
    check("other settings are untouched", polluted["theme"] == "DARK" && polluted["key_spacing"] == 1f)
    check("the stamp is written", polluted[PrefsMigrations.KEY_GEOMETRY_SCHEMA] == 2)
    check("nav strip mode 3 becomes 0", polluted["nav_strip_mode"] == 0)
    check("glide bias is reset", "swipe_bias_data" !in polluted)
    check("the llm flag goes, the user's llm choice stays", "llm_suggestions_reset_v2" !in polluted && polluted["llm_suggestions"] == true)
    check("a user with a baked fit gets the notice", polluted[notice] == true)
    check("second run is a no-op", !PrefsMigrations.run(s) && s.commits == 1)
    val clean = S(mutableMapOf("tap_model_data" to "a:1.0,2.0"))
    PrefsMigrations.run(clean)
    check("a user whose board never moved gets no notice", notice !in clean.m)
    val restored = mapOf<String, Any?>("key_layout_fit" to "q:9,9", "theme" to "LIGHT")
        .filterKeys { !PrefsMigrations.staysOnDevice(it) }
    check("an old backup cannot bring the fit back", "key_layout_fit" !in restored && restored["theme"] == "LIGHT")
    val again = S(mutableMapOf(PrefsMigrations.KEY_GEOMETRY_SCHEMA to 2, "nav_strip_mode" to 3, "swipe_bias_data" to "the\t-5"))
    PrefsMigrations.run(again, afterRestore = true)
    check("a re-run after restore heals nav strip and unstamped bias", again.m["nav_strip_mode"] == 0 && "swipe_bias_data" !in again.m)
}

fun touchCheckTests() {
    println("--- TouchCheck ---")
    val (m1, d1) = TouchCheck.align("thw quick", "the quick")
    check("substitution lines up with the letter asked for", d1 == 1 && m1[2] == 2 && m1[8] == 8)
    val (m2, d2) = TouchCheck.align("thee", "the")
    check("an inserted tap lines up with nothing", d2 == 1 && m2.count { it == -1 } == 1)
    val (m3, d3) = TouchCheck.align("te", "the")
    check("a skipped letter does not shift what follows", d3 == 1 && m3[0] == 0 && m3[1] == 2)
    val target = TouchCheck.SENTENCES.joinToString(" ")
    val rows = DeviceLayout.hitRows()
    val centre = HashMap<Char, Pair<Float, Float>>()
    for (row in rows) for (k in row) if (k.scorable) centre[k.symbol] = k.cx to k.cy
    val counts = target.filter { it in 'a'..'z' }.groupingBy { it }.eachCount()
    println("  sentences: ${target.count { it in 'a'..'z' }} letters, fewest per letter ${counts.values.min()}, row totals " +
        ROWS.map { r -> r.sumOf { counts[it] ?: 0 } })
    val rng = Random(9)
    val errsY = ArrayList<Double>()
    val errsX = ArrayList<Double>()
    val ses = ArrayList<Double>()
    val trueY = 4f
    val trueX = -3f
    repeat(400) {
        val taps = target.map { c ->
            val p = centre[c]!!
            TouchCheck.Tap(c, p.first + (trueX + rng.nextGaussian().toFloat() * 9f) * D, p.second + (trueY + rng.nextGaussian().toFloat() * 9f) * D)
        }
        val r = TouchCheck.analyse(taps, target, D) { centre[it] }
        val b = TouchCheck.rowBias(r.samples, ROWS)
        for (rb in b) {
            errsY += abs(rb.meanYDp - trueY).toDouble()
            errsX += abs(rb.meanXDp - trueX).toDouble()
            ses += rb.seYDp.toDouble()
        }
    }
    println(String.format(Locale.US, "  sigma 9: row bias recovered to within %.2f dp in x, %.2f dp in y on average; reported standard error %.2f dp",
        errsX.average(), errsY.average(), ses.average()))
    check("the check can state a row's bias to about a dp", errsY.average() < 1.5)
}

fun marginTests() {
    println("--- Margins: the five-step findKey as shipped against the HitMap, no fit, no tap model (simulated) ---")
    fun reproduces(name: String, s: MarginSim.Scenario, shipped: Double, map: Double, delete: Double, shift: Double) {
        check(
            "$name: weighted error $shipped% to $map%",
            abs(s.asShipped.weightedPct - shipped) < 0.03 && abs(s.map.weightedPct - map) < 0.03,
            String.format(Locale.US, "%.3f%% to %.3f%%", s.asShipped.weightedPct, s.map.weightedPct)
        )
        check(
            "$name: as shipped, aimed at l fires DELETE $delete%, aimed at a toggles SHIFT $shift%",
            abs(s.asShipped.deleteFromLPct - delete) < 0.1 && abs(s.asShipped.shiftFromAPct - shift) < 0.1,
            String.format(Locale.US, "%.2f%% and %.2f%%", s.asShipped.deleteFromLPct, s.asShipped.shiftFromAPct)
        )
    }
    val moderate = MarginSim.scenario(3f, 4f)
    val zero = MarginSim.scenario(0f, 0f)
    val outward = MarginSim.scenario(5f, 4f)
    MarginSim.print("the reference users, bias +-3,+4", moderate)
    MarginSim.print("the reference users, zero bias", zero)
    MarginSim.print("the reference users, bias +-5,+4", outward)
    // Recorded figures; the Gboard-parity retune (shorter keys, tighter pitch)
    // moved every one of them, so they were re-recorded from a run on the new
    // geometry rather than left stale.
    reproduces("bias +-3,+4", moderate, 4.49, 4.23, 2.48, 2.88)
    reproduces("zero bias", zero, 3.25, 3.13, 0.77, 0.91)
    reproduces("bias +-5,+4", outward, 5.96, 5.52, 3.43, 3.97)
    val perKey = mapOf(
        'a' to (4.5 to 1.9), 'q' to (3.6 to 1.7), 'z' to (5.4 to 5.4),
        'l' to (4.1 to 2.0), 'p' to (3.8 to 1.9), 'm' to (5.4 to 5.4)
    )
    for ((c, want) in perKey) {
        check(
            "bias +-3,+4: key $c ${want.first}% to ${want.second}%",
            abs(moderate.asShipped.keyPct(c) - want.first) < 0.1 && abs(moderate.map.keyPct(c) - want.second) < 0.1,
            String.format(Locale.US, "%.2f%% to %.2f%%", moderate.asShipped.keyPct(c), moderate.map.keyPct(c))
        )
    }
    // Bound moved with the Gboard-parity retune: the centre-to-boundary distance
    // shrank from 31.1dp to 25.1dp, so a biased tap crosses into the neighbour's
    // cell more often no matter how the hit map is built. What remains is still
    // only the taps that truly land in those cells (the shipped findKey spills
    // 2.48% and 2.88% on this same scenario).
    check(
        "with the HitMap a margin tap no longer fires DELETE or SHIFT",
        moderate.map.deleteFromLPct < 1.2 && moderate.map.shiftFromAPct < 1.2,
        String.format(
            Locale.US,
            "%.2f%% and %.2f%% remain, the taps that truly land in those cells",
            moderate.map.deleteFromLPct, moderate.map.shiftFromAPct
        )
    )
    // Twenty users are one draw. A much larger group shows what the figure is on average.
    val many = MarginSim.scenario(3f, 4f, MarginSim.freshUsers(400, 77L), 2_000)
    MarginSim.print("400 fresh users, bias +-3,+4", many)
    check(
        "on fresh users the map still beats the shipped findKey, on screen too",
        many.map.weighted < many.asShipped.weighted && many.mapOnScreen.weighted < many.asShippedOnScreen.weighted
    )
}

// ---- Monte Carlo against the app's own dictionary -------------------------------------

class Arm(val name: String) { var err = 0L; var ok = 0L; var prefix = StringBuilder(); var wordOk = true }

fun monteCarlo(dictPath: String) {
    println("--- Monte Carlo: HitMap, learned offsets and language prior on the real dictionary (simulated) ---")
    val entries = ArrayList<Pair<String, Long>>()
    File(dictPath).forEachLine { line ->
        val p = line.split('\t')
        if (p.size == 2) {
            val w = p[0].lowercase(Locale.US)
            val c = p[1].toLongOrNull()
            if (c != null && w.isNotEmpty() && w.all { it in 'a'..'z' }) entries += w to c
        }
    }
    entries.sortByDescending { it.second }
    val top = entries.take(60000)
    val trie = Trie()
    for ((w, c) in top) trie.insert(w, c)
    println("dictionary words used: ${top.size}")
    val rows = DeviceLayout.hitRows()
    val map = HitMap(rows, 0f, W)
    val keys = map.keys
    val viewH = DeviceLayout.HEIGHT_PX.toFloat()
    val index = HashMap<Char, Int>()
    for ((i, k) in keys.withIndex()) if (k.scorable) index[k.symbol] = i

    // Weighted word sampling.
    val cum = DoubleArray(top.size)
    var acc = 0.0
    for ((i, e) in top.withIndex()) { acc += e.second.toDouble().pow(0.85); cum[i] = acc }
    fun sampleUsage(rng: Random): String {
        val r = rng.nextDouble() * acc
        var lo = 0; var hi = cum.size - 1
        while (lo < hi) { val m = (lo + hi) ushr 1; if (cum[m] < r) lo = m + 1 else hi = m }
        return top[lo].first
    }
    fun sampleRare(rng: Random): String = top[20000 + rng.nextInt(top.size - 20000)].first
    fun sampleRandom(rng: Random): String = buildString { repeat(3 + rng.nextInt(6)) { append('a' + rng.nextInt(26)) } }

    fun scenario(
        title: String,
        sigma: Float,
        biasX: Float,
        biasY: Float,
        learnTaps: Int,
        mode: String,
        wordsPerUser: Int,
        seed: Long,
        expected: List<Double>
    ) {
        val rng = Random(seed)
        val arms = listOf(Arm("map"), Arm("offsets"), Arm("language"), Arm("both"))
        var keystrokes = 0L
        var words = 0L
        val users = 8
        var learnedYErr = 0.0
        var learnedYSum = 0.0
        var trueYSum = 0.0
        var learnedCount = 0
        repeat(users) {
            val biasOf = HashMap<Char, Pair<Float, Float>>()
            for ((c, i) in index) {
                val side = if (keys[i].cx < W / 2) -1f else 1f
                biasOf[c] = (side * biasX + rng.nextGaussian().toFloat() * 1.5f) to (biasY + rng.nextGaussian().toFloat() * 1.5f)
            }
            fun tap(c: Char): Pair<Float, Float> {
                val k = keys[index[c]!!]
                val b = biasOf[c]!!
                // A real touch is always inside the view, so clamp to it.
                val x = k.cx + (b.first + rng.nextGaussian().toFloat() * sigma) * D
                val y = k.cy + (b.second + rng.nextGaussian().toFloat() * sigma) * D
                return x.coerceIn(0f, W - 1f) to y.coerceIn(0f, viewH - 1f)
            }
            // Learning: only words typed without a slip feed the model, measured at DOWN.
            val model = TouchModel(MemStore(), ROWS)
            var learned = 0
            while (learned < learnTaps) {
                val w = sampleUsage(rng)
                val pts = w.map { tap(it) }
                val allRight = w.indices.all { keys[map.keyAt(pts[it].first, pts[it].second)].symbol == w[it] }
                learned += w.length
                if (allRight) for (i in w.indices) {
                    val k = keys[index[w[i]]!!]
                    model.observe(w[i], (pts[i].first - k.cx) / D, (pts[i].second - k.cy) / D)
                }
            }
            for (c in 'a'..'z') {
                learnedYSum += model.offsetYDp(c)
                trueYSum += biasOf[c]!!.second
                learnedYErr += abs(model.offsetYDp(c) - biasOf[c]!!.second)
                learnedCount++
            }
            val plain = TapResolver(map, D)
            val learnedRes = TapResolver(map, D).also { it.setOffsets(model) }
            val prefixHolder = arrayOf("")
            val prior = TapResolver.Prior { out -> NextCharPrior.fill(trie, prefixHolder[0], out) }
            repeat(wordsPerUser) {
                val w = when (mode) { "usage" -> sampleUsage(rng); "rare" -> sampleRare(rng); else -> sampleRandom(rng) }
                for (a in arms) { a.prefix.setLength(0); a.wordOk = true }
                for (ch in "$w ") {
                    val (x, y) = tap(ch)
                    val want = index[ch]!!
                    keystrokes++
                    for (a in arms) {
                        prefixHolder[0] = a.prefix.toString()
                        val r = when (a.name) {
                            "map" -> map.keyAt(x, y)
                            "offsets" -> learnedRes.resolve(x, y, null)
                            "language" -> plain.resolve(x, y, prior)
                            else -> learnedRes.resolve(x, y, prior)
                        }
                        if (r != want) { a.err++; a.wordOk = false }
                        if (r >= 0 && keys[r].symbol in 'a'..'z') a.prefix.append(keys[r].symbol)
                    }
                }
                for (a in arms) if (a.wordOk) a.ok++
                words++
            }
        }
        val base = arms[0].err.toDouble()
        println(title)
        for (a in arms) {
            println(String.format(Locale.US, "  %-9s key error %5.2f%%  clean words %5.1f%%  vs the map %+6.1f%%",
                a.name, 100.0 * a.err / keystrokes, 100.0 * a.ok / words, 100.0 * (a.err / base - 1.0)))
        }
        println(String.format(Locale.US, "  learned y offset mean %.2f dp vs true %.2f dp, mean abs error %.2f dp",
            learnedYSum / learnedCount, trueYSum / learnedCount, learnedYErr / learnedCount))
        val got = arms.map { 100.0 * it.err / keystrokes }
        check(
            "$title: key error stays at the design's $expected",
            got.indices.all { abs(got[it] - expected[it]) < 0.015 },
            got.joinToString(" ") { String.format(Locale.US, "%.2f", it) }
        )
    }

    // Seeded, so these are exact until the code changes: map, offsets, language, both.
    scenario("sigma 9, bias 3/4, 3000 learning taps, usage words", 9f, 3f, 4f, 3000, "usage", 750, 1, listOf(3.58, 2.85, 1.59, 1.25))
    scenario("sigma 9, bias 3/4, 3000 learning taps, rare words", 9f, 3f, 4f, 3000, "rare", 300, 2, listOf(3.53, 2.81, 1.96, 1.48))
    scenario("sigma 9, bias 3/4, 3000 learning taps, random strings", 9f, 3f, 4f, 3000, "random", 300, 3, listOf(3.34, 2.90, 3.72, 3.28))
    scenario("sigma 6, bias 3/4, 3000 learning taps, usage words", 6f, 3f, 4f, 3000, "usage", 750, 4, listOf(0.31, 0.11, 0.12, 0.05))
    scenario("sigma 12, bias 3/4, 3000 learning taps, usage words", 12f, 3f, 4f, 3000, "usage", 750, 5, listOf(10.09, 9.23, 5.96, 5.60))
    scenario("sigma 9, bias 3/4, 300 learning taps, usage words", 9f, 3f, 4f, 300, "usage", 750, 6, listOf(3.22, 2.87, 1.52, 1.29))
    scenario("sigma 9, zero bias, 3000 learning taps, usage words", 9f, 0f, 0f, 3000, "usage", 750, 7, listOf(2.76, 2.68, 1.15, 1.08))
    scenario("sigma 9, zero bias, 3000 learning taps, random strings", 9f, 0f, 0f, 3000, "random", 300, 8, listOf(2.83, 2.74, 3.19, 2.96))
    scenario("sigma 9, large bias 5/8, 3000 learning taps, usage words", 9f, 5f, 8f, 3000, "usage", 750, 9, listOf(4.78, 2.95, 2.40, 1.48))
}

fun perf() {
    println("--- Rough cost per resolve on this desktop JVM ---")
    val map = HitMap(DeviceLayout.hitRows(), 0f, W)
    val res = TapResolver(map, D)
    val rng = Random(1)
    val h = DeviceLayout.HEIGHT_PX.toFloat()
    val xs = FloatArray(1 shl 20) { rng.nextFloat() * W }
    val ys = FloatArray(1 shl 20) { rng.nextFloat() * h }
    val prior = TapResolver.Prior { out -> out.fill(1f / 27f); true }
    var sink = 0
    repeat(3) { for (i in xs.indices) sink += res.resolve(xs[i], ys[i], prior) }
    val t0 = System.nanoTime()
    for (i in xs.indices) sink += res.resolve(xs[i], ys[i], prior)
    val ns = (System.nanoTime() - t0) / xs.size
    println("$ns ns per resolve (sink $sink)")
}

fun main(args: Array<String>) {
    layoutTests()
    touchModelTests()
    hitMapTests()
    resolverTests()
    trieTests()
    migrationTests()
    touchCheckTests()
    perf()
    marginTests()
    if (args.isNotEmpty()) monteCarlo(args[0])
    println(if (failures == 0) "ALL PROPERTY CHECKS PASSED" else "$failures PROPERTY CHECKS FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}
