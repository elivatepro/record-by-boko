package com.boko.roudio.recorder

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

    override fun link(serial: String) { commands += "link $serial" }

    override fun connect(serial: String) { commands += "connect $serial" }

    override fun disconnect() { commands += "disconnect" }

    override suspend fun unlink(serial: String) { commands += "unlink $serial" }

    override fun refresh() { commands += "refresh" }
}

class MemoryRecorderStore(var saved: RecorderState = RecorderState()) : RecorderStore {
    override fun load() = saved

    override fun save(state: RecorderState) {
        saved = state
    }
}
