package com.localboard.keyboard.data

import com.localboard.keyboard.data.db.NGramDao
import com.localboard.keyboard.data.db.NGramEntity
import com.localboard.keyboard.data.db.NGramRows
import com.localboard.keyboard.data.db.NGramSql
import com.localboard.keyboard.data.db.StyleStatDao
import com.localboard.keyboard.data.db.StyleStatEntity
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * A version 5 database on a real SQLite, with the tables and indexes Room creates for that version
 * (copied from the generated AppDatabase_Impl). Room itself cannot run without a device, which is
 * acceptable here because version 6 changes no schema, so what is left to prove is the SQL.
 */
internal class V5Store : AutoCloseable {
    val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    init {
        SCHEMA.forEach(::exec)
    }

    fun exec(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    fun ngram(prev1: String, prev2: String, word: String, count: Int, lastUsed: Long) {
        db.prepareStatement("INSERT INTO ngrams VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, prev1)
            it.setString(2, prev2)
            it.setString(3, word)
            it.setInt(4, count)
            it.setLong(5, lastUsed)
            it.executeUpdate()
        }
    }

    /** One transaction, as the open helper runs a migration, so a failure leaves version 5 whole. */
    fun migrate(statements: List<String> = NGramRows.MIGRATION_5_TO_6) = inTransaction {
        statements.forEach(::exec)
    }

    fun <T> inTransaction(block: () -> T): T {
        db.autoCommit = false
        try {
            val out = block()
            db.commit()
            return out
        } catch (t: Throwable) {
            db.rollback()
            throw t
        } finally {
            db.autoCommit = true
        }
    }

    /** The count and last use of one row, or null when it is not there. */
    fun row(prev1: String, prev2: String, word: String): Pair<Int, Long>? =
        db.prepareStatement("SELECT count, lastUsed FROM ngrams WHERE prev1 = ? AND prev2 = ? AND word = ?").use {
            it.setString(1, prev1)
            it.setString(2, prev2)
            it.setString(3, word)
            it.executeQuery().use { r -> if (r.next()) r.getInt(1) to r.getLong(2) else null }
        }

    fun count(table: String, where: String = "1"): Int =
        db.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM $table WHERE $where").use { it.next(); it.getInt(1) }
        }

    fun counts(): Map<String, Int> = linkedMapOf(
        "unigram rows" to count("ngrams", "prev1 = '' AND prev2 = ''"),
        "bigram rows" to count("ngrams", "prev1 != '' AND prev2 = ''"),
        "trigram rows" to count("ngrams", "prev1 != '' AND prev2 != ''"),
        "malformed rows" to count("ngrams", "prev1 = '' AND prev2 != ''"),
        "style_stats" to count("style_stats"),
        "app_context" to count("app_context"),
        "usage_stats" to count("usage_stats"),
        "typing_log" to count("typing_log"),
        "distillations" to count("distillations"),
    )

    override fun close() = db.close()

    private companion object {
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `ngrams` (`prev1` TEXT NOT NULL, `prev2` TEXT NOT NULL, `word` TEXT NOT NULL, `count` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL, PRIMARY KEY(`prev1`, `prev2`, `word`))",
            "CREATE INDEX IF NOT EXISTS `index_ngrams_word` ON `ngrams` (`word`)",
            "CREATE INDEX IF NOT EXISTS `index_ngrams_prev1` ON `ngrams` (`prev1`)",
            "CREATE INDEX IF NOT EXISTS `index_ngrams_prev1_prev2` ON `ngrams` (`prev1`, `prev2`)",
            "CREATE TABLE IF NOT EXISTS `style_stats` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `app_context` (`packageName` TEXT NOT NULL, `topWordsJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
            "CREATE TABLE IF NOT EXISTS `usage_stats` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            "CREATE TABLE IF NOT EXISTS `typing_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `appPackage` TEXT NOT NULL, `screenKey` TEXT NOT NULL, `text` TEXT NOT NULL, `origin` INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_typing_log_ts` ON `typing_log` (`ts`)",
            "CREATE INDEX IF NOT EXISTS `index_typing_log_appPackage` ON `typing_log` (`appPackage`)",
            "CREATE TABLE IF NOT EXISTS `distillations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `source` TEXT NOT NULL, `notes` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_distillations_ts` ON `distillations` (`ts`)",
        )
    }
}

/** The DAO over a real SQLite, running the SQL text the app ships. */
internal open class JdbcNGramDao(private val db: Connection) : NGramDao {

