package com.boko.roudio.recorder

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderStateTest {
    private val t0 = Instant.parse("2026-10-10T13:00:00Z")
    private fun at(minutes: Long) = t0.plusSeconds(minutes * 60)

    private val notePro = FoundRecorder("8810000004F2A", RecorderModel.NotePro, signal = -60)
    private val connected = RecorderState().linking(notePro).reduce(RecorderEvent.Connected(notePro.serial), t0)

    @Test
    fun `linking a found recorder remembers it and connects`() {
        assertEquals(LinkedRecorder(notePro.serial, RecorderModel.NotePro), connected.linked)
        assertEquals(LinkPhase.Idle, connected.link)
        assertTrue(connected.isConnected)
    }

    @Test
    fun `recording state is unknown until the recorder reports it on this connection`() {
        assertEquals(RecordingNow.Unknown, connected.recordingNow)

        val recording = connected.reduce(RecorderEvent.Recording(true, at(-20)), at(1))
        assertEquals(RecordingNow.Recording(at(-20)), recording.recordingNow)

        val stopped = recording.reduce(RecorderEvent.Recording(false), at(2))
        assertEquals(RecordingNow.NotRecording, stopped.recordingNow)
    }

    @Test
    fun `recording state goes unknown when the connection drops and stays so after reconnecting`() {
        val recording = connected.reduce(RecorderEvent.Recording(true), at(1))
        val away = recording.reduce(RecorderEvent.Disconnected, at(5))
        assertEquals(RecordingNow.Unknown, away.recordingNow)
        assertEquals(at(5), away.lastSeen)

        // The old reading is from the previous connection, so it doesn't count.
        val back = away.reduce(RecorderEvent.Connected(notePro.serial), at(30))
        assertEquals(RecordingNow.Unknown, back.recordingNow)
    }

    @Test
    fun `battery and storage are kept with their time after disconnecting`() {
        val state = connected
            .reduce(RecorderEvent.Battery(64, charging = false), at(1))
            .reduce(RecorderEvent.Storage(64_000_000_000, 58_000_000_000), at(2))
            .reduce(RecorderEvent.Disconnected, at(41))
        assertFalse(state.isConnected)
        assertEquals(Seen(BatteryReading(64, false), at(1)), state.battery)
        assertEquals(58_000_000_000, state.storage?.value?.freeBytes)
        assertEquals(at(41), state.lastSeen)
    }

    @Test
    fun `a battery level without charging info keeps the last charging state`() {
        val state = connected
            .reduce(RecorderEvent.Battery(80, charging = true), at(1))
            .reduce(RecorderEvent.Battery(81), at(2))
        assertEquals(BatteryReading(81, true), state.battery?.value)
    }

    @Test
    fun `a search with nothing in it becomes not found, a found list stays`() {
        assertEquals(LinkPhase.NothingFound, RecorderState().searching().searchTimedOut().link)

        val found = RecorderState().searching().reduce(RecorderEvent.Found(listOf(notePro)), t0)
        assertEquals(found, found.searchTimedOut())
    }

    @Test
    fun `found recorders are listed nearest first`() {
        val far = notePro.copy(serial = "88200000091C0", signal = -90)
        val state = RecorderState().searching().reduce(RecorderEvent.Found(listOf(far, notePro)), t0)
        assertEquals(listOf(notePro, far), (state.link as LinkPhase.Searching).found)
    }

    @Test
    fun `found results are ignored when not searching`() {
        val state = RecorderState().reduce(RecorderEvent.Found(listOf(notePro)), t0)
        assertEquals(LinkPhase.Idle, state.link)
    }

    @Test
    fun `a drop while linking is a failed link`() {
        val state = RecorderState().linking(notePro).reduce(RecorderEvent.Disconnected, t0)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped, notePro), state.link)
        assertNull(state.linked)
    }

    @Test
    fun `Bluetooth off while linking is a failed link`() {
        val state = RecorderState().linking(notePro).reduce(RecorderEvent.BluetoothChanged(false), t0)
        assertEquals(LinkPhase.Failed(LinkFailure.Dropped, notePro), state.link)
    }

    @Test
    fun `the Bluetooth step on 11h ticks once the connection is up`() {
        val state = RecorderState().linking(notePro).reduce(RecorderEvent.BluetoothLinked, t0)
        assertEquals(LinkPhase.Linking(notePro, bluetoothUp = true), state.link)
    }

    @Test
    fun `a bound recorder is linked elsewhere unless it's the one Roudio has`() {
        val bound = notePro.copy(linkedElsewhere = true)
        val other = bound.copy(serial = "88200000091C0")
        val ours = RecorderState(linked = LinkedRecorder(notePro.serial, RecorderModel.NotePro)).searching()
            .reduce(RecorderEvent.Found(listOf(bound, other)), t0)
        assertEquals(listOf(notePro, other), (ours.link as LinkPhase.Searching).found)
    }

    @Test
    fun `a refused handshake while linking means linked to another app`() {
        val state = RecorderState().linking(notePro)
            .reduce(RecorderEvent.LinkFailed(notePro.serial, LinkFailure.LinkedElsewhere), t0)
        assertEquals(LinkPhase.Failed(LinkFailure.LinkedElsewhere, notePro), state.link)
    }

    @Test
    fun `turning Bluetooth off disconnects and stops a search`() {
        val off = connected.searching().reduce(RecorderEvent.BluetoothChanged(false), at(3))
        assertFalse(off.bluetoothOn)
        assertFalse(off.isConnected)
        assertEquals(LinkPhase.Idle, off.link)
        assertEquals(at(3), off.lastSeen)
    }

    @Test
    fun `unlinking forgets everything but the Bluetooth state`() {
        val state = connected.reduce(RecorderEvent.Battery(50), at(1)).unlinked()
        assertEquals(RecorderState(), state)
    }
}
