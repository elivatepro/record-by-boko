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
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.setup.SafeNote
import com.boko.roudio.ui.setup.SmallButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * 13a (connected) and 13b (not connected), as far as milestone 1 goes: the recorder card, what's
 * safe while it's away, and unlinking. Transfers and the options arrive with milestone 2.
 */
@Composable
fun RecorderScreen(
    state: RecorderState,
    now: Instant,
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
        if (!state.isConnected) {
            SafeNote(
                stringResource(if (state.bluetoothOn) R.string.recorder_away_safe else R.string.recorder_bluetooth_safe),
            ) {
                SmallButton(
                    stringResource(if (state.bluetoothOn) R.string.try_to_reconnect else R.string.turn_on_bluetooth),
                    if (state.bluetoothOn) onReconnect else onTurnOnBluetooth,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                    height = 44.dp,
                )
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
    RoudioTheme(darkTheme = false) { RecorderScreen(previewConnected, previewNow, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun RecorderConnectedDarkPreview() {
    RoudioTheme(darkTheme = true) { RecorderScreen(previewConnected, previewNow, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun RecorderAwayPreview() {
    val away = previewConnected.copy(connectedSince = null, lastSeen = previewNow.minusSeconds(3060))
    RoudioTheme(darkTheme = false) { RecorderScreen(away, previewNow, {}, {}, {}, {}) }
}
