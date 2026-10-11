package com.boko.roudio.ui.conversation

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.boko.roudio.recorder.OggOpus
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.api.models.AskRequest
import com.boko.roudio.api.models.ConversationDetail
import com.boko.roudio.api.models.ConversationRename
import com.boko.roudio.api.models.ItemView
import com.boko.roudio.api.models.NoteRequest
import com.boko.roudio.api.models.SegmentEdit
import com.boko.roudio.api.models.SegmentView
import com.boko.roudio.api.models.SpeakerRename
import com.boko.roudio.data.Sync
import com.boko.roudio.store.TransferState
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import com.boko.roudio.ui.today.durationText
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Pane { Notes, Transcript, Ask }

/**
 * 06 Notes, 07 Transcript (07b editing, 07c rename speaker) and 08 Ask for one conversation,
 * with the player. Reads the cached detail, so it works offline; fetches it when it isn't cached.
 */
@Composable
fun ConversationRoute(app: RoudioApp, id: String, atMs: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cached by app.database.library().observeConversation(id).collectAsState(initial = null)
    var fetched by remember { mutableStateOf<ConversationDetail?>(null) }
    var offline by remember { mutableStateOf(false) }
    val detail = cached?.detail?.let { runCatching { Sync.decodeDetail(it) }.getOrNull() } ?: fetched
    LaunchedEffect(id) {
        if (cached?.detail == null) {
            fetched = try {
                app.sync.refreshConversation(id)
            } catch (e: IOException) {
                offline = true
                null
            }
        }
    }

    val player = remember { RecordingPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            position = player.positionMs
            playing = player.playing
            delay(250)
        }
    }

    val recordings by app.recordings.recordings.collectAsState(initial = emptyList())
    val localRecording = detail?.recording?.let { r ->
        recordings.firstOrNull { it.serial == r.recorderSerial && it.sessionId == r.sessionId }
    }
    val phoneFile = localRecording?.takeIf { it.transfer >= TransferState.OnPhone }
        ?.fileName?.let { File(app.recordingFiles.dir, it) }?.takeIf { it.exists() }

    // The phone's own copy when it has one (spec §2: the phone keeps every recording).
    LaunchedEffect(detail?.recording?.id, phoneFile?.path) {
        val recording = detail?.recording ?: return@LaunchedEffect
        val start = if (player.positionMs > 0) player.positionMs else atMs ?: detail.conversation.startMs
        val playable = phoneFile?.let { withContext(Dispatchers.IO) { OggOpus.audioOnly(it, context.cacheDir) } }
        val uri = playable?.let(Uri::fromFile) ?: try {
            app.api.library.getRecordingAudio(recording.id).body()?.url?.let(Uri::parse)
        } catch (e: IOException) {
            null
        }
        if (uri != null) player.load(uri, start)
    }
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    if (detail == null) {
        Message(if (offline) stringResource(R.string.not_on_this_phone) else "", onBack)
        return
    }
    var pane by rememberSaveable { mutableStateOf(if (atMs != null) Pane.Transcript else Pane.Notes) }
    val names = remember(detail) { ConversationModel.speakerNames(detail) }
    val chapters = remember(detail) { ConversationModel.chapters(detail) }
    val start = detail.conversation.startMs
    val length = detail.conversation.endMs - start
    val controls = PlayerControls(
        positionMs = position - start,
        lengthMs = length,
        playing = playing,
        speed = player.speed,
        chapter = ConversationModel.chapterAt(chapters, position)?.label,
        onToggle = player::toggle,
        onBack10 = { player.skip(-10_000) },
        onForward30 = { player.skip(30_000) },
        onSpeed = { player.nextSpeed() },
        onSeek = { relative -> player.seekTo(start + relative, play = false) },
    )
    val seek: (Long) -> Unit = { ms -> player.seekTo(ms) }
    val askFailed = stringResource(R.string.ask_failed)
    val askOffline = stringResource(R.string.ask_offline)
    val refresh: () -> Unit = { scope.launch { runCatching { app.sync.refreshConversation(id) } } }

    when (pane) {
        Pane.Notes -> NotesPane(
            detail, names, controls.copy(streaming = phoneFile == null), onBack, onPane = { pane = it }, onSeek = seek,
            phoneCopy = phoneFile != null,
            notOnPhone = phoneFile == null && localRecording?.transfer == TransferState.Uploaded,
            downloading = downloading,
            onMore = { menu = true },
            onDownload = {
                val local = localRecording ?: return@NotesPane
                downloading = true
                scope.launch {
                    downloadAgain(app, detail, local)
                    downloading = false
                }
            },
            onRetryBackup = { scope.launch { runCatching { app.api.backups.retryBackup(detail.recording.id) } } },
            onSaveNote = { text ->
                scope.launch {
                    runCatching { app.api.library.setNote(detail.conversation.id, NoteRequest(text)) }
                    refresh()
                }
            },
        )
        Pane.Transcript -> TranscriptPane(
            detail, names, chapters, position, controls, onBack, onPane = { pane = it }, onSeek = seek,
            onEdit = { segment, text ->
                scope.launch {
                    runCatching { app.api.library.editSegment(segment.id, SegmentEdit(text, OffsetDateTime.now(ZoneOffset.UTC))) }
                    refresh()
                }
            },
            onRename = { label, name ->
                val speaker = detail.speakers.firstOrNull { it.label == label } ?: return@TranscriptPane
                scope.launch {
                    runCatching { app.api.library.renameSpeaker(speaker.id, SpeakerRename(name)) }
                    refresh()
                }
            },
        )
        Pane.Ask -> AskPane(
            detail, names, onBack, onPane = { pane = it }, onSeek = seek,
            onAsk = { question, done ->
                scope.launch {
                    val error = try {
                        val response = app.api.library.askConversation(detail.conversation.id, AskRequest(question))
                        if (response.isSuccessful) null else askFailed
                    } catch (e: IOException) {
                        askOffline
                    }
                    refresh()
                    done(error)
                }
            },
            onClear = {
                scope.launch {
                    runCatching { app.api.library.clearAsk(detail.conversation.id) }
                    refresh()
                }
            },
        )
    }
    if (menu) {
        RecordingMenu(
            title = detail.conversation.title,
            phoneCopy = phoneFile != null,
            onRename = { title ->
                menu = false
                scope.launch {
                    runCatching { app.api.library.renameConversation(detail.conversation.id, ConversationRename(title)) }
                    refresh()
                }
            },
            onShareNotes = {
                menu = false
                context.startActivity(shareTextIntent(shareText(detail, names), detail.conversation.title))
            },
            onShareAudio = phoneFile?.let { file ->
                {
                    menu = false
                    context.startActivity(shareAudioIntent(context, file, detail.conversation.title))
                }
            },
            onWriteUpAgain = {
                menu = false
                scope.launch { runCatching { app.api.backups.reprocess(detail.recording.id) } }
            },
            onDelete = {
                menu = false
                deleting = true
            },
            onDismiss = { menu = false },
        )
    }
    if (deleting && phoneFile != null && localRecording != null) {
        DeleteFromPhoneSheet(
            sizeBytes = phoneFile.length(),
            backupState = detail.recording.backupState,
            onRetryBackup = {
                deleting = false
                scope.launch { runCatching { app.api.backups.retryBackup(detail.recording.id) } }
            },
            onDelete = {
                deleting = false
                // Only the owner deletes the phone copy, and only from here (decision 13).
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { phoneFile.delete() }
                    app.recordings.update(localRecording.copy(fileName = null, updatedAt = java.time.Instant.now()))
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

/** Downloads the server's copy back to this phone, kept only if its SHA-256 matches. */
private suspend fun downloadAgain(app: RoudioApp, detail: ConversationDetail, local: com.boko.roudio.store.Recording) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val url = app.api.library.getRecordingAudio(detail.recording.id).body()?.url ?: return@runCatching
            val target = app.recordingFiles.final(local)
            val partial = app.recordingFiles.partial(local)
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching
                partial.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (com.boko.roudio.transfer.Transfers.sha256(partial) != local.sha256) {
                partial.delete()
                return@runCatching
            }
            partial.renameTo(target)
            app.recordings.update(local.copy(fileName = target.name, updatedAt = java.time.Instant.now()))
        }
    }
}

