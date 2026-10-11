package com.boko.roudio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/**
 * 15c Languages you speak. The server has no language setting yet: Scribe v2 detects the
 * language of every recording by itself, so this screen says so instead of offering choices
 * that wouldn't change anything.
 */
@Composable
fun LanguagesScreen(onBack: () -> Unit) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackTitleHeader(stringResource(R.string.settings_languages), onBack)
        Text(stringResource(R.string.languages_intro), style = roudioText(16.sp, 24.sp), color = c.muted)
        Column {
            SectionLabel(stringResource(R.string.languages_mostly), Modifier.padding(bottom = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.RadioButton
                        selected = true
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp).border(2.dp, c.ink, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).background(c.ink, CircleShape))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.languages_automatic), style = roudioText(17.sp, 22.sp, FontWeight.SemiBold), color = c.ink)
                    Text(stringResource(R.string.languages_automatic_body), style = roudioText(14.sp, 20.sp), color = c.muted)
                }
            }
            Divider()
        }
        InfoNote(stringResource(R.string.languages_not_yet))
        InfoNote(stringResource(R.string.languages_accuracy))
    }
}

/** Fog callout with an info mark (15c). */
@Composable
private fun InfoNote(text: String) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(OrganiseIcons.Info, contentDescription = null, tint = c.ink, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Text(text, style = roudioText(15.sp, 21.sp), color = c.ink)
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LanguagesPreview() {
    RoudioTheme(darkTheme = false) { LanguagesScreen {} }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LanguagesDarkPreview() {
    RoudioTheme(darkTheme = true) { LanguagesScreen {} }
}
