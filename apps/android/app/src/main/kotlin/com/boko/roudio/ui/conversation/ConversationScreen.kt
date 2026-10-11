package com.boko.roudio.ui.conversation

import androidx.compose.runtime.Composable
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.nav.Placeholder

/** 06 Notes, 07 Transcript, 08 Ask, with the player (S3 still writing up, S8 failed). */
@Composable
fun ConversationRoute(app: RoudioApp, id: String, atMs: Long?, onBack: () -> Unit) {
    Placeholder("Recording")
}
