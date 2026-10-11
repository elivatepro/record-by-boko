package com.boko.roudio.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** 11a Setup · Welcome. */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val c = RoudioTheme.colors
    SetupScaffold(
        bottom = {
            Footnote(stringResource(R.string.welcome_footnote))
            BigButton(stringResource(R.string.get_started), onGetStarted)
        },
    ) {
        Row(
            Modifier.height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoudioMark()
            Text(
                "roudio",
                style = roudioText(20.sp, 24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em),
                color = c.ink,
            )
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.welcome_title),
                style = roudioText(34.sp, 40.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.welcome_body), style = roudioText(16.sp, 24.sp), color = c.muted)
        }
        Column {
            Feature(RoudioIcons.Mic, R.string.welcome_pulls_title, R.string.welcome_pulls_body, last = false)
            Feature(RoudioIcons.Notes, R.string.welcome_writes_title, R.string.welcome_writes_body, last = false)
            Feature(RoudioIcons.CloudCheck, R.string.welcome_backs_title, R.string.welcome_backs_body, last = true)
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, body: Int, last: Boolean) {
    ListRow(last = last, verticalPadding = 14.dp) {
        IconTile(icon)
        RowText(stringResource(title), stringResource(body), Modifier.weight(1f))
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun WelcomePreview() {
    RoudioTheme(darkTheme = false) { WelcomeScreen(onGetStarted = {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun WelcomeDarkPreview() {
    RoudioTheme(darkTheme = true) { WelcomeScreen(onGetStarted = {}) }
}
