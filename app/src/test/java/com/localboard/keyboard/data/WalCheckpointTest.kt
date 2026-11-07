package com.localboard.keyboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager

/**
 * What the checkpoint on hide has to do on a real SQLite file, which is the same engine under the app's
 * encryption. A checkpoint does its work when the statement runs, so preparing and closing it folds nothing.
 */
class WalCheckpointTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun walStore(): Pair<Connection, java.io.File> {
        val file = folder.newFile("store.db")
        val db = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        db.createStatement().use {
            it.executeQuery("PRAGMA journal_mode=WAL").use { r -> r.next(); assertEquals("wal", r.getString(1).lowercase()) }
            it.execute("CREATE TABLE ngrams (word TEXT NOT NULL, filler TEXT NOT NULL)")
        }
        // Well under the automatic checkpoint, like the real store's 772 frames.
        db.autoCommit = false
        db.prepareStatement("INSERT INTO ngrams VALUES (?, ?)").use {
            for (i in 0 until 2000) {
                it.setString(1, "w$i")
                it.setString(2, "x".repeat(200))
                it.executeUpdate()
            }
        }
        db.commit()
        db.autoCommit = true
        return db to file
    }

    @Test
    fun preparingTheCheckpointFoldsNothingAndSteppingItDoes() {
        val (db, file) = walStore()
        db.use {
            val before = file.length()
            println("WAL checkpoint on a real file: ${before} bytes before")

            db.prepareStatement("PRAGMA wal_checkpoint(PASSIVE)").close()
            assertEquals("prepared and closed, it has not run", before, file.length())

            db.prepareStatement("PRAGMA wal_checkpoint(PASSIVE)").use { s ->
                s.executeQuery().use { r ->
                    r.next()
                    assertEquals("nothing was busy", 0, r.getInt(1))
                }
            }
            println("WAL checkpoint on a real file: ${file.length()} bytes after stepping it")
            assertTrue("the rows are in the main file now", file.length() > before + 2000 * 200)
        }
    }

    /** Setting it is done by the time the statement is prepared, which is why the open callback's pragmas work. */
    @Test
    fun anAutoCheckpointSettingTakesEffectWhenPrepared() {
        val (db, _) = walStore()
        db.use {
            db.prepareStatement("PRAGMA wal_autocheckpoint=2000").close()
            db.createStatement().use { s ->
                s.executeQuery("PRAGMA wal_autocheckpoint").use { r ->
                    r.next()
                    assertEquals(2000, r.getInt(1))
                }
            }
        }
    }
}
