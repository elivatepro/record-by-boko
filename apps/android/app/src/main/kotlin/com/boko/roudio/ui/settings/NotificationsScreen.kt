package com.boko.roudio.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.nudge.NudgeSettings
import com.boko.roudio.nudge.WakingHours
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 15d Settings → Notifications: waking hours and the nudges. Calls join this screen in M8.6. */
@Composable
fun NotificationsRoute(app: RoudioApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by app.nudgeSettings.settings.collectAsState()
    fun calendarGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var calendarAllowed by remember { mutableStateOf(calendarGranted()) }
    var askedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        calendarAllowed = calendarGranted()
        onPauseOrDispose {}
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        calendarAllowed = granted
        askedOnce = true
        if (granted) app.nudgeSettings.update { it.copy(meetings = true) }
    }
    NotificationsScreen(
        settings = settings,
        calendarAllowed = calendarAllowed,
        onAllowCalendar = {
            // Refused for good: Android won't ask again, so open Roudio's app settings instead.
            val activity = context as? android.app.Activity
            val canAsk = activity == null || !askedOnce ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
            if (canAsk) {
                ask.launch(Manifest.permission.READ_CALENDAR)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        onChange = app.nudgeSettings::update,
        onBack = onBack,
    )
}

@Composable
fun NotificationsScreen(
    settings: NudgeSettings,
    calendarAllowed: Boolean,
    onAllowCalendar: () -> Unit,
    onChange: ((NudgeSettings) -> NudgeSettings) -> Unit,
    onBack: () -> Unit,
) {
    val c = RoudioTheme.colors
    var editingWaking by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackTitleHeader(stringResource(R.string.notifications_title), onBack)
        Text(stringResource(R.string.notifications_intro), style = roudioText(16.sp, 24.sp), color = c.muted)

        Column {
            SectionLabel(stringResource(R.string.notifications_when), Modifier.padding(bottom = 4.dp))
            NavRow(
                stringResource(R.string.notifications_waking),
                stringResource(R.string.notifications_waking_body, clock(settings.waking.from), clock(settings.waking.until)),
                { editingWaking = true },
                last = true,
            )
        }

        Column {
            SectionLabel(stringResource(R.string.notifications_nudge_when), Modifier.padding(bottom = 4.dp))
            if (calendarAllowed) {
                SwitchRow(
                    stringResource(R.string.notifications_meeting),
                    stringResource(R.string.notifications_meeting_body),
                    settings.meetings,
                    enabled = true,
                ) { on -> onChange { it.copy(meetings = on) } }
            } else {
                AllowRow(stringResource(R.string.notifications_meeting), stringResource(R.string.notifications_meeting_body), onAllowCalendar)
            }
            Divider()
            SwitchRow(
                stringResource(R.string.notifications_idle),
                stringResource(R.string.notifications_idle_body),
                settings.idle,
                enabled = true,
            ) { on -> onChange { it.copy(idle = on) } }
            if (settings.idle) {
                val choices = NudgeSettings.IDLE_CHOICES
                Segmented(
                    segments = choices.map { Segment(idleLabel(it)) },
                    selected = choices.indexOf(settings.idleMinutes).coerceAtLeast(0),
                    onSelect = { i -> onChange { it.copy(idleMinutes = choices[i]) } },
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            Divider()
            SwitchRow(
                stringResource(R.string.notifications_battery),
                stringResource(R.string.notifications_battery_body),
                settings.battery,
                enabled = true,
            ) { on -> onChange { it.copy(battery = on) } }
            Divider()
            SwitchRow(
                stringResource(R.string.notifications_storage),
                stringResource(R.string.notifications_storage_body),
                settings.storage,
                enabled = true,
            ) { on -> onChange { it.copy(storage = on) } }
        }

        InfoNote(stringResource(R.string.notifications_microphone))
    }

    if (editingWaking) {
        WakingHoursDialog(settings.waking, onDismiss = { editingWaking = false }) { hours ->
            onChange { it.copy(waking = hours) }
            editingWaking = false
        }
    }
}

/** A feature that needs a permission first: the 11c "Allow" pill in place of the switch. */
@Composable
private fun AllowRow(title: String, body: String, onAllow: () -> Unit) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = roudioText(17.sp, 22.sp, FontWeight.SemiBold), color = c.ink)
            Text(body, style = roudioText(14.sp, 20.sp), color = c.muted)
        }
        Box(
            Modifier
                .height(48.dp)
                .clickable(role = Role.Button, onClick = onAllow),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.background(c.primary, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.allow), style = roudioText(14.sp, 18.sp, FontWeight.SemiBold), color = c.onPrimary)
            }
        }
    }
}

/** Waking hours: pick when they start, then when they end. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakingHoursDialog(current: WakingHours, onDismiss: () -> Unit, onSave: (WakingHours) -> Unit) {
    val c = RoudioTheme.colors
    val context = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    var step by remember { mutableStateOf(0) }
    var from by remember { mutableStateOf(current.from) }
    val fromState = rememberTimePickerState(current.from.hour, current.from.minute, is24)
    val untilState = rememberTimePickerState(current.until.hour, current.until.minute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.sheet,
        title = {
            Text(
                stringResource(if (step == 0) R.string.notifications_waking_from else R.string.notifications_waking_until),
                style = roudioText(20.sp, 26.sp, FontWeight.SemiBold),
                color = c.ink,
            )
        },
        text = {
            val colours = TimePickerDefaults.colors(
                clockDialColor = c.raised,
                selectorColor = c.primary,
                timeSelectorSelectedContainerColor = c.primary,
                timeSelectorSelectedContentColor = c.onPrimary,
                timeSelectorUnselectedContainerColor = c.raised,
                timeSelectorUnselectedContentColor = c.ink,
                periodSelectorSelectedContainerColor = c.primary,
                periodSelectorSelectedContentColor = c.onPrimary,
                periodSelectorUnselectedContentColor = c.ink,
            )
            if (step == 0) TimePicker(fromState, colors = colours) else TimePicker(untilState, colors = colours)
        },
        confirmButton = {
            TextButton(onClick = {
                if (step == 0) {
                    from = LocalTime.of(fromState.hour, fromState.minute)
                    step = 1
                } else {
                    val until = LocalTime.of(untilState.hour, untilState.minute)
                    // Equal times would leave no waking hours at all; keep the old window then.
                    if (until != from) onSave(WakingHours(from, until)) else onDismiss()
                }
            }) {
                Text(stringResource(if (step == 0) R.string.next else R.string.save), color = c.ink, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = c.ink) }
        },
    )
}

@Composable
private fun idleLabel(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.notifications_idle_hours, minutes / 60) else stringResource(R.string.notifications_idle_minutes, minutes)

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun clock(time: LocalTime): String = CLOCK.format(time)

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun NotificationsPreview() {
    RoudioTheme(darkTheme = false) { NotificationsScreen(NudgeSettings(), calendarAllowed = false, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun NotificationsDarkPreview() {
    RoudioTheme(darkTheme = true) { NotificationsScreen(NudgeSettings(), calendarAllowed = true, {}, {}, {}) }
}
