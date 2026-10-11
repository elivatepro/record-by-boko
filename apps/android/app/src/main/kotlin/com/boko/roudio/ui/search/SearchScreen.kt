package com.boko.roudio.ui.search

import androidx.compose.runtime.Composable
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.nav.Placeholder

/** 05 Search, 05b results, S4 no results, S5 offline. */
@Composable
fun SearchRoute(app: RoudioApp, onOpenConversation: (id: String, atMs: Long?) -> Unit) {
    Placeholder("Search")
}
