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
    version = 5,
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
        private const val DB_NAME = "localboard.db"

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

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildWithRecovery(context).also { instance = it }
            }
        }

        /** Load libsqlcipher.so — sqlcipher-android 4.5.x ships NO
         *  auto-loader, so skipping this crashes the first query with
         *  UnsatisfiedLinkError. */
        private fun ensureNativeLoaded(): Boolean {
            if (nativeLoaded) return encryptionActive
            return try {
                System.loadLibrary("sqlcipher")
                encryptionActive = true
                nativeLoaded = true
                true
            } catch (t: Throwable) {
                encryptionActive = false
                nativeLoaded = true
                false
            }
        }

        private fun builder(context: Context, encrypted: Boolean): RoomDatabase.Builder<AppDatabase> {
            val b = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
                val passphrase = KeystoreManager.getDatabasePassphrase(context.applicationContext)
                b.openHelperFactory(SupportOpenHelperFactory(passphrase))
            }
            return b
        }

        private fun buildWithRecovery(context: Context): AppDatabase {
            val canEncrypt = ensureNativeLoaded()

            if (canEncrypt) {
                // First open can still fail (e.g. Keystore key invalidated and
                // regenerated -> stale passphrase vs existing db file). Force an
                // open now; on failure, delete the unreadable file and start
                // fresh rather than crash-loop forever.
                try {
                    val db = builder(context, encrypted = true).build()
                    db.openHelper.writableDatabase
                    return db
                } catch (t: Throwable) {
                    // Only an unreadable file justifies destroying months of
                    // learned typing data — a transient failure (OOM, a busy
                    // disk, a Keystore hiccup) must propagate, not wipe.
                    if (!isCorruptionFailure(t)) throw t
                    runCatching { instance?.close() }
                    instance = null
                    context.applicationContext.getDatabasePath(DB_NAME)?.let { f: File ->
                        f.delete()
                        File(f.parentFile, "$DB_NAME-journal").delete()
                        File(f.parentFile, "$DB_NAME-wal").delete()
                        File(f.parentFile, "$DB_NAME-shm").delete()
                    }
                    return try {
                        val db = builder(context, encrypted = true).build()
                        db.openHelper.writableDatabase
                        db
                    } catch (t2: Throwable) {
                        if (!isCorruptionFailure(t2)) throw t2
                        encryptionActive = false
                        openPlaintext(context)
                    }
                }
            }
            return openPlaintext(context)
        }

        /** True when [t] means the stored data is genuinely unreadable:
         *  SQLite corruption, the SQLCipher "file is not a database" wrong-
         *  passphrase error, or the Keystore layer failing to unwrap the
         *  passphrase (AEADBadTagException). Walks the cause chain because
         *  Room wraps open failures. */
        private fun isCorruptionFailure(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                if (cur is android.database.sqlite.SQLiteDatabaseCorruptException ||
                    cur is net.zetetic.database.sqlcipher.SQLiteNotADatabaseException ||
                    cur is javax.crypto.AEADBadTagException ||
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
                encryptionActive = false
                Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
            }
        }
    }
}
