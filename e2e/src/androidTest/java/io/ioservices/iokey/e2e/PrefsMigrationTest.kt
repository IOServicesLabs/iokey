package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an update does to the settings an older build left behind, through the real start of the app and the real
 * preferences file. The file is the one such a build stores after a few runs of its typing test, and the process is
 * stopped while it goes in, so each start here is what the first start after an update is. The glide reset is here too,
 * because the update's repair and the reset are the two ways old glide corrections leave.
 */
class PrefsMigrationTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val shell get() = E2eDevice.shell

    private fun runAs(script: String) = shell.sh("run-as ${ime.pkg} sh -c '$script'")

    private fun startOn(xml: String) {
        ime.forceStop()
        ime.writePrefsXml(xml)
        ime.select()
        showReady(chat)
    }

    /** A new process over what is stored now, with [seed] applied while nothing is running. */
    private fun restart(seed: () -> Unit = {}) {
        kb.hideWithBack()
        ime.forceStop()
        seed()
        ime.select()
        showReady(chat)
    }

    private fun stored(): String = ime.readPrefsXml() ?: throw AssertionError("The keyboard has no preferences file")

    private fun holds(xml: String, name: String) = xml.contains("name=\"$name\"")

    private fun assertRepaired(xml: String) {
        for (name in RETIRED) assertFalse("$name must be gone from:\n$xml", holds(xml, name))
        assertTrue("The stamp must be on disk:\n$xml", xml.contains("<int name=\"geometry_schema\" value=\"2\" />"))
        assertTrue("The old strip option must become the default:\n$xml", xml.contains("<int name=\"nav_strip_mode\" value=\"0\" />"))
        assertFalse("The old glide bias must be gone:\n$xml", holds(xml, "swipe_bias_data"))
        assertFalse("The old flag must be gone:\n$xml", holds(xml, "llm_suggestions_reset_v2"))
    }

    @Test
    fun oldFile_isRepairedAtStart_andASecondStartLeavesItAlone() {
        startOn(OLD_FILE)

        val repaired = stored()
        assertRepaired(repaired)
        assertTrue("Moved keys came back, so the notice is due:\n$repaired", repaired.contains(NOTICE_DUE))
        assertTrue("The person's own choice must stay:\n$repaired", repaired.contains("<boolean name=\"llm_suggestions\" value=\"true\" />"))
        assertTrue("The person's own spacing must stay:\n$repaired", repaired.contains("<float name=\"key_spacing\" value=\"1.2\" />"))

        // Bias in the form the keyboard still writes, as if it was learned after the update: only a repair that runs
        // twice would take it.
        restart { ime.seedStringPrefs(mapOf("swipe_bias_data" to "you\t-1")) }

        val again = stored()
        assertRepaired(again.replace(Regex("""\s*<string name="swipe_bias_data">.*?</string>""", RegexOption.DOT_MATCHES_ALL), ""))
        assertTrue("A second start must not take what was learned since:\n$again", holds(again, "swipe_bias_data"))
        assertTrue("A second start must not use up the notice:\n$again", again.contains(NOTICE_DUE))
    }

    /** The folder refuses every write, so the repair's commit fails the way it does on a full or failing disk. */
    @Test
    fun aRepairThatCannotWrite_leavesTheKeyboardUp_andTheNextStartFinishesIt() {
        ime.forceStop()
        ime.writePrefsXml(OLD_FILE)
        runAs("chmod 500 shared_prefs")
        atEnd { runAs("chmod 771 shared_prefs") }
        val refused = runAs("touch shared_prefs/probe 2>&1 && echo written || echo refused")
        check(refused.contains("refused")) { "The folder still takes writes, so this test would prove nothing: $refused" }
        ime.select()
        showReady(chat)
        val pid = ime.pid()

        kb.type("see you ")
        assertTrue(
            "Typing must go on with the repair not written, but the field holds '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).lowercase().endsWith("see you ") },
        )
        assertEquals("The keyboard must not die over it", pid, ime.pid())
        assertTrue("Nothing can have reached the disk:\n${stored()}", holds(stored(), "key_layout_fit"))

        restart { runAs("chmod 771 shared_prefs") }

        assertRepaired(stored())
        assertTrue("The repair that finished late still owes the notice:\n${stored()}", stored().contains(NOTICE_DUE))
    }

    private fun find(node: AccessibilityNodeInfo, tappable: Boolean, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        if (node.text?.toString()?.let(matches) == true && (node.isClickable || !tappable)) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> find(c, tappable, matches)?.let { return it } }
        }
        return null
    }

    private fun findOnScreen(tappable: Boolean = false, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg } ?: return null
        return find(root, tappable, matches)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val r = Rect()
        node.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
    }

    /** Only something that takes a tap, because a dialog's title can read the same as its button. */
    private fun tapText(what: String, matches: (String) -> Boolean) {
        var found: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { found = findOnScreen(tappable = true, matches = matches); found != null }) { "Nothing on screen reads $what" }
        tap(found!!)
    }

    /** Through the app's own launcher screen and its button, since the settings screen has no other way in. */
    private fun openSettings() {
        shell.exec("am start -n ${ime.pkg}/$ONBOARDING")
        tapText("'$OPEN_SETTINGS_LABEL'") { it.equals(OPEN_SETTINGS_LABEL, ignoreCase = true) }
    }

    @Test
    fun theNoticeShowsOnceInSettings_andThenNeverAgain() {
        startOn(OLD_FILE)
        kb.hideWithBack()

        openSettings()
        assertTrue(
            "The notice must say what happened, and it is not on the screen",
            Wait.until(SCREEN_MS, 200) { findOnScreen { NOTICE_TEXT in it } != null },
        )
        assertTrue(
            "Showing the notice must use it up:\n${stored()}",
            Wait.until(SCREEN_MS, 200) { !holds(stored(), "keys_reset_notice") },
        )
        tapText("'Done'") { it.equals("Done", ignoreCase = true) }
        assertTrue(
            "Done must close the notice",
            Wait.until(SCREEN_MS, 200) { findOnScreen { SETTINGS_SUBTITLE in it } != null },
        )

        // A new process builds the screen again and asks again, which is the only way the notice could come back.
        ime.forceStop()
        openSettings()
        assertTrue(
            "The settings screen must come up",
            Wait.until(SCREEN_MS, 200) { findOnScreen { SETTINGS_SUBTITLE in it } != null },
        )
        assertFalse(
            "The notice must not come back",
            Wait.until(NO_NOTICE_MS, 200) { findOnScreen { NOTICE_TEXT in it } != null },
        )
    }

    /** The settings screen is long, so this brings the button up before anything taps it, as a person would. */
    private fun scrollTo(label: String) {
        var found: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { found = findOnScreen(tappable = true) { it == label }; found != null }) { "The settings screen has no '$label'" }
        found!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        Wait.untilStable(SCREEN_MS, 200) { Rect().also { r -> findOnScreen(tappable = true) { it == label }?.getBoundsInScreen(r) } }
    }

    /** A glide wiped straight away counts as a correction. Returns the word once the keyboard has saved that. */
    private fun wipeAGlide(): String {
        kb.glide(GLIDED_WORD)
        assertTrue("The glide must type something", Wait.until(SCREEN_MS, 100) { host.text(chat).isNotBlank() })
        val glided = Wait.untilStable(GLIDE_SETTLE_MS, 150) { host.text(chat) }.trim().lowercase()
        kb.backspace()
        assertTrue(
            "Wiping '$glided' straight after its glide must save the corrections:\n${stored()}",
            Wait.until(BIAS_SAVE_MS, 200) { glided in stored().lowercase() },
        )
        return glided
    }

    /**
     * The control: a save writes all the keyboard holds in place of what was stored, so the old word can only still be
     * there because it was in memory. Without this, the reset test below could pass on a keyboard that never held it.
     */
    @Test
    fun withNoReset_theKeyboardSavesTheOldWordAgain() {
        startOn(REPAIRED_FILE)

        wipeAGlide()

        assertTrue("The file's word must be in the keyboard's memory:\n${stored()}", OLD_BIAS_WORD in stored())
    }

    /**
     * The keyboard holds its glide corrections in memory as well, and each new one saves all that it holds. So a reset
     * has to reach that memory or the old words come back. This process started over the file, so the old word is in its
     * memory, and wiping a glide at the end is what makes it save.
     */
    @Test
    fun resetGlideLearning_isNotUndoneByTheKeyboardStillRunning() {
        startOn(REPAIRED_FILE)
        val pid = ime.pid()
        kb.hideWithBack()

        openSettings()
        scrollTo(GLIDE_RESET_LABEL)
        tapText("'$GLIDE_RESET_LABEL' on the settings screen") { it == GLIDE_RESET_LABEL }
        assertTrue(
            "The reset must ask first and say what it forgets",
            Wait.until(SCREEN_MS, 200) { findOnScreen { GLIDE_RESET_QUESTION in it } != null },
        )
        tapText("'$GLIDE_RESET_LABEL' in the dialog") { it == GLIDE_RESET_LABEL }
        assertTrue(
            "The stored corrections must go at once, and the keyboard be told:\n${stored()}",
            Wait.until(SCREEN_MS, 200) { stored().let { OLD_BIAS_WORD !in it && GLIDE_RESET_PENDING in it } },
        )

        host.bringToFront()
        kb.show(chat)
        ime.waitIdle()
        assertEquals("All this must be one process, or the keyboard's memory was not what was tested", pid, ime.pid())
        assertTrue(
            "Showing the keyboard must use the request up:\n${stored()}",
            Wait.until(SCREEN_MS, 200) { GLIDE_RESET_PENDING !in stored() },
        )

        wipeAGlide()

        assertFalse("The reset word came back from the keyboard's memory:\n${stored()}", OLD_BIAS_WORD in stored())
    }

    private fun findDescribed(node: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> findDescribed(c, description)?.let { return it } }
        }
        return null
    }

    /** The system's own file picker. It opens on whichever folder it likes, and the file is listed under Downloads. */
    private fun pickFile(name: String) {
        var file: AccessibilityNodeInfo? = null
        val listed = Wait.until(SCREEN_MS, 500) {
            E2eDevice.ua.clearCache()
            val root = E2eDevice.ua.rootInActiveWindow ?: return@until false
            file = find(root, false) { it == name }
            if (file == null) (find(root, false) { it == "Downloads" } ?: findDescribed(root, "Show roots"))?.let(::tap)
            file != null
        }
        check(listed) { "The file picker does not list $name" }
        tap(file!!)
    }

    /**
     * A file as an older build wrote it, through the screen's own button and the system's picker. What stays out is
     * filtered on the way in and repaired after, and this is the only test that runs the two together on the real file.
     */
    @Test
    fun anOldBackupRestored_doesNotBringTheOldFitBack() {
        shell.sh("cat > $BACKUP_PATH <<'E2E_EOF'\n$OLD_BACKUP\nE2E_EOF\n")
        atEnd { shell.exec("rm -f $BACKUP_PATH") }
        startOn(REPAIRED_FILE)
        kb.hideWithBack()

        openSettings()
        scrollTo(DATA_CONTROLS_LABEL)
        tapText("'$DATA_CONTROLS_LABEL'") { it == DATA_CONTROLS_LABEL }
        scrollTo(RESTORE_LABEL)
        tapText("'$RESTORE_LABEL'") { it == RESTORE_LABEL }
        pickFile(BACKUP_NAME)
        assertTrue(
            "The restore must say the file is from an older version",
            Wait.until(SCREEN_MS, 200) { findOnScreen { OLDER_BACKUP_TEXT in it } != null },
        )
        tapText("'Restore'") { it == "Restore" }

        assertTrue(
            "The backup's own settings must arrive, or nothing was restored:\n${stored()}",
            Wait.until(SCREEN_MS, 200) { RESTORED_SPACING in stored() },
        )
        val restored = stored()
        assertRepaired(restored)
        assertFalse("No key moved back, so there is nothing to announce:\n$restored", holds(restored, "keys_reset_notice"))

        restart { host.bringToFront() }

        val later = stored()
        assertRepaired(later)
        assertTrue("A later start must leave what was restored:\n$later", RESTORED_SPACING in later)
    }

    private companion object {
        val RETIRED = listOf("key_layout_fit", "tap_model_data", "key_offset_x", "key_offset_y")
        const val NOTICE_DUE = "<boolean name=\"keys_reset_notice\" value=\"true\" />"
        const val NOTICE_TEXT = "back in their standard positions"
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS_LABEL = "Open Settings"
        const val SETTINGS_SUBTITLE = "Make IOKey yours"
        const val SCREEN_MS = 20_000L
        const val NO_NOTICE_MS = 3_000L
        const val GLIDE_RESET_LABEL = "Reset glide learning"
        const val GLIDE_RESET_QUESTION = "Forgets which glide results"
        const val GLIDE_RESET_PENDING = "<boolean name=\"swipe_bias_reset_pending\" value=\"true\" />"
        const val OLD_BIAS_WORD = "banana"
        const val GLIDED_WORD = "hello"
        const val GLIDE_SETTLE_MS = 3_000L
        const val BIAS_SAVE_MS = 12_000L
        const val DATA_CONTROLS_LABEL = "Data and network controls"
        const val RESTORE_LABEL = "Restore from a file\u2026"
        const val OLDER_BACKUP_TEXT = "older version of IOKey"
        const val RESTORED_SPACING = "<float name=\"key_spacing\" value=\"1.3\" />"
        const val BACKUP_NAME = "iokey-test-old-backup.json"
        const val BACKUP_PATH = "/sdcard/Download/$BACKUP_NAME"
        const val TEXT_MS = 1_000L

        /** Invented values, in the form and types an older build stores them. */
        val OLD_FILE = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="key_layout_fit">q:1.1,9.1;h:5.7,4.8;k:-3.2,4.4</string>
                <string name="tap_model_data">a:1.0,2.0;s:0.5,3.1</string>
                <float name="key_offset_x" value="5.0" />
                <float name="key_offset_y" value="-3.0" />
                <int name="nav_strip_mode" value="3" />
                <string name="swipe_bias_data">the${'\t'}-5</string>
                <boolean name="llm_suggestions" value="true" />
                <boolean name="llm_suggestions_reset_v2" value="true" />
                <float name="key_spacing" value="1.2" />
                <boolean name="onboarding_done" value="true" />
                <boolean name="tutorial_seen" value="true" />
                <boolean name="show_advanced" value="true" />
                <boolean name="settings_simple" value="false" />
            </map>
        """.trimIndent() + "\n"

        /** Invented values, in the format an older build exports, with the prefs that build would have held. */
        val OLD_BACKUP = """
            {"version":1,"createdAt":1700000000000,"prefs":{
            "key_layout_fit":{"t":"s","v":"q:1.1,9.1;h:5.7,4.8;k:-3.2,4.4"},
            "tap_model_data":{"t":"s","v":"a:1.0,2.0;s:0.5,3.1"},
            "key_offset_x":{"t":"f","v":5.0},
            "key_offset_y":{"t":"f","v":-3.0},
            "nav_strip_mode":{"t":"i","v":3},
            "swipe_bias_data":{"t":"s","v":"the\t-5"},
            "llm_suggestions_reset_v2":{"t":"b","v":true},
            "key_spacing":{"t":"f","v":1.3}
            },"words":[{"w":"isn","c":4},{"w":"moonlight","c":3}]}
        """.trimIndent()

        /** A file already repaired. Its one glide correction is left for the reset, or for a restore to replace. */
        val REPAIRED_FILE = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <int name="geometry_schema" value="2" />
                <string name="swipe_bias_data">$OLD_BIAS_WORD${'\t'}-3</string>
                <boolean name="onboarding_done" value="true" />
                <boolean name="tutorial_seen" value="true" />
                <boolean name="show_advanced" value="true" />
                <boolean name="settings_simple" value="false" />
            </map>
        """.trimIndent() + "\n"
    }
}
