package com.boko.roudio.ui.review

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.api.models.ConversationSummary
import com.boko.roudio.api.models.ReviewChange
import com.boko.roudio.api.models.ReviewView
import com.boko.roudio.data.SyncWork
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.nav.TAB_BAR_SPACE
import com.boko.roudio.ui.search.positionText
import com.boko.roudio.ui.settings.BrandHeader
import com.boko.roudio.ui.settings.CouldNotLoad
import com.boko.roudio.ui.settings.Divider
import com.boko.roudio.ui.settings.Loading
import com.boko.roudio.ui.settings.PlayChip
import com.boko.roudio.ui.settings.SHORT_DAY
import com.boko.roudio.ui.settings.ScreenTitle
import com.boko.roudio.ui.settings.Segment
import com.boko.roudio.ui.settings.Segmented
import com.boko.roudio.ui.settings.dashedBorder
import com.boko.roudio.ui.settings.fetch
import com.boko.roudio.ui.setup.SmallButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 10 Review (Waiting), 10b Accepted, 10c edit sheet, S6 all caught up. */
@Composable
fun ReviewRoute(app: RoudioApp, onOpenConversation: (id: String, atMs: Long?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ReviewView>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var justDone by remember { mutableStateOf(emptySet<Long>()) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        val loaded = fetch { app.api.organise.listReview() }
        if (loaded == null) failed = items == null else items = loaded
    }

    val change: (Long, ReviewView.State?, String?) -> Unit = { id, state, text ->
        val before = items.orEmpty()
        items = ReviewModel.change(before, id, state, text)
        saveFailed = false
        justDone = when (state) {
            ReviewView.State.DONE -> justDone + id
            ReviewView.State.ACCEPTED -> justDone - id
            else -> justDone
        }
        scope.launch {
            val saved = fetch { app.api.organise.updateReview(id, ReviewChange(state = state?.let(ReviewModel::toChange), text = text)) }
            if (saved == null) {
                items = before
                saveFailed = true
            } else {
                items = items?.map { if (it.id == id) saved else it }
                // The tab's badge comes from the last sync.
                SyncWork.now(context)
            }
        }
    }

    val loaded = items
    when {
        loaded != null -> ReviewScreen(
            items = loaded,
            justDone = justDone,
            saveFailed = saveFailed,
            today = LocalDate.now(),
            onChange = change,
            onOpenConversation = onOpenConversation,
        )
        failed -> ReviewFrame { CouldNotLoad(stringResource(R.string.no_connection), onRetry = { attempt++ }) }
        else -> ReviewFrame { Loading() }
    }
}

/** The tab's header around whatever is below it. */
@Composable
private fun ReviewFrame(content: @Composable () -> Unit) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = TAB_BAR_SPACE),
    ) {
        BrandHeader()
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScreenTitle(stringResource(R.string.tab_review))
            Text(stringResource(R.string.review_subtitle), style = roudioText(15.sp, 22.sp), color = c.muted)
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp)) { content() }
    }
}

/**
 * Review with its items loaded: one suggestion at a time on Waiting, the accepted tasks to tick,
 * and what's done. [onChange] takes a new state and/or the owner's wording.
 */