    private inline fun <T> prepared(sql: String, block: PreparedStatement.() -> T): T =
        db.prepareStatement(sql).use { it.block() }

    private fun select(sql: String, limit: Int): List<NGramEntity> = prepared(sql) {
        setInt(1, limit)
        executeQuery().use { r ->
            buildList {
                while (r.next()) add(r.entity())
            }
        }
    }

    private fun ResultSet.entity() = NGramEntity(
        getString("prev1"), getString("prev2"), getString("word"), getInt("count"), getLong("lastUsed")
    )

    override suspend fun upsertAll(items: List<NGramEntity>) = prepared(
        "INSERT OR REPLACE INTO ngrams (prev1, prev2, word, count, lastUsed) VALUES (?, ?, ?, ?, ?)"
    ) {
        for (e in items) {
            setString(1, e.prev1)
            setString(2, e.prev2)
            setString(3, e.word)
            setInt(4, e.count)
            setLong(5, e.lastUsed)
            executeUpdate()
        }
    }

    override suspend fun increment(prev1: String, prev2: String, word: String, delta: Int, ts: Long): Int =
        prepared(NGramSql.INCREMENT) {
            setInt(1, delta)
            setLong(2, ts)
            setString(3, prev1)
            setString(4, prev2)
            setString(5, word)
            executeUpdate()
        }

    override suspend fun topUnigrams(limit: Int) = select(NGramSql.TOP_UNIGRAMS, limit)
    override suspend fun topBigrams(limit: Int) = select(NGramSql.TOP_BIGRAMS, limit)
    override suspend fun topTrigrams(limit: Int) = select(NGramSql.TOP_TRIGRAMS, limit)

    override suspend fun count(): Int = prepared("SELECT COUNT(*) FROM ngrams") {
        executeQuery().use { it.next(); it.getInt(1) }
    }

    override suspend fun wordCount(): Int = prepared(NGramSql.WORD_COUNT) {
        executeQuery().use { it.next(); it.getInt(1) }
    }

    override suspend fun totalWordTokens(): Long? =
        prepared("SELECT SUM(count) FROM ngrams WHERE prev1 = '' AND prev2 = ''") {
            executeQuery().use { it.next(); it.getLong(1) }
        }

    override suspend fun clear() {
        prepared("DELETE FROM ngrams") { executeUpdate() }
    }

    override suspend fun deleteWord(word: String) {
        prepared(NGramSql.DELETE_WORD) {
            setString(1, word)
            executeUpdate()
        }
    }

    override suspend fun deleteContexts(words: List<String>) {
        val slots = words.joinToString(", ") { "?" }
        prepared(NGramSql.DELETE_CONTEXTS.replace(":words", slots)) {
            (words + words).forEachIndexed { i, word -> setString(i + 1, word) }
            executeUpdate()
        }
    }

    override suspend fun deleteSpent(): Int = prepared(NGramSql.DELETE_SPENT) { executeUpdate() }

    override suspend fun upsertMax(prev1: String, prev2: String, word: String, count: Int, ts: Long) {
        throw UnsupportedOperationException("Nothing under test restores a backup")
    }

    override suspend fun addCount(prev1: String, prev2: String, word: String, delta: Int, ts: Long) {
        prepared(NGramSql.ADD_COUNT) {
            setString(1, prev1)
            setString(2, prev2)
            setString(3, word)
            setInt(4, delta)
            setLong(5, ts)
            executeUpdate()
        }
    }

    override suspend fun prune(olderThan: Long): Int = prepared(NGramSql.PRUNE) {
        setLong(1, olderThan)
        executeUpdate()
    }
}

internal class JdbcStyleStatDao(private val db: Connection) : StyleStatDao {
    override suspend fun putAll(items: List<StyleStatEntity>) {
        db.prepareStatement("INSERT OR REPLACE INTO style_stats (`key`, `value`) VALUES (?, ?)").use {
            for (e in items) {
                it.setString(1, e.key)
                it.setString(2, e.value)
                it.executeUpdate()
            }
        }
    }

    override suspend fun all(): List<StyleStatEntity> =
        db.createStatement().use { s ->
            s.executeQuery("SELECT `key`, `value` FROM style_stats").use { r ->
                buildList {
                    while (r.next()) add(StyleStatEntity(r.getString(1), r.getString(2)))
                }
            }
        }

    override suspend fun get(key: String): String? =
        db.prepareStatement("SELECT value FROM style_stats WHERE `key` = ?").use {
            it.setString(1, key)
            it.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }

    override suspend fun clear() {
        db.createStatement().use { it.execute("DELETE FROM style_stats") }
    }
}
