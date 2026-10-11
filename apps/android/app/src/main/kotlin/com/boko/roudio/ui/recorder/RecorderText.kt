package com.boko.roudio.ui.recorder

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.boko.roudio.R
import com.boko.roudio.recorder.BatteryReading
import com.boko.roudio.recorder.RecordingNow
import com.boko.roudio.recorder.StorageReading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong

/** How recorder readings are worded everywhere (11i, 13a, 13b, the Today chip). */

@Composable
fun batteryText(battery: BatteryReading): String = when (battery.charging) {
    true -> stringResource(R.string.battery_charging, battery.percent)
    false -> stringResource(R.string.battery_not_charging, battery.percent)
    null -> stringResource(R.string.battery_level, battery.percent)
}

/** "58 of 64 GB". Free space is rounded down, so it never looks roomier than it is. */
@Composable
fun freeSpaceText(storage: StorageReading): String =
    stringResource(R.string.free_of_total, gigabytes(storage.freeBytes, roundDown = true), gigabytes(storage.totalBytes))

fun gigabytes(bytes: Long, roundDown: Boolean = false): String {
    val gb = bytes / 1_000_000_000.0
    return (if (roundDown) gb.toLong() else gb.roundToLong()).toString()
}

@Composable
fun recordingText(now: RecordingNow): String = when (now) {
    is RecordingNow.Recording ->
        now.since?.let { stringResource(R.string.recording_yes_since, clockTime(it)) } ?: stringResource(R.string.recording_yes)
    RecordingNow.NotRecording -> stringResource(R.string.recording_no)
    RecordingNow.Unknown -> stringResource(R.string.recording_unknown)
}

/** "14:10" in the phone's time zone and 12/24-hour setting. */
fun clockTime(at: Instant): String = CLOCK.format(at.atZone(ZoneId.systemDefault()))

private val CLOCK = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
