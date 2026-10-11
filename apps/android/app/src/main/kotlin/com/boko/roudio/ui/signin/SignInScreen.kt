package com.boko.roudio.ui.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.setup.StepHeader
import com.boko.roudio.ui.theme.RoudioRadius
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

sealed interface SignInError {
    data class NotOwner(val email: String?) : SignInError
    data class Other(val message: String) : SignInError
}

/** 11b Setup · Sign in (Paper page "04 Setup…"). */
@Composable
fun SignInScreen(
    busy: Boolean,
    error: SignInError?,
    onContinue: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            StepHeader(step = 1, onBack = onBack)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.sign_in_title),
                    style = roudioText(30.sp, 36.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                    color = c.ink,
                )
                Text(
                    stringResource(R.string.sign_in_body),
                    style = roudioText(16.sp, 24.sp),
                    color = c.muted,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard(RoudioIcons.Shield, R.string.sign_in_private_title, R.string.sign_in_private_body)
                InfoCard(RoudioIcons.Cloud, R.string.sign_in_drive_title, R.string.sign_in_drive_body)
            }
            if (error != null) ErrorCallout(error)
        }
        Spacer(Modifier.height(12.dp))
        ContinueWithGoogle(busy = busy, onClick = onContinue)
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: Int, body: Int) {
    val c = RoudioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.raised, RoundedCornerShape(RoudioRadius.Chip))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = roudioText(15.sp, 21.sp, FontWeight.SemiBold), color = c.ink)
            Text(stringResource(body), style = roudioText(14.sp, 20.sp), color = c.muted)
        }
    }
}

@Composable
private fun ErrorCallout(error: SignInError) {
    val c = RoudioTheme.colors
    val (title, body) = when (error) {
        is SignInError.NotOwner -> stringResource(R.string.not_owner_title) to
            stringResource(R.string.not_owner_body, error.email ?: stringResource(R.string.that_account))
        is SignInError.Other -> stringResource(R.string.sign_in_failed_title) to error.message
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.alertTint, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(RoudioIcons.Alert, contentDescription = null, tint = c.alert, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = roudioText(14.sp, 20.sp, FontWeight.SemiBold), color = c.alert)
            Text(body, style = roudioText(13.sp, 19.sp), color = c.ink)
        }
    }
}

@Composable
private fun ContinueWithGoogle(busy: Boolean, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary,
            contentColor = c.onPrimary,
            disabledContainerColor = c.primary.copy(alpha = 0.7f),
            disabledContentColor = c.onPrimary,
        ),
    ) {
        Box(
            Modifier.size(28.dp).background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RoudioIcons.Google, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.size(12.dp))
        Text(
            stringResource(if (busy) R.string.signing_in else R.string.continue_with_google),
            style = roudioText(16.sp, 20.sp, FontWeight.SemiBold),
        )
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SignInPreview() {
    RoudioTheme(darkTheme = false) { SignInScreen(busy = false, error = null, onContinue = {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SignInNotOwnerDarkPreview() {
    RoudioTheme(darkTheme = true) {
        SignInScreen(busy = false, error = SignInError.NotOwner("studio@example.com"), onContinue = {})
    }
}
