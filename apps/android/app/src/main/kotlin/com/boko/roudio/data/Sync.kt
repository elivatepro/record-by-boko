package com.boko.roudio.data

import com.boko.roudio.api.models.ItemView
import com.boko.roudio.ui.conversation.ConversationModel
import android.util.Log
import com.boko.roudio.api.apis.LibraryApi
import com.boko.roudio.api.apis.OrganiseApi
import com.boko.roudio.api.infrastructure.Serializer
import com.boko.roudio.api.models.ConversationDetail
import com.boko.roudio.api.models.ConversationSummary
import com.boko.roudio.api.models.DigestView
import com.boko.roudio.api.models.RecordingSummary
import com.boko.roudio.store.BackupState
import com.boko.roudio.store.CleanupState
import com.boko.roudio.store.ConversationEntity
import com.boko.roudio.store.DigestEntity
import com.boko.roudio.store.LibraryDao
import com.boko.roudio.store.ProcessingState
import com.boko.roudio.store.RecordingStore
import com.boko.roudio.store.SearchEntry
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the last sync left off, so the next one only asks for what changed. */
interface SyncCursor {
    var since: OffsetDateTime?
}

/**
 * Pulls processed results down (spec §2, "Sync"): mirrors each recording's processing, backup and
 * clean-up state, and caches conversations (with their transcript, items and search index) and
 * digests, so reading and search work offline. The server is the source of truth for results.
 */
class Sync(
    private val organise: OrganiseApi,
    private val library: LibraryApi,
    private val recordings: RecordingStore,
    private val dao: LibraryDao,
    private val cursor: SyncCursor,
) {
    private val lock = Mutex()
    private val _badges = MutableStateFlow(Badges())

    /** Unread Updates and Review items waiting, for the bell and the tab (from the last sync). */
    val badges: StateFlow<Badges> = _badges.asStateFlow()

    data class Badges(val unreadUpdates: Int = 0, val waitingReview: Int = 0)

    /** Returns false when the server couldn't be reached; nothing local is lost then. */
    suspend fun run(): Boolean = lock.withLock {
        try {
            val response = organise.sync(cursor.since)
            val view = response.body()
            if (!response.isSuccessful || view == null) return false
            for (recording in view.recordings) mirror(recording)
            for (digest in view.digests) refreshDay(LocalDate.parse(digest.day))
            _badges.value = Badges(view.unreadUpdates, view.waitingReview)
            cursor.since = view.serverTime
            true
        } catch (e: IOException) {
            Log.i(TAG, "sync: no connection")
            false
        }
    }

    /** Fetch one conversation again (after an edit, a rename or a new Ask). */
    suspend fun refreshConversation(id: String): ConversationDetail? {
        val response = library.getConversation(java.util.UUID.fromString(id))
        val detail = response.body() ?: return null
        saveDetail(detail)
        return detail
    }

    private suspend fun mirror(server: RecordingSummary) {
        recordings.bySession(server.recorderSerial, server.sessionId)?.let { local ->
            recordings.update(
                local.copy(
                    serverId = server.id.toString(),
                    processing = ProcessingState.fromServer(server.processingState),
                    processingError = server.processingError,
                    backup = BackupState.fromServer(server.backupState),
                    backupError = server.backupError,
                    cleanup = CleanupState.fromServer(server.cleanupState),
                    resultsVersion = server.resultsVersion,
                    updatedAt = Instant.now(),
                ),
            )
        }
        val cached = dao.conversationsOf(server.id.toString())
        // Conversations always have someone speaking; no people means the detail predates them.
        val upToDate = cached.isNotEmpty() &&
            cached.all { it.resultsVersion == server.resultsVersion && it.detail != null && it.people.isNotEmpty() }
        if (server.resultsVersion == 0 || upToDate) return
        val list = organise.getRecordingConversations(server.id).body() ?: return
        dao.putConversations(list.conversations.map { entity(it, server) })
        dao.dropConversationsExcept(server.id.toString(), list.conversations.map { it.id.toString() }.ifEmpty { listOf("") })
        for (conversation in list.conversations) {
            library.getConversation(conversation.id).body()?.let { saveDetail(it) }
        }
    }

    private suspend fun saveDetail(detail: ConversationDetail) {
        val id = detail.conversation.id.toString()
        if (dao.conversation(id) == null) dao.putConversations(listOf(entity(detail.conversation, detail.recording)))
        dao.putDetail(
            id,
            Serializer.kotlinxSerializationJson.encodeToString(ConversationDetail.serializer(), detail),
            people = ConversationModel.speakerNames(detail).values.joinToString(", "),
            decisions = detail.items.count { it.kind == ItemView.Kind.DECISION },
        )
        dao.clearSearch(id)
        dao.putSearch(
            buildList {
                add(SearchEntry(id, "summary", detail.conversation.title + " — " + detail.conversation.summary, detail.conversation.startMs))
                detail.segments.forEach { add(SearchEntry(id, "transcript", it.text, it.startMs)) }
                detail.note?.let { add(SearchEntry(id, "note", it, detail.conversation.startMs)) }
            },
        )
    }

    private suspend fun refreshDay(day: LocalDate) {
        val view = library.getDay(day).body() ?: return
        val digest = view.digest ?: return
        dao.putDigest(
            DigestEntity(
                day = day.toString(),
                state = digest.state.value,
                json = Serializer.kotlinxSerializationJson.encodeToString(DigestView.serializer(), digest),
                updatedAt = Instant.now(),
            ),
        )
    }

    private fun entity(c: ConversationSummary, r: RecordingSummary) = ConversationEntity(
        id = c.id.toString(),
        serverRecordingId = r.id.toString(),
        serial = r.recorderSerial,
        sessionId = r.sessionId,
        day = c.day.toString(),
        kind = c.kind,
        title = c.title,
        summary = c.summary,
        startedAt = c.startedAt.toInstant(),
        endedAt = c.endedAt.toInstant(),
        startMs = c.startMs,
        endMs = c.endMs,
        resultsVersion = r.resultsVersion,
    )

    companion object {
        private const val TAG = "Sync"

        fun decodeDetail(json: String): ConversationDetail =
            Serializer.kotlinxSerializationJson.decodeFromString(ConversationDetail.serializer(), json)

        fun decodeDigest(json: String): DigestView =
            Serializer.kotlinxSerializationJson.decodeFromString(DigestView.serializer(), json)
    }
}
