package com.localboard.keyboard.engine

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where every key is drawn, as plain arithmetic.
 *
 * Drawn keys are a pure function of the layout, the optional keys, key spacing,
 * height scale, font scale, orientation, one-handed mode and view width. Those are
 * the only inputs here, and nothing learned from the user's touch is one of them, so
 * no amount of typing or testing can move a key.
 *
 * This is also the one copy of the numbers. The keyboard, its settings preview and
 * the offline simulators all lay keys out through it: a separate copy in any of them
 * drifts silently, and every simulated result then describes a keyboard nobody has.
 */
object KeyLayoutMath {

    /** A row of keys as the layout sees it: relative widths, and the fraction of the
     *  usable width left empty on each side. */
    class Row(val weights: FloatArray, val edgePadding: Float = 0f)

    /** One key's rectangle, in px. */
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /** Everything [layout] needs to know about the view, in px. */
    class Metrics(
        val originX: Float,
        val usableWidth: Float,
        val topPadding: Float,
        val bottomPadding: Float,
        val keyHeight: Float,
        val keyGap: Float,
        val rowGap: Float
    )

    /**
     * The metrics for a view of [viewWidthPx], rounded to whole px the way the view's
     * own dp() rounds them. Gaps and key height are whole pixels on purpose, so a key
     * edge lands on the same pixel every time the board is drawn.
     *
     * [oneHandedMode]: 0 = full width, 1 = docked right, 2 = docked left. The gutter
     * goes on the side the grid is not docked to.
     */
    fun metrics(
        density: Float,
        viewWidthPx: Int,
        heightScale: Float = 1f,
        formFactor: Float = 1f,
        keySpacing: Float = 1f,
        oneHandedMode: Int = 0,
        oneHandedWidth: Float = 0.82f
    ): Metrics {
        fun dp(v: Float): Int = (v * density).roundToInt()
        val spacing = keySpacing.coerceIn(0.4f, 1.8f)
        val side = dp(3f)
        val whole = viewWidthPx - side * 2
        val mode = oneHandedMode.coerceIn(0, 2)
        val fraction = oneHandedWidth.coerceIn(0.6f, 1f)
        return Metrics(
            originX = if (mode == 1) side + whole * (1f - fraction) else side.toFloat(),
            usableWidth = if (mode == 0) whole.toFloat() else whole * fraction,
            topPadding = dp(8f).toFloat(),
            bottomPadding = dp(8f).toFloat(),
            // Gboard parity (measured on the 1080x2316 reference, density 2.8):
            // Gboard's letter keys are 112 px where 40dp lands, and its rows
            // breathe with a 28 px (10dp) gap; together with the prediction
            // bar and the bottom strip the board totals the same 841 px.
            // heightScale still multiplies this base, so a stored scale keeps
            // meaning the same relative choice on the new default.
            keyHeight = dp(40 * heightScale * formFactor).toFloat(),
            keyGap = dp(4.5f * spacing).toFloat(),
            rowGap = dp(10f * spacing).toFloat()
        )
    }

    /**
     * How much taller the keys get on this screen, on top of the user's height scale.
     * Landscape is shorter because vertical space is precious there, tablets slightly
     * taller, and keys grow with the system font so large labels are not clipped.
     */
    fun formFactor(landscape: Boolean, smallestWidthDp: Int, fontScale: Float): Float {
        var f = 1f
        if (landscape) f *= 0.85f
        if (smallestWidthDp >= 600) f *= 1.12f
        if (fontScale > 1f) f *= fontScale.coerceAtMost(1.3f)
        return f
    }

    /** One list of boxes per row, top row first, keys left to right. */
    fun layout(rows: List<Row>, m: Metrics): List<List<Box>> {
        var y = m.topPadding
        return rows.map { row ->
            val totalWeight = row.weights.sumOf { it.toDouble() }.toFloat()
            val gaps = m.keyGap * (row.weights.size - 1)
            val edgePad = m.usableWidth * row.edgePadding
            val keyArea = m.usableWidth - gaps - edgePad * 2
            var x = m.originX + edgePad
            val boxes = row.weights.map { weight ->
                val w = keyArea * (weight / totalWeight)
                val box = Box(x, y, x + w, y + m.keyHeight)
                x += w + m.keyGap
                box
            }
            y += m.keyHeight + m.rowGap
            boxes
        }
    }

    /** The height the view measures itself at. */
    fun heightPx(rowCount: Int, m: Metrics): Int =
        (m.topPadding + m.bottomPadding + rowCount * m.keyHeight +
            (rowCount - 1).coerceAtLeast(0) * m.rowGap).roundToInt()

    /**
     * [m] for a board that may be at most [maxBoardPx] tall. A board that fits comes back
     * as it is. One that does not gets shorter keys, in whole px and never under [minKeyPx],
     * and nothing else changes, so every row stays whole and keeps its width and its gaps.
     * The cap is a fixed sum of what the screen holds, never a measured frame, so one show
     * and the next agree on where the keys are.
     */
    fun capped(m: Metrics, rowCount: Int, maxBoardPx: Int, minKeyPx: Float): Metrics {
        if (rowCount <= 0 || heightPx(rowCount, m) <= maxBoardPx) return m
        val rest = m.topPadding + m.bottomPadding + (rowCount - 1) * m.rowGap
        val keyHeight = floor((maxBoardPx - rest) / rowCount).coerceAtLeast(minKeyPx)
        if (keyHeight >= m.keyHeight) return m
        return Metrics(m.originX, m.usableWidth, m.topPadding, m.bottomPadding, keyHeight, m.keyGap, m.rowGap)
    }
}
