package com.boko.roudio.ui.search

import android.database.SQLException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.store.ProcessingState
import com.boko.roudio.store.TransferState
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.nav.TAB_BAR_SPACE
import com.boko.roudio.ui.recorder.rememberNow
import com.boko.roudio.ui.settings.BrandHeader
import com.boko.roudio.ui.settings.Divider
import com.boko.roudio.ui.settings.EmptyState
import com.boko.roudio.ui.settings.Loading
import com.boko.roudio.ui.settings.PlayChip
import com.boko.roudio.ui.settings.SectionLabel
import com.boko.roudio.ui.settings.dayAndTime
import com.boko.roudio.ui.settings.fetch
import com.boko.roudio.ui.setup.BigButton
import com.boko.roudio.ui.setup.QuietButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Why results came from the phone's index rather than the server (S5). */
enum class Offline { NoInternet, ServerUnreachable }

/** What the screen shows under the field. */
sealed interface SearchState {
    data object Idle : SearchState

    data object Searching : SearchState

    data class Results(val query: String, val groups: List<ResultGroup>, val offline: Offline?) : SearchState
}

/** 05 Search, 05b results, S4 no results, S5 offline. */
@Composable
fun SearchRoute(app: RoudioApp, onOpenConversation: (id: String, atMs: Long?) -> Unit) {
    val context = LocalContext.current
    val recentStore = remember { RecentSearches(context) }
    var recent by remember { mutableStateOf(recentStore.load()) }
    var query by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf(TimeFilter.AnyTime) }
    var kinds by remember { mutableStateOf(emptySet<MatchKind>()) }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val online by app.network.online.collectAsState()
    val recordings by app.recordings.recordings.collectAsState(initial = emptyList())
    val writingUp = recordings.count {
        it.transfer == TransferState.Uploaded && it.processing != ProcessingState.Done && it.processing != ProcessingState.Failed
    }

    LaunchedEffect(query.trim(), online) {
        val q = query.trim()
        if (q.isEmpty()) {
            state = SearchState.Idle
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        if (state !is SearchState.Results) state = SearchState.Searching
        state = search(app, q, online)
    }

    SearchScreen(
        query = query,
        state = state,
        recent = recent,
        time = time,
        kinds = kinds,
        writingUp = writingUp,
        now = rememberNow(),
        onQuery = { query = it },
        onSubmit = { if (it.isNotBlank()) recent = recentStore.add(it) },
        onClearRecent = { recent = recentStore.clear() },
        onTime = { time = time.next() },
        onKind = { kind -> kinds = if (kind in kinds) kinds - kind else kinds + kind },
        onClearFilters = {
            time = TimeFilter.AnyTime
            kinds = emptySet()
        },
        onOpen = { id, atMs ->
            recent = recentStore.add(query)
            onOpenConversation(id, atMs)
        },
    )
}

/** Asks the server when online; otherwise, or when it can't be reached, the phone's own index. */
private suspend fun search(app: RoudioApp, q: String, online: Boolean): SearchState.Results {
    if (online) {
        fetch { app.api.organise.search(q) }?.let { return SearchState.Results(q, SearchModel.fromServer(it), offline = null) }
    }
    val offline = if (online) Offline.ServerUnreachable else Offline.NoInternet
    val fts = SearchModel.ftsQuery(q) ?: return SearchState.Results(q, emptyList(), offline)
    val dao = app.database.library()
    val entries = try {
        dao.search(fts)
    } catch (e: SQLException) {
        emptyList()
    }
    val conversations = entries.map { it.conversationId }.distinct().mapNotNull { dao.conversation(it) }.associateBy { it.id }
    return SearchState.Results(q, SearchModel.fromLocal(entries, conversations, SearchModel.terms(q)), offline)
}

