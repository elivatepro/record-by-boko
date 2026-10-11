package com.boko.roudio.ui.setup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.recorder.FoundRecorder
import com.boko.roudio.recorder.RecorderModel
import com.boko.roudio.recorder.serialEnding
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.loopMillis
import com.boko.roudio.ui.rememberReduceMotion
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** 11f Setup · Finding recorder. [seconds] counts up while the search runs. */
@Composable
fun FindingScreen(seconds: Int, onStop: () -> Unit, onBack: () -> Unit) {
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Footnote(stringResource(R.string.finding_footnote))
                BigButton(stringResource(R.string.stop_looking), onStop, filled = false)
            }
        },
    ) {
        StepHeader(step = 5, onBack = onBack)
        SetupTitle(stringResource(R.string.finding_title), stringResource(R.string.finding_body))
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            SearchRadar()
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(size = 16.dp, stroke = 2.5.dp)
            Text(
                stringResource(R.string.finding_status, seconds),
                style = roudioText(14.sp, 20.sp, FontWeight.Medium),
                color = RoudioTheme.colors.ink,
            )
        }
    }
}

/** The 280 dp rings with a sweeping arc and the recorder in the middle (11f). */
@Composable
private fun SearchRadar() {
    val c = RoudioTheme.colors
    val still = rememberReduceMotion()
    val sweeping by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(loopMillis(2400), easing = LinearEasing)),
        label = "sweep",
    )
    val turn = if (still) 0f else sweeping
    Canvas(Modifier.size(280.dp)) {
        val s = size.width / 280f
        val centre = Offset(140f * s, 140f * s)
        drawCircle(c.hairline, radius = 132f * s, center = centre, style = Stroke(2f * s))
        drawCircle(c.hairline, radius = 96f * s, center = centre, style = Stroke(2f * s))
        drawCircle(c.raised, radius = 60f * s, center = centre)
        drawArc(
            c.primary,
            startAngle = turn - 90f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = Offset(8f * s, 8f * s),
            size = Size(264f * s, 264f * s),
            style = Stroke(6f * s, cap = StrokeCap.Round),
        )
        // The recorder.
        drawRoundRect(
            c.ink,
            topLeft = Offset(124f * s, 114f * s),
            size = Size(32f * s, 52f * s),
            cornerRadius = CornerRadius(9f * s),
            style = Stroke(3f * s),
        )
        drawLine(c.ink, Offset(134f * s, 126f * s), Offset(146f * s, 126f * s), 3f * s, StrokeCap.Round)
        drawCircle(c.ink, radius = 3.5f * s, center = Offset(140f * s, 152f * s))
    }
}

/** 11g Setup · Recorder found. Recorders linked to another app are greyed and open 12d. */
@Composable
fun FoundScreen(
    found: List<FoundRecorder>,
    onConnect: (FoundRecorder) -> Unit,
    onLinkedElsewhere: (FoundRecorder) -> Unit,
    onKeepLooking: () -> Unit,
    onBack: () -> Unit,
) {
    SetupScaffold(
        bottom = { BigButton(stringResource(R.string.keep_looking), onKeepLooking, filled = false) },
    ) {
        StepHeader(step = 5, onBack = onBack)
        SetupTitle(stringResource(R.string.found_title), stringResource(R.string.found_body))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            found.forEach { recorder ->
                if (recorder.linkedElsewhere) {
                    LinkedElsewhereCard(recorder, onClick = { onLinkedElsewhere(recorder) })
                } else {
                    FoundCard(recorder, onConnect = { onConnect(recorder) })
                }
            }
        }
    }
}

@Composable
private fun FoundCard(recorder: FoundRecorder, onConnect: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().border(1.5.dp, c.ink, shape).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecorderTile(background = c.raised, tint = c.ink)
        RowText(
            recorder.model.displayName,
            stringResource(R.string.found_nearby, recorder.serial.serialEnding),
            Modifier.weight(1f),
        )
        SmallButton(stringResource(R.string.connect), onConnect, filled = true, height = 44.dp)
    }
}

@Composable
private fun LinkedElsewhereCard(recorder: FoundRecorder, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.raised, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecorderTile(background = c.ground, tint = c.muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(recorder.model.displayName, style = roudioText(16.sp, 22.sp, FontWeight.Medium), color = c.muted)
            Text(
                stringResource(R.string.found_linked_elsewhere, recorder.serial.serialEnding),
                style = roudioText(14.sp, 20.sp),
                color = c.muted,
            )
        }
    }
}

@Composable
private fun RecorderTile(background: Color, tint: Color) {
    Box(Modifier.size(48.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(RoudioIcons.Recorder, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** 12a Error · Recorder not found. */
@Composable
fun NotFoundScreen(onLookAgain: () -> Unit, onMaybePlaud: () -> Unit, onBack: () -> Unit) {
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(R.string.look_again), onLookAgain)
                QuietButton(stringResource(R.string.maybe_plaud), onMaybePlaud)
            }
        },
        gap = 24.dp,
    ) {
        BackHeader(onBack)
        ErrorIcon(RoudioIcons.RecorderMissing)
        ErrorTitle(stringResource(R.string.not_found_title), stringResource(R.string.not_found_body))
        Column {
            CheckItem(stringResource(R.string.not_found_on_title), stringResource(R.string.not_found_on_body), last = false)
            CheckItem(stringResource(R.string.not_found_near_title), stringResource(R.string.not_found_near_body), last = false)
            CheckItem(stringResource(R.string.not_found_other_title), stringResource(R.string.not_found_other_body), last = true)
        }
    }
}

private val sampleFound = listOf(
    FoundRecorder("8810000004F2A", RecorderModel.NotePro, signal = -55),
    FoundRecorder("88200000091C0", RecorderModel.NotePinS, signal = -80, linkedElsewhere = true),
)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FindingPreview() {
    RoudioTheme(darkTheme = false) { FindingScreen(12, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FoundPreview() {
    RoudioTheme(darkTheme = false) { FoundScreen(sampleFound, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun FoundDarkPreview() {
    RoudioTheme(darkTheme = true) { FoundScreen(sampleFound, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun NotFoundPreview() {
    RoudioTheme(darkTheme = false) { NotFoundScreen({}, {}, {}) }
}
