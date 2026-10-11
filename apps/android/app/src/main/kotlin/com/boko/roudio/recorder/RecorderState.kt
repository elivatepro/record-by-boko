package com.boko.roudio.recorder

import java.time.Instant

/** A value from the recorder and when it was received. */
data class Seen<T>(val value: T, val at: Instant)

data class LinkedRecorder(val serial: String, val model: RecorderModel)

data class BatteryReading(val percent: Int, val charging: Boolean?)

data class StorageReading(val totalBytes: Long, val freeBytes: Long)

data class RecordingReading(val isRecording: Boolean, val startedAt: Instant?)

/** [latest] is set when Plaud offers newer firmware. Updating erases the recorder (spec §4). */
data class FirmwareReading(val current: String, val latest: String?)

/** Where linking stands. Only the setup flow and the error screens look at this. */
sealed interface LinkPhase {
    data object Idle : LinkPhase

    data class Searching(val found: List<FoundRecorder>) : LinkPhase

    /** The search ran for [SEARCH_TIME] with no recorder in it (12a). */
    data object NothingFound : LinkPhase

    /** [bluetoothUp]: the first step on 11h is done and the handshake is running. */
    data class Linking(val recorder: FoundRecorder, val bluetoothUp: Boolean = false) : LinkPhase

    /** [recorder] is the one we tried, so "Try again" on 12d/12e can link it again. */
    data class Failed(val reason: LinkFailure, val recorder: FoundRecorder? = null) : LinkPhase
}

/** What the UI says about "is it recording now". Brief: unknown unless there's a fresh event. */
sealed interface RecordingNow {
    data class Recording(val since: Instant?) : RecordingNow

    data object NotRecording : RecordingNow

    data object Unknown : RecordingNow
}

data class RecorderState(
    val bluetoothOn: Boolean = true,
    val linked: LinkedRecorder? = null,
    val link: LinkPhase = LinkPhase.Idle,
    /** Set while connected; the start of the current connection. */
    val connectedSince: Instant? = null,
    /** The last time anything was heard from the recorder. */
    val lastSeen: Instant? = null,
    val battery: Seen<BatteryReading>? = null,
    val storage: Seen<StorageReading>? = null,
    val recording: Seen<RecordingReading>? = null,
    val firmware: FirmwareReading? = null,
) {
    val isConnected: Boolean get() = connectedSince != null

    /**
     * Fresh means: connected, and the recorder has reported its recording state since this
     * connection began. Anything older could be wrong, so it's unknown.
     */
    val recordingNow: RecordingNow
        get() {
            val since = connectedSince ?: return RecordingNow.Unknown
            val reading = recording?.takeIf { !it.at.isBefore(since) } ?: return RecordingNow.Unknown
            return if (reading.value.isRecording) {
                RecordingNow.Recording(reading.value.startedAt)
            } else {
                RecordingNow.NotRecording
            }
        }

    fun searching(): RecorderState = copy(link = LinkPhase.Searching(emptyList()))

    /** The search time is up: nothing found becomes 12a; a found list stays on screen. */
    fun searchTimedOut(): RecorderState {
        val phase = link as? LinkPhase.Searching ?: return this
        return if (phase.found.isEmpty()) copy(link = LinkPhase.NothingFound) else this
    }

    fun linking(recorder: FoundRecorder): RecorderState = copy(link = LinkPhase.Linking(recorder))

    /** The owner tapped a recorder 11g shows as linked elsewhere: straight to 12d. */
    fun linkedElsewhere(recorder: FoundRecorder): RecorderState =
        copy(link = LinkPhase.Failed(LinkFailure.LinkedElsewhere, recorder))

    fun linkIdle(): RecorderState = copy(link = LinkPhase.Idle)

    /** After unlinking, only the phone's Bluetooth state is still worth keeping. */
    fun unlinked(): RecorderState = RecorderState(bluetoothOn = bluetoothOn)

    fun reduce(event: RecorderEvent, now: Instant): RecorderState = when (event) {
        is RecorderEvent.BluetoothChanged ->
            if (event.on) {
                copy(bluetoothOn = true)
            } else {
                // Nothing can be connected or searching without Bluetooth.
                disconnected(now).copy(
                    bluetoothOn = false,
                    link = when (val phase = link) {
                        is LinkPhase.Failed -> phase
                        is LinkPhase.Linking -> LinkPhase.Failed(LinkFailure.Dropped, phase.recorder)
                        else -> LinkPhase.Idle
                    },
                )
            }

        is RecorderEvent.Found -> {
            val phase = link
            if (phase is LinkPhase.Searching) {
                val ours = linked?.serial
                val found = event.recorders
                    .map { if (it.serial == ours) it.copy(linkedElsewhere = false) else it }
                    .sortedByDescending { it.signal }
                copy(link = LinkPhase.Searching(found))
            } else {
                this
            }
        }

        RecorderEvent.BluetoothLinked -> {
            val phase = link
            if (phase is LinkPhase.Linking) copy(link = phase.copy(bluetoothUp = true)) else this
        }

        is RecorderEvent.Connected -> {
            val phase = link
            val newlyLinked = (phase as? LinkPhase.Linking)?.recorder
                ?.takeIf { it.serial == event.serial }
                ?.let { LinkedRecorder(it.serial, it.model) }
            copy(
                linked = linked ?: newlyLinked
                    ?: LinkedRecorder(event.serial, RecorderModel.fromSerial(event.serial)),
                link = LinkPhase.Idle,
                connectedSince = connectedSince ?: now,
                lastSeen = now,
            )
        }

        is RecorderEvent.LinkFailed -> {
            val phase = link
            if (phase is LinkPhase.Linking) copy(link = LinkPhase.Failed(event.reason, phase.recorder)) else this
        }

        RecorderEvent.Disconnected -> {
            val dropped = disconnected(now)
            val phase = link
            if (phase is LinkPhase.Linking) {
                dropped.copy(link = LinkPhase.Failed(LinkFailure.Dropped, phase.recorder))
            } else {
                dropped
            }
        }

        is RecorderEvent.Battery -> copy(
            battery = Seen(
                BatteryReading(
                    percent = event.percent.coerceIn(0, 100),
                    // Some events carry only the level; keep the last charging state then.
                    charging = event.charging ?: battery?.value?.charging,
                ),
                now,
            ),
            lastSeen = now,
        )

        is RecorderEvent.Storage -> copy(
            storage = Seen(StorageReading(event.totalBytes, event.freeBytes), now),
            lastSeen = now,
        )

        is RecorderEvent.Firmware -> copy(firmware = FirmwareReading(event.current, event.latest))

        is RecorderEvent.Recording -> copy(
            recording = Seen(RecordingReading(event.isRecording, event.startedAt), now),
            lastSeen = now,
        )
    }

    private fun disconnected(now: Instant): RecorderState =
        if (isConnected) copy(connectedSince = null, lastSeen = now) else this

    companion object {
        /** How long "Finding your recorder" looks before showing 12a (flow-and-states.md). */
        val SEARCH_TIME: java.time.Duration = java.time.Duration.ofSeconds(30)
    }
}
