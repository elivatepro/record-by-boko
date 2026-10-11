package com.boko.roudio.recorder

import java.io.File
import java.io.IOException
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

    /**
     * Link a recorder from the latest [RecorderEvent.Found]. [alreadyOurs]: it's the recorder
     * Roudio already has linked, so the ordinary handshake is enough (no force-clear).
     */
    fun link(serial: String, alreadyOurs: Boolean = false)

    /** Reconnect to the recorder that's already linked. It's found by its serial. */
    fun connect(serial: String)

    fun disconnect()

    /** Forget the recorder on both the recorder and Plaud's side. Best effort. */
    suspend fun unlink(serial: String)

    /** Ask a connected recorder for battery, storage and recording state again. */
    fun refresh()

    /** The recordings on the connected recorder. Throws [RecorderBusyException] if it can't say. */
    suspend fun listFiles(): List<RecorderFile>

    /**
     * Copy one recording to [target] as Ogg Opus (decision 12), reporting 0–100 as it comes off the
     * recorder. Never deletes anything from the recorder. Throws [ExportException] when the
     * transfer stops; cancelling the coroutine stops it too. A partial [target] is removed.
     */
    suspend fun export(file: RecorderFile, target: File, onProgress: (Int) -> Unit)
}

/** A recording on the recorder. Plaud's session id is its start time in Unix seconds. */
data class RecorderFile(val sessionId: Long, val sizeBytes: Long) {
    val startedAt: Instant? get() = sessionStart(sessionId)

    companion object {
        /** Session ids outside 2020–2100 aren't times; treat the start as unknown. */
        fun sessionStart(sessionId: Long): Instant? =
            sessionId.takeIf { it in 1_577_836_800L..4_102_444_800L }?.let(Instant::ofEpochSecond)
    }
}

class RecorderBusyException(message: String) : IOException(message)

class ExportException(message: String) : IOException(message)

sealed interface RecorderEvent {
    data class BluetoothChanged(val on: Boolean) : RecorderEvent

    data class Found(val recorders: List<FoundRecorder>) : RecorderEvent

    /** While linking: the Bluetooth connection is up and the handshake is starting (11h). */
    data object BluetoothLinked : RecorderEvent

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
    /**
     * The recorder says it's bound to an app. Bound to Roudio looks the same, so [RecorderState]
     * clears this for the recorder Roudio has linked; for any other it means 11g greys it out.
     */
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

/** [shortName] fills "Linking your Note Pro" (11h, 11i, 12d). */
enum class RecorderModel(val displayName: String, val shortName: String) {
    NotePro("Plaud Note Pro", "Note Pro"),
    NotePinS("Plaud NotePin S", "NotePin S"),
    Other("Plaud recorder", "recorder"),
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
