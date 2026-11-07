package com.localboard.keyboard.data

import com.localboard.keyboard.data.db.NGramRows
import com.localboard.keyboard.util.BackupManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a chat import and an old backup may do to counts already stored. Words are invented or ordinary. */
class ChatImportAndRestoreTest {

    /** A word typed 200 times and found 3 times in an export is at 203, as it is in memory, and stays there. */
    @Test
    fun anImportAddsToWhatIsStored() = runBlocking {
        V5Store().use { s ->
            val dao = JdbcNGramDao(s.db)
            s.ngram("", "", "zorblax", 200, 10)
            s.ngram("so", "", "zorblax", 40, 10)

            dao.addCount("", "", "zorblax", 3, 20)
            dao.addCount("so", "", "zorblax", 1, 5)
            dao.addCount("", "", "kubectl", 2, 20)

            assertEquals(203 to 20L, s.row("", "", "zorblax"))
            assertEquals("the last use never goes back", 41 to 10L, s.row("so", "", "zorblax"))
            assertEquals("a new word starts at what was found", 2 to 20L, s.row("", "", "kubectl"))
        }
    }

    /** A kill in the middle leaves the store as it was. The app runs this in a Room transaction. */
    @Test
    fun anImportThatFailsPartWayLeavesNothing() = runBlocking {
        V5Store().use { s ->
            val dao = JdbcNGramDao(s.db)
            s.ngram("", "", "zorblax", 200, 10)
            try {
                s.inTransaction {
                    runBlocking { dao.addCount("", "", "zorblax", 3, 20) }
                    throw IllegalStateException("killed")
                }
            } catch (_: IllegalStateException) {
            }
            assertEquals(200 to 10L, s.row("", "", "zorblax"))
        }
    }

    private val oldFile = BackupManager.Payload(
        prefs = emptyMap(),
        words = listOf(
            "mirble" to 9, "moonlight" to 3, "Teh" to 2, "don" to 40, "haven" to 12, "won" to 30, "can" to 30, "won't" to 5
        ),
        version = 1,
    )

    @Test
    fun anOldBackupDoesNotBringBackWhatTheUpgradeTookOut() {
        assertEquals(
            listOf("moonlight" to 3, "won" to NGramRows.WON_COUNT_LIMIT, "can" to 30, "won't" to 5),
            BackupManager.wordsToRestore(oldFile, setOf("mirble", "teh")),
        )
    }

    /** Whatever it holds was typed again after the upgrade, so it is wanted. */
    @Test
    fun aBackupFromAfterTheUpgradeIsLeftWhole() {
        val newFile = oldFile.copy(version = 2)
        assertEquals(oldFile.words, BackupManager.wordsToRestore(newFile, setOf("mirble", "teh")))
    }

    @Test
    fun theFragmentsNoBackupMayBringBackIncludeTheRarerContractions() {
        for (fragment in listOf("ain", "mustn", "needn", "mightn", "isn", "ve")) {
            assertEquals(fragment, false, BackupManager.restorableWord(fragment))
        }
        assertEquals(true, BackupManager.restorableWord("moonlight"))
    }
}
