package com.boko.roudio.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.api.models.DigestView
import com.boko.roudio.ui.nav.TAB_BAR_SPACE
import com.boko.roudio.recorder.BatteryReading
import com.boko.roudio.recorder.LinkedRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.Seen
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.recorder.clockTime
import com.boko.roudio.ui.setup.RoudioMark
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * 01 Today (and 01b for a past day, S1 with the recorder away, S2 offline). The digest card,
 * bell and tabs arrive with the screens they open; the dial can't tell conversation from quiet
 * until milestone 3, so recorded time shows as "not written up yet".
 */
@Composable
fun TodayScreen(
    model: TodayModel,
    recorder: RecorderState,
    online: Boolean,
    now: Instant,
    onRecorder: () -> Unit,
    onPreviousDay: () -> Unit,
    onBackToToday: () -> Unit,
    digest: DigestView? = null,
    unreadUpdates: Int = 0,
    onDigest: () -> Unit = {},
    onUpdates: () -> Unit = {},
    onConversation: (String) -> Unit = {},
    onPending: (Long) -> Unit = {},
) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoudioMark()
            Text(
                "roudio",
                style = roudioText(20.sp, 24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em),
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            Bell(unreadUpdates, onUpdates)
        }
        DateRow(model, now, onPreviousDay)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Dial(model, now)
        }
        Legend(model.kinds)
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
            if (model.isToday) {
                RecorderChip(recorder, model.onRecorder, onRecorder)
            } else {
                Chip(RoudioIcons.ArrowRight, stringResource(R.string.back_to_today), onBackToToday)
            }
        }
        if (!online && model.waitingToUpload > 0) OfflineBanner(model.waitingToUpload)
        digest?.let { DigestCard(it, onDigest) }
        Column(Modifier.padding(top = 14.dp, bottom = TAB_BAR_SPACE)) {
            if (model.rows.isEmpty()) {
                Text(
                    stringResource(if (model.isToday) R.string.today_empty else R.string.past_day_empty),
                    style = roudioText(15.sp, 22.sp),
                    color = c.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
                )
            }
            model.rows.forEach { row ->
                TimelineRowView(row) {
                    row.conversationId?.let(onConversation) ?: row.pendingId?.let(onPending)
                }
            }
        }
    }
}

@Composable
private fun DateRow(model: TodayModel, now: Instant, onPreviousDay: () -> Unit) {
    val c = RoudioTheme.colors
    val today = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (model.day) {
                        today -> R.string.today
                        today.minusDays(1) -> R.string.yesterday
                        else -> R.string.past_day
                    },
                ),
                style = roudioText(13.sp, 16.sp, FontWeight.SemiBold),
                color = c.muted,
            )
            Text(
                DAY.format(model.day),
                style = roudioText(26.sp, 32.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        IconButton(
            onClick = onPreviousDay,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = c.raised, contentColor = c.ink),
        ) {
            Icon(RoudioIcons.ChevronLeft, contentDescription = stringResource(R.string.previous_day), Modifier.size(22.dp))
        }
    }
}

/**
 * The 24-hour dial (Paper "24-hour dial"): midnight at the top, clockwise. 300 dp on Today with
 * the total in the middle; smaller and plain on the digest (09).
 */
