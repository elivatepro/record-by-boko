package com.boko.roudio.store

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A conversation or event the server wrote up, cached for offline reading (spec §2, "Sync").
 * [detail] is the server's ConversationDetail as JSON (transcript, items, speakers, note, Ask).
 */
@Entity(tableName = "conversations", indices = [Index("day"), Index("serverRecordingId")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val serverRecordingId: String,
    val serial: String,
    val sessionId: Long,
    /** Local day, yyyy-MM-dd. */
    val day: String,
    val kind: String,
    val title: String,
    val summary: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val startMs: Long,
    val endMs: Long,
    val resultsVersion: Int,
    val detail: String? = null,
)

/** A day's digest as the server's DigestView JSON. */
@Entity(tableName = "digests")
data class DigestEntity(
    @PrimaryKey val day: String,
    val state: String,
    val json: String,
    val updatedAt: Instant,
)

/** Offline full-text search over cached transcripts, summaries and notes (milestone 5). */
@Fts4
@Entity(tableName = "search_index")
data class SearchEntry(
    val conversationId: String,
    /** transcript, summary or note */
    val kind: String,
    val text: String,
    val atMs: Long,
)
