package com.boko.roudio.ui.today

import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingReading
import com.boko.roudio.recorder.Seen
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import com.boko.roudio.transfer.Transfers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayModelTest {
    private val lagos = ZoneOffset.ofHours(1)
    private val now = Instant.parse("2026-10-11T13:32:00Z") // 14:32 in Lagos
    private val today = LocalDate.of(2026, 10, 11)
    private val linked = RecorderState(linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro))
    private var nextId = 1L

    private fun recording(start: String, minutes: Long, transfer: TransferState, uploaded: Long = 0) = Recording(
        id = nextId++,
        serial = "8810000004F2A",
        sessionId = Instant.parse(start).epochSecond,
        startedAt = Instant.parse(start),
        zoneOffsetSeconds = 3600,
        sizeOnRecorder = minutes * 60 * 33_000 / 8,
        transfer = transfer,
        fileBytes = if (transfer >= TransferState.OnPhone) 1000 else null,
        durationMs = if (transfer >= TransferState.OnPhone) minutes * 60_000 else null,
        uploadedBytes = uploaded,
        firstSeenAt = now,
        updatedAt = now,
    )

    private fun build(
        recordings: List<Recording>,
        recorder: RecorderState = linked,
        day: LocalDate = today,
        progress: Transfers.Progress? = null,
    ) = TodayModel.build(recordings, recorder, progress, day, now, lagos)

    @Test
    fun `a recording sits on the dial at its local time and counts toward the total`() {
        val model = build(listOf(recording("2026-10-11T09:00:00Z", 60, TransferState.Uploaded)))
        val span = model.spans.single()
        assertEquals(10f / 24, span.start, 0.0001f) // 10:00 local
        assertEquals(11f / 24, span.end, 0.0001f)
        assertEquals(SpanKind.NotWrittenUp, span.kind)
        assertEquals(Duration.ofHours(1), model.recorded)
        val row = model.rows.single()
        assertEquals("10:00", row.time)
        assertEquals(RowTitle.Length(Duration.ofHours(1)), row.title)
        assertEquals(RowStatus.WaitingToWriteUp, row.status)
        assertFalse(row.stillOnPlaud)
    }

    @Test
    fun `recordings still on the recorder are buoy spans, waiting on your Plaud`() {
        val model = build(
            listOf(
                recording("2026-10-11T08:00:00Z", 30, TransferState.OnRecorder),
                recording("2026-10-11T09:00:00Z", 30, TransferState.Transferring),
            ),
            progress = Transfers.Progress(2, 48, now),
        )
        assertTrue(model.spans.all { it.kind == SpanKind.StillOnPlaud })
        assertEquals(listOf(RowStatus.Transferring(48), RowStatus.NotTransferred), model.rows.map { it.status })
        assertTrue(model.rows.all { it.title == RowTitle.WaitingOnPlaud && it.stillOnPlaud })
        assertEquals(2, model.onRecorder)
    }

    @Test
    fun `the file being recorded runs to now and leads the timeline`() {
        val since = Instant.parse("2026-10-11T13:10:00Z")
        val recording = linked.copy(
            connectedSince = now.minusSeconds(3600),
            recording = Seen(RecordingReading(true, since), now),
        )
        val model = build(emptyList(), recording)
        val span = model.spans.single()
        assertEquals(SpanKind.StillOnPlaud, span.kind)
        assertEquals((14 * 60 + 10) / 1440f, span.start, 0.0001f)
        assertEquals((14 * 60 + 32) / 1440f, span.end, 0.0001f)
        assertEquals(RowStatus.RecordingNow, model.rows.first().status)
        assertEquals("14:10", model.rows.first().time)
        assertEquals(1, model.onRecorder)
        assertEquals((14 * 60 + 32) / 1440f, model.now!!, 0.0001f)
    }

    @Test
    fun `out of reach while it was recording says it may still be recording`() {
        val away = linked.copy(
            recording = Seen(RecordingReading(true, Instant.parse("2026-10-11T13:10:00Z")), now.minusSeconds(900)),
        )
        val model = build(emptyList(), away)
        assertEquals(RowStatus.MaybeRecording, model.rows.single().status)
        assertTrue(model.spans.isEmpty())
    }

    @Test
    fun `a recording across midnight shows on both days`() {
        val late = recording("2026-10-10T22:30:00Z", 60, TransferState.Uploaded) // 23:30 → 00:30 local
        val yesterday = build(listOf(late), day = today.minusDays(1))
        assertEquals(Duration.ofMinutes(30), yesterday.recorded)
        assertNull(yesterday.now)
        assertFalse(yesterday.isToday)
        val todayModel = build(listOf(late))
        assertEquals(0f, todayModel.spans.single().start)
        assertEquals(Duration.ofMinutes(30), todayModel.recorded)
    }

    @Test
    fun `upload progress and the no-internet count come from the saved rows`() {
        val model = build(
            listOf(
                recording("2026-10-11T08:00:00Z", 10, TransferState.Uploading, uploaded = 250),
                recording("2026-10-11T09:00:00Z", 10, TransferState.OnPhone),
                recording("2026-10-11T10:00:00Z", 10, TransferState.Uploaded),
            ),
        )
        assertEquals(
            listOf(RowStatus.WaitingToWriteUp, RowStatus.WaitingToUpload, RowStatus.Uploading(25)),
            model.rows.map { it.status },
        )
        assertEquals(2, model.waitingToUpload)
    }
}
