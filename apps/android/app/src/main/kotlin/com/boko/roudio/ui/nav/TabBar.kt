package com.boko.roudio.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

enum class Tab { Today, Search, Review, Settings }

/** Space the content leaves at the bottom for the floating tab bar. */
val TAB_BAR_SPACE = 104.dp

/**
 * The floating tab pill (Paper "Nav — clear glass pill"). The design's frosted glass needs blur;
 * this is its solid fallback (design-decisions.md), slightly translucent with the same rim.
 */
@Composable
fun BoxScope.TabBar(selected: Tab, reviewWaiting: Boolean, onSelect: (Tab) -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp)
            .width(364.dp)
            .height(64.dp)
            .shadow(16.dp, shape, ambientColor = c.ink.copy(alpha = 0.18f), spotColor = c.ink.copy(alpha = 0.18f))
            .background(c.sheet.copy(alpha = 0.96f), shape)
            .border(1.dp, c.sheetRim, shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            TabButton(tab, tab == selected, badge = tab == Tab.Review && reviewWaiting) { onSelect(tab) }
        }
    }
}

@Composable
private fun TabButton(tab: Tab, selected: Boolean, badge: Boolean, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val label = stringResource(
        when (tab) {
            Tab.Today -> R.string.today
            Tab.Search -> R.string.tab_search
            Tab.Review -> R.string.tab_review
            Tab.Settings -> R.string.tab_settings
        },
    )
    val icon = when (tab) {
        Tab.Today -> RoudioIcons.TabToday
        Tab.Search -> RoudioIcons.TabSearch
        Tab.Review -> RoudioIcons.TabReview
        Tab.Settings -> RoudioIcons.TabSettings
    }
    val modifier = Modifier
        .height(52.dp)
        .semantics {
            this.selected = selected
            contentDescription = label
        }
        .clickable(role = Role.Tab, onClick = onClick)
    if (selected) {
        Row(
            modifier.background(c.primary, RoundedCornerShape(26.dp)).padding(start = 14.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(22.dp))
            Text(label, style = roudioText(14.sp, 18.sp, FontWeight.SemiBold), color = c.onPrimary)
        }
    } else {
        Box(modifier.width(56.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
            if (badge) Box(Modifier.offset(x = 9.dp, y = (-9).dp).size(9.dp).background(c.buoy, CircleShape))
        }
    }
}

/** A tab's screen with the tab bar floating over it. */
@Composable
fun TabScaffold(selected: Tab, reviewWaiting: Boolean, onSelect: (Tab) -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        TabBar(selected, reviewWaiting, onSelect)
    }
}
