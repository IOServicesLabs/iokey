package com.localboard.keyboard.data.db

import java.io.File
import kotlin.math.min

/**
 * The store's files on disk and the one copy set aside from them, with no database in the way, so
 * that what happens to them when something fails part way can be tried on real files.
 *
 * The side files stay with their main file or the words in them are lost: a main file can be 4 KB
 * with everything since the last checkpoint in its write-ahead log. So the main file moves last and
 * is deleted last. That also makes the state after a kill readable: side files aside with no main
 * file beside them can only be a move that did not finish. See [finishInterruptedMove].
 *
 * @param rename stands in for [File.renameTo] when a test needs a rename to fail.
 */
internal class StoreFiles(
    private val dir: File,
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) {
    enum class SetAside {
        /** The store is the copy now, and any earlier copy is gone. */
        MOVED,

        /** The store held too little to take the place of the copy, or nothing, so it is gone and the copy stays. */
        COPY_KEPT,

        /** Everything is where it was, apart from an earlier copy that may have gone. */
        FAILED
    }

    val main: File get() = File(dir, DB_NAME)
    val copy: File get() = File(dir, SET_ASIDE_NAME)

    /** A failed open can leave an empty main file, and that holds nothing anyone could lose. */
    val hasContent: Boolean get() = main.length() > 0L

    /** Written with no encryption, by a start with no library or by the fallback in [AppDatabase]. */
    fun isPlaintext(): Boolean = try {
        main.inputStream().use { input ->
            val head = ByteArray(PLAIN_HEADER.length)
            input.read(head) == head.size && String(head, Charsets.ISO_8859_1) == PLAIN_HEADER
        }
    } catch (_: java.io.IOException) {
        false
    }

    /**
     * Finishes a move that a kill or a failed rename cut short, by taking it back. Run before every
     * open: a main file without its write-ahead log opens without complaint, missing whatever was in
     * that log, and the next move would have deleted the log for good.
     *
     * @return false when some of it could not be put back.
     */
    fun finishInterruptedMove(): Boolean {
        if (copy.exists()) return true
        return SIDE_FILES.map { suffix ->
            val aside = File(dir, SET_ASIDE_NAME + suffix)
            !aside.exists() || File(dir, DB_NAME + suffix).exists() || rename(aside, File(dir, DB_NAME + suffix))
        }.all { it }
    }

    /**
     * For a store that reads as damaged, with nobody asked. That reading can be wrong, and the store
     * may be one started after the real words went aside. So it never takes the place of a copy that
     * holds more, and it stays where it is for the person to decide about.
     *
     * @return false when the store is not out of the way.
     */
    fun setAsideUnasked(): Boolean {
        if (copy.exists() && (isPlaintext() || bytesIn(DB_NAME) < bytesIn(SET_ASIDE_NAME))) return false
        if (!hasContent) return deleteStore()
        return moveToCopy()
    }

    /** For a store the person gave up on, having been told what goes where. */
    fun setAsideAsked(): SetAside {
        val tooLittle = !hasContent || (
            copy.exists() &&
                (isPlaintext() || bytesIn(DB_NAME) < min(NEAR_EMPTY_BYTES, bytesIn(SET_ASIDE_NAME)))
            )
        if (tooLittle) return if (deleteStore()) SetAside.COPY_KEPT else SetAside.FAILED
        return if (moveToCopy()) SetAside.MOVED else SetAside.FAILED
    }

    /** Puts back a store that went aside, in place of the new one that did not work out. */
    fun moveBack(): Boolean {
        if (!deleteStore()) return false
        // The main file first this way: with none aside, what is left is what [finishInterruptedMove]
        // takes back, even if a kill lands before the next line.
        if (copy.exists() && !rename(copy, main)) return false
        return finishInterruptedMove()
    }

    /** False when some of it is still there. */
    fun deleteCopy(): Boolean =
        (SIDE_FILES + "").map { File(dir, SET_ASIDE_NAME + it) }.all { !it.exists() || it.delete() }

    /** False when some of it is still there. */
    fun deleteStore(): Boolean =
        (SIDE_FILES + "").map { File(dir, DB_NAME + it) }.map { !it.exists() || it.delete() }.all { it }

    /** Replaces any earlier copy. False when something could not be moved, and then what had moved is back. */
    private fun moveToCopy(): Boolean {
        if (!deleteCopy()) return false
        val moved = (SIDE_FILES + "").all { suffix ->
            val from = File(dir, DB_NAME + suffix)
            !from.exists() || rename(from, File(dir, SET_ASIDE_NAME + suffix))
        }
        if (!moved) {
            finishInterruptedMove()
            return false
        }
        // A move leaves the time of the last write, and the diagnostics say when this was.
        copy.setLastModified(System.currentTimeMillis())
        return true
    }

    private fun bytesIn(name: String): Long = File(dir, name).length() + File(dir, "$name-wal").length()

    companion object {
        const val DB_NAME = "localboard.db"

        /** Where a store goes that reads as damaged, or that the user gave up on. One copy: the next
         *  replaces it. It keeps SQLite's own names for the side files, so it can still be opened. */
        const val SET_ASIDE_NAME = "localboard-set-aside.db"
        private val SIDE_FILES = listOf("-journal", "-wal", "-shm")

        /** The first bytes of every SQLite file that is not encrypted. An encrypted one starts with its salt. */
        private const val PLAIN_HEADER = "SQLite format 3\u0000"

        /** A store and its write-ahead log this small, against a copy that holds more, is one that had
         *  only just started. */
        const val NEAR_EMPTY_BYTES = 256L * 1024
    }
}
