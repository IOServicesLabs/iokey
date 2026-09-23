package com.localboard.keyboard.llm

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.BackoffPolicy
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
import com.localboard.keyboard.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Overnight distillation: while the phone is charging and (nearly) full,
 * read the last two days of the encrypted typing log and let the on-device
 * model distill a short "who this person is as a writer" note. That note is
 * blended into every future generation alongside the measured style profile.
 *
 * Runs under WorkManager constraints (charging + battery not low), checks
 * for ~full charge inside, and retries later otherwise. All data stays in
 * the Keystore-encrypted database on this device; typing data is never
 * transmitted anywhere.
 */
class NightlyDistillWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.overnightDistillation) return Result.success()

        // "Charging overnight and at max charge": WorkManager only fires this
        // while charging; if the battery isn't nearly full yet, check back later.
        val (level, charging) = batteryState()
        if (!charging) return Result.success()
        if (level in 0 until 90) {
            return if (runAttemptCount < 5) Result.retry() else Result.success()
        }

        val log = TypingLog(applicationContext)
        log.prune(days = 7)
        val since = System.currentTimeMillis() - 48 * 3_600_000L
        val fragments = log.since(since, 400)
        if (fragments.size < 12) return Result.success()

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
                AppDatabase.get(applicationContext).styleStatDao()
                    .putAll(listOf(StyleStatEntity("persona_notes", persona)))
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
        val notes = if (!ModelRegistry.isReady(applicationContext, spec)) {
            StyleProfiler.fallbackProfile(facts)
        } else {
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
                if (rewritten.isBlank() || !StyleProfiler.isConsistent(rewritten, facts))
                    StyleProfiler.fallbackProfile(facts)
                else rewritten.trim()
            } catch (t: Throwable) {
                StyleProfiler.fallbackProfile(facts)
            } finally {
                try { provider.releaseModel("nightly distill done") }
                catch (_: Throwable) { }
            }
        }
        return try {
            val clean = sanitize(notes)
            if (clean != null) {
                val db = AppDatabase.get(applicationContext)
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
                db.usageStatDao().put(
                    com.localboard.keyboard.data.db.UsageStatEntity(
                        "last_distillation", System.currentTimeMillis()
                    )
                )
            }
            Result.success()
        } catch (t: Throwable) {
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
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<NightlyDistillWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
