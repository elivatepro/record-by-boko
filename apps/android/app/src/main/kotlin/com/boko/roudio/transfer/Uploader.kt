package com.boko.roudio.transfer

import android.util.Log
import com.boko.roudio.api.apis.RecordingsApi
import com.boko.roudio.api.models.RecordingUpload
import com.boko.roudio.api.models.RecordingUploadRequest
import com.boko.roudio.data.errorDetail
import com.boko.roudio.store.Recording
import com.boko.roudio.store.RecordingStore
import com.boko.roudio.store.TransferState
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Response

/**
 * Sends recordings that are "on phone" to the server (spec §3): starts or resumes the upload,
 * sends the parts the server doesn't have yet, then asks it to check the whole file's SHA-256.
 * Only a match makes a recording "uploaded". The phone copy is never touched.
 */
class Uploader(
    private val api: RecordingsApi,
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val now: () -> Instant = Instant::now,
) {
    enum class Outcome { Done, Retry }

    /** Uploads everything waiting, oldest first. [Outcome.Retry] means try again later (no network…). */
    suspend fun uploadAll(): Outcome {
        val skipped = mutableSetOf<Long>()
        while (true) {
            val next = (store.inTransfer(TransferState.Uploading) + store.inTransfer(TransferState.OnPhone))
                .filter { it.id !in skipped }
                .minByOrNull { it.startedAt }
                ?: return Outcome.Done
            when (upload(next)) {
                Step.Uploaded -> Unit
                Step.Skip -> skipped += next.id
                Step.Retry -> return Outcome.Retry
            }
        }
    }

    private enum class Step { Uploaded, Skip, Retry }

    private suspend fun upload(recording: Recording): Step {
        val file = recording.fileName?.let { File(files.dir, it) }
        if (file == null || !file.exists() || recording.sha256 == null || recording.fileBytes == null) {
            return give(recording, Step.Skip, "The phone's copy is missing, so it can't be uploaded.")
        }
        var current = recording.copy(transfer = TransferState.Uploading, updatedAt = now())
        store.update(current)
        return try {
            // A checksum mismatch makes the server start over; one fresh try, then leave it be.
            repeat(2) {
                val started = call { start(current, file) } ?: return give(current, Step.Retry, NO_SERVER)
                current = current.copy(serverId = started.id.toString(), uploadedBytes = started.receivedBytes)
                store.update(current)
                if (started.state == RecordingUpload.State.UPLOADED) return uploaded(current)

                var part = started.nextPart
                while (part != null) {
                    val number: Int = part
                    val body = slice(file, started.partBytes.toLong(), number)
                    val sent = call { api.uploadRecordingPart(started.id, number, body) }
                        ?: return give(current, Step.Retry, NO_SERVER)
                    current = current.copy(uploadedBytes = sent.receivedBytes, updatedAt = now())
                    store.update(current)
                    part = sent.nextPart
                }

                val completed = api.completeRecordingUpload(started.id)
                if (completed.isSuccessful) return uploaded(current)
                val detail = completed.errorDetail()
                when {
                    detail?.code == "checksum_mismatch" -> Log.w(TAG, "server copy of ${recording.sessionId} didn't match; again")
                    detail?.code == "parts_missing" -> Unit // the next round asks what's there
                    completed.code() >= 500 || completed.code() == 401 -> return give(current, Step.Retry, NO_SERVER)
                    else -> return give(current, Step.Skip, detail?.message ?: "The server refused the upload.")
                }
            }
            give(current, Step.Skip, "The server's copy didn't match this phone's twice. It will be tried again later.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            give(current, Step.Retry, NO_SERVER)
        } catch (e: RefusedException) {
            give(current, Step.Skip, e.message)
        }
    }

    private suspend fun start(recording: Recording, file: File): Response<RecordingUpload> =
        api.startRecordingUpload(
            recording.serial,
            recording.sessionId,
            RecordingUploadRequest(
                startedAt = recording.startedAt.atOffset(ZoneOffset.UTC),
                zoneOffsetSeconds = recording.zoneOffsetSeconds,
                sizeBytes = file.length(),
                sha256 = recording.sha256!!,
                durationMs = recording.durationMs,
            ),
        )

    /**
     * The body if the call worked; null if it's worth retrying later (server trouble, signed
     * out). Throws [RefusedException] when the server says no for good.
     */
    private suspend fun <T> call(request: suspend () -> Response<T>): T? {
        val response = request()
        response.body()?.takeIf { response.isSuccessful }?.let { return it }
        if (response.code() >= 500 || response.code() == 401 || response.code() == 429) return null
        throw RefusedException(response.errorDetail()?.message ?: "The server refused the upload (${response.code()}).")
    }

    private class RefusedException(override val message: String) : Exception(message)

    private suspend fun uploaded(recording: Recording): Step {
        store.update(
            recording.copy(
                transfer = TransferState.Uploaded,
                uploadedBytes = recording.fileBytes ?: recording.uploadedBytes,
                uploadError = null,
                updatedAt = now(),
            ),
        )
        Log.i(TAG, "uploaded session ${recording.sessionId}")
        return Step.Uploaded
    }

    private suspend fun give(recording: Recording, step: Step, error: String): Step {
        val latest = store.byId(recording.id) ?: recording
        store.update(
            // It stays "uploading"; the next run carries on. Nothing on the phone changes.
            latest.copy(
                uploadAttempts = latest.uploadAttempts + 1,
                uploadError = error,
                updatedAt = now(),
            ),
        )
        return step
    }

    companion object {
        private const val TAG = "Uploader"
        private const val NO_SERVER = "Couldn't reach Roudio's server. The phone keeps its copy and tries again."
        private val OPUS = "application/octet-stream".toMediaType()

        /** Part [number] (1-based) of [file], streamed from disk rather than read into memory. */
        fun slice(file: File, partBytes: Long, number: Int): RequestBody {
            val offset = partBytes * (number - 1)
            val length = minOf(partBytes, file.length() - offset)
            return object : RequestBody() {
                override fun contentType() = OPUS

                override fun contentLength() = length

                override fun writeTo(sink: BufferedSink) {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset)
                        val buffer = ByteArray(64 * 1024)
                        var left = length
                        while (left > 0) {
                            val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) throw IOException("the file got shorter while uploading")
                            sink.write(buffer, 0, read)
                            left -= read
                        }
                    }
                }
            }
        }
    }
}
