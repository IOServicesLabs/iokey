package com.localboard.keyboard.data

import com.localboard.keyboard.data.db.StoreFiles
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What happens to the store's files on disk, on real files, when each step can fail or be cut short. */
class StoreFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val files get() = StoreFiles(folder.root)
    private fun file(name: String) = File(folder.root, name)

    private fun bytes(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    private fun write(name: String, size: Int, fill: Int = 1) = file(name).writeBytes(bytes(size, fill))

    private fun assertHolds(name: String, size: Int, fill: Int) =
        assertArrayEquals("what $name holds", bytes(size, fill), file(name).readBytes())

    /** The shape the upgrade log shows: nearly everything still in the write-ahead log. */
    private fun aStoreMostlyInItsLog(fill: Int = 1) {
        write(STORE, 4_096, fill)
        write("$STORE-wal", 200_000, fill)
        write("$STORE-shm", 32_768, fill)
    }

    private fun assertThatStoreIsAsWritten(fill: Int = 1) {
        assertHolds(STORE, 4_096, fill)
        assertHolds("$STORE-wal", 200_000, fill)
        assertHolds("$STORE-shm", 32_768, fill)
    }

    private fun assertNothingAside() {
        val aside = folder.root.list()!!.filter { it.startsWith(ASIDE) }
        assertEquals("nothing may be left aside", emptyList<String>(), aside)
    }

    @Test
    fun aMoveTakesTheWriteAheadLogWithIt() {
        aStoreMostlyInItsLog()
        assertEquals(StoreFiles.SetAside.MOVED, files.setAsideAsked())
        assertHolds(ASIDE, 4_096, 1)
        assertHolds("$ASIDE-wal", 200_000, 1)
        assertHolds("$ASIDE-shm", 32_768, 1)
        assertFalse(file(STORE).exists())
        assertFalse(file("$STORE-wal").exists())
    }

    @Test
    fun theMainFileMovesLast() {
        aStoreMostlyInItsLog()
        val moved = ArrayList<String>()
        StoreFiles(folder.root) { from, to ->
            moved += from.name
            from.renameTo(to)
        }.setAsideAsked()
        assertEquals(STORE, moved.last())
        assertEquals(3, moved.size)
    }

    @Test
    fun aRenameThatFailsPutsBackWhatHadMoved() {
        aStoreMostlyInItsLog()
        val failing = StoreFiles(folder.root) { from, to -> from.name != STORE && from.renameTo(to) }
        assertEquals(StoreFiles.SetAside.FAILED, failing.setAsideAsked())
        assertThatStoreIsAsWritten()
        assertNothingAside()
    }

    private class Killed : RuntimeException()

    /** A kill at any point before the main file has moved leaves what the next open puts right. */
    @Test
    fun aKillAtAnyPointOfAMoveLosesNothing() {
        for (renamesBeforeTheKill in 0..2) {
            aStoreMostlyInItsLog()
            var renames = 0
            val killedPartWay = StoreFiles(folder.root) { from, to ->
                if (renames++ == renamesBeforeTheKill) throw Killed()
                from.renameTo(to)
            }
            try {
                killedPartWay.setAsideAsked()
            } catch (_: Killed) {
            }
            assertTrue("the next open must put it back", files.finishInterruptedMove())
            assertThatStoreIsAsWritten()
            assertNothingAside()
        }
    }

    @Test
    fun aMoveThatFinishedIsLeftAlone() {
        aStoreMostlyInItsLog()
        files.setAsideAsked()
        assertTrue(files.finishInterruptedMove())
        assertHolds("$ASIDE-wal", 200_000, 1)
        assertFalse(file("$STORE-wal").exists())
    }

    @Test
    fun aMoveCutShortDoesNotWriteOverALogTheStoreHasSince() {
        write(STORE, 4_096, 2)
        write("$STORE-wal", 100, 2)
        write("$ASIDE-wal", 200, 1)
        files.finishInterruptedMove()
        assertHolds("$STORE-wal", 100, 2)
    }

    @Test
    fun aDamagedStoreWithNoCopyIsMovedUnasked() {
        aStoreMostlyInItsLog()
        assertTrue(files.setAsideUnasked())
        assertHolds("$ASIDE-wal", 200_000, 1)
    }

    /** The real words already went aside, and what reads as damaged now only just started. */
    @Test
    fun aSmallerStoreDoesNotTakeThePlaceOfACopyUnasked() {
        write(ASIDE, 1_000_000, 9)
        aStoreMostlyInItsLog()
        assertFalse(files.setAsideUnasked())
        assertHolds(ASIDE, 1_000_000, 9)
        assertThatStoreIsAsWritten()
    }

    /** The log counts: a main file of 4 KB can be the bigger store. */
    @Test
    fun aStoreBiggerOnlyInItsLogStillTakesThePlaceOfACopyUnasked() {
        write(ASIDE, 100_000, 9)
        aStoreMostlyInItsLog()
        assertTrue(files.setAsideUnasked())
        assertHolds(ASIDE, 4_096, 1)
    }

    /** Written by a fallback, however big. It reads fine, so it is no damaged store, and no encrypted copy gives way to it. */
    @Test
    fun aPlaintextStoreNeverTakesThePlaceOfACopyUnasked() {
        write(ASIDE, 1_000, 9)
        file(STORE).writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + bytes(10_000, 1))
        assertTrue(files.isPlaintext())
        assertFalse(files.setAsideUnasked())
        assertHolds(ASIDE, 1_000, 9)
    }

    @Test
    fun anEmptyStoreIsNeverTheCopysReplacement() {
        write(ASIDE, 1_000, 9)
        write(STORE, 0)
        assertFalse(files.setAsideUnasked())
        assertHolds(ASIDE, 1_000, 9)

        assertEquals(StoreFiles.SetAside.COPY_KEPT, files.setAsideAsked())
        assertHolds(ASIDE, 1_000, 9)
        assertFalse(file(STORE).exists())
    }

    @Test
    fun anEmptyStoreWithNoCopyIsJustRemoved() {
        write(STORE, 0)
        assertTrue(files.setAsideUnasked())
        assertFalse(file(STORE).exists())
        assertNothingAside()
    }

    /** The second of two tentative tries, or a second tap, with the words safe from the first. */
    @Test
    fun startingFreshAgainKeepsTheCopyWithTheWordsInIt() {
        write(STORE, 2_000_000, 7)
        assertEquals(StoreFiles.SetAside.MOVED, files.setAsideAsked())

        write(STORE, 4_096, 2)
        write("$STORE-wal", 8_192, 2)
        assertEquals(StoreFiles.SetAside.COPY_KEPT, files.setAsideAsked())
        assertHolds(ASIDE, 2_000_000, 7)
        assertFalse(file(STORE).exists())
        assertFalse(file("$STORE-wal").exists())
    }

    /** It is the person's choice, and they were told a new set replaces the old. */
    @Test
    fun aStoreInRealUseMayReplaceALargerCopyWhenAsked() {
        write(ASIDE, 2_000_000, 9)
        write(STORE, (StoreFiles.NEAR_EMPTY_BYTES + 1).toInt(), 1)
        assertEquals(StoreFiles.SetAside.MOVED, files.setAsideAsked())
        assertHolds(ASIDE, (StoreFiles.NEAR_EMPTY_BYTES + 1).toInt(), 1)
    }

    @Test
    fun aPlaintextStoreDoesNotReplaceACopyWhenAskedEither() {
        write(ASIDE, 1_000, 9)
        file(STORE).writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + bytes(1_000_000, 1))
        assertEquals(StoreFiles.SetAside.COPY_KEPT, files.setAsideAsked())
        assertHolds(ASIDE, 1_000, 9)
    }

    @Test
    fun movingBackPutsTheOldStoreInPlaceOfTheOneThatFailed() {
        aStoreMostlyInItsLog()
        files.setAsideAsked()
        write(STORE, 500, 2)
        write("$STORE-wal", 500, 2)

        assertTrue(files.moveBack())
        assertThatStoreIsAsWritten()
        assertNothingAside()
    }

    /** The main file goes first this way, so what a kill leaves is what the next open takes back. */
    @Test
    fun aKillWhileMovingBackLosesNothing() {
        aStoreMostlyInItsLog()
        files.setAsideAsked()
        var renames = 0
        val killedPartWay = StoreFiles(folder.root) { from, to ->
            if (renames++ == 1) throw Killed()
            from.renameTo(to)
        }
        try {
            killedPartWay.moveBack()
        } catch (_: Killed) {
        }
        assertTrue(files.finishInterruptedMove())
        assertThatStoreIsAsWritten()
        assertNothingAside()
    }

    @Test
    fun deletingTheCopyLeavesNothingOfIt() {
        write(ASIDE, 100)
        write("$ASIDE-wal", 100)
        write("$ASIDE-shm", 100)
        write("$ASIDE-journal", 100)
        assertTrue(files.deleteCopy())
        assertNothingAside()
    }

    @Test
    fun deletingACopyThatIsNotThereIsNoFailure() {
        assertTrue(files.deleteCopy())
    }

    /** Side files left over must not be taken for the words of a later move. */
    @Test
    fun anEarlierCopyGoesWholeWhenAMoveReplacesIt() {
        write(ASIDE, 100, 9)
        write("$ASIDE-wal", 300_000, 9)
        write(STORE, 500_000, 1)
        assertEquals(StoreFiles.SetAside.MOVED, files.setAsideAsked())
        assertHolds(ASIDE, 500_000, 1)
        assertFalse("the old copy's log may not stay with the new copy", file("$ASIDE-wal").exists())
    }

    @Test
    fun anEncryptedStoreIsNotTakenForPlaintext() {
        write(STORE, 16_384, 0x5a)
        assertFalse(files.isPlaintext())
        write(STORE, 4)
        assertFalse("too short to be anything", files.isPlaintext())
        file(STORE).delete()
        assertFalse(files.isPlaintext())
    }

    private companion object {
        const val STORE = StoreFiles.DB_NAME
        const val ASIDE = StoreFiles.SET_ASIDE_NAME
    }
}
