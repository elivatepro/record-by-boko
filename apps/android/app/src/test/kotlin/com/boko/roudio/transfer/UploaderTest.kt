package com.boko.roudio.transfer

import com.boko.roudio.api.apis.RecordingsApi
import com.boko.roudio.api.models.RecordingUpload
import com.boko.roudio.api.models.RecordingUploadRequest
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class UploaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-11T10:00:00Z")
    private val store = MemoryRecordingStore()
    private val server = FakeServer(partBytes = 4)
    private val files by lazy { RecordingFiles(folder.root) }

    private suspend fun onPhone(content: String, sessionId: Long = 1_791_710_034): Recording {
        val name = "8810000004F2A-$sessionId.opus"
        val file = File(folder.root, name).apply { writeText(content) }
        store.addNew(
            listOf(
                Recording(
                    serial = "8810000004F2A", sessionId = sessionId, startedAt = Instant.ofEpochSecond(sessionId),
                    zoneOffsetSeconds = 3600, sizeOnRecorder = file.length(), transfer = TransferState.OnPhone,
                    fileName = name, fileBytes = file.length(), sha256 = Transfers.sha256(file), durationMs = 7200,
                    firstSeenAt = now, updatedAt = now,
                ),
            ),
        )
        return store.all().last()
    }

    private fun uploader() = Uploader(server, store, files) { now }

    @Test
    fun `a recording goes up in parts and is uploaded once the server's check passes`() = runTest {
        onPhone("OggS0123456789")
        assertEquals(Uploader.Outcome.Done, uploader().uploadAll())

        val saved = store.all().single()
        assertEquals(TransferState.Uploaded, saved.transfer)
        assertNull(saved.uploadError)
        assertEquals("OggS0123456789", server.stored.values.single())
        assertEquals(listOf(1, 2, 3, 4), server.partsSent)
        assertEquals(3600, server.lastRequest?.zoneOffsetSeconds)
    }

    @Test
    fun `an interrupted upload resumes from the next part the server wants`() = runTest {
        onPhone("OggS0123456789")
        server.failPart = 3
        assertEquals(Uploader.Outcome.Retry, uploader().uploadAll())
        val halfway = store.all().single()
        assertEquals(TransferState.Uploading, halfway.transfer)
        assertEquals(8L, halfway.uploadedBytes)

        server.failPart = null
        server.partsSent.clear()
        assertEquals(Uploader.Outcome.Done, uploader().uploadAll())
        assertEquals(listOf(3, 4), server.partsSent)
        assertEquals(TransferState.Uploaded, store.all().single().transfer)
    }

    @Test
    fun `no server means try later, with the phone copy kept`() = runTest {
        onPhone("OggS")
        server.down = true
        assertEquals(Uploader.Outcome.Retry, uploader().uploadAll())
        val saved = store.all().single()
        assertEquals(TransferState.Uploading, saved.transfer)
        assertEquals(1, saved.uploadAttempts)
        assertEquals(true, File(folder.root, saved.fileName!!).exists())
    }

    @Test
    fun `a checksum mismatch starts the upload again once`() = runTest {
        onPhone("OggS0123")
        server.corruptNextComplete = true
        assertEquals(Uploader.Outcome.Done, uploader().uploadAll())
        assertEquals(TransferState.Uploaded, store.all().single().transfer)
        assertEquals(2, server.starts)
    }

    @Test
    fun `a refusal is noted and the next recording still goes up`() = runTest {
        onPhone("OggS", sessionId = 1)
        onPhone("OggS-two", sessionId = 2)
        server.refuseSession = 1
        assertEquals(Uploader.Outcome.Done, uploader().uploadAll())
        val (first, second) = store.all()
        assertEquals("A different file is already on the server.", first.uploadError)
        assertEquals(TransferState.Uploading, first.transfer)
        assertEquals(TransferState.Uploaded, second.transfer)
    }

    @Test
    fun `a recording whose phone copy is gone is skipped`() = runTest {
        val recording = onPhone("OggS")
        File(folder.root, recording.fileName!!).delete()
        assertEquals(Uploader.Outcome.Done, uploader().uploadAll())
        assertEquals("The phone's copy is missing, so it can't be uploaded.", store.all().single().uploadError)
    }

    @Test
    fun `slices cover the file exactly`() {
        val file = File(folder.root, "f").apply { writeText("abcdefghij") }
        fun read(n: Int) = Buffer().also { Uploader.slice(file, 4, n).writeTo(it) }.readUtf8()
        assertEquals(listOf("abcd", "efgh", "ij"), (1..3).map(::read))
    }
}