/** The Search tab, drawn from its state. */
@Composable
fun SearchScreen(
    query: String,
    state: SearchState,
    recent: List<String>,
    time: TimeFilter,
    kinds: Set<MatchKind>,
    writingUp: Int,
    now: Instant,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onClearRecent: () -> Unit,
    onTime: () -> Unit,
    onKind: (MatchKind) -> Unit,
    onClearFilters: () -> Unit,
    onOpen: (id: String, atMs: Long?) -> Unit,
) {
    val c = RoudioTheme.colors
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
        BrandHeader()
        SearchField(query, onQuery, onSubmit)
        FilterChips(time, kinds, onTime, onKind)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = TAB_BAR_SPACE),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                SearchState.Idle -> item { StartPanel(recent, onPick = { onQuery(it); onSubmit(it) }, onClearRecent) }
                SearchState.Searching -> item { Loading() }
                is SearchState.Results -> {
                    val shown = SearchModel.filter(state.groups, kinds, time, now, zone)
                    if (state.offline != null) item { OfflineBanner(state.offline) }
                    when {
                        shown.isNotEmpty() -> {
                            val matches = shown.sumOf { it.hits.size }
                            item {
                                Text(
                                    pluralStringResource(
                                        R.plurals.search_matches,
                                        matches,
                                        matches,
                                        pluralStringResource(R.plurals.search_in_recordings, shown.size, shown.size),
                                    ),
                                    style = roudioText(14.sp, 20.sp, FontWeight.Medium),
                                    color = c.muted,
                                )
                            }
                            items(shown, key = { it.conversationId }) { group ->
                                if (state.offline != null) {
                                    FlatGroup(group, now, onOpen)
                                } else {
                                    CardGroup(group, now, onOpen)
                                }
                            }
                        }
                        state.groups.isNotEmpty() -> item {
                            Column(Modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                EmptyState(
                                    OrganiseIcons.Search,
                                    stringResource(R.string.search_filtered_out_title),
                                    stringResource(R.string.search_filtered_out_body),
                                )
                                QuietButton(stringResource(R.string.search_clear_filters), onClearFilters)
                            }
                        }
                        else -> item { NoResults(state, writingUp, onRetryWith = { onQuery(it); onSubmit(it) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onSubmit: (String) -> Unit) {
    val c = RoudioTheme.colors
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .background(if (focused) c.ground else c.raised, shape)
            .then(if (focused) Modifier.border(2.dp, c.ink, shape) else Modifier)
            .padding(start = 20.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OrganiseIcons.Search, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_hint), style = roudioText(17.sp, 22.sp), color = c.muted, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = roudioText(17.sp, 22.sp, FontWeight.Medium).copy(color = c.ink),
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSubmit(query)
                    focus.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (query.isNotEmpty()) {
            // Navy when there are results to read; quieter while typing (05b vs S4).
            Box(
                Modifier
                    .size(44.dp)
                    .background(if (focused) c.raised else c.primary, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.search_clear)) { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    OrganiseIcons.Close,
                    contentDescription = stringResource(R.string.search_clear),
                    tint = if (focused) c.ink else c.onPrimary,
                    modifier = Modifier.size(if (focused) 18.dp else 22.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterChips(time: TimeFilter, kinds: Set<MatchKind>, onTime: () -> Unit, onKind: (MatchKind) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The time chip always shows its choice; tapping moves to the next one.
        FilterChip(
            stringResource(
                when (time) {
                    TimeFilter.AnyTime -> R.string.search_any_time
                    TimeFilter.ThisWeek -> R.string.search_this_week
                    TimeFilter.Today -> R.string.today
                },
            ),
            selected = true,
            onClick = onTime,
        )
        FilterChip(stringResource(R.string.search_transcripts), MatchKind.Transcript in kinds) { onKind(MatchKind.Transcript) }
        FilterChip(stringResource(R.string.search_notes), MatchKind.Note in kinds) { onKind(MatchKind.Note) }
        FilterChip(stringResource(R.string.search_summaries), MatchKind.Summary in kinds) { onKind(MatchKind.Summary) }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Box(
        Modifier
            .height(40.dp)
            .background(if (selected) c.primary else c.raised, RoundedCornerShape(20.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = if (selected) c.onPrimary else c.ink)
    }
}

/** 05 before typing: recent searches, or a line about what search covers. */
@Composable
private fun StartPanel(recent: List<String>, onPick: (String) -> Unit, onClearRecent: () -> Unit) {
    val c = RoudioTheme.colors
    if (recent.isEmpty()) {
        Text(
            stringResource(R.string.search_start),
            style = roudioText(15.sp, 22.sp),
            color = c.muted,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.search_recent), Modifier.weight(1f))
            Text(
                stringResource(R.string.search_clear_recent),
                style = roudioText(14.sp, 20.sp, FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.clickable(role = Role.Button, onClick = onClearRecent).padding(vertical = 12.dp, horizontal = 4.dp),
            )
        }
        recent.forEach { text ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(text) }.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(OrganiseIcons.History, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                Text(text, style = roudioText(17.sp, 22.sp), color = c.ink, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(OrganiseIcons.ArrowUpRight, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
            }
            Divider()
        }
    }
}

/** S5: results come from the phone, and recordings still being written up aren't in them. */
@Composable
private fun OfflineBanner(offline: Offline) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RoudioIcons.WifiOff, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
        Text(
            stringResource(if (offline == Offline.NoInternet) R.string.search_offline else R.string.search_server_unreachable),
            style = roudioText(14.sp, 20.sp),
            color = c.ink,
        )
    }
}

/** A 05b card: the conversation, then each match on its own white tile. */
@Composable
private fun CardGroup(group: ResultGroup, now: Instant, onOpen: (String, Long?) -> Unit) {
    val c = RoudioTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GroupTitle(group, now, Modifier.padding(bottom = 2.dp)) { onOpen(group.conversationId, null) }
        group.hits.forEach { hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.ground, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onOpen(group.conversationId, openAt(hit)) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HitBody(group, hit, onOpen)
            }
        }
    }
}

/** S5's flatter layout: the conversation over hairline-separated matches. */
@Composable
private fun FlatGroup(group: ResultGroup, now: Instant, onOpen: (String, Long?) -> Unit) {
    Column {
        GroupTitle(group, now, Modifier.padding(vertical = 8.dp)) { onOpen(group.conversationId, null) }
        Divider()
        group.hits.forEach { hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpen(group.conversationId, openAt(hit)) }
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HitBody(group, hit, onOpen)
            }
            Divider()
        }
    }
}

@Composable
private fun GroupTitle(group: ResultGroup, now: Instant, modifier: Modifier, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.title,
            style = roudioText(17.sp, 22.sp, FontWeight.SemiBold),
            color = c.ink,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Text(dayAndTime(group.startedAt, now), style = roudioText(13.sp, 18.sp).copy(fontFeatureSettings = "tnum"), color = c.muted)
    }
}

@Composable
private fun HitBody(group: ResultGroup, hit: Hit, onOpen: (String, Long?) -> Unit) {
    val c = RoudioTheme.colors
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(
                when (hit.kind) {
                    MatchKind.Summary -> R.string.search_kind_summary
                    MatchKind.Note -> R.string.search_kind_note
                    MatchKind.Transcript -> R.string.search_kind_transcript
                },
            ),
            style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
            color = c.muted,
            modifier = Modifier.weight(1f),
        )
        if (hit.kind == MatchKind.Transcript) PlayChip(hit.atMs) { onOpen(group.conversationId, hit.atMs) }
    }
    Text(highlighted(hit), style = roudioText(16.sp, 22.sp), color = c.ink)
}

