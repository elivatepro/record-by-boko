package com.boko.roudio.ui.conversation

import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.store.ProcessingState
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.setup.BigButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import com.boko.roudio.ui.today.durationText
import java.io.File
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A recording on the server that isn't written up: S3 (still writing up) or S8 (writing up
 * failed, with "Try again now"). The phone's copy plays meanwhile.
 */
@Composable
fun PendingRoute(app: RoudioApp, recordingId: Long, onBack: () -> Unit) {
    val c = RoudioTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordings by app.recordings.recordings.collectAsState(initial = emptyList())
    val recording = recordings.firstOrNull { it.id == recordingId }
    val player = remember { RecordingPlayer(context) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var retried by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            position = player.positionMs
            playing = player.playing
            delay(250)
        }
    }
    LaunchedEffect(recording?.fileName) {
        recording?.fileName?.let { File(app.recordingFiles.dir, it) }?.takeIf { it.exists() }?.let { player.load(Uri.fromFile(it), 0) }
    }
    Column(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = c.raised, contentColor = c.ink),
        ) { Icon(RoudioIcons.Back, contentDescription = stringResource(R.string.back), modifier = Modifier.size(22.dp)) }
        if (recording == null) return@Column
        val length = Duration.ofMillis(recording.durationMs ?: 0)
        val start = recording.startedAt.atOffset(ZoneOffset.ofTotalSeconds(recording.zoneOffsetSeconds)).toLocalTime()
        Text(
            stringResource(R.string.row_recording_length, durationText(length)),
            style = roudioText(26.sp, 32.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text("%02d:%02d".format(start.hour, start.minute), style = roudioText(14.sp, 20.sp), color = c.muted)
        PendingPlayer(position, length.toMillis(), playing, player)
        val failed = recording.processing == ProcessingState.Failed && !retried
        val quiet = recording.processing == ProcessingState.Done && !retried
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (failed) c.alertTint else c.raised, RoundedCornerShape(18.dp))
                .then(if (failed) Modifier.border(1.dp, c.alert.copy(alpha = 0.3f), RoundedCornerShape(18.dp)) else Modifier)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (failed) RoudioIcons.Alert else RoudioIcons.Notes, contentDescription = null, tint = if (failed) c.alert else c.ink, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when {
                            failed -> R.string.writing_up_failed_title
                            quiet -> R.string.nothing_said_title
                            else -> R.string.still_writing_up_title
                        },
                    ),
                    style = roudioText(15.sp, 20.sp, FontWeight.SemiBold),
                    color = c.ink,
                )
                Text(
                    recording.processingError?.takeIf { failed } ?: stringResource(
                        when {
                            failed -> R.string.writing_up_failed_body
                            quiet -> R.string.nothing_said_body
                            else -> R.string.still_writing_up_body
                        },
                    ),
                    style = roudioText(14.sp, 20.sp),
                    color = c.muted,
                )
            }
        }
        val serverId = recording.serverId
        if (failed && serverId != null) {
            BigButton(stringResource(R.string.try_again_now), onClick = {
                retried = true
                scope.launch { runCatching { app.api.backups.reprocess(UUID.fromString(serverId)) } }
            })
        }
        // No conversation found, and the owner thinks there was one: look again.
        if (quiet && !retried && serverId != null) {
            OutlinedButton(
                onClick = {
                    retried = true
                    scope.launch { runCatching { app.api.backups.reprocess(UUID.fromString(serverId)) } }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) { Text(stringResource(R.string.write_up_again), style = roudioText(16.sp, 20.sp, FontWeight.SemiBold), color = c.ink) }
        }
    }
}

@Composable
private fun PendingPlayer(positionMs: Long, lengthMs: Long, playing: Boolean, player: RecordingPlayer) {
    val c = RoudioTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.heroCard, RoundedCornerShape(28.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconButton(
            onClick = player::toggle,
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = c.buoy, contentColor = c.onBuoy),
        ) {
            Icon(
                if (playing) RoudioIcons.Pause else RoudioIcons.Play,
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                modifier = Modifier.size(26.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ConversationModel.clock(positionMs), style = roudioText(28.sp, 32.sp, FontWeight.SemiBold), color = c.onHeroCard)
            Text(stringResource(R.string.player_of, ConversationModel.clock(lengthMs)), style = roudioText(12.sp, 16.sp, FontWeight.Medium), color = c.onHeroCardMuted)
        }
    }
}
