package com.boko.roudio.transfer

import android.util.Log
import com.boko.roudio.api.apis.BackupsApi
import com.boko.roudio.api.models.CleanupReport
import com.boko.roudio.recorder.RecorderFile
import com.boko.roudio.recorder.RecorderSource
import com.boko.roudio.store.CleanupState
import com.boko.roudio.store.RecordingStore
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Clears the recorder of recordings that are safe everywhere (spec §8). A recording is deleted
 * from the recorder only when all of these hold:
 * 1. the server lists it as clean-up-ready (its copy matched the phone's and the Drive copy is
 *    verified), which it only does with deleting switched on in Settings;
 * 2. this phone still has its own copy with the same SHA-256.
 * The phone's copy is never deleted. A failed delete leaves the recording on the recorder and is
 * reported, to be tried again; nothing else is undone.
 */
class CleanupGate(
    private val api: BackupsApi,
    private val source: RecorderSource,
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val now: () -> Instant = Instant::now,
) {
    /** Returns how many recordings were cleared from the recorder. */
    suspend fun run(serial: String): Int {
        val list = try {
            api.getCleanup(serial).body() ?: return 0
        } catch (e: IOException) {
            return 0
        }
        if (!list.deletingEnabled) return 0
        var cleared = 0
        for (candidate in list.candidates) {
            val local = store.bySession(serial, candidate.sessionId) ?: continue
            val copy = local.fileName?.let { File(files.dir, it) }
            // The phone's own copy must still be here and still match (condition 1 of spec §8).
            if (copy == null || !copy.exists() || Transfers.sha256(copy) != candidate.sha256 || local.sha256 != candidate.sha256) {
                Log.w(TAG, "session ${candidate.sessionId}: phone copy doesn't match; not deleting")
                continue
            }
            try {
                report(candidate.recordingId, CleanupReport.State.DELETING)
                store.update(local.copy(cleanup = CleanupState.Deleting, updatedAt = now()))
                source.deleteFromRecorder(RecorderFile(candidate.sessionId, candidate.sizeBytes))
                report(candidate.recordingId, CleanupReport.State.CLEARED)
                store.update(local.copy(cleanup = CleanupState.Cleared, updatedAt = now()))
                cleared++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "couldn't clear session ${candidate.sessionId}", e)
                runCatching { report(candidate.recordingId, CleanupReport.State.FAILED, e.message) }
                store.update(local.copy(cleanup = CleanupState.CanCleanUp, updatedAt = now()))
                return cleared // the recorder is unhappy; try the rest next time
            }
        }
        return cleared
    }

    private suspend fun report(id: java.util.UUID, state: CleanupReport.State, error: String? = null) {
        api.reportCleanup(id, CleanupReport(state, error?.take(500)))
    }

    private companion object {
        const val TAG = "CleanupGate"
    }
}
