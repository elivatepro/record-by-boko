package com.boko.roudio.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** Where each step on 11h stands. */
enum class LinkStep { Done, Running, Waiting }

/** 11h Setup · Linking. [model] is the display name without "Plaud", e.g. "Note Pro". */
@Composable
fun LinkingScreen(
    model: String,
    bluetooth: LinkStep,
    roudio: LinkStep,
    readings: LinkStep,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    SetupScaffold(
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Footnote(stringResource(R.string.linking_footnote))
                BigButton(stringResource(R.string.cancel), onCancel, filled = false)
            }
        },
    ) {
        StepHeader(step = 6, onBack = onBack)
        SetupTitle(stringResource(R.string.linking_title, model), stringResource(R.string.linking_body))
        Column(Modifier.padding(vertical = 8.dp)) {
            StepRow(
                if (bluetooth == LinkStep.Done) stringResource(R.string.linking_bluetooth) else stringResource(R.string.linking_bluetooth_pending),
                null,
                bluetooth,
                last = false,
            )
            StepRow(stringResource(R.string.linking_roudio), stringResource(R.string.linking_roudio_body), roudio, last = false)
            StepRow(stringResource(R.string.linking_readings), null, readings, last = true)
        }
    }
}

@Composable
private fun StepRow(title: String, detail: String?, step: LinkStep, last: Boolean) {
    val c = RoudioTheme.colors
    ListRow(last = last, verticalPadding = 14.dp, centered = true) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (step) {
                LinkStep.Done -> CheckBadge(24.dp)
                LinkStep.Running -> Spinner(size = 24.dp, stroke = 3.dp)
                LinkStep.Waiting -> Box(Modifier.size(18.dp).border(2.dp, c.muted.copy(alpha = 0.4f), CircleShape))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = roudioText(
                    16.sp,
                    22.sp,
                    when (step) {
                        LinkStep.Done -> FontWeight.Medium
                        LinkStep.Running -> FontWeight.SemiBold
                        LinkStep.Waiting -> FontWeight.Normal
                    },
                ),
                color = if (step == LinkStep.Waiting) c.muted else c.ink,
            )
            if (detail != null && step == LinkStep.Running) {
                Text(detail, style = roudioText(13.sp, 18.sp), color = c.muted)
            }
        }
    }
}

/** A reading on 11i: label on the left, value on the right. */
data class Reading(val label: String, val value: String)

/** 11i Setup · Linked. */
@Composable
fun LinkedScreen(model: String, readings: List<Reading>, onDone: () -> Unit) {
    val c = RoudioTheme.colors
    SetupScaffold(
        bottom = {
            Footnote(stringResource(R.string.linked_footnote))
            BigButton(stringResource(R.string.go_to_today), onDone)
        },
    ) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { LinkedMark() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.linked_title, model),
                style = roudioText(30.sp, 36.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
                color = c.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Text(
                stringResource(R.string.linked_body),
                style = roudioText(16.sp, 24.sp),
                color = c.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.raised, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            readings.forEachIndexed { i, reading ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(reading.label, style = roudioText(14.sp, 20.sp), color = c.muted, modifier = Modifier.weight(1f))
                        Text(reading.value, style = roudioText(14.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
                    }
                    if (i < readings.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
                }
            }
        }
    }
}

/** The thick ring with a tick and the buoy dot (11i). */
@Composable
private fun LinkedMark() {
    val c = RoudioTheme.colors
    Canvas(Modifier.size(120.dp)) {
        val s = size.width / 120f
        drawCircle(c.ink, radius = 48f * s, center = Offset(60f * s, 60f * s), style = Stroke(12f * s))
        drawCircle(c.ground, radius = 16f * s, center = Offset(94f * s, 26f * s))
        drawCircle(c.buoy, radius = 13f * s, center = Offset(94f * s, 26f * s))
        val tick = Path().apply {
            moveTo(42f * s, 61f * s)
            lineTo(54f * s, 73f * s)
            lineTo(78f * s, 48f * s)
        }
        drawPath(tick, c.ink, style = Stroke(8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LinkingPreview() {
    RoudioTheme(darkTheme = false) {
        LinkingScreen("Note Pro", LinkStep.Done, LinkStep.Running, LinkStep.Waiting, {}, {})
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LinkedPreview() {
    RoudioTheme(darkTheme = false) {
        LinkedScreen(
            "Note Pro",
            listOf(Reading("Battery", "64% · not charging"), Reading("Free space", "58 of 64 GB"), Reading("Recording now", "Yes, since 14:10")),
            {},
        )
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun LinkedDarkPreview() {
    RoudioTheme(darkTheme = true) {
        LinkedScreen("Note Pro", listOf(Reading("Battery", "64% · not charging")), {})
    }
}
