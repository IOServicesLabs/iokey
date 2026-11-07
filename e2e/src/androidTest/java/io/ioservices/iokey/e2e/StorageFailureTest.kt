package io.ioservices.iokey.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.ioservices.iokey.e2e.harness.E2eDevice
import io.ioservices.iokey.e2e.harness.E2eTestBase
import io.ioservices.iokey.e2e.harness.Keyboard
import io.ioservices.iokey.e2e.harness.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A store the keyboard cannot open has to show up where someone can read it. Typing carries on and nothing looks
 * wrong, so learning that stops without a word is the worst way this can fail. A file the app may not read stands in
 * for a failed upgrade: both come out of the first open as an exception that does not mean the data is corrupt, and
 * neither may cost the user the file.
 */
class StorageFailureTest : E2eTestBase() {

    override val wipeLearnedStateBeforeEachTest = true

    private val chat = HostActivity.CHAT
    private val shell get() = E2eDevice.shell

    private fun runAs(script: String) = shell.sh("run-as ${ime.pkg} sh -c '$script'")

    private fun lockTheStore() {
        runAs("mkdir -p databases; rm -f databases/$STORE*; : > databases/$STORE; chmod 000 databases/$STORE")
        val read = runAs("cat databases/$STORE >/dev/null 2>&1 && echo readable || echo refused")
        check(read.contains("refused")) { "The store can still be read, so this test would prove nothing: $read" }
    }

