package com.boko.roudio.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.api.models.BackupsView
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.serialEnding
import com.boko.roudio.system.PhoneSettings
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.nav.TAB_BAR_SPACE
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** 15 Settings. */
@Composable
fun SettingsRoute(
    app: RoudioApp,
    onRecorder: () -> Unit,
    onBackups: () -> Unit,
    onWordList: () -> Unit,
    onLanguages: () -> Unit,
    onSignOut: () -> Unit,
) {
    val context = LocalContext.current
    val recorder by app.recorder.state.collectAsState()
    var words by remember { mutableStateOf<WordLists?>(null) }
    var backups by remember { mutableStateOf<BackupsView?>(null) }
    fun readWarning() = SettingsModel.backgroundWarning(PhoneSettings.batteryUnrestricted(context), PhoneSettings.autostart(context))
    var warning by remember { mutableStateOf(readWarning()) }
    LifecycleResumeEffect(Unit) {
        warning = readWarning()
        onPauseOrDispose {}
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { warning = readWarning() }
    LaunchedEffect(Unit) {
        fetch { app.api.organise.listWords() }?.let { words = WordListModel.partition(it) }
        backups = fetch { app.api.backups.getBackups() }
    }
    SettingsScreen(
        recorder = recorder,
        words = words,
        backups = backups,
        warning = warning,
        onFix = {
            when (warning) {
                BackgroundWarning.Battery -> {
                    val ask = PhoneSettings.requestBatteryUnrestricted(context)
                    battery.launch(if (ask.resolveActivity(context.packageManager) != null) ask else PhoneSettings.appDetails(context))
                }
                BackgroundWarning.Autostart -> context.startActivity(PhoneSettings.autostartSettings(context))
                null -> Unit
            }
        },
        onRecorder = onRecorder,
        onBackups = onBackups,
        onWordList = onWordList,
        onLanguages = onLanguages,
        onSignOut = onSignOut,
    )
}

/**
 * Settings: a warning when a background exemption was switched off, then writing up, the
 * recorder and backups, and the account. Only real settings are shown: the theme follows the
 * phone, and the services are fixed on the server.
 */
@Composable
fun SettingsScreen(
    recorder: RecorderState,
    words: WordLists?,
    backups: BackupsView?,
    warning: BackgroundWarning?,
    onFix: () -> Unit,
    onRecorder: () -> Unit,
    onBackups: () -> Unit,
    onWordList: () -> Unit,
    onLanguages: () -> Unit,
    onSignOut: () -> Unit,
) {
    val c = RoudioTheme.colors
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = TAB_BAR_SPACE),
    ) {
        BrandHeader()
        Column(Modifier.padding(horizontal = 20.dp)) {
            ScreenTitle(stringResource(R.string.tab_settings), Modifier.padding(top = 12.dp, bottom = 20.dp))
            if (warning != null) BackgroundWarningCard(warning, onFix)

            Group(stringResource(R.string.settings_look)) {
                NavRow(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_body), null, last = true)
            }
            Group(stringResource(R.string.settings_writing_up)) {
                NavRow(stringResource(R.string.settings_languages), stringResource(R.string.settings_languages_body), onLanguages)
                NavRow(
                    stringResource(R.string.settings_word_list),
                    words?.let { wordSummary(it) } ?: stringResource(R.string.settings_word_list_body),
                    onWordList,
                )
                NavRow(stringResource(R.string.settings_services), stringResource(R.string.settings_services_body), null, last = true)
            }
            Group(stringResource(R.string.settings_recorder_backups)) {
                NavRow(stringResource(R.string.recorder_title), recorderSummary(recorder), onRecorder)
                NavRow(stringResource(R.string.backups_title), backups?.let { backupsSummary(it) }, onBackups, last = true)
            }
            Group(stringResource(R.string.settings_account)) {
                NavRow(
                    stringResource(R.string.settings_google),
                    stringResource(
                        when (backups?.driveConnected) {
                            true -> R.string.settings_google_drive
                            false -> R.string.settings_google_no_drive
                            null -> R.string.settings_google_body
                        },
                    ),
                    null,
                )
                NavRow(stringResource(R.string.settings_sign_out), null, onClick = { confirmSignOut = true }, last = true, titleColor = c.alert)
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text(stringResource(R.string.settings_sign_out), color = c.alert) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 24.dp)) {
        SectionLabel(label, Modifier.padding(bottom = 4.dp))
        content()
    }
}