/** Everything the player shows and does; times count from the conversation's start. */
private data class PlayerControls(
    val positionMs: Long,
    val lengthMs: Long,
    val playing: Boolean,
    val speed: Float,
    val chapter: String?,
    val onToggle: () -> Unit,
    val onBack10: () -> Unit,
    val onForward30: () -> Unit,
    val onSpeed: () -> Unit,
    val onSeek: (Long) -> Unit,
    val streaming: Boolean = false,
)

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    val c = RoudioTheme.colors
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().padding(12.dp)) {
        RoundButton(RoudioIcons.Back, stringResource(R.string.back), onBack)
        Text(text, style = roudioText(15.sp, 22.sp), color = c.muted, modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = c.raised, contentColor = c.ink),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) }
}

/** "Today 09:30 · 44 min" in the recording's own time zone. */
@Composable
private fun when_(detail: ConversationDetail): String {
    val zone = ZoneOffset.ofTotalSeconds(detail.recording.zoneOffsetSeconds)
    val started = detail.conversation.startedAt.atZoneSameInstant(zone)
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val day = when (started.toLocalDate()) {
        today -> stringResource(R.string.today)
        today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> started.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    val length = Duration.ofMillis(detail.conversation.endMs - detail.conversation.startMs)
    return stringResource(R.string.meta_day_time_length, day, "%02d:%02d".format(started.hour, started.minute), durationText(length))
}

@Composable
private fun Segmented(selected: Pane, onPane: (Pane) -> Unit) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(c.raised, RoundedCornerShape(25.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(Pane.Notes to R.string.tab_notes, Pane.Transcript to R.string.tab_transcript, Pane.Ask to R.string.tab_ask).forEach { (pane, label) ->
            val on = pane == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (on) c.primary else Color.Transparent, RoundedCornerShape(22.dp))
                    .clickable(role = Role.Tab) { onPane(pane) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = roudioText(14.sp, 18.sp, FontWeight.SemiBold), color = if (on) c.onPrimary else c.muted)
            }
        }
    }
}

