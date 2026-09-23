package com.localboard.keyboard.context

import android.content.Context
import com.localboard.keyboard.data.db.AppDatabase
import com.localboard.keyboard.data.db.TypingLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The private typing log: raw committed text fragments in the Keystore-
 * encrypted database (SQLCipher AES-256 when available). The only network
 * traffic in this app is the model download the user explicitly starts;
 * typing data is never transmitted anywhere. The only reader is the
 * on-device model pipeline (context fallback and the nightly distillation
 * worker). Fragments are pruned after a few days, and apps on the user's
 * blocklist are never written.
 */
class TypingLog(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { AppDatabase.get(appContext) }

    suspend fun log(text: String, appPackage: String, screenKey: String = "") =
        withContext(Dispatchers.IO) {
            val clean = text.trim()
            if (clean.length < 2) return@withContext
            try {
                db.typingLogDao().insert(
                    TypingLogEntity(
                        ts = System.currentTimeMillis(),
                        appPackage = appPackage,
                        screenKey = screenKey,
                        text = clean.take(500)
                    )
                )
            } catch (_: Throwable) { }
        }

    /** One queued typing-log line. [origin]: 0 = human-typed, 1 = AI draft —
     *  AI rows are stored (the privacy dashboard shows what the keyboard
     *  wrote) but every style-recall query filters them out. */
    data class PendingLine(
        val text: String,
        val appPackage: String,
        val screenKey: String,
        val origin: Int = 0
    )

    /** Batch insert for the keyboard's write coalescing — one encrypted-DB
     *  transaction per typing burst instead of one per committed word. */
    suspend fun logAll(lines: List<PendingLine>) = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val entities = lines.mapIndexedNotNull { i, line ->
            val clean = line.text.trim()
            if (clean.length < 2) null
            else TypingLogEntity(
                ts = now + i, appPackage = line.appPackage,
                screenKey = line.screenKey, text = clean.take(500),
                origin = line.origin
            )
        }
        if (entities.isEmpty()) return@withContext
        try {
            db.typingLogDao().insertAll(entities)
        } catch (_: Throwable) { }
    }

    /** Newest-first fragments for one app — the "what was recently typed
     *  here" fallback when the editor won't share its on-screen text. */
    suspend fun recentForApp(appPackage: String, limit: Int = 12): List<String> =
        withContext(Dispatchers.IO) {
            try {
                db.typingLogDao().recentForApp(appPackage, limit).map { it.text }
            } catch (_: Throwable) { emptyList() }
        }

    /** Newest-first fragments across all (non-blocked) apps since [sinceTs]. */
    suspend fun since(sinceTs: Long, limit: Int = 400): List<TypingLogEntity> =
        withContext(Dispatchers.IO) {
            try { db.typingLogDao().since(sinceTs, limit) } catch (_: Throwable) { emptyList() }
        }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        try { db.typingLogDao().count() } catch (_: Throwable) { 0 }
    }

    /** Keep only the last [days] days — the log is a rolling window, not an archive. */
    suspend fun prune(days: Int = 7) = withContext(Dispatchers.IO) {
        try {
            db.typingLogDao().pruneOlderThan(System.currentTimeMillis() - days * 86_400_000L)
        } catch (_: Throwable) { }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try { db.typingLogDao().clear() } catch (_: Throwable) { }
    }

    suspend fun clearApp(appPackage: String) = withContext(Dispatchers.IO) {
        try { db.typingLogDao().clearApp(appPackage) } catch (_: Throwable) { }
    }
}
