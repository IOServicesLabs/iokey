package com.localboard.keyboard.context

import kotlin.math.min

/**
 * How the store has been doing, in plain words for the diagnostics, and when to try it again. A
 * failed write is retried rather than ending persistence, because a full disk or a busy database
 * passes. Without this, learning stopped silently until the process died.
 *
 * A store that would not open is also what a failed migration looks like. Nothing is lost by it: the
 * migration runs in a transaction, and the next try runs it again. Tries come from the keyboard
 * showing and hiding, at the pace [takeOpenRetry] sets, and what is typed meanwhile waits for them.
 */
class StorageHealth(
    private val now: () -> Long = System::currentTimeMillis,
    private val remembered: StoreFailureMemory = StoreFailureMemory.ForThisProcess()
) {
    /** Failures in a row since a write last worked. */
    @Volatile
    var failures = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var openError: String? = null

    /** The open failed because the key to the store did not work, not because of the file. */
    @Volatile
    private var keyFailed = false

    @Volatile
    private var diskFull = false

    @Volatile
    private var libraryMissing = false

    /** Why saved words did not reach the model at this start, when the store opened but the read
     *  failed. A later write working does not put them back, so this stays until the next start. */
    @Volatile
    private var loadError: String? = null

    @Volatile
    private var retryAt = 0L

    @Volatile
    private var openRetryAt = 0L
    private var openRetriesTaken = 0

    fun writeFailed(t: Throwable) {
        failures++
        lastError = describe(t)
        retryAt = now() + backoffMs(failures)
    }

    fun writeWorked() {
        failures = 0
        lastError = null
        retryAt = 0L
    }

    /**
     * A try at opening the store failed. [storageShort] says the phone is nearly out of space, which
     * a failure on a full disk does not always say itself. [libraryMissing] says no try can work.
     */
    fun openFailed(t: Throwable, storageShort: Boolean = false, libraryMissing: Boolean = false) {
        openError = describe(t)
        keyFailed = isKeyFailure(t)
        // A key that does not work is not cured by free space, however little there is.
        diskFull = isDiskFull(t) || (storageShort && !keyFailed)
        this.libraryMissing = libraryMissing
    }

    /** The first open of a process failed. This is what counts towards offering Start fresh. */
    fun startFailed(t: Throwable, storageShort: Boolean = false, libraryMissing: Boolean = false) {
        openFailed(t, storageShort, libraryMissing)
        remembered.write(remembered.read().afterFailedStart(now()))
        openRetriesTaken = 1
        openRetryAt = now() + OPEN_RETRY_GAPS_MS[0]
    }

    /** True when it is time for another try at opening, and this then counts as that try. */
    @Synchronized
    fun takeOpenRetry(): Boolean {
        if (openError == null || libraryMissing || now() < openRetryAt) return false
        openRetryAt = now() + OPEN_RETRY_GAPS_MS[openRetriesTaken.coerceAtMost(OPEN_RETRY_GAPS_MS.size - 1)]
        openRetriesTaken++
        return true
    }

    /** The store opened, so the episode is over, and the next one starts counting from nothing. */
    fun storeOpened() {
        writeWorked()
        openError = null
        keyFailed = false
        diskFull = false
        libraryMissing = false
        loadError = null
        openRetriesTaken = 0
        openRetryAt = 0L
        if (remembered.read() != StoreFailureRecord()) remembered.write(StoreFailureRecord())
    }

    fun loadFailed(t: Throwable) {
        loadError = describe(t)
    }

    /** A new store took the place of one that would not open, so nothing here is true any more. */
    fun storeReplaced() = storeOpened()

    /** How long a write has to wait before trying again. */
    fun msUntilRetry(): Long = (retryAt - now()).coerceAtLeast(0L)

    /** What the person can do about the store right now. */
    fun advice(): StoreAdvice = when {
        openError == null -> StoreAdvice.NONE
        libraryMissing -> StoreAdvice.UPDATE_APP
        diskFull -> StoreAdvice.FREE_SPACE
        remembered.read().hasLastedLongEnough(now()) -> StoreAdvice.START_FRESH
        else -> StoreAdvice.WAIT
    }

    /** What to tell the person from the keyboard, once in an episode and only when it has lasted. */
    fun noticeDue(): StoreAdvice? {
        if (openError == null) return null
        val record = remembered.read()
        return if (!record.noticeShown && record.hasLastedLongEnough(now())) advice() else null
    }

    fun noticeShown() {
        remembered.write(remembered.read().copy(noticeShown = true))
    }

    /** What is wrong right now, or null when nothing is. */
    fun problem(): String? {
        openError?.let {
            if (libraryMissing) {
                return "Saved words could not be opened, because part of IOKey did not load on this " +
                    "phone ($it). What you type is learned for now but not saved. Nothing was deleted."
            }
            if (diskFull) {
                return "Saved words could not be opened, because the phone is out of storage ($it). " +
                    "What you type is learned for now but not saved. Nothing was deleted. The " +
                    "keyboard keeps trying, so freeing up some space is all this needs."
            }
            if (keyFailed) {
                return "Saved words could not be opened, because the key that unlocks them did not " +
                    "work ($it). What you type is learned for now but not saved. Nothing was " +
                    "deleted, and the keyboard keeps trying. If this does not clear up, the key may " +
                    "be gone for good, and Settings then offers Start fresh, the way to have words " +
                    "saved again."
            }
            val wayOut = if (advice() == StoreAdvice.START_FRESH) {
                " If this does not clear up, Start fresh in Settings is the way to have words saved again."
            } else ""
            return "Saved words could not be opened ($it). What you type is learned for now but not " +
                "saved. Nothing was deleted, and the keyboard keeps trying.$wayOut"
        }
        val load = loadError?.let {
            "Saved words could not be read at start ($it), so the keyboard does not know them until " +
                "it restarts. Nothing was deleted."
        }
        val write = lastError?.let {
            val times = if (failures == 1) "once" else "$failures times in a row"
            "Reading or saving words failed $times ($it). The keyboard keeps what you type and tries again."
        }
        return listOfNotNull(load, write).joinToString(" ").ifEmpty { null }
    }

    companion object {
        private const val FIRST_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 10L * 60 * 1000
        private const val MINUTE_MS = 60_000L

        /** Each try reads the Keystore and may run a migration, so a store that stays closed gets
         *  fewer of them, down to one an hour. */
        private val OPEN_RETRY_GAPS_MS = longArrayOf(1 * MINUTE_MS, 5 * MINUTE_MS, 30 * MINUTE_MS, 60 * MINUTE_MS)

        /** 10 s after the first failure, doubling up to ten minutes. */
        fun backoffMs(failures: Int): Long =
            min(MAX_BACKOFF_MS, FIRST_BACKOFF_MS shl failures.coerceIn(1, 16))

        /** True when the store said it is out of space. Walks the causes, because Room wraps what SQLite threw. */
        fun isDiskFull(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                val message = cur.message.orEmpty()
                if (cur.javaClass.simpleName == "SQLiteFullException" ||
                    DISK_FULL_WORDS.any { message.contains(it, ignoreCase = true) }
                ) return true
                cur = cur.cause
            }
            return false
        }

        private val DISK_FULL_WORDS = listOf("disk is full", "SQLITE_FULL", "No space left", "ENOSPC")

        /** True when unwrapping the store's passphrase is what failed. Every way the Keystore
         *  and its ciphers say no comes down to one of these two. */
        fun isKeyFailure(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is java.security.GeneralSecurityException || cur is java.security.ProviderException) return true
                cur = cur.cause
            }
            return false
        }

        /** The deepest cause is what the database said, the rest is whoever wrapped it. */
        fun describe(t: Throwable): String {
            var root = t
            while (true) {
                val next = root.cause
                if (next == null || next === root) break
                root = next
            }
            // On one line: the Keystore's messages run to several, with blank ones between.
            val message = root.message?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
            return if (message != null) "${root.javaClass.simpleName}: $message"
            else root.javaClass.simpleName
        }
    }
}

