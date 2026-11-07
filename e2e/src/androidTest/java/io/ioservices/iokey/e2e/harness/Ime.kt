package io.ioservices.iokey.e2e.harness

import android.os.SystemClock
import android.util.Log

/** The keyboard app as a process and a stored state. Works on any installed debuggable build. */
class Ime(private val shell: Shell, val pkg: String, service: String) {

    val id = "$pkg/$service"

    private val swallowedErrors = ArrayList<String>()

    /**
     * Makes IOKey the keyboard, and keeps at it until the choice holds. Right after a force-stop or a data
     * wipe the system is still processing the package change, and that processing puts its fallback
     * keyboard back over a selection made too early.
     */
    fun select() {
        val held = Wait.until(SELECT_TIMEOUT_MS, 200) {
            if (!isSelected()) {
                shell.exec("ime enable $id")
                shell.exec("ime set $id")
            }
            Wait.until(1_000, 100) { !isSelected() }.not()
        }
        check(held) { "Could not select $id. Current keyboard: ${currentIme()}. Enabled: ${shell.exec("ime list -s")}" }
    }

    fun isSelected(): Boolean = currentIme() == id

    /**
     * Makes sure there is a keyboard process for a tap to bring up. When a data wipe or force-stop lands while
     * the system is binding the service, the binding is lost, nothing will start the process again, and
     * selecting the keyboard that is already selected does nothing. Only a change of selection rebinds it.
     */
    fun ensureRunning() {
        if (Wait.until(BIND_TIMEOUT_MS, 100) { pid() != null }) return
        Log.i(TAG, "No keyboard process after ${BIND_TIMEOUT_MS / 1000} s, selecting it afresh")
        val other = shell.exec("ime list -s").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && it != id }
        if (other != null) {
            shell.exec("ime set $other")
            SystemClock.sleep(RESELECT_PAUSE_MS)
        }
        shell.exec("ime set $id")
        select()
        check(Wait.until(RESTART_TIMEOUT_MS, 200) { pid() != null }) {
            "The keyboard process did not start. Selected: ${currentIme()}"
        }
    }

    fun currentIme(): String = shell.exec("settings get secure default_input_method").trim()

    fun pid(): Int? = shell.exec("pidof $pkg").trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()

    /**
     * Ends the process the way the system does under memory pressure: the selection stays and the service
     * is rebound. Android delays the rebind, and delays it more each time within a minute, so the wait is
     * long.
     */
    fun killProcess(waitForRestart: Boolean = true) {
        val before = pid() ?: return
        shell.sh("run-as $pkg kill -9 $before")
        check(Wait.until(5_000) { pid() != before }) { "The keyboard process $before did not die" }
        if (waitForRestart) {
            check(Wait.until(RESTART_TIMEOUT_MS, 200) { pid() != null }) {
                "The keyboard process was not restarted within ${RESTART_TIMEOUT_MS / 1000} s"
            }
        }
    }

    /** Force-stop makes Android fall back to another keyboard, so the selection has to be made again. */
    fun forceStop() {
        shell.exec("am force-stop $pkg")
        Wait.until(5_000) { pid() == null }
    }

    /** Wipes everything the keyboard has learned and stored. This resets the selection too. */
    fun clearData() {
        setAsideSwallowedErrors()
        val out = shell.exec("pm clear $pkg")
        check(out.contains("Success")) { "pm clear failed: $out" }
        Wait.until(5_000) { pid() == null }
    }

    /**
     * What the keyboard wrote to its crash log about exceptions it caught and lived through, each entry once. A test
     * sees nothing of those, because the keyboard carries on, and this log is the one trace. A wipe erases it and most
     * tests start with one, so [clearData] sets the entries aside first.
     */
    fun takeSwallowedErrors(): List<String> {
        setAsideSwallowedErrors()
        return swallowedErrors.toList().also { swallowedErrors.clear() }
    }

    private fun setAsideSwallowedErrors() {
        val log = shell.sh("run-as $pkg sh -c 'cat $CRASH_LOG_PATH 2>/dev/null; rm -f $CRASH_LOG_PATH'")
        swallowedErrors += log.split(CRASH_ENTRY_START).filter { SWALLOWED_MARK in it }.map { it.trim() }
    }

    /**
     * Waits for the keyboard's background start-up work to end, by watching its CPU time stop rising.
     * Until then the dictionary may not be loaded, and a typo typed that early is not corrected at all.
     */
    fun waitIdle(timeoutMs: Long = 45_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var quiet = 0
        var last = -1L
        while (quiet < 3 && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(300)
            val used = cpuTicks()
            quiet = if (used != null && last >= 0 && used - last <= IDLE_TICKS_PER_POLL) quiet + 1 else 0
            if (used != null) last = used
        }
    }

    private fun cpuTicks(): Long? {
        val pid = pid() ?: return null
        val stat = shell.exec("cat /proc/$pid/stat")
        // The process name in field 2 may contain spaces, so count from the closing parenthesis.
        val rest = stat.substringAfterLast(')', "").trim().split(' ')
        val user = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val system = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return user + system
    }

    /** Reads the keyboard's main preferences file, or null if there is none yet. */
    fun readPrefsXml(): String? {
        val out = shell.sh("run-as $pkg cat $PREFS_PATH 2>/dev/null")
        return out.takeIf { it.contains("<map") }
    }

    /** Replaces the preferences file. Only safe while the process is stopped, so call [forceStop] first. */
    fun writePrefsXml(xml: String) {
        val script = buildString {
            append("run-as $pkg sh -c 'mkdir -p shared_prefs; rm -f $PREFS_PATH.bak; cat > $PREFS_PATH' <<'E2E_EOF'\n")
            append(xml)
            if (!xml.endsWith("\n")) append('\n')
            append("E2E_EOF\n")
        }
        shell.sh(script)
        val readBack = readPrefsXml()
        check(readBack?.trim() == xml.trim()) { "Could not write the keyboard's preferences. Read back: $readBack" }
    }

    /** Sets string preferences directly. It is how a test puts learned data in place on a debug build. */
    fun seedStringPrefs(values: Map<String, String>) {
        val existing = readPrefsXml() ?: EMPTY_PREFS
        var xml = existing
        for ((name, value) in values) {
            xml = Regex("""\s*<string name="${Regex.escape(name)}">.*?</string>""", RegexOption.DOT_MATCHES_ALL)
                .replace(xml, "")
            xml = xml.replace("</map>", "    <string name=\"$name\">${xmlEscape(value)}</string>\n</map>")
        }
        writePrefsXml(xml)
    }

    /** Sets float preferences directly, in the file format SharedPreferences uses. */
    fun seedFloatPrefs(values: Map<String, Float>) {
        val existing = readPrefsXml() ?: EMPTY_PREFS
        var xml = existing
        for ((name, value) in values) {
            xml = Regex("""\s*<float name="${Regex.escape(name)}" value="[^"]*" />""").replace(xml, "")
            xml = xml.replace("</map>", "    <float name=\"$name\" value=\"$value\" />\n</map>")
        }
        writePrefsXml(xml)
    }

    /** Sets boolean preferences directly, in the file format SharedPreferences uses. */
    fun seedBooleanPrefs(values: Map<String, Boolean>) {
        val existing = readPrefsXml() ?: EMPTY_PREFS
        var xml = existing
        for ((name, value) in values) {
            xml = Regex("""\s*<boolean name="${Regex.escape(name)}" value="[^"]*" />""").replace(xml, "")
            xml = xml.replace("</map>", "    <boolean name=\"$name\" value=\"$value\" />\n</map>")
        }
        writePrefsXml(xml)
    }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val PREFS_NAME = "localboard_prefs"
        private const val PREFS_PATH = "shared_prefs/$PREFS_NAME.xml"
        private const val CRASH_LOG_PATH = "files/last_crash.txt"
        private const val SWALLOWED_MARK = "keyboard kept running"
        private val CRASH_ENTRY_START = Regex("""(?m)^(?=\u2550{3} IOKey )""")
        private const val EMPTY_PREFS = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
        private const val TAG = "IOKeyE2E"
        private const val SELECT_TIMEOUT_MS = 15_000L
        private const val BIND_TIMEOUT_MS = 6_000L
        private const val RESELECT_PAUSE_MS = 500L
        private const val RESTART_TIMEOUT_MS = 100_000L
        private const val IDLE_TICKS_PER_POLL = 3L
    }
}