/** The warning card at the top of 15: what's limiting Roudio and a way to fix it. */
@Composable
private fun BackgroundWarningCard(warning: BackgroundWarning, onFix: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.warningTint, shape)
            .border(1.dp, c.warningRim, shape)
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(RoudioIcons.Warning, contentDescription = null, tint = c.buoyText, modifier = Modifier.padding(top = 1.dp).size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (warning == BackgroundWarning.Battery) R.string.settings_battery_title else R.string.settings_autostart_title),
                style = roudioText(16.sp, 22.sp, FontWeight.SemiBold),
                color = c.ink,
            )
            Text(
                stringResource(if (warning == BackgroundWarning.Battery) R.string.settings_battery_body else R.string.settings_autostart_body),
                style = roudioText(15.sp, 21.sp),
                color = c.ink,
            )
            Text(
                stringResource(R.string.settings_fix_it),
                style = roudioText(15.sp, 20.sp, FontWeight.SemiBold).copy(textDecoration = TextDecoration.Underline),
                color = c.ink,
                modifier = Modifier
                    .height(44.dp)
                    .clickable(role = Role.Button, onClick = onFix)
                    .padding(top = 12.dp),
            )
        }
    }
}

/** "23 names and words · 4 learned from your edits", plus how many wait to be confirmed. */
@Composable
private fun wordSummary(words: WordLists): String {
    val listed = pluralStringResource(R.plurals.settings_words, words.list.size, words.list.size)
    return when {
        words.toConfirm.isNotEmpty() ->
            stringResource(R.string.settings_words_and, listed, pluralStringResource(R.plurals.settings_words_to_confirm, words.toConfirm.size, words.toConfirm.size))
        words.learned > 0 -> stringResource(R.string.settings_words_and, listed, pluralStringResource(R.plurals.settings_words_learned, words.learned, words.learned))
        else -> listed
    }
}

/** "Plaud Note Pro · 4F2A · connected". */
@Composable
private fun recorderSummary(state: RecorderState): String {
    val linked = state.linked ?: return stringResource(R.string.settings_recorder_none)
    return stringResource(
        R.string.settings_recorder_body,
        stringResource(R.string.recorder_name, linked.model.displayName, linked.serial.serialEnding),
        stringResource(if (state.isConnected) R.string.settings_connected else R.string.settings_not_connected),
    )
}

/** "Drive connected · 1 failed", or what's waiting. */
@Composable
private fun backupsSummary(view: BackupsView): String {
    val drive = stringResource(if (view.driveConnected) R.string.settings_drive_connected else R.string.settings_drive_not_connected)
    return when {
        view.failed > 0 -> stringResource(R.string.settings_words_and, drive, pluralStringResource(R.plurals.settings_backups_failed, view.failed, view.failed))
        view.waiting > 0 -> stringResource(R.string.settings_words_and, drive, pluralStringResource(R.plurals.settings_backups_waiting, view.waiting, view.waiting))
        else -> stringResource(R.string.settings_words_and, drive, stringResource(R.string.settings_backups_all))
    }
}

/** 15b Word list. */
@Composable
fun WordListRoute(app: RoudioApp, onBack: () -> Unit) {
    WordListRouteContent(app, onBack)
}

/** 15c Languages. */
@Composable
fun LanguagesRoute(app: RoudioApp, onBack: () -> Unit) {
    LanguagesScreen(onBack)
}

private val previewRecorder = RecorderState(linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro))

@Preview(widthDp = 412, heightDp = 1200)
@Composable
private fun SettingsPreview() {
    RoudioTheme(darkTheme = false) {
        SettingsScreen(previewRecorder, null, null, BackgroundWarning.Battery, {}, {}, {}, {}, {}, {})
    }
}

@Preview(widthDp = 412, heightDp = 1200)
@Composable
private fun SettingsDarkPreview() {
    RoudioTheme(darkTheme = true) {
        SettingsScreen(previewRecorder, null, null, null, {}, {}, {}, {}, {}, {})
    }
}
