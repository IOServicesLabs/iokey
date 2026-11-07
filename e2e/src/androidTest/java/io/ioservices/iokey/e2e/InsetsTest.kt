package io.ioservices.iokey.e2e

import android.os.SystemClock
import android.view.KeyEvent
import io.ioservices.iokey.e2e.harness.DeviceEnv
import io.ioservices.iokey.e2e.harness.DeviceEnv.Companion.CONFIG_SETTLE_MS
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The keyboard must come up all the way, above the gesture bar, whatever happened while it was hidden.
 *
 * The stuck state this guards against has a signature: the window comes up short by the height of the
 * strip behind the navigation bar, so the whole keyboard sits that much too low, the system's bar draws
 * over the bottom row, and touches on the lower part of that row fall through to the app. It only shows on
 * the first show after the trigger, because the next hide and show repairs it, so every check here runs on
 * that first show and before anything else that could repair it.
 */
abstract class InsetsTestBase : E2eTestBase() {

    private val field = HostActivity.CHAT

    /**
     * Where the system draws its back and switch buttons inside the keyboard's window, they stand taller than
     * the navigation area. Whether the keys clear them too is tested at the bottom of this file. Here it is
     * enough that a build does the same in every state, so a healthy keyboard shows what this one does.
     */
    private var keepsClearOfSystemButtons = false

    private fun noteWhatThisBuildKeepsClear() {
        val screenHeight = host.windowSizePx().second
        val grid = kb.readLayout()?.view ?: throw AssertionError("The keys cannot be read")
        val buttons = kb.systemButtonsHeight(screenHeight)
        keepsClearOfSystemButtons = buttons > host.navigationBarBottomPx() && screenHeight - grid.bottom >= buttons
    }

    /** What the bottom of the window must hold back from the keys in the state the device is in right now. */
    private fun areaToKeepClear(): Int {
        val nav = host.navigationBarBottomPx()
        return if (keepsClearOfSystemButtons) maxOf(nav, kb.systemButtonsHeight(host.windowSizePx().second)) else nav
    }

    /** A healthy keyboard to start from. The warm show matters: a cold show may be what is under test. */
    private fun startFromCorrectKeyboard() {
        kb.show(field)
        ime.waitIdle()
        noteWhatThisBuildKeepsClear()
        kb.hideCompletely()
        kb.show(field)
        assertKeyboardFits("before the trigger")
    }

    /** The same on 3-button navigation. The process restart settles it, whatever the switch itself did. */
    private fun startFromCorrectKeyboardOnThreeButtons() {
        kb.show(field)
        ime.waitIdle()
        noteWhatThisBuildKeepsClear()
        kb.hideCompletely()
        env.setNavigation(DeviceEnv.Navigation.THREE_BUTTON)
        waitForConfigChange()
        ime.killProcess()
        kb.show(field)
        ime.waitIdle()
        kb.hideCompletely()
        kb.show(field)
        assertKeyboardFits("settled on 3-button navigation")
    }

    private fun assertKeyboardFits(context: String) {
        kb.assertIokeyShowing()
        val state = kb.windowState()
        val layout = kb.readLayout() ?: throw AssertionError("$context: the keys cannot be read")
        val clear = areaToKeepClear()
        val (_, screenHeight) = host.windowSizePx()
        val back = kb.systemBackButton()
        val facts = "window $state, key grid ${layout.view}, lowest key bottom ${layout.lowestKeyBottom}, " +
            "area to keep clear $clear px, navigation area ${host.navigationBarBottomPx()} px, " +
            "system back button ${back?.toShortString() ?: "none"} of height ${back?.height() ?: 0} px, screen height $screenHeight"

        assertEquals("$context: the window must reach the bottom of the screen. $facts", screenHeight, state.frame.bottom)
        assertEquals(
            "$context: the window height must be its content plus the area it keeps clear. $facts",
            layout.view.bottom - state.frame.top + clear, state.frame.height(),
        )
        assertEquals("$context: the requested height must match the frame. $facts", state.frame.height(), state.requestedHeight)
        assertTrue(
            "$context: the lowest keys must end above the area to keep clear, which starts at ${screenHeight - clear}. $facts",
            layout.lowestKeyBottom <= screenHeight - clear,
        )
        assertTrue(
            "$context: the touchable area must cover every key. $facts",
            state.touchable.bottom >= layout.lowestKeyBottom,
        )
        assertBottomRowTapReachesKeyboard(context, layout, facts)
    }