@Composable
fun Dial(model: TodayModel, now: Instant, diameter: Dp = 300.dp, centre: Boolean = true) {
    val c = RoudioTheme.colors
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontFamily = com.boko.roudio.ui.theme.Lexend, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
    val notWrittenUp = c.ink.copy(alpha = 0.55f)
    val quiet = c.ink.copy(alpha = 0.28f)
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val s = size.width / 300f
            val centre = Offset(150f * s, 150f * s)
            val ring = 122f * s
            val band = 21.9f * s
            val box = Size(ring * 2, ring * 2)
            val topLeft = Offset(centre.x - ring, centre.y - ring)
            drawCircle(c.raised, radius = ring, center = centre, style = Stroke(band))
            model.spans.forEach { span ->
                // Even a few seconds stays visible as a sliver.
                val sweep = maxOf((span.end - span.start) * 360f, MIN_SWEEP)
                drawArc(
                    color = when (span.kind) {
                        SpanKind.Conversation -> c.ink
                        SpanKind.Quiet -> quiet
                        SpanKind.NotWrittenUp -> notWrittenUp
                        SpanKind.StillOnPlaud -> c.buoy
                    },
                    startAngle = span.start * 360f - 90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = Stroke(band),
                )
            }
            for (hour in 0 until 24) {
                val major = hour % 6 == 0
                if (!major && !model.isToday) continue // 01b shows the four main ticks only
                val angle = Math.toRadians(hour * 15.0 - 90.0)
                val (inner, outer) = if (major) 137.9f to 144.9f else 138.9f to 141.9f
                drawLine(
                    c.muted,
                    Offset(centre.x + cos(angle).toFloat() * inner * s, centre.y + sin(angle).toFloat() * inner * s),
                    Offset(centre.x + cos(angle).toFloat() * outer * s, centre.y + sin(angle).toFloat() * outer * s),
                    strokeWidth = (if (major) 1.6f else 1f) * s,
                )
            }
            listOf("00" to 0, "06" to 6, "12" to 12, "18" to 18).forEach { (text, hour) ->
                val angle = Math.toRadians(hour * 15.0 - 90.0)
                val layout = measurer.measure(text, label)
                val at = Offset(centre.x + cos(angle).toFloat() * 92f * s, centre.y + sin(angle).toFloat() * 92f * s)
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
            }
            model.now?.let { fraction ->
                val angle = Math.toRadians(fraction * 360.0 - 90.0)
                val at = Offset(centre.x + cos(angle).toFloat() * 141f * s, centre.y + sin(angle).toFloat() * 141f * s)
                drawCircle(c.buoy, radius = 5f * s, center = at)
                drawCircle(c.ink, radius = 5f * s, center = at, style = Stroke(2f * s))
            }
        }
        if (centre) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                durationText(model.recorded),
                style = roudioText(34.sp, 40.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em),
                color = c.ink,
            )
            Text(
                stringResource(if (model.isToday) R.string.recorded_today else R.string.recorded),
                style = roudioText(13.sp, 18.sp),
                color = c.muted,
            )
            if (model.isToday) {
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(c.buoy, CircleShape))
                    Text(
                        stringResource(R.string.now_time, clockTime(now)),
                        style = roudioText(12.sp, 16.sp, FontWeight.SemiBold),
                        color = c.ink,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(kinds: Set<SpanKind>) {
    val c = RoudioTheme.colors
    // Wraps onto a second line when all four kinds are on the dial.
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val written = SpanKind.Conversation in kinds || SpanKind.Quiet in kinds
        if (written) {
            LegendItem(c.ink, outlined = false, stringResource(R.string.legend_conversation))
            LegendItem(c.ink.copy(alpha = 0.28f), outlined = false, stringResource(R.string.legend_quiet))
        }
        if (SpanKind.NotWrittenUp in kinds || !written) {
            LegendItem(c.ink.copy(alpha = 0.55f), outlined = false, stringResource(R.string.legend_not_written_up))
        }
        LegendItem(c.buoy, outlined = true, stringResource(R.string.legend_still_on_plaud))
    }
}

@Composable
private fun LegendItem(colour: Color, outlined: Boolean, text: String) {
    val c = RoudioTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(14.dp)
                .height(6.dp)
                .then(if (outlined) Modifier.border(1.dp, c.ink, RoundedCornerShape(2.dp)) else Modifier)
                .background(colour, RoundedCornerShape(2.dp)),
        )
        Text(text, style = roudioText(12.sp, 16.sp, FontWeight.Medium), color = c.muted)
    }
}

/**
 * The recorder chip (01, S1). Fog when connected; a buoy rim when not, with when it was last seen.
 * Battery only shows once the recorder has reported it on this connection.
 */
