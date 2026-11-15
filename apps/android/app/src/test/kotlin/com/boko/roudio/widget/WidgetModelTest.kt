package com.boko.roudio.widget

import com.boko.roudio.recorder.BatteryReading
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecordCommand
import com.boko.roudio.recorder.RecordControl
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingReading
import com.boko.roudio.recorder.Seen
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {
    private val lagos = ZoneOffset.ofHours(1)
    private val now = Instant.parse("2026-11-14T13:40:00Z") // 14:40 local
    private val linked = RecorderState(linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro))
    private val connected = linked.copy(
        connectedSince = now.minusSeconds(600),
        lastSeen = now,
        battery = Seen(BatteryReading(64, false), now),
    )

    private fun recording(start: String, minutes: Long) = Recording(
        id = 1,
        serial = "8810000004F2A",
        sessionId = Instant.parse(start).epochSecond,
        startedAt = Instant.parse(start),
        zoneOffsetSeconds = 3600,
        sizeOnRecorder = 0,
        transfer = TransferState.Uploaded,
        fileBytes = 1000,
        durationMs = minutes * 60_000,
        uploadedBytes = 0,
        firstSeenAt = now,
        updatedAt = now,
    )

    private fun build(state: RecorderState, recordings: List<Recording> = emptyList()) =
        WidgetModel.build(state, recordings, now, lagos)

    @Test
    fun `recording shows since when, battery and a Stop button`() {
        val since = Instant.parse("2026-11-14T08:12:00Z")
        val m = build(connected.copy(recording = Seen(RecordingReading(true, since), now)))
        assertEquals(WidgetModel.Status.Recording, m.status)
        assertEquals(LocalTime.of(9, 12), m.since)
        assertEquals(64, m.battery)
        assertEquals("Note Pro", m.modelName)
        assertEquals(RecordControl.Stop, m.control)
        // The recording in progress counts toward today's captured time.
        assertEquals(Duration.ofMinutes(5 * 60 + 28), m.captured)
    }

    @Test
    fun `not recording says when today's last recording stopped`() {
        val m = build(connected.copy(recording = Seen(RecordingReading(false, null), now)), listOf(recording("2026-11-14T09:00:00Z", 65)))
        assertEquals(WidgetModel.Status.NotRecording, m.status)
        assertEquals(LocalTime.of(11, 5), m.since)
        assertEquals(RecordControl.Start, m.control)
        assertEquals(Duration.ofMinutes(65), m.captured)
    }

    @Test
    fun `charging shows on the gauge only while the battery does`() {
        val plugged = connected.copy(battery = Seen(BatteryReading(64, true), now))
        val m = build(plugged.copy(recording = Seen(RecordingReading(false, null), now)))
        assertEquals(true, m.charging)
        assertEquals(false, build(connected.copy(recording = Seen(RecordingReading(false, null), now))).charging)
        // Not yet reported: no battery, so no bolt either.
        assertEquals(false, build(plugged).charging)
    }

    @Test
    fun `connected but not yet reported reads as not connected, with no button`() {
        val m = build(connected)
        assertEquals(WidgetModel.Status.NotConnected, m.status)
        assertEquals(RecordControl.Unavailable, m.control)
        assertNull(m.battery)
    }

    @Test
    fun `out of range shows when it was last heard and no button`() {
        val m = build(linked.copy(lastSeen = Instant.parse("2026-11-14T13:02:00Z")))
        assertEquals(WidgetModel.Status.NotConnected, m.status)
        assertEquals(LocalTime.of(14, 2), m.lastHeard)
        assertEquals(RecordControl.Unavailable, m.control)
    }

    @Test
    fun `a waiting start shows Starting`() {
        val m = build(connected.copy(recording = Seen(RecordingReading(false, null), now)).asked(RecordCommand.Start, now))
        assertEquals(RecordControl.Starting, m.control)
    }

    @Test
    fun `no recorder linked draws an empty ring`() {
        val m = build(RecorderState())
        assertEquals(WidgetModel.Status.NoRecorder, m.status)
        assertTrue(m.spans.isEmpty())
    }
}
