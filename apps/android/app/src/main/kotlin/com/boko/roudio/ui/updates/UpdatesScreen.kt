package com.boko.roudio.ui.updates

import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.api.models.UpdateView
import com.boko.roudio.data.SyncWork
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.backups.RetryState
import com.boko.roudio.ui.backups.retryBackup
import com.boko.roudio.ui.recorder.clockTime
import com.boko.roudio.ui.settings.CouldNotLoad
import com.boko.roudio.ui.settings.Divider
import com.boko.roudio.ui.settings.EmptyState
import com.boko.roudio.ui.settings.Loading
import com.boko.roudio.ui.settings.SHORT_DAY
import com.boko.roudio.ui.settings.SectionLabel
import com.boko.roudio.ui.settings.Segment
import com.boko.roudio.ui.settings.Segmented
import com.boko.roudio.ui.settings.fetch
import com.boko.roudio.ui.settings.send
import com.boko.roudio.ui.theme.RoudioRadius
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.launch

/** An update as loaded, with the recording's name and the conversation to open, if there is one. */
data class UpdateRow(val update: UpdateView, val recordingTitle: String?, val conversationId: String?)

/**
 * 03 Updates and 03b all read / empty, drawn as a sheet. Opening it marks everything read on the
 * server; the dots stay for this visit, so it's still clear what was new.
 */
@Composable
fun UpdatesRoute(
    app: RoudioApp,
    onOpenConversation: (id: String, atMs: Long?) -> Unit,
    onBackups: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<UpdateRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var retries by remember { mutableStateOf(emptyMap<Long, RetryState>()) }

    LaunchedEffect(attempt) {
        failed = false
        val updates = fetch { app.api.organise.listUpdates() }
        if (updates == null) {
            failed = true
            return@LaunchedEffect
        }
        val dao = app.database.library()
        rows = updates.map { update ->
            val conversations = update.recordingId?.let { dao.conversationsOf(it.toString()) }.orEmpty().sortedBy { it.startMs }
            UpdateRow(
                update = update,
                recordingTitle = conversations.firstOrNull()?.title,
                conversationId = update.conversationId?.toString() ?: conversations.firstOrNull()?.id,
            )
        }
        if (updates.any { !it.read } && send { app.api.organise.markUpdatesRead() }) SyncWork.now(context)
    }

    UpdatesSheet(
        rows = rows,
        failed = failed,
        retries = retries,
        onRetryLoad = { attempt++ },
        onMarkAllRead = {
            rows = rows?.map { it.copy(update = it.update.copy(read = true)) }
            scope.launch { send { app.api.organise.markUpdatesRead() } }
        },
        onRetryBackup = { update ->
            val id = update.recordingId ?: return@UpdatesSheet
            retries = retries + (update.id to RetryState.Working)
            scope.launch {
                val ok = retryBackup(app, id)
                retries = retries + (update.id to if (ok) RetryState.Started else RetryState.Failed)
            }
        },
        onOpen = { id -> onOpenConversation(id, null) },
        onBackups = onBackups,
        onDismiss = { update ->
            rows = rows?.filterNot { it.update.id == update.id }
            scope.launch { if (send { app.api.organise.dismissUpdate(update.id) }) SyncWork.now(context) }
        },
        onBack = onBack,
    )
}

/** The sheet over a dimmed top strip; tapping the strip closes it, like Back. */
@Composable
fun UpdatesSheet(
    rows: List<UpdateRow>?,
    failed: Boolean,
    retries: Map<Long, RetryState>,
    onRetryLoad: () -> Unit,
    onMarkAllRead: () -> Unit,
    onRetryBackup: (UpdateView) -> Unit,
    onOpen: (conversationId: String) -> Unit,
    onBackups: () -> Unit,
    onBack: () -> Unit,
    initialFilter: Int = 0,
    onDismiss: (UpdateView) -> Unit = {},
) {
    val c = RoudioTheme.colors
    var filter by rememberSaveable { mutableIntStateOf(initialFilter) }
    // Dragging the handle down moves the sheet with the finger; far or fast enough closes it.
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
    ) {
        Column(
            Modifier
                .statusBarsPadding()
                .padding(top = 48.dp)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .fillMaxSize()
                .background(c.sheet, RoundedCornerShape(topStart = RoudioRadius.Sheet, topEnd = RoudioRadius.Sheet))
                // Swallows taps so only the strip above closes the sheet.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .draggable(
                        state = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (drag.value > closeAt || velocity > 1500f) onBack() else drag.animateTo(0f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).background(c.hairline, RoundedCornerShape(2.dp)))
            }
            when {
                rows != null -> Loaded(rows, filter, { filter = it }, retries, onMarkAllRead, onRetryBackup, onOpen, onBackups, onDismiss)
                failed -> Padded {
                    Header(null, onMarkAllRead)
                    CouldNotLoad(stringResource(R.string.no_connection), onRetryLoad)
                }
                else -> Padded {
                    Header(null, onMarkAllRead)
                    Loading()
                }
            }
        }
    }
}

