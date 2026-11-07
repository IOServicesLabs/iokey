package com.localboard.keyboard.engine

import kotlin.math.sqrt

/** Pure core of the "Check my touch accuracy" screen. Nothing here touches a key. */
object TouchCheck {

    /**
     * 180 letters with every letter at least twice, spread across the rows about the
     * way real text is. That is enough to say something about a row and not enough
     * to say anything about a single key, which is why [rowBias] stops at rows.
     */
    val SENTENCES: List<String> = listOf(
        "the five boxing wizards jump quickly at dawn",
        "we expect a jazz quartet to play for the village this week",
        "please check the schedule and call me back when you can",
        "she fixed the broken zipper on her old jacket over the weekend"
    )

    class Tap(val char: Char, val xPx: Float, val yPx: Float)

    class RowBias(
        val row: Int,
        val n: Int,
        val meanXDp: Float,
        val meanYDp: Float,
        val seXDp: Float,
        val seYDp: Float
    )

    /** The sentence asked for [wanted] and the tap typed [typed], [count] times. */
    class Confusion(val wanted: Char, val typed: Char, val count: Int)

    class Result(
        val targetLength: Int,
        val errors: Int,
        val samples: List<TouchModel.Sample>,
        /** Most frequent first. */
        val confusions: List<Confusion> = emptyList()
    ) {
        val accuracy: Float
            get() = if (targetLength == 0) 0f
            else (targetLength - errors).coerceAtLeast(0).toFloat() / targetLength
    }

    /** Levenshtein with backtrace: for each typed index, the target index it lines up
     *  with, or -1 for a tap that lines up with nothing. Also returns the distance. */
    fun align(typed: String, target: String): Pair<IntArray, Int> {
        val n = typed.length
        val m = target.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val sub = if (typed[i - 1] == target[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j - 1] + sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
            }
        }
        val out = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val sub = if (typed[i - 1] == target[j - 1]) 0 else 1
            when {
                d[i][j] == d[i - 1][j - 1] + sub -> { out[i - 1] = j - 1; i--; j-- }
                d[i][j] == d[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return out to d[n][m]
    }

    /**
     * A tap is measured against the key the sentence asked for, even when it typed a
     * different letter: that is exactly the information a mistyped tap carries. Taps
     * further than [maxDp] from that key are slips of attention and are dropped.
     *
     * Case is folded first, so a capital from auto-caps is not reported as a mistake.
     * Separators carry NaN coordinates: the distance test below fails for them, so a
     * space lined up with a letter is never measured.
     */
    fun analyse(
        taps: List<Tap>,
        target: String,
        density: Float,
        maxDp: Float = 30f,
        centreOf: (Char) -> Pair<Float, Float>?
    ): Result {
        val typed = String(CharArray(taps.size) { taps[it].char.lowercaseChar() })
        val wanted = target.lowercase()
        val (map, distance) = align(typed, wanted)
        val samples = ArrayList<TouchModel.Sample>(taps.size)
        val swaps = HashMap<Pair<Char, Char>, Int>()
        for (i in taps.indices) {
            val j = map[i]
            if (j < 0) continue
            val want = wanted[j]
            if (want !in 'a'..'z') continue
            val c = centreOf(want) ?: continue
            val dx = (taps[i].xPx - c.first) / density
            val dy = (taps[i].yPx - c.second) / density
            if (dx * dx + dy * dy <= maxDp * maxDp) {
                samples += TouchModel.Sample(want, dx, dy)
                val got = typed[i]
                if (got != want && got in 'a'..'z') {
                    val pair = want to got
                    swaps[pair] = (swaps[pair] ?: 0) + 1
                }
            }
        }
        val confusions = swaps.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Char, Char>, Int>> { it.value }
                .thenBy { it.key.first }
                .thenBy { it.key.second })
            .map { Confusion(it.key.first, it.key.second, it.value) }
        return Result(target.length, distance, samples, confusions)
    }

    /** What the check can honestly claim. A row has enough taps; a single key does not. */
    fun rowBias(samples: List<TouchModel.Sample>, rows: List<String>): List<RowBias> =
        rows.mapIndexed { r, row ->
            val s = samples.filter { it.letter in row }
            val n = s.size
            if (n < 2) RowBias(r, n, 0f, 0f, 0f, 0f)
            else {
                val mx = s.sumOf { it.dxDp.toDouble() } / n
                val my = s.sumOf { it.dyDp.toDouble() } / n
                val vx = s.sumOf { (it.dxDp - mx) * (it.dxDp - mx) } / (n - 1)
                val vy = s.sumOf { (it.dyDp - my) * (it.dyDp - my) } / (n - 1)
                RowBias(r, n, mx.toFloat(), my.toFloat(), sqrt(vx / n).toFloat(), sqrt(vy / n).toFloat())
            }
        }
}
