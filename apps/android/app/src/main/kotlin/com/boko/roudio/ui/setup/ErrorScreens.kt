package com.boko.roudio.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.recorder.LinkFailure
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** 12b Error · Bluetooth off. */
@Composable
fun BluetoothOffScreen(onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.turn_on_bluetooth), onTurnOn)
                QuietButton(stringResource(R.string.not_now), onNotNow)
            }
        },
        gap = 24.dp,
    ) {
        BackHeader(onNotNow)
        ErrorIcon(RoudioIcons.BluetoothOff)
        ErrorTitle(stringResource(R.string.bluetooth_off_title), stringResource(R.string.bluetooth_off_body))
        SafeNote(stringResource(R.string.bluetooth_off_safe))
    }
}

/** 12c Error · Nearby devices refused for good. */
@Composable
fun PermissionRefusedScreen(onOpenSettings: () -> Unit, onWhy: () -> Unit, onBack: () -> Unit) {
    val c = RoudioTheme.colors
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.open_app_settings), onOpenSettings)
                QuietButton(stringResource(R.string.why_needed), onWhy)
            }
        },
        gap = 24.dp,
    ) {
        BackHeader(onBack)
        ErrorIcon(RoudioIcons.Lock)
        ErrorTitle(stringResource(R.string.refused_title), stringResource(R.string.refused_body))
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.raised, RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.refused_path_label), style = roudioText(13.sp, 18.sp, FontWeight.SemiBold), color = c.muted)
            Text(stringResource(R.string.refused_path), style = roudioText(16.sp, 24.sp, FontWeight.Medium), color = c.ink)
        }
    }
}

/** 12d Error · Linked to another app. [model] as on 11h ("Note Pro"). */
@Composable
fun LinkedElsewhereScreen(model: String, onTryAgain: () -> Unit, onOpenPlaud: () -> Unit, onBack: () -> Unit) {
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.elsewhere_try_again), onTryAgain)
                QuietButton(stringResource(R.string.open_plaud_app), onOpenPlaud)
            }
        },
        gap = 24.dp,
    ) {
        BackHeader(onBack)
        ErrorIcon(RoudioIcons.Link)
        ErrorTitle(stringResource(R.string.elsewhere_title), stringResource(R.string.elsewhere_body, model))
        Column {
            NumberedStep(1, stringResource(R.string.elsewhere_step_1))
            NumberedStep(2, stringResource(R.string.elsewhere_step_2, model))
            NumberedStep(3, stringResource(R.string.elsewhere_step_3))
        }
    }
}

/** 12e Error · Linking failed, worded for why it failed. */
@Composable
fun LinkFailedScreen(reason: LinkFailure, onTryAgain: () -> Unit, onBack: () -> Unit) {
    val c = RoudioTheme.colors
    var details by rememberSaveable { mutableStateOf(false) }
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.try_again), onTryAgain)
                QuietButton(stringResource(if (details) R.string.hide_details else R.string.show_details)) { details = !details }
            }
        },
        gap = 24.dp,
    ) {
        BackHeader(onBack)
        ErrorIcon(RoudioIcons.Alert, alert = true)
        ErrorTitle(
            stringResource(R.string.link_failed_title),
            stringResource(
                when (reason) {
                    LinkFailure.NoInternet -> R.string.link_failed_body_internet
                    LinkFailure.Other -> R.string.link_failed_body_other
                    else -> R.string.link_failed_body_dropped
                },
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SafeNote(stringResource(R.string.link_failed_safe))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.link_failed_usually), style = roudioText(13.sp, 18.sp, FontWeight.SemiBold), color = c.muted)
                Text(stringResource(R.string.link_failed_cause_internet), style = roudioText(15.sp, 22.sp), color = c.ink)
                Text(stringResource(R.string.link_failed_cause_range), style = roudioText(15.sp, 22.sp), color = c.ink)
            }
            if (details) {
                Text(
                    stringResource(
                        when (reason) {
                            LinkFailure.NoInternet -> R.string.link_failed_detail_internet
                            LinkFailure.Other -> R.string.link_failed_detail_other
                            else -> R.string.link_failed_detail_dropped
                        },
                    ),
                    style = roudioText(14.sp, 20.sp),
                    color = c.muted,
                )
            }
        }
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun BluetoothOffPreview() {
    RoudioTheme(darkTheme = false) { BluetoothOffScreen({}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PermissionRefusedPreview() {
    RoudioTheme(darkTheme = false) { PermissionRefusedScreen({}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LinkedElsewherePreview() {
    RoudioTheme(darkTheme = false) { LinkedElsewhereScreen("Note Pro", {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LinkFailedPreview() {
    RoudioTheme(darkTheme = false) { LinkFailedScreen(LinkFailure.Dropped, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LinkFailedDarkPreview() {
    RoudioTheme(darkTheme = true) { LinkFailedScreen(LinkFailure.NoInternet, {}, {}) }
}
