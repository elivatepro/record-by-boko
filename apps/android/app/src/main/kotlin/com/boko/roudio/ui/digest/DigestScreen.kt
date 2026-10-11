package com.boko.roudio.ui.digest

import androidx.compose.runtime.Composable
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.nav.Placeholder

/** 09 Daily digest (09c quiet day, 09d failed). */
@Composable
fun DigestRoute(app: RoudioApp, day: String, onOpenConversation: (String, Long?) -> Unit, onBack: () -> Unit) {
    Placeholder("Daily digest")
}
