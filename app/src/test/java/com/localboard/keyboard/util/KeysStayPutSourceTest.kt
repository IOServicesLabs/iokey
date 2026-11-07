package com.localboard.keyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads the source, because this promise is about what is absent. The view cannot run on a plain
 * JVM, and a test that only stores a fit and looks at the keys passes even with the old code in
 * place once the repair at start has removed the fit. So this checks directly that nothing learned
 * can reach the view's layout, and keeps the search for the old names from having to be done by hand.
 */
class KeysStayPutSourceTest {

    private val sourceRoot: File =
        listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    private val keyboardView: String =
        File(sourceRoot, "java/com/localboard/keyboard/ui/KeyboardView.kt").readText()

    /** The function's text, from its opening brace to the one that closes it. */
    private fun body(signature: String): String {
        val start = keyboardView.indexOf(signature)
        assertTrue("KeyboardView has no '$signature'", start >= 0)
        val open = keyboardView.indexOf('{', start)
        var depth = 0
        for (i in open until keyboardView.length) {
            when (keyboardView[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return keyboardView.substring(open, i + 1)
            }
        }
        throw AssertionError("'$signature' never closes")
    }

    @Test
    fun `the view lays keys out through the layout math and nothing else`() {
        val rects = body("private fun computeKeyRects()")
        assertTrue(rects.contains("KeyLayoutMath.layout("))
        assertTrue("every rect is a box as the math gave it", rects.contains("RectF(box.left, box.top, box.right, box.bottom)"))
        assertEquals("one place decides where rects come from", 1, Regex("RectF\\(").findAll(rects).count())
    }

    @Test
    fun `the only inputs to the math are the ones that may change where a key is drawn`() {
        val metrics = keyboardView.substringAfter("private fun layoutMetrics() = KeyLayoutMath.metrics(")
            .substringBefore("\n    )")
        val inputs = Regex("(\\w+) =").findAll(metrics).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("density", "viewWidthPx", "heightScale", "formFactor", "keySpacing", "oneHandedMode", "oneHandedWidth"),
            inputs
        )
    }

    @Test
    fun `nothing learned is named where keys are laid out or measured`() {
        val learned = Regex("(?i)offset|keyFit|touchModel|tapModel|learn|calibrat|prefs")
        for (signature in listOf("private fun computeKeyRects()", "override fun onMeasure(")) {
            val found = learned.find(body(signature))?.value
            assertFalse("'$signature' names '$found'", found != null)
        }
    }

    @Test
    fun `a touch is decided by the hit map alone`() {
        val find = body("private fun findKey(x: Float, y: Float)")
        assertTrue(find.contains("hitMap?.keyAt(x, y)"))
        assertFalse(Regex("(?i)offset|model").containsMatchIn(find))
    }

    private fun sources(): List<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "xml") && !it.path.contains("${File.separator}cpp${File.separator}") }
        .toList()

    @Test
    fun `the old code is gone by name`() {
        val gone = Regex("keyFit|keyOffset|TapLearningModel|tapModelData|recordTapLearning|MIN_KEY_FRACTION")
        val still = sources().filter { gone.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList<String>(), still)
    }

    @Test
    fun `only the repair still names the retired preferences`() {
        val names = Regex("key_layout_fit|tap_model_data|key_offset_x|key_offset_y|llm_suggestions_reset_v2")
        val naming = sources().filter { names.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("PrefsMigrations.kt"), naming)
    }
}
