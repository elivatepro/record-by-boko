package com.boko.roudio.ui.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.api.models.BackupsView
import com.boko.roudio.api.models.RecordingSummary
import com.boko.roudio.api.models.Settings
import com.boko.roudio.store.TransferState
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.settings.BackTitleHeader
import com.boko.roudio.ui.settings.CouldNotLoad
import com.boko.roudio.ui.settings.Divider
import com.boko.roudio.ui.settings.Loading
import com.boko.roudio.ui.settings.SHORT_DAY
import com.boko.roudio.ui.settings.SectionLabel
import com.boko.roudio.ui.settings.SwitchRow
import com.boko.roudio.ui.settings.dashedBorder
import com.boko.roudio.ui.settings.fetch
import com.boko.roudio.ui.setup.BigButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import com.boko.roudio.ui.today.durationText
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.launch

/** A recording on 14: the server's summary, its name, and whether this phone still has it. */
data class BackupRow(val recording: RecordingSummary, val title: String?, val onPhone: Boolean)

/** 14 Backups & clean-up. */
@Composable
fun BackupsRoute(app: RoudioApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<BackupsView?>(null) }
    var titles by remember { mutableStateOf(emptyMap<UUID, String>()) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var retries by remember { mutableStateOf(emptyMap<UUID, RetryState>()) }
    var deletingSaveFailed by remember { mutableStateOf(false) }
    val local by app.recordings.recordings.collectAsState(initial = emptyList())
    val onPhone = local.filter { it.transfer >= TransferState.OnPhone }.map { it.serial to it.sessionId }.toSet()

    LaunchedEffect(attempt) {
        failed = false
        val loaded = fetch { app.api.backups.getBackups() }
        if (loaded == null) {
            failed = view == null
            return@LaunchedEffect
        }
        view = loaded
        val dao = app.database.library()
        titles = loaded.recordings.take(MAX_ROWS).mapNotNull { r ->
            dao.conversationsOf(r.id.toString()).minByOrNull { it.startMs }?.let { r.id to it.title }
        }.toMap()
    }

    val loaded = view
    if (loaded == null) {
        Column(Modifier.fillMaxSize().background(RoudioTheme.colors.ground).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            BackTitleHeader(stringResource(R.string.backups_title), onBack)
            if (failed) CouldNotLoad(stringResource(R.string.no_connection), onRetry = { attempt++ }) else Loading()
        }
        return
    }
    BackupsScreen(
        view = loaded,
        rows = loaded.recordings.take(MAX_ROWS).map {
            BackupRow(it, titles[it.id], (it.recorderSerial to it.sessionId) in onPhone)
        },
        retries = retries,
        deletingSaveFailed = deletingSaveFailed,
        onRetry = { recording ->
            retries = retries + (recording.id to RetryState.Working)
            scope.launch {
                val ok = retryBackup(app, recording.id)
                retries = retries + (recording.id to if (ok) RetryState.Started else RetryState.Failed)
                if (ok) attempt++
            }
        },
        onDeleting = { on ->
            val before = loaded
            view = loaded.copy(deletingEnabled = on)
            deletingSaveFailed = false
            scope.launch {
                val saved = fetch { app.api.backups.putSettings(Settings(deletingEnabled = on)) }
                if (saved == null) {
                    view = before
                    deletingSaveFailed = true
                } else {
                    view = view?.copy(deletingEnabled = saved.deletingEnabled)
                }
            }
        },
        onBack = onBack,
    )
}

/**
 * Backups & clean-up with the server's view: Drive and the counts, failed backups with what's
 * still safe, the "delete from the recorder" switch (off until the owner turns it on), and each
 * recording's three copies.
 */
