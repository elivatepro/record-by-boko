package com.boko.roudio.transfer

import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingNow
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState

/**
 * A firmware update erases the recorder (spec §4), so it's only offered when nothing on it would be
 * lost: connected, not recording, and every recording seen on it has reached "uploaded".
 */
object FirmwareGate {
    enum class Gate { NoUpdate, Waiting, Ready }

    fun gate(state: RecorderState, recordings: List<Recording>): Gate {
        if (state.firmware?.latest == null) return Gate.NoUpdate
        val serial = state.linked?.serial ?: return Gate.Waiting
        val allUploaded = recordings.filter { it.serial == serial }.all { it.transfer == TransferState.Uploaded }
        val idle = state.isConnected && state.recordingNow == RecordingNow.NotRecording
        return if (allUploaded && idle) Gate.Ready else Gate.Waiting
    }
}
