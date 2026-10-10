package com.boko.roudio.recorder

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * The only way the app talks to the recorder (spec §2). [PlaudRecorderSource] is the real one;
 * tests use a fake. Commands return straight away and their results arrive as [events].
 */
interface RecorderSource {
    /** Hot stream of everything the recorder and the phone's Bluetooth report. */
    val events: Flow<RecorderEvent>

    fun isBluetoothOn(): Boolean

    /** Look for nearby recorders. Results arrive as [RecorderEvent.Found]. */
    fun startSearch()

    fun stopSearch()

    /** Link a recorder from the latest [RecorderEvent.Found] for the first time. */
    fun link(serial: String)

    /** Reconnect to the recorder that's already linked. It's found by its serial. */
    fun connect(serial: String)

    fun disconnect()

    /** Forget the recorder on both the recorder and Plaud's side. Best effort. */
    suspend fun unlink(serial: String)

    /** Ask a connected recorder for battery, storage and recording state again. */
    fun refresh()
}

sealed interface RecorderEvent {
    data class BluetoothChanged(val on: Boolean) : RecorderEvent

    data class Found(val recorders: List<FoundRecorder>) : RecorderEvent

    /** The handshake finished: the recorder is linked to Roudio and connected. */
    data class Connected(val serial: String) : RecorderEvent

    data class LinkFailed(val serial: String?, val reason: LinkFailure) : RecorderEvent

    data object Disconnected : RecorderEvent

    data class Battery(val percent: Int, val charging: Boolean? = null) : RecorderEvent

    data class Storage(val totalBytes: Long, val freeBytes: Long) : RecorderEvent

    /** [startedAt] is when the current recording began, when the recorder says. */
    data class Recording(val isRecording: Boolean, val startedAt: Instant? = null) : RecorderEvent
}

data class FoundRecorder(
    val serial: String,
    val model: RecorderModel,
    /** Received signal strength in dBm; higher is nearer. */
    val signal: Int,
    /** Already linked to another app, so Roudio can't link it (screen 12d). */
    val linkedElsewhere: Boolean = false,
)

enum class LinkFailure {
    /** The recorder refused the handshake, or Plaud says another account owns it (12d). */
    LinkedElsewhere,

    /** The Bluetooth connection dropped or timed out while linking (12e). */
    Dropped,

    /** Plaud's servers couldn't be reached; linking needs them (12e). */
    NoInternet,

    /** Anything else (12e). */
    Other,
}

enum class RecorderModel(val displayName: String) {
    NotePro("Plaud Note Pro"),
    NotePinS("Plaud NotePin S"),
    Other("Plaud recorder"),
    ;

    companion object {
        /** Plaud serials start with a model code. */
        fun fromSerial(serial: String): RecorderModel = when {
            serial.startsWith("881") -> NotePro
            serial.startsWith("882") -> NotePinS
            else -> Other
        }
    }
}

/** The last four characters, as printed on the back of the recorder (11g, 13a). */
val String.serialEnding: String get() = takeLast(4).uppercase()
