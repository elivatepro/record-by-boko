package com.boko.roudio.transfer

import android.util.Log
import com.boko.roudio.recorder.OggOpus
import com.boko.roudio.recorder.Recorder
import com.boko.roudio.recorder.RecorderFile
import com.boko.roudio.recorder.RecorderSource
import com.boko.roudio.recorder.RecordingNow
import com.boko.roudio.store.Recording
import com.boko.roudio.store.RecordingStore
import com.boko.roudio.store.TransferState
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings finished recordings across from the recorder (spec §4): lists the recorder's files,
 * saves new ones as "on recorder", then exports them oldest first. Each export lands in a
 * partial file; only a complete one gets its SHA-256 and becomes "on phone". A failed or
 * interrupted export throws the partial file away and starts from the beginning next time
 * (decision 14). Nothing on the recorder is ever deleted: [RecorderSource] can't.
 */
class Transfers(
    private val source: RecorderSource,
    private val recorder: Recorder,
    private val store: RecordingStore,
    private val files: RecordingFiles,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val checkEvery: Duration = CHECK_EVERY,
    /** Called each time a recording reaches the phone, to start the upload queue. */
    private val onPhone: () -> Unit = {},
    /** Runs after each check while connected and idle: the clean-up gate. */
    private val afterSync: suspend (serial: String) -> Unit = {},
) {
    private val wanted = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    private val _progress = MutableStateFlow<Progress?>(null)

    /** The export running now and how far it is (0–100); null when none is. Not saved. */
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _stopped = MutableStateFlow<Progress?>(null)

    /** The last export that stopped partway (13b "Stopped at 48%"), until the next one starts. */
    val stopped: StateFlow<Progress?> = _stopped.asStateFlow()

    /** [startedAt] lets the recorder screen say roughly how long is left. */
    data class Progress(val recordingId: Long, val percent: Int, val startedAt: Instant, val viaWifi: Boolean = false)

    /** Fast transfer over the recorder's Wi-Fi, asked for on the recorder screen. */
    sealed interface Wifi {
        data object Off : Wifi

        /** Waiting for the recorder's Wi-Fi; Android asks the owner to allow joining it. */
        data object Joining : Wifi

        data object On : Wifi

        /** It didn't come up; the transfers carry on over Bluetooth. */
        data class Failed(val reason: String) : Wifi
    }

    private val _wifi = MutableStateFlow<Wifi>(Wifi.Off)
    val wifi: StateFlow<Wifi> = _wifi.asStateFlow()

    @Volatile private var fastWanted = false

    /** Bring what's on the recorder across over Wi-Fi this once, then back to Bluetooth. */
    fun fastTransfer() {
        fastWanted = true
        wanted.trySend(Unit)
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            recoverInterrupted()
            // Look again on every new connection, whenever the recorder starts or stops a
            // recording (a file has just closed), and now and then while connected.
            launch {
                recorder.state
                    .map { Trigger(it.connectedSince, it.recordingNow) }
                    .distinctUntilChanged()
                    .collect { if (it.connectedSince != null) wanted.trySend(Unit) }
            }
            launch {
                while (true) {
                    delay(checkEvery.toMillis())
                    wanted.trySend(Unit)
                }
            }
            for (request in wanted) {
                runCatching { sync() }.onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "transfer check failed", it)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Ask for a check now (debug, "Try again"). */
    fun check() {
        wanted.trySend(Unit)
    }

    private data class Trigger(val connectedSince: Instant?, val recordingNow: RecordingNow)

    /** A transfer that was running when the app stopped: back to the start, partial file gone. */
    private suspend fun recoverInterrupted() {
        withContext(io) { files.deletePartials() }
        for (recording in store.inTransfer(TransferState.Transferring)) {
            store.update(recording.copy(transfer = TransferState.OnRecorder, updatedAt = now()))
        }
    }

    internal suspend fun sync() {
        val state = recorder.state.value
        val serial = state.linked?.serial ?: return
        if (!state.isConnected) return
        // Not until the recorder has said whether it's recording: the open file mustn't be picked.
        if (state.recordingNow == RecordingNow.Unknown) return
        val onRecorder = source.listFiles()
        val finished = when (val recording = recorder.state.value.recordingNow) {
            is RecordingNow.Recording -> {
                val open = recording.since?.epochSecond ?: onRecorder.maxOfOrNull { it.sessionId }
                onRecorder.filter { it.sessionId != open }
            }
            RecordingNow.NotRecording -> onRecorder
            RecordingNow.Unknown -> return
        }
        store.addNew(finished.map { newRecording(serial, it) })

        val bySession = finished.associateBy { it.sessionId }
        val pending = store.inTransfer(TransferState.OnRecorder).mapNotNull { recording ->
            bySession[recording.sessionId]?.takeIf { recording.serial == serial }?.let { recording to it }
        }
        val viaWifi = fastWanted && pending.isNotEmpty() && openWifi()
        fastWanted = false
        try {
            for ((recording, file) in pending) {
                if (!recorder.state.value.isConnected) return
                if (!export(recording, file, viaWifi)) return
            }
        } finally {
            if (viaWifi) {
                withContext(NonCancellable) { runCatching { source.closeWifi() } }
                _wifi.value = Wifi.Off
            }
        }
        if (recorder.state.value.isConnected) afterSync(serial)
    }

    /** True once the recorder's Wi-Fi is up; on failure the exports carry on over Bluetooth. */
    private suspend fun openWifi(): Boolean {
        _wifi.value = Wifi.Joining
        return try {
            source.openWifi()
            _wifi.value = Wifi.On
            true
        } catch (e: CancellationException) {
            _wifi.value = Wifi.Off
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fast transfer didn't start", e)
            _wifi.value = Wifi.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun newRecording(serial: String, file: RecorderFile): Recording {
        val seen = now()
        val start = file.startedAt ?: seen
        return Recording(
            serial = serial,
            sessionId = file.sessionId,
            startedAt = start,
            zoneOffsetSeconds = zone().rules.getOffset(start).totalSeconds,
            sizeOnRecorder = file.sizeBytes,
            firstSeenAt = seen,
            updatedAt = seen,
        )
    }

    /** Returns false when the export failed, so the rest wait for the next check. */
    private suspend fun export(recording: Recording, file: RecorderFile, viaWifi: Boolean = false): Boolean {
        val current = recording.copy(transfer = TransferState.Transferring, updatedAt = now())
        store.update(current)
        val started = now()
        _progress.value = Progress(recording.id, 0, started, viaWifi)
        _stopped.value = null
        val partial = files.partial(recording)
        val target = files.final(recording)
        return try {
            source.export(file, partial, viaWifi) { percent -> _progress.value = Progress(recording.id, percent, started, viaWifi) }
            val (sha, bytes, duration) = withContext(io) {
                val facts = Triple(sha256(partial), partial.length(), OggOpus.durationMs(partial))
                check(partial.renameTo(target)) { "couldn't move the file into place" }
                facts
            }
            store.update(
                current.copy(
                    transfer = TransferState.OnPhone,
                    transferError = null,
                    fileName = target.name,
                    fileBytes = bytes,
                    sha256 = sha,
                    durationMs = duration,
                    updatedAt = now(),
                ),
            )
            Log.i(TAG, "on phone: session ${recording.sessionId}, $bytes bytes")
            onPhone()
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { giveBack(recording, partial, error = null, failed = false) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "export of session ${recording.sessionId} failed", e)
            _stopped.value = _progress.value
            giveBack(recording, partial, error = e.message ?: e.javaClass.simpleName, failed = true)
            false
        } finally {
            _progress.value = null
        }
    }

    private suspend fun giveBack(recording: Recording, partial: File, error: String?, failed: Boolean) {
        withContext(io) { partial.delete() }
        val latest = store.byId(recording.id) ?: recording
        store.update(
            latest.copy(
                transfer = TransferState.OnRecorder,
                transferAttempts = latest.transferAttempts + if (failed) 1 else 0,
                transferError = error ?: latest.transferError,
                updatedAt = now(),
            ),
        )
    }

    companion object {
        private const val TAG = "Transfers"

        /** New files also show up through recording events; this catches anything missed. */
        val CHECK_EVERY: Duration = Duration.ofMinutes(10)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Where recordings live in app storage. Private to Roudio and never backed up by Android. */
class RecordingFiles(val dir: File) {
    fun final(recording: Recording): File = File(dir.apply { mkdirs() }, "${recording.serial}-${recording.sessionId}.opus")

    fun partial(recording: Recording): File = File(dir.apply { mkdirs() }, "${recording.serial}-${recording.sessionId}.opus.part")

    fun deletePartials() {
        dir.listFiles { file -> file.name.endsWith(".part") }?.forEach { it.delete() }
    }
}