/** On the Intent that opens Settings from the keyboard's notice about the store. */
const val EXTRA_SHOW_STORE_NOTE = "com.localboard.keyboard.extra.SHOW_STORE_NOTE"

/** What the person can do about a store that will not open. */
enum class StoreAdvice {
    /** The store is open. */
    NONE,

    /** It has not been failing for long, and it may well pass. The keyboard keeps trying. */
    WAIT,

    /** It has lasted, and giving up the old words is a fair choice to offer. */
    START_FRESH,

    /** Free space is what it needs. Moving the store away would give up words that would come back by themselves. */
    FREE_SPACE,

    /** No try can work in this build, and the file waits for one that can read it. */
    UPDATE_APP
}

/**
 * What one failed start tells the next. It stays out of the store, which is what it is about.
 *
 * @param failingSince by the wall clock. A change of clock only moves when the offer appears.
 */
data class StoreFailureRecord(
    val failedStarts: Int = 0,
    val failingSince: Long = 0L,
    val noticeShown: Boolean = false
) {
    fun afterFailedStart(now: Long): StoreFailureRecord = copy(
        failedStarts = failedStarts + 1,
        failingSince = if (failingSince == 0L) now else failingSince
    )

    /** The point where a failure has stopped looking like something that passes. */
    fun hasLastedLongEnough(now: Long): Boolean =
        failedStarts >= OFFER_AFTER_FAILED_STARTS ||
            (failingSince != 0L && now - failingSince >= OFFER_AFTER_FAILING_MS)

    companion object {
        const val OFFER_AFTER_FAILED_STARTS = 3
        const val OFFER_AFTER_FAILING_MS = 24L * 60 * 60 * 1000
    }
}

