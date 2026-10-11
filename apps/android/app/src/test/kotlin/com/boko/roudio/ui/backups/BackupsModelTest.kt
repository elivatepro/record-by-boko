package com.boko.roudio.ui.backups

import com.boko.roudio.api.models.RecordingSummary
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupsModelTest {
    private fun recording(
        backup: String = "waiting",
        cleanup: String = "blocked",
        processing: String = "done",
        transfer: String = "uploaded",
        n: Long = 1,
    ) = RecordingSummary(
        id = UUID(0, n),
        recorderSerial = "8810000004F2A",
        sessionId = n,
        startedAt = OffsetDateTime.of(2026, 10, 11, 9, 30, 0, 0, ZoneOffset.ofHours(1)),
        zoneOffsetSeconds = 3600,
        durationMs = 60_000,
        transferState = transfer,
        processingState = processing,
        processingError = null,
        backupState = backup,
        backupError = null,
        cleanupState = cleanup,
        resultsVersion = 1,
    )

    @Test
    fun `drive copy in plain words`() {
        assertEquals(DriveCopy.Checked, BackupsModel.drive(recording(backup = "backed_up")))
        assertEquals(DriveCopy.Failed, BackupsModel.drive(recording(backup = "failed")))
        assertEquals(DriveCopy.Uploading, BackupsModel.drive(recording(backup = "uploading")))
        assertEquals(DriveCopy.Checking, BackupsModel.drive(recording(backup = "checking")))
        assertEquals(DriveCopy.AfterWriteUp, BackupsModel.drive(recording(processing = "transcribing")))
        assertEquals(DriveCopy.Waiting, BackupsModel.drive(recording(processing = "done")))
        assertEquals(DriveCopy.Waiting, BackupsModel.drive(recording(processing = "failed")))
    }

    @Test
    fun `server copy counts once uploaded`() {
        assertTrue(BackupsModel.onServer(recording()))
        assertFalse(BackupsModel.onServer(recording(transfer = "uploading")))
    }

    @Test
    fun `the recorder copy follows the gate and the switch`() {
        assertEquals(RecorderCopy.Cleared, BackupsModel.recorder(recording(cleanup = "cleared"), deletingEnabled = false))
        assertEquals(RecorderCopy.Clearing, BackupsModel.recorder(recording(cleanup = "deleting"), deletingEnabled = true))
        assertEquals(RecorderCopy.StaysOnRecorder, BackupsModel.recorder(recording(backup = "failed"), deletingEnabled = true))
        assertEquals(RecorderCopy.ReadyToClear, BackupsModel.recorder(recording(backup = "backed_up", cleanup = "can_clean_up"), deletingEnabled = true))
        assertEquals(RecorderCopy.KeptDeletingOff, BackupsModel.recorder(recording(backup = "backed_up", cleanup = "can_clean_up"), deletingEnabled = false))
        assertEquals(RecorderCopy.Waiting, BackupsModel.recorder(recording(), deletingEnabled = true))
    }

    @Test
    fun `failed lists only failed backups, in order`() {
        val list = listOf(recording(backup = "failed", n = 1), recording(backup = "backed_up", n = 2), recording(backup = "failed", n = 3))
        assertEquals(listOf(1L, 3L), BackupsModel.failed(list).map { it.sessionId })
    }
}
