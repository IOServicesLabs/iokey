package com.localboard.keyboard.llm

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.localboard.keyboard.context.ContextEngine
import com.localboard.keyboard.context.TypingLog
import com.localboard.keyboard.data.db.AppDatabase
import com.localboard.keyboard.data.db.StyleStatEntity
import com.localboard.keyboard.engine.FastEngine
import com.localboard.keyboard.util.NightlyOutcome
import com.localboard.keyboard.util.PhoneStats
import com.localboard.keyboard.util.PowerGate
import com.localboard.keyboard.util.Prefs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The stamp a finished run leaves, in the usage stats. */
private const val LAST_RUN_STAT = "last_distillation"

/**
 * What the nightly job decides, with nothing of Android in it, so that a JVM test runs the real rule.
 */
internal object NightlyRules {

    /** Under a day, so that a run starting a little earlier each night does not put off the next one by a whole round. */
    private const val MIN_GAP_MS = 20 * 3_600_000L

    /** True when a run finished so lately that this round has nothing to do. A stamp ahead of the clock says nothing, since clocks move. */
    fun ranRecently(stampMs: Long?, nowMs: Long): Boolean {
        if (stampMs == null) return false
        val age = nowMs - stampMs
        return age >= 0 && age < MIN_GAP_MS
    }

    /** There is a model this job may load. The quick default can be on the phone for next words alone, and is not for this job until someone chooses it. */
    fun modelMayRun(specId: String, fileReady: Boolean, storedActiveId: String?): Boolean =
        fileReady && (specId != ModelRegistry.QUICK_DEFAULT_ID || ModelRegistry.isChosenActive(storedActiveId, specId))

    /** How a run that reached the model's turn ended. A stopped run's result may be the fallback its cancellation fell into, so a stop comes first. */
    fun outcome(stopped: Boolean, tooHot: Boolean, modelReady: Boolean, gotRewrite: Boolean): NightlyOutcome = when {
        stopped -> NightlyOutcome.STOPPED
        tooHot -> NightlyOutcome.TOO_HOT
        !modelReady -> NightlyOutcome.NO_MODEL_FALLBACK
        gotRewrite -> NightlyOutcome.MODEL_REWRITE
        else -> NightlyOutcome.MODEL_FALLBACK
    }

    /** Whether a run ending that way writes its style notes, the archive and the stamp. */
    fun saves(outcome: NightlyOutcome): Boolean = when (outcome) {
        NightlyOutcome.MODEL_REWRITE, NightlyOutcome.MODEL_FALLBACK, NightlyOutcome.NO_MODEL_FALLBACK -> true
        else -> false
    }
}

/**
 * Overnight distillation: while the phone is charging, idle and (nearly) full,
 * read the last two days of the encrypted typing log and let the on-device
 * model distill a short "who this person is as a writer" note. That note is
 * blended into every future generation alongside the measured style profile.
 *
 * Runs under WorkManager constraints (charging, idle, battery not low) and comes
 * round every few hours, so that a phone reaching ~full charge late still gets its
 * run, and does its work at most once a day. All data stays in
 * the Keystore-encrypted database on this device; typing data is never
 * transmitted anywhere.
 */
class NightlyDistillWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        distill()
    } catch (t: Throwable) {
        // Only a read of the log gets out of there, ahead of anything being saved.
        PhoneStats.noteNightlyRun(applicationContext, if (isStopped) NightlyOutcome.STOPPED else NightlyOutcome.FAILED)
        throw t
    }

    /** A round ending here did nothing, and the next one looks again. */
    private fun endedEarly(outcome: NightlyOutcome): Result {
        PhoneStats.noteNightlyRun(applicationContext, outcome)
        return Result.success()
    }

    /** When the last run finished. A read that fails says nothing about that, and must not hold a run back. */
    private suspend fun lastRunStamp(): Long? = try {
        AppDatabase.get(applicationContext).usageStatDao().get(LAST_RUN_STAT)
    } catch (_: Throwable) {
        null
    }

    private suspend fun distill(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.overnightDistillation) return endedEarly(NightlyOutcome.OFF)

        // The job comes round every few hours, so most rounds find the day's work done. Such a round
        // records nothing, so that what the phone shows is still the last real run.
        if (NightlyRules.ranRecently(lastRunStamp(), System.currentTimeMillis())) return Result.success()

        // "Charging overnight and at max charge": WorkManager only fires this
        // while charging; if the battery isn't nearly full yet, the next round
        // checks again. A retry would not last the night: this job can have no
        // backoff of its own, and the default's retries are over within minutes.
        val (level, charging) = batteryState()
        if (!charging) return endedEarly(NightlyOutcome.NOT_CHARGING)
        if (level in 0 until 90) return endedEarly(NightlyOutcome.BATTERY_BELOW_90)

        // All this run saves is built from the log read below, and the run can take minutes.
        // Saved after an erase, it would bring back what the erase promised was gone.
        val engine = ContextEngine.shared(applicationContext)
        val eraseCountBefore = engine.eraseCount

        val log = TypingLog(applicationContext)
        log.prune(days = 7)
        val since = System.currentTimeMillis() - 48 * 3_600_000L
        val fragments = log.since(since, 400)
        if (fragments.size < 12) return endedEarly(NightlyOutcome.TOO_FEW_WORDS)

        // "What they talk about, and with whom" — refreshed on the same run,
        // over a WIDER window than the style pass. Style is a habit and shows
        // up in two days of messages; subjects and relationships are not, and
        // a 48-hour view would rewrite the persona every night around whatever
        // happened yesterday. Seven days is the whole retained log.
        val personaFragments = log.since(
            System.currentTimeMillis() - 7 * 86_400_000L, 1200
        )
        runCatching {
            val persona = PersonaBuilder.build(personaFragments, ::appLabel)
            if (persona.isNotBlank()) {
                engine.unlessErasedSince(eraseCountBefore) { db ->
                    db.styleStatDao().putAll(listOf(StyleStatEntity("persona_notes", persona)))
                }
            }
        }

        // Same deterministic engine as the manual SMS distillation (the
        // hallucination fix): measure the fragments into hard facts — a tiny
        // model cannot be trusted to ANALYZE raw text — and let it only
        // rewrite the facts into fluent notes. The rewrite must agree with
        // the measurements or the measured profile is used instead. Works
        // even with no model installed (the measured profile stands alone).
        val texts = fragments.asReversed().map { it.text }
        val facts = StyleProfiler.analyze(texts)
        val spec = ModelRegistry.active(prefs)
        val modelReady = NightlyRules.modelMayRun(
            spec.id, ModelRegistry.isReady(applicationContext, spec), ModelRegistry.storedActiveId(applicationContext)
        )
        // Nobody is there to ask, so the power-aware switch has no say: minutes of every core on a hot phone is
        // never wanted. On charge only heat counts. With no model to load the fallback costs nothing.
        val tooHot = modelReady && PowerGate.isLowPower(applicationContext, prefs)
        var gotRewrite = false
        val notes = if (!modelReady || tooHot) {
            StyleProfiler.fallbackProfile(facts)
        } else {
            // Before the load, so that a kill during it leaves this as the last word.
            PhoneStats.noteNightlyRun(applicationContext, NightlyOutcome.STARTED)
            // A throwaway provider for this one run. The native model slots
            // are process-global and the keyboard's unload watchdog watches
            // ITS OWN provider instance — without the finally-release below,
            // the 0.4–2.5 GB model stays resident after the nightly run.
            val provider = LlamaCppProvider(applicationContext)
            try {
                val rewritten = provider.generate(
                    GenerateRequest(
                        contextBefore = StyleProfiler.factsBlock(facts),
                        styleProfile = null,
                        maxWords = 130,
                        temperature = 0.3f,
                        instruction = "Rewrite the notes below into ONE smooth " +
                            "paragraph (70-110 words) that an AI ghostwriter can " +
                            "follow. Keep every fact EXACTLY as stated — do not " +
                            "add, soften, or contradict anything. No bullets, no " +
                            "preamble. Write in present tense.",
                        // PROFILE purpose: the material must NOT be shrunk by the
                        // message context-fitter, and the profile must NOT pass
                        // through the message sanitizer.
                        purpose = GenerateRequest.Purpose.PROFILE
                    )
                ) { }
                if (rewritten.isBlank() || !StyleProfiler.isConsistent(rewritten, facts)) {
                    StyleProfiler.fallbackProfile(facts)
                } else {
                    gotRewrite = true
                    rewritten.trim()
                }
            } catch (t: Throwable) {
                StyleProfiler.fallbackProfile(facts)
            } finally {
                // A stopped coroutine would throw at the first suspension in here, and the model would stay in memory.
                withContext(NonCancellable) {
                    try { provider.releaseModel("nightly distill done") }
                    catch (_: Throwable) { }
                }
            }
        }
        // The catch above takes a cancellation too, so a stopped run reaches here holding a fallback it did not choose.
        val outcome = NightlyRules.outcome(isStopped, tooHot, modelReady, gotRewrite)
        if (!NightlyRules.saves(outcome)) {
            PhoneStats.noteNightlyRun(applicationContext, outcome)
            return if (outcome == NightlyOutcome.STOPPED && runAttemptCount < 2) Result.retry() else Result.success()
        }
        return try {
            val clean = sanitize(notes)
            engine.unlessErasedSince(eraseCountBefore) { db ->
                if (clean != null) {
                    db.styleStatDao().putAll(listOf(StyleStatEntity("personality_notes", clean)))
                    // Keep the past: every distillation is archived so the user
                    // can look back at how their profile evolved (and for a
                    // future "year wrapped").
                    db.distillationDao().insert(
                        com.localboard.keyboard.data.db.DistillationEntity(
                            ts = System.currentTimeMillis(),
                            source = "nightly",
                            notes = clean
                        )
                    )
                    db.distillationDao().prune(90)
                }
                // Stamped with nothing to save as well: a rewrite that came out unusable would most
                // likely come out the same in a few hours, and each try is another model load.
                db.usageStatDao().put(
                    com.localboard.keyboard.data.db.UsageStatEntity(
                        LAST_RUN_STAT, System.currentTimeMillis()
                    )
                )
            }
            PhoneStats.noteNightlyRun(applicationContext, if (clean != null) outcome else NightlyOutcome.FAILED)
            Result.success()
        } catch (t: Throwable) {
            PhoneStats.noteNightlyRun(applicationContext, if (isStopped) NightlyOutcome.STOPPED else NightlyOutcome.FAILED)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    /** The rolling profile: capped well under 600 words (~1,000 chars) as
     *  the user asked — rich enough to describe a voice, small enough to
     *  blend into a tiny model's prompt. */
    private fun sanitize(raw: String): String? {
        val t = raw.trim().take(1000)
        if (t.length < 30) return null
        val plain = t.count { it in ' '..'~' || it == '\n' }
        if (plain.toFloat() / t.length < 0.9f) return null
        return t
    }

    /** Package name -> the label a human would recognise. Cached because the
     *  persona pass asks about the same handful of apps repeatedly and each
     *  miss is a binder call. */
    private val labelCache = HashMap<String, String>(8)

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        labelCache[pkg]?.let { return it }
        val label = try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            // Uninstalled since the log line was written: the bare package is
            // still more informative than dropping the thread entirely.
            pkg.substringAfterLast('.')
        }
        labelCache[pkg] = label
        return label
    }

    private fun batteryState(): Pair<Int, Boolean> {
        val i: Intent? = applicationContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return pct to charging
    }

    companion object {
        private const val WORK_NAME = "nightly_distillation"

        /** (Re)schedule the nightly job. Safe to call often. */
        fun schedule(context: Context) {
            // Idle keeps a model load off a phone in someone's hand. WorkManager's builder throws on backoff
            // criteria beside idle, and a throw here would leave the old job enqueued with no idle at all.
            // So there are none, and the job comes round often instead of retrying.
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()
            val request = PeriodicWorkRequestBuilder<NightlyDistillWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
