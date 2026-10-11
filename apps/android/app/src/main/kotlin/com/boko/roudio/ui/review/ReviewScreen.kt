package com.boko.roudio.ui.review

import androidx.compose.runtime.Composable
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.nav.Placeholder

/** 10 Review (Waiting), 10b Accepted, 10c edit sheet, S6 all caught up. */
@Composable
fun ReviewRoute(app: RoudioApp, onOpenConversation: (id: String, atMs: Long?) -> Unit) {
    Placeholder("Review")
}
