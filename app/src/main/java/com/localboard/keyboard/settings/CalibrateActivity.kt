package com.localboard.keyboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.localboard.keyboard.ui.KeyType
import com.localboard.keyboard.ui.KeyboardLayouts
import com.localboard.keyboard.util.Prefs
import com.localboard.keyboard.util.TapLearningModel
import kotlin.math.sin

/**
 * "Fit keyboard to your fingers" — two calibration games.
 *
 * GRID: tap glowing dots naturally; the median error becomes a permanent
 * whole-board offset.
 *
 * TYPING TEST: type two pangrams on a mock of the REAL layout, naturally and
 * without careful aiming. Every letter's intended position is known, so each
 * tap is a high-confidence per-key sample — this is what fixes stubborn
 * one-key habits ("I keep hitting q when I mean w") that a whole-board
 * shift can't touch. Feeds the same TapLearningModel that live typing and
 * autocorrect signals keep refining afterwards.
 */
class CalibrateActivity : AppCompatActivity() {

    /** A letter key on the mock board (typing test). */
    private data class TypingKey(val letter: Char, val rect: RectF, val label: String)

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Fit keyboard to your fingers"
        setContentView(RootView(this))
    }

    private enum class Mode { CHOOSE, GRID, TYPING }

    private inner class RootView(context: Context) : View(context) {

        private var mode = Mode.CHOOSE

        // Shared paints
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var btnA = RectF()
        private var btnB = RectF()
        private var redoRect = RectF()
        private var doneRect = RectF()

        // ---- grid game state ----
        private val gridRects = ArrayList<RectF>()
        private var gridTop = 0f
        private val targetOrder = ArrayList<Int>()
        private var current = 0
        private val errors = ArrayList<Pair<Float, Float>>()
        private var done = false
        private var resultDxDp = 0f
        private var resultDyDp = 0f
        private var flashAt = 0L
        private var flashX = 0f
        private var flashY = 0f

        // ---- typing test state ----
        private val phrases = listOf(
            "the quick brown fox jumps over the lazy dog",
            "pack my box with five dozen liquor jugs"
        )
        private var phraseIdx = 0
        private var charIdx = 0
        private var typingDone = false
        private var samples = 0
        private val tKeys = ArrayList<TypingKey>()
        private val tapModel by lazy { TapLearningModel(prefs) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            buildGrid(w, h)
            buildTypingBoard(w, h)
            val bw = width * 0.38f
            val bh = dp(56f)
            btnA = RectF(width / 2f - bw - dp(8f), dp(300f), width / 2f - dp(8f), dp(300f) + bh)
            btnB = RectF(width / 2f + dp(8f), dp(300f), width / 2f + bw + dp(8f), dp(300f) + bh)
        }

        // ---------- layout builders ----------

        private fun buildGrid(w: Int, h: Int) {
            gridRects.clear()
            val rows = 4
            val cols = 10
            val keyH = dp(56f) * prefs.heightScale
            val gap = dp(4.5f)
            val boardH = rows * keyH + (rows - 1) * gap + dp(8f)
            gridTop = h - boardH - dp(6f)
            var y = gridTop
            for (r in 0 until rows) {
                val kw = (w - gap * (cols + 1)) / cols
                var x = gap
                for (c in 0 until cols) {
                    gridRects += RectF(x, y, x + kw, y + keyH)
                    x += kw + gap
                }
                y += keyH + gap
            }
            if (targetOrder.isEmpty()) {
                val picks = listOf(0, 4, 9, 10, 15, 18, 20, 24, 29, 32, 37, 13, 26, 6, 21, 35)
                targetOrder += picks.shuffled(kotlin.random.Random(42))
            }
        }

        /** Mock of the REAL qwerty layout (same weights/gaps as KeyboardView),
         *  so per-key samples transfer directly to the live keyboard. */
        private fun buildTypingBoard(w: Int, h: Int) {
            tKeys.clear()
            val model = KeyboardLayouts.qwerty(
                com.localboard.keyboard.ui.LayoutOptions(numberRow = false, digitPopups = false)
            )
            val keyH = dp(56f) * prefs.heightScale
            val gap = dp(4.5f)
            val rowGap = dp(6f)
            val sidePad = dp(3f)
            val rows = model.rows.size
            val boardH = rows * keyH + (rows - 1) * rowGap + dp(16f)
            val top = h - boardH - dp(6f) + dp(8f)
            val usable = w - sidePad * 2
            var y = top
            for (row in model.rows) {
                val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                val gaps = gap * (row.keys.size - 1)
                val edgePad = usable * row.edgePadding
                val keyArea = usable - gaps - edgePad * 2
                var x = sidePad + edgePad
                for (key in row.keys) {
                    val kw = keyArea * (key.weight / totalWeight)
                    val r = RectF(x, y, x + kw, y + keyH)
                    if (key.type == KeyType.CHAR && key.output.length == 1 &&
                        key.output[0].isLetter()
                    ) {
                        tKeys += TypingKey(key.output[0].lowercaseChar(), r, key.label)
                    }
                    x += kw + gap
                }
                y += keyH + rowGap
            }
        }

        private fun keyFor(letter: Char): TypingKey? = tKeys.firstOrNull { it.letter == letter }

        // ---------- drawing ----------

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            textPaint.color = 0xFFF2F4F8.toInt()
            subPaint.color = 0xFF9AA4B2.toInt()
            when (mode) {
                Mode.CHOOSE -> drawChoose(canvas, w)
                Mode.GRID -> drawGrid(canvas, w)
                Mode.TYPING -> drawTyping(canvas, w)
            }
        }

        private fun drawChoose(canvas: Canvas, w: Float) {
            textPaint.textSize = sp(21f)
            canvas.drawText("Fit the keyboard to your hands", w / 2, dp(72f), textPaint)
            subPaint.textSize = sp(15f)
            canvas.drawText("Quick grid fit: taps 16 dots, shifts the whole board.", w / 2, dp(120f), subPaint)
            canvas.drawText("Typing test: type two sentences, tunes EVERY KEY —", w / 2, dp(146f), subPaint)
            canvas.drawText("best for a stubborn key you keep missing.", w / 2, dp(170f), subPaint)
            subPaint.textSize = sp(14f)
            canvas.drawText("Afterwards the keyboard also keeps learning on its own:", w / 2, dp(226f), subPaint)
            canvas.drawText("every autocorrect and quick retype teaches it your hands.", w / 2, dp(250f), subPaint)

            btnPaint.color = 0xFF232A33.toInt()
            canvas.drawRoundRect(btnA, dp(10f), dp(10f), btnPaint)
            canvas.drawRoundRect(btnB, dp(10f), dp(10f), btnPaint)
            textPaint.textSize = sp(15f)
            canvas.drawText("Quick grid fit", btnA.centerX(), btnA.centerY() + sp(5f), textPaint)
            canvas.drawText("Typing test", btnB.centerX(), btnB.centerY() + sp(5f), textPaint)
        }

        private fun drawGrid(canvas: Canvas, w: Float) {
            if (!done) {
                textPaint.textSize = sp(20f)
                canvas.drawText("Hold the phone as you normally type", w / 2, dp(64f), textPaint)
                subPaint.textSize = sp(15f)
                canvas.drawText("Tap each glowing dot with your typing finger.", w / 2, dp(96f), subPaint)
                canvas.drawText("Don't aim carefully — tap the way you naturally would.", w / 2, dp(120f), subPaint)
                subPaint.textSize = sp(14f)
                canvas.drawText("${current.coerceAtMost(targetOrder.size)} / ${targetOrder.size}", w / 2, dp(152f), subPaint)
            }

            keyPaint.color = 0xFF232A33.toInt()
            for (r in gridRects) canvas.drawRoundRect(r, dp(6f), dp(6f), keyPaint)

            if (!done && current < targetOrder.size) {
                val t = gridRects[targetOrder[current]]
                val pulse = (sin(System.currentTimeMillis() / 180.0) + 1.0) / 2.0
                targetPaint.color = 0xFF6EA8FE.toInt()
                canvas.drawCircle(t.centerX(), t.centerY(), dp(13f) + (dp(5f) * pulse).toFloat(), targetPaint)
                targetPaint.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(t.centerX(), t.centerY(), dp(5f), targetPaint)
                postInvalidateDelayed(40)
            }

            if (System.currentTimeMillis() - flashAt < 180) {
                targetPaint.color = 0xFF7BD88F.toInt()
                canvas.drawCircle(flashX, flashY, dp(10f), targetPaint)
                postInvalidateDelayed(40)
            }

            if (done) {
                textPaint.textSize = sp(22f)
                canvas.drawText("Calibrated!", w / 2, dp(72f), textPaint)
                subPaint.textSize = sp(15f)
                val dirX = when {
                    resultDxDp < -2 -> "${-resultDxDp.toInt()}dp left"
                    resultDxDp > 2 -> "${resultDxDp.toInt()}dp right"
                    else -> "not horizontally"
                }
                val dirY = when {
                    resultDyDp < -2 -> "${-resultDyDp.toInt()}dp up"
                    resultDyDp > 2 -> "${resultDyDp.toInt()}dp down"
                    else -> "not vertically"
                }
                canvas.drawText("Your keys now shift $dirX and $dirY,", w / 2, dp(108f), subPaint)
                canvas.drawText("matching where your fingers actually land.", w / 2, dp(132f), subPaint)
                canvas.drawText("The keyboard picks this up next time it opens.", w / 2, dp(164f), subPaint)
                drawDoneButtons(canvas)
            }
        }

        private fun drawTyping(canvas: Canvas, w: Float) {
            if (typingDone) {
                textPaint.textSize = sp(22f)
                canvas.drawText("Per-key fit saved!", w / 2, dp(72f), textPaint)
                subPaint.textSize = sp(15f)
                canvas.drawText("$samples letter taps measured across the whole board.", w / 2, dp(110f), subPaint)
                canvas.drawText("$fitKeys keys MOVED to where your fingers actually land —", w / 2, dp(134f), subPaint)
                canvas.drawText("the keys shift, not just their hidden target.", w / 2, dp(158f), subPaint)
                canvas.drawText("Autocorrect and quick retypes keep tuning it from here.", w / 2, dp(190f), subPaint)
                drawDoneButtons(canvas)
            } else {
                textPaint.textSize = sp(19f)
                canvas.drawText("Type this — naturally, don't aim", w / 2, dp(56f), textPaint)
                drawPhrase(canvas, w)
                subPaint.typeface = Typeface.DEFAULT
                subPaint.color = 0xFF9AA4B2.toInt()
                subPaint.textSize = sp(14f)
                canvas.drawText(
                    "Sentence ${phraseIdx + 1} of ${phrases.size}",
                    w / 2, phraseBottom + dp(26f), subPaint
                )
            }

            // Mock real board with labels; the target letter's key pulses.
            keyPaint.color = 0xFF232A33.toInt()
            val currentLetter = currentTargetLetter()
            for (tk in tKeys) {
                canvas.drawRoundRect(tk.rect, dp(6f), dp(6f), keyPaint)
                subPaint.color = 0xFFF2F4F8.toInt()
                subPaint.textSize = sp(16f)
                val baseline = tk.rect.centerY() - (subPaint.descent() + subPaint.ascent()) / 2
                canvas.drawText(tk.label, tk.rect.centerX(), baseline, subPaint)
            }
            if (!typingDone && currentLetter != null) {
                keyFor(currentLetter)?.let { tk ->
                    val pulse = (sin(System.currentTimeMillis() / 180.0) + 1.0) / 2.0
                    targetPaint.color = 0xFF6EA8FE.toInt()
                    targetPaint.style = Paint.Style.STROKE
                    targetPaint.strokeWidth = dp(2f) + dp(1.5f) * pulse.toFloat()
                    canvas.drawRoundRect(tk.rect, dp(6f), dp(6f), targetPaint)
                    targetPaint.style = Paint.Style.FILL
                    postInvalidateDelayed(40)
                }
            }
            if (System.currentTimeMillis() - flashAt < 180) {
                targetPaint.color = 0xFF7BD88F.toInt()
                canvas.drawCircle(flashX, flashY, dp(10f), targetPaint)
                postInvalidateDelayed(40)
            }
            subPaint.color = 0xFF9AA4B2.toInt()
        }

        /** Bottom of the wrapped phrase block, so the caption can follow it. */
        private var phraseBottom = dp(120f)

        /**
         * The target sentence, wrapped to the screen.
         *
         * It used to be laid out as one centred line, which for a 43-character
         * pangram at 22sp starts off the left edge and ends off the right — so
         * the words you were meant to be reading were the one thing you could
         * not see, and the only way to follow along was to watch the keys.
         * That defeats the test: the whole point is to type without looking at
         * your hands, because looking is what makes you aim carefully, and
         * careful aiming measures nothing about natural finger placement.
         *
         * Now it word-wraps and shrinks to fit, so the sentence always sits
         * still in one readable block above the keys.
         */
        private fun drawPhrase(canvas: Canvas, w: Float) {
            val phrase = phrases[phraseIdx]
            val margin = dp(16f)
            val maxW = w - margin * 2

            // Largest size at which every word fits, down to a readable floor.
            var size = sp(23f)
            val floor = sp(15f)
            subPaint.typeface = Typeface.DEFAULT_BOLD
            while (size > floor) {
                subPaint.textSize = size
                if (phrase.split(' ').all { subPaint.measureText(it) <= maxW }) break
                size -= sp(1f)
            }
            subPaint.textSize = size

            // Greedy wrap, carrying each word's absolute start index so the
            // per-character colouring still tracks progress through the whole
            // sentence rather than through the line.
            data class Line(val text: String, val start: Int)
            val lines = ArrayList<Line>()
            var lineStart = 0
            var cur = StringBuilder()
            var i = 0
            while (i < phrase.length) {
                val next = phrase.indexOf(' ', i).let { if (it < 0) phrase.length else it }
                val word = phrase.substring(i, next)
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                if (subPaint.measureText(candidate) > maxW && cur.isNotEmpty()) {
                    lines += Line(cur.toString(), lineStart)
                    lineStart = i
                    cur = StringBuilder(word)
                } else {
                    cur = StringBuilder(candidate)
                }
                i = next + 1
            }
            if (cur.isNotEmpty()) lines += Line(cur.toString(), lineStart)

            val lineH = size * 1.45f
            var y = dp(112f)
            for (line in lines) {
                val lw = subPaint.measureText(line.text)
                var x = (w - lw) / 2f
                for (j in line.text.indices) {
                    val abs = line.start + j
                    val c = line.text[j]
                    val cw = subPaint.measureText(c.toString())
                    subPaint.color = when {
                        abs < charIdx -> 0xFF7BD88F.toInt()
                        abs == charIdx -> 0xFF6EA8FE.toInt()
                        else -> 0xFF9AA4B2.toInt()
                    }
                    if (c != ' ') canvas.drawText(c.toString(), x + cw / 2, y, subPaint)
                    // A caret under the next letter: with the keys unwatched,
                    // colour alone is easy to lose track of mid-sentence.
                    if (abs == charIdx) {
                        subPaint.color = 0xFF6EA8FE.toInt()
                        canvas.drawRect(
                            x, y + dp(4f), x + maxOf(cw, dp(6f)), y + dp(6f), subPaint
                        )
                    }
                    x += cw
                }
                y += lineH
            }
            phraseBottom = y - lineH
        }

        private fun drawDoneButtons(canvas: Canvas) {
            val bw = width * 0.34f
            val bh = dp(52f)
            val by = dp(200f)
            redoRect = RectF(width / 2f - bw - dp(10f), by, width / 2f - dp(10f), by + bh)
            doneRect = RectF(width / 2f + dp(10f), by, width / 2f + bw + dp(10f), by + bh)
            btnPaint.color = 0xFF232A33.toInt()
            canvas.drawRoundRect(redoRect, dp(10f), dp(10f), btnPaint)
            canvas.drawRoundRect(doneRect, dp(10f), dp(10f), btnPaint)
            textPaint.textSize = sp(16f)
            canvas.drawText("Redo", redoRect.centerX(), redoRect.centerY() + sp(6f), textPaint)
            canvas.drawText("Done", doneRect.centerX(), doneRect.centerY() + sp(6f), textPaint)
        }

        // ---------- input ----------

        private fun currentTargetLetter(): Char? {
            if (typingDone || phraseIdx >= phrases.size) return null
            val phrase = phrases[phraseIdx]
            var i = charIdx
            while (i < phrase.length && !phrase[i].isLetter()) i++
            return phrase.getOrNull(i)?.lowercaseChar()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
            val x = event.x
            val y = event.y

            when (mode) {
                Mode.CHOOSE -> {
                    when {
                        btnA.contains(x, y) -> { mode = Mode.GRID; invalidate() }
                        btnB.contains(x, y) -> { mode = Mode.TYPING; invalidate() }
                    }
                    return true
                }
                Mode.GRID -> {
                    if (done) {
                        when {
                            redoRect.contains(x, y) -> { errors.clear(); current = 0; done = false; invalidate() }
                            doneRect.contains(x, y) -> (context as CalibrateActivity).finish()
                        }
                        return true
                    }
                    if (current < targetOrder.size) {
                        val t = gridRects[targetOrder[current]]
                        errors += (x - t.centerX()) to (y - t.centerY())
                        flashAt = System.currentTimeMillis()
                        flashX = x
                        flashY = y
                        current++
                        if (current >= targetOrder.size) finishGrid()
                        invalidate()
                    }
                    return true
                }
                Mode.TYPING -> {
                    if (typingDone) {
                        when {
                            redoRect.contains(x, y) -> {
                                phraseIdx = 0; charIdx = 0; samples = 0
                                typingDone = false; invalidate()
                            }
                            doneRect.contains(x, y) -> (context as CalibrateActivity).finish()
                        }
                        return true
                    }
                    // Advance past spaces automatically, then sample this tap
                    // against the INTENDED letter — wherever it actually landed.
                    val phrase = phrases[phraseIdx]
                    while (charIdx < phrase.length && !phrase[charIdx].isLetter()) charIdx++
                    if (charIdx < phrase.length) {
                        val letter = phrase[charIdx].lowercaseChar()
                        keyFor(letter)?.let { tk ->
                            val d = resources.displayMetrics.density
                            val dxDp = (x - tk.rect.centerX()) / d
                            val dyDp = (y - tk.rect.centerY()) / d
                            // Guard: a tap this far off the intended key is a
                            // slip (mis-touched screen), not a hand habit —
                            // one stray sample would skew a key's learned spot.
                            if (dxDp * dxDp + dyDp * dyDp <= 30f * 30f) {
                                tapModel.recordGuided(letter.toString(), dxDp, dyDp)
                                samples++
                            }
                        }
                        flashAt = System.currentTimeMillis()
                        flashX = x
                        flashY = y
                        charIdx++
                        if (charIdx >= phrase.length) {
                            if (phraseIdx + 1 >= phrases.size) finishTyping()
                            else { phraseIdx++; charIdx = 0 }
                        }
                        invalidate()
                    }
                    return true
                }
            }
        }

        private fun finishGrid() {
            val dxs = errors.map { it.first }.sorted()
            val dys = errors.map { it.second }.sorted()
            val medX = dxs[dxs.size / 2]
            val medY = dys[dys.size / 2]
            resultDxDp = (medX / resources.displayMetrics.density).coerceIn(-48f, 48f)
            resultDyDp = (medY / resources.displayMetrics.density).coerceIn(-48f, 48f)
            prefs.keyOffsetXDp = resultDxDp
            prefs.keyOffsetYDp = resultDyDp
            done = true
        }

        /**
         * Turn the measured samples into a layout, not just a hit-test tweak.
         *
         * The tap model alone moves where a key LISTENS while leaving it drawn
         * where it was, so the key you aim at and the key you hit drift apart
         * and the calibration is invisible. Writing the same measurements into
         * keyFitData moves the drawn keys to where the fingers actually land,
         * which is the thing that makes typing feel natural.
         *
         * The tap model is then cleared. It has to be: the offsets are now
         * expressed in the geometry, and leaving them in the model as well
         * would shift each key twice and push its target off the far edge.
         * From here the model relearns residual drift from the new positions.
         */
        private fun finishTyping() {
            typingDone = true
            val measured = tapModel.snapshot()
            if (measured.isNotEmpty()) {
                // Locale.US, not the default locale. This is a storage format
                // whose own field separator is a comma, and half the world
                // formats 1.5 as "1,5" — on those locales every entry came
                // back unparseable, keyFitMap() dropped all of them, and the
                // test reported success having thrown the measurements away
                // AFTER clearing the tap model. Same reason as the identical
                // format string in TapLearningModel.persist().
                prefs.keyFitData = measured.entries.joinToString(";") { (k, v) ->
                    "$k:" + "%.1f,%.1f".format(java.util.Locale.US, v.first, v.second)
                }
                tapModel.clear()
                fitKeys = measured.size
            }
        }

        /** How many keys the last test actually moved, for the summary. */
        private var fitKeys = 0

        private fun dp(v: Float) = v * resources.displayMetrics.density
        private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    }
}