    /**
     * A tap near the lower edge of the space bar. It is the middle of the screen, clear of the system's
     * back and switcher buttons, so what it shows is purely whether the touch reaches the keyboard.
     */
    private fun assertBottomRowTapReachesKeyboard(context: String, layout: Keyboard.Layout, facts: String) {
        val space = layout.require(Keyboard.SPACE).rect
        val before = host.text(field)
        val touchesBefore = host.appTouchDowns()
        touch.tap(space.exactCenterX(), (space.bottom - LOWER_EDGE_INSET_PX).toFloat())
        val arrived = waitForFieldText(before + " ")
        val leaked = host.appTouchDowns() - touchesBefore
        assertEquals("$context: a tap on the lower part of the bottom row must not reach the app. $facts", 0, leaked)
        assertTrue("$context: a tap on the lower part of the bottom row must type a space. Text: '${host.text(field)}'. $facts", arrived)
    }

    private fun waitForFieldText(expected: String): Boolean = Wait.until(2_500) { host.text(field) == expected }

    private fun waitForConfigChange() = SystemClock.sleep(CONFIG_SETTLE_MS)

    // ---------------------------------------------------------------------------------------------
    // Negative controls: these are right today and must stay right
    // ---------------------------------------------------------------------------------------------

    @Test
    fun control_firstShowFits() {
        kb.show(field)
        ime.waitIdle()
        noteWhatThisBuildKeepsClear()
        assertKeyboardFits("first show")
    }

    @Test
    fun control_warmHideAndShowFits() {
        startFromCorrectKeyboard()
        repeat(3) { cycle ->
            kb.hideCompletely()
            kb.show(field)
            assertKeyboardFits("warm show $cycle")
        }
    }

    /** Comes back before the system takes the window down, which is a different path from the slower cycle above. */
    @Test
    fun control_rapidHideAndShowFits() {
        startFromCorrectKeyboard()
        repeat(RAPID_CYCLES) { cycle ->
            kb.hideWithBack()
            kb.show(field)
            assertKeyboardFits("rapid show $cycle")
        }
    }

