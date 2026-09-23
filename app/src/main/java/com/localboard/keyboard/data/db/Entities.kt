package com.localboard.keyboard.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A learned n-gram. prev1 = word immediately before, prev2 = word before that.
 * Empty strings mark unigrams (both empty) and bigrams (prev2 empty).
 */
@Entity(
    tableName = "ngrams",
    primaryKeys = ["prev1", "prev2", "word"],
    indices = [Index("word"), Index("prev1"), Index("prev1", "prev2")]
)
data class NGramEntity(
    val prev1: String,
    val prev2: String,
    val word: String,
    val count: Int,
    val lastUsed: Long
)

/** Rolling style statistics, serialized as simple key -> value pairs. */
@Entity(tableName = "style_stats")
data class StyleStatEntity(
    @PrimaryKey val key: String,
    val value: String
)

/** Per-app learned vocabulary so predictions adapt to where the user types. */
@Entity(tableName = "app_context")
data class AppContextEntity(
    @PrimaryKey val packageName: String,
    /** JSON array of [word, count] pairs, top ~40 words for this app. */
    val topWordsJson: String,
    val updatedAt: Long
)

/** Minimal, local-only usage counters for the privacy dashboard. */
@Entity(tableName = "usage_stats")
data class UsageStatEntity(
    @PrimaryKey val key: String,
    val value: Long
)

/**
 * The private typing log: raw committed text fragments, kept for a few days
 * and distilled into the personal profile by the overnight worker. Lives in
 * the same Keystore-encrypted database as everything else; the app holds no
 * internet permission, so these bytes can never leave the device. Fragments
 * from apps on the user's blocklist are never written here.
 */
@Entity(
    tableName = "typing_log",
    indices = [Index("ts"), Index("appPackage")]
)
data class TypingLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val appPackage: String,
    /** Which screen/conversation inside the app (first line of the capture —
     *  usually the chat title). "" when unknown. Lets the AI recall what you
     *  typed in THIS conversation specifically. */
    val screenKey: String = "",
    val text: String,
    /** 0 = typed by the user; 1 = AI-originated (an accepted WIFM/compose
     *  draft). AI text must never feed voice examples or style learning —
     *  otherwise the model trains on its own output and the "your voice"
     *  it mirrors drifts into generic-assistant prose. */
    val origin: Int = 0
)


/**
 * One past distillation of the user's writing profile. Every time the
 * nightly worker (or an SMS re-distill) produces a new profile, the OLD
 * understanding is kept here — a look-back-in-time history the user can
 * browse in settings (and the raw material for a future "year wrapped").
 * Same encrypted database as everything else; never leaves the device.
 */
@Entity(
    tableName = "distillations",
    indices = [Index("ts")]
)
data class DistillationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    /** "nightly" | "sms" — where this profile came from. */
    val source: String,
    val notes: String
)
