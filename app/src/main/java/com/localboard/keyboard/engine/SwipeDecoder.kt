package com.localboard.keyboard.engine

import android.graphics.PointF
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Glide-typing decoder, geometry-first architecture (v3 — allocation-free).
 *
 * The weights are simulation-tuned, on invented text, so they are a starting
 * point. The hot path does not allocate per candidate. Two things made glide
 * typing stall for about a second per word on a 114k dictionary:
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
        /** Words as they are spelled, indexed by word id. */
        val word: Array<String>,
        /** The letters a finger traces for that word: "don't" is "dont". */
        val key: Array<String>,
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
        val bucketStart: IntArray,
        /** Every word with an apostrophe, by spelling. A hundred or so entries, read
         *  only for candidates that have one. */
        val contractions: Map<String, Contraction>
    ) {
        fun len(id: Int): Int = key[id].length
        fun frStart(id: Int): Int = off[id] / 2
    }

    /**
     * What the language prior believes about a word with an apostrophe, which the
     * trie's figure alone cannot say. That figure is set by hand and runs high
     * against the dictionary ("we'll" is 4.5 times "well", "he'll" 17 times "hell"),
     * which suits completions, while the bundled rows hardly ever list a contraction,
     * so each takes the penalty for an unseen pair. On a curve shared with a plain
     * word only language decides, so the first effect wrongly beats the plain word
     * where there is no context and the second wrongly loses to it mid-sentence.
     */
    private class Contraction(
        /** A subject and its verb, "we'll", "it's": it comes where a clause can start. */
        val opensClause: Boolean,
        /** [opensClause] or a negative, "don't": never a noun. "mom's" can be one. */
        val verbForm: Boolean,
        /** The plain spelling of its curve is itself a verb ("were"), which is what a
         *  question opens with: "how were you" against "how we're doing". */
        val rivalOpensQuestion: Boolean,
        /** The figure to start from, with nothing for or against it. */
        val usualFreq: Long,
        /** ...and where what came before calls for the plain spelling: "feel ill". */
        val unlikelyFreq: Long,
        /** The plain spelling of its curve can be a complement itself: "is well", "feel ill". */
        val rivalIsComplement: Boolean
    )

    @Volatile private var table: Table? = null
    @Volatile private var tableTrieVersion = -1
    /** The [KeyboardGeometry.layoutVersion] the live table was built against.
     *  Without this the table's ideal curves outlive the board they describe —
     *  see [ensureTable]. */
    @Volatile private var tableGeomVersion = -1
    @Volatile private var buildRunning = false
    @Volatile private var lastBuildMs = 0L
    /** See [warmUpAfterFirstLoad]. */
    @Volatile private var skipCooldownOnce = false

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

    /** True when a decode can read a stroke on the board now on screen. A table a word or two behind
     *  the dictionary still can, so unlike [tableIsCurrent] this does not ask about the trie. */
    val tableServes: Boolean get() = table != null && tableGeomVersion == geometry.layoutVersion

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

    /** For the moment the learned words have loaded. A table built just ahead of them,
     *  which the first show often asks for, is missing the user's own words, and the
     *  cooldown is meant for later edits, not for that. So the next build skips it once,
     *  even if it can only start later: when a build under way finishes, or when the key
     *  width is known. */
    fun warmUpAfterFirstLoad() {
        synchronized(this) {
            if (table != null && tableIsCurrent()) return
            skipCooldownOnce = true
        }
        warmUp()
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
            if (table != null && !geomStale && !skipCooldownOnce && now - lastBuildMs < REBUILD_COOLDOWN_MS) return
            skipCooldownOnce = false
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
            // Asked for while this build was running, so this table may predate the words.
            if (skipCooldownOnce) {
                if (tableIsCurrent()) skipCooldownOnce = false else kickBuild(keyW)
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
        val keepK = ArrayList<String>(words.size)
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
            // The apostrophe is on another page and no finger goes there, so a
            // contraction is traced by its letters alone: "don't" is the curve
            // d-o-n-t. One at the very start or end ('cause, dogs') is not part
            // of a curve anybody means, and stays a word to tap.
            val k = if ('\'' in w) {
                if (w[0] == '\'' || w[w.length - 1] == '\'') continue
                w.replace("'", "")
            } else {
                w
            }
            if (k.length < 2) continue
            for (c in k) {
                val ci = c - 'a'
                if (ci < 0 || ci > 25 || !have[ci]) continue@outer
            }
            keepW += w
            keepK += k
            keepF[keepN++] = f
            letterTotal += k.length
        }

        // The plain word a contraction shares its curve with, if there is one.
        val plainFreq = HashMap<String, Long>()
        for (i in 0 until keepN) {
            if (keepW[i].length != keepK[i].length) plainFreq[keepK[i]] = 0L
        }
        for (i in 0 until keepN) {
            if (keepW[i].length == keepK[i].length && keepK[i] in plainFreq) plainFreq[keepK[i]] = keepF[i]
        }
        val contractions = HashMap<String, Contraction>()
        for (i in 0 until keepN) {
            if (keepW[i].length != keepK[i].length) {
                contractions[keepW[i]] = contractionOf(keepW[i], keepK[i], keepF[i], plainFreq[keepK[i]] ?: 0L)
            }
        }

        val n = keepN
        val wordArr = Array(n) { "" }
        val keyArr = Array(n) { "" }
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
            // Everything geometric below runs on the traced letters.
            val w = keepK[i]
            val f = keepF[i]
            wordArr[i] = keepW[i]
            keyArr[i] = w
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

        return Table(wordArr, keyArr, freq, bits, iarc, off, xy, fr, order, bucketStart, contractions)
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
        /** The same points centred on their centroid and divided by the larger
         *  side of their bounding box: the shape, wherever it was drawn and
         *  however big. */
        val shapeX = FloatArray(NSAMPLES)
        val shapeY = FloatArray(NSAMPLES)
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
            normalizeShape(ux, uy, shapeX, shapeY)

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
     *  entry closest to zero, the one that says least, so a burst of
     *  corrections can't grow the map without bound. Racing inserts can
     *  overshoot the bound by a handful of entries — harmless, and corrected
     *  on the next insert. */
    private fun addBias(w: String, delta: Int) {
        if (!gestureBias.containsKey(w) && gestureBias.size >= BIAS_MAX_ENTRIES) {
            var victim: String? = null
            var victimV = 0
            var victimSize = Int.MAX_VALUE
            for ((k, v) in gestureBias) {
                val size = kotlin.math.abs(v)
                if (size < victimSize) { victimSize = size; victimV = v; victim = k }
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
     *
     * This is only the fallback for a caller with no mask of its own, and a weak
     * one. The view keeps a point only once the finger has moved 2 dp, so a finger
     * that has stopped adds nothing here, and what this counts is a narrow band of
     * speed rather than a pause.
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
        /** What this user has typed after [prev1Lower], with counts. Read once per
         *  decode, not per candidate. */
        val biRow: Map<String, Int>?,
        /** What this user has typed after the last two words, with counts. */
        val triRow: Map<String, Int>?,
        /** Letter-fit window half-width, in key units. Grows with the
         *  gesture: the expected arc position of the 8th letter of a long
         *  word drifts further from where the finger actually was than the
         *  2nd letter of a short one, and a fixed window charged long words
         *  a miss per drifted letter. */
        val win: Float,
        /** Taken off a contraction here, see [NOUN_FOLLOWS]. */
        val contractionPenalty: Double,
        /** What came before calls for a plain word, not a new clause. */
        val plainExpected: Boolean,
        /** What came before takes a complement or a clause, see [COMPLEMENT_OR_CLAUSE_FOLLOWS]. */
        val complementOrClause: Boolean,
        /** The previous word can open a question, see [Contraction.rivalOpensQuestion]. */
        val afterQuestionWord: Boolean,
        /** The share of its figure a clause-opening contraction keeps here, whatever the
         *  row says: see [clauseLift]. */
        val clauseLift: Double
    ) {
        /** Scratch reused across candidates: chamfer back-distances. */
        val backs = FloatArray(NSAMPLES)
        /** Scratch reused across candidates: a word's curve, resampled, then as a shape. */
        val idealX = FloatArray(NSAMPLES)
        val idealY = FloatArray(NSAMPLES)
        val idealShapeX = FloatArray(NSAMPLES)
        val idealShapeY = FloatArray(NSAMPLES)
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
        // Geometry reads the traced letters. Language reads the word as it is
        // spelled, because every count behind it is keyed on "don't", not "dont".
        val disp = t.word[id]
        val w = t.key[id]
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
        val c2 = q.biRow?.get(disp) ?: 0
        val c3 = q.triRow?.get(disp) ?: 0
        val con = if (disp.length != wl) t.contractions[disp] else null
        val plainHere = con != null && (q.plainExpected || (q.complementOrClause && con.rivalIsComplement))
        val prior = when {
            con == null -> t.freq[id]
            plainHere -> con.unlikelyFreq
            else -> con.usualFreq
        }
        var eff0 = if (lm != null && p1 != null) lm.effectiveFrequency(p1, disp, prior) else prior
        if (con != null && !plainHere && con.opensClause && !(con.rivalOpensQuestion && q.afterQuestionWord)) {
            eff0 = max(eff0, (t.freq[id] * q.clauseLift).toLong())
        }
        // The bundled model backs off hard from a pair it has not seen. A pair this
        // user has actually typed is not unlikely just because that model lacks it.
        val eff = if (c2 > 0 || c3 > 0) max(eff0, prior) else eff0
        val lf = ln(eff.toDouble() + 1.0)
        val freqBonus = FREQ_W * min(lf, FREQ_CAP) + FREQ_SOFT * max(0.0, lf - FREQ_CAP)
        // Personal counts are evidence, and evidence saturates. Uncapped, a word typed
        // thousands of times outbid the shape of whatever was actually drawn, so more
        // history meant worse glides, and a keyboard that remembers can reach those
        // counts. Context earns more than the bare word because it says why this word
        // here; the word alone says only that this person likes it.
        val userBonus = min(USER_CAP, USER_W * ln(fastEngine.userFrequencyLower(disp) + 1.0))
        var ctxBonus = 0.0
        if (c2 > 0) ctxBonus += min(BI_CAP, BI_W * ln(1.0 + c2))
        if (c3 > 0) ctxBonus += min(TRI_CAP, TRI_W * ln(1.0 + c3))
        // Words the user has previously rescued from the alternates list get
        // a standing thumb on the scale; words whose committed glide was
        // rejected get pressed down (negative bias), symmetric through the
        // same map.
        val biasBonus = if (learningEnabled) BIAS_W * (gestureBias[disp] ?: 0) else 0.0

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
        // A pair this user has typed has already answered the question the penalty asks.
        val grammarPen = if (con != null && con.verbForm && c2 == 0 && c3 == 0) q.contractionPenalty else 0.0
        return -GEO_W * (1f - BACK_W - WORST_W) * ld - missPen - arcPen -
            END_W * endPen + freqBonus + userBonus + ctxBonus + biasBonus + doubleCredit - grammarPen
    }

    /** Stage 2: reverse chamfer — every gesture sample's distance to the
     *  word's ideal polyline, mean and worst-quartile. The hot loop, scalar
     *  and allocation-free; only survivors of stage 1 pay for it. */
    private fun scoreStage2(t: Table, id: Int, q: Query): Double {
        val wl = t.key[id].length
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
        return -GEO_W * (BACK_W * min(bm, 1.5f) + WORST_W * min(bw, 2.0f)) -
            SHAPE_W * shapeDist(t, id, q)
    }

    /**
     * How far the gesture's shape is from the word's, position and size aside.
     * Every other geometry term is in absolute coordinates, so a finger that lands
     * a little low or draws a little small is charged on all of them at once for
     * the same habit. This one still sees the right shape.
     *
     * Both curves are sampled by arc fraction, so a word's letters are compared at
     * the points of the gesture where the finger should have been passing them.
     */
    private fun shapeDist(t: Table, id: Int, q: Query): Float {
        val wl = t.key[id].length
        // A curve that goes nowhere ("mm") has no shape to agree with. Without this
        // it would score as a perfect match for any gesture that gets here.
        if (t.iarc[id] <= 0f) return DOT_SHAPE
        val base = t.off[id]
        val frBase = t.frStart(id)
        val ix = q.idealX
        val iy = q.idealY
        var seg = 1
        for (s in 0 until NSAMPLES) {
            val f = s.toFloat() / (NSAMPLES - 1)
            while (seg < wl - 1 && t.fr[frBase + seg] < f) seg++
            val f0 = t.fr[frBase + seg - 1]
            val f1 = t.fr[frBase + seg]
            val u = if (f1 > f0) (f - f0) / (f1 - f0) else 0f
            val x0 = t.xy[base + (seg - 1) * 2]
            val y0 = t.xy[base + (seg - 1) * 2 + 1]
            ix[s] = x0 + (t.xy[base + seg * 2] - x0) * u
            iy[s] = y0 + (t.xy[base + seg * 2 + 1] - y0) * u
        }
        normalizeShape(ix, iy, q.idealShapeX, q.idealShapeY)
        val gx = q.pi.shapeX
        val gy = q.pi.shapeY
        val hx = q.idealShapeX
        val hy = q.idealShapeY
        var sum = 0f
        for (s in 0 until NSAMPLES) {
            val dx = gx[s] - hx[s]
            val dy = gy[s] - hy[s]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / NSAMPLES
    }

    private fun buildQuery(
        rawPath: List<PointF>,
        prev1: String?,
        prev2: String? = null,
        givenDwell: Int? = null
    ): Query? {
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
        val dwell = if (doubleLetterEnabled) givenDwell ?: dwellMask(pi, keyW) else 0
        // Hoisted out of the candidate loop: FastEngine's case-insensitive
        // lookups lowercase per call, which was two string allocations (plus
        // the bigram key concat) per candidate, per decode. Table words pass
        // buildTable's letter filter, so they are already lowercase and can go
        // straight to the *Lower entry points.
        val prev1Lower = prev1?.lowercase()?.takeIf { it.isNotEmpty() }
        val prev2Lower = prev2?.lowercase()?.takeIf { it.isNotEmpty() }
        val lm = fastEngine.languageModel
        val plainExpected = prev1Lower != null && expectsPlainWord(prev1Lower)
        return Query(
            pi, keyW,
            pi.px[0], pi.py[0], pi.px[pi.px.size - 1], pi.py[pi.py.size - 1],
            vbits, dwell, prev1Lower, lm,
            biRow = prev1Lower?.let { fastEngine.bigramRowLower(it) },
            // What came before a full stop says nothing about the sentence after it.
            triRow = if (prev1Lower != null && prev2Lower != null && prev1Lower != SENTENCE_MARK) {
                fastEngine.trigramRowLower(prev2Lower, prev1Lower)
            } else {
                null
            },
            win = WIN_BASE + WIN_PER_ARC * pi.arc,
            contractionPenalty = if (prev1Lower in NOUN_FOLLOWS) NOUN_FOLLOWS_PENALTY else 0.0,
            plainExpected = plainExpected,
            complementOrClause = prev1Lower in COMPLEMENT_OR_CLAUSE_FOLLOWS,
            afterQuestionWord = prev1Lower in QUESTION_WORDS,
            clauseLift = if (lm == null || prev1Lower == null || plainExpected) 0.0 else clauseLift(lm, prev1Lower)
        )
    }

    /**
     * Returns up to [maxResults] candidates, best first. Practically never empty.
     *
     * [prev1] and [prev2] are the last two words, lowercase or not. [dwellMask]
     * is a 26-bit mask of the letters the finger paused on, for a caller that can
     * tell from the raw touch samples, which this path has already thinned. It is
     * taken as given while [doubleLetterEnabled]. Left null, the mask is estimated
     * from the path, which barely works once the view has thinned it.
     */
    fun decode(
        rawPath: List<PointF>,
        maxResults: Int = 4,
        prev1: String? = null,
        prev2: String? = null,
        dwellMask: Int? = null
    ): List<Suggestion> {
        val keyW = geometry.averageKeyWidth
        if (keyW <= 0f) return emptyList()
        val t = ensureTable(keyW) ?: return emptyList()
        val q = buildQuery(rawPath, prev1, prev2, dwellMask) ?: return emptyList()
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
                    val wl = t.key[id].length
                    if (wl > maxLen) continue
                    if (wl < 4 && t.freq[id] < FRAGMENT_FLOOR) continue
                    if (kotlin.math.abs(t.iarc[id] - pi.arc) > LEN_TOL_A * pi.arc + LEN_TOL_B) continue
                    // Letters the gesture never came near. Two slips are
                    // allowed on a short word and three on a longer one: this
                    // runs before any scoring, so a word it drops is gone
                    // whatever the shape says, and a finger running between
                    // two rows is near neither of them.
                    val missing = Integer.bitCount(t.bits[id] and vinv)
                    if (missing > maxMissing(wl)) continue
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
    fun explain(rawPath: List<PointF>, word: String, prev1: String? = null, prev2: String? = null): String {
        val keyW = geometry.averageKeyWidth
        val t = ensureTable(keyW) ?: return "no table"
        val q = buildQuery(rawPath, prev1, prev2) ?: return "path rejected"
        val disp = word.lowercase()
        var id = -1
        for (i in t.word.indices) if (t.word[i] == disp) { id = i; break }
        if (id < 0) return "$disp: not in glide table"
        val w = t.key[id]
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
        if (missing > maxMissing(wl)) {
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
        sb.append("total=${"%.2f".format(s1 + s2)} s1=${"%.2f".format(s1)} s2=${"%.2f".format(s2)} ")
        sb.append("ld=${"%.2f".format(ldSum / wl)} missPen=${"%.1f".format(misses)} arcD=${"%.1f".format(pi.arc - t.iarc[id])} ")
        sb.append("end=${"%.2f".format(seedD + endD)} win=${"%.2f".format(q.win)} ")
        sb.append("shape=${"%.3f".format(shapeDist(t, id, q))} bi=${q.biRow?.get(disp) ?: 0} tri=${q.triRow?.get(disp) ?: 0} ")
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
        /** Points per nat of how often this user typed the word, up to a cap. These
         *  and the context weights below are starting values, chosen on an invented
         *  corpus. Real traces should decide them. */
        private const val USER_W = 0.3
        private const val USER_CAP = 1.5
        /** Points per nat of how often this user typed the word after the last word. */
        private const val BI_W = 1.0
        private const val BI_CAP = 3.0
        /** ...and after the last two words. */
        private const val TRI_W = 2.0
        private const val TRI_CAP = 6.0
        /** The mark the context tracker keeps between sentences. */
        private const val SENTENCE_MARK = "."
        /**
         * After these a noun is coming, so a contraction is next to impossible. It
         * matters because "he'll" and "hell" trace one curve and the bundled model
         * backs both off alike after "the", which leaves the commoner spelling to win:
         * "what the he'll". Words that can end a clause, like "her" or "that", are left
         * out: "i told her i'm late".
         */
        private val NOUN_FOLLOWS = setOf("a", "an", "the", "my", "your", "our", "his", "their")
        private const val NOUN_FOLLOWS_PENALTY = 4.0
        /** After these the sentence wants its complement, so a new clause is not coming:
         *  "was well", "get well", "was its own thing". */
        private val COMPLEMENT_FOLLOWS = setOf(
            "am", "are", "was", "were", "be", "been", "being",
            "isn't", "aren't", "wasn't", "weren't",
            "feels", "felt", "feeling", "get", "gets", "got", "getting",
            "fell", "became", "become"
        )
        /** These take a complement or a whole clause, "feel ill" and "the thing is it's fine",
         *  and a previous word cannot say which. The candidate can: a contraction loses only
         *  to a plain spelling that could be the complement, see [COMPLEMENT_WORDS]. That
         *  leaves "the thing is i'll" and "i feel i'll" wrong until the pair is typed once. */
        private val COMPLEMENT_OR_CLAUSE_FOLLOWS = setOf("is", "feel")
        /** Plain spellings that can be the complement of a linking verb: "is well", "feel
         *  ill", "war is hell". */
        private val COMPLEMENT_WORDS = setOf("well", "ill", "hell")
        /** A subject follows these often enough, but not one that takes a contraction: an
         *  object ("of it"), a question ("do we"), or the words people actually say next
         *  ("as well", "as hell"). The prior gets no help here, and no harm either, since
         *  words like these can end a clause: "hold on i'll check". */
        private val NO_CLAUSE_FOLLOWS = setOf(
            "of", "in", "on", "at", "to", "for", "with", "by", "from", "into", "as",
            "do", "does", "did", "can", "could", "would", "should", "will", "shall", "may", "might", "must",
            "have", "has", "had"
        )
        private val QUESTION_WORDS = setOf("what", "where", "how", "why", "who")
        /** Subject pronouns in the bundled rows, the model's only sign that a clause starts
         *  after a word. "It" and "you" are objects as well ("make it"), and "i" is not
         *  counted because the rows hardly list it. */
        private val SUBJECTS = arrayOf("we", "he", "she", "they")
        /** A word followed by one of [SUBJECTS] this often opens clauses as readily as
         *  anything does. "and" is at 0.03, "so" at 0.08 and "because" at 0.23. */
        private const val CLAUSE_RATE_FULL = 0.05
        /** What a contraction can start with and still open a clause. */
        private val CLAUSE_HEADS = setOf(
            "i", "you", "he", "she", "it", "we", "they",
            "that", "there", "here", "what", "who", "where", "how", "when", "why", "let"
        )
        /** Pairs close to even in real chat with nothing to go on, where the plain word is
         *  the safer guess: "well i think", "hell yeah", "these ones". Rows should settle
         *  them when they land. Every other pair keeps the contraction, as "ill" and "id"
         *  rarely open a message, or the pick is defensible, as with "we're" and "were". */
        private val PLAIN_FIRST_WITHOUT_CONTEXT = setOf("we'll", "he'll", "one's")
        /** Just under the plain word: a typed pair or a pick then decides it at once. */
        private const val JUST_UNDER_PLAIN = 0.9
        /** Clearly under it where the plain word is what is expected. */
        private const val WELL_UNDER_PLAIN = 0.5

        private fun expectsPlainWord(prev: String): Boolean =
            prev in NOUN_FOLLOWS || prev in COMPLEMENT_FOLLOWS ||
                prev.endsWith("'m") || prev.endsWith("'re") || prev.endsWith("'s")

        /**
         * How far a contraction's figure survives after [prev]. The rows have no
         * contractions, but they do have the pronouns that "we're" and "he'll" begin with,
         * so a word those often follow is one a contraction can follow too, and a pair
         * missing from its row says nothing. Zero where no clause can be told to start,
         * which leaves the usual backoff.
         */
        private fun clauseLift(lm: BaseLanguageModel, prev: String): Double {
            if (prev in NO_CLAUSE_FOLLOWS) return 0.0
            var rate = 0.0
            for (s in SUBJECTS) rate += lm.prob(prev, s)
            return min(1.0, rate / CLAUSE_RATE_FULL)
        }

        private fun contractionOf(word: String, key: String, freq: Long, plainFreq: Long): Contraction {
            val opensClause = word.substring(0, word.indexOf('\'')) in CLAUSE_HEADS
            val verbForm = opensClause || word.endsWith("n't")
            val usual = if (plainFreq > 0L && word in PLAIN_FIRST_WITHOUT_CONTEXT) {
                min(freq, (plainFreq * JUST_UNDER_PLAIN).toLong())
            } else {
                freq
            }
            val unlikely = if (plainFreq > 0L && verbForm) {
                min(usual, (plainFreq * WELL_UNDER_PLAIN).toLong())
            } else {
                usual
            }
            return Contraction(opensClause, verbForm, key == "were", usual, unlikely, key in COMPLEMENT_WORDS)
        }

        /** First and last letter must be this close to where the gesture starts
         *  and ends. A key is not the unit that matters here: the row pitch is
         *  1.84 key widths on a phone, so 1.4 was under one row. */
        private const val SEED_R = 1.7f
        private const val END_R = 1.7f
        private const val MISS_R = 0.85f
        /** A letter counts as visited inside this. Half a row pitch is 0.92. */
        private const val VISIT_R = 1.0f
        /** Points per unit of [shapeDist], where 1.0 is the size of the whole
         *  gesture. Not sensitive between about 40 and 80; much above that it
         *  starts to overrule the letters on sloppy gestures. */
        private const val SHAPE_W = 60.0
        /** [shapeDist] of a word whose curve is a single point. */
        private const val DOT_SHAPE = 0.5f
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
        private const val BIAS_W = 0.5
        /** Ceiling on that bias either way, so one word can't dominate forever. A word
         *  wiped a few times for unrelated reasons sits at the floor, so the floor has
         *  to be survivable: in simulation, 4.5 points took the commonest words from 99
         *  to 88 percent on ordinary gestures, and 1.5 points takes them to 97. */
        private const val BIAS_CAP = 3
        /** Radius (key units) inside which samples count toward a dwell. */
        private const val DWELL_R = 0.62f
        /** Consecutive near-stationary in-key samples needed to call it a dwell. */
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
         *  the entry closest to zero. */
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

        private fun maxMissing(letters: Int): Int = if (letters <= 4) 2 else 3

        /** Writes [x],[y] into [outX],[outY] centred on their centroid and divided
         *  by the larger side of their bounding box. */
        private fun normalizeShape(x: FloatArray, y: FloatArray, outX: FloatArray, outY: FloatArray) {
            var cx = 0f
            var cy = 0f
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in x.indices) {
                val px = x[i]
                val py = y[i]
                cx += px
                cy += py
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
            }
            cx /= x.size
            cy /= x.size
            val scale = max(max(maxX - minX, maxY - minY), 1e-3f)
            for (i in x.indices) {
                outX[i] = (x[i] - cx) / scale
                outY[i] = (y[i] - cy) / scale
            }
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
