package com.localboard.keyboard.touch

import com.localboard.keyboard.engine.HitKey
import com.localboard.keyboard.engine.KeyLayoutMath
import com.localboard.keyboard.engine.TouchModel

/**
 * The board the touch numbers were worked out on: stock QWERTY with the default bottom
 * row, on a Samsung S22 Ultra at its default display size. Shared with tools/touch-sim,
 * so it stays free of JUnit. StockLayoutTest keeps it equal to the real KeyboardLayouts.
 */
object DeviceLayout {
    const val DENSITY = 450f / 160f
    const val WIDTH_PX = 1080

    val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private const val FN = HitKey.NOT_SCORABLE

    private class Spec(val symbols: CharArray, val weights: FloatArray, val edgePadding: Float = 0f)

    private fun letters(row: String) = FloatArray(row.length) { 1f }

    private val SPECS = listOf(
        Spec(LETTER_ROWS[0].toCharArray(), letters(LETTER_ROWS[0])),
        Spec(LETTER_ROWS[1].toCharArray(), letters(LETTER_ROWS[1]), 0.05f),
        // SHIFT, the letters, DELETE.
        Spec(
            charArrayOf(FN) + LETTER_ROWS[2].toCharArray() + charArrayOf(FN),
            floatArrayOf(1.5f) + letters(LETTER_ROWS[2]) + floatArrayOf(1.5f)
        ),
        // ?123, comma, emoji, space, period, enter.
        Spec(
            charArrayOf(FN, FN, FN, ' ', FN, FN),
            floatArrayOf(1.5f, 1f, 1f, 4f, 1f, 1.5f)
        )
    )

    val ROWS: List<KeyLayoutMath.Row> = SPECS.map { KeyLayoutMath.Row(it.weights, it.edgePadding) }

    val METRICS: KeyLayoutMath.Metrics = KeyLayoutMath.metrics(DENSITY, WIDTH_PX)

    val HEIGHT_PX: Int = KeyLayoutMath.heightPx(ROWS.size, METRICS)

    fun boxes(m: KeyLayoutMath.Metrics = METRICS): List<List<KeyLayoutMath.Box>> =
        KeyLayoutMath.layout(ROWS, m)

    /** The rows a HitMap is built from, the way the view will build them. */
    fun hitRows(m: KeyLayoutMath.Metrics = METRICS): List<List<HitKey>> =
        boxes(m).mapIndexed { r, row ->
            row.mapIndexed { i, b -> HitKey(SPECS[r].symbols[i], b.left, b.top, b.right, b.bottom) }
        }
}

class MemStore(var value: String = "") : TouchModel.Store {
    override fun read(): String = value
    override fun write(value: String) {
        this.value = value
    }
}
