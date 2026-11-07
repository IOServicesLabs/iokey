package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys drawn must be the keys you get, the same ones every time, whatever has been learned about the
 * user's fingers. Learned data is put in place by writing it to the debug build's preferences, in the format
 * the typing test stores, which is what several runs of that test leave behind.
 */
class KeysTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val field = HostActivity.CHAT

    private fun nominalKeys(): Keyboard.Layout {
        showReady(field)
        return kb.readLayout() ?: throw AssertionError("The keys cannot be read")
    }

    /** The keyboard stopped, [seed] applied to what it has stored, then started again as a fresh process. */
    private fun restartWith(seed: () -> Unit): Keyboard.Layout {
        kb.hideWithBack()
        ime.forceStop()
        seed()
        ime.select()
        return nominalKeys()
    }

    private fun assertSameKeys(expected: Keyboard.Layout, actual: Keyboard.Layout, what: String) {
        // The shift key announces its state in its label, and the state is not what is being compared.
        fun keyed(l: Keyboard.Layout) = l.faces.associate { it.label.substringBefore(',') to it.rect }
        val want = keyed(expected)
        val got = keyed(actual)
        assertEquals("$what: the same keys must be on the keyboard", want.keys, got.keys)
        val moved = want.filter { (label, rect) -> got[label] != rect }
            .map { (label, rect) -> "$label ${rect.toShortString()} to ${got[label]?.toShortString()}" }
        assertTrue("$what: ${moved.size} of ${want.size} keys are not where they belong: ${moved.take(MAX_LISTED)}", moved.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Learned data must not move what is drawn
    // ---------------------------------------------------------------------------------------------

    @Test
    fun learnedFit_leavesDrawnKeysAtNominal() {
        val nominal = nominalKeys()
        val after = restartWith { ime.seedStringPrefs(mapOf(KEY_FIT to TYPICAL_FIT)) }
        assertSameKeys(nominal, after, "after the typing test stored a typical fit")
    }

    @Test
    fun learnedFit_extremeValues_leavesDrawnKeysAtNominal() {
        val nominal = nominalKeys()
        val after = restartWith { ime.seedStringPrefs(mapOf(KEY_FIT to EXTREME_FIT)) }
        assertSameKeys(nominal, after, "after the typing test stored its largest offsets")
    }

    /**
     * With a fit stored, tapping where the keys belong must still give those keys. This fit's offsets cancel along
     * each row, so this holds even on a build that moves keys. It is a guard, and [learnedFit_neighboursMovedTogether_offCentreTapsTypeTheirKeys]
     * is the typing test that sees the keys move.
     */
    @Test
    fun learnedFit_nominalKeyCentresTypeTheirKeys() {
        val nominal = nominalKeys()
        restartWith { ime.seedStringPrefs(mapOf(KEY_FIT to EXTREME_FIT)) }
        kb.show(HostActivity.PASSWORD)
        val letters = nominal.faces.filter { it.label.length == 1 && it.label[0] in 'a'..'z' }
        for (face in letters) touchAndPause(face.rect.exactCenterX(), face.rect.exactCenterY())
        val expected = letters.joinToString("") { it.label }
        assertEquals("tapping the centre of where each key belongs", expected, host.text(HostActivity.PASSWORD).lowercase())
    }

    /** The middle of where a key belongs is that key, whatever has been learned. A guard, for the same reason as the test above. */
    @Test
    fun learnedFit_middleOfEveryKeyTypesThatKey() {
        val nominal = nominalKeys()
        restartWith { ime.seedStringPrefs(mapOf(KEY_FIT to EXTREME_FIT)) }
        assertMiddleOfEveryKeyTypesThatKey(nominal, "with a fit stored")
    }

    /**
     * The typing side of a fit that moves keys. An edge between two keys moves by the mean of their offsets, so where
     * neighbours agree the edges between them move the whole way, as they do for a finger with a steady habit. A point
     * well inside where a key belongs then lies inside its neighbour. The keys tapped are the ones whose near edge moved.
     */
    @Test
    fun learnedFit_neighboursMovedTogether_offCentreTapsTypeTheirKeys() {
        val nominal = nominalKeys()
        restartWith { ime.seedStringPrefs(mapOf(KEY_FIT to NEIGHBOURS_TOGETHER_FIT)) }
        val password = HostActivity.PASSWORD
        kb.show(password)
        val wrong = ArrayList<String>()
        for ((labels, side) in listOf("ert" to -1, "yu" to 1)) {
            for (label in labels.map { it.toString() }) {
                val r = nominal.require(label).rect
                val x = r.exactCenterX() + side * r.width() * OFF_CENTRE_WIDTH_FRACTION
                val result = whatTapDoes(x, r.exactCenterY(), password)
                if (result != label) wrong += "$label at x=$x (key ${r.left} to ${r.right}) gave $result"
                SystemClock.sleep(FACE_TAP_GAP_MS)
            }
        }
        assertTrue("With a fit stored, a tap well off centre on where a key belongs must type that key. ${wrong.size} did not: $wrong", wrong.isEmpty())
    }

    @Test
    fun savedKeyOffset_leavesDrawnKeysAtNominal() {
        val nominal = nominalKeys()
        val after = restartWith { ime.seedFloatPrefs(mapOf(KEY_OFFSET_X to 6f, KEY_OFFSET_Y to 6f)) }
        assertSameKeys(nominal, after, "after a key offset was stored")
    }

    @Test
    fun savedTapModel_leavesDrawnKeysAtNominal() {
        val nominal = nominalKeys()
        val after = restartWith { ime.seedStringPrefs(mapOf(TAP_MODEL to EXTREME_FIT)) }
        assertSameKeys(nominal, after, "after a tap model was stored")
    }

    @Test
    fun savedTapModel_keyCentresStillTypeTheirKeys() {
        val nominal = nominalKeys()
        restartWith { ime.seedStringPrefs(mapOf(TAP_MODEL to EXTREME_FIT)) }
        kb.show(HostActivity.PASSWORD)
        val letters = nominal.faces.filter { it.label.length == 1 && it.label[0] in 'a'..'z' }
        for (face in letters) touchAndPause(face.rect.exactCenterX(), face.rect.exactCenterY())
        val expected = letters.joinToString("") { it.label }
        assertEquals("tapping the centre of each key", expected, host.text(HostActivity.PASSWORD).lowercase())
    }

    @Test
    fun savedTapModel_middleOfEveryKeyTypesThatKey() {
        val nominal = nominalKeys()
        restartWith { ime.seedStringPrefs(mapOf(TAP_MODEL to EXTREME_FIT)) }
        assertMiddleOfEveryKeyTypesThatKey(nominal, "with a tap model stored")
    }

    // ---------------------------------------------------------------------------------------------
    // Run-to-run stability
    // ---------------------------------------------------------------------------------------------

    @Test
    fun keyBounds_stableAcrossShows() {
        val first = nominalKeys()
        repeat(3) { cycle ->
            kb.hideWithBack()
            kb.show(field)
            assertSameKeys(first, kb.layout(), "show number ${cycle + 2}")
        }
    }

    /** Through a plain show: the repair a short window gets elsewhere would hide the very jump this looks for. */
    @Test
    fun keyBounds_stableAcrossProcessRestart() {
        val first = nominalKeys()
        kb.hideWithBack()
        ime.killProcess()
        kb.show(field)
        assertFalse("The window must not come up short after a process restart: ${kb.windowState()}", kb.isShort())
        assertSameKeys(first, kb.layout(), "after a process restart")
        ime.waitIdle()
        kb.forgetLayout()
        assertSameKeys(first, kb.layout(), "after a process restart, once its start-up work ended")
    }

    @Test
    fun keyBounds_stableAcrossPageSwitch() {
        val first = nominalKeys()
        kb.tapKey(Keyboard.SYMBOLS)
        check(Wait.until(3_000, 60) { kb.readLayout()?.face(Keyboard.LETTERS) != null }) { "The symbols page did not open" }
        kb.forgetLayout()
        kb.tapKey(Keyboard.LETTERS)
        check(Wait.until(3_000, 60) { kb.readLayout()?.isLetters == true }) { "The letters did not come back" }
        kb.forgetLayout()
        assertSameKeys(first, kb.layout(), "back on the letters page")
    }

    // ---------------------------------------------------------------------------------------------
    // The stock geometry
    // ---------------------------------------------------------------------------------------------

    @Test
    fun stockLayout_rowsAreEvenAndKeysDoNotOverlap() {
        val keys = nominalKeys()
        val rows = keys.faces.groupBy { it.rect.top }.toSortedMap().values.toList()
        assertEquals("four rows of keys. Tops: ${keys.faces.map { it.rect.top }.distinct()}", 4, rows.size)
        for (row in rows) {
            assertEquals("every key of a row has the same height: $row", 1, row.map { it.rect.height() }.distinct().size)
            val ordered = row.sortedBy { it.rect.left }
            for ((a, b) in ordered.zipWithNext()) {
                assertTrue("${a.label} and ${b.label} overlap: ${a.rect} ${b.rect}", a.rect.right <= b.rect.left)
            }
        }
        for ((upper, lower) in rows.zipWithNext()) {
            assertTrue("rows overlap: ${upper[0].rect} ${lower[0].rect}", upper[0].rect.bottom <= lower[0].rect.top)
        }
        val widths = rows[0].map { it.rect.width() }
        assertTrue("the top row's keys are of equal width, to the rounding: $widths", widths.max() - widths.min() <= 1)
    }

    // ---------------------------------------------------------------------------------------------
    // Where a touch goes
    // ---------------------------------------------------------------------------------------------

    /** What one tap did to the field, in words a failure message can use. */
    private fun whatTapDoes(x: Float, y: Float, target: String): String {
        val before = host.text(target)
        touch.tap(x, y)
        Wait.until(TAP_RESULT_MS, 20) { host.text(target) != before }
        val after = host.text(target)
        return when {
            after == before -> "nothing, or the shift key"
            after.length == before.length + 1 && after.startsWith(before) -> after.last().lowercase()
            after.length < before.length -> "delete"
            else -> "changed the text to '$after'"
        }
    }

    private fun touchAndPause(x: Float, y: Float) {
        touch.tap(x, y)
        SystemClock.sleep(Keyboard.TYPING_GAP_MS)
    }

    /** Four points around the centre of each key, in the part of it that nothing learned may take away from it. */
    private fun assertMiddleOfEveryKeyTypesThatKey(nominal: Keyboard.Layout, what: String) {
        val password = HostActivity.PASSWORD
        kb.show(password)
        val wrong = ArrayList<String>()
        for (face in nominal.faces.filter { it.label.length == 1 && it.label[0] in 'a'..'z' }.sortedBy { it.label }) {
            val r = face.rect
            for (sideways in listOf(-1, 1)) {
                for (upDown in listOf(-1, 1)) {
                    val x = r.exactCenterX() + sideways * r.width() * MIDDLE_WIDTH_FRACTION
                    val y = r.exactCenterY() + upDown * r.height() * MIDDLE_HEIGHT_FRACTION
                    val result = whatTapDoes(x, y, password)
                    if (result != face.label) wrong += "${face.label} at ($x,$y) gave $result"
                    SystemClock.sleep(FACE_TAP_GAP_MS)
                }
            }
        }
        assertTrue("$what, the middle of where each key belongs must type that key. ${wrong.size} did not: ${wrong.take(MAX_LISTED)}", wrong.isEmpty())
    }

    /** The margin beside an outer key of the home row belongs to that key. No other key is anywhere near it. */
    private fun assertMarginBelongsTo(letter: String, xs: (Rect, Int) -> List<Int>) {
        val keys = nominalKeys()
        val key = keys.require(letter).rect
        val samples = xs(key, keys.view.right)
        check(samples.isNotEmpty()) { "The home row has no margin to test here: $key in ${keys.view}" }
        val wrong = ArrayList<String>()
        for (y in listOf(key.top + ROW_EDGE_INSET_PX, key.centerY(), key.bottom - ROW_EDGE_INSET_PX)) {
            for (x in samples) {
                val result = whatTapDoes(x.toFloat(), y.toFloat(), field)
                if (result != letter) wrong += "($x,$y) gave $result"
                SystemClock.sleep(Keyboard.TYPING_GAP_MS)
            }
        }
        assertTrue("A tap in the margin beside '$letter' (key $key) must type '$letter'. ${wrong.size} did not: $wrong", wrong.isEmpty())
    }

    @Test
    fun homeRowLeftMargin_typesA() = assertMarginBelongsTo("a") { key, _ ->
        (1 until key.left step MARGIN_STEP_PX).toList()
    }

    @Test
    fun homeRowRightMargin_typesL() = assertMarginBelongsTo("l") { key, right ->
        (key.right + 1 until right step MARGIN_STEP_PX).toList()
    }

    /** Every pixel of a key's face is that key: the centre and just inside each corner. */
    @Test
    fun everyLetterKey_wholeFaceTypesThatKey() {
        val password = HostActivity.PASSWORD
        showReady(password)
        val keys = kb.layout()
        val wrong = ArrayList<String>()
        for (face in keys.faces.filter { it.label.length == 1 && it.label[0] in 'a'..'z' }.sortedBy { it.label }) {
            val r = face.rect
            val points = listOf(
                r.exactCenterX() to r.exactCenterY(),
                r.left + CORNER_INSET_PX.toFloat() to r.top + CORNER_INSET_PX.toFloat(),
                r.right - CORNER_INSET_PX.toFloat() to r.top + CORNER_INSET_PX.toFloat(),
                r.left + CORNER_INSET_PX.toFloat() to r.bottom - CORNER_INSET_PX.toFloat(),
                r.right - CORNER_INSET_PX.toFloat() to r.bottom - CORNER_INSET_PX.toFloat(),
            )
            for ((x, y) in points) {
                val result = whatTapDoes(x, y, password)
                if (result != face.label) wrong += "${face.label} at ($x,$y) gave $result"
                SystemClock.sleep(FACE_TAP_GAP_MS)
            }
        }
        assertTrue("Every part of a key must type that key. ${wrong.size} did not: ${wrong.take(MAX_LISTED)}", wrong.isEmpty())
    }

    private companion object {
        const val KEY_FIT = "key_layout_fit"
        const val TAP_MODEL = "tap_model_data"
        const val KEY_OFFSET_X = "key_offset_x"
        const val KEY_OFFSET_Y = "key_offset_y"

        /** A finger that lands a little low and to the right, more so toward the far side, in dp. */
        const val TYPICAL_FIT =
            "q:-1.5,3.0;w:-1.0,3.5;e:-0.5,3.0;r:0.0,3.5;t:0.5,4.0;y:1.0,3.5;u:1.5,3.0;i:2.0,3.5;o:2.5,3.0;p:3.0,3.5;" +
                "a:-1.5,4.0;s:-1.0,4.5;d:-0.5,4.0;f:0.0,4.5;g:0.5,4.0;h:1.0,4.5;j:1.5,4.0;k:2.0,4.5;l:2.5,4.0;" +
                "z:-1.0,5.0;x:-0.5,5.5;c:0.0,5.0;v:0.5,5.5;b:1.0,5.0;n:1.5,5.5;m:2.0,5.0"

        /**
         * Neighbours pushed opposite ways by the most the typing test will store. An edge moves by the mean of the keys
         * on either side of it, so sideways this cancels along each row and only the rows move. It shows in where keys
         * are drawn, and not in which key a tap gives.
         */
        const val EXTREME_FIT =
            "q:10.0,10.0;w:-10.0,-10.0;e:10.0,10.0;r:-10.0,-10.0;t:10.0,10.0;y:-10.0,-10.0;u:10.0,10.0;i:-10.0,-10.0;o:10.0,10.0;p:-10.0,-10.0;" +
                "a:-10.0,10.0;s:10.0,-10.0;d:-10.0,10.0;f:10.0,-10.0;g:-10.0,10.0;h:10.0,-10.0;j:-10.0,10.0;k:10.0,-10.0;l:-10.0,10.0;" +
                "z:10.0,10.0;x:-10.0,-10.0;c:10.0,10.0;v:-10.0,-10.0;b:10.0,10.0;n:-10.0,-10.0;m:10.0,10.0"

        /** Sideways only, by the most the typing test will store, with neighbours moving the same way. */
        const val NEIGHBOURS_TOGETHER_FIT =
            "w:10.0,0.0;e:10.0,0.0;r:10.0,0.0;t:10.0,0.0;y:-10.0,0.0;u:-10.0,0.0;i:-10.0,0.0"

        /** On the face of the key, near its edge and further from the centre than the most a key's edge may move. */
        const val OFF_CENTRE_WIDTH_FRACTION = 0.40f

        const val MAX_LISTED = 14
        const val MARGIN_STEP_PX = 10
        const val ROW_EDGE_INSET_PX = 6
        const val CORNER_INSET_PX = 3
        const val TAP_RESULT_MS = 500L
        const val FACE_TAP_GAP_MS = 30L

        /** Each side of the centre, as a share of the key's size: just inside the middle half of its width and 60% of its height. */
        const val MIDDLE_WIDTH_FRACTION = 0.24f
        const val MIDDLE_HEIGHT_FRACTION = 0.28f
    }
}
