package com.localboard.keyboard

import android.app.Application
import com.localboard.keyboard.ime.SURVIVED_ERROR_MARK
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Process-wide safety net. The keyboard's worst failure mode is a silent
 * death in the background — Android then takes seconds to rebind the IME,
 * which reads as "the keyboard doesn't pop up". Every uncaught crash is
 * now written to a small local log (never leaves the device) so Settings
 * can show exactly what died and when, instead of guessing.
 */
class LocalBoardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Arm the network guard before anything can reach for a socket, so
        // the user's "no network" switch is in force from process start
        // rather than from whenever the first screen happens to load prefs.
        // Key labels resolve their typeface with no Context in hand, so the
        // application one is parked here before any view can ask.
        com.localboard.keyboard.util.FontCache.init(this)
        // From API 35 every activity draws under the status and navigation
        // bars whether it opts in or not. Registered here so all twelve
        // settings screens — and any added later — get insets without each
        // one having to remember.
        com.localboard.keyboard.util.EdgeToEdge.install(this)
        // Entitlements: which implementation lands here is decided by the
        // product flavour — Play Billing in the store build, always-unlocked
        // in the sideload build.
        com.localboard.keyboard.billing.ProActivityRoute.targetClass =
            com.localboard.keyboard.settings.ProActivity::class.java
        try {
            com.localboard.keyboard.billing.Pro.install(
                com.localboard.keyboard.billing.ProProviderImpl(), this
            )
        } catch (_: Throwable) {
            // Billing unavailable (no Play services, sideloaded store build):
            // Pro simply reports locked rather than taking the process down.
        }
        try {
            com.localboard.keyboard.util.NetGuard.init(
                com.localboard.keyboard.util.Prefs(this)
            )
        } catch (_: Throwable) {
            // Prefs failing here must not take the process down; NetGuard
            // then stays at its permissive default and the download paths
            // still hash-check everything they fetch.
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordCrash(thread, error)
            } catch (_: Throwable) {
                // The crash log itself must never crash the crash.
            }
            previous?.uncaughtException(thread, error)
                ?: kotlin.system.exitProcess(10)
        }
        // Repairs what older builds stored, before the keyboard or any screen reads a
        // preference. After the crash handler and in its own catch: a keyboard with stale
        // bytes in its preferences still types, and one that dies here does not. The repair
        // and its stamp reach the disk together, so a failure is simply tried again at the
        // next start.
        try {
            com.localboard.keyboard.util.PrefsMigrations.run(
                com.localboard.keyboard.util.Prefs(this).migrationStore()
            )
        } catch (error: Throwable) {
            try {
                appendToLog("settings repair, $SURVIVED_ERROR_MARK", error)
            } catch (_: Throwable) {
                // The crash log itself must never crash the crash.
            }
        }
        // These counts live in their own file, apart from the settings and so apart from
        // backups: they describe this device, not the person's choices.
        try {
            val stored = getSharedPreferences(STATS_FILE, MODE_PRIVATE)
            com.localboard.keyboard.util.LocalStats.attach(
                object : com.localboard.keyboard.util.LocalStats.Store {
                    override fun read(key: String): Long = stored.getLong(key, 0L)
                    override fun write(key: String, value: Long) {
                        stored.edit().putLong(key, value).apply()
                    }
                }
            )
        } catch (_: Throwable) {
            // The counts then stay in memory. Nothing that types depends on them.
        }
    }

    private fun recordCrash(thread: Thread, error: Throwable) =
        appendToLog("thread=${thread.name}", error)

    private fun appendToLog(label: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (t: Throwable) { "?" }
        val entry = buildString {
            append("═══ IOKey ").append(ver)
            append(" · ").append(label).append(" · ")
            append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()))
            append(" ═══\n")
            append(sw.toString().take(6000))
            append('\n')
        }
        // Keep the last few crashes, capped: enough to see a repeating
        // pattern, never a disk burden.
        val dir = filesDir
        val log = java.io.File(dir, CRASH_FILE)
        val old = if (log.exists()) log.readText().takeLast(12_000) else ""
        log.writeText(old + entry)
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
        private const val STATS_FILE = "local_stats"
    }
}