/** A tappable time: "▶ 9:42" (with an optional speaker, as on Ask's sources). */
@Composable
private fun TimeChip(ms: Long, label: String? = null, filled: Boolean = true, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Row(
        Modifier
            .height(30.dp)
            .background(if (filled) c.raised else c.ground, RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 7.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RoudioIcons.PlaySmall, contentDescription = null, tint = c.ink, modifier = Modifier.size(12.dp))
        Text(ConversationModel.clock(ms), style = roudioText(12.sp, 16.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), color = c.ink)
        if (label != null) Text(label, style = roudioText(12.sp, 16.sp), color = c.muted)
    }
}

@Composable
private fun NotesPane(
    detail: ConversationDetail,
    names: Map<String, String>,
    controls: PlayerControls,
    onBack: () -> Unit,
    onPane: (Pane) -> Unit,
    onSeek: (Long) -> Unit,
    onRetryBackup: () -> Unit,
    onSaveNote: (String) -> Unit,
    phoneCopy: Boolean = true,
    notOnPhone: Boolean = false,
    downloading: Boolean = false,
    onMore: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    val c = RoudioTheme.colors
    val start = detail.conversation.startMs
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(RoudioIcons.Back, stringResource(R.string.back), onBack)
            RoundButton(RoudioIcons.More, stringResource(R.string.more_options), onMore)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                detail.conversation.title,
                style = roudioText(26.sp, 32.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
            val people = names.values.distinct().joinToString(", ")
            Text(
                if (people.isEmpty()) when_(detail) else stringResource(R.string.meta_with, when_(detail), people),
                style = roudioText(14.sp, 20.sp),
                color = c.muted,
            )
        }
        StateChips(detail, onRetryBackup, notOnPhone)
        if (notOnPhone) {
            Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                NotOnPhoneCard(detail.recording.backupState == "backed_up", downloading, onDownload)
            }
        }
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) { PlayerCard(controls) }
        Spacer(Modifier.height(16.dp))
        Segmented(Pane.Notes, onPane)
        Section(stringResource(R.string.summary)) {
            Text(detail.conversation.summary, style = roudioText(16.sp, 25.sp), color = c.ink)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                detail.summarySources.mapNotNull { ConversationModel.sourceTime(detail, it) }.distinct().forEach { at ->
                    TimeChip(at - start) { onSeek(at) }
                }
            }
        }
        ItemList(stringResource(R.string.decided), detail.items.filter { it.kind == ItemView.Kind.DECISION }, start, onSeek)
        ItemList(stringResource(R.string.follow_ups), detail.items.filter { it.kind == ItemView.Kind.TASK }, start, onSeek)
        ItemList(stringResource(R.string.key_moments), detail.items.filter { it.kind == ItemView.Kind.KEY_MOMENT }, start, onSeek)
        NoteSection(detail.note.orEmpty(), onSaveNote)
    }
}

