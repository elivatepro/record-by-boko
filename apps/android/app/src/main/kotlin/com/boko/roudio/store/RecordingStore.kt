package com.boko.roudio.store

import kotlinx.coroutines.flow.Flow

/** Saved recordings and their track states. Room on the phone; a fake in tests. */
interface RecordingStore {
    val recordings: Flow<List<Recording>>

    /** Adds recordings not seen before; ones already saved are left as they are. */
    suspend fun addNew(recordings: List<Recording>)

    suspend fun inTransfer(state: TransferState): List<Recording>

    suspend fun byId(id: Long): Recording?

    suspend fun update(recording: Recording)

    suspend fun bySession(serial: String, sessionId: Long): Recording?
}

class RoomRecordingStore(private val dao: RecordingDao) : RecordingStore {
    override val recordings: Flow<List<Recording>> = dao.observeAll()

    override suspend fun addNew(recordings: List<Recording>) = dao.insertNew(recordings)

    override suspend fun inTransfer(state: TransferState) = dao.inTransfer(state)

    override suspend fun byId(id: Long) = dao.byId(id)

    override suspend fun update(recording: Recording) = dao.update(recording)

    override suspend fun bySession(serial: String, sessionId: Long) = dao.bySession(serial, sessionId)
}
