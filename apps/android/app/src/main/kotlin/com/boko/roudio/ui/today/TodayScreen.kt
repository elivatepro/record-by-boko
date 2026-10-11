package com.boko.roudio.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.recorder.BatteryReading
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.Seen
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.recorder.clockTime
import com.boko.roudio.ui.setup.RoudioMark
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 01 Today, milestone 1 shell: header, date and the recorder chip. The dial, digest and timeline
 * come with milestone 2; the bell, day buttons and tabs come with the screens they open.
 */
@Composable
fun TodayScreen(state: RecorderState, today: LocalDate, onRecorder: () -> Unit) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoudioMark()
            Text(
                "roudio",
                style = roudioText(20.sp, 24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em),
                color = c.ink,
            )
        }
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp)) {
            Text(stringResource(R.string.today), style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = c.muted)
            Text(
                DAY.format(today),
                style = roudioText(26.sp, 32.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 28.dp), contentAlignment = Alignment.Center) {
            RecorderChip(state, onRecorder)
        }
        Text(
            stringResource(R.string.today_empty),
            style = roudioText(15.sp, 22.sp),
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        )
    }
}

/**
 * The recorder chip (01, S1). Fog when connected; a buoy rim when not, with when it was last seen.
 * Battery only shows once the recorder has reported it on this connection.
 */
@Composable
fun RecorderChip(state: RecorderState, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val connected = state.isConnected
    val freshBattery = state.battery?.takeIf { b -> state.connectedSince?.let { !b.at.isBefore(it) } == true }
    val seen = state.lastSeen?.let(::clockTime)
    val text = when {
        connected && freshBattery != null -> stringResource(R.string.chip_linked_battery, freshBattery.value.percent)
        connected -> stringResource(R.string.chip_linked)
        !state.bluetoothOn -> stringResource(R.string.chip_bluetooth_off)
        seen != null -> stringResource(R.string.chip_not_connected_seen, seen)
        else -> stringResource(R.string.chip_not_connected)
    }
    Row(
        Modifier
            .height(36.dp)
            .then(if (connected) Modifier.background(c.raised, shape) else Modifier.background(c.ground, shape).border(1.5.dp, c.buoy, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RoudioIcons.RecorderChip, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
        Text(text, style = roudioText(13.sp, 16.sp, FontWeight.Medium), color = c.ink)
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMM")

private val previewNow = Instant.parse("2026-10-10T13:32:00Z")
private val previewLinked = RecorderState(
    linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro),
    connectedSince = previewNow.minusSeconds(60),
    lastSeen = previewNow,
    battery = Seen(BatteryReading(64, false), previewNow),
)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun TodayPreview() {
    RoudioTheme(darkTheme = false) { TodayScreen(previewLinked, LocalDate.of(2026, 10, 10)) {} }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun TodayAwayPreview() {
    val away = previewLinked.copy(connectedSince = null, lastSeen = previewNow.minusSeconds(3060))
    RoudioTheme(darkTheme = true) { TodayScreen(away, LocalDate.now(ZoneId.of("UTC"))) {} }
}
