package com.boko.roudio.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** Each permission row: allowed, or a button to ask. Optional ones get the quieter button. */
data class PermissionRows(
    val nearbyDevices: Boolean,
    val notifications: Boolean,
    val nearbyWifi: Boolean,
)

/** 11c Setup · Permissions. Continue asks for Nearby devices first if it isn't allowed yet. */
@Composable
fun PermissionsScreen(
    allowed: PermissionRows,
    onAllowNearbyDevices: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowNearbyWifi: () -> Unit,
    onContinue: () -> Unit,
    onBack: (() -> Unit)?,
) {
    SetupScaffold(
        bottom = {
            Footnote(stringResource(R.string.permissions_footnote))
            BigButton(stringResource(R.string.continue_label), onContinue)
        },
    ) {
        StepHeader(step = 2, onBack = onBack)
        SetupTitle(stringResource(R.string.permissions_title), stringResource(R.string.permissions_body))
        Column {
            Permission(
                RoudioIcons.Bluetooth, R.string.permission_nearby_title, R.string.permission_nearby_body,
                allowed = allowed.nearbyDevices, required = true, onAllow = onAllowNearbyDevices, last = false,
            )
            Permission(
                RoudioIcons.Bell, R.string.permission_notifications_title, R.string.permission_notifications_body,
                allowed = allowed.notifications, required = true, onAllow = onAllowNotifications, last = false,
            )
            Permission(
                RoudioIcons.Wifi, R.string.permission_wifi_title, R.string.permission_wifi_body,
                allowed = allowed.nearbyWifi, required = false, onAllow = onAllowNearbyWifi, last = true,
            )
        }
        Footnote(stringResource(R.string.permissions_no_microphone), center = false)
    }
}

@Composable
private fun Permission(
    icon: ImageVector,
    title: Int,
    body: Int,
    allowed: Boolean,
    required: Boolean,
    onAllow: () -> Unit,
    last: Boolean,
) {
    ListRow(last = last, verticalPadding = 16.dp, centered = true) {
        IconTile(icon)
        RowText(stringResource(title), stringResource(body), Modifier.weight(1f))
        if (allowed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CheckBadge(18.dp)
                Text(
                    stringResource(R.string.allowed),
                    style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
                    color = RoudioTheme.colors.ink,
                )
            }
        } else {
            SmallButton(stringResource(R.string.allow), onAllow, filled = required)
        }
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PermissionsPreview() {
    RoudioTheme(darkTheme = false) {
        PermissionsScreen(PermissionRows(true, false, false), {}, {}, {}, {}, onBack = {})
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PermissionsDarkPreview() {
    RoudioTheme(darkTheme = true) {
        PermissionsScreen(PermissionRows(true, false, false), {}, {}, {}, {}, onBack = {})
    }
}
