package com.localboard.keyboard.ime

import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.engine.HybridRouter
import com.localboard.keyboard.ime.LLMKeyboardService.Companion.MODEL_IDLE_UNLOAD_MS
import com.localboard.keyboard.llm.LlamaCppProvider
import com.localboard.keyboard.llm.LocalStyleComposer
import com.localboard.keyboard.llm.ProviderRegistry
import com.localboard.keyboard.util.ColdStartStage
import com.localboard.keyboard.util.PhoneStats
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Engine construction, their start-up work, and when models unload.
 *
 * Fields only this file needs belong here. The service holds one instance, so
 * adding a field leaves it untouched.
 */
internal class EngineState

/** In a crash log header, what tells an error the keyboard survived from a crash. */
internal const val SURVIVED_ERROR_MARK = "keyboard kept running"

/** An exception of one class goes in the crash log at most this often. */
private const val CRASH_LOG_MIN_INTERVAL_MS = 60_000L

/** How long the first glide table waits for the learned words once the dictionary is in. */
private const val GLIDE_TABLE_WAIT_MS = 3_000L

/** When each class last went in. Kept for the process, not the service: a service the
 *  system keeps rebuilding must not get a fresh allowance each time. */
private val crashLogRecordedAt = HashMap<String, Long>()

/** Two classes failing together both write, and each read has to see the other's write. */
private val crashLogWriteLock = Any()

/**
 * The list of recent fields names the apps typed into, and the process lives on after an erase,
 * so it has to go with the rest. One object for the process: the engine keeps its listeners in a
 * set, so a service restart does not add a second. A throw here would report the erase unfinished,
 * so it does nothing but empty the list.
 */
private val fieldListEraser = ContextEngine.EraseListener { packageName ->
    PhoneStats.eraseFields(packageName)
}

/**
 * serviceScope's last resort. Without a handler, an exception in any coroutine
 * here reaches the default handler and takes the process with it, which on a
 * keyboard means seconds with no keyboard over one bad prediction pass. It goes
 * in the log Settings already shows, in the same format, and the keyboard
 * carries on: the worst case is a refresh that never landed.
 *
 * Out of memory still goes on to the default handler, which records it and ends
 * the process. The restart is what gives that heap back, and carrying on would
 * leave a degraded keyboard, maybe with no record of why, since writing the entry
 * needs memory too.
 *
 * With no process death to stop it, one exception can repeat on every keystroke.
 * So a class is recorded at most once a minute, or it would push real crashes out
 * of the capped log, and the recording leaves the failing thread, usually the main
 * one, because it takes a binder call and file reads and writes.
 */
internal fun LLMKeyboardService.coroutineCrashHandler(): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error ->
        if (error is OutOfMemoryError) throw error
        recordCaughtError(error, "coroutine")
    }

/**
 * Puts an exception the keyboard lived through into the crash log, under the limits above. Also for a
 * catch that has its own way of telling the user: the message says what failed, and only this says why.
 */
internal fun LLMKeyboardService.recordCaughtError(error: Throwable, source: String) {
    val now = SystemClock.elapsedRealtime()
    val className = error.javaClass.name
    val due = synchronized(crashLogRecordedAt) {
        val last = crashLogRecordedAt[className]
        (last == null || now - last >= CRASH_LOG_MIN_INTERVAL_MS).also {
            if (it) crashLogRecordedAt[className] = now
        }
    }
    if (!due) {
        android.util.Log.w("LLMKeyboard", "caught in $source, already recorded: $className")
        return
    }
    android.util.Log.e("LLMKeyboard", "caught in $source", error)
    val failedOn = Thread.currentThread().name
    val failedAt = System.currentTimeMillis()
    try {
        kotlin.concurrent.thread(name = "crash-log") { recordSurvivedError(error, source, failedOn, failedAt) }
    } catch (_: Throwable) {
        // No thread to be had. Logcat has it, and the keyboard still carries on.
    }
}

private fun LLMKeyboardService.recordSurvivedError(
    error: Throwable,
    source: String,
    failedOn: String,
    failedAt: Long
) {
    try {
        val trace = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(trace))
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) { "?" }
        val entry = buildString {
            append("═══ IOKey ").append(ver)
            append(" · ").append(source).append(", ").append(SURVIVED_ERROR_MARK).append(" · thread=")
            append(failedOn).append(" · ")
            append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", failedAt))
            append(" ═══\n")
            append(trace.toString().take(6000))
            append('\n')
        }
        synchronized(crashLogWriteLock) {
            val log = java.io.File(filesDir, com.localboard.keyboard.LocalBoardApp.CRASH_FILE)
            val old = if (log.exists()) log.readText().takeLast(12_000) else ""
            log.writeText(old + entry)
        }
    } catch (_: Throwable) {
        // The crash log itself must never crash the crash.
    }
}