@Composable
fun ReviewScreen(
    items: List<ReviewView>,
    justDone: Set<Long>,
    saveFailed: Boolean,
    today: LocalDate,
    onChange: (id: Long, state: ReviewView.State?, text: String?) -> Unit,
    onOpenConversation: (id: String, atMs: Long?) -> Unit,
    initialTab: Int = 0,
) {
    val c = RoudioTheme.colors
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var editing by remember { mutableStateOf<ReviewView?>(null) }
    val lists = ReviewModel.split(items, justDone)
    val open: (ReviewView) -> Unit = { onOpenConversation(it.conversation.id.toString(), it.atMs) }
    ReviewFrame {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Segmented(
                listOf(
                    Segment(stringResource(R.string.review_waiting), lists.waiting.size, highlight = true),
                    Segment(stringResource(R.string.review_accepted), lists.accepted.count { it.state == ReviewView.State.ACCEPTED }),
                    Segment(stringResource(R.string.review_done), lists.done.size),
                ),
                selected = tab,
                onSelect = { tab = it },
            )
            if (saveFailed) {
                Text(stringResource(R.string.review_save_failed), style = roudioText(14.sp, 20.sp), color = c.alert)
            }
            when (tab) {
                0 -> if (lists.waiting.isEmpty()) {
                    AllCaughtUp(ReviewModel.tallyToday(items, today, ZoneId.systemDefault()), onSeeAccepted = { tab = 1 })
                } else {
                    Waiting(
                        lists.waiting,
                        onAccept = { onChange(it.id, ReviewView.State.ACCEPTED, null) },
                        onEdit = { editing = it },
                        onDismiss = { onChange(it.id, ReviewView.State.DISMISSED, null) },
                        onOpen = open,
                    )
                }
                1 -> TaskList(
                    lists.accepted,
                    empty = stringResource(R.string.review_accepted_empty),
                    footer = stringResource(R.string.review_accepted_footer),
                    today = today,
                    justDone = justDone,
                    onTick = { item, done -> onChange(item.id, if (done) ReviewView.State.DONE else ReviewView.State.ACCEPTED, null) },
                    onOpen = open,
                )
                else -> TaskList(
                    lists.done,
                    empty = stringResource(R.string.review_done_empty),
                    footer = null,
                    today = today,
                    justDone = emptySet(),
                    onTick = { item, done -> onChange(item.id, if (done) ReviewView.State.DONE else ReviewView.State.ACCEPTED, null) },
                    onOpen = open,
                )
            }
        }
    }
    editing?.let { item ->
        EditSuggestionSheet(
            item = item,
            onSave = { text ->
                editing = null
                onChange(item.id, ReviewView.State.ACCEPTED, text)
            },
            onCancel = { editing = null },
        )
    }
}

