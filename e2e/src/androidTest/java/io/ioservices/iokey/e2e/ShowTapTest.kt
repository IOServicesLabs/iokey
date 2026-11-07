package io.ioservices.iokey.e2e

import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The harness taps a field again when the tap landed as a long press, which a starved emulator can do to a 45 ms tap.
 * That second tap must be earned, because a tap repeated for any other reason could hide a keyboard that failed to
 * come up.
 */
class ShowTapTest : E2eTestBase() {

    /** Why the second tap exists: the field opens its menu, and the keyboard stays down until something else asks. */
    @Test
    fun aLongPressOnAnEmptyField_bringsNoKeyboard() {
        val field = host.fieldRect(HostActivity.CHAT)
        val longClicksBefore = host.fieldLongClicks()

        touch.press((field.right - 30).toFloat(), (field.bottom - 30).toFloat(), SURELY_A_LONG_PRESS_MS)

        assertEquals("The field must report the long press", longClicksBefore + 1, host.fieldLongClicks())
        assertFalse("A long press brought the keyboard up", Wait.until(NO_KEYBOARD_WAIT_MS, 100) { kb.isInputShown() })
        kb.show(HostActivity.CHAT)
    }

    @Test
    fun aTapThatLandsAsALongPress_isTappedAgainAndCounted() {
        kb.show(HostActivity.CHAT, firstHoldMs = SURELY_A_LONG_PRESS_MS)

        assertEquals(1, kb.tapsThatLandedAsLongPresses)
        kb.assertIokeyShowing()
    }

    /** Written so a stall cannot fail it: every touch past the first has to be one that was counted. */
    @Test
    fun aTapIsNeverRepeatedUncounted() {
        val touchesBefore = host.appTouchDowns()

        kb.show(HostActivity.CHAT)

        assertEquals(touchesBefore + 1 + kb.tapsThatLandedAsLongPresses, host.appTouchDowns())
    }

    private companion object {
        const val SURELY_A_LONG_PRESS_MS = 1_000L
        const val NO_KEYBOARD_WAIT_MS = 3_000L
    }
}
