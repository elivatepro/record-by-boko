package com.boko.roudio.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boko.roudio.R
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.RoudioTypeScale
import com.boko.roudio.ui.theme.roudioText

/**
 * Temporary landing screen for milestone 0: proves sign-in, the session and the Plaud token
 * exchange work on a real phone. Setup continues with 11c in milestone 1.
 */
@Composable
fun SignedInScreen(
    email: String?,
    driveConnected: Boolean?,
    plaudStatus: String?,
    onCheckPlaud: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = RoudioTheme.colors
    val body = roudioText(RoudioTypeScale.BodySize, RoudioTypeScale.BodyLineHeight)
    Column(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.signed_in),
            style = roudioText(RoudioTypeScale.TitleSize, RoudioTypeScale.TitleLineHeight, FontWeight.SemiBold),
            color = c.ink,
        )
        Text(email ?: "…", style = body, color = c.muted)
        when (driveConnected) {
            true -> Text(stringResource(R.string.drive_connected), style = body, color = c.ink)
            false -> Text(stringResource(R.string.drive_not_connected), style = body, color = c.buoyText)
            null -> Unit
        }
        OutlinedButton(onClick = onCheckPlaud) { Text(stringResource(R.string.check_plaud), style = body) }
        plaudStatus?.let { Text(it, style = body, color = c.muted) }
        OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out), style = body) }
    }
}
