package com.localboard.keyboard.engine

import android.graphics.PointF
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Glide-typing decoder, geometry-first architecture (v3 — allocation-free).
 *
 * Scoring is unchanged from v2 (simulation-tuned weights); what changed is
 * that the hot path no longer allocates. Two things made glide typing stall
 * for about a second per word on a 114k dictionary:
 *
 *  1. `segDist` returned `Pair<Float, Float>`. Kotlin generics box primitives,
 *     so every call allocated three objects — and the chamfer loop, which
 *     called it ~360k times per decode, threw the second value away. With the
 *     live preview decoding every 120 ms that was ~9.7 MILLION allocations
 *     per gesture, on the UI thread, competing with the trail renderer.
 *     It is now a scalar function over floats: zero allocation.
 *
 *  2. The bucket table was an object graph — one WordInfo + an ArrayList +
 *     one PointF per letter + a FloatArray, ~11 objects per word, so ~1.3M
 *     objects and ~29 MB churned on every rebuild. Rebuilds fire whenever the
 *     learned-word count moves, i.e. every few words of typing. It is now a
 *     struct-of-arrays: a fixed handful of primitive arrays, no per-word
 *     objects at all.
 *
 * Decoding itself is pure reads, so the IME runs it off the main thread.
 */
class SwipeDecoder(
    private val fastEngine: FastEngine,
    private val geometry: KeyboardGeometry
) {

    // ---- dictionary table: struct of arrays, no per-word objects ----------

    private class Table(
        /** Word strings, indexed by word id. */
        val word: Array<String>,
        val freq: LongArray,
        /** Visited-letter bitmask per word. */
        val bits: IntArray,
        /** Ideal arc length of the word's key curve, in key units. */
        val iarc: FloatArray,
        /** Start index into [xy] for each word (2 floats per letter). */
        val off: IntArray,
        /** Flat x,y pairs of every word's ideal key centres, concatenated. */
        val xy: FloatArray,
        /** Flat arc-fraction of each letter, parallel to [xy] (1 per letter). */
        val fr: FloatArray,
        /** Word ids grouped by (firstLetter, lastLetter) bucket. */
        val order: IntArray,
        /** bucketStart[b]..bucketStart[b+1] indexes [order] for bucket b. */
        val bucketStart: IntArray
    ) {
        fun len(id: Int): Int = word[id].length
        fun frStart(id: Int): Int = off[id] / 2
    }

    @Volatile private var table: Table? = null
    @Volatile private var tableTrieVersion = -1
    /** The [KeyboardGeometry.layoutVersion] the live table was built against.
     *  Without this the table's ideal curves outlive the board they describe —
     *  see [ensureTable]. */
    @Volatile private var tableGeomVersion = -1
    @Volatile private var buildRunning = false
    @Volatile private var lastBuildMs = 0L

    /** True when the live table still describes the board on screen and the
     *  dictionary it was built from.
     *
     *  The geometry half is not optional. [buildTable] bakes each letter's
     *  centre divided by the BUILD-TIME average key width into the word
     *  curves, while decoding normalises the finger path by the LIVE key width
     *  and measures the per-letter fit against the LIVE centres. Let those two
     *  drift apart and the reverse-chamfer term is comparing a gesture on this
     *  keyboard against ideal curves drawn on a different one: the shape
     *  evidence compresses toward its clamps, ranking falls back onto raw word
     *  frequency, and the decoder starts confidently returning common words
     *  that have nothing to do with the path. Changing key height, key
     *  spacing, one-handed mode or running the finger-fit test all move the
     *  centres, and all of them are re-applied on every field focus. */
    private fun tableIsCurrent(): Boolean =
        tableTrieVersion == fastEngine.trieVersion &&
            tableGeomVersion == geometry.layoutVersion

    /** The table is never built on the UI thread. decode() returns instantly:
     *  the fresh table when current, the STALE one while a daemon thread
     *  builds its replacement — a word learned seconds ago can wait one cycle
     *  to join glide typing. */
    private fun ensureTable(keyW: Float): Table? {
        val current = table
        if (current != null && tableIsCurrent()) return current
        if (!fastEngine.isReady) return null
        kickBuild(keyW)
        return current
    }

    /** Pre-warm from a non-UI thread at keyboard-show, so the first glide
     *  rarely arrives before the first build. */
    fun warmUp() {
        if (!fastEngine.isReady) return
        val keyW = geometry.averageKeyWidth
        if (keyW <= 0f) return
        if (table != null && tableIsCurrent()) return
        kickBuild(keyW)
    }

    private fun kickBuild(keyW: Float) {
        // Keyed on trieVersion, not learnedWordCount: only words that reach
        // the TRIE can be glided, and promotion is far rarer than a learn, so
        // this alone removes most rebuilds. The cooldown covers the rest — a
        // stale table is fully serviceable. The first build is never skipped:
        // there is nothing to serve without it.
        //
        // A GEOMETRY change is the exception. A table built for a dictionary
        // one word out of date still decodes correctly; a table built for a
        // keyboard that is no longer on screen does not decode at all, so it
        // must not be made to wait out a cooldown meant for dictionary edits.
        val geomStale = tableGeomVersion != geometry.layoutVersion
        val now = System.currentTimeMillis()
        // Check-and-set must be one atomic step: decode() reaches here from
        // both the preview thread and the main thread, and an unsynchronized
        // pair of callers could each see buildRunning == false and launch two
        // concurrent builds of the same table.
        synchronized(this) {
            if (buildRunning) return
            if (table != null && !geomStale && now - lastBuildMs < REBUILD_COOLDOWN_MS) return
            buildRunning = true
        }
        // Read the version BEFORE the build, not after: a layout that changes
        // while the daemon is walking the dictionary must leave the resulting
        // table marked stale so the next decode rebuilds it, rather than
        // stamping it with a version whose centres it never saw.
        val builtForGeom = geometry.layoutVersion
        val builtForTrie = fastEngine.trieVersion
        kotlin.concurrent.thread(isDaemon = true, name = "swipe-buckets") {
            try {
                val built = buildTable(keyW)
                synchronized(this@SwipeDecoder) {
                    table = built
                    tableTrieVersion = builtForTrie
                    tableGeomVersion = builtForGeom
                    lastBuildMs = System.currentTimeMillis()
                }
            } catch (_: Throwable) {
                // A failed build leaves the old table live; retry next time.
            } finally {
                buildRunning = false
            }
        }
    }

    private fun buildTable(keyW: Float): Table {
        val words = fastEngine.allWords()

        // Pass 1: filter to swipeable words and size the flat arrays exactly,
        // so nothing has to grow (growth is what made this allocate).
        // Parallel arrays, not Pair<String, Long>: one boxed Pair per word
        // was ~114k throwaway objects per rebuild for data we unpack
        // immediately in pass 2.
        val keepW = ArrayList<String>(words.size)
        val keepF = LongArray(words.size)
        var keepN = 0
        var letterTotal = 0
        val cx = FloatArray(26)
        val cy = FloatArray(26)
        val have = BooleanArray(26)
        // One snapshot for all 26 letters. Read per letter, a layout published
        // partway through would mix centres from two different boards into one
        // table — and this table is cached, so that mixture would outlive the
        // moment that produced it.
        val centres = geometry.keyCenters
        for (ci in 0 until 26) {
            val kc = centres['a' + ci]
            if (kc != null) { cx[ci] = kc.x / keyW; cy[ci] = kc.y / keyW; have[ci] = true }
        }
        outer@ for ((w, f) in words) {
            if (w.length < 2 || w.length > 20) continue
            for (c in w) {
                val ci = c - 'a'
                if (ci < 0 || ci > 25 || !have[ci]) continue@outer
            }
            keepW += w
            keepF[keepN++] = f
            letterTotal += w.length
        }

        val n = keepN
        val wordArr = Array(n) { "" }
        val freq = LongArray(n)
        val bits = IntArray(n)
        val iarc = FloatArray(n)
        val off = IntArray(n)
        val xy = FloatArray(letterTotal * 2)
        val fr = FloatArray(letterTotal)
        val bucketOf = IntArray(n)
        val bucketCount = IntArray(BUCKETS + 1)

        var cursor = 0
        for (i in 0 until n) {
            val w = keepW[i]
            val f = keepF[i]
            wordArr[i] = w
            freq[i] = f
            off[i] = cursor * 2
            var b = 0
            var arc = 0f
            var px = 0f
            var py = 0f
            for (j in w.indices) {
                val ci = w[j] - 'a'
                b = b or (1 shl ci)
                val x = cx[ci]
                val y = cy[ci]
                xy[(cursor + j) * 2] = x
                xy[(cursor + j) * 2 + 1] = y
                if (j > 0) arc += sqrt((x - px) * (x - px) + (y - py) * (y - py))
                px = x; py = y
            }
            bits[i] = b
            iarc[i] = arc
            // Arc fraction of each letter along the ideal curve.
            if (arc > 0f) {
                var acc = 0f
                var qx = xy[cursor * 2]
                var qy = xy[cursor * 2 + 1]
                fr[cursor] = 0f
                for (j in 1 until w.length) {
                    val x = xy[(cursor + j) * 2]
                    val y = xy[(cursor + j) * 2 + 1]
                    acc += sqrt((x - qx) * (x - qx) + (y - qy) * (y - qy))
                    fr[cursor + j] = acc / arc
                    qx = x; qy = y
                }
            }
            val bk = (w[0] - 'a') * 32 + (w[w.length - 1] - 'a')
            bucketOf[i] = bk
            bucketCount[bk]++
            cursor += w.length
        }

        // Counting sort into bucket order.
        val bucketStart = IntArray(BUCKETS + 1)
        var run = 0
        for (b in 0 until BUCKETS) {
            bucketStart[b] = run
            run += bucketCount[b]
        }
        bucketStart[BUCKETS] = run
        val fill = bucketStart.copyOf()
        val order = IntArray(n)
        for (i in 0 until n) {
            val b = bucketOf[i]
            order[fill[b]++] = i
        }

        return Table(wordArr, freq, bits, iarc, off, xy, fr, order, bucketStart)
    }

    // ---- per-path precomputes (flat arrays, no PointF) --------------------

    private class PathInfo(raw: List<PointF>, keyW: Float, geometry: KeyboardGeometry) {
        val px: FloatArray
        val py: FloatArray
        val cum: FloatArray
        val arc: Float
        /** Uniformly resampled gesture points. */
        val ux: FloatArray
        val uy: FloatArray
        val keyBestDist = FloatArray(26) { Float.MAX_VALUE }
        /** Per letter, pairs of (arcPosition, distance) along the gesture. */
        val keyProfile: Array<FloatArray>
        /** Scratch for segDist's segment parameter, written during profile
         *  construction only. Per-PathInfo, never shared: decode() runs on
         *  the preview thread and the main thread at once, and a shared
         *  holder let one gesture's profile read another gesture's t. */
        private val segT = FloatArray(1)

        init {
            val n = raw.size
            px = FloatArray(n)
            py = FloatArray(n)
            for (i in 0 until n) {
                px[i] = raw[i].x / keyW
                py[i] = raw[i].y / keyW
            }
            cum = FloatArray(n)
            var a = 0f
            for (i in 1 until n) {
                a += sqrt(
                    (px[i] - px[i - 1]) * (px[i] - px[i - 1]) +
                        (py[i] - py[i - 1]) * (py[i] - py[i - 1])
                )
                cum[i] = a
            }
            arc = a

            ux = FloatArray(NSAMPLES)
            uy = FloatArray(NSAMPLES)
            resampleUniform(px, py, cum, a, ux, uy)

            keyProfile = Array(26) { EMPTY }
            val centres = geometry.keyCenters
            for (ci in 0 until 26) {
                val kc = centres['a' + ci] ?: continue
                val kx = kc.x / keyW
                val ky = kc.y / keyW
                val prof = FloatArray(max(0, n - 1) * 2)
                var bestD = Float.MAX_VALUE
                for (j in 1 until n) {
                    val d = segDist(kx, ky, px[j - 1], py[j - 1], px[j], py[j], segT)
                    val t = segT[0]
                    val ap = cum[j - 1] + t * (cum[j] - cum[j - 1])
                    prof[(j - 1) * 2] = ap
                    prof[(j - 1) * 2 + 1] = d
                    if (d < bestD) bestD = d
                }
                keyBestDist[ci] = bestD
                keyProfile[ci] = prof
            }
        }

        /** Closest approach to key [ci] within arc window [lo,hi]. Profiles
         *  are arc-ordered, so a binary search finds the window start and the
         *  scan stops at its end. */
        fun windowDist(ci: Int, lo: Float, hi: Float): Float {
            val prof = keyProfile[ci]
            // First pair index whose arc position is >= lo.
            var loIdx = 0
            var hiIdx = prof.size / 2
            while (loIdx < hiIdx) {
                val mid = (loIdx + hiIdx) ushr 1
                if (prof[mid * 2] < lo) loIdx = mid + 1 else hiIdx = mid
            }
            var best = Float.MAX_VALUE
            var i = loIdx * 2
            while (i < prof.size) {
                val ap = prof[i]
                if (ap > hi) break
                val d = prof[i + 1]
                if (d < best) best = d
                i += 2
            }
            return best
        }
    }

    /** Toggles, pushed in by the IME from Prefs. */
    @Volatile var doubleLetterEnabled = true
    @Volatile var learningEnabled = true

    /**
     * Per-user gesture memory: words the user explicitly picked out of the
     * alternates after this decoder put something else first (positive
     * entries), and committed glide words the user rejected outright
     * (negative entries). Glide is a geometry problem with a language
     * tie-breaker, and the tie-breaker is exactly where personal habit lives
     * — "recieve vs receive" style near-misses that only THIS user resolves
     * one way.
     *
     * Kept small and bounded ([BIAS_MAX_ENTRIES]); persisted by the IME
     * through Prefs.
     */
    private val gestureBias = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Bucket key + wall-clock of the most recent decode, written on
     *  whatever thread decode() last ran on. [noteRejectedCommit] reads
     *  these to tell a rejection that belongs to a just-shown gesture apart
     *  from a stale or duplicate call. */
    @Volatile private var lastGestureSig = -1
    @Volatile private var lastGestureAtMs = 0L

    fun noteChosenAlternate(word: String) {
        if (!learningEnabled) return
        val w = word.lowercase()
        if (w.length < 2) return
        addBias(w, 1)
    }

    /**
     * Negative counterpart to [noteChosenAlternate]: the word this decoder
     * committed at finger-up was REJECTED (e.g. immediately deleted), so its
     * standing bias drops one step, clamped at -[BIAS_CAP]. Applies only
     * when a decode ran within the last [REJECT_WINDOW_MS]; without a recent
     * gesture signature the rejection can't be tied to any decode and is a
     * no-op. Persistence is unchanged — the IME snapshots via
     * [biasSnapshot] exactly as it does for positive bias.
     */
    fun noteRejectedCommit(word: String) {
        if (!learningEnabled) return
        val w = word.lowercase()
        if (w.length < 2) return
        if (lastGestureSig < 0) return
        if (System.currentTimeMillis() - lastGestureAtMs > REJECT_WINDOW_MS) return
        addBias(w, -1)
    }

    /** Shifts [w]'s bias by [delta], clamped to ±[BIAS_CAP]; a net of zero
     *  removes the entry. Inserting a NEW word into a full map evicts the
     *  lowest-count entry, so a burst of corrections can't grow the map
     *  without bound. Racing inserts can overshoot the bound by a handful
     *  of entries — harmless, and corrected on the next insert. */
    private fun addBias(w: String, delta: Int) {
        if (!gestureBias.containsKey(w) && gestureBias.size >= BIAS_MAX_ENTRIES) {
            var victim: String? = null
            var victimV = Int.MAX_VALUE
            for ((k, v) in gestureBias) {
                if (v < victimV) { victimV = v; victim = k }
            }
            if (victim != null) gestureBias.remove(victim, victimV)
        }
        // merge deletes the key when the remap returns null: a bias that
        // nets to zero is no bias at all.
        gestureBias.merge(w, delta) { a, b ->
            val n = max(-BIAS_CAP, min(BIAS_CAP, a + b))
            if (n == 0) null else n
        }
    }

    fun biasSnapshot(): Map<String, Int> = HashMap(gestureBias)

    fun restoreBias(m: Map<String, Int>) {
        gestureBias.clear()
        for ((k, v) in m) {
            if (v == 0) continue
            if (gestureBias.size >= BIAS_MAX_ENTRIES) break
            gestureBias[k] = max(-BIAS_CAP, min(BIAS_CAP, v))
        }
    }

    /** Nearest start/end letters of the gesture, packed like buildTable's
     *  bucket key. Used only as a "there WAS a recent gesture" signature
     *  for [noteRejectedCommit]. */
    private fun bucketKeyOf(sx: Float, sy: Float, lx: Float, ly: Float, keyW: Float): Int {
        var c1 = 0
        var c2 = 0
        var d1 = Float.MAX_VALUE
        var d2 = Float.MAX_VALUE
        for (c in 0 until 26) {
            val ds = geometry.squaredDistanceKeyUnits('a' + c, sx, sy, keyW)
            if (ds < d1) { d1 = ds; c1 = c }
            val de = geometry.squaredDistanceKeyUnits('a' + c, lx, ly, keyW)
            if (de < d2) { d2 = de; c2 = c }
        }
        return c1 * 32 + c2
    }

    /**
     * Doubled letters are the weakest part of a pure-geometry decoder: "hello"
     * and "helo" trace almost the same curve, so the model leans entirely on
     * arc length, which is noisy. A real double-tap on a key shows up as a
     * DWELL — the finger slows or loops without leaving the key. This finds
     * those keys so the scorer can stop penalising the extra letter.
     *
     * Returns a 26-bit mask of letters the gesture dwelled on.
     */
    private fun dwellMask(pi: PathInfo, keyW: Float): Int {
        val n = pi.px.size
        if (n < 8) return 0
        val total = pi.arc
        if (total <= 0f) return 0
        // Ignore the head and tail of the gesture. The finger genuinely rests
        // on touch-down and on lift, so samples there look exactly like a
        // dwell — measured against real paths this was the only source of
        // false doubles (it fired on the last letter of every word).
        //
        // The cost is that a word whose double IS its last letter ("coffee")
        // can't be detected. That's the right trade: a spurious double turns
        // a correct word into a wrong one, while a missed double just leaves
        // the arc-length term to decide as before.
        val lo = total * DWELL_EDGE
        val hi = total * (1f - DWELL_EDGE)
        var mask = 0
        val centres = geometry.keyCenters
        for (ci in 0 until 26) {
            val kc = centres['a' + ci] ?: continue
            val kx = kc.x / keyW
            val ky = kc.y / keyW
            // Count CONSECUTIVE near-stationary samples inside the key. This
            // measures "the finger stopped" directly, rather than inferring it
            // from arc-over-run — the run inevitably includes the approach and
            // departure, whose travel swamped the pause.
            var slow = 0
            for (i in 1 until n) {
                val at = pi.cum[i]
                if (at < lo || at > hi) { slow = 0; continue }
                val dx = pi.px[i] - kx
                val dy = pi.py[i] - ky
                if (dx * dx + dy * dy > DWELL_R * DWELL_R) { slow = 0; continue }
                val sx = pi.px[i] - pi.px[i - 1]
                val sy = pi.py[i] - pi.py[i - 1]
                slow = if (sx * sx + sy * sy < DWELL_STEP * DWELL_STEP) slow + 1 else 0
                if (slow >= DWELL_SAMPLES) { mask = mask or (1 shl ci); break }
            }
        }
        return mask
    }

    /**
     * Everything one decode needs that does not depend on the candidate:
     * the path precomputes, the letters it visited, its dwells, and the
     * language context. Built once per decode, read by [scoreStage1] and
     * [scoreStage2] for every candidate.
     */
    private class Query(
        val pi: PathInfo,
        val keyW: Float,
        val sx: Float, val sy: Float, val lx: Float, val ly: Float,
        val vbits: Int,
        val dwell: Int,
        val prev1Lower: String?,
        val lm: BaseLanguageModel?,
        /** Letter-fit window half-width, in key units. Grows with the
         *  gesture: the expected arc position of the 8th letter of a long
         *  word drifts further from where the finger actually was than the
         *  2nd letter of a short one, and a fixed window charged long words
         *  a miss per drifted letter. */
        val win: Float
    ) {
        /** Scratch reused across candidates: chamfer back-distances. */
        val backs = FloatArray(NSAMPLES)
    }

    /**
     * Stage 1: the cheap, order-aware part of the score. Windowed letter
     * fit, miss count, arc-length and endpoint agreement, and the whole
     * language prior. O(word length · log path) per candidate, so every
     * candidate in the endpoint buckets can afford it.
     *
     * Returns the partial score; the reverse-chamfer terms are added by
     * [scoreStage2] for the survivors only.
     */
    private fun scoreStage1(t: Table, id: Int, q: Query): Double {
        val w = t.word[id]
        val wl = w.length
        val pi = q.pi
        val frBase = t.frStart(id)
        var ldSum = 0f
        var missPen = 0f
        for (i in 0 until wl) {
            val exp = t.fr[frBase + i] * pi.arc
            val d = min(pi.windowDist(w[i] - 'a', exp - q.win, exp + q.win), MISS_CLAMP)
            // Graded, not a step: a letter passed at 1.0 key widths is a cut
            // corner, at 2.0 it was never visited. The old flat 4-point
            // "miss" at 0.85 made "over" lose to "offer" because the v was
            // rounded by a hair.
            if (d > MISS_R) missPen += (d - MISS_R) * MISS_SLOPE
            ldSum += min(d, 1.5f)
        }
        val ld = ldSum / wl
        val endPen = sqrt(geometry.squaredDistanceKeyUnits(w[0], q.sx, q.sy, q.keyW)) +
            sqrt(geometry.squaredDistanceKeyUnits(w[wl - 1], q.lx, q.ly, q.keyW))

        // Language prior. Context-aware when the bundled bigram model knows
        // the previous word: the candidate is then scored by how often it
        // FOLLOWS that word, backing off to a discounted unigram count.
        // Uncapped up to FREQ_CAP so a ten-nat gap ("have" 2.1M vs
        // "jeffree" 85) is worth ten points — the shape terms cannot fake
        // that much fit, which is what stops noise words in the 114k list
        // from winning on a slightly better chamfer.
        val lm = q.lm
        val p1 = q.prev1Lower
        val eff = if (lm != null && p1 != null) lm.effectiveFrequency(p1, w, t.freq[id]) else t.freq[id]
        val lf = ln(eff.toDouble() + 1.0)
        val freqBonus = FREQ_W * min(lf, FREQ_CAP) + FREQ_SOFT * max(0.0, lf - FREQ_CAP)
        val userBonus = USER_W * ln(fastEngine.userFrequencyLower(w) + 1.0)
        val ctxBonus = if (p1 != null && fastEngine.hasBigramLower(p1, w)) CTX_BONUS else 0.0
        // Words the user has previously rescued from the alternates list get
        // a standing thumb on the scale; words whose committed glide was
        // rejected get pressed down (negative bias), symmetric through the
        // same map.
        val biasBonus = if (learningEnabled) BIAS_W * (gestureBias[w] ?: 0) else 0.0

        // Doubled letters: a pure-geometry model has almost no evidence for
        // the second 'l' in "hello". If the gesture DWELLED on a letter this
        // word doubles, stop charging the arc-length penalty that would
        // otherwise favour "helo".
        var doubleCredit = 0.0
        if (q.dwell != 0) {
            var k = 1
            while (k < wl) {
                if (w[k] == w[k - 1] && (q.dwell and (1 shl (w[k] - 'a'))) != 0) doubleCredit += DOUBLE_W
                k++
            }
        }

        // Arc-length agreement is ASYMMETRIC. Fingers cut corners, so a
        // real gesture is almost always SHORTER than the word's ideal
        // centre-to-centre polyline — by a couple of key widths on a long
        // word — and that deficit is weak evidence. A gesture LONGER than
        // the ideal is a different story: the finger went somewhere this
        // word never goes, which is exactly how a shorter, commoner word
        // ("being", "period", "model") steals a long glide ("barging",
        // "pistols", "mitchell") on frequency alone.
        val arcD = pi.arc - t.iarc[id]
        val arcPen = if (arcD > 0f) ARC_W_LONG * arcD else ARC_W_SHORT * -arcD
        return -GEO_W * (1f - BACK_W - WORST_W) * ld - missPen - arcPen -
            END_W * endPen + freqBonus + userBonus + ctxBonus + biasBonus + doubleCredit
    }

    /** Stage 2: reverse chamfer — every gesture sample's distance to the
     *  word's ideal polyline, mean and worst-quartile. The hot loop, scalar
     *  and allocation-free; only survivors of stage 1 pay for it. */
    private fun scoreStage2(t: Table, id: Int, q: Query): Double {
        val wl = t.word[id].length
        val base = t.off[id]
        val pi = q.pi
        val backs = q.backs
        var bm = 0f
        for (ui in 0 until NSAMPLES) {
            val gx = pi.ux[ui]
            val gy = pi.uy[ui]
            var bd = Float.MAX_VALUE
            for (i in 1 until wl) {
                val d = segDist(
                    gx, gy,
                    t.xy[base + (i - 1) * 2], t.xy[base + (i - 1) * 2 + 1],
                    t.xy[base + i * 2], t.xy[base + i * 2 + 1]
                )
                if (d < bd) bd = d
            }
            backs[ui] = bd
            bm += bd
        }
        bm /= NSAMPLES
        backs.sort()               // ascending; worst quartile is the tail
        var bw = 0f
        val k = max(1, NSAMPLES / 4)
        for (i in 0 until k) bw += backs[NSAMPLES - 1 - i]
        bw /= k
        return -GEO_W * (BACK_W * min(bm, 1.5f) + WORST_W * min(bw, 2.0f))
    }

    private fun buildQuery(rawPath: List<PointF>, prev1: String?): Query? {
        if (!fastEngine.isReady || !geometry.hasLetters() || rawPath.size < 3) return null
        val keyW = geometry.averageKeyWidth
        if (keyW <= 0f) return null
        val pi = PathInfo(rawPath, keyW, geometry)
        // "we", "as", "see": one key width of travel is a real word. The view
        // has already applied its own arming distance before anything gets
        // here, so this only has to reject a path that is effectively a dot.
        if (pi.arc < MIN_ARC) return null
        var vbits = 0
        for (ci in 0 until 26) {
            if (pi.keyBestDist[ci] <= VISIT_R) vbits = vbits or (1 shl ci)
        }
        val dwell = if (doubleLetterEnabled) dwellMask(pi, keyW) else 0
        // Hoisted out of the candidate loop: FastEngine's case-insensitive
        // lookups lowercase per call, which was two string allocations (plus
        // the bigram key concat) per candidate, per decode. Table words pass
        // buildTable's a-z filter, so they are already lowercase and can go
        // straight to the *Lower entry points.
        val prev1Lower = prev1?.lowercase()?.takeIf { it.isNotEmpty() }
        return Query(
            pi, keyW,
            pi.px[0], pi.py[0], pi.px[pi.px.size - 1], pi.py[pi.py.size - 1],
            vbits, dwell, prev1Lower, fastEngine.languageModel,
            win = WIN_BASE + WIN_PER_ARC * pi.arc
        )
    }

    /** Returns up to [maxResults] candidates, best first. Practically never empty. */
    fun decode(rawPath: List<PointF>, maxResults: Int = 4, prev1: String? = null): List<Suggestion> {
        val keyW = geometry.averageKeyWidth
        if (keyW <= 0f) return emptyList()
        val t = ensureTable(keyW) ?: return emptyList()
        val q = buildQuery(rawPath, prev1) ?: return emptyList()
        val pi = q.pi
        val maxLen = (pi.arc / 0.55f).toInt() + 2

        // Stamp this decode's gesture so a rejected commit can be attributed
        // to it (see noteRejectedCommit).
        lastGestureSig = bucketKeyOf(q.sx, q.sy, q.lx, q.ly, keyW)
        lastGestureAtMs = System.currentTimeMillis()

        val seedR2 = SEED_R * SEED_R
        val endR2 = END_R * END_R

        // ---- stage 1: every candidate in the endpoint buckets --------------
        // Fixed-size survivor heap: ids + partial scores, no boxing.
        val s1Id = IntArray(STAGE1_KEEP)
        val s1Score = DoubleArray(STAGE1_KEEP) { Double.NEGATIVE_INFINITY }
        var s1Kept = 0
        var evaluated = 0
        val vinv = q.vbits.inv()

        bucketLoop@
        for (c1 in 0 until 26) {
            if (geometry.squaredDistanceKeyUnits('a' + c1, q.sx, q.sy, keyW) > seedR2) continue
            for (c2 in 0 until 26) {
                if (geometry.squaredDistanceKeyUnits('a' + c2, q.lx, q.ly, keyW) > endR2) continue
                val b = c1 * 32 + c2
                var oi = t.bucketStart[b]
                val oEnd = t.bucketStart[b + 1]
                while (oi < oEnd) {
                    val id = t.order[oi]
                    oi++
                    val wl = t.word[id].length
                    if (wl > maxLen) continue
                    if (wl < 4 && t.freq[id] < FRAGMENT_FLOOR) continue
                    if (kotlin.math.abs(t.iarc[id] - pi.arc) > LEN_TOL_A * pi.arc + LEN_TOL_B) continue
                    // Letters the gesture never came near. One slip is
                    // allowed on a short word and two on a longer one — the
                    // old 0/1 threw away the right answer for a quarter of
                    // ordinary-noise glides, which no amount of scoring can
                    // recover from.
                    val missing = Integer.bitCount(t.bits[id] and vinv)
                    if (missing > (if (wl <= 4) 1 else 2)) continue
                    if (++evaluated > EVAL_CAP) break@bucketLoop

                    val s = scoreStage1(t, id, q)
                    if (s > s1Score[STAGE1_KEEP - 1]) {
                        var p = STAGE1_KEEP - 1
                        while (p > 0 && s1Score[p - 1] < s) {
                            s1Score[p] = s1Score[p - 1]
                            s1Id[p] = s1Id[p - 1]
                            p--
                        }
                        s1Score[p] = s
                        s1Id[p] = id
                        if (s1Kept < STAGE1_KEEP) s1Kept++
                    }
                }
            }
        }
        if (s1Kept == 0) return emptyList()

        // ---- stage 2: chamfer on the survivors ------------------------------
        val bestId = IntArray(KEEP)
        val bestScore = DoubleArray(KEEP) { Double.NEGATIVE_INFINITY }
        var kept = 0
        for (i in 0 until s1Kept) {
            val id = s1Id[i]
            // Never volunteer a slur for a sloppy gesture. Checked here,
            // on the ~160 survivors, rather than per candidate.
            if (!OffensiveWords.allowedFor(t.word[id], fastEngine.userFrequencyLower(t.word[id]).toInt())) continue
            val s = s1Score[i] + scoreStage2(t, id, q)
            if (s > bestScore[KEEP - 1]) {
                var p = KEEP - 1
                while (p > 0 && bestScore[p - 1] < s) {
                    bestScore[p] = bestScore[p - 1]
                    bestId[p] = bestId[p - 1]
                    p--
                }
                bestScore[p] = s
                bestId[p] = id
                if (kept < KEEP) kept++
            }
        }

        val out = ArrayList<Suggestion>(min(maxResults, kept))
        var i = 0
        while (i < kept && out.size < maxResults) {
            val wtext = t.word[bestId[i]]
            if (out.none { it.text == wtext }) out += Suggestion(wtext, bestScore[i])
            i++
        }
        return out
    }

    /**
     * Diagnostic: how [word] scores against [rawPath], term by term, or why
     * it never reached scoring. For the offline simulator and bug reports;
     * never on a hot path.
     */
    fun explain(rawPath: List<PointF>, word: String, prev1: String? = null): String {
        val keyW = geometry.averageKeyWidth
        val t = ensureTable(keyW) ?: return "no table"
        val q = buildQuery(rawPath, prev1) ?: return "path rejected"
        val w = word.lowercase()
        var id = -1
        for (i in t.word.indices) if (t.word[i] == w) { id = i; break }
        if (id < 0) return "$w: not in glide table"
        val pi = q.pi
        val sb = StringBuilder()
        val wl = w.length
        val maxLen = (pi.arc / 0.55f).toInt() + 2
        val c1 = w[0] - 'a'; val c2 = w[wl - 1] - 'a'
        val seedD = sqrt(geometry.squaredDistanceKeyUnits('a' + c1, q.sx, q.sy, keyW))
        val endD = sqrt(geometry.squaredDistanceKeyUnits('a' + c2, q.lx, q.ly, keyW))
        if (seedD > SEED_R) sb.append("FILTER seed ${"%.2f".format(seedD)} > $SEED_R; ")
        if (endD > END_R) sb.append("FILTER end ${"%.2f".format(endD)} > $END_R; ")
        if (wl > maxLen) sb.append("FILTER maxLen $wl > $maxLen; ")
        if (wl < 4 && t.freq[id] < FRAGMENT_FLOOR) sb.append("FILTER fragment; ")
        if (kotlin.math.abs(t.iarc[id] - pi.arc) > LEN_TOL_A * pi.arc + LEN_TOL_B)
            sb.append("FILTER arc ${"%.1f".format(t.iarc[id])} vs ${"%.1f".format(pi.arc)}; ")
        val missing = Integer.bitCount(t.bits[id] and q.vbits.inv())
        if (missing > (if (wl <= 4) 1 else 2)) {
            val letters = StringBuilder()
            for (ci in 0 until 26) if ((t.bits[id] and q.vbits.inv() and (1 shl ci)) != 0) letters.append('a' + ci)
            sb.append("FILTER missing=$missing [$letters]; ")
        }
        val frBase = t.frStart(id)
        var misses = 0f
        var ldSum = 0f
        val fit = StringBuilder()
        for (i in 0 until wl) {
            val exp = t.fr[frBase + i] * pi.arc
            val d = min(pi.windowDist(w[i] - 'a', exp - q.win, exp + q.win), MISS_CLAMP)
            if (d > MISS_R) misses += (d - MISS_R) * MISS_SLOPE
            ldSum += min(d, 1.5f)
            fit.append(w[i]).append('=').append("%.2f".format(d)).append(' ')
        }
        val s1 = scoreStage1(t, id, q)
        val s2 = scoreStage2(t, id, q)
        val eff = if (q.lm != null && q.prev1Lower != null)
            q.lm.effectiveFrequency(q.prev1Lower, w, t.freq[id]) else t.freq[id]
        sb.append("total=${"%.2f".format(s1 + s2)} s1=${"%.2f".format(s1)} s2=${"%.2f".format(s2)} ")
        sb.append("ld=${"%.2f".format(ldSum / wl)} missPen=${"%.1f".format(misses)} arcD=${"%.1f".format(pi.arc - t.iarc[id])} ")
        sb.append("end=${"%.2f".format(seedD + endD)} lnf=${"%.1f".format(ln(eff + 1.0))} win=${"%.2f".format(q.win)} ")
        sb.append("fit[").append(fit.toString().trim()).append(']')
        return sb.toString()
    }

    companion object {
        // Simulation-tuned scoring weights (see swipe simulator, v1.4.0).
        private const val GEO_W = 7.0
        /** Arc penalty per key unit the gesture is LONGER than the word's
         *  ideal polyline (the finger visited somewhere extra). */
        private const val ARC_W_LONG = 1.5
        /** ...and per key unit it is SHORTER (corner cutting; expected). */
        private const val ARC_W_SHORT = 0.8
        private const val END_W = 3.0
        /** Points per key unit a letter's closest approach exceeds MISS_R. */
        private const val MISS_SLOPE = 6.0f
        /** Closest-approach distances are clamped here before scoring, so
         *  a letter the window never reached costs a bounded amount. */
        private const val MISS_CLAMP = 2.5f
        /** Points per nat of (context-aware) log frequency. */
        private const val FREQ_W = 1.0
        /** Above this log-frequency (≈ the top few hundred words) the prior
         *  flattens, so "the"/"to" cannot swallow every short gesture. */
        private const val FREQ_CAP = 16.0
        private const val FREQ_SOFT = 0.35
        private const val USER_W = 1.3
        private const val CTX_BONUS = 1.5
        private const val SEED_R = 1.4f
        private const val END_R = 1.4f
        private const val MISS_R = 0.85f
        private const val VISIT_R = 0.8f
        /** Letter-fit window half-width: base plus growth per key unit of
         *  gesture arc. A fixed 1.6 charged long words a miss for every
         *  letter whose expected arc position had drifted. */
        private const val WIN_BASE = 1.4f
        private const val WIN_PER_ARC = 0.06f
        private const val BACK_W = 0.45f
        private const val WORST_W = 0.25f
        private const val LEN_TOL_A = 0.65f
        private const val LEN_TOL_B = 2.5f
        /** Short words rarer than this never glide: the dictionary's two-
         *  and three-letter tail is scrabble fragments, not English. Was
         *  8000, which also cut "flu", "gym", "fab"; the language prior now
         *  handles the rest of the junk on its own. */
        private const val FRAGMENT_FLOOR = 1500L
        private const val NSAMPLES = 20
        /** Raised from 4000: measured against the real 114k dictionary this
         *  truncated ~1 gesture in 14, and scoring is now allocation-free so
         *  the extra candidates are close to free. */
        private const val EVAL_CAP = 40000
        /** Top-K kept while scoring. */
        private const val KEEP = 8
        /** Stage-1 survivors that get the full chamfer. The language prior
         *  and letter fit are both in stage 1, so the true best is
         *  essentially always in the top few dozen. */
        private const val STAGE1_KEEP = 160
        /** Score credit per doubled letter the gesture actually dwelled on. */
        private const val DOUBLE_W = 1.6
        /** Weight of the per-user "I picked this alternate" bias. */
        private const val BIAS_W = 0.9
        /** Ceiling on that bias so one word can't dominate forever. */
        private const val BIAS_CAP = 5
        /** Radius (key units) inside which samples count toward a dwell. */
        private const val DWELL_R = 0.62f
        /** Consecutive near-stationary in-key samples needed to call it a
         *  dwell. Validated against simulated glides: 4 catches deliberate
         *  pauses with no false positives on clean pass-throughs. */
        private const val DWELL_SAMPLES = 4
        /** Per-sample step (key units) below which the finger counts as
         *  stopped. A pass-through moves ~0.13 key units per sample. */
        private const val DWELL_STEP = 0.075f
        /** Fraction of the gesture at each end where dwell is ignored —
         *  touch-down and lift always look stationary. */
        private const val DWELL_EDGE = 0.12f
        /** Shortest gesture (key units) that is decoded at all. */
        private const val MIN_ARC = 0.7f
        private const val BUCKETS = 26 * 32
        private const val REBUILD_COOLDOWN_MS = 20_000L
        /** How long after a decode a rejected commit is still attributed to
         *  that decode's gesture (see noteRejectedCommit). */
        private const val REJECT_WINDOW_MS = 30_000L
        /** Hard bound on gesture-bias entries; new inserts past this evict
         *  the lowest-count entry. */
        private const val BIAS_MAX_ENTRIES = 256

        private val EMPTY = FloatArray(0)

        /** Distance from (px,py) to segment (ax,ay)-(bx,by). Allocation-free.
         *  This form discards the segment parameter, which the chamfer loop
         *  never needs. */
        @JvmStatic
        private fun segDist(
            px: Float, py: Float,
            ax: Float, ay: Float, bx: Float, by: Float
        ): Float = segDist(px, py, ax, ay, bx, by, EMPTY)

        /** As above, but also writes the clamped segment parameter t into
         *  [outT] (pass [EMPTY] to ignore it). The holder is supplied by the
         *  CALLER — one per PathInfo — never a shared static: decode() runs
         *  concurrently on the preview thread and the main thread, and a
         *  static holder let one gesture's profile read another gesture's t.
         *  A parameter instead of a Pair return is the whole point — Kotlin
         *  boxes Pair<Float,Float>, which is what made this allocate. */
        @JvmStatic
        private fun segDist(
            px: Float, py: Float,
            ax: Float, ay: Float, bx: Float, by: Float,
            outT: FloatArray
        ): Float {
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            if (l2 < 1e-12f) {
                if (outT.isNotEmpty()) outT[0] = 0f
                val ddx = px - ax
                val ddy = py - ay
                return sqrt(ddx * ddx + ddy * ddy)
            }
            var t = ((px - ax) * dx + (py - ay) * dy) / l2
            if (t < 0f) t = 0f else if (t > 1f) t = 1f
            if (outT.isNotEmpty()) outT[0] = t
            val ddx = px - (ax + t * dx)
            val ddy = py - (ay + t * dy)
            return sqrt(ddx * ddx + ddy * ddy)
        }

        /** Uniform-by-arc resampling into caller-supplied arrays. */
        private fun resampleUniform(
            px: FloatArray, py: FloatArray, cum: FloatArray, total: Float,
            ox: FloatArray, oy: FloatArray
        ) {
            val n = px.size
            if (n == 0) return
            if (n == 1 || total < 1e-6f) {
                for (i in ox.indices) { ox[i] = px[0]; oy[i] = py[0] }
                return
            }
            val spacing = total / (ox.size - 1)
            ox[0] = px[0]; oy[0] = py[0]
            var out = 1
            var mark = spacing
            var i = 1
            while (i < n && out < ox.size - 1) {
                while (i < n && cum[i] < mark) i++
                if (i >= n) break
                val segLen = cum[i] - cum[i - 1]
                val t = if (segLen <= 0f) 0f else (mark - cum[i - 1]) / segLen
                ox[out] = px[i - 1] + (px[i] - px[i - 1]) * t
                oy[out] = py[i - 1] + (py[i] - py[i - 1]) * t
                out++
                mark += spacing
            }
            while (out < ox.size) {
                ox[out] = px[n - 1]; oy[out] = py[n - 1]; out++
            }
        }
    }
}

/** squaredDistance in key units without changing KeyboardGeometry's public API. */
private fun KeyboardGeometry.squaredDistanceKeyUnits(c: Char, x: Float, y: Float, keyW: Float): Float {
    val d2 = squaredDistance(c, x * keyW, y * keyW)
    return if (d2 == Float.MAX_VALUE) d2 else d2 / (keyW * keyW)
}