/** Where [StoreFailureRecord] is kept between starts. */
interface StoreFailureMemory {
    fun read(): StoreFailureRecord
    fun write(record: StoreFailureRecord)

    class ForThisProcess : StoreFailureMemory {
        @Volatile
        private var record = StoreFailureRecord()
        override fun read() = record
        override fun write(record: StoreFailureRecord) {
            this.record = record
        }
    }
}

/**
 * Plain prefs of its own, like the wrapped passphrase's. In the main prefs a backup would carry the
 * count to another start, and an erase that could not reach the store would forget it was failing.
 */
class PrefsStoreFailureMemory(context: android.content.Context) : StoreFailureMemory {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    override fun read() = StoreFailureRecord(
        prefs.getInt(KEY_FAILED_STARTS, 0),
        prefs.getLong(KEY_FAILING_SINCE, 0L),
        prefs.getBoolean(KEY_NOTICE_SHOWN, false)
    )

    /** commit(), since the process that fails to start is as likely as any to be killed right after. */
    override fun write(record: StoreFailureRecord) {
        prefs.edit()
            .putInt(KEY_FAILED_STARTS, record.failedStarts)
            .putLong(KEY_FAILING_SINCE, record.failingSince)
            .putBoolean(KEY_NOTICE_SHOWN, record.noticeShown)
            .commit()
    }

    private companion object {
        const val FILE = "localboard_store_failure"
        const val KEY_FAILED_STARTS = "failed_starts"
        const val KEY_FAILING_SINCE = "failing_since"
        const val KEY_NOTICE_SHOWN = "notice_shown"
    }
}

/**
 * Which style_stats rows may be saved. Some rows are saved whole from what memory holds, so a save
 * from a memory that never got to read the row replaces it, for good, with next to nothing. That
 * would make "Nothing was deleted" untrue the moment the store takes writes again.
 *
 * [wholeRows] are read by the first load, and the rest by whoever asks for them.
 */
class StoredRowGuard(private val wholeRows: Set<String>) {
    @Volatile
    private var loaded = false
    private val unread = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The first load has read [wholeRows] into memory. */
    fun loadRead() {
        loaded = true
    }

    /** Asking for [key] failed, so whoever asked holds none of what is stored under it. */
    fun readFailed(key: String) {
        unread.add(key)
    }

    fun readWorked(key: String) {
        unread.remove(key)
    }

    /** The store holds nothing now, so no save can lose anything. */
    fun storeEmptied() {
        loaded = true
        unread.clear()
    }

    fun maySave(key: String): Boolean = key !in unread && (loaded || key !in wholeRows)
}
