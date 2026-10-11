package com.boko.roudio.recorder

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepConnectedTest {
    private val serial = "8810000004F2A"
    private val source = FakeRecorderSource()
    private val store = MemoryRecorderStore(RecorderState(linked = LinkedRecorder(serial, RecorderModel.NotePro)))

    private fun TestScope.start(): Recorder {
        val recorder = Recorder(source, store, backgroundScope) { Instant.EPOCH }
        KeepConnected(recorder, backgroundScope).start()
        runCurrent()
        return recorder
    }

    private fun count(command: String) = source.commands.count { it == command }

    @Test
    fun `reconnects at once, then with growing gaps`() = runTest {
        start()
        assertEquals(1, count("connect $serial"))
        advanceTimeBy(30_001)
        assertEquals(2, count("connect $serial"))
        advanceTimeBy(60_001)
        assertEquals(3, count("connect $serial"))
    }

    @Test
    fun `stops reconnecting once connected and refreshes now and then`() = runTest {
        start()
        source.emit(RecorderEvent.Connected(serial))
        runCurrent()
        val connects = count("connect $serial")
        val refreshes = count("refresh")

        advanceTimeBy(KeepConnected.REFRESH_EVERY.toMillis() + 1)
        assertEquals(connects, count("connect $serial"))
        assertEquals(refreshes + 1, count("refresh"))
    }

    @Test
    fun `waits while Bluetooth is off and reconnects as soon as it's back`() = runTest {
        source.bluetoothOn = false
        start()
        advanceTimeBy(600_000)
        assertEquals(0, count("connect $serial"))

        source.bluetoothOn = true
        source.emit(RecorderEvent.BluetoothChanged(true))
        runCurrent()
        assertEquals(1, count("connect $serial"))
    }

    @Test
    fun `does nothing without a linked recorder`() = runTest {
        store.saved = RecorderState()
        start()
        advanceTimeBy(600_000)
        assertEquals(emptyList<String>(), source.commands)
    }

    @Test
    fun `leaves the recorder alone while the setup flow is searching`() = runTest {
        val recorder = start()
        recorder.search()
        val connects = count("connect $serial")
        advanceTimeBy(20_000)
        assertEquals(connects, count("connect $serial"))
    }
}