@Composable
private fun StateChips(detail: ConversationDetail, onRetryBackup: () -> Unit, notOnPhone: Boolean = false) {
    val c = RoudioTheme.colors
    val recording = detail.recording
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val processing = when (recording.processingState) {
            "done" -> R.string.chip_written_up
            "failed" -> R.string.chip_writing_up_failed
            else -> R.string.chip_writing_up
        }
        StateChip(RoudioIcons.CheckCircle, stringResource(processing), warn = recording.processingState == "failed", onClick = null)
        val backupFailed = recording.backupState == "failed"
        if (notOnPhone && !backupFailed) {
            StateChip(RoudioIcons.Cloud, stringResource(R.string.chip_not_on_phone), warn = false, onClick = null)
            return@Row
        }
        StateChip(
            RoudioIcons.Cloud,
            stringResource(
                when (recording.backupState) {
                    "backed_up" -> R.string.chip_backed_up
                    "failed" -> R.string.chip_backup_failed
                    else -> R.string.chip_not_backed_up
                },
            ),
            warn = backupFailed,
            onClick = if (backupFailed) onRetryBackup else null,
        )
    }
}

@Composable
private fun StateChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, warn: Boolean, onClick: (() -> Unit)?) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .height(32.dp)
            .then(if (warn) Modifier.border(1.5.dp, c.buoy, shape) else Modifier.background(c.raised, shape))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
        Text(text, style = roudioText(13.sp, 16.sp, if (warn) FontWeight.SemiBold else FontWeight.Medium), color = c.ink)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = RoudioTheme.colors.muted)
        content()
    }
}

@Composable
private fun ItemList(title: String, items: List<ItemView>, start: Long, onSeek: (Long) -> Unit) {
    if (items.isEmpty()) return
    val c = RoudioTheme.colors
    Text(title, style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = c.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp))
    items.forEach { item ->
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        if (item.kind == ItemView.Kind.KEY_MOMENT) RoudioIcons.Diamond else RoudioIcons.Tick,
                        contentDescription = null,
                        tint = c.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(item.text, style = roudioText(15.sp, 21.sp, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                TimeChip(item.atMs - start) { onSeek(item.atMs) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        }
    }
}

@Composable
private fun NoteSection(note: String, onSave: (String) -> Unit) {
    val c = RoudioTheme.colors
    var text by rememberSaveable(note) { mutableStateOf(note) }
    Section(stringResource(R.string.your_note)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.add_note), style = roudioText(15.sp, 22.sp), color = c.muted) },
            textStyle = roudioText(15.sp, 22.sp).copy(color = c.ink),
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        if (text != note) {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.ink) }
        }
    }
}

/** The big navy player card on Notes (06). */
@Composable
private fun PlayerCard(controls: PlayerControls) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.heroCard, RoundedCornerShape(28.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            val fraction = if (controls.lengthMs > 0) (controls.positionMs.toFloat() / controls.lengthMs).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.size(96.dp)) {
                val s = size.width / 96f
                drawCircle(Color.White.copy(alpha = 0.18f), radius = 40f * s, style = Stroke(5f * s))
                drawArc(
                    c.buoy, -90f, 360f * fraction, false, topLeft = Offset(8f * s, 8f * s), size = Size(80f * s, 80f * s),
                    style = Stroke(5f * s, cap = StrokeCap.Round),
                )
            }
            Box(
                Modifier.size(60.dp).background(c.buoy, CircleShape).clickable(role = Role.Button, onClick = controls.onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (controls.playing) RoudioIcons.Pause else RoudioIcons.Play,
                    contentDescription = stringResource(if (controls.playing) R.string.pause else R.string.play),
                    tint = c.onBuoy,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                ConversationModel.clock(controls.positionMs),
                style = roudioText(28.sp, 32.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
                color = c.onHeroCard,
            )
            Text(
                when {
                    controls.streaming -> stringResource(R.string.player_streaming, ConversationModel.clock(controls.lengthMs))
                    controls.chapter != null -> stringResource(R.string.player_of_chapter, ConversationModel.clock(controls.lengthMs), controls.chapter)
                    else -> stringResource(R.string.player_of, ConversationModel.clock(controls.lengthMs))
                },
                style = roudioText(12.sp, 16.sp, FontWeight.Medium),
                color = c.onHeroCardMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(RoudioIcons.Back10, stringResource(R.string.back_10), controls.onBack10)
                CardButton(RoudioIcons.Forward30, stringResource(R.string.forward_30), controls.onForward30)
                Box(
                    Modifier.height(44.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button, onClick = controls.onSpeed).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.speed, speedText(controls.speed)), style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = c.onHeroCard)
                }
            }
        }
    }
}