@Composable
private fun Padded(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), content = content)
}

@Composable
private fun Loaded(
    rows: List<UpdateRow>,
    filter: Int,
    onFilter: (Int) -> Unit,
    retries: Map<Long, RetryState>,
    onMarkAllRead: () -> Unit,
    onRetryBackup: (UpdateView) -> Unit,
    onOpen: (String) -> Unit,
    onBackups: () -> Unit,
    onDismiss: (UpdateView) -> Unit,
) {
    val unread = rows.count { !it.update.read }
    val needsYou = rows.filter { UpdatesModel.needsYou(it.update) }
    val zone = ZoneId.systemDefault()
    val shown = if (filter == 0) rows else needsYou
    val sections = UpdatesModel.sections(shown.map { it.update }, LocalDate.now(), zone)
    val byId = rows.associateBy { it.update.id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Padded {
                Header(unread, onMarkAllRead)
                Segmented(
                    listOf(
                        Segment(stringResource(R.string.updates_everything), rows.size, highlight = unread > 0),
                        Segment(stringResource(R.string.updates_needs_you), needsYou.size),
                    ),
                    selected = filter,
                    onSelect = onFilter,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    RoudioIcons.Bell,
                    stringResource(if (filter == 0) R.string.updates_empty_title else R.string.updates_nothing_title),
                    stringResource(R.string.updates_nothing_body),
                    Modifier.padding(top = 64.dp, start = 20.dp, end = 20.dp),
                )
            }
        }
        sections.forEach { (section, updates) ->
            item {
                SectionLabel(
                    when (section) {
                        UpdateSection.NeedsYou -> stringResource(R.string.updates_needs_you)
                        UpdateSection.Today -> stringResource(R.string.updates_earlier_today)
                        UpdateSection.Yesterday -> stringResource(R.string.yesterday)
                        is UpdateSection.Day -> SHORT_DAY.format(section.day)
                    },
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            items(updates, key = { it.id }) { update ->
                val row = byId.getValue(update.id)
                SwipeAway(onGone = { onDismiss(update) }) {
                    UpdateItem(row, retries[update.id] ?: RetryState.Idle, onRetryBackup, onOpen, onBackups)
                }
            }
        }
        item { Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
    }
}

@Composable
private fun Header(unread: Int?, onMarkAllRead: () -> Unit) {
    val c = RoudioTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (unread != null) {
                Text(
                    if (unread > 0) pluralStringResource(R.plurals.updates_unread, unread, unread) else stringResource(R.string.updates_all_read),
                    style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
                    color = c.muted,
                )
            }
            Text(
                stringResource(R.string.updates_title),
                style = roudioText(30.sp, 36.sp, FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (unread != null && unread > 0) {
            Row(
                Modifier
                    .height(48.dp)
                    .background(c.raised, RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button, onClick = onMarkAllRead)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(OrganiseIcons.DoubleCheck, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.updates_mark_all_read), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
            }
        }
    }
}

@Composable
private fun UpdateItem(
    row: UpdateRow,
    retry: RetryState,
    onRetryBackup: (UpdateView) -> Unit,
    onOpen: (String) -> Unit,
    onBackups: () -> Unit,
) {
    val c = RoudioTheme.colors
    val update = row.update
    val (message, safe) = UpdatesModel.splitSafe(update.body)
    val actions = UpdatesModel.actions(update, row.conversationId != null)
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KindIcon(update.kind, unread = !update.read)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        // "Backup failed · Studio call": the server's failure titles don't name the recording.
                        row.recordingTitle?.takeIf { update.kind in NAMED_KINDS }
                            ?.let { stringResource(R.string.updates_title_with_recording, update.title, it) } ?: update.title,
                        style = roudioText(17.sp, 22.sp, if (update.read) FontWeight.Medium else FontWeight.SemiBold),
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        timeText(update.createdAt),
                        style = roudioText(13.sp, 18.sp).copy(fontFeatureSettings = "tnum"),
                        color = c.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(message, style = roudioText(15.sp, 21.sp), color = c.muted)
                if (safe != null) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(RoudioIcons.ShieldCheck, contentDescription = null, tint = c.ink, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                        Text(safe, style = roudioText(14.sp, 20.sp), color = c.ink)
                    }
                }
                if (actions.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEach { action ->
                            when (action) {
                                UpdateAction.RetryBackup -> RetryButton(retry) { onRetryBackup(update) }
                                UpdateAction.OpenRecording -> PillButton(stringResource(R.string.updates_open_recording), filled = false) {
                                    row.conversationId?.let(onOpen)
                                }
                                UpdateAction.SeeCleanup -> Row(
                                    Modifier.height(48.dp).clickable(role = Role.Button, onClick = onBackups),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.updates_see_cleanup), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
                                    Icon(RoudioIcons.Chevron, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    if (retry == RetryState.Failed) {
                        Text(stringResource(R.string.backups_retry_failed), style = roudioText(13.sp, 18.sp), color = c.alert)
                    }
                }
            }
        }
        Divider()
    }
}

@Composable
private fun RetryButton(state: RetryState, onClick: () -> Unit) {
    val text = stringResource(
        when (state) {
            RetryState.Working -> R.string.backups_retrying
            RetryState.Started -> R.string.backups_retry_started
            else -> R.string.backups_retry
        },
    )
    PillButton(text, filled = true, enabled = state == RetryState.Idle || state == RetryState.Failed, icon = OrganiseIcons.Retry, onClick = onClick)
}

@Composable
private fun PillButton(text: String, filled: Boolean, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) c.primary else c.raised,
            contentColor = if (filled) c.onPrimary else c.ink,
            disabledContainerColor = (if (filled) c.primary else c.raised).copy(alpha = 0.6f),
            disabledContentColor = if (filled) c.onPrimary else c.ink,
        ),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(text, style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), maxLines = 1)
    }
}