    private fun find(node: AccessibilityNodeInfo, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        if (node.text?.toString()?.let(matches) == true) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> find(c, matches)?.let { return it } }
        }
        return null
    }

    private fun findOnScreen(matches: (String) -> Boolean): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        val root = E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg } ?: return null
        return find(root, matches)
    }

    /** Through the app's own launcher screen and its button, since the settings screen has no other way in. */
    private fun diagnosticsInSettings(): String {
        kb.hideWithBack()
        // A kill leaves the task behind with Settings on top, and a plain start just brings that to the front.
        shell.exec("am start --activity-clear-task -n ${ime.pkg}/$ONBOARDING")
        var button: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { button = findOnScreen { it.equals(OPEN_SETTINGS_LABEL, ignoreCase = true) }; button != null }) {
            "The launcher screen has no '$OPEN_SETTINGS_LABEL' button"
        }
        val r = Rect()
        button!!.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
        var report = ""
        check(Wait.until(SCREEN_MS, 200) { report = findOnScreen { DIAGNOSTICS_LAST_LINE in it }?.text?.toString().orEmpty(); report.isNotEmpty() }) {
            "The settings screen shows no diagnostics report"
        }
        return report
    }

    @Test
    fun aStoreThatWillNotOpen_isReportedAndTypingGoesOn() {
        ime.forceStop()
        ime.seedBooleanPrefs(
            mapOf("onboarding_done" to true, "tutorial_seen" to true, "show_advanced" to true, "settings_simple" to false),
        )
        lockTheStore()
        atEnd { ime.clearData() }
        ime.select()
        showReady(chat)
        val pid = ime.pid()

        kb.type("see you ")
        assertTrue(
            "Typing must go on with no store, but the field holds '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).lowercase().endsWith("see you ") },
        )

        val report = diagnosticsInSettings()
        assertTrue("The diagnostics must say the words could not be opened, but read:\n$report", report.contains(REPORTED))
        assertEquals("The keyboard must not die over it", pid, ime.pid())
        assertTrue("The file must not be deleted", runAs("ls databases").contains(STORE))
    }

    private fun firstRunIsOver() {
        ime.forceStop()
        ime.seedBooleanPrefs(
            mapOf("onboarding_done" to true, "tutorial_seen" to true, "show_advanced" to true, "settings_simple" to false),
        )
    }

    private fun checksum(file: String) = runAs("cksum databases/$file").trim().substringBefore(" databases/")

    /**
     * Any phone with a store on it has the passphrase that goes with it. A store with none beside it reads as a key
     * that failed, whatever its bytes are, because a new passphrase is what would then make it read as damaged.
     */
    private fun aFirstStartHasStoredThePassphrase() {
        ime.select()
        showReady(chat)
        check(Wait.until(SCREEN_MS, 200) { "enc_passphrase" in runAs("cat shared_prefs/$KEYSTORE_PREFS.xml") }) {
            "The first start stored no passphrase"
        }
        kb.hideWithBack()
        ime.forceStop()
    }

    /** Bytes that are no database at all, which is what a store wrecked on disk reads as to its first query. */
    @Test
    fun aStoreThatReadsAsCorrupt_isMovedAsideAndSavingStartsAgain() {
        firstRunIsOver()
        atEnd { ime.clearData() }
        aFirstStartHasStoredThePassphrase()
        runAs("mkdir -p databases; rm -f databases/*; head -c 16384 /dev/urandom > databases/$STORE")
        val wrecked = checksum(STORE)
        check(wrecked.isNotBlank()) { "The wrecked store was not written" }
        ime.select()
        showReady(chat)

        kb.type("see you ")
        assertTrue(
            "Typing must go on, but the field holds '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).lowercase().endsWith("see you ") },
        )
        val movedAside = Wait.until(SCREEN_MS, 200) { runAs("ls databases").lines().any { it.trim() == SET_ASIDE } }
        assertTrue("The store must be moved aside: ${runAs("ls -l databases")}", movedAside)

        assertEquals("The copy must be the file that was there", wrecked, checksum(SET_ASIDE))
        assertTrue("A new store must take its place", runAs("ls databases").lines().any { it.trim() == STORE })
        assertTrue("A new store must not be the old bytes", checksum(STORE) != wrecked)

        val report = diagnosticsInSettings()
        assertTrue("The diagnostics must say a copy is still here, but read:\n$report", report.contains(COPY_STAYS))
        assertTrue("Nothing is wrong now, so nothing may report a store that will not open:\n$report", !report.contains(WRONG_NOW))
        assertTrue("And nothing may offer to start over", button(START_FRESH) == null)
    }

    private fun button(label: String): AccessibilityNodeInfo? {
        fun inTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isClickable && node.text?.toString().equals(label, ignoreCase = true)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { c -> inTree(c)?.let { return it } }
            }
            return null
        }
        E2eDevice.ua.clearCache()
        return E2eDevice.ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == ime.pkg }?.let(::inTree)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val r = Rect()
        node.getBoundsInScreen(r)
        touch.tap(r.exactCenterX(), r.exactCenterY())
    }

    /** The wrapped passphrase no longer unwraps, which is what a torn write or a key the system lost comes to. */
    private fun breakTheKey() {
        val prefs = "shared_prefs/$KEYSTORE_PREFS.xml"
        runAs("""sed -i "s/\(enc_passphrase..\)......../\1AAAAAAAA/" $prefs; rm -f $prefs.bak""")
        val stored = runAs("cat $prefs")
        check("AAAAAAAA" in stored) { "The stored key is unchanged, so this test would prove nothing: $stored" }
    }

    /** From a real store with a real word in it, through the only way out there is, which only the person can take. */
    @Test
    fun aKeyThatFails_leavesTheStoreAloneUntilThePersonStartsFresh() {
        firstRunIsOver()
        atEnd { ime.clearData() }
        ime.select()
        showReady(chat)
        kb.type("see you ")
        check(Wait.until(TEXT_MS, 40) { host.text(chat).lowercase().endsWith("see you ") }) { "Typing did not start" }
        kb.hideWithBack()
        check(Wait.until(SCREEN_MS, 200) { runAs("ls databases").contains(STORE) }) { "The store was never made" }

        ime.forceStop()
        breakTheKey()
        val untouched = checksum(STORE)
        // Where the words since the last checkpoint are, so a store without it has lost them.
        val log = checksum("$STORE-wal").takeIf { "$STORE-wal" in runAs("ls databases") }
        ime.select()
        showReady(chat)
        val pid = ime.pid()

        kb.type("see you ")
        assertTrue(
            "Typing must go on with no store, but the field holds '${host.text(chat)}'",
            Wait.until(TEXT_MS, 40) { host.text(chat).lowercase().endsWith("see you ") },
        )

        val report = diagnosticsInSettings()
        assertTrue("The diagnostics must name the key, but read:\n$report", report.contains(KEY_REPORTED))
        assertEquals("The keyboard must not die over it", pid, ime.pid())
        assertEquals("Nothing may touch the store on its own", untouched, checksum(STORE))
        assertTrue("Nothing may be moved on its own: ${runAs("ls databases")}", !runAs("ls databases").contains(SET_ASIDE))

        // A failed read of the key must not have replaced it either, or a key that comes back would open nothing.
        assertTrue("The stored key must stay as it was", "AAAAAAAA" in runAs("cat shared_prefs/$KEYSTORE_PREFS.xml"))

        // A failure this new may well pass, so Settings says what is going on and does not yet offer to give up on it.
        assertTrue("Settings must have a note about it", findOnScreen { WAITING_NOTE in it } != null)
        assertTrue("One start that failed must not offer it", button(START_FRESH) == null)

        ime.forceStop()
        ime.select()
        host.bringToFront()
        showReady(chat)
        diagnosticsInSettings()
        assertTrue("Two starts that failed must not offer it", button(START_FRESH) == null)

        // Not through showReady, which can show twice, and the notice comes once.
        ime.forceStop()
        ime.select()
        host.bringToFront()
        kb.show(chat)
        var notice: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 100) { notice = noticeInKeyboard(); notice != null }) {
            "Three starts that failed and the keyboard says nothing. In its windows: ${kb.visibleTextInImeWindows()}"
        }
        assertEquals("Nothing may touch the store however many starts fail", untouched, checksum(STORE))
        tap(notice!!)

        var offer: AccessibilityNodeInfo? = null
        check(Wait.until(SCREEN_MS, 200) { offer = button(START_FRESH); offer != null }) { "The notice leads to no '$START_FRESH'" }
        offer!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        Wait.untilStable(SCREEN_MS, 200) { Rect().also { r -> button(START_FRESH)?.getBoundsInScreen(r) } }
        tap(button(START_FRESH)!!)

        // The dialog's title reads the same, and that does not take a tap.
        check(Wait.until(SCREEN_MS, 200) { findOnScreen { CONFIRM_TEXT in it } != null }) { "Starting fresh must be confirmed" }
        assertEquals("Nothing may have moved before the answer", untouched, checksum(STORE))
        tap(button(START_FRESH)!!)

        val movedAside = Wait.until(SCREEN_MS, 200) { runAs("ls databases").lines().any { it.trim() == SET_ASIDE } }
        assertTrue("The old store must be moved aside: ${runAs("ls -l databases")}", movedAside)
        assertEquals("The copy must be the store that was there", untouched, checksum(SET_ASIDE))
        if (log != null) assertEquals("Its log must go with it", log, checksum("$SET_ASIDE-wal"))

        // Settings rebuilds itself after this, which is the point where a real problem would still show. The report
        // also lists what happened earlier, with its time, and the old failure is rightly still in that list.
        var after = ""
        val cleared = Wait.until(SCREEN_MS, 200) {
            after = findOnScreen { DIAGNOSTICS_LAST_LINE in it }?.text?.toString().orEmpty()
            after.isNotEmpty() && !after.contains(WRONG_NOW) && after.contains(COPY_STAYS)
        }
        assertTrue("The diagnostics must stop reporting the key and say a copy is kept, and read:\n$after", cleared)
        assertTrue("With nothing wrong, nothing may offer it again", button(START_FRESH) == null)

        // What the screen then promises: this process saves again, into a store a new process can open.
        host.bringToFront()
        showReady(chat)
        keep(SAVED_WORD)
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)
        ime.killProcess()
        showReady(chat)
        assertCompletes(SAVED_WORD, "A word typed after starting fresh must complete after a restart")

        val second = diagnosticsInSettings()
        assertTrue("A new process must open the new store, but the diagnostics read:\n$second", !second.contains(WRONG_NOW))
    }

    /** The keyboard's notice about the store, in its own window, which is no use to [findOnScreen]. */
    private fun noticeInKeyboard(): AccessibilityNodeInfo? {
        E2eDevice.ua.clearCache()
        val window = E2eDevice.ua.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: return null
        return window.root?.let { root -> find(root) { NOTICE in it } }
    }

    /** What was typed while the store would not open has to reach it when it does, with nobody doing anything. */
    @Test
    fun aStoreThatComesBack_takesWhatWasTypedMeanwhile() {
        firstRunIsOver()
        atEnd { ime.clearData() }
        ime.select()
        showReady(chat)
        keep(SAVED_WORD)
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)

        ime.forceStop()
        runAs("chmod 000 databases/$STORE")
        val read = runAs("cat databases/$STORE >/dev/null 2>&1 && echo readable || echo refused")
        check(read.contains("refused")) { "The store can still be read, so this test would prove nothing: $read" }
        ime.select()
        showReady(chat)

        val report = diagnosticsInSettings()
        assertTrue("The store must really be closed for this to prove anything, but the diagnostics read:\n$report", report.contains(REPORTED))
        val failedBy = SystemClock.elapsedRealtime()

        // It could open from here on, and nothing tells the keyboard. Its first look comes a minute after it failed.
        runAs("chmod 600 databases/$STORE")
        host.bringToFront()
        showReady(chat)
        keep(TYPED_MEANWHILE)
        SystemClock.sleep((failedBy + FIRST_RETRY_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)

        showReady(chat)
        assertCompletes(SAVED_WORD, "A word from before must be back once the store is")

        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)
        ime.killProcess()
        showReady(chat)
        assertCompletes(TYPED_MEANWHILE, "A word typed while the store was closed must have reached it")
        assertCompletes(SAVED_WORD, "A word from before must still be there")

        val after = diagnosticsInSettings()
        assertTrue("The store is open again, so nothing may say otherwise:\n$after", !after.contains(WRONG_NOW))
    }

    /**
     * With the store closed, the one use just typed is all memory knows of a word. Backspace takes that back and memory
     * has lost the word, which says nothing about the uses in the store. They must still be there when it comes back.
     */
    @Test
    fun aWordTakenBackWhileTheStoreIsClosed_keepsItsStoredUses() {
        firstRunIsOver()
        atEnd { ime.clearData() }
        ime.select()
        showReady(chat)
        keep(SAVED_WORD)
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)

        ime.forceStop()
        runAs("chmod 000 databases/$STORE")
        val read = runAs("cat databases/$STORE >/dev/null 2>&1 && echo readable || echo refused")
        check(read.contains("refused")) { "The store can still be read, so this test would prove nothing: $read" }
        ime.select()
        showReady(chat)

        val report = diagnosticsInSettings()
        assertTrue("The store must really be closed for this to prove anything, but the diagnostics read:\n$report", report.contains(REPORTED))
        val failedBy = SystemClock.elapsedRealtime()

        runAs("chmod 600 databases/$STORE")
        host.bringToFront()
        showReady(chat)
        keep(SAVED_WORD, uses = 1)
        kb.backspace()
        check(Wait.until(CORRECTION_MS, 40) { host.text(chat).lowercase().endsWith(" $SAVED_WORD") }) {
            "Backspace did not go back into the word: '${host.text(chat)}'"
        }
        repeat(SAVED_WORD.length) { kb.backspace() }
        SystemClock.sleep((failedBy + FIRST_RETRY_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        kb.hideWithBack()
        SystemClock.sleep(PERSIST_MS)

        ime.killProcess()
        showReady(chat)
        assertCompletes(SAVED_WORD, "What the store held of the word must have outlived taking back one use of it")

        val after = diagnosticsInSettings()
        assertTrue("The store is open again, so nothing may say otherwise:\n$after", !after.contains(WRONG_NOW))
    }

    private fun assertCompletes(word: String, why: String) {
        val prefix = word.dropLast(1)
        val before = host.text(chat)
        kb.type(prefix)
        SystemClock.sleep(BAR_SETTLE_MS)
        kb.tapSlot(Keyboard.Slot.CENTRE)
        Wait.until(CHIP_MS, 40) { host.text(chat).length > before.length + prefix.length }
        assertEquals(why, "$word ", host.text(chat).substring(before.length).lowercase())
    }

    /** Types the word as someone with their own vocabulary would, taking it back when it is corrected. */
    private fun keep(word: String, uses: Int = USES) {
        repeat(uses) {
            kb.type("so $word ")
            Wait.until(CORRECTION_MS, 40) { !host.text(chat).lowercase().endsWith(" $word ") }
            if (!host.text(chat).lowercase().endsWith(" $word ")) {
                kb.backspace()
                check(Wait.until(CORRECTION_MS, 40) { host.text(chat).lowercase().endsWith(" $word") }) {
                    "Backspace did not take the correction back: '${host.text(chat)}'"
                }
                kb.type(" ")
            }
        }
    }

    private companion object {
        const val SAVED_WORD = "xylqor"
        const val TYPED_MEANWHILE = "vumbrex"
        const val USES = 3
        const val CORRECTION_MS = 300L
        const val BAR_SETTLE_MS = 600L
        const val CHIP_MS = 1_000L
        const val PERSIST_MS = 3_000L
        const val FIRST_RETRY_MS = 61_000L
        const val STORE = "localboard.db"
        const val SET_ASIDE = "localboard-set-aside.db"
        const val KEYSTORE_PREFS = "localboard_keystore"
        const val START_FRESH = "Start fresh"
        const val CONFIRM_TEXT = "stay on this phone until another set replaces them"
        const val KEY_REPORTED = "the key that unlocks them"
        const val WAITING_NOTE = "be opened right now"
        const val NOTICE = "Tap to see what you can do"
        const val COPY_STAYS = "were moved aside on"
        const val ONBOARDING = "com.localboard.keyboard.onboarding.OnboardingActivity"
        const val OPEN_SETTINGS_LABEL = "Open Settings"
        const val DIAGNOSTICS_LAST_LINE = "crash log:"
        const val REPORTED = "Saved words could not be opened"
        const val WRONG_NOW = "word storage: $REPORTED"
        const val SCREEN_MS = 20_000L
        const val TEXT_MS = 1_000L
    }
}