@Composable
fun RecorderChip(state: RecorderState, onRecorder: Int, onClick: () -> Unit) {
    val connected = state.isConnected
    val freshBattery = state.battery?.takeIf { b -> state.connectedSince?.let { !b.at.isBefore(it) } == true }
    val seen = state.lastSeen?.let(::clockTime)
    val text = when {
        connected && freshBattery != null && onRecorder > 0 ->
            stringResource(R.string.chip_linked_battery_on_device, freshBattery.value.percent, onRecorder)
        connected && freshBattery != null -> stringResource(R.string.chip_linked_battery, freshBattery.value.percent)
        connected -> stringResource(R.string.chip_linked)
        !state.bluetoothOn -> stringResource(R.string.chip_bluetooth_off)
        seen != null -> stringResource(R.string.chip_not_connected_seen, seen)
        else -> stringResource(R.string.chip_not_connected)
    }
    Chip(RoudioIcons.RecorderChip, text, onClick, warning = !connected)
}

@Composable
private fun Chip(icon: ImageVector, text: String, onClick: () -> Unit, warning: Boolean = false) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .height(36.dp)
            .then(if (warning) Modifier.background(c.ground, shape).border(1.5.dp, c.buoy, shape) else Modifier.background(c.raised, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
        Text(text, style = roudioText(13.sp, 16.sp, FontWeight.Medium), color = c.ink)
    }
}

/** The bell with its unread dot (01). */
@Composable
private fun Bell(unread: Int, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .background(c.raised, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            RoudioIcons.Bell,
            contentDescription = if (unread > 0) pluralStringResource(R.plurals.bell_updates_unread, unread, unread) else stringResource(R.string.updates),
            tint = c.ink,
            modifier = Modifier.size(22.dp),
        )
        if (unread > 0) {
            Box(Modifier.offset(x = 10.dp, y = (-10).dp).size(12.dp).background(c.raised, CircleShape).padding(2.dp).background(c.buoy, CircleShape))
        }
    }
}

/** The digest card (01): updating with "4 of 6", or finished. */
@Composable
private fun DigestCard(digest: DigestView, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .background(c.heroCard, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(RoudioIcons.Sunrise, contentDescription = null, tint = c.buoy, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.daily_digest), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.onHeroCard, modifier = Modifier.weight(1f))
            Text(
                when (digest.state) {
                    DigestView.State.UPDATING -> stringResource(R.string.digest_updating_count, digest.recordingsDone, digest.recordingsTotal)
                    DigestView.State.FINISHED -> stringResource(R.string.digest_finished)
                    DigestView.State.QUIET -> stringResource(R.string.digest_quiet)
                    DigestView.State.FAILED -> stringResource(R.string.digest_failed)
                },
                style = roudioText(13.sp, 16.sp, FontWeight.Medium),
                color = c.onHeroCardMuted,
            )
        }
        Text(
            digest.overview ?: stringResource(R.string.digest_waiting),
            style = roudioText(19.sp, 26.sp, FontWeight.Medium),
            color = c.onHeroCard,
        )
    }
}

/** S2: no internet, with recordings waiting on the phone. */
@Composable
private fun OfflineBanner(waiting: Int) {
    val c = RoudioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .background(c.raised, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RoudioIcons.WifiOff, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        Column {
            Text(
                pluralStringResource(R.plurals.offline_waiting, waiting, waiting),
                style = roudioText(14.sp, 20.sp, FontWeight.SemiBold),
                color = c.ink,
            )
            Text(stringResource(R.string.offline_safe), style = roudioText(12.sp, 16.sp), color = c.muted)
        }
    }
}

