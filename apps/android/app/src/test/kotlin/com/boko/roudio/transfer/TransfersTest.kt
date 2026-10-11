package com.boko.roudio.transfer

import com.boko.roudio.recorder.ExportException
import com.boko.roudio.recorder.FakeRecorderSource
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.MemoryRecorderStore
import com.boko.roudio.recorder.Recorder
import com.boko.roudio.recorder.RecorderEvent
import com.boko.roudio.recorder.RecorderFile
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.store.Recording
import com.boko.roudio.store.RecordingStore
import com.boko.roudio.store.TransferState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TransfersTest {
    @get:Rule val folder = TemporaryFolder()

    private val serial = "8810000004F2A"
    private val now = Instant.parse("2026-10-11T09:00:00Z")
    private val older = RecorderFile(1_791_700_000, 30_000)
    private val newer = RecorderFile(1_791_710_000, 8_000)
    private val source = FakeRecorderSource()
    private val store = MemoryRecordingStore()

    private fun TestScope.setUp(): Pair<Recorder, Transfers> {
        val recorder = Recorder(
            source,
            MemoryRecorderStore(RecorderState(linked = LinkedRecorder(serial, RecorderModel.NotePro))),
            backgroundScope,
        ) { now }
        val io = StandardTestDispatcher(testScheduler)
        val transfers = Transfers(
            source, recorder, store, RecordingFiles(folder.root), backgroundScope, io, { now }, { ZoneId.of("Africa/Lagos") },
        )
        runCurrent()
        return recorder to transfers
    }

    private suspend fun TestScope.connect(recording: Boolean, since: Instant? = null) {
        source.emit(RecorderEvent.Connected(serial))
        source.emit(RecorderEvent.Recording(recording, since))
        runCurrent()
    }

    @Test
    fun `new recordings are saved and brought across oldest first, with their checksum`() = runTest {
        source.files = listOf(newer, older)
        val (_, transfers) = setUp()
        connect(recording = false)
        transfers.sync()
        runCurrent()

        assertEquals(listOf("listFiles", "export ${older.sessionId}", "export ${newer.sessionId}"), source.commands.filter { it != "refresh" })
        val saved = store.all().associateBy { it.sessionId }
        val first = saved.getValue(older.sessionId)
        assertEquals(TransferState.OnPhone, first.transfer)
        assertEquals(Transfers.sha256(File(folder.root, first.fileName!!)), first.sha256)
        assertEquals(4L, first.fileBytes)
        assertEquals(Instant.ofEpochSecond(older.sessionId), first.startedAt)
        assertEquals(3600, first.zoneOffsetSeconds)
        assertFalse(folder.root.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `the file still being recorded is left alone`() = runTest {
        source.files = listOf(older, newer)
        val (_, transfers) = setUp()
        connect(recording = true, since = Instant.ofEpochSecond(newer.sessionId))
        transfers.sync()
        runCurrent()

        assertEquals(listOf(older.sessionId), store.all().map { it.sessionId })
        assertFalse(source.commands.contains("export ${newer.sessionId}"))
    }

    @Test
    fun `nothing is picked until the recorder says whether it's recording`() = runTest {
        source.files = listOf(older)
        val (_, transfers) = setUp()
        source.emit(RecorderEvent.Connected(serial))
        runCurrent()
        transfers.sync()
        assertFalse(source.commands.contains("listFiles"))
    }

    @Test
    fun `a failed export throws the partial file away, keeps the recording on the recorder and stops`() = runTest {
        source.files = listOf(older, newer)
        source.exporter = { _, target, _ ->
            target.writeText("half")
            throw ExportException("the recorder disconnected")
        }
        val (_, transfers) = setUp()
        connect(recording = false)
        transfers.sync()
        runCurrent()

        val first = store.all().first { it.sessionId == older.sessionId }
        assertEquals(TransferState.OnRecorder, first.transfer)
        assertEquals(1, first.transferAttempts)
        assertEquals("the recorder disconnected", first.transferError)
        assertNull(first.fileName)
        assertFalse("no partial file left", folder.root.listFiles()!!.any())
        assertFalse("the next one waits", source.commands.contains("export ${newer.sessionId}"))
        assertNull(transfers.progress.value)
    }

    @Test
    fun `a recording already on the phone isn't exported again`() = runTest {
        source.files = listOf(older)
        val (_, transfers) = setUp()
        connect(recording = false)
        transfers.sync()
        runCurrent()
        transfers.sync()
        runCurrent()
        assertEquals(1, source.commands.count { it == "export ${older.sessionId}" })
    }

    @Test
    fun `a transfer cut off by the app stopping restarts from the beginning`() = runTest {
        store.addNew(
            listOf(
                Recording(
                    serial = serial, sessionId = older.sessionId, startedAt = now, zoneOffsetSeconds = 0,
                    sizeOnRecorder = 1, transfer = TransferState.Transferring, firstSeenAt = now, updatedAt = now,
                ),
            ),
        )
        File(folder.root, "$serial-${older.sessionId}.opus.part").writeText("half")
        val (_, transfers) = setUp()
        transfers.start()
        runCurrent()

        assertEquals(TransferState.OnRecorder, store.all().single().transfer)
        assertFalse(folder.root.listFiles()!!.any { it.name.endsWith(".part") })
        transfers.stop()
    }

    @Test
    fun `a new connection starts a check by itself`() = runTest {
        source.files = listOf(older)
        val (_, transfers) = setUp()
        transfers.start()
        runCurrent()
        connect(recording = false)
        runCurrent()
        assertTrue(source.commands.contains("export ${older.sessionId}"))
        transfers.stop()
    }
}

class MemoryRecordingStore : RecordingStore {
    private val rows = MutableStateFlow<List<Recording>>(emptyList())
    private var nextId = 1L

    fun all() = rows.value

    override val recordings: Flow<List<Recording>> = rows

    override suspend fun addNew(recordings: List<Recording>) {
        val fresh = recordings.filter { r -> rows.value.none { it.serial == r.serial && it.sessionId == r.sessionId } }
        rows.value = rows.value + fresh.map { it.copy(id = nextId++) }
    }

    override suspend fun inTransfer(state: TransferState) = rows.value.filter { it.transfer == state }.sortedBy { it.startedAt }

    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }

    override suspend fun update(recording: Recording) {
        rows.value = rows.value.map { if (it.id == recording.id) recording else it }
    }
}
