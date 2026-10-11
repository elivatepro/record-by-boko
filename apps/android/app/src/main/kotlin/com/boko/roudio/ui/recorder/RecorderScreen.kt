package com.boko.roudio.ui.recorder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.recorder.BatteryReading
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingNow
import com.boko.roudio.recorder.RecordingReading
import com.boko.roudio.recorder.Seen
import com.boko.roudio.recorder.StorageReading
import com.boko.roudio.recorder.serialEnding
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import com.boko.roudio.transfer.FirmwareGate
import com.boko.roudio.transfer.Transfers
import com.boko.roudio.ui.today.durationText
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.setup.SafeNote
import com.boko.roudio.ui.setup.SmallButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.delay

/**
 * 13a (connected) and 13b (not connected), as far as milestone 1 goes: the recorder card, what's
 * safe while it's away, and unlinking. Transfers and the options arrive with milestone 2.
 */
@Composable
fun RecorderScreen(
    state: RecorderState,
    now: Instant,
    recordings: List<Recording>,
    progress: Transfers.Progress?,
    stopped: Transfers.Progress?,
    onReconnect: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onUnlink: () -> Unit,
    onBack: () -> Unit,
) {
    val c = RoudioTheme.colors
    var confirmUnlink by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = c.raised, contentColor = c.ink),
            ) {
                Icon(RoudioIcons.Back, contentDescription = stringResource(R.string.back), Modifier.size(22.dp))
            }
            Text(
                stringResource(R.string.recorder_title),
                style = roudioText(18.sp, 24.sp, FontWeight.SemiBold),
                color = c.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Spacer(Modifier.width(48.dp))
        }
        RecorderCard(state, now)
        val reconnect: @Composable () -> Unit = {
            SmallButton(
                stringResource(if (state.bluetoothOn) R.string.try_to_reconnect else R.string.turn_on_bluetooth),
                if (state.bluetoothOn) onReconnect else onTurnOnBluetooth,
                filled = true,
                modifier = Modifier.fillMaxWidth(),
                height = 44.dp,
            )
        }
        // A stopped transfer carries its own "what's safe" note and the reconnect button (13b).
        val stoppedRecording = stopped?.takeIf { !state.isConnected }
            ?.let { s -> recordings.firstOrNull { it.id == s.recordingId && it.transfer == TransferState.OnRecorder } }
        TransfersSection(state, now, recordings, progress, stopped?.takeIf { stoppedRecording != null }, stoppedRecording, reconnect)
        if (!state.isConnected && stoppedRecording == null) {
            SafeNote(
                stringResource(if (state.bluetoothOn) R.string.recorder_away_safe else R.string.recorder_bluetooth_safe),
            ) { reconnect() }
        }
        val gate = FirmwareGate.gate(state, recordings)
        if (gate != FirmwareGate.Gate.NoUpdate) {
            Column {
                Text(
                    stringResource(R.string.options),
                    style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
                    color = c.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.update_title), style = roudioText(16.sp, 22.sp, FontWeight.SemiBold), color = c.ink)
                        Text(
                            stringResource(if (gate == FirmwareGate.Gate.Ready) R.string.update_ready_body else R.string.update_waiting_body),
                            style = roudioText(13.sp, 18.sp),
                            color = c.muted,
                        )
                    }
                    Text(
                        stringResource(if (gate == FirmwareGate.Gate.Ready) R.string.update_ready else R.string.update_waiting),
                        style = roudioText(14.sp, 18.sp, FontWeight.SemiBold),
                        color = c.muted,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            }
        }
        Text(
            stringResource(R.string.unlink_recorder),
            style = roudioText(15.sp, 20.sp, FontWeight.SemiBold),
            color = c.alert,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { confirmUnlink = true }
                .padding(vertical = 14.dp),
        )
    }
    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text(stringResource(R.string.unlink_confirm_title)) },
            text = { Text(stringResource(R.string.unlink_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlink = false
                    onUnlink()
                }) { Text(stringResource(R.string.unlink), color = c.alert) }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The navy card: name, connection line and the three tiles. */
@Composable
private fun RecorderCard(state: RecorderState, now: Instant) {
    val c = RoudioTheme.colors
    val linked = state.linked ?: return
    val connected = state.isConnected
    val seen = state.lastSeen?.let(::clockTime)
    Column(
        Modifier.fillMaxWidth().background(c.heroCard, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(c.heroTile, CircleShape), contentAlignment = Alignment.Center) {
                Icon(RoudioIcons.Recorder, contentDescription = null, tint = c.onHeroCard, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.recorder_name, linked.model.displayName, linked.serial.serialEnding),
                    style = roudioText(18.sp, 24.sp, FontWeight.SemiBold),
                    color = c.onHeroCard,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Filled dot when connected, hollow when not (13a vs 13b).
                    Box(
                        Modifier
                            .size(8.dp)
                            .then(
                                if (connected) {
                                    Modifier.background(c.onHeroCard, CircleShape)
                                } else {
                                    Modifier.border(1.5.dp, c.onHeroCard, CircleShape)
                                },
                            ),
                    )
                    Text(
                        when {
                            connected && isRecent(state.lastSeen, now) -> stringResource(R.string.recorder_connected_now)
                            connected && seen != null -> stringResource(R.string.recorder_connected_seen, seen)
                            !state.bluetoothOn && seen != null -> stringResource(R.string.recorder_bluetooth_off_seen, seen)
                            !state.bluetoothOn -> stringResource(R.string.recorder_bluetooth_off)
                            seen != null -> stringResource(R.string.recorder_not_connected_seen, seen)
                            else -> stringResource(R.string.recorder_not_connected)
                        },
                        style = roudioText(13.sp, 18.sp),
                        color = c.onHeroCardMuted,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val battery = state.battery
            Tile(
                label = stringResource(R.string.tile_battery),
                value = battery?.let { stringResource(R.string.tile_percent, it.value.percent) } ?: stringResource(R.string.tile_none),
                detail = when {
                    battery == null -> stringResource(R.string.tile_not_read)
                    !connected -> stringResource(R.string.tile_at, clockTime(battery.at))
                    battery.value.charging == true -> stringResource(R.string.tile_charging)
                    battery.value.charging == false -> stringResource(R.string.tile_not_charging)
                    else -> ""
                },
            )
            val storage = state.storage
            Tile(
                label = stringResource(R.string.tile_free_space),
                value = storage?.let { stringResource(R.string.tile_gb, gigabytes(it.value.freeBytes, roundDown = true)) }
                    ?: stringResource(R.string.tile_none),
                detail = when {
                    storage == null -> stringResource(R.string.tile_not_read)
                    !connected -> stringResource(R.string.tile_at, clockTime(storage.at))
                    else -> stringResource(R.string.tile_of_gb, gigabytes(storage.value.totalBytes))
                },
            )
            when (val recording = state.recordingNow) {
                is RecordingNow.Recording -> Tile(
                    label = stringResource(R.string.tile_recording),
                    value = stringResource(R.string.tile_recording_now),
                    detail = recording.since?.let { stringResource(R.string.tile_since, clockTime(it)) }.orEmpty(),
                    dot = c.buoy,
                )
                RecordingNow.NotRecording -> Tile(
                    label = stringResource(R.string.tile_recording),
                    value = stringResource(R.string.tile_recording_off),
                    detail = stringResource(R.string.tile_not_recording),
                )
                RecordingNow.Unknown -> Tile(
                    label = stringResource(R.string.tile_recording),
                    value = stringResource(R.string.tile_unknown),
                    detail = stringResource(
                        when {
                            connected -> R.string.tile_checking
                            !state.bluetoothOn -> R.string.tile_bluetooth_off
                            else -> R.string.tile_out_of_range
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, detail: String, dot: Color? = null) {
    val c = RoudioTheme.colors
    Column(
        Modifier.weight(1f).background(c.heroTile, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = roudioText(12.sp, 16.sp), color = c.onHeroCardMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(value, style = roudioText(17.sp, 22.sp, FontWeight.SemiBold), color = c.onHeroCard, maxLines = 1)
        }
        Text(detail, style = roudioText(12.sp, 16.sp), color = c.onHeroCardMuted, maxLines = 1)
    }
}

/** 13a/13b "Transfers": what's coming across, what's waiting, and what reached the phone today. */
@Composable
private fun TransfersSection(
    state: RecorderState,
    now: Instant,
    recordings: List<Recording>,
    progress: Transfers.Progress?,
    stopped: Transfers.Progress?,
    stoppedRecording: Recording?,
    reconnect: @Composable () -> Unit,
) {
    val c = RoudioTheme.colors
    val stillOnRecorder = recordings.count { it.transfer == TransferState.OnRecorder || it.transfer == TransferState.Transferring }
    val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
    val doneToday = recordings.filter {
        it.transfer >= TransferState.OnPhone && it.startedAt.atZone(ZoneId.systemDefault()).toLocalDate() == today
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(R.string.transfers_title),
                style = roudioText(20.sp, 26.sp, FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                when {
                    !state.isConnected -> stringResource(R.string.transfers_waiting)
                    stillOnRecorder > 0 -> pluralStringResource(R.plurals.transfers_still_on_recorder, stillOnRecorder, stillOnRecorder)
                    else -> stringResource(R.string.transfers_all_across)
                },
                style = roudioText(13.sp, 18.sp),
                color = c.muted,
            )
        }
        val rows = mutableListOf<@Composable () -> Unit>()
        recordings.firstOrNull { it.id == progress?.recordingId }?.let { recording ->
            rows += { TransferringRow(recording, progress!!, now) }
        }
        if (stopped != null && stoppedRecording != null) {
            rows += { StoppedRow(recording = stoppedRecording, percent = stopped.percent, bluetoothOn = state.bluetoothOn, reconnect = reconnect) }
        }
        val recording = state.recording?.value
        when {
            state.recordingNow is RecordingNow.Recording -> rows += {
                val since = (state.recordingNow as RecordingNow.Recording).since
                SimpleRow(
                    stringResource(R.string.transfer_recording_now, since?.let(::hhmm).orEmpty()).trimStart(' ', '·'),
                    stringResource(R.string.transfer_recording_now_body),
                    bar = c.buoy,
                )
            }
            !state.isConnected && recording?.isRecording == true -> rows += {
                SimpleRow(
                    stringResource(R.string.transfer_maybe_recording, recording.startedAt?.let(::hhmm).orEmpty()).trimStart(' ', '·'),
                    stringResource(R.string.transfer_maybe_recording_body),
                    bar = c.buoy,
                )
            }
        }
        if (doneToday.isNotEmpty()) {
            rows += {
                SimpleRow(
                    pluralStringResource(R.plurals.transfer_done_today, doneToday.size, doneToday.size),
                    stringResource(
                        if (doneToday.all { it.transfer == TransferState.Uploaded }) R.string.transfer_done_uploaded else R.string.transfer_done_uploading,
                    ),
                    bar = c.ink,
                    bold = false,
                )
            }
        }
        rows.forEachIndexed { i, row ->
            row()
            if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        }
    }
}

@Composable
private fun TransferringRow(recording: Recording, progress: Transfers.Progress, now: Instant) {
    val elapsed = Duration.between(progress.startedAt, now).seconds
    // Only once there's enough to go on; early percentages include the set-up time.
    val minutesLeft = if (progress.percent >= 5 && elapsed > 0) {
        ((elapsed * (100 - progress.percent) / progress.percent) / 60 + 1).toInt()
    } else {
        null
    }
    Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Bar(RoudioTheme.colors.buoy)
            RowLines(
                transferTitle(recording),
                minutesLeft?.let { stringResource(R.string.transfer_bluetooth_left, it) } ?: stringResource(R.string.transfer_bluetooth),
                Modifier.weight(1f),
            )
            Percent(progress.percent)
        }
        ProgressBar(progress.percent, RoudioTheme.colors.ink)
    }
}

@Composable
private fun StoppedRow(recording: Recording, percent: Int, bluetoothOn: Boolean, reconnect: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Bar(RoudioTheme.colors.buoy)
            RowLines(
                transferTitle(recording),
                stringResource(if (bluetoothOn) R.string.transfer_stopped_range else R.string.transfer_stopped_bluetooth, percent),
                Modifier.weight(1f),
            )
            Percent(percent)
        }
        ProgressBar(percent, RoudioTheme.colors.muted.copy(alpha = 0.6f))
        SafeNote(stringResource(R.string.transfer_stopped_safe), Modifier.padding(start = 16.dp)) { reconnect() }
    }
}

@Composable
private fun SimpleRow(title: String, body: String, bar: Color, bold: Boolean = true) {
    Row(
        Modifier.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Bar(bar)
        RowLines(title, body, Modifier.weight(1f), bold)
    }
}

@Composable
private fun Bar(colour: Color) = Box(Modifier.width(4.dp).height(36.dp).background(colour, RoundedCornerShape(2.dp)))

@Composable
private fun RowLines(title: String, body: String, modifier: Modifier, bold: Boolean = true) {
    val c = RoudioTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = roudioText(15.sp, 20.sp, if (bold) FontWeight.SemiBold else FontWeight.Medium), color = c.ink)
        Text(body, style = roudioText(13.sp, 18.sp), color = c.muted)
    }
}

@Composable
private fun Percent(percent: Int) = Text(
    stringResource(R.string.transfer_percent, percent),
    style = roudioText(14.sp, 18.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
    color = RoudioTheme.colors.ink,
)

@Composable
private fun ProgressBar(percent: Int, fill: Color) {
    val c = RoudioTheme.colors
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(6.dp).background(c.hairline, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(percent / 100f).height(6.dp).background(fill, RoundedCornerShape(3.dp)))
    }
}

/** "13:05 · 1h 05m": the recording's local start and its length (estimated until measured). */
@Composable
private fun transferTitle(recording: Recording): String {
    val start = recording.startedAt.atOffset(ZoneOffset.ofTotalSeconds(recording.zoneOffsetSeconds)).toLocalTime()
    val length = recording.durationMs?.let(Duration::ofMillis) ?: Duration.ofSeconds(recording.sizeOnRecorder * 8 / 33_000)
    return stringResource(R.string.transfer_title, "%02d:%02d".format(start.hour, start.minute), durationText(length))
}

private fun hhmm(at: Instant): String {
    val time = at.atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

/** "Just now" means within the last minute. */
fun isRecent(at: Instant?, now: Instant): Boolean = at != null && Duration.between(at, now) < Duration.ofMinutes(1)

/** The current time, ticking every 15 s, for "seen just now" wording. */
@Composable
fun rememberNow(): Instant {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(15_000)
            value = Instant.now()
        }
    }
    return now
}

private val previewNow = Instant.parse("2026-10-10T13:32:00Z")
private val previewConnected = RecorderState(
    linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro),
    connectedSince = previewNow.minusSeconds(600),
    lastSeen = previewNow,
    battery = Seen(BatteryReading(64, false), previewNow),
    storage = Seen(StorageReading(64_000_000_000, 58_400_000_000), previewNow),
    recording = Seen(RecordingReading(true, previewNow.minusSeconds(1320)), previewNow),
)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun RecorderConnectedPreview() {
    RoudioTheme(darkTheme = false) { RecorderScreen(previewConnected, previewNow, emptyList(), null, null, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun RecorderConnectedDarkPreview() {
    RoudioTheme(darkTheme = true) { RecorderScreen(previewConnected, previewNow, emptyList(), null, null, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun RecorderAwayPreview() {
    val away = previewConnected.copy(connectedSince = null, lastSeen = previewNow.minusSeconds(3060))
    RoudioTheme(darkTheme = false) { RecorderScreen(away, previewNow, emptyList(), null, null, {}, {}, {}, {}) }
}