private fun speedText(speed: Float) = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

@Composable
private fun CardButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Box(
        // 44 dp to the eye, 48 dp to the finger.
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(2.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = c.onHeroCard, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun CompactHeader(detail: ConversationDetail, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(RoudioIcons.Back, stringResource(R.string.back), onBack)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(detail.conversation.title, style = roudioText(16.sp, 20.sp, FontWeight.SemiBold), color = c.ink, maxLines = 1)
            val language = detail.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
            Text(listOfNotNull(when_(detail), language).joinToString(" · "), style = roudioText(12.sp, 16.sp), color = c.muted, maxLines = 1)
        }
        actions()
    }
}

@Composable
private fun TranscriptPane(
    detail: ConversationDetail,
    names: Map<String, String>,
    chapters: List<Chapter>,
    positionMs: Long,
    controls: PlayerControls,
    onBack: () -> Unit,
    onPane: (Pane) -> Unit,
    onSeek: (Long) -> Unit,
    onEdit: (SegmentView, String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    val c = RoudioTheme.colors
    val start = detail.conversation.startMs
    var editing by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SegmentView?>(null) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val current = ConversationModel.playing(detail.segments, positionMs)
    LaunchedEffect(current?.idx) {
        val index = detail.segments.indexOfFirst { it.idx == current?.idx }
        if (index >= 0 && controls.playing) list.animateScrollToItem(index + 2)
    }
    Box(Modifier.fillMaxSize().background(c.ground)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), state = list, contentPadding = PaddingValues(bottom = 140.dp)) {
            item {
                CompactHeader(detail, onBack) {
                    RoundButton(if (editing) RoudioIcons.Tick else RoudioIcons.Pencil, stringResource(if (editing) R.string.done_editing else R.string.edit_transcript)) {
                        editing = !editing
                    }
                }
                Spacer(Modifier.height(4.dp))
                Segmented(Pane.Transcript, onPane)
            }
            if (chapters.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val active = ConversationModel.chapterAt(chapters, positionMs)
                        chapters.forEach { chapter -> ChapterChip(chapter, chapter == active, start) { onSeek(chapter.atMs) } }
                    }
                }
            }
            itemsIndexed(detail.segments, key = { _, s -> s.id }) { index, segment ->
                // Only while the audio runs: paused at 0:00, the first line isn't "Playing".
                val isPlaying = controls.playing && segment.idx == current?.idx
                val speaker = names[segment.speaker] ?: segment.speaker
                Turn(
                    speaker = speaker,
                    speakerIndex = names.keys.indexOf(segment.speaker),
                    time = ConversationModel.clock(segment.startMs - start),
                    text = segment.text,
                    playing = isPlaying,
                    editing = editing,
                    onClick = { if (editing) editTarget = segment else onSeek(segment.startMs) },
                    onSpeaker = { renameTarget = segment.speaker },
                )
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) { DockedPlayer(controls) }
    }
    editTarget?.let { segment ->
        EditDialog(segment, onDismiss = { editTarget = null }) { text ->
            onEdit(segment, text)
            editTarget = null
        }
    }
    renameTarget?.let { label ->
        RenameDialog(names[label].orEmpty(), onDismiss = { renameTarget = null }) { name ->
            onRename(label, name)
            renameTarget = null
        }
    }
}