@Composable
fun BackupsScreen(
    view: BackupsView,
    rows: List<BackupRow>,
    retries: Map<UUID, RetryState>,
    deletingSaveFailed: Boolean,
    onRetry: (RecordingSummary) -> Unit,
    onDeleting: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val c = RoudioTheme.colors
    var confirmDeleting by remember { mutableStateOf(false) }
    val failedRows = rows.filter { it.recording.backupState == "failed" }
    LazyColumn(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
    ) {
        item { BackTitleHeader(stringResource(R.string.backups_title), onBack) }
        item { DriveCard(view, Modifier.padding(top = 20.dp)) }
        if (failedRows.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.updates_needs_you), Modifier.padding(top = 28.dp, bottom = 12.dp)) }
            items(failedRows, key = { "failed-${it.recording.id}" }) { row ->
                FailedCard(row, retries[row.recording.id] ?: RetryState.Idle) { onRetry(row.recording) }
            }
        }
        item {
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.backups_clearing_title),
                    style = roudioText(22.sp, 28.sp, FontWeight.SemiBold),
                    color = c.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.backups_clearing_body), style = roudioText(15.sp, 21.sp), color = c.muted)
            }
        }
        item {
            SwitchRow(
                title = stringResource(R.string.backups_deleting_title),
                body = stringResource(if (view.deletingEnabled) R.string.backups_deleting_on else R.string.backups_deleting_off),
                checked = view.deletingEnabled,
                enabled = true,
                onChange = { on -> if (on) confirmDeleting = true else onDeleting(false) },
            )
            if (deletingSaveFailed) {
                Text(stringResource(R.string.backups_deleting_save_failed), style = roudioText(13.sp, 18.sp), color = c.alert)
            }
            Divider()
        }
        if (rows.isEmpty()) {
            item {
                Text(stringResource(R.string.backups_no_recordings), style = roudioText(15.sp, 22.sp), color = c.muted, modifier = Modifier.padding(top = 16.dp))
            }
        }
        items(rows, key = { it.recording.id }) { row -> CopiesRow(row, view.deletingEnabled) }
    }
    if (confirmDeleting) {
        AlertDialog(
            onDismissRequest = { confirmDeleting = false },
            icon = { Icon(RoudioIcons.Warning, contentDescription = null, tint = c.buoyText) },
            title = { Text(stringResource(R.string.backups_deleting_confirm_title)) },
            text = { Text(stringResource(R.string.backups_deleting_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleting = false
                    onDeleting(true)
                }) { Text(stringResource(R.string.backups_deleting_confirm), color = c.alert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleting = false }) { Text(stringResource(R.string.backups_deleting_keep_off)) }
            },
        )
    }
}

/** Drive's state and the three counts (backed up, waiting, failed). */
@Composable
private fun DriveCard(view: BackupsView, modifier: Modifier) {
    val c = RoudioTheme.colors
    Column(
        modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(24.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(c.ground, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (view.driveConnected) RoudioIcons.CloudCheck else OrganiseIcons.CloudAlert,
                    contentDescription = null,
                    tint = if (view.driveConnected) c.ink else c.alert,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (view.driveConnected) R.string.backups_drive_connected else R.string.backups_drive_not_connected),
                    style = roudioText(17.sp, 22.sp, FontWeight.SemiBold),
                    color = c.ink,
                )
                Text(
                    stringResource(if (view.driveConnected) R.string.backups_drive_folder else R.string.backups_drive_reconnect),
                    style = roudioText(14.sp, 20.sp),
                    color = c.muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile(view.backedUp, stringResource(R.string.backups_count_backed_up), alert = false)
            CountTile(view.waiting, stringResource(R.string.backups_count_waiting), alert = false)
            CountTile(view.failed, stringResource(R.string.backups_count_failed), alert = view.failed > 0)
        }
    }
}

@Composable
private fun RowScope.CountTile(count: Int, label: String, alert: Boolean) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .weight(1f)
            .background(c.ground, shape)
            .then(if (alert) Modifier.border(1.5.dp, c.alert, shape) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "$count",
            style = roudioText(24.sp, 30.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
            color = if (alert) c.alert else c.ink,
        )
        Text(label, style = roudioText(14.sp, 18.sp), color = if (alert) c.alert else c.muted)
    }
}

