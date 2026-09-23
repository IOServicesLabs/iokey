package com.localboard.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.localboard.keyboard.util.Prefs

/**
 * Live miniature of the real keyboard, used in Settings and the theme studio.
 *
 * It renders an actual [KeyboardTheme] — every colour, the corner radius, the
 * key font and real key legends — so "what you see is what you get" is
 * literally true. Before this it drew blank rounded rectangles, which meant
 * theming had to be judged by reopening the keyboard in another app.
 */
class KeyboardPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var theme: KeyboardTheme = KeyboardTheme.load(context, Prefs(context))
    private var heightScale = 1f
    private var textScale = 1f
    /** Bottom-row shape mirrors the user's layout choices. */
    private var showEmojiKey = true
    private var showCommaKey = true
    private var showApostropheKey = false
    private var showSelectionKey = false
    private var showNumberRow = false

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun refresh(heightScale: Float, textScale: Float, theme: KeyboardTheme) {
        this.heightScale = heightScale
        this.textScale = textScale
        this.theme = theme
        textPaint.typeface = theme.typeface()
        requestLayout()
        invalidate()
    }

    /** Show the same bottom row the user has configured. */
    fun setBottomRow(emoji: Boolean, comma: Boolean, apostrophe: Boolean) {
        showEmojiKey = emoji
        showCommaKey = comma
        showApostropheKey = apostrophe
        invalidate()
    }

    /** Full bottom-row + number-row shape, for the key customiser. */
    fun setKeys(
        emoji: Boolean,
        comma: Boolean,
        apostrophe: Boolean,
        selection: Boolean,
        numberRow: Boolean
    ) {
        showEmojiKey = emoji
        showCommaKey = comma
        showApostropheKey = apostrophe
        showSelectionKey = selection
        showNumberRow = numberRow
        requestLayout()
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val keyH = dp(38 * heightScale)
        val rowCount = if (showNumberRow) 5 else 4
        val h = (keyH * rowCount + dp(5f) * (rowCount - 1) + dp(16f) + dp(34f)).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgPaint.color = theme.background
        val radius = dp(theme.keyRadiusDp.coerceIn(0f, 20f))
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            dp(10f), dp(10f), bgPaint
        )

        // ── Prediction bar: three suggestions, centre one accented ────────
        val barH = dp(34f)
        textPaint.textSize = dp(12f) * textScale
        val slots = listOf("the", "quick", "brown")
        for (i in slots.indices) {
            val cx = width * (i + 0.5f) / 3f
            textPaint.color = if (i == 1) theme.accent else theme.textSecondary
            textPaint.isFakeBoldText = i == 1
            canvas.drawText(
                slots[i], cx,
                barH * 0.5f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint
            )
        }
        textPaint.isFakeBoldText = false
        // Divider under the bar.
        accentPaint.color = theme.divider
        accentPaint.alpha = 90
        canvas.drawRect(dp(8f), barH - dp(0.5f), width - dp(8f), barH, accentPaint)
        accentPaint.alpha = 255

        // ── Keys ─────────────────────────────────────────────────────────
        val top = barH + dp(8f)
        val rowGap = dp(5f)
        val side = dp(4f)
        val keyH = dp(38 * heightScale)
        val gap = dp(3.5f)

        val rows = buildList {
            if (showNumberRow) add("1234567890".map { it.toString() to false })
            add("qwertyuiop".map { it.toString() to false })
            add("asdfghjkl".map { it.toString() to false })
            add(listOf("⇧" to true) + "zxcvbnm".map { it.toString() to false } + listOf("⌫" to true))
            add(buildList {
                add("?123" to true)
                if (showCommaKey) add("," to false)
                if (showApostropheKey) add("'" to false)
                if (showEmojiKey) add("☺" to true)
                if (showSelectionKey) add("⌶" to true)
                add("space" to false)
                add("." to false)
                add("⏎" to true)
            })
        }
        val weights = buildList {
            if (showNumberRow) add(List(10) { 1f })
            add(List(10) { 1f })
            add(List(9) { 1f })
            add(listOf(1.5f) + List(7) { 1f } + listOf(1.5f))
            add(buildList {
                add(1.4f)
                var used = 0f
                if (showCommaKey) { add(1f); used += 1f }
                if (showApostropheKey) { add(1f); used += 1f }
                if (showEmojiKey) { add(1f); used += 1f }
                if (showSelectionKey) { add(1f); used += 1f }
                add(3.6f + (4f - used))
                add(1f)
                add(1.6f)
            })
        }

        for (row in rows.indices) {
            val y = top + row * (keyH + rowGap)
            if (y + keyH > height) break
            val wts = weights[row]
            val total = wts.sum()
            val edge = if (row == (if (showNumberRow) 2 else 1)) width * 0.05f else 0f
            val usable = width - side * 2 - edge * 2 - gap * (wts.size - 1)
            var x = side + edge
            for (i in wts.indices) {
                val kw = usable * (wts[i] / total)
                rect.set(x, y, x + kw, y + keyH)
                val (label, functional) = rows[row][i]
                keyPaint.color = if (functional) theme.keyFunctional else theme.key
                canvas.drawRoundRect(rect, radius, radius, keyPaint)

                textPaint.color = theme.keyText ?: theme.text
                textPaint.textSize = dp(if (label.length > 2) 9f else 13f) * textScale
                canvas.drawText(
                    label, rect.centerX(),
                    rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2,
                    textPaint
                )
                x += kw + gap
            }
        }
    }
}
