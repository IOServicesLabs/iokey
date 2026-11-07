package io.ioservices.iokey.e2e

import android.os.SystemClock
import io.ioservices.iokey.e2e.harness.DeviceEnv
import io.ioservices.iokey.e2e.harness.E2eTestBase
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rotation replaces the keyboard's views while the service lives on. The quick emoji row is off by default, and
 * what the service remembered about the old row must not leave the new one empty.
 */
class EmojiRowRecreationTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private fun assertRowIsFilled(context: String) {
        kb.waitForShown()
        val texts = kb.visibleTextInImeWindows()
        assertTrue("$context: the quick row must be there, with its button to the full panel. The window holds: $texts", MORE_BUTTON in texts)
    }

    @Test
    fun quickRowIsFilledAgainAfterARotation() {
        ime.forceStop()
        ime.seedBooleanPrefs(mapOf(KEY_EMOJI_ROW to true))
        atEnd { ime.clearData() }
        ime.select()
        kb.show(HostActivity.CHAT)
        ime.waitIdle()
        assertRowIsFilled("before the rotation")

        env.setRotation(1)
        SystemClock.sleep(DeviceEnv.CONFIG_SETTLE_MS)
        assertRowIsFilled("in landscape")

        env.setRotation(0)
        SystemClock.sleep(DeviceEnv.CONFIG_SETTLE_MS)
        assertRowIsFilled("back in portrait")
    }

    private companion object {
        const val KEY_EMOJI_ROW = "emoji_row"
        const val MORE_BUTTON = "⋯"
    }
}