    @Test
    fun control_darkModeWhileHiddenFits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setNightMode(true)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show in dark mode")
        kb.hideCompletely()
        env.setNightMode(false)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show back in light mode")
    }

    // ---------------------------------------------------------------------------------------------
    // Triggers reproduced by hand on this emulator
    // ---------------------------------------------------------------------------------------------

    /** Back with the rotation right behind it, before the system has taken the keyboard's window down. */
    private fun backThenFastRotateAndBack() {
        touch.key(KeyEvent.KEYCODE_BACK)
        env.setRotation(1)
        waitForConfigChange()
        env.setRotation(0)
        waitForConfigChange()
    }

    @Test
    fun backThenFastRotateAndBack_fits() {
        startFromCorrectKeyboard()
        backThenFastRotateAndBack()
        kb.show(field)
        assertKeyboardFits("first show after back, a fast rotation and rotating back")
    }

    @Test
    fun backThenFastRotateShownInLandscapeThenBackAgain_fits() {
        startFromCorrectKeyboard()
        touch.key(KeyEvent.KEYCODE_BACK)
        env.setRotation(1)
        waitForConfigChange()
        kb.show(field)
        kb.assertIokeyShowing()
        touch.key(KeyEvent.KEYCODE_BACK)
        env.setRotation(0)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show in portrait after showing in landscape")
    }

    @Test
    fun resolutionChangeWhileHidden_fitsAtHigherResolution() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setDisplay(1440, 3088, 600)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show at 1440x3088")
    }

    @Test
    fun resolutionChangeWhileHidden_fitsAtLowerResolution() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setDisplay(720, 1544, 300)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show at 720x1544")
    }

    /** The next show after a stuck one repairs it, so this show must be the first one after the reset. */
    @Test
    fun resolutionResetWhileHidden_fits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setDisplay(1440, 3088, 600)
        waitForConfigChange()
        kb.show(field)
        kb.assertIokeyShowing()
        kb.hideCompletely()
        env.resetDisplay()
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show after resetting the resolution")
    }

    @Test
    fun switchGestureToThreeButtons_fits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setNavigation(DeviceEnv.Navigation.THREE_BUTTON)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show after switching to 3-button navigation")
    }

    @Test
    fun switchThreeButtonsToGesture_fits() {
        startFromCorrectKeyboardOnThreeButtons()
        kb.hideCompletely()
        env.setNavigation(DeviceEnv.Navigation.GESTURE)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show after switching back to gesture navigation")
    }

    @Test
    fun rotateWhileHiddenAndBack_fits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setRotation(1)
        waitForConfigChange()
        env.setRotation(0)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show after rotating away and back while hidden")
    }

    // ---------------------------------------------------------------------------------------------
    // Triggers the code reading predicted. They did not reproduce by hand on this emulator, but may on the
    // phone, whose framework differs.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun processKilledWhileHidden_fits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        ime.killProcess()
        kb.show(field)
        ime.waitIdle()
        assertKeyboardFits("first show after the process was killed while hidden")
    }

    @Test
    fun processKilledWhileShown_fits() {
        startFromCorrectKeyboard()
        ime.killProcess()
        // The system brings the keyboard back by itself.
        kb.waitForShown()
        ime.waitIdle()
        assertKeyboardFits("after the process was killed while showing")
    }

    @Test
    fun forceStopAndReselect_fits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        ime.forceStop()
        ime.select()
        kb.show(field)
        ime.waitIdle()
        assertKeyboardFits("first show after force-stop and reselecting")
    }

    /**
     * The same route, read the moment the keys are up. The test above lets the start-up work end first, and anything
     * that work does to the window would hide a first show that came up short.
     */
    @Test
    fun forceStopAndReselect_fitsBeforeStartUpEnds() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        ime.forceStop()
        ime.select()
        kb.show(field)
        assertKeyboardFits("first show after force-stop and reselecting, before its start-up work ended")
    }

    /**
     * Short windows arrive by themselves on the first show after a data wipe, in the tests that start with one. So this
     * goes through what those tests go through: the host closing under the keyboard, a wipe, a new host window, and a
     * look once the start-up work has ended. It comes up short only some of the time, so one round proves little. The
     * height at the show itself goes in the results, to say which of the two moments it came up short at.
     */
    @Test
    fun dataWipedAsTheHostCloses_fitsEveryTime() {
        repeat(WIPE_ROUNDS) { round ->
            startFromCorrectKeyboard()
            host.close()
            ime.clearData()
            ime.select()
            host.launch(hostClass)
            kb.show(field)
            record("windowHeightAtShow$round", kb.windowState().frame.height())
            ime.waitIdle()
            assertKeyboardFits("first show after a data wipe, round $round")
        }
    }

    /**
     * The test above without the wipe. A phone never wipes the keyboard's data, but it can end the process just as an
     * app closes, when memory is tightest. So this says whether that route can bring a short window on a real device.
     */
    @Test
    fun processKilledAsTheHostCloses_fitsEveryTime() {
        repeat(WIPE_ROUNDS) { round ->
            startFromCorrectKeyboard()
            host.close()
            ime.killProcess()
            host.launch(hostClass)
            kb.show(field)
            record("windowHeightAtShow$round", kb.windowState().frame.height())
            ime.waitIdle()
            assertKeyboardFits("first show after the process was killed as the host closed, round $round")
        }
    }

    /** The wipe again, with the keyboard fully down first, so that what this adds to the wipe is the only difference. */
    @Test
    fun dataWipedWhileHidden_fitsEveryTime() {
        repeat(WIPE_ROUNDS) { round ->
            startFromCorrectKeyboard()
            kb.hideCompletely()
            host.close()
            ime.clearData()
            ime.select()
            host.launch(hostClass)
            kb.show(field)
            record("windowHeightAtShow$round", kb.windowState().frame.height())
            ime.waitIdle()
            assertKeyboardFits("first show after a data wipe with the keyboard already hidden, round $round")
        }
    }

    @Test
    fun rotateWhileHiddenShownInLandscape_fits() {
        startFromCorrectKeyboard()
        kb.hideCompletely()
        env.setRotation(1)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show in landscape after rotating while hidden")
    }

    /** An app with the system's bars hidden. The gesture area is still there, and the keyboard must keep clear of it. */
    @Test
    fun immersiveApp_forceStopAndReselect_fits() {
        env.skipFullScreenExplanation()
        host.launch(ImmersiveHostActivity::class.java)
        startFromCorrectKeyboard()
        kb.hideCompletely()
        ime.forceStop()
        ime.select()
        kb.show(field)
        ime.waitIdle()
        assertKeyboardFits("first show after force-stop under an app with the bars hidden")
    }

    @Test
    fun threeButtons_forceStopAndReselect_fits() {
        startFromCorrectKeyboardOnThreeButtons()
        kb.hideCompletely()
        ime.forceStop()
        ime.select()
        kb.show(field)
        ime.waitIdle()
        assertKeyboardFits("first show after force-stop and reselecting on 3-button navigation")
    }

    @Test
    fun threeButtons_rotateWhileHiddenShownInLandscape_fits() {
        startFromCorrectKeyboardOnThreeButtons()
        kb.hideCompletely()
        env.setRotation(1)
        waitForConfigChange()
        kb.show(field)
        assertKeyboardFits("first show in landscape after rotating while hidden on 3-button navigation")
    }

    /** Rotating with the keyboard up races its own redraw, so one round proves little. */
    @Test
    fun rotateWhileShowing_fitsEveryTime() {
        startFromCorrectKeyboard()
        repeat(ROTATION_ROUNDS) { cycle ->
            env.setRotation(1)
            waitForConfigChange()
            kb.waitForShown()
            env.setRotation(0)
            waitForConfigChange()
            kb.waitForShown()
            assertKeyboardFits("after rotating there and back while showing, cycle $cycle")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The bottom row itself
    // ---------------------------------------------------------------------------------------------

    private fun assertSymbolsPageOpens(context: String) {
        val opened = Wait.until(2_500, 100) { kb.readLayout()?.let { it.face(Keyboard.LETTERS) != null && !it.isLetters } == true }
        assertTrue(
            "$context: the symbols page must open. Keyboard shown ${kb.isInputShown()}, host has focus ${host.hasWindowFocus()}, " +
                "keys ${kb.readLayout()?.faces?.map { it.label }}",
            opened,
        )
        kb.settle()
    }

    /** The keyboard must not change height between pages: a jump here is the stuck state being repaired. */
    @Test
    fun symbolsPageKeepsTheSameWindowHeight() {
        startFromCorrectKeyboard()
        val letters = kb.windowState()
        kb.tapKey(Keyboard.SYMBOLS)
        assertSymbolsPageOpens("from a healthy window")
        assertEquals("The window must keep its height on the symbols page", letters.frame, kb.windowState().frame)
    }

    /**
     * What the test above cannot see, because it starts healthy. A page change lays everything out again, so from the
     * stuck window the whole keyboard jumps up. It tells the user the state was there, and with no stuck window to come
     * from there is no jump. The tap is on the upper part of the key, which the system's back button leaves uncovered
     * even when the keyboard sits too low.
     */
    @Test
    fun symbolsPageFromTheStuckWindow_keepsTheSameWindowHeight() {
        startFromCorrectKeyboard()
        backThenFastRotateAndBack()
        kb.show(field)
        val before = kb.windowState()
        record("windowHeightBeforeSymbols", before.frame.height())
        val key = kb.layout().require(Keyboard.SYMBOLS).rect
        touch.tap(key.exactCenterX(), (key.top + UPPER_PART_INSET_PX).toFloat())
        assertSymbolsPageOpens("after back, a fast rotation and rotating back")
        val after = kb.windowState()
        record("windowHeightOnSymbols", after.frame.height())
        assertEquals("The window must keep its height when the symbols page opens. Before: $before, after: $after", before.frame, after.frame)
    }

    /**
     * The bottom row must take a tap on its lower edge across its whole width. The system draws its back
     * and keyboard-switch buttons inside the keyboard window near both ends of this row.
     */
    private fun assertLowerEdgeTypes(label: String, char: String) {
        startFromCorrectKeyboard()
        val r = kb.layout().require(label).rect
        val before = host.text(field)
        touch.tap(r.exactCenterX(), (r.bottom - LOWER_EDGE_INSET_PX).toFloat())
        assertTrue(
            "A tap on the lower edge of '$label' must type it. Text '${host.text(field)}', keyboard shown ${kb.isInputShown()}, " +
                "host has focus ${host.hasWindowFocus()}",
            waitForFieldText(before + char),
        )
    }

    @Test
    fun bottomRowLowerEdge_comma() = assertLowerEdgeTypes(Keyboard.COMMA, ",")

    @Test
    fun bottomRowLowerEdge_period() = assertLowerEdgeTypes(Keyboard.PERIOD, ".")

    @Test
    fun bottomRowLowerEdge_enter() = assertLowerEdgeTypes(Keyboard.ENTER, "\n")

    @Test
    fun bottomRowLowerEdge_symbolsKey() {
        startFromCorrectKeyboard()
        val r = kb.layout().require(Keyboard.SYMBOLS).rect
        touch.tap(r.exactCenterX(), (r.bottom - LOWER_EDGE_INSET_PX).toFloat())
        val switched = Wait.until(2_500, 100) { kb.readLayout()?.face(Keyboard.LETTERS) != null }
        assertTrue(
            "A tap on the lower edge of the symbols key must open the symbols page. Keyboard shown ${kb.isInputShown()}, " +
                "host has focus ${host.hasWindowFocus()}",
            switched,
        )
    }

    /**
     * The tallest keyboard the settings allow, on the shortest screen it can meet. It must shrink to fit, because
     * a keyboard taller than the screen loses its bottom rows, which are the ones with the space bar. Shown a
     * second time so that this is about the height alone and not about the first show after a rotation.
     */
    @Test
    fun tallestKeyboardInLandscape_keepsEveryRowWhole() {
        kb.show(field)
        ime.waitIdle()
        kb.hideCompletely()
        val stored = ime.readPrefsXml()
        atEnd {
            ime.forceStop()
            if (stored != null) ime.writePrefsXml(stored) else ime.clearData()
        }
        ime.forceStop()
        ime.seedFloatPrefs(mapOf(KEY_HEIGHT_SCALE to MAX_HEIGHT_SCALE))
        ime.seedBooleanPrefs(mapOf(KEY_NUMBER_ROW to true))
        ime.select()
        env.setRotation(1)
        waitForConfigChange()
        kb.show(field)
        ime.waitIdle()
        kb.hideCompletely()
        kb.show(field)
        kb.assertIokeyShowing()

        val layout = kb.readLayout() ?: throw AssertionError("The keys cannot be read")
        val rows = listOf(NUMBER_ROW_KEY, "q", "a", "z", Keyboard.SPACE).associateWith { layout.require(it).rect }
        val tallest = rows.values.maxOf { it.height() }
        val cut = rows.filterValues { it.height() < tallest * WHOLE_ROW_FRACTION }
        val nav = host.navigationBarBottomPx()
        val (_, screenHeight) = host.windowSizePx()
        val facts = "window ${kb.windowState()}, one key from each row $rows, navigation area $nav px, screen height $screenHeight"
        assertTrue("Every row must be whole, but ${cut.keys} are cut. $facts", cut.isEmpty())
        assertTrue(
            "The lowest keys must end above the navigation area, which starts at ${screenHeight - nav}, " +
                "but they end at ${layout.lowestKeyBottom}. $facts",
            layout.lowestKeyBottom <= screenHeight - nav,
        )
    }

    /** The keyboard stopped, [seed] applied, then its first show since then, sideways. */
    private fun firstShowInLandscape(seed: () -> Unit): Keyboard.Layout {
        kb.show(field)
        ime.waitIdle()
        kb.hideCompletely()
        val stored = ime.readPrefsXml()
        atEnd {
            ime.forceStop()
            if (stored != null) ime.writePrefsXml(stored) else ime.clearData()
        }
        ime.forceStop()
        seed()
        ime.select()
        env.setRotation(1)
        waitForConfigChange()
        kb.show(field)
        kb.assertIokeyShowing()
        return kb.readLayout() ?: throw AssertionError("The keys cannot be read")
    }

    private fun nominalLandscapeKeyPx(): Int {
        val density = host.onMain { it.resources.displayMetrics.density }
        return (NOMINAL_KEY_DP * LANDSCAPE_FORM_FACTOR * density).roundToInt()
    }

    private fun assertLowestKeyInsideTheLimit(layout: Keyboard.Layout) {
        val nav = host.navigationBarBottomPx()
        val (_, screenHeight) = host.windowSizePx()
        assertTrue(
            "The lowest keys must end above the navigation area, which starts at ${screenHeight - nav}, " +
                "but they end at ${layout.lowestKeyBottom}. Window ${kb.windowState()}, screen height $screenHeight",
            layout.lowestKeyBottom <= screenHeight - nav,
        )
    }

    /** The height cap is for boards that do not fit. One that does keeps the keys it always had, from its first show. */
    @Test
    fun defaultKeyboardInLandscape_keepsNominalKeys() {
        val layout = firstShowInLandscape { }
        assertEquals("A board that fits keeps its key height", nominalLandscapeKeyPx(), layout.require("q").rect.height())
    }

    /** The quick row takes room from the same window, and the keys give up just the few pixels it runs over by. */
    @Test
    fun quickRowInLandscape_losesAtMostAFewPixels() {
        val layout = firstShowInLandscape { ime.seedBooleanPrefs(mapOf(KEY_EMOJI_ROW to true)) }
        val nominal = nominalLandscapeKeyPx()
        val height = layout.require("q").rect.height()
        record("keyHeight", height)
        assertTrue(
            "With the quick row the keys must stay within $QUICK_ROW_LOSS_PX px of the $nominal px they have without it, " +
                "but they are $height px",
            height in (nominal - QUICK_ROW_LOSS_PX)..nominal,
        )
        assertLowestKeyInsideTheLimit(layout)
    }

    /** [tallestKeyboardInLandscape_keepsEveryRowWhole] reads the second show. The first has to fit as well, with no second layout to rescue it. */
    @Test
    fun tallestKeyboardInLandscape_firstShowFitsToo() {
        val layout = firstShowInLandscape {
            ime.seedFloatPrefs(mapOf(KEY_HEIGHT_SCALE to MAX_HEIGHT_SCALE))
            ime.seedBooleanPrefs(mapOf(KEY_NUMBER_ROW to true))
        }
        record("keyHeight", layout.require("q").rect.height())
        assertLowestKeyInsideTheLimit(layout)
    }

    private companion object {
        /** KeyLayoutMath's default base, in dp. The harness has no dependency on :app, so this
         *  one literal is recomputed from the app's KeyLayoutMath default (40dp) rather than read. */
        const val NOMINAL_KEY_DP = 40
        const val LANDSCAPE_FORM_FACTOR = 0.85f
        const val KEY_EMOJI_ROW = "emoji_row"
        const val QUICK_ROW_LOSS_PX = 3
        const val LOWER_EDGE_INSET_PX = 3
        const val UPPER_PART_INSET_PX = 12
        const val WIPE_ROUNDS = 5
        const val RAPID_CYCLES = 10
        const val ROTATION_ROUNDS = 5
        const val KEY_HEIGHT_SCALE = "height_scale"
        const val MAX_HEIGHT_SCALE = 1.5f
        const val KEY_NUMBER_ROW = "number_row"
        const val NUMBER_ROW_KEY = "1"
        const val WHOLE_ROW_FRACTION = 0.9f
    }
}

/** The keyboard under an app window that handles configuration changes itself. */
class InsetsTest : InsetsTestBase()

/** The keyboard under an app window the framework recreates on a configuration change, as most apps are. */
class InsetsRecreatedWindowTest : InsetsTestBase() {
    override val hostClass = RecreatingHostActivity::class.java
}
