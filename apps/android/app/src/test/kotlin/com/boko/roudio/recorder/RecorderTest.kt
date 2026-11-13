package com.boko.roudio.recorder

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTest {
    private val now = Instant.parse("2026-10-10T13:00:00Z")
    private val notePro = FoundRecorder("8810000004F2A", RecorderModel.NotePro, signal = -60)
    private val source = FakeRecorderSource()
    private val store = MemoryRecorderStore()

    private fun TestScope.recorder() = Recorder(source, store, backgroundScope) { now }.also { runCurrent() }

    @Test
    fun `nothing found for 30 seconds shows not found and stops searching`() = runTest {
        val recorder = recorder()
        recorder.search()
        advanceTimeBy(29_000)
        assertTrue(recorder.state.value.link is LinkPhase.Searching)

        advanceTimeBy(2_000)
        assertEquals(LinkPhase.NothingFound, recorder.state.value.link)
        assertEquals(listOf("startSearch", "stopSearch"), source.commands)
    }

    @Test
    fun `linking saves the recorder and asks for its readings`() = runTest {
        val recorder = recorder()
        recorder.link(notePro)
        source.emit(RecorderEvent.Connected(notePro.serial))
        runCurrent()

        assertEquals(LinkedRecorder(notePro.serial, RecorderModel.NotePro), store.saved.linked)
        assertEquals(listOf("stopSearch", "link ${notePro.serial}", "refresh"), source.commands)
    }

    @Test
    fun `linking that never finishes fails after a minute`() = runTest {
        val recorder = recorder()
        recorder.link(notePro)
        advanceTimeBy(Recorder.LINK_TIME.toMillis() + 1)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped, notePro), recorder.state.value.link)
        assertEquals("disconnect", source.commands.last())
    }

    @Test
    fun `a saved recorder comes back with its last-seen values but not connected`() = runTest {
        store.saved = RecorderState(
            linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro),
            lastSeen = now,
            battery = Seen(BatteryReading(64, false), now),
        )
        source.bluetoothOn = false
        val state = recorder().state.value
        assertEquals(64, state.battery?.value?.percent)
        assertEquals(false, state.isConnected)
        assertEquals(false, state.bluetoothOn)
        assertEquals(RecordingNow.Unknown, state.recordingNow)
    }

    @Test
    fun `reconnect only asks when linked, disconnected and Bluetooth is on`() = runTest {
        val recorder = recorder()
        recorder.reconnect()
        assertEquals(emptyList<String>(), source.commands)

        store.saved = RecorderState(linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro))
        recorder().reconnect()
        assertEquals(listOf("connect ${notePro.serial}"), source.commands)
    }

    @Test
    fun `unlinking forgets the saved recorder`() = runTest {
        store.saved = RecorderState(linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro))
        val recorder = recorder()
        recorder.unlink()
        assertEquals(null, store.saved.linked)
        assertEquals("unlink ${notePro.serial}", source.commands.last())
    }

    @Test
    fun `linking the recorder that's already ours uses the ordinary handshake`() = runTest {
        store.saved = RecorderState(linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro))
        recorder().link(notePro)
        assertEquals("relink ${notePro.serial}", source.commands.last())
    }

    @Test
    fun `searching drops a connected recorder first, since it won't show in a scan`() = runTest {
        store.saved = RecorderState(linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro))
        val recorder = recorder()
        source.emit(RecorderEvent.Connected(notePro.serial))
        runCurrent()
        recorder.search()
        assertEquals(listOf("refresh", "disconnect", "startSearch"), source.commands)
    }

    @Test
    fun `cancelling a link disconnects and goes back to idle`() = runTest {
        val recorder = recorder()
        recorder.link(notePro)
        recorder.cancelLink()
        assertEquals(LinkPhase.Idle, recorder.state.value.link)
        assertEquals("disconnect", source.commands.last())
        advanceTimeBy(Recorder.LINK_TIME.toMillis() + 1)
        assertEquals(LinkPhase.Idle, recorder.state.value.link)
    }

    private suspend fun TestScope.connected(recording: Boolean?): Recorder {
        store.saved = RecorderState(linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro))
        val recorder = recorder()
        source.emit(RecorderEvent.Connected(notePro.serial))
        if (recording != null) source.emit(RecorderEvent.Recording(recording))
        runCurrent()
        source.commands.clear()
        return recorder
    }

    @Test
    fun `start and stop are refused until the recorder says whether it is recording`() = runTest {
        val recorder = connected(recording = null)
        assertEquals(RecordControl.Unavailable, recorder.state.value.recordControl)
        assertEquals(false, recorder.startRecording())
        assertEquals(false, recorder.stopRecording())
        assertEquals(emptyList<String>(), source.commands)
    }

    @Test
    fun `start is refused while not connected, even with an old reading`() = runTest {
        store.saved = RecorderState(
            linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro),
            recording = Seen(RecordingReading(false, null), now),
        )
        val recorder = recorder()
        assertEquals(false, recorder.startRecording())
        assertEquals(emptyList<String>(), source.commands)
    }

    @Test
    fun `starting shows Starting until the recorder confirms`() = runTest {
        val recorder = connected(recording = false)
        assertEquals(RecordControl.Start, recorder.state.value.recordControl)
        assertEquals(false, recorder.stopRecording())

        assertTrue(recorder.startRecording())
        assertEquals(listOf("startRecording"), source.commands)
        assertEquals(RecordControl.Starting, recorder.state.value.recordControl)
        // A second tap while waiting does nothing.
        assertEquals(false, recorder.startRecording())

        // A stale "not recording" in between doesn't settle it.
        source.emit(RecorderEvent.Recording(false))
        runCurrent()
        assertEquals(RecordControl.Starting, recorder.state.value.recordControl)

        source.emit(RecorderEvent.Recording(true))
        runCurrent()
        assertEquals(RecordControl.Stop, recorder.state.value.recordControl)
    }

    @Test
    fun `stopping passes through Stopping to Start`() = runTest {
        val recorder = connected(recording = true)
        assertTrue(recorder.stopRecording())
        assertEquals(RecordControl.Stopping, recorder.state.value.recordControl)
        source.emit(RecorderEvent.Recording(false))
        runCurrent()
        assertEquals(RecordControl.Start, recorder.state.value.recordControl)
        assertEquals(listOf("stopRecording"), source.commands)
    }

    @Test
    fun `a start can ask for a scene`() = runTest {
        val recorder = connected(recording = false)
        recorder.startRecording(scene = 2)
        assertEquals(listOf("startRecording 2"), source.commands)
    }

    @Test
    fun `an unanswered command gives up and asks the recorder again`() = runTest {
        val recorder = connected(recording = false)
        recorder.startRecording()
        advanceTimeBy(Recorder.COMMAND_TIME.toMillis() + 1)
        assertEquals(RecordControl.Start, recorder.state.value.recordControl)
        assertEquals(listOf("startRecording", "refresh"), source.commands)
    }

    @Test
    fun `dropping the connection clears a waiting command`() = runTest {
        val recorder = connected(recording = false)
        recorder.startRecording()
        source.emit(RecorderEvent.Disconnected)
        runCurrent()
        assertEquals(null, recorder.state.value.pending)
        assertEquals(RecordControl.Unavailable, recorder.state.value.recordControl)
    }
}
