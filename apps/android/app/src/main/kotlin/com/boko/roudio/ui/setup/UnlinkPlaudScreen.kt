package com.boko.roudio.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.boko.roudio.R
import com.boko.roudio.ui.theme.RoudioTheme

/** 11e Setup · Free it from the Plaud app. */
@Composable
fun UnlinkPlaudScreen(onUnlinked: () -> Unit, onNeverLinked: () -> Unit, onBack: (() -> Unit)?) {
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.unlink_done), onUnlinked)
                QuietButton(stringResource(R.string.unlink_never), onNeverLinked)
            }
        },
    ) {
        StepHeader(step = 4, onBack = onBack)
        SetupTitle(stringResource(R.string.unlink_title), stringResource(R.string.unlink_body))
        WarningCallout(stringResource(R.string.unlink_warning_title), stringResource(R.string.unlink_warning_body))
        Column {
            NumberedStep(1, stringResource(R.string.unlink_step_1))
            NumberedStep(2, stringResource(R.string.unlink_step_2))
            NumberedStep(3, stringResource(R.string.unlink_step_3))
        }
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun UnlinkPlaudPreview() {
    RoudioTheme(darkTheme = false) { UnlinkPlaudScreen({}, {}, onBack = {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun UnlinkPlaudDarkPreview() {
    RoudioTheme(darkTheme = true) { UnlinkPlaudScreen({}, {}, onBack = {}) }
}
