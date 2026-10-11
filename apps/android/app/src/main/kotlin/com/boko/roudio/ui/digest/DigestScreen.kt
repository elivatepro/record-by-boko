package com.boko.roudio.ui.digest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.boko.roudio.api.models.DigestView
import com.boko.roudio.data.Sync
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.conversation.ConversationModel
import com.boko.roudio.ui.recorder.rememberNow
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import com.boko.roudio.ui.today.Dial
import com.boko.roudio.ui.today.TodayModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 09 Daily digest, 09c quiet day, 09d failed. Moments open the conversation at that moment. */
@Composable
fun DigestRoute(app: RoudioApp, day: String, onOpenConversation: (String, Long?) -> Unit, onBack: () -> Unit) {
    val c = RoudioTheme.colors
    val date = LocalDate.parse(day)
    val row by app.database.library().observeDigest(day).collectAsState(initial = null)
    val digest = remember(row) { row?.json?.let { runCatching { Sync.decodeDigest(it) }.getOrNull() } }
    val recordings by app.recordings.recordings.collectAsState(initial = emptyList())
    val conversations by app.database.library().observeConversations().collectAsState(initial = emptyList())
    val recorder by app.recorder.state.collectAsState()
    val now = rememberNow()
    val offset = ZoneId.systemDefault().rules.getOffset(now)
    val model = remember(recordings, conversations, day) {
        TodayModel.build(recordings, recorder, null, date, now, offset, conversations)
    }
    val starts = remember(conversations) { conversations.associate { it.id to it.startMs } }

    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Box(Modifier.padding(start = 12.dp, top = 8.dp)) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = c.raised, contentColor = c.ink),
            ) { Icon(RoudioIcons.Back, contentDescription = stringResource(R.string.back), modifier = Modifier.size(22.dp)) }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
            Text(stringResource(R.string.daily_digest), style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = c.muted)
            Text(
                DateTimeFormatter.ofPattern("EEEE d MMM").format(date),
                style = roudioText(30.sp, 36.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                color = c.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        Row(Modifier.padding(start = 12.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Dial(model, now, diameter = 170.dp, centre = false)
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("${digest?.keyMoments ?: 0}", stringResource(R.string.key_moments).lowercase())
                Stat("${digest?.decisions ?: 0}", stringResource(R.string.decisions_word))
                digest?.let {
                    Stat(
                        stringResource(R.string.digest_of, it.recordingsDone, it.recordingsTotal),
                        stringResource(if (it.state == DigestView.State.UPDATING) R.string.recordings_so_far else R.string.recordings_word),
                    )
                }
            }
        }
        val overview = when (digest?.state) {
            null -> stringResource(R.string.digest_waiting)
            DigestView.State.QUIET -> stringResource(R.string.digest_quiet_body)
            DigestView.State.FAILED -> stringResource(R.string.digest_failed_body)
            else -> digest.overview ?: stringResource(R.string.digest_waiting)
        }
        Text(
            overview,
            style = roudioText(20.sp, 28.sp),
            color = c.ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp).fillMaxWidth().background(c.raised, RoundedCornerShape(24.dp)).padding(24.dp),
        )
        if (!digest?.moments.isNullOrEmpty()) {
            Text(
                stringResource(R.string.moments),
                style = roudioText(20.sp, 26.sp, FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp),
            )
            digest!!.moments.forEach { moment ->
                val id = moment.conversationId.toString()
                val local = moment.at.atZoneSameInstant(ZoneId.systemDefault()).toLocalTime()
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenConversation(id, moment.atMs) }.padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(RoudioIcons.Diamond, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                        Text(
                            "%02d:%02d".format(local.hour, local.minute),
                            style = roudioText(15.sp, 20.sp, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum"),
                            color = c.ink,
                        )
                        Text(moment.text, style = roudioText(16.sp, 22.sp, FontWeight.Medium), color = c.ink, modifier = Modifier.weight(1f))
                        val relative = moment.atMs - (starts[id] ?: moment.atMs)
                        Row(
                            Modifier.height(30.dp).background(c.raised, RoundedCornerShape(15.dp)).padding(start = 7.dp, end = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(RoudioIcons.PlaySmall, contentDescription = null, tint = c.ink, modifier = Modifier.size(12.dp))
                            Text(ConversationModel.clock(relative), style = roudioText(12.sp, 16.sp, FontWeight.SemiBold), color = c.ink)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val c = RoudioTheme.colors
    Column {
        Text(value, style = roudioText(26.sp, 30.sp, FontWeight.SemiBold), color = c.ink)
        Text(label, style = roudioText(14.sp, 18.sp), color = c.muted)
    }
}
