package com.boko.roudio.recorder

import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Records the commands it gets; tests push events with [emit]. */
class FakeRecorderSource(var bluetoothOn: Boolean = true) : RecorderSource {
    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RecorderEvent> = _events

    val commands = mutableListOf<String>()

    suspend fun emit(event: RecorderEvent) = _events.emit(event)

    override fun isBluetoothOn() = bluetoothOn

    override fun startSearch() { commands += "startSearch" }

    override fun stopSearch() { commands += "stopSearch" }

    override fun link(serial: String, alreadyOurs: Boolean) {
        commands += if (alreadyOurs) "relink $serial" else "link $serial"
    }

    override fun connect(serial: String) { commands += "connect $serial" }

    override fun disconnect() { commands += "disconnect" }

    override suspend fun unlink(serial: String) { commands += "unlink $serial" }

    override fun refresh() { commands += "refresh" }

    /** What [listFiles] returns. */
    var files = listOf<RecorderFile>()

    /** Called by [export]; write the file, or throw to fail the transfer. */
    var exporter: suspend (RecorderFile, File, (Int) -> Unit) -> Unit = { _, target, onProgress ->
        target.writeText("opus")
        onProgress(100)
    }

    override suspend fun listFiles(): List<RecorderFile> {
        commands += "listFiles"
        return files
    }

    /** Throw from here to make a delete fail. */
    var deleter: suspend (RecorderFile) -> Unit = {}

    override suspend fun deleteFromRecorder(file: RecorderFile) {
        commands += "delete ${file.sessionId}"
        deleter(file)
    }

    override suspend fun export(file: RecorderFile, target: File, onProgress: (Int) -> Unit) {
        commands += "export ${file.sessionId}"
        exporter(file, target, onProgress)
    }
}

class MemoryRecorderStore(var saved: RecorderState = RecorderState()) : RecorderStore {
    override fun load() = saved

    override fun save(state: RecorderState) {
        saved = state
    }
}