/** 10: the first waiting suggestion as a card on a short stack, with what comes next. */
@Composable
private fun Waiting(
    waiting: List<ReviewView>,
    onAccept: (ReviewView) -> Unit,
    onEdit: (ReviewView) -> Unit,
    onDismiss: (ReviewView) -> Unit,
    onOpen: (ReviewView) -> Unit,
) {
    val c = RoudioTheme.colors
    val item = waiting.first()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box {
            // The cards underneath: only a sliver of each shows.
            if (waiting.size > 2) StackLayer(inset = 32.dp, drop = 28.dp)
            if (waiting.size > 1) StackLayer(inset = 16.dp, drop = 14.dp)
            SuggestionCard(item, onAccept, onEdit, onDismiss, onOpen)
        }
        val next = waiting.getOrNull(1)
        Text(
            if (next != null) {
                stringResource(R.string.review_position_next, 1, waiting.size, next.text)
            } else {
                stringResource(R.string.review_position, 1, waiting.size)
            },
            style = roudioText(14.sp, 20.sp),
            color = c.muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun StackLayer(inset: Dp, drop: Dp) {
    val c = RoudioTheme.colors
    Box(
        Modifier
            .padding(horizontal = inset)
            .offset(y = drop)
            .fillMaxWidth()
            .height(120.dp)
            .background(if (inset > 16.dp) c.raised else c.hairline, RoundedCornerShape(28.dp)),
    )
}

/** The suggestion card. Swipe right to accept, left to dismiss; the buttons do the same. */
@Composable
private fun SuggestionCard(
    item: ReviewView,
    onAccept: (ReviewView) -> Unit,
    onEdit: (ReviewView) -> Unit,
    onDismiss: (ReviewView) -> Unit,
    onOpen: (ReviewView) -> Unit,
) {
    val c = RoudioTheme.colors
    val scope = rememberCoroutineScope()
    val drag = remember(item.id) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val shape = RoundedCornerShape(28.dp)
    val accept = stringResource(R.string.review_accept)
    val dismiss = stringResource(R.string.review_dismiss)
    Column(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(drag.value.roundToInt(), 0) }
            .graphicsLayer { rotationZ = drag.value / threshold * 3f }
            .pointerInput(item.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                drag.value > threshold -> onAccept(item)
                                drag.value < -threshold -> onDismiss(item)
                                else -> drag.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        scope.launch { drag.snapTo(drag.value + amount) }
                    },
                )
            }
            .shadow(12.dp, shape, ambientColor = c.ink.copy(alpha = 0.12f), spotColor = c.ink.copy(alpha = 0.12f))
            .background(c.sheet, shape)
            .border(1.dp, c.hairline, shape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.review_suggested_task),
            style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
            color = c.muted,
            modifier = Modifier.dashedBorder(c.muted, 16.dp, 1.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            item.text,
            style = roudioText(24.sp, 30.sp, FontWeight.SemiBold),
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.conversation.title, style = roudioText(15.sp, 20.sp), color = c.muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            PlayChip(item.atMs) { onOpen(item) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onAccept(item) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
            ) {
                Icon(OrganiseIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(accept, style = roudioText(16.sp, 20.sp, FontWeight.SemiBold), modifier = Modifier.padding(start = 8.dp))
            }
            RoundAction(OrganiseIcons.Pencil, stringResource(R.string.review_edit)) { onEdit(item) }
            RoundAction(OrganiseIcons.Close, dismiss) { onDismiss(item) }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Box(
        Modifier
            .size(52.dp)
            .background(c.raised, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
    }
}

/** 10b and Done: tasks with a tick box. Ticking marks done; Undo (or unticking) brings it back. */
@Composable
private fun TaskList(
    tasks: List<ReviewView>,
    empty: String,
    footer: String?,
    today: LocalDate,
    justDone: Set<Long>,
    onTick: (ReviewView, Boolean) -> Unit,
    onOpen: (ReviewView) -> Unit,
) {
    val c = RoudioTheme.colors
    if (tasks.isEmpty()) {
        Text(empty, style = roudioText(15.sp, 22.sp), color = c.muted, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Column {
        tasks.forEach { task ->
            val done = task.state == ReviewView.State.DONE
            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TickBox(done, task.text) { onTick(task, !done) }
                Column(
                    Modifier.weight(1f).clickable(role = Role.Button) { onOpen(task) },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        task.text,
                        style = roudioText(17.sp, 22.sp, FontWeight.SemiBold).copy(
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                        ),
                        color = if (done) c.muted else c.ink,
                    )
                    if (done && task.id in justDone) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.review_done_just_now), style = roudioText(14.sp, 20.sp), color = c.muted)
                            Text(
                                stringResource(R.string.review_undo),
                                style = roudioText(14.sp, 20.sp, FontWeight.SemiBold),
                                color = c.ink,
                                modifier = Modifier.clickable(role = Role.Button) { onTick(task, false) },
                            )
                        }
                    } else {
                        Text(taskDetail(task, today), style = roudioText(14.sp, 20.sp), color = c.muted)
                    }
                }
            }
            Divider()
        }
        if (footer != null) {
            Text(footer, style = roudioText(15.sp, 22.sp), color = c.muted, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

/** "Studio call · 38:40 · today", or "· edited by you" when reworded. */
@Composable
private fun taskDetail(task: ReviewView, today: LocalDate): String {
    val day = task.conversation.day
    val last = when {
        ReviewModel.edited(task) -> stringResource(R.string.review_edited_by_you)
        day == today -> stringResource(R.string.review_today)
        day == today.minusDays(1) -> stringResource(R.string.review_yesterday)
        else -> SHORT_DAY.format(day)
    }
    return stringResource(R.string.review_task_detail, task.conversation.title, positionText(task.atMs), last)
}

/** 24 dp rounded box; filled navy with a tick when done. */
@Composable
private fun TickBox(checked: Boolean, label: String, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(7.dp)
    val state = stringResource(if (checked) R.string.review_done else R.string.review_not_done)
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .then(if (checked) Modifier.background(c.primary, shape) else Modifier.border(2.dp, c.ink, shape)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(OrganiseIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

/** S6: nothing waiting, with what happened today. */
@Composable
private fun AllCaughtUp(tally: Tally, onSeeAccepted: () -> Unit) {
    val c = RoudioTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val ink = c.ink
        Canvas(Modifier.size(76.dp)) {
            val s = size.width / 76f
            drawCircle(ink, radius = 34f * s, style = Stroke(7f * s))
            val tick = Path().apply {
                moveTo(23f * s, 39f * s)
                lineTo(33f * s, 48f * s)
                lineTo(52f * s, 29f * s)
            }
            drawPath(tick, ink, style = Stroke(7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(
            stringResource(R.string.review_caught_up),
            style = roudioText(24.sp, 30.sp, FontWeight.SemiBold),
            color = c.ink,
            modifier = Modifier.padding(top = 6.dp).semantics { heading() },
        )
        Text(
            if (tally.total > 0) {
                pluralStringResource(R.plurals.review_went_through, tally.total, tally.total, tally.accepted, tally.dismissed)
            } else {
                stringResource(R.string.review_nothing_waiting)
            },
            style = roudioText(16.sp, 22.sp),
            color = c.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SmallButton(stringResource(R.string.review_see_accepted), onSeeAccepted, filled = false, height = 44.dp)
    }
}

private fun previewItem(id: Long, text: String, state: ReviewView.State, title: String = "Studio call", atMs: Long = 2_285_000) = ReviewView(
    id = id,
    state = state,
    text = text,
    suggestedText = text,
    conversation = ConversationSummary(
        id = UUID(0, id),
        recordingId = UUID(1, 1),
        day = LocalDate.of(2026, 10, 11),
        kind = "conversation",
        title = title,
        summary = "",
        startedAt = OffsetDateTime.of(2026, 10, 11, 9, 30, 0, 0, ZoneOffset.ofHours(1)),
        endedAt = OffsetDateTime.of(2026, 10, 11, 10, 30, 0, 0, ZoneOffset.ofHours(1)),
        startMs = 0,
        endMs = 3_600_000,
    ),
    atMs = atMs,
    sources = listOf(1),
    updatedAt = OffsetDateTime.of(2026, 10, 11, 12, 0, 0, 0, ZoneOffset.ofHours(1)),
)

private val previewItems = listOf(
    previewItem(1, "Export 5 sample recordings for the test, by Monday", ReviewView.State.WAITING),
    previewItem(2, "Send the shortlist to Daniel", ReviewView.State.WAITING),
    previewItem(3, "Share the sample files folder with Priya", ReviewView.State.ACCEPTED, atMs = 2_320_000),
    previewItem(4, "Call the landlord about the water bill", ReviewView.State.ACCEPTED, "Errands note", 280_000),
    previewItem(5, "Pick up the dry cleaning before 6", ReviewView.State.DONE, "Errands note", 120_000),
)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ReviewWaitingPreview() {
    RoudioTheme(darkTheme = false) { ReviewScreen(previewItems, emptySet(), false, LocalDate.of(2026, 10, 11), { _, _, _ -> }, { _, _ -> }) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ReviewWaitingDarkPreview() {
    RoudioTheme(darkTheme = true) { ReviewScreen(previewItems, emptySet(), false, LocalDate.of(2026, 10, 11), { _, _, _ -> }, { _, _ -> }) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ReviewAcceptedPreview() {
    RoudioTheme(darkTheme = false) {
        ReviewScreen(previewItems, setOf(5L), false, LocalDate.of(2026, 10, 11), { _, _, _ -> }, { _, _ -> }, initialTab = 1)
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ReviewCaughtUpPreview() {
    RoudioTheme(darkTheme = false) {
        ReviewScreen(previewItems.drop(2), emptySet(), false, LocalDate.of(2026, 10, 11), { _, _, _ -> }, { _, _ -> })
    }
}