/** A tiny in-memory version of the upload API (services/backend/src/roudio/api/recordings.py). */
private class FakeServer(private val partBytes: Int) : RecordingsApi {
    private class Upload(val id: UUID, var request: RecordingUploadRequest, val parts: MutableMap<Int, ByteArray> = mutableMapOf())

    private val uploads = mutableMapOf<Long, Upload>()
    val stored = mutableMapOf<Long, String>()
    val partsSent = mutableListOf<Int>()
    var lastRequest: RecordingUploadRequest? = null
    var starts = 0
    var down = false
    var failPart: Int? = null
    var corruptNextComplete = false
    var refuseSession: Long? = null

    private fun total(size: Long) = ((size + partBytes - 1) / partBytes).toInt()

    private fun progress(u: Upload): RecordingUpload {
        var received = 0L
        var n = 1
        while (u.parts.containsKey(n)) received += u.parts.getValue(n++).size
        val done = received >= u.request.sizeBytes
        return RecordingUpload(u.id, RecordingUpload.State.UPLOADING, partBytes, total(u.request.sizeBytes), received, if (done) null else n)
    }

    private fun <T> error(code: Int, errorCode: String, message: String): Response<T> =
        Response.error(code, """{"detail":{"code":"$errorCode","message":"$message"}}""".toResponseBody("application/json".toMediaType()))

    override suspend fun startRecordingUpload(serial: String, sessionId: Long, recordingUploadRequest: RecordingUploadRequest): Response<RecordingUpload> {
        if (down) return error(503, "storage_unavailable", "down")
        if (sessionId == refuseSession) return error(409, "already_uploaded", "A different file is already on the server.")
        starts++
        lastRequest = recordingUploadRequest
        stored[sessionId]?.let {
            return Response.success(RecordingUpload(uploads.getValue(sessionId).id, RecordingUpload.State.UPLOADED, partBytes, 1, it.length.toLong(), null))
        }
        val upload = uploads.getOrPut(sessionId) { Upload(UUID.randomUUID(), recordingUploadRequest) }
        return Response.success(progress(upload))
    }

    override suspend fun uploadRecordingPart(recordingId: UUID, number: Int, body: RequestBody): Response<RecordingUpload> {
        if (number == failPart) throw java.io.IOException("connection reset")
        val upload = uploads.values.first { it.id == recordingId }
        upload.parts[number] = Buffer().also { body.writeTo(it) }.readByteArray()
        partsSent += number
        return Response.success(progress(upload))
    }

    override suspend fun completeRecordingUpload(recordingId: UUID): Response<RecordingUpload> {
        val (session, upload) = uploads.entries.first { it.value.id == recordingId }
        val bytes = (1..total(upload.request.sizeBytes)).flatMap { upload.parts.getValue(it).toList() }.toByteArray()
        if (corruptNextComplete) {
            corruptNextComplete = false
            upload.parts.clear()
            return error(409, "checksum_mismatch", "mismatch")
        }
        stored[session] = String(bytes)
        return Response.success(RecordingUpload(recordingId, RecordingUpload.State.UPLOADED, partBytes, total(upload.request.sizeBytes), bytes.size.toLong(), null))
    }
}
