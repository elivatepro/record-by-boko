package com.boko.roudio.nudge

import com.boko.roudio.recorder.BatteryReading
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingReading
import com.boko.roudio.recorder.Seen
import com.boko.roudio.recorder.StorageReading
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeRulesTest {
    private val lagos = ZoneOffset.ofHours(1)
    private val noon = Instant.parse("2026-11-14T11:00:00Z") // 12:00 local
    private val linked = RecorderState(linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro))

    private fun connected(at: Instant, recording: Boolean? = false, battery: Int = 80, charging: Boolean = false, freeOf100: Long = 50) =
        linked.copy(
            connectedSince = at.minusSeconds(3600),
            lastSeen = at,
            recording = recording?.let { Seen(RecordingReading(it, null), at) },
            battery = Seen(BatteryReading(battery, charging), at),
            storage = Seen(StorageReading(100, freeOf100), at),
        )

    private fun input(
        recorder: RecorderState,
        at: Instant = noon,
        settings: NudgeSettings = NudgeSettings(),
        events: List<CalendarEvent> = emptyList(),
        calendar: Boolean = true,
        waiting: Int = 0,
    ) = NudgeInputs(settings, recorder, at, LocalDateTime.ofInstant(at, lagos), calendar, events, waiting, Duration.ofMinutes(waiting * 60L))

    private fun run(vararg inputs: NudgeInputs, memory: NudgeMemory = NudgeMemory()): List<List<Nudge>> {
        var m = memory
        return inputs.map { i -> NudgeRules.evaluate(i, m).also { m = it.second }.first }
    }

    @Test
    fun `a meeting that just started nudges once, with Start when the recorder can`() {
        val event = CalendarEvent(7, "Design review", noon.minusSeconds(60), allDay = false)
        val (first, second) = run(input(connected(noon), events = listOf(event)), input(connected(noon.plusSeconds(60)), noon.plusSeconds(60), events = listOf(event)))
        assertEquals(listOf(Nudge.Meeting("Design review", canStart = true, key = "meeting:7:${event.begin.epochSecond}")), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `no meeting nudge while recording, for all-day events, without the calendar, or switched off`() {
        val event = CalendarEvent(7, "Design review", noon.minusSeconds(60), allDay = false)
        assertTrue(run(input(connected(noon, recording = true), events = listOf(event)))[0].isEmpty())
        assertTrue(run(input(connected(noon), events = listOf(event.copy(allDay = true))))[0].isEmpty())
        assertTrue(run(input(connected(noon), events = listOf(event), calendar = false))[0].isEmpty())
        assertTrue(run(input(connected(noon), events = listOf(event), settings = NudgeSettings(meetings = false)))[0].isEmpty())
    }

    @Test
    fun `a meeting while out of range still nudges, without a Start button`() {
        val event = CalendarEvent(7, "Design review", noon.minusSeconds(60), allDay = false)
        val nudge = run(input(linked, events = listOf(event)))[0].single() as Nudge.Meeting
        assertEquals(false, nudge.canStart)
    }

    @Test
    fun `idle for the set time during waking hours nudges once per idle stretch`() {
        val ticks = (0..31).map { m -> noon.plusSeconds(m * 60L) }
        val results = run(*ticks.map { input(connected(it), at = it) }.toTypedArray())
        assertEquals(1, results.flatten().count { it is Nudge.Idle })
        assertTrue(results[30].single() is Nudge.Idle) // after 30 minutes, not before
    }

    @Test
    fun `recording resets the idle clock`() {
        val start = noon
        val (_, m1) = NudgeRules.evaluate(input(connected(start), at = start), NudgeMemory())
        val mid = start.plusSeconds(20 * 60)
        val (_, m2) = NudgeRules.evaluate(input(connected(mid, recording = true), at = mid), m1)
        val later = start.plusSeconds(35 * 60)
        val (due, _) = NudgeRules.evaluate(input(connected(later), at = later), m2)
        assertTrue(due.none { it is Nudge.Idle }) // only 0 minutes idle since recording stopped
    }

    @Test
    fun `nothing at night, and a low battery waits for the morning`() {
        val night = Instant.parse("2026-11-14T01:00:00Z") // 02:00 local
        val (nightDue, m) = NudgeRules.evaluate(input(connected(night, battery = 15), at = night), NudgeMemory())
        assertTrue(nightDue.isEmpty())
        val morning = Instant.parse("2026-11-14T06:05:00Z") // 07:05 local
        val (morningDue, _) = NudgeRules.evaluate(input(connected(morning, battery = 15), at = morning), m)
        assertEquals(listOf(Nudge.BatteryLow(15, "battery-low")), morningDue)
    }

    @Test
    fun `a low battery fires once, and again only after it has recovered`() {
        val t = (0..3).map { noon.plusSeconds(it * 600L) }
        val results = run(
            input(connected(t[0], battery = 18), at = t[0]),
            input(connected(t[1], battery = 17), at = t[1]),
            input(connected(t[2], battery = 60, charging = true), at = t[2]),
            input(connected(t[3], battery = 19), at = t[3]),
        )
        assertEquals(listOf(1, 0, 0, 1), results.map { r -> r.count { it is Nudge.BatteryLow } })
    }

    @Test
    fun `in the evening a battery under half asks to charge it tonight, once a day`() {
        val evening = Instant.parse("2026-11-14T20:10:00Z") // 21:10 local; waking ends 23:00
        val results = run(input(connected(evening, battery = 41), at = evening), input(connected(evening.plusSeconds(600), battery = 40), at = evening.plusSeconds(600)))
        assertEquals(listOf(Nudge.ChargeTonight(41, "charge:2026-11-14")), results[0])
        assertTrue(results[1].isEmpty())
        // Not in the afternoon.
        assertTrue(run(input(connected(noon, battery = 41)))[0].isEmpty())
    }

    @Test
    fun `a low battery in the evening is one nudge, not two`() {
        val evening = Instant.parse("2026-11-14T20:10:00Z")
        val results = run(input(connected(evening, battery = 12), at = evening), input(connected(evening.plusSeconds(60), battery = 12), at = evening.plusSeconds(60)))
        assertEquals(listOf(Nudge.BatteryLow(12, "battery-low")), results[0])
        assertTrue(results[1].isEmpty())
    }

    @Test
    fun `plugging it in says it's charging once, then once more when it's full`() {
        val t = (0..4).map { noon.plusSeconds(it * 600L) }
        val results = run(
            input(connected(t[0], battery = 40), at = t[0]),
            input(connected(t[1], battery = 42, charging = true), at = t[1]),
            input(connected(t[2], battery = 70, charging = true), at = t[2]),
            input(connected(t[3], battery = 100, charging = true), at = t[3]),
            input(connected(t[4], battery = 100, charging = true), at = t[4]),
        )
        val charging = results.map { r -> r.filter { it is Nudge.Charging || it is Nudge.Charged } }
        assertEquals(listOf(emptyList(), listOf(Nudge.Charging(42, "charging")), emptyList(), listOf(Nudge.Charged("charged")), emptyList()), charging)
    }

    @Test
    fun `unplugging and plugging back in says so again, a dropped connection doesn't`() {
        val t = (0..3).map { noon.plusSeconds(it * 600L) }
        val results = run(
            input(connected(t[0], battery = 50, charging = true), at = t[0]),
            input(linked.copy(lastSeen = t[1]), at = t[1]),
            input(connected(t[2], battery = 55, charging = true), at = t[2]),
            input(connected(t[3], battery = 55), at = t[3]),
            input(connected(t[3].plusSeconds(60), battery = 55, charging = true), at = t[3].plusSeconds(60)),
        )
        assertEquals(listOf(1, 0, 0, 0, 1), results.map { r -> r.count { it is Nudge.Charging } })
    }

    @Test
    fun `charging answers at night, full waits for the morning, and the switch turns both off`() {
        val night = Instant.parse("2026-11-14T01:00:00Z") // 02:00 local
        val morning = Instant.parse("2026-11-14T06:05:00Z") // 07:05 local
        val results = run(
            input(connected(night, battery = 30, charging = true), at = night),
            input(connected(night.plusSeconds(7200), battery = 100, charging = true), at = night.plusSeconds(7200)),
            input(connected(morning, battery = 100, charging = true), at = morning),
        )
        assertEquals(listOf(listOf(Nudge.Charging(30, "charging")), emptyList(), listOf(Nudge.Charged("charged"))), results)
        val off = NudgeSettings(charging = false)
        assertTrue(run(input(connected(noon, battery = 30, charging = true), settings = off))[0].isEmpty())
        // Plugged in already full: just "fully charged".
        assertEquals(listOf(Nudge.Charged("charged")), run(input(connected(noon, battery = 100, charging = true)))[0])
    }

    @Test
    fun `storage nearly full nudges only while recordings wait to transfer`() {
        assertTrue(run(input(connected(noon, freeOf100 = 5), waiting = 0))[0].isEmpty())
        val due = run(input(connected(noon, freeOf100 = 5), waiting = 3))[0].single()
        assertEquals(Nudge.StorageFull(3, Duration.ofHours(3), "storage-full"), due)
    }

    @Test
    fun `each nudge can be switched off`() {
        val off = NudgeSettings(battery = false, storage = false)
        assertTrue(run(input(connected(noon, battery = 10, freeOf100 = 5), settings = off, waiting = 2))[0].isEmpty())
    }
}
