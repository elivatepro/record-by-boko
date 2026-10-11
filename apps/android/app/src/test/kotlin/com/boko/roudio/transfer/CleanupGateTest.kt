package com.boko.roudio.transfer

import com.boko.roudio.api.apis.BackupsApi
import com.boko.roudio.api.models.BackupsView
import com.boko.roudio.api.models.CleanupCandidate
import com.boko.roudio.api.models.CleanupList
import com.boko.roudio.api.models.CleanupReport
import com.boko.roudio.api.models.Settings
import com.boko.roudio.recorder.ExportException
import com.boko.roudio.recorder.FakeRecorderSource
import com.boko.roudio.store.CleanupState
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class CleanupGateTest {
    @get:Rule val folder = TemporaryFolder()

    private val serial = "8810000004F2A"
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private val source = FakeRecorderSource()
    private val store = MemoryRecordingStore()
    private val api = FakeBackups()

    private suspend fun onPhone(sessionId: Long, content: String): Pair<Recording, String> {
        val name = "$serial-$sessionId.opus"
        val file = File(folder.root, name).apply { writeText(content) }
        val sha = Transfers.sha256(file)
        store.addNew(
            listOf(
                Recording(
                    serial = serial, sessionId = sessionId, startedAt = now, zoneOffsetSeconds = 0, sizeOnRecorder = 4,
                    transfer = TransferState.Uploaded, fileName = name, fileBytes = file.length(), sha256 = sha,
                    firstSeenAt = now, updatedAt = now,
                ),
            ),
        )
        return store.all().last() to sha
    }

    private fun gate() = CleanupGate(api, source, store, RecordingFiles(folder.root)) { now }

    @Test
    fun `nothing is deleted while deleting is switched off`() = runTest {
        val (_, sha) = onPhone(1, "OggS")
        api.list = CleanupList(false, listOf(CleanupCandidate(UUID.randomUUID(), 1, sha, 4)))
        assertEquals(0, gate().run(serial))
        assertTrue(source.commands.none { it.startsWith("delete") })
    }

    @Test
    fun `a recording whose phone copy doesn't match stays on the recorder`() = runTest {
        onPhone(1, "OggS")
        api.list = CleanupList(true, listOf(CleanupCandidate(UUID.randomUUID(), 1, "0".repeat(64), 4)))
        assertEquals(0, gate().run(serial))
        assertTrue(source.commands.none { it.startsWith("delete") })
    }

    @Test
    fun `a safe recording is cleared from the recorder, never from the phone`() = runTest {
        val (recording, sha) = onPhone(1, "OggS")
        val id = UUID.randomUUID()
        api.list = CleanupList(true, listOf(CleanupCandidate(id, 1, sha, 4)))
        assertEquals(1, gate().run(serial))
        assertEquals(listOf("delete 1"), source.commands)
        assertEquals(listOf(id to CleanupReport.State.DELETING, id to CleanupReport.State.CLEARED), api.reports)
        assertEquals(CleanupState.Cleared, store.all().single().cleanup)
        assertTrue(File(folder.root, recording.fileName!!).exists())
    }

    @Test
    fun `a failed delete is reported and the rest wait`() = runTest {
        val (_, first) = onPhone(1, "OggS")
        val (_, second) = onPhone(2, "OggS-2")
        source.deleter = { throw ExportException("the recorder refused the delete (3)") }
        api.list = CleanupList(true, listOf(CleanupCandidate(UUID.randomUUID(), 1, first, 4), CleanupCandidate(UUID.randomUUID(), 2, second, 6)))
        assertEquals(0, gate().run(serial))
        assertEquals(listOf("delete 1"), source.commands)
        assertEquals(CleanupReport.State.FAILED, api.reports.last().second)
        assertEquals(CleanupState.CanCleanUp, store.all().first().cleanup)
    }
}

private class FakeBackups : BackupsApi {
    var list = CleanupList(false, emptyList())
    val reports = mutableListOf<Pair<UUID, CleanupReport.State>>()

    override suspend fun getCleanup(serial: String): Response<CleanupList> = Response.success(list)

    override suspend fun reportCleanup(recordingId: UUID, cleanupReport: CleanupReport): Response<Unit> {
        reports += recordingId to cleanupReport.state
        return Response.success(Unit)
    }

    override suspend fun getBackups(): Response<BackupsView> = throw NotImplementedError()

    override suspend fun getSettings(): Response<Settings> = throw NotImplementedError()

    override suspend fun putSettings(settings: Settings): Response<Settings> = throw NotImplementedError()

    override suspend fun retryBackup(recordingId: UUID): Response<Unit> = throw NotImplementedError()

    override suspend fun reprocess(recordingId: UUID): Response<Unit> = throw NotImplementedError()
}
