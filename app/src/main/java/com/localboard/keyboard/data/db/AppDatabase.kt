package com.localboard.keyboard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.localboard.keyboard.util.KeystoreManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [
        NGramEntity::class,
        StyleStatEntity::class,
        AppContextEntity::class,
        UsageStatEntity::class,
        TypingLogEntity::class,
        DistillationEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ngramDao(): NGramDao
    abstract fun styleStatDao(): StyleStatDao
    abstract fun appContextDao(): AppContextDao
    abstract fun usageStatDao(): UsageStatDao
    abstract fun typingLogDao(): TypingLogDao
    abstract fun distillationDao(): DistillationDao

    companion object {
        private const val DB_NAME = StoreFiles.DB_NAME

        /** v1 -> v2: add the encrypted typing log table. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `typing_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`appPackage` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_typing_log_ts` ON `typing_log` (`ts`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_typing_log_appPackage` " +
                        "ON `typing_log` (`appPackage`)"
                )
            }
        }

        /** v2 -> v3: keep every past profile distillation (the look-back history). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `distillations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`notes` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_distillations_ts` ON `distillations` (`ts`)"
                )
            }
        }

        /** v3 -> v4: tag typing-log fragments with the screen/conversation
         *  they were typed in (per-app-screen context for the AI). */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `typing_log` ADD COLUMN `screenKey` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v4 -> v5: typing_log gains `origin` (0 human / 1 AI) so accepted
         *  AI drafts can be excluded from style recall — the model must
         *  never train on its own output. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `typing_log` ADD COLUMN `origin` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v5 -> v6: no schema change. Rebuilds the n-gram rows that v5 never wrote, so that
         *  what the user typed before this version still loads: see [NGramRows.MIGRATION_5_TO_6].
         *  The open helper runs a migration in one transaction, so a failure leaves v5 as it was
         *  and the next open tries again. */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                NGramRows.MIGRATION_5_TO_6.forEach { db.execSQL(it) }
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** False when SQLCipher native code failed to load and we degraded
         *  to a plain (still fully on-device) database. Surfaced in the
         *  privacy dashboard. */
        @Volatile
        var encryptionActive: Boolean = true
            private set

        /** True once the native lib load was attempted. */
        @Volatile
        private var nativeLoaded = false

        @Volatile
        private var nativeAvailable = false

        /** The library that reads the store failed to load, which no retry in this process can change. */
        val libraryMissing: Boolean get() = nativeLoaded && !nativeAvailable

        /** What made this process move an unreadable store aside, for the diagnostics. */
        @Volatile
        var movedAsideBecause: Throwable? = null
            private set

        /**
         * Runs when a real store has just opened. Anyone can be the one who opens it, since every
         * [get] tries while there is no instance, and whoever keeps the words needs to hear of it or
         * goes on treating a healthy store as a closed one. It must not block.
         */
        @Volatile
        var onStoreOpened: (() -> Unit)? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildWithRecovery(context).also {
                    instance = it
                    if (it.openHelper.databaseName != null) onStoreOpened?.invoke()
                }
            }
        }

        /** The store that was set aside, or null when there is none. */
        fun setAsideCopy(context: Context): File? = storeFiles(context).copy.takeIf { it.exists() }

        /** Erase has to reach this copy too. False when some of it is still there. */
        fun deleteSetAsideCopy(context: Context): Boolean = synchronized(this) {
            storeFiles(context).deleteCopy()
        }

        /**
         * The user gave up on a store that will not open. It goes aside like a damaged one, and a new
         * one opens. Nothing else ever does this for them, because the failure may pass, and the words
         * with it. A passphrase that still unwraps is kept, so that the copy can still be read with it.
         *
         * @throws Throwable when no new store opened. A store that went aside and its passphrase are
         * then back as they were, so the next try and the next start find what this one found. What
         * making room took is not back: an earlier copy that store was to replace, or a store too
         * small to replace the copy, which [StoreFiles.setAsideAsked] deletes.
         */
        fun startFresh(context: Context) {
            synchronized(this) {
                val app = context.applicationContext
                // Without the library a new store would be a plaintext one, and the file set aside
                // is one a build with the library could still read.
                check(ensureNativeLoaded()) { "the library that opens the store did not load" }
                runCatching { instance?.close() }
                instance = null
                val files = storeFiles(app)
                val passphraseBefore = KeystoreManager.stored(app)
                val setAside = files.setAsideAsked()
                if (setAside == StoreFiles.SetAside.FAILED) throw java.io.IOException("the store could not be moved")
                KeystoreManager.forgetPassphraseIfUnreadable(app)
                try {
                    val db = get(app)
                    db.openHelper.writableDatabase
                    checkNotNull(db.openHelper.databaseName) { "only a temporary store in memory could be opened" }
                } catch (t: Throwable) {
                    runCatching { instance?.close() }
                    instance = null
                    // The passphrase first: a kill in between must not leave the old store back with the wrong one.
                    KeystoreManager.putBack(app, passphraseBefore)
                    if (setAside == StoreFiles.SetAside.MOVED) files.moveBack() else files.deleteStore()
                    throw t
                }
            }
        }

        /**
         * For Erase, when the store will not open and so cannot be emptied: the person asked for this
         * data to go, and that takes no key. False when some of it is still there.
         */
        fun deleteStore(context: Context): Boolean = synchronized(this) {
            runCatching { instance?.close() }
            instance = null
            val app = context.applicationContext
            storeFiles(app).deleteStore().also { gone ->
                if (gone) KeystoreManager.forgetPassphraseIfUnreadable(app)
            }
        }

        /** True when a real store on disk is open, whoever it was that got it open. */
        fun isOpenOnDisk(): Boolean = instance?.openHelper?.databaseName != null

        /**
         * Another try for someone who found the store closed. A stand-in in memory from an earlier
         * try goes first, since [get] would otherwise go on handing it out.
         */
        fun tryAgain(context: Context): AppDatabase = synchronized(this) {
            instance?.takeIf { it.openHelper.databaseName == null }?.let {
                runCatching { it.close() }
                instance = null
            }
            get(context)
        }

        private fun storeFiles(context: Context) =
            StoreFiles(context.applicationContext.getDatabasePath(DB_NAME).absoluteFile.parentFile!!)

        /** Load libsqlcipher.so — sqlcipher-android 4.5.x ships NO
         *  auto-loader, so skipping this crashes the first query with
         *  UnsatisfiedLinkError. */
        private fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return nativeAvailable
            nativeAvailable = try {
                System.loadLibrary("sqlcipher")
                true
            } catch (t: Throwable) {
                false
            }
            encryptionActive = nativeAvailable
            nativeLoaded = true
            return nativeAvailable
        }

        private fun builder(context: Context, encrypted: Boolean): RoomDatabase.Builder<AppDatabase> {
            val b = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        // WAL tuning, which matters more here than in a normal
                        // app because SQLCipher DECRYPTS EVERY PAGE IT READS.
                        // A WAL that grows without checkpointing means each
                        // prediction lookup walks (and decrypts) more pages —
                        // the device was carrying a 3 MB WAL against a 4.3 MB
                        // database.
                        //
                        // synchronous=NORMAL is the documented-safe setting in
                        // WAL mode: it drops the fsync on every commit while
                        // still being crash-safe (only a power loss can lose
                        // the last transactions, which for a typing model is
                        // an acceptable trade against an fsync per learned
                        // word).
                        // autocheckpoint is raised, NOT lowered. Lowering it
                        // shrinks the WAL by checkpointing more often — which
                        // means more write amplification *while typing*, the
                        // exact moment we are trying to protect. Raising it
                        // defers automatic checkpoints, and ContextEngine
                        // .checkpoint() folds the log back explicitly when the
                        // keyboard hides, where nobody is waiting.
                        try {
                            db.query("PRAGMA synchronous=NORMAL").close()
                            db.query("PRAGMA wal_autocheckpoint=2000").close()
                        } catch (_: Throwable) {
                            // A pragma that a build refuses is not fatal.
                        }
                    }
                })
            if (encrypted) {
                val app = context.applicationContext
                val passphrase = KeystoreManager.getDatabasePassphrase(
                    app, storeHasContent = app.getDatabasePath(DB_NAME).length() > 0L
                )
                b.openHelperFactory(SupportOpenHelperFactory(passphrase))
            }
            return b
        }

        private fun openEncrypted(context: Context): AppDatabase {
            val db = builder(context, encrypted = true).build()
            try {
                db.openHelper.writableDatabase
            } catch (t: Throwable) {
                // This runs again at every retry, and each try must not leave a helper open.
                runCatching { db.close() }
                throw t
            }
            encryptionActive = true
            return db
        }

        private fun buildWithRecovery(context: Context): AppDatabase {
            val files = storeFiles(context)
            files.finishInterruptedMove()
            val canEncrypt = ensureNativeLoaded()

            // SQLCipher reads a plaintext store as not a database, but the store is fine and holds
            // what was typed into it. Treated as damaged it would go aside over the real words.
            if (files.isPlaintext()) {
                encryptionActive = false
                return openPlaintext(context)
            }
            if (canEncrypt) {
                // First open can still fail (e.g. a wrong passphrase against an
                // existing db file, or a damaged page the migration read). Force
                // an open now; on failure, move the unreadable file aside and
                // start a new one rather than crash-loop forever. It is never
                // deleted: months of learned typing have no other copy.
                try {
                    return openEncrypted(context)
                } catch (t: Throwable) {
                    // Only an unreadable file justifies leaving months of learned
                    // typing behind — a transient failure (OOM, a busy disk, a
                    // Keystore hiccup) must propagate, and the next start tries again.
                    if (!isCorruptionFailure(t)) throw t
                    runCatching { instance?.close() }
                    instance = null
                    if (!files.setAsideUnasked()) throw t
                    movedAsideBecause = t
                    return try {
                        openEncrypted(context)
                    } catch (t2: Throwable) {
                        if (!isCorruptionFailure(t2)) throw t2
                        encryptionActive = false
                        openPlaintext(context)
                    }
                }
            }
            // With no library the store on disk reads as damaged, and the framework's
            // helper deletes a file it finds damaged on the way in, before anything here
            // could step in. The keyboard runs from memory and the file waits for a build
            // that can read it.
            if (files.main.exists()) return inMemory(context)
            return openPlaintext(context)
        }

        /** True when [t] means the stored data is genuinely unreadable: SQLite
         *  corruption or the SQLCipher "file is not a database" wrong-passphrase
         *  error. A Keystore failure to unwrap the passphrase is not one: it says
         *  nothing about the file, which is fine and only waits for its key. Walks
         *  the cause chain because Room wraps open failures. */
        private fun isCorruptionFailure(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is android.database.sqlite.SQLiteDatabaseCorruptException ||
                    cur is net.zetetic.database.sqlcipher.SQLiteNotADatabaseException ||
                    (cur.message?.contains("file is not a database") == true)
                ) return true
                cur = cur.cause
            }
            return false
        }

        /** Plain (unencrypted) open, mirrored on the encrypted branch: force
         *  the first query NOW, inside a try/catch. Without this, a device
         *  that can't even open a plaintext Room db (or that holds an
         *  encrypted file while the native lib failed to load) crashed on the
         *  first lazy query from ContextEngine. Fall back to an in-memory
         *  database instead: the keyboard keeps working, nothing persists,
         *  and ContextEngine degrades to persistenceAvailable=false. */
        private fun openPlaintext(context: Context): AppDatabase {
            return try {
                val db = builder(context, encrypted = false).build()
                db.openHelper.writableDatabase
                db
            } catch (t: Throwable) {
                inMemory(context)
            }
        }

        private fun inMemory(context: Context): AppDatabase {
            encryptionActive = false
            return Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }
    }
}
