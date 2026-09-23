package com.localboard.keyboard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NGramDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<NGramEntity>)

    @Query(
        "UPDATE ngrams SET count = count + :delta, lastUsed = :ts " +
            "WHERE prev1 = :prev1 AND prev2 = :prev2 AND word = :word"
    )
    suspend fun increment(prev1: String, prev2: String, word: String, delta: Int, ts: Long): Int

    @Query("SELECT * FROM ngrams WHERE prev1 = '' AND prev2 = '' ORDER BY count DESC LIMIT :limit")
    suspend fun topUnigrams(limit: Int): List<NGramEntity>

    @Query("SELECT * FROM ngrams WHERE prev2 = '' ORDER BY count DESC LIMIT :limit")
    suspend fun topBigrams(limit: Int): List<NGramEntity>

    @Query("SELECT * FROM ngrams WHERE prev2 != '' ORDER BY count DESC LIMIT :limit")
    suspend fun topTrigrams(limit: Int): List<NGramEntity>

    @Query("SELECT COUNT(*) FROM ngrams")
    suspend fun count(): Int

    @Query("SELECT SUM(count) FROM ngrams WHERE prev1 = '' AND prev2 = ''")
    suspend fun totalWordTokens(): Long?

    @Query("DELETE FROM ngrams")
    suspend fun clear()

    /** Delete every n-gram that ends in [word] (unigram, bigrams, trigrams). */
    @Query("DELETE FROM ngrams WHERE word = :word")
    suspend fun deleteWord(word: String)

    /** Delete only [word]'s unigram row — strike-1 typo demotion drops the
     *  bare count so the demotion survives a restart, while any bigram/trigram
     *  context rows stay (the word itself is only benched, not hanged). */
    @Query("DELETE FROM ngrams WHERE prev1 = '' AND prev2 = '' AND word = :word")
    suspend fun deleteUnigram(word: String)

    /** Dictionary import with MAX semantics: a re-import never lowers an
     *  existing count (unlike upsertAll, which is REPLACE-on-conflict). */
    @Query(
        "INSERT INTO ngrams (prev1, prev2, word, count, lastUsed) " +
            "VALUES (:prev1, :prev2, :word, :count, :ts) " +
            "ON CONFLICT(prev1, prev2, word) DO UPDATE SET count = MAX(count, excluded.count)"
    )
    suspend fun upsertMax(prev1: String, prev2: String, word: String, count: Int, ts: Long)

    /** Keep the personal model bounded: prune stale, rarely-used entries. */
    @Query("DELETE FROM ngrams WHERE count <= 1 AND lastUsed < :olderThan")
    suspend fun prune(olderThan: Long): Int
}

@Dao
interface StyleStatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(items: List<StyleStatEntity>)

    @Query("SELECT * FROM style_stats")
    suspend fun all(): List<StyleStatEntity>

    @Query("DELETE FROM style_stats")
    suspend fun clear()
}

@Dao
interface AppContextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: AppContextEntity)

    @Query("SELECT * FROM app_context WHERE packageName = :pkg")
    suspend fun get(pkg: String): AppContextEntity?

    @Query("SELECT * FROM app_context ORDER BY updatedAt DESC")
    suspend fun all(): List<AppContextEntity>

    /** Dashboard counts APPS, not the per-thread rows (P0). */
    @Query("SELECT COUNT(*) FROM app_context WHERE packageName NOT LIKE '%|%'")
    suspend fun count(): Int

    /** P0: keep the [keep] most recent thread rows per app; the table was
     *  append-only — one row per conversation, forever. */
    @Query(
        "DELETE FROM app_context WHERE packageName LIKE :pkg || '|%' AND updatedAt NOT IN " +
            "(SELECT updatedAt FROM app_context WHERE packageName LIKE :pkg || '|%' " +
            "ORDER BY updatedAt DESC LIMIT :keep)"
    )
    suspend fun pruneThreads(pkg: String, keep: Int): Int

    /** P0: global cap on thread rows across all apps. */
    @Query(
        "DELETE FROM app_context WHERE packageName LIKE '%|%' AND updatedAt NOT IN " +
            "(SELECT updatedAt FROM app_context WHERE packageName LIKE '%|%' " +
            "ORDER BY updatedAt DESC LIMIT :keep)"
    )
    suspend fun pruneAllThreads(keep: Int): Int

    @Query("DELETE FROM app_context WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String): Int

    /** The app row plus every per-thread row ("pkg|threadKey", B8). */
    @Query("DELETE FROM app_context WHERE packageName = :pkg OR packageName LIKE :pkg || '|%'")
    suspend fun deleteByPackageAndThreads(pkg: String): Int

    @Query("DELETE FROM app_context")
    suspend fun clear()
}

@Dao
interface UsageStatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: UsageStatEntity)

    @Query("SELECT * FROM usage_stats")
    suspend fun all(): List<UsageStatEntity>

    @Query("SELECT value FROM usage_stats WHERE `key` = :key")
    suspend fun get(key: String): Long?

    /** Atomic accumulate — one statement instead of a read-then-write pair
     *  (the old flush did two encrypted-DB round trips per key). */
    @Query(
        "INSERT INTO usage_stats (`key`, value) VALUES (:key, :delta) " +
            "ON CONFLICT(`key`) DO UPDATE SET value = value + excluded.value"
    )
    suspend fun addDelta(key: String, delta: Long)

    @Query("DELETE FROM usage_stats")
    suspend fun clear()
}

@Dao
interface TypingLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TypingLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<TypingLogEntity>): List<Long>

    /** Newest-first fragments since [sinceTs], capped. Human-typed only —
     *  AI-originated drafts (origin = 1) are kept for the privacy dashboard
     *  but must never feed style recall. */
    @Query("SELECT * FROM typing_log WHERE ts >= :sinceTs AND origin = 0 ORDER BY ts DESC LIMIT :limit")
    suspend fun since(sinceTs: Long, limit: Int): List<TypingLogEntity>

    /** Newest-first fragments for one app — the on-screen-context fallback. */
    @Query("SELECT * FROM typing_log WHERE appPackage = :pkg AND origin = 0 ORDER BY ts DESC LIMIT :limit")
    suspend fun recentForApp(pkg: String, limit: Int): List<TypingLogEntity>

    /** Newest-first fragments for one conversation/screen inside an app. */
    @Query("SELECT * FROM typing_log WHERE appPackage = :pkg AND screenKey = :screen AND origin = 0 ORDER BY ts DESC LIMIT :limit")
    suspend fun recentForAppAndScreen(pkg: String, screen: String, limit: Int): List<TypingLogEntity>

    @Query("DELETE FROM typing_log WHERE ts < :olderThan")
    suspend fun pruneOlderThan(olderThan: Long): Int

    @Query("SELECT COUNT(*) FROM typing_log")
    suspend fun count(): Int

    @Query("DELETE FROM typing_log")
    suspend fun clear()

    @Query("DELETE FROM typing_log WHERE appPackage = :pkg")
    suspend fun clearApp(pkg: String): Int
}


@Dao
interface DistillationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DistillationEntity): Long

    /** Newest-first history for the settings profile viewer. */
    @Query("SELECT * FROM distillations ORDER BY ts DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<DistillationEntity>

    /** Keep the history bounded. */
    @Query("DELETE FROM distillations WHERE id NOT IN (SELECT id FROM distillations ORDER BY ts DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    /** Wipe one bad profile from the history (user-driven, per-row). */
    @Query("DELETE FROM distillations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM distillations")
    suspend fun clear()
}
