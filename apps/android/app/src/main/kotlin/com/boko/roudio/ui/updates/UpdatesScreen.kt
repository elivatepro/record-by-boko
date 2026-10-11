package com.boko.roudio.ui.updates

import androidx.compose.runtime.Composable
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.nav.Placeholder

/** 03 Updates and 03b all read / empty (a sheet over Today). */
@Composable
fun UpdatesRoute(
    app: RoudioApp,
    onOpenConversation: (id: String, atMs: Long?) -> Unit,
    onBackups: () -> Unit,
    onBack: () -> Unit,
) {
    Placeholder("Updates")
}
