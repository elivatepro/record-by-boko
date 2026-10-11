package com.boko.roudio.ui.setup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.ui.RoudioIcons
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/** Shared layout and pieces of the setup flow (11a–11i) and its error screens (12a–12e). */

/** Scrolling content on top, actions pinned to the bottom, as on every setup artboard. */
@Composable
fun SetupScaffold(
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = RoudioTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 28.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = bottom)
    }
}

const val SETUP_STEPS = 6

@Composable
fun StepHeader(step: Int, onBack: (() -> Unit)?, of: Int = SETUP_STEPS) {
    val c = RoudioTheme.colors
    Row(
        Modifier.height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconButton(
            onClick = { onBack?.invoke() },
            enabled = onBack != null,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = c.raised,
                contentColor = c.ink,
                disabledContainerColor = c.raised,
                disabledContentColor = c.muted.copy(alpha = 0.5f),
            ),
        ) {
            Icon(RoudioIcons.Back, contentDescription = stringResource(R.string.back), Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.step_of, step, of),
                style = roudioText(13.sp, 18.sp, FontWeight.Medium),
                color = c.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(of) { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(if (i < step) c.primary else c.hairline, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
fun SetupTitle(title: String, body: String?) {
    val c = RoudioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = roudioText(30.sp, 36.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.02).em),
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) Text(body, style = roudioText(16.sp, 24.sp), color = c.muted)
    }
}

/** The muted 13 sp line above the bottom buttons. */
@Composable
fun Footnote(text: String, center: Boolean = true) {
    Text(
        text,
        style = roudioText(13.sp, 19.sp),
        color = RoudioTheme.colors.muted,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (center) 8.dp else 0.dp),
    )
}

/** Full-width 56 dp pill. [filled] = navy; otherwise the fog secondary style. */
@Composable
fun BigButton(text: String, onClick: () -> Unit, filled: Boolean = true, enabled: Boolean = true) {
    val c = RoudioTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) c.primary else c.raised,
            contentColor = if (filled) c.onPrimary else c.ink,
            disabledContainerColor = (if (filled) c.primary else c.raised).copy(alpha = 0.5f),
            disabledContentColor = if (filled) c.onPrimary else c.ink,
        ),
    ) {
        Text(text, style = roudioText(16.sp, 20.sp, FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

/** Plain text button under a [BigButton] (11e "It was never linked"). */
@Composable
fun QuietButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Text(text, style = roudioText(15.sp, 20.sp, FontWeight.SemiBold), color = RoudioTheme.colors.ink)
    }
}

/** The 40 dp (or 44 dp) pill inside a row or card ("Allow", "Open battery settings"). */
@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val c = RoudioTheme.colors
    Button(
        onClick = onClick,
        // Drawn at the design's height; Material still gives it a 48 dp touch target.
        modifier = modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) c.primary else c.raised,
            contentColor = if (filled) c.onPrimary else c.ink,
        ),
    ) {
        Text(text, style = roudioText(14.sp, 18.sp, FontWeight.SemiBold))
    }
}

/** Icon in a 40 dp fog circle, as in the 11a and 11c rows. */
@Composable
fun IconTile(icon: ImageVector) {
    val c = RoudioTheme.colors
    Box(Modifier.size(40.dp).background(c.raised, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
    }
}

/** Title and muted line, the text half of every setup row. */
@Composable
fun RowText(title: String, body: String, modifier: Modifier = Modifier) {
    val c = RoudioTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = roudioText(16.sp, 22.sp, FontWeight.SemiBold), color = c.ink)
        Text(body, style = roudioText(14.sp, 20.sp), color = c.muted)
    }
}

/** A row in a list separated by hairlines (11a, 11c). */
@Composable
fun ListRow(
    last: Boolean,
    verticalPadding: Dp,
    centered: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val c = RoudioTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = if (centered) Alignment.CenterVertically else Alignment.Top,
            content = content,
        )
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
    }
}

/** Filled circle with a tick: "Allowed" (11c, 18 dp) and a finished item (11d, 24 dp). */
@Composable
fun CheckBadge(size: Dp) {
    val fill = RoudioTheme.colors.primary
    val tick = RoudioTheme.colors.onPrimary
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 16f
        drawCircle(fill, radius = 7f * s)
        val path = Path().apply {
            moveTo(5f * s, 8.2f * s)
            lineTo(7f * s, 10.2f * s)
            lineTo(11f * s, 6.1f * s)
        }
        drawPath(path, tick, style = Stroke(1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Empty ring: a step still to do (solid) or optional (dashed) on 11d. */
@Composable
fun TodoRing(dashed: Boolean) {
    val c = RoudioTheme.colors
    val colour = if (dashed) c.muted.copy(alpha = 0.65f) else c.ink
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(
            colour,
            radius = (size.width - stroke) / 2,
            style = Stroke(
                width = stroke,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            ),
        )
    }
}

/** The Roudio mark: ring with the buoy dot (11a header). */
@Composable
fun RoudioMark(modifier: Modifier = Modifier) {
    val c = RoudioTheme.colors
    Canvas(modifier.size(22.dp)) {
        val s = size.width / 22f
        drawCircle(c.ink, radius = 8f * s, center = Offset(11f * s, 11f * s), style = Stroke(3f * s))
        drawCircle(c.ground, radius = 3.35f * s, center = Offset(17.2f * s, 5.6f * s))
        drawCircle(c.buoy, radius = 2.6f * s, center = Offset(17.2f * s, 5.6f * s))
    }
}

/** Rounded card used on 11d: fog when done or optional, outlined when it's the one to do now. */
@Composable
fun StepCard(current: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (current) {
                    Modifier.border(1.5.dp, c.ink, shape)
                } else {
                    Modifier.background(c.raised, shape)
                },
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Warning callout (11e "Before you unlink"). */
@Composable
fun WarningCallout(title: String, body: String) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.warningTint, shape)
            .border(1.dp, c.warningRim, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(RoudioIcons.Warning, contentDescription = null, tint = c.buoyText, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = roudioText(14.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
            Text(body, style = roudioText(14.sp, 20.sp), color = c.ink)
        }
    }
}

/** Numbered instruction (11e). */
@Composable
fun NumberedStep(number: Int, text: String) {
    val c = RoudioTheme.colors
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = roudioText(13.sp, 16.sp, FontWeight.SemiBold), color = c.onPrimary)
        }
        Text(text, style = roudioText(15.sp, 22.sp), color = c.ink, modifier = Modifier.padding(top = 3.dp))
    }
}
