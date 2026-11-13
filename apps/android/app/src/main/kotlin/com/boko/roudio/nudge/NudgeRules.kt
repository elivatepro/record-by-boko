package com.boko.roudio.nudge

import com.boko.roudio.recorder.RecordControl
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingNow
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/** An event from the phone's calendar (CalendarContract.Instances). Nothing leaves the phone. */
data class CalendarEvent(val id: Long, val title: String, val begin: Instant, val allDay: Boolean)

/** A nudge to post. [key] makes each one fire once per event or condition. */
sealed interface Nudge {
    val key: String

    /** "Design review started — start recording?" [canStart]: the Start button can work right now. */
    data class Meeting(val title: String, val canStart: Boolean, override val key: String) : Nudge

    data class Idle(val minutes: Int, override val key: String) : Nudge

    data class BatteryLow(val percent: Int, override val key: String) : Nudge

    data class ChargeTonight(val percent: Int, override val key: String) : Nudge

    /** Storage nearly full while [waiting] recordings ([waitingLength] of audio) wait to transfer. */
    data class StorageFull(val waiting: Int, val waitingLength: Duration, override val key: String) : Nudge
}

data class NudgeInputs(
    val settings: NudgeSettings,
    val recorder: RecorderState,
    val now: Instant,
    /** The phone's local time now, for waking hours and the evening reminder. */
    val local: LocalDateTime,
    val calendarAllowed: Boolean,
    /** Events that began in the last [NudgeRules.MEETING_WINDOW] (the caller asks the calendar for them). */
    val events: List<CalendarEvent>,
    /** Recordings still on the recorder and how much audio they hold. */
    val waiting: Int,
    val waitingLength: Duration,
)

/** What has fired already, and since when the recorder has sat idle. Saved across restarts. */
data class NudgeMemory(val fired: Set<String> = emptySet(), val idleSince: Instant? = null)

/**
 * The nudge rules (next-features.md, milestone 8). Pure: the service gathers the inputs, posts what
 * comes back and saves the memory. Quiet outside waking hours: battery and storage nudges whose
 * condition still holds simply fire when waking hours start.
 */
object NudgeRules {
    val MEETING_WINDOW: Duration = Duration.ofMinutes(10)
    private const val BATTERY_LOW = 20
    private const val BATTERY_RESET = 30
    private const val CHARGE_TONIGHT_BELOW = 50
    private const val STORAGE_FULL = 0.10
    private const val STORAGE_RESET = 0.15
    private val EVENING_BEFORE_SLEEP: Duration = Duration.ofHours(2)
    private const val MEMORY_LIMIT = 200

    fun evaluate(input: NudgeInputs, memory: NudgeMemory): Pair<List<Nudge>, NudgeMemory> {
        val s = input.settings
        val r = input.recorder
        val awake = s.waking.contains(input.local.toLocalTime())
        val recording = r.recordingNow is RecordingNow.Recording
        var fired = memory.fired

        // Conditions that have cleared can fire again next time.
        val battery = r.battery?.value?.takeIf { r.isConnected }
        if (battery != null && (battery.percent >= BATTERY_RESET || battery.charging == true)) fired = fired - BATTERY_KEY
        val free = r.storage?.value?.takeIf { r.isConnected && it.totalBytes > 0 }?.let { it.freeBytes.toDouble() / it.totalBytes }
        if (free != null && free >= STORAGE_RESET) fired = fired - STORAGE_KEY

        // Idle time only counts while awake, connected and known not to be recording.
        val idleNow = awake && r.recordControl == RecordControl.Start
        val idleSince = if (idleNow) memory.idleSince ?: input.now else null

        val out = mutableListOf<Nudge>()
        if (awake) {
            if (s.meetings && input.calendarAllowed && !recording) {
                input.events
                    .filter { !it.allDay && !it.begin.isAfter(input.now) && it.begin.isAfter(input.now.minus(MEETING_WINDOW)) }
                    .forEach { out += Nudge.Meeting(it.title, r.recordControl == RecordControl.Start, "meeting:${it.id}:${it.begin.epochSecond}") }
            }
            if (s.idle && idleSince != null && Duration.between(idleSince, input.now) >= Duration.ofMinutes(s.idleMinutes.toLong())) {
                out += Nudge.Idle(s.idleMinutes, "idle:${idleSince.epochSecond}")
            }
            if (s.battery && battery != null && battery.charging != true) {
                if (battery.percent < BATTERY_LOW) out += Nudge.BatteryLow(battery.percent, BATTERY_KEY)
                val evening = !input.local.toLocalTime().isBefore(s.waking.until.minus(EVENING_BEFORE_SLEEP))
                if (evening && battery.percent < CHARGE_TONIGHT_BELOW) {
                    out += Nudge.ChargeTonight(battery.percent, "charge:${input.local.toLocalDate()}")
                }
            }
            if (s.storage && free != null && free < STORAGE_FULL && input.waiting > 0) {
                out += Nudge.StorageFull(input.waiting, input.waitingLength, STORAGE_KEY)
            }
        }
        // A low battery covers the evening reminder too: one nudge about the battery, not two.
        val fresh = out.filter { it.key !in fired }
        val lowBattery = fresh.any { it is Nudge.BatteryLow }
        val due = if (lowBattery) fresh.filterNot { it is Nudge.ChargeTonight } else fresh
        val covered = if (lowBattery) out.filterIsInstance<Nudge.ChargeTonight>().map { it.key } else emptyList()
        fired = (fired + due.map { it.key } + covered).toList().takeLast(MEMORY_LIMIT).toSet()
        return due to NudgeMemory(fired, idleSince)
    }

    private const val BATTERY_KEY = "battery-low"
    private const val STORAGE_KEY = "storage-full"
}
