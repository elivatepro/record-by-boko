package com.boko.roudio.ui.backups

import com.boko.roudio.api.models.RecordingSummary

/** The Drive copy, as one pill on 14. */
enum class DriveCopy { Checked, Failed, Uploading, Checking, AfterWriteUp, Waiting }

/** Whether the recording has left the recorder, as the word on the right of each 14 row. */
enum class RecorderCopy {
    /** Deleted from the recorder after all three copies matched. */
    Cleared,

    /** The phone is deleting it now. */
    Clearing,

    /** All three copies match and deleting is on: it goes on the next connection. */
    ReadyToClear,

    /** All three copies match, but deleting from the recorder is switched off. */
    KeptDeletingOff,

    /** The Drive backup failed, so it stays until a retry works. */
    StaysOnRecorder,

    /** Still uploading, being written up or being backed up. */
    Waiting,
}

/** Backups' logic, apart from the UI: plain words for the server's states (spec §8). */
object BackupsModel {
    fun drive(recording: RecordingSummary): DriveCopy = when (recording.backupState) {
        "backed_up" -> DriveCopy.Checked
        "failed" -> DriveCopy.Failed
        "uploading" -> DriveCopy.Uploading
        "checking" -> DriveCopy.Checking
        else -> if (recording.processingState in FINISHED) DriveCopy.Waiting else DriveCopy.AfterWriteUp
    }

    /** The server has its copy once the upload finished and its checksum matched. */
    fun onServer(recording: RecordingSummary): Boolean = recording.transferState == "uploaded"

    fun recorder(recording: RecordingSummary, deletingEnabled: Boolean): RecorderCopy = when {
        recording.cleanupState == "cleared" -> RecorderCopy.Cleared
        recording.cleanupState == "deleting" -> RecorderCopy.Clearing
        recording.backupState == "failed" -> RecorderCopy.StaysOnRecorder
        recording.cleanupState == "can_clean_up" -> if (deletingEnabled) RecorderCopy.ReadyToClear else RecorderCopy.KeptDeletingOff
        else -> RecorderCopy.Waiting
    }

    /** The "Needs you" cards: recordings whose backup failed, newest first (the server's order). */
    fun failed(recordings: List<RecordingSummary>): List<RecordingSummary> = recordings.filter { it.backupState == "failed" }

    private val FINISHED = setOf("done", "failed")
}
