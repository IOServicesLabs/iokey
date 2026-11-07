package io.ioservices.iokey.e2e

import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Why a keyboard window can stay too short, on the framework of the device this runs on, with none of the keyboard's
 * code and nothing racing. The measures below are the ones a window of wrapped content gets from the system, in order:
 * at the display's height, then at the frame it was given, then at the display's height again on the next pass. A view
 * that changes size between the first two is lost if the system's own request is all that carries it, because that
 * request stops at the first parent already flagged and the last measure is answered from a cache.
 *
 * If the first test fails on some device, that device's framework does not have this bug and something else is making
 * its keyboard short. If the second fails, forcing the chain from the changed view up to the window is not enough there.
 */
@RunWith(AndroidJUnit4::class)
class MeasureCacheTest {

    private val width = View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY)

    private fun atMost(height: Int) = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)

    private class Window(val decor: FrameLayout, val strip: View)

    private fun newWindow(): Window {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val decor = FrameLayout(context)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        decor.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, KEYS))
        val strip = View(context)
        root.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, 0))
        return Window(decor, strip)
    }

    /** The strip takes its height after the first measure of a pass, while everything above it is still flagged. */
    private fun Window.growStripDuringThePass() {
        decor.measure(width, atMost(DISPLAY))
        assertEquals(KEYS, decor.measuredHeight)
        strip.layoutParams = strip.layoutParams.apply { height = STRIP }
    }

    /** The frame the window got from that first measure, and then the system's next pass. */
    private fun Window.finishThePassAndMeasureAgain(): Int {
        decor.measure(width, atMost(KEYS))
        decor.layout(0, 0, WIDTH, KEYS)
        decor.measure(width, atMost(DISPLAY))
        return decor.measuredHeight
    }

    private fun forceUpToWindow(from: View) {
        var top = from
        var parent = from.parent
        while (parent is View) {
            parent.forceLayout()
            top = parent
            parent = parent.parent
        }
        top.requestLayout()
    }

    @Test
    fun aChangeCarriedOnlyByItsOwnRequestIsLost() {
        val window = newWindow()
        window.growStripDuringThePass()
        assertEquals("The next pass answers from the cache, at the old height", KEYS, window.finishThePassAndMeasureAgain())
    }

    @Test
    fun forcingTheChainAtTheChangeKeepsIt() {
        val window = newWindow()
        window.growStripDuringThePass()
        forceUpToWindow(window.strip)
        assertEquals("The next pass must see the new height", KEYS + STRIP, window.finishThePassAndMeasureAgain())
    }

    /** A window already stuck, repaired later: what the keyboard does when it finds itself short. */
    @Test
    fun forcingTheChainLaterRepairsAStuckWindow() {
        val window = newWindow()
        window.growStripDuringThePass()
        assertEquals(KEYS, window.finishThePassAndMeasureAgain())
        forceUpToWindow(window.strip)
        window.decor.measure(width, atMost(DISPLAY))
        assertEquals("The repair must reach the window's height", KEYS + STRIP, window.decor.measuredHeight)
    }

    private companion object {
        const val WIDTH = 1080
        const val DISPLAY = 2316
        const val KEYS = 850
        const val STRIP = 68
    }
}