internal fun LLMKeyboardService.startEngines() {
    val firstSet = PhoneStats.coldStartBegin()
    fastEngine = FastEngine.shared(this)
    contextEngine = ContextEngine.shared(this)
    router = HybridRouter(fastEngine)
    registry = ProviderRegistry(prefs, LocalStyleComposer(fastEngine))
    registry.register(LlamaCppProvider(this))
    contextEngine.addEraseListener(fieldListEraser)
    PhoneStats.coldStartMark(firstSet, ColdStartStage.ENGINES_BUILT)

    serviceScope.launch(Dispatchers.IO) {
        contextEngine.initialize()
        PhoneStats.coldStartMark(firstSet, ColdStartStage.INITIALIZE_RETURNED)
    }
    // The show that asked for the glide table usually comes before the dictionary, and
    // then nothing would ask again until a glide found no table and was lost. A finished
    // table is not rebuilt for a while, so one built just ahead of the learned words would
    // keep them out of glide for that long after every restart. It waits for them, but
    // a slow store does not get to hold glide up, and however late they come they get a
    // table of their own at once.
    serviceScope.launch {
        contextEngine.awaitDictionary()
        PhoneStats.coldStartMark(firstSet, ColdStartStage.DICTIONARY_IN)
        if (withTimeoutOrNull(GLIDE_TABLE_WAIT_MS) { contextEngine.awaitFirstLoad() } == null) {
            swipeDecoder?.warmUp()
            contextEngine.awaitFirstLoad()
        }
        PhoneStats.coldStartMark(firstSet, ColdStartStage.FIRST_LOAD_OVER)
        // A start with no show yet has no decoder and asks for nothing, so that line must say not measured.
        swipeDecoder?.let {
            it.warmUpAfterFirstLoad()
            PhoneStats.coldStartMark(firstSet, ColdStartStage.GLIDE_TABLE_ASKED_FOR)
        }
    }

    // Overnight on-charger style/personality distillation (user-toggleable
    // in settings; WorkManager keeps it across reboots).
    try {
        if (prefs.overnightDistillation) {
            com.localboard.keyboard.llm.NightlyDistillWorker.schedule(this)
        }
    } catch (_: Throwable) { }
}

internal fun LLMKeyboardService.onShowCancelModelUnload(info: EditorInfo, restarting: Boolean) {
    // Keyboard is back: the model stays resident while there is a user
    // to draft for. Any pending idle-unload is off.
    unloadJob?.cancel()
}

internal fun LLMKeyboardService.onShowLoadAppContext(info: EditorInfo, restarting: Boolean) {
    serviceScope.launch(Dispatchers.IO) {
        contextEngine.loadAppContext(currentAppPackage)
    }
}

internal fun LLMKeyboardService.onHideReleaseEngines() {
    serviceScope.launch(Dispatchers.IO) {
        // checkpoint() flushes first, then folds the write-ahead log back
        // into the database. Doing it here puts that work in the one window
        // where nobody is waiting on a prediction.
        try { contextEngine.checkpoint() } catch (_: Throwable) { }
    }
    scheduleModelUnload()
}

// ------------------------------------------------------------------
// Memory pressure: the #1 keyboard-killer
// ------------------------------------------------------------------
//
// A resident GGUF model (0.4–2.5 GB) + KV cache makes this process the
// system's favourite thing to kill the moment the keyboard hides. Every
// kill reads as "the keyboard doesn't pop up for a couple of seconds"
// (cold start on next tap) and, under pressure, as a crash loop. So the
// model only stays resident while the keyboard is actually up; it frees
// itself shortly after hide, and instantly under real memory pressure.

internal fun LLMKeyboardService.llamaProvider(): com.localboard.keyboard.llm.LlamaCppProvider? =
    registry.all().filterIsInstance<com.localboard.keyboard.llm.LlamaCppProvider>()
        .firstOrNull()

/** Grace period after the keyboard hides: quick app-switching keeps the
 *  model warm (drafts stay instant); genuinely leaving frees the RAM. */
internal fun LLMKeyboardService.scheduleModelUnload(delayMs: Long = MODEL_IDLE_UNLOAD_MS) {
    unloadJob?.cancel()
    unloadJob = serviceScope.launch(Dispatchers.IO) {
        delay(delayMs)
        val provider = llamaProvider() ?: return@launch
        if (provider.idleSince(android.os.SystemClock.elapsedRealtime()) >= delayMs) {
            provider.releaseModel("idle ${delayMs / 60000} min after keyboard hide")
        }
        // Whisper is smaller but still a resident chunk; dictation
        // reloads fast enough that hiding always frees it.
        try { com.localboard.keyboard.llm.VoiceBridge.freeModel() } catch (_: Throwable) { }
    }
}