@Composable
private fun ChapterChip(chapter: Chapter, active: Boolean, start: Long, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .height(40.dp)
            .then(if (active) Modifier.background(c.primary, shape) else Modifier.border(1.dp, c.hairline, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ConversationModel.clock(chapter.atMs - start),
            style = roudioText(12.sp, 16.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
            color = if (active) c.onHeroCardMuted else c.muted,
        )
        Text(chapter.label, style = roudioText(13.sp, 16.sp, if (active) FontWeight.SemiBold else FontWeight.Medium), color = if (active) c.onPrimary else c.ink)
    }
}

/** One speaker turn (07): a playing turn gets the buoy tint and "Playing". */
@Composable
private fun Turn(
    speaker: String,
    speakerIndex: Int,
    time: String,
    text: String,
    playing: Boolean,
    editing: Boolean,
    onClick: () -> Unit,
    onSpeaker: () -> Unit,
) {
    val c = RoudioTheme.colors
    val dot = when (speakerIndex % 3) {
        0 -> c.ink
        1 -> c.muted.copy(alpha = 0.7f)
        else -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 20.dp, top = 18.dp).clickable(onClick = onClick)) {
        if (playing) Box(Modifier.width(4.dp).fillMaxHeight().background(c.buoy, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
        Column(
            Modifier
                .weight(1f)
                .then(
                    if (playing) {
                        Modifier.background(c.warningTint, RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)).padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
                    } else {
                        Modifier.padding(start = 24.dp)
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).background(dot, CircleShape).then(if (dot == Color.Transparent) Modifier.border(2.dp, c.ink, CircleShape) else Modifier),
                )
                Text(speaker, style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = c.ink, modifier = Modifier.clickable(role = Role.Button, onClick = onSpeaker))
                Text(time, style = roudioText(12.sp, 16.sp, FontWeight.Medium).copy(fontFeatureSettings = "tnum"), color = c.muted)
                if (playing) {
                    Spacer(Modifier.weight(1f))
                    Icon(RoudioIcons.Waveform, contentDescription = null, tint = c.buoyText, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.playing), style = roudioText(12.sp, 16.sp, FontWeight.SemiBold), color = c.buoyText)
                }
            }
            Text(
                text,
                style = roudioText(17.sp, 28.sp, if (playing) FontWeight.Medium else FontWeight.Normal),
                color = c.ink,
                modifier = if (editing) Modifier.border(1.dp, c.hairline, RoundedCornerShape(8.dp)).padding(6.dp) else Modifier,
            )
        }
    }
}