/** The snippet with its matches highlighted: pale buoy in light, solid buoy with Night text in dark. */
@Composable
private fun highlighted(hit: Hit): AnnotatedString {
    val c = RoudioTheme.colors
    val dark = c.ground.luminance() < 0.5f
    val style = SpanStyle(
        background = if (dark) c.buoy else c.buoy.copy(alpha = 0.3f),
        color = if (dark) c.onBuoy else c.ink,
        fontWeight = FontWeight.SemiBold,
    )
    return buildAnnotatedString {
        if (hit.kind == MatchKind.Transcript) append("“")
        SearchModel.spans(hit.snippet).forEach { span ->
            if (span.match) withStyle(style) { append(span.text) } else append(span.text)
        }
        if (hit.kind == MatchKind.Transcript) append("”")
    }
}

/** Transcript matches open at their line; summaries and notes open at the top. */
private fun openAt(hit: Hit): Long? = if (hit.kind == MatchKind.Transcript) hit.atMs else null

/** S4: nothing found, a shorter search to try, and what isn't searchable yet. */
@Composable
private fun NoResults(state: SearchState.Results, writingUp: Int, onRetryWith: (String) -> Unit) {
    val c = RoudioTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EmptyState(
            OrganiseIcons.Search,
            stringResource(R.string.search_nothing_for, state.query),
            stringResource(if (state.offline == null) R.string.search_nothing_body else R.string.search_nothing_body_offline),
        )
        SearchModel.fewerWords(state.query)?.let { shorter ->
            Box(Modifier.padding(horizontal = 4.dp)) {
                BigButton(stringResource(R.string.search_instead, shorter), { onRetryWith(shorter) })
            }
        }
        if (writingUp > 0) {
            Text(
                pluralStringResource(R.plurals.search_still_writing_up, writingUp, writingUp),
                style = roudioText(14.sp, 20.sp),
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

private const val DEBOUNCE_MS = 300L

private val previewNow = Instant.parse("2026-10-11T13:32:00Z")
private val previewGroups = listOf(
    ResultGroup(
        "a",
        "Studio call",
        previewNow.minusSeconds(4 * 3600),
        0,
        listOf(
            Hit(MatchKind.Summary, "Agreed a [[provider]] [[shortlist]] of three services to test.", 0),
            Hit(MatchKind.Transcript, "Can we keep the [[provider]] [[shortlist]] to three?", 582_000),
        ),
    ),
    ResultGroup(
        "b",
        "Breakfast planning",
        previewNow.minusSeconds(5 * 3600 + 1500),
        0,
        listOf(Hit(MatchKind.Note, "Ask Daniel for the [[provider]] [[shortlist]] by Monday.", 0)),
    ),
)

@Composable
private fun Preview(state: SearchState, query: String) {
    SearchScreen(
        query, state, listOf("provider shortlist", "Kumasi flights"), TimeFilter.AnyTime, emptySet(), 1, previewNow,
        {}, {}, {}, {}, {}, {}, { _, _ -> },
    )
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SearchStartPreview() {
    RoudioTheme(darkTheme = false) { Preview(SearchState.Idle, "") }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SearchResultsPreview() {
    RoudioTheme(darkTheme = false) { Preview(SearchState.Results("provider shortlist", previewGroups, null), "provider shortlist") }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SearchResultsDarkPreview() {
    RoudioTheme(darkTheme = true) { Preview(SearchState.Results("provider shortlist", previewGroups, null), "provider shortlist") }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SearchOfflinePreview() {
    RoudioTheme(darkTheme = false) {
        Preview(SearchState.Results("provider", previewGroups.take(1), Offline.NoInternet), "provider")
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun SearchNothingDarkPreview() {
    RoudioTheme(darkTheme = true) { Preview(SearchState.Results("catering quote", emptyList(), null), "catering quote") }
}
