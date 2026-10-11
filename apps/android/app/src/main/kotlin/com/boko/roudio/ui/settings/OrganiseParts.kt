package com.boko.roudio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.recorder.clockTime
import com.boko.roudio.ui.search.positionText
import com.boko.roudio.ui.setup.BigButton
import com.boko.roudio.ui.setup.RoudioMark
import com.boko.roudio.ui.setup.Spinner
import com.boko.roudio.ui.theme.RoudioPalette
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pieces shared by Search (05), Review (10), Settings (15–15c), Updates (03) and Backups (14). */

/** The "roudio" wordmark row at the top of the Search, Review and Settings tabs. */
@Composable
fun BrandHeader() {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoudioMark()
        Text(
            "roudio",
            style = roudioText(20.sp, 24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em),
            color = c.ink,
        )
    }
}

/** A tab's large title (10 "Review", 15 "Settings"). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = roudioText(30.sp, 36.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
        color = RoudioTheme.colors.ink,
        modifier = modifier.semantics { heading() },
    )
}

/** Round Back button with the title centred (14, 15b, 15c), as on 13a. */
@Composable
fun BackTitleHeader(title: String, onBack: () -> Unit) {
    val c = RoudioTheme.colors
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = c.raised, contentColor = c.ink),
        ) {
            Icon(RoudioIcons.Back, contentDescription = stringResource(R.string.back), Modifier.size(22.dp))
        }
        Text(
            title,
            style = roudioText(18.sp, 24.sp, FontWeight.SemiBold),
            color = c.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Spacer(Modifier.width(48.dp))
    }
}

/** Muted 13 sp label above a group of rows ("Recent", "Needs you", "Writing up"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
        color = RoudioTheme.colors.muted,
        modifier = modifier.semantics { heading() },
    )
}

/** One choice in a [Segmented] control. [highlight] puts the count in buoy: waiting or unread. */
data class Segment(val label: String, val count: Int? = null, val highlight: Boolean = false)

/** Fog pill with a navy selected segment (10 Waiting / Accepted / Done, 03 Everything / Needs you). */
@Composable
fun Segmented(segments: List<Segment>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = RoudioTheme.colors
    // A count on the selected pill: buoy on navy; on dark's light pill the text-safe buoy.
    val buoyOnPrimary = if (c.primary.luminance() > 0.5f) RoudioPalette.BuoyInk else c.buoy
    Row(
        modifier.fillMaxWidth().height(52.dp).background(c.raised, RoundedCornerShape(26.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        segments.forEachIndexed { i, segment ->
            val isSelected = i == selected
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(if (isSelected) Modifier.background(c.primary, RoundedCornerShape(22.dp)) else Modifier)
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) { onSelect(i) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    segment.label,
                    style = roudioText(15.sp, 20.sp, FontWeight.SemiBold),
                    color = if (isSelected) c.onPrimary else c.ink,
                    maxLines = 1,
                )
                if (segment.count != null) {
                    Text(
                        "${segment.count}",
                        style = roudioText(13.sp, 18.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
                        color = when {
                            isSelected && segment.highlight -> buoyOnPrimary
                            isSelected -> c.onPrimary.copy(alpha = 0.75f)
                            segment.highlight -> c.buoyText
                            else -> c.muted
                        },
                    )
                }
            }
        }
    }
}

/** Fog pill with a play triangle and the position in the recording ("▶ 9:42"). */
@Composable
fun PlayChip(atMs: Long, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val label = positionText(atMs)
    Row(
        Modifier
            .height(30.dp)
            .background(c.raised, RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.organise_play_from, label), onClick = onClick)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OrganiseIcons.Play, contentDescription = null, tint = c.ink, modifier = Modifier.size(12.dp))
        Text(label, style = roudioText(14.sp, 18.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), color = c.ink)
    }
}

/** Icon in a ringed circle with a title and a line (S4 no results, 03b nothing needs you). */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val c = RoudioTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(64.dp).background(c.hairline, CircleShape).padding(8.dp).background(c.ground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(30.dp))
        }
        Text(
            title,
            style = roudioText(22.sp, 28.sp, FontWeight.SemiBold),
            color = c.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(body, style = roudioText(16.sp, 22.sp), color = c.muted, textAlign = TextAlign.Center)
    }
}

/** Waiting for the server. */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Spinner(size = 32.dp, stroke = 3.dp)
    }
}

/** The server couldn't be reached: say so and offer to try again. */
@Composable
fun CouldNotLoad(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val c = RoudioTheme.colors
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(RoudioIcons.WifiOff, contentDescription = null, tint = c.ink, modifier = Modifier.size(28.dp))
        Text(message, style = roudioText(15.sp, 22.sp), color = c.muted, textAlign = TextAlign.Center)
        BigButton(stringResource(R.string.try_again), onRetry, filled = false)
    }
}

/** A settings row: title, muted line, and a chevron when it opens something. */
@Composable
fun NavRow(title: String, body: String?, onClick: (() -> Unit)?, last: Boolean = false, titleColor: Color? = null) {
    val c = RoudioTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = roudioText(17.sp, 22.sp, FontWeight.SemiBold), color = titleColor ?: c.ink)
                if (body != null) Text(body, style = roudioText(14.sp, 20.sp), color = c.muted)
            }
            if (onClick != null) Icon(RoudioIcons.Chevron, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        }
        if (!last) Divider()
    }
}

/** A row with a switch on the right (14 "Delete from the recorder"). */
@Composable
fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = roudioText(17.sp, 22.sp, FontWeight.SemiBold), color = c.ink)
            Text(body, style = roudioText(14.sp, 20.sp), color = c.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.primary,
                checkedThumbColor = c.onPrimary,
                checkedBorderColor = c.primary,
                uncheckedTrackColor = c.raised,
                uncheckedThumbColor = c.muted,
                uncheckedBorderColor = c.hairline,
            ),
        )
    }
}

/** "Today 09:30", "Yesterday 18:05", "Mon 12 Oct 09:30", in the phone's time zone. */
@Composable
fun dayAndTime(at: Instant, now: Instant): String {
    val zone = ZoneId.systemDefault()
    val day = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val dayText = when (day) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> SHORT_DAY.format(day)
    }
    return stringResource(R.string.organise_day_time, dayText, clockTime(at))
}

/** "Mon 12 Oct". */
val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(RoudioTheme.colors.hairline))
}

/** Dashed outline: a suggestion, or something not decided yet (10 "Suggested task", 15b learned words). */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}