/** 40 dp circle with the kind's icon; red for failures; the buoy dot when unread. */
@Composable
private fun KindIcon(kind: String, unread: Boolean) {
    val c = RoudioTheme.colors
    val (icon, alert) = when (kind) {
        "backup_failed" -> OrganiseIcons.CloudAlert to true
        "processing_failed" -> RoudioIcons.Alert to true
        "cleanup_partial" -> OrganiseIcons.Trash to false
        "backed_up" -> RoudioIcons.CloudCheck to false
        "written_up" -> OrganiseIcons.CircleCheck to false
        else -> RoudioIcons.Bell to false
    }
    Box {
        Box(
            Modifier.size(40.dp).background(if (alert) c.alertTint else c.raised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (alert) c.alert else c.ink, modifier = Modifier.size(20.dp))
        }
        if (unread) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(12.dp)
                    .background(c.sheet, CircleShape)
                    .padding(1.5.dp)
                    .background(c.buoy, CircleShape),
            )
        }
    }
}

private val NAMED_KINDS = setOf("backup_failed", "processing_failed")

/** "10:24" today, else "Mon 12 Oct". */
private fun timeText(at: OffsetDateTime): String {
    val zone = ZoneId.systemDefault()
    val day = at.atZoneSameInstant(zone).toLocalDate()
    return if (day == LocalDate.now(zone)) clockTime(at.toInstant()) else SHORT_DAY.format(day)
}

private val previewRows = run {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    fun update(id: Long, kind: String, title: String, body: String, action: String?, read: Boolean, minutesAgo: Long) = UpdateView(
        id = id,
        kind = kind,
        title = title,
        body = body,
        action = action,
        recordingId = UUID(0, id),
        conversationId = null,
        read = read,
        createdAt = now.minusMinutes(minutesAgo),
    )
    listOf(
        UpdateRow(
            update(
                1, "backup_failed", "Backup failed",
                "Drive upload stopped at 61%. The connection to Drive timed out. It's safe on your phone and the server, and on the recorder.",
                "retry_backup", false, 20,
            ),
            "Studio call",
            "c1",
        ),
        UpdateRow(
            update(2, "cleanup_partial", "Couldn't clear a recording from the recorder", "It's backed up and safe. Roudio will try again.", null, false, 60),
            null,
            null,
        ),
        UpdateRow(update(3, "written_up", "Studio call is written up", "Ready to read. Not backed up yet.", null, true, 120), null, "c1"),
    )
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun UpdatesPreview() {
    RoudioTheme(darkTheme = false) { UpdatesSheet(previewRows, false, emptyMap(), {}, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun UpdatesDarkPreview() {
    RoudioTheme(darkTheme = true) { UpdatesSheet(previewRows, false, emptyMap(), {}, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun UpdatesNothingNeedsYouPreview() {
    val read = previewRows.map { it.copy(update = it.update.copy(read = true)) }
    RoudioTheme(darkTheme = false) { UpdatesSheet(read, false, emptyMap(), {}, {}, {}, {}, {}, {}, initialFilter = 1) }
}

/** Swipe an update left or right to dismiss it for good. */
@Composable
private fun SwipeAway(onGone: () -> Unit, content: @Composable () -> Unit) {
    val c = RoudioTheme.colors
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(c.alertTint).padding(horizontal = 24.dp),
                contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Icon(RoudioIcons.Bin, contentDescription = stringResource(R.string.updates_dismiss), tint = c.alert) }
        },
        onDismiss = { onGone() },
    ) {
        Box(Modifier.background(c.sheet)) { content() }
    }
}
