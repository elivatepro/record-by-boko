package com.boko.roudio.ui.settings

import androidx.compose.runtime.Composable
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.nav.Placeholder

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
    Placeholder("Settings")
}

/** 15b Word list. */
@Composable
fun WordListRoute(app: RoudioApp, onBack: () -> Unit) {
    Placeholder("Word list")
}

/** 15c Languages. */
@Composable
fun LanguagesRoute(app: RoudioApp, onBack: () -> Unit) {
    Placeholder("Languages")
}