/** A failed backup: why, what's still safe, and the retry. */
@Composable
private fun FailedCard(row: BackupRow, retry: RetryState, onRetry: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.padding(bottom = 12.dp).fillMaxWidth().border(1.dp, c.hairline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(36.dp).background(c.alertTint, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(OrganiseIcons.CloudAlert, contentDescription = null, tint = c.alert, modifier = Modifier.size(20.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.backups_failed_title, recordingName(row)),
                    style = roudioText(17.sp, 22.sp, FontWeight.SemiBold),
                    color = c.ink,
                )
                Text(
                    row.recording.backupError ?: stringResource(R.string.backups_failed_no_reason),
                    style = roudioText(15.sp, 21.sp),
                    color = c.muted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(RoudioIcons.ShieldCheck, contentDescription = null, tint = c.ink, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Text(stringResource(R.string.backups_failed_safe), style = roudioText(15.sp, 21.sp), color = c.ink)
        }
        BigButton(
            stringResource(
                when (retry) {
                    RetryState.Working -> R.string.backups_retrying
                    RetryState.Started -> R.string.backups_retry_started
                    else -> R.string.backups_retry
                },
            ),
            onRetry,
            enabled = retry == RetryState.Idle || retry == RetryState.Failed,
        )
        if (retry == RetryState.Failed) {
            Text(stringResource(R.string.backups_retry_failed), style = roudioText(13.sp, 18.sp), color = c.alert)
        }
    }
}

/** One recording on its way off the recorder: phone, server and Drive, and where it stands. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopiesRow(row: BackupRow, deletingEnabled: Boolean) {
    val c = RoudioTheme.colors
    val recorder = BackupsModel.recorder(row.recording, deletingEnabled)
    Column {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    recordingName(row, withTime = true),
                    style = roudioText(17.sp, 22.sp, FontWeight.SemiBold),
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        when (recorder) {
                            RecorderCopy.Cleared -> R.string.backups_recorder_cleared
                            RecorderCopy.Clearing -> R.string.backups_recorder_clearing
                            RecorderCopy.ReadyToClear -> R.string.backups_recorder_ready
                            RecorderCopy.KeptDeletingOff -> R.string.backups_recorder_kept
                            RecorderCopy.StaysOnRecorder -> R.string.backups_recorder_stays
                            RecorderCopy.Waiting -> R.string.backups_recorder_waiting
                        },
                    ),
                    style = roudioText(15.sp, 20.sp, FontWeight.SemiBold),
                    color = when (recorder) {
                        RecorderCopy.Cleared, RecorderCopy.ReadyToClear, RecorderCopy.Clearing -> c.ink
                        RecorderCopy.StaysOnRecorder -> c.buoyText
                        else -> c.muted
                    },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.onPhone) {
                    CopyPill(stringResource(R.string.backups_copy_phone), CopyLook.Done)
                } else {
                    CopyPill(stringResource(R.string.backups_copy_not_on_phone), CopyLook.Pending)
                }
                if (BackupsModel.onServer(row.recording)) {
                    CopyPill(stringResource(R.string.backups_copy_server), CopyLook.Done)
                } else {
                    CopyPill(stringResource(R.string.backups_copy_server_uploading), CopyLook.Pending)
                }
                when (BackupsModel.drive(row.recording)) {
                    DriveCopy.Checked -> CopyPill(stringResource(R.string.backups_copy_drive_checked), CopyLook.Done)
                    DriveCopy.Failed -> CopyPill(stringResource(R.string.backups_copy_drive_failed), CopyLook.Failed)
                    DriveCopy.Uploading -> CopyPill(stringResource(R.string.backups_copy_drive_uploading), CopyLook.Pending)
                    DriveCopy.Checking -> CopyPill(stringResource(R.string.backups_copy_drive_checking), CopyLook.Pending)
                    DriveCopy.AfterWriteUp -> CopyPill(stringResource(R.string.backups_copy_drive_after_write_up), CopyLook.Pending)
                    DriveCopy.Waiting -> CopyPill(stringResource(R.string.backups_copy_drive_waiting), CopyLook.Pending)
                }
            }
        }
        Divider()
    }
}

private enum class CopyLook { Done, Failed, Pending }

/** Tick on fog when the copy is there; red cross when it failed; dashed while it's on its way. */
@Composable
private fun CopyPill(text: String, look: CopyLook) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val icon: ImageVector? = when (look) {
        CopyLook.Done -> OrganiseIcons.Check
        CopyLook.Failed -> OrganiseIcons.Close
        CopyLook.Pending -> null
    }
    Row(
        Modifier
            .height(32.dp)
            .then(
                when (look) {
                    CopyLook.Done -> Modifier.background(c.raised, shape)
                    CopyLook.Failed -> Modifier.border(1.5.dp, c.alert, shape)
                    CopyLook.Pending -> Modifier.dashedBorder(c.muted, 16.dp, 1.dp)
                },
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colour = when (look) {
            CopyLook.Done -> c.ink
            CopyLook.Failed -> c.alert
            CopyLook.Pending -> c.muted
        }
        if (icon != null) Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
        Text(text, style = roudioText(14.sp, 18.sp, FontWeight.SemiBold), color = colour)
    }
}

/** "Studio call", or "Recording · 1h 05m" before it's written up; with its local start time first. */
@Composable
private fun recordingName(row: BackupRow, withTime: Boolean = false): String {
    val recording = row.recording
    val name = row.title ?: stringResource(
        R.string.row_recording_length,
        durationText(Duration.ofMillis((recording.durationMs ?: 0).toLong())),
    )
    if (!withTime) return name
    val local = recording.startedAt.withOffsetSameInstant(ZoneOffset.ofTotalSeconds(recording.zoneOffsetSeconds))
    val time = "%02d:%02d".format(local.hour, local.minute)
    val day = local.toLocalDate()
    val start = if (day == LocalDate.now()) time else "${SHORT_DAY.format(day)} $time"
    return stringResource(R.string.backups_row_title, start, name)
}

private const val MAX_ROWS = 100

private fun previewRecording(n: Long, backup: String, cleanup: String, processing: String = "done") = RecordingSummary(
    id = UUID(0, n),
    recorderSerial = "8810000004F2A",
    sessionId = n,
    startedAt = OffsetDateTime.now(ZoneOffset.ofHours(1)).withHour(8 + n.toInt()).withMinute(30),
    zoneOffsetSeconds = 3600,
    durationMs = 1_800_000,
    transferState = "uploaded",
    processingState = processing,
    processingError = null,
    backupState = backup,
    backupError = if (backup == "failed") "Drive said the upload was incomplete." else null,
    cleanupState = cleanup,
    resultsVersion = 1,
)

private val previewRows = listOf(
    BackupRow(previewRecording(3, "backed_up", "cleared"), "Errands voice note", true),
    BackupRow(previewRecording(1, "failed", "blocked"), "Studio call", true),
    BackupRow(previewRecording(4, "waiting", "blocked", processing = "transcribing"), "Lunch at Osei’s", true),
)

private val previewView = BackupsView(
    driveConnected = true,
    deletingEnabled = false,
    recordings = previewRows.map { it.recording },
    backedUp = 3,
    waiting = 1,
    failed = 1,
    canCleanUp = 0,
    cleared = 1,
)

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun BackupsPreview() {
    RoudioTheme(darkTheme = false) { BackupsScreen(previewView, previewRows, emptyMap(), false, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun BackupsDarkPreview() {
    RoudioTheme(darkTheme = true) { BackupsScreen(previewView, previewRows, emptyMap(), false, {}, {}, {}) }
}