@Composable
private fun TimelineRowView(row: TimelineRow, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (row.conversationId != null || row.pendingId != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(36.dp).background(if (row.stillOnPlaud) c.buoy else c.ink, RoundedCornerShape(2.dp)))
            Text(
                row.time,
                style = roudioText(14.sp, 18.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
                color = c.ink,
                modifier = Modifier.width(44.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    when (val title = row.title) {
                        RowTitle.WaitingOnPlaud -> stringResource(R.string.row_waiting_on_plaud)
                        is RowTitle.Length -> stringResource(R.string.row_recording_length, durationText(title.length))
                        is RowTitle.Conversation -> title.title
                    },
                    style = roudioText(15.sp, 20.sp, FontWeight.Medium),
                    color = c.ink,
                )
                Text(statusText(row), style = roudioText(12.sp, 16.sp), color = c.muted)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
    }
}

@Composable
private fun statusText(row: TimelineRow): String {
    if (row.status == RowStatus.MaybeRecording) return stringResource(R.string.row_maybe_recording)
    // Written up: who spoke and the decisions say more than "Written up" (01 "Daniel, Priya · 2 decisions").
    if (row.status == RowStatus.WrittenUp && row.people.isNotEmpty()) {
        val decided = if (row.decisions > 0) pluralStringResource(R.plurals.row_decisions, row.decisions, row.decisions) else null
        return listOfNotNull(row.people, decided, backupWord(row.backup)).joinToString(" · ")
    }
    val word = when (val status = row.status) {
        RowStatus.RecordingNow -> stringResource(R.string.word_recording_now)
        RowStatus.MaybeRecording -> ""
        RowStatus.NotTransferred -> stringResource(R.string.word_not_transferred)
        is RowStatus.Transferring -> stringResource(R.string.word_transferring, status.percent)
        RowStatus.WaitingToUpload -> stringResource(R.string.word_waiting_upload)
        is RowStatus.Uploading -> stringResource(R.string.word_uploading, status.percent)
        RowStatus.WaitingToWriteUp -> stringResource(R.string.word_waiting_write_up)
        RowStatus.WritingUp -> stringResource(R.string.word_writing_up)
        RowStatus.WritingUpFailed -> stringResource(R.string.word_writing_up_failed)
        RowStatus.WrittenUp -> stringResource(R.string.word_written_up)
        RowStatus.NothingSaid -> stringResource(R.string.word_nothing_said)
    }
    return stringResource(R.string.row_words, word, backupWord(row.backup))
}

@Composable
private fun backupWord(backup: BackupWord): String = stringResource(
    when (backup) {
        BackupWord.Later -> R.string.backup_later
        BackupWord.NotYet -> R.string.backup_not_yet
        BackupWord.BackedUp -> R.string.backup_done
        BackupWord.Failed -> R.string.backup_failed_word
    },
)

/** "8h 27m", "12 min", "7 s". */
@Composable
fun durationText(length: Duration): String = when {
    length >= Duration.ofHours(1) -> stringResource(R.string.hours_minutes, length.toHours().toInt(), (length.toMinutes() % 60).toInt())
    length >= Duration.ofMinutes(1) -> stringResource(R.string.minutes_only, length.toMinutes().toInt())
    else -> stringResource(R.string.seconds_only, length.seconds.toInt())
}

private const val MIN_SWEEP = 1.5f
private val DAY = DateTimeFormatter.ofPattern("EEEE d MMM")

private val previewNow = Instant.parse("2026-10-10T13:32:00Z")
private val previewRecorder = RecorderState(
    linked = LinkedRecorder("8810000004F2A", RecorderModel.NotePro),
    connectedSince = previewNow.minusSeconds(60),
    lastSeen = previewNow,
    battery = Seen(BatteryReading(64, false), previewNow),
)
private val previewModel = TodayModel(
    day = LocalDate.of(2026, 10, 10),
    isToday = true,
    spans = listOf(DialSpan(9 * 3600, 11 * 3600, SpanKind.NotWrittenUp), DialSpan(14 * 3600, 14 * 3600 + 1900, SpanKind.StillOnPlaud)),
    recorded = Duration.ofMinutes(152),
    now = (14 * 60 + 32) / 1440f,
    rows = listOf(
        TimelineRow(null, "14:10", RowTitle.WaitingOnPlaud, RowStatus.RecordingNow, BackupWord.Later, true),
        TimelineRow(2, "09:00", RowTitle.Conversation("Studio call"), RowStatus.WrittenUp, BackupWord.BackedUp, false, "c1"),
    ),
    waitingToUpload = 1,
    onRecorder = 1,
)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun TodayPreview() {
    RoudioTheme(darkTheme = false) { TodayScreen(previewModel, previewRecorder, online = true, previewNow, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun TodayOfflineDarkPreview() {
    RoudioTheme(darkTheme = true) {
        TodayScreen(previewModel, previewRecorder.copy(connectedSince = null), online = false, previewNow, {}, {}, {})
    }
}
