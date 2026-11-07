package io.ioservices.iokey.e2e

import android.os.SystemClock
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-handed, the keys fill a narrower grid at one side and the rest of the width is empty. A tap still means the key
 * under it, the margins inside the grid and the strip at the docked screen edge still belong to the keys beside them,
 * and the empty part means nothing. In a password field nothing corrects what lands, so the field holds exactly what
 * the taps did.
 */
class OneHandedKeysTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val field = HostActivity.PASSWORD

    private fun startDocked(mode: Int): Keyboard.Layout {
        ime.forceStop()
        ime.writePrefsXml(fileDocked(mode))
        ime.select()
        showReady(field)
        return kb.layout()
    }

    /** What one tap added to the field. */
    private fun typedBy(x: Float, y: Float): String {
        val before = host.text(field)
        touch.tap(x, y)
        Wait.until(TAP_RESULT_MS, 20) { host.text(field) != before }
        SystemClock.sleep(Keyboard.TYPING_GAP_MS)
        return host.text(field).removePrefix(before).lowercase()
    }

    private fun assertTapsWhenDocked(mode: Int) {
        val keys = startDocked(mode)
        val letters = keys.faces.filter { it.label.length == 1 && it.label[0] in 'a'..'z' }
        val gridLeft = letters.minOf { it.rect.left }
        val gridRight = letters.maxOf { it.rect.right }
        val gutter = if (mode == DOCKED_RIGHT) 0..gridLeft else gridRight..keys.view.right
        check(gutter.last - gutter.first > MIN_GUTTER_PX) { "This is not docked: keys $gridLeft to $gridRight in ${keys.view}" }

        val wrong = ArrayList<String>()
        for (face in letters.sortedBy { it.label }) {
            val typed = typedBy(face.rect.exactCenterX(), face.rect.exactCenterY())
            if (typed != face.label) wrong += "${face.label} gave '$typed'"
        }
        assertTrue("The centre of each key must type it, and ${wrong.size} did not: $wrong", wrong.isEmpty())

        val a = keys.require("a").rect
        val l = keys.require("l").rect
        assertEquals("Just left of a, inside the grid", "a", typedBy((a.left - MARGIN_PX).toFloat(), a.exactCenterY()))
        assertEquals("Just right of l, inside the grid", "l", typedBy((l.right + MARGIN_PX).toFloat(), l.exactCenterY()))

        // The thin strip between the keys and the screen edge they are docked to is the outer keys', as at full width.
        val edgeX = if (mode == DOCKED_RIGHT) (gridRight + keys.view.right) / 2f else (keys.view.left + gridLeft) / 2f
        for (outer in if (mode == DOCKED_RIGHT) listOf("p", "l") else listOf("q", "a")) {
            val y = keys.require(outer).rect.exactCenterY()
            assertEquals("Beside $outer, at the docked edge", outer, typedBy(edgeX, y))
        }

        val before = host.text(field)
        val x = (gutter.first + gutter.last) / 2f
        for (face in listOf("q", "a", "z")) touch.tap(x, keys.require(face).rect.exactCenterY())
        SystemClock.sleep(TAP_RESULT_MS)
        assertEquals("A tap on the empty side must do nothing", before, host.text(field))
    }

    @Test
    fun dockedLeft_aTapMeansTheKeyUnderIt_andTheEmptySideNothing() = assertTapsWhenDocked(DOCKED_LEFT)

    @Test
    fun dockedRight_aTapMeansTheKeyUnderIt_andTheEmptySideNothing() = assertTapsWhenDocked(DOCKED_RIGHT)

    private companion object {
        const val DOCKED_RIGHT = 1
        const val DOCKED_LEFT = 2
        const val MIN_GUTTER_PX = 100
        const val MARGIN_PX = 2
        const val TAP_RESULT_MS = 1_000L

        fun fileDocked(mode: Int) = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <int name="geometry_schema" value="2" />
                <int name="one_handed_mode" value="$mode" />
                <float name="one_handed_width" value="0.82" />
                <boolean name="onboarding_done" value="true" />
                <boolean name="tutorial_seen" value="true" />
            </map>
        """.trimIndent() + "\n"
    }
}