/** The docked player under the transcript (07). */
@Composable
private fun DockedPlayer(controls: PlayerControls) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(32.dp)
    Column(
        Modifier
            .width(388.dp)
            .background(c.sheet.copy(alpha = 0.96f), shape)
            .border(1.dp, c.sheetRim, shape)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ConversationModel.clock(controls.positionMs), style = roudioText(12.sp, 16.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"), color = c.ink, modifier = Modifier.width(44.dp))
            androidx.compose.material3.Slider(
                value = if (controls.lengthMs > 0) (controls.positionMs.toFloat() / controls.lengthMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { controls.onSeek((it * controls.lengthMs).toLong()) },
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.buoy, activeTrackColor = c.primary, inactiveTrackColor = c.ink.copy(alpha = 0.14f)),
            )
            Text(ConversationModel.clock(controls.lengthMs), style = roudioText(12.sp, 16.sp, FontWeight.Medium).copy(fontFeatureSettings = "tnum"), color = c.muted, modifier = Modifier.width(44.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controls.onSpeed) { Text(stringResource(R.string.speed, speedText(controls.speed)), style = roudioText(14.sp, 18.sp, FontWeight.SemiBold), color = c.ink) }
            IconButton(onClick = controls.onBack10) { Icon(RoudioIcons.Back10, stringResource(R.string.back_10), tint = c.ink, modifier = Modifier.size(24.dp)) }
            Box(Modifier.size(56.dp).background(c.primary, CircleShape).clickable(role = Role.Button, onClick = controls.onToggle), contentAlignment = Alignment.Center) {
                Icon(
                    if (controls.playing) RoudioIcons.Pause else RoudioIcons.Play,
                    contentDescription = stringResource(if (controls.playing) R.string.pause else R.string.play),
                    // Full contrast on the button in both themes (yellow on near-white was hard to see).
                    tint = c.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = controls.onForward30) { Icon(RoudioIcons.Forward30, stringResource(R.string.forward_30), tint = c.ink, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(56.dp))
        }
    }
}

@Composable
private fun EditDialog(segment: SegmentView, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(segment.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_segment_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                if (segment.originalText != segment.text) {
                    Text(stringResource(R.string.edit_segment_original, segment.originalText), style = roudioText(13.sp, 18.sp), color = RoudioTheme.colors.muted)
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(if (current.startsWith("Speaker ")) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_speaker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rename_speaker_body), style = roudioText(14.sp, 20.sp), color = RoudioTheme.colors.muted)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.speaker_name_hint)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AskPane(
    detail: ConversationDetail,
    names: Map<String, String>,
    onBack: () -> Unit,
    onPane: (Pane) -> Unit,
    onSeek: (Long) -> Unit,
    onAsk: (String, (String?) -> Unit) -> Unit,
    onClear: () -> Unit,
) {
    val c = RoudioTheme.colors
    val start = detail.conversation.startMs
    var question by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ask: (String) -> Unit = { q ->
        if (q.isNotBlank() && !busy) {
            busy = true
            error = null
            onAsk(q.trim()) { problem ->
                busy = false
                error = problem
                if (problem == null) question = ""
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().imePadding()) {
        CompactHeader(detail, onBack) { RoundButton(RoudioIcons.Restart, stringResource(R.string.ask_clear), onClear) }
        Spacer(Modifier.height(4.dp))
        Segmented(Pane.Ask, onPane)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().border(1.dp, c.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(RoudioIcons.Info, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.ask_info_title), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
                    Text(stringResource(R.string.ask_info_body), style = roudioText(13.sp, 18.sp), color = c.muted)
                }
            }
            detail.asks.forEach { turn ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        turn.question,
                        style = roudioText(16.sp, 22.sp),
                        color = c.onPrimary,
                        modifier = Modifier.background(c.primary, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                val sources = turn.sources.mapNotNull { idx -> detail.segments.firstOrNull { it.idx == idx } }
                if (turn.enough) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            pluralStringResource(R.plurals.ask_from_recording, sources.size, sources.size),
                            style = roudioText(13.sp, 18.sp, FontWeight.SemiBold),
                            color = c.muted,
                        )
                        Text(turn.answer, style = roudioText(17.sp, 26.sp), color = c.ink)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sources.forEach { s -> TimeChip(s.startMs - start, names[s.speaker]) { onSeek(s.startMs) } }
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().background(c.warningTint, RoundedCornerShape(18.dp)).border(1.dp, c.warningRim, RoundedCornerShape(18.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.ask_not_enough), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.buoyText)
                        Text(turn.answer, style = roudioText(15.sp, 22.sp), color = c.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sources.forEach { s -> TimeChip(s.startMs - start, names[s.speaker], filled = false) { onSeek(s.startMs) } }
                        }
                    }
                }
            }
            error?.let { Text(it, style = roudioText(14.sp, 20.sp), color = c.alert) }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(R.string.ask_suggest_decide, R.string.ask_suggest_next, R.string.ask_suggest_who).forEach { suggestion ->
                val text = stringResource(suggestion)
                Text(
                    text,
                    style = roudioText(14.sp, 18.sp, FontWeight.Medium),
                    color = c.ink,
                    modifier = Modifier.border(1.dp, c.hairline, RoundedCornerShape(24.dp)).clickable(role = Role.Button) { ask(text) }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).background(c.sheet, RoundedCornerShape(32.dp)).border(1.dp, c.sheetRim, RoundedCornerShape(32.dp)).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (question.isEmpty()) Text(stringResource(R.string.ask_hint), style = roudioText(16.sp, 22.sp), color = c.muted)
                BasicTextField(
                    value = question,
                    onValueChange = { question = it },
                    textStyle = roudioText(16.sp, 22.sp).copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.size(48.dp).background(if (question.isBlank() || busy) c.raised else c.primary, CircleShape).clickable(role = Role.Button) { ask(question) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(RoudioIcons.ArrowUp, stringResource(R.string.ask_send), tint = if (question.isBlank() || busy) c.muted else c.onPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}
