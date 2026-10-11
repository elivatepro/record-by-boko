package com.boko.roudio.ui.conversation

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.boko.roudio.R
import com.boko.roudio.api.models.ConversationDetail
import com.boko.roudio.api.models.ItemView
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.setup.BigButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import java.io.File

/** 06c: the Recording's More sheet. [onShareAudio] is null when this phone has no copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingMenu(
    title: String,
    phoneCopy: Boolean,
    onRename: (String) -> Unit,
    onShareNotes: () -> Unit,
    onShareAudio: (() -> Unit)?,
    onWriteUpAgain: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = RoudioTheme.colors
    var renaming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = roudioText(14.sp, 20.sp, FontWeight.SemiBold), color = c.muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            MenuRow(RoudioIcons.Pencil, stringResource(R.string.rename_recording)) { renaming = true }
            MenuRow(RoudioIcons.Download, stringResource(R.string.share_notes), onClick = onShareNotes)
            if (onShareAudio != null) MenuRow(RoudioIcons.Note, stringResource(R.string.share_audio), onClick = onShareAudio)
            MenuRow(RoudioIcons.Restart, stringResource(R.string.write_up_again), stringResource(R.string.write_up_again_body), onClick = onWriteUpAgain)
            if (phoneCopy) {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(c.hairline))
                MenuRow(RoudioIcons.Bin, stringResource(R.string.delete_from_phone), danger = true, onClick = onDelete)
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_recording)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.rename_recording_hint)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) onRename(text.trim())
                    renaming = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MenuRow(icon: ImageVector, text: String, body: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) c.alert else c.ink, modifier = Modifier.size(24.dp))
        Column {
            Text(text, style = roudioText(17.sp, 22.sp), color = if (danger) c.alert else c.ink)
            if (body != null) Text(body, style = roudioText(13.sp, 18.sp), color = c.muted)
        }
    }
}

/** 06d: where every copy is, and a warning when Drive isn't backed up yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteFromPhoneSheet(sizeBytes: Long, backupState: String, onRetryBackup: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = RoudioTheme.colors
    val backedUp = backupState == "backed_up"
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.delete_title), style = roudioText(24.sp, 30.sp, FontWeight.SemiBold), color = c.ink)
            Text(stringResource(R.string.delete_body, megabytes(sizeBytes)), style = roudioText(15.sp, 22.sp), color = c.muted)
            Column(Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 4.dp)) {
                Where(stringResource(R.string.where_phone), stringResource(R.string.will_be_deleted), c.alert, last = false)
                Where(stringResource(R.string.where_server), stringResource(R.string.kept_plays_here), c.ink, last = false)
                Where(
                    stringResource(R.string.where_drive),
                    stringResource(
                        when (backupState) {
                            "backed_up" -> R.string.drive_backed_up
                            "failed" -> R.string.drive_failed
                            else -> R.string.drive_not_yet
                        },
                    ),
                    if (backedUp) c.ink else c.alert,
                    last = true,
                )
            }
            if (!backedUp) {
                Row(
                    Modifier.fillMaxWidth().background(c.warningTint, RoundedCornerShape(16.dp)).border(1.dp, c.warningRim, RoundedCornerShape(16.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(RoudioIcons.Warning, contentDescription = null, tint = c.buoyText, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.delete_warning), style = roudioText(15.sp, 22.sp), color = c.ink)
                }
                BigButton(stringResource(R.string.retry_backup_first), onRetryBackup)
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, c.alert),
                ) { Text(stringResource(R.string.delete_anyway), style = roudioText(16.sp, 20.sp, FontWeight.SemiBold), color = c.alert) }
            } else {
                BigButton(stringResource(R.string.delete_now), onDelete)
            }
        }
    }
}

@Composable
private fun Where(label: String, value: String, colour: androidx.compose.ui.graphics.Color, last: Boolean) {
    val c = RoudioTheme.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = roudioText(15.sp, 20.sp), color = c.ink)
            Text(value, style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = colour)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
    }
}

/** 06e: the audio was deleted from this phone; it streams from the server. */
@Composable
fun NotOnPhoneCard(backedUp: Boolean, downloading: Boolean, onDownload: () -> Unit) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.raised, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RoudioIcons.CloudCheck, contentDescription = null, tint = c.ink, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.deleted_title), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
            Text(stringResource(if (backedUp) R.string.deleted_body_drive else R.string.deleted_body_server), style = roudioText(13.sp, 18.sp), color = c.muted)
        }
        TextButton(onClick = onDownload, enabled = !downloading) {
            Text(stringResource(R.string.download), style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
        }
    }
}

private fun megabytes(bytes: Long): String = if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else "${(bytes / 1000).coerceAtLeast(1)} KB"

/** The transcript and notes as plain text, for "Share transcript and notes". */
fun shareText(detail: ConversationDetail, names: Map<String, String>): String = buildString {
    val start = detail.conversation.startMs
    appendLine(detail.conversation.title)
    appendLine()
    appendLine(detail.conversation.summary)
    listOf(ItemView.Kind.DECISION to "Decided", ItemView.Kind.TASK to "Follow-ups", ItemView.Kind.KEY_MOMENT to "Key moments").forEach { (kind, heading) ->
        val items = detail.items.filter { it.kind == kind }
        if (items.isNotEmpty()) {
            appendLine()
            appendLine(heading)
            items.forEach { appendLine("• ${it.text} (${ConversationModel.clock(it.atMs - start)})") }
        }
    }
    detail.note?.let {
        appendLine()
        appendLine("Your note")
        appendLine(it)
    }
    appendLine()
    appendLine("Transcript")
    detail.segments.forEach { appendLine("[${ConversationModel.clock(it.startMs - start)}] ${names[it.speaker] ?: it.speaker}: ${it.text}") }
}

fun shareTextIntent(text: String, title: String): Intent =
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text), title)

fun shareAudioIntent(context: Context, file: File, title: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("audio/ogg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, title)
}
