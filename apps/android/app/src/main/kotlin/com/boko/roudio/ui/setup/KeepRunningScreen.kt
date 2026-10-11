package com.boko.roudio.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.boko.roudio.R
import com.boko.roudio.system.PhoneSettings.Autostart
import com.boko.roudio.ui.theme.RoudioTheme

/**
 * What 11d knows about the phone. [autostart] is null on phones without Xiaomi's Autostart.
 * [autostartConfirmed]: Android wouldn't say, so the owner opened the setting and came back.
 */
data class KeepRunningState(
    val autostart: Autostart?,
    val autostartConfirmed: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val autostartDone: Boolean
        get() = autostart == null || autostart == Autostart.On || (autostart == Autostart.Unknown && autostartConfirmed)

    val allDone: Boolean get() = autostartDone && batteryUnrestricted
}

/** 11d Setup · Keep running. The first item still to do is outlined and has its button. */
@Composable
fun KeepRunningScreen(
    state: KeepRunningState,
    onOpenAutostart: () -> Unit,
    onOpenBattery: () -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
) {
    SetupScaffold(
        bottom = {
            if (!state.allDone) Footnote(stringResource(R.string.keep_running_footnote))
            if (state.allDone) {
                BigButton(stringResource(R.string.continue_label), onContinue)
            } else {
                BigButton(stringResource(R.string.continue_without_it), onContinue, filled = false)
            }
        },
    ) {
        StepHeader(step = 3, onBack = onBack)
        SetupTitle(stringResource(R.string.keep_running_title), stringResource(R.string.keep_running_body))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val autostartIsNext = state.autostart != null && !state.autostartDone
            val batteryIsNext = !autostartIsNext && !state.batteryUnrestricted
            if (state.autostart != null) {
                Item(
                    done = state.autostartDone,
                    current = autostartIsNext,
                    title = stringResource(
                        when {
                            state.autostart == Autostart.On -> R.string.autostart_on
                            state.autostartDone -> R.string.autostart_checked
                            else -> R.string.autostart_title
                        },
                    ),
                    body = stringResource(
                        when {
                            state.autostartDone -> R.string.autostart_done_body
                            state.autostart == Autostart.Unknown -> R.string.autostart_unknown_body
                            else -> R.string.autostart_path
                        },
                    ),
                    button = stringResource(R.string.open_autostart_settings),
                    onButton = onOpenAutostart,
                )
            }
            Item(
                done = state.batteryUnrestricted,
                current = batteryIsNext,
                title = stringResource(R.string.battery_title),
                body = stringResource(if (state.batteryUnrestricted) R.string.battery_done_body else R.string.battery_path),
                button = stringResource(R.string.open_battery_settings),
                onButton = onOpenBattery,
            )
            StepCard(current = false) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TodoRing(dashed = true)
                    RowText(
                        stringResource(R.string.lock_recents_title),
                        stringResource(R.string.lock_recents_body),
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Item(
    done: Boolean,
    current: Boolean,
    title: String,
    body: String,
    button: String,
    onButton: () -> Unit,
) {
    StepCard(current = current) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (done) CheckBadge(24.dp) else TodoRing(dashed = false)
            RowText(title, body, Modifier.weight(1f))
        }
        if (!done && current) SmallButton(button, onButton, filled = true, modifier = Modifier.fillMaxWidth(), height = 44.dp)
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun KeepRunningPreview() {
    RoudioTheme(darkTheme = false) {
        KeepRunningScreen(KeepRunningState(Autostart.On, false, false), {}, {}, {}, onBack = {})
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun KeepRunningUnknownDarkPreview() {
    RoudioTheme(darkTheme = true) {
        KeepRunningScreen(KeepRunningState(Autostart.Unknown, false, true), {}, {}, {}, onBack = {})
    }
}
