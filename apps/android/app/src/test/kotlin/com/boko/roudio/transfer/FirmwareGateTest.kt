package com.boko.roudio.transfer

import com.boko.roudio.recorder.FirmwareReading
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingReading
import com.boko.roudio.recorder.Seen
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import com.boko.roudio.transfer.FirmwareGate.Gate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class FirmwareGateTest {
    private val now = Instant.parse("2026-10-11T10:00:00Z")
    private val serial = "8810000004F2A"
    private val idle = RecorderState(
        linked = LinkedRecorder(serial, RecorderModel.NotePro),
        connectedSince = now,
        recording = Seen(RecordingReading(false, null), now),
        firmware = FirmwareReading("1.7.0", "1.8.0"),
    )

    private fun recording(transfer: TransferState) = Recording(
        serial = serial, sessionId = 1, startedAt = now, zoneOffsetSeconds = 0, sizeOnRecorder = 1,
        transfer = transfer, firstSeenAt = now, updatedAt = now,
    )

    @Test
    fun `no newer firmware, nothing to offer`() {
        assertEquals(Gate.NoUpdate, FirmwareGate.gate(idle.copy(firmware = FirmwareReading("1.8.0", null)), emptyList()))
    }

    @Test
    fun `waits while anything isn't uploaded yet`() {
        TransferState.entries.filter { it != TransferState.Uploaded }.forEach { transfer ->
            assertEquals(transfer.name, Gate.Waiting, FirmwareGate.gate(idle, listOf(recording(transfer))))
        }
    }

    @Test
    fun `waits while recording or not connected`() {
        val uploaded = listOf(recording(TransferState.Uploaded))
        assertEquals(Gate.Waiting, FirmwareGate.gate(idle.copy(recording = Seen(RecordingReading(true, now), now)), uploaded))
        assertEquals(Gate.Waiting, FirmwareGate.gate(idle.copy(connectedSince = null), uploaded))
    }

    @Test
    fun `ready once everything is uploaded and the recorder is idle`() {
        assertEquals(Gate.Ready, FirmwareGate.gate(idle, listOf(recording(TransferState.Uploaded))))
    }
}
