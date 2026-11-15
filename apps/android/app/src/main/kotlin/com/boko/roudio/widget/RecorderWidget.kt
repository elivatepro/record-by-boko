package com.boko.roudio.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.boko.roudio.MainActivity
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.recorder.RecordControl
import com.boko.roudio.recorder.RecordingControlReceiver
import com.boko.roudio.ui.theme.RoudioPalette as P
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.first

/**
 * The home-screen widget (16): the recorder's state, battery and today's captured hours, with a
 * mini 24-hour ring and a Start / Stop button that only appears when the recorder has said what it's
 * doing. Tapping anywhere else opens the recorder screen.
 */
class RecorderWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as RoudioApp
        val firstRecordings = app.recordings.recordings.first()
        provideContent {
            // Read inside the composition: Glance keeps this session alive and recomposes on every
            // change, so "Starting…" shows the moment Start is tapped. (Reading once before
            // provideContent left the widget stale; seen on the phone, 14 Nov 2026.)
            val recorder by app.recorder.state.collectAsState()
            val recordings by app.recordings.recordings.collectAsState(initial = firstRecordings)
            val now = Instant.now()
            val model = WidgetModel.build(recorder, recordings, now, ZoneId.systemDefault().rules.getOffset(now))
            val ring = remember(model.spans, model.now, model.status) { ringLayers(context, model) }
            val gauge = remember(model.battery, model.charging, model.status) { gaugeLayers(context, model) }
            Content(context, model, ring, gauge)
        }
    }

    @Composable
    private fun Content(context: Context, m: WidgetModel, ring: RingLayers, gauge: GaugeLayers) {
        val ink = ColorProvider(day = P.Navy, night = P.NightInk)
        val muted = ColorProvider(day = P.Muted, night = P.NightMuted)
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val title = context.getString(
            when (m.status) {
                WidgetModel.Status.Recording -> R.string.widget_recording
                WidgetModel.Status.NotRecording -> R.string.widget_not_recording
                WidgetModel.Status.NotConnected -> R.string.widget_not_connected
                WidgetModel.Status.NoRecorder -> R.string.widget_no_recorder
            },
        )
        val battery = m.battery?.let { context.getString(R.string.widget_battery, m.modelName ?: "", it) }
        val subtitle = when (m.status) {
            WidgetModel.Status.Recording, WidgetModel.Status.NotRecording -> listOfNotNull(
                m.since?.let {
                    context.getString(
                        if (m.status == WidgetModel.Status.Recording) R.string.widget_since else R.string.widget_stopped_at,
                        clock.format(it),
                    )
                },
                battery,
            ).joinToString(" · ")
            WidgetModel.Status.NotConnected -> m.lastHeard?.let { context.getString(R.string.widget_last_heard, clock.format(it)) }
                ?: context.getString(R.string.widget_out_of_range)
            WidgetModel.Status.NoRecorder -> context.getString(R.string.widget_link_first)
        }
        val footer = when (m.status) {
            WidgetModel.Status.NoRecorder -> ""
            WidgetModel.Status.NotConnected -> context.getString(R.string.widget_comes_back)
            else -> context.getString(R.string.widget_captured, duration(context, m.captured))
        }
        val open = actionStartActivity(MainActivity.recorderIntent(context))

        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(28.dp)
                .background(ColorProvider(day = P.Paper, night = P.NightRaised))
                .padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 14.dp)
                .clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Ring(m, ring, gauge)
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(title, style = TextStyle(color = ink, fontSize = 20.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Text(subtitle, style = TextStyle(color = muted, fontSize = 13.sp), maxLines = 2)
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val footerColour = if (m.status == WidgetModel.Status.NotConnected) muted else ink
                Text(
                    footer,
                    style = TextStyle(color = footerColour, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Button(context, m)
            }
        }
    }

    /** Start / Stop (or Starting… / Stopping…), Open Roudio with no recorder, nothing when not connected. */
    @Composable
    private fun Button(context: Context, m: WidgetModel) {
        val fog = ColorProvider(day = P.Fog, night = P.NightCard)
        val ink = ColorProvider(day = P.Navy, night = P.NightInk)
        val solid = ColorProvider(day = P.Navy, night = P.NightInk)
        val onSolid = ColorProvider(day = P.Paper, night = P.Night)
        fun broadcast(action: String) =
            actionSendBroadcast(Intent(context, RecordingControlReceiver::class.java).setAction(action))
        val (label, primary, action) = when {
            m.status == WidgetModel.Status.NoRecorder ->
                Triple(R.string.widget_open, false, actionStartActivity(MainActivity.recorderIntent(context)))
            m.control == RecordControl.Start -> Triple(R.string.widget_start, true, broadcast(RecordingControlReceiver.ACTION_START))
            m.control == RecordControl.Stop -> Triple(R.string.widget_stop, false, broadcast(RecordingControlReceiver.ACTION_STOP))
            m.control == RecordControl.Starting -> Triple(R.string.widget_starting, false, null)
            m.control == RecordControl.Stopping -> Triple(R.string.widget_stopping, false, null)
            else -> return
        }
        Box(
            GlanceModifier
                .height(40.dp)
                .cornerRadius(20.dp)
                .background(if (primary) solid else fog)
                .padding(horizontal = 18.dp)
                .let { if (action != null) it.clickable(action) else it },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                context.getString(label),
                style = TextStyle(color = if (primary) onSolid else ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
    }

    private fun duration(context: Context, d: Duration): String = when {
        d >= Duration.ofHours(1) -> context.getString(R.string.hours_minutes, d.toHours().toInt(), (d.toMinutes() % 60).toInt())
        d >= Duration.ofMinutes(1) -> context.getString(R.string.minutes_only, d.toMinutes().toInt())
        else -> context.getString(R.string.seconds_only, d.seconds.toInt())
    }

    /** The ring's layers as white masks; [Ring] tints each one so day and night both work. */
    private class RingLayers(val track: Bitmap, val arcs: Bitmap, val halo: Bitmap?, val buoy: Bitmap?)

    /**
     * The mini dial: captured spans on a track, and the buoy at now (it marks "now" only). Drawn as
     * masks and tinted by Glance, because a bitmap's own colours can't follow the phone's dark mode.
     */
    @Composable
    private fun Ring(m: WidgetModel, layers: RingLayers, gauge: GaugeLayers) {
        val full = GlanceModifier.fillMaxSize()
        val arc = if (m.status == WidgetModel.Status.NotConnected) {
            ColorProvider(day = P.Navy.copy(alpha = 0.45f), night = P.NightInk.copy(alpha = 0.45f))
        } else {
            ColorProvider(day = P.Navy, night = P.NightInk)
        }
        Box(GlanceModifier.size(56.dp)) {
            Image(ImageProvider(layers.track), null, full, colorFilter = ColorFilter.tint(ColorProvider(day = P.Hairline, night = P.NightCardTile)))
            Image(ImageProvider(layers.arcs), null, full, colorFilter = ColorFilter.tint(arc))
            layers.halo?.let { Image(ImageProvider(it), null, full, colorFilter = ColorFilter.tint(ColorProvider(day = P.Paper, night = P.NightRaised))) }
            layers.buoy?.let { Image(ImageProvider(it), null, full, colorFilter = ColorFilter.tint(ColorProvider(day = P.Buoy, night = P.NightBuoy))) }
            // The recorder in the middle, filled like a battery gauge.
            val buoy = ColorProvider(day = P.Buoy, night = P.NightBuoy)
            Image(ImageProvider(gauge.body), null, full, colorFilter = ColorFilter.tint(arc))
            gauge.level?.let { Image(ImageProvider(it), null, full, colorFilter = ColorFilter.tint(if (gauge.low) buoy else arc)) }
            gauge.bolt?.let { Image(ImageProvider(it), null, full, colorFilter = ColorFilter.tint(buoy)) }
        }
    }

    /** The recorder glyph as a battery gauge: its outline, the charge level, and a bolt while charging. */
    private class GaugeLayers(val body: Bitmap, val level: Bitmap?, val low: Boolean, val bolt: Bitmap?)

    private fun gaugeLayers(context: Context, m: WidgetModel): GaugeLayers {
        val px = (56 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val u = px / 56f // one dp
        fun layer(draw: Canvas.() -> Unit) = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { Canvas(it).draw() }
        val white = android.graphics.Color.WHITE
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * u; color = white }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white }
        // The same shape as the notification icon: a rounded body, 13 × 21 dp, with a slot at the top.
        val body = RectF(px / 2f - 6.5f * u, px / 2f - 10.5f * u, px / 2f + 6.5f * u, px / 2f + 10.5f * u)
        val inner = RectF(body.left + 3f * u, body.top + 3f * u, body.right - 3f * u, body.bottom - 3f * u)
        val percent = m.battery
        val bodyLayer = layer {
            drawRoundRect(body, 3.5f * u, 3.5f * u, outline)
            // No reading: the plain glyph, slot and all.
            if (percent == null) drawRect(px / 2f - 1.5f * u, body.top + 3.5f * u, px / 2f + 1.5f * u, body.top + 6.5f * u, fill)
        }
        if (percent == null) return GaugeLayers(bodyLayer, null, low = false, bolt = null)
        val level = percent.coerceIn(0, 100) / 100f
        val levelLayer = if (level <= 0f) {
            null
        } else {
            layer { drawRoundRect(RectF(inner.left, inner.bottom - inner.height() * level, inner.right, inner.bottom), 1.5f * u, 1.5f * u, fill) }
        }
        val bolt = if (!m.charging) {
            null
        } else {
            layer {
                // A small bolt over the body's middle, with a halo cut so it reads on the fill.
                val cx = px / 2f
                val cy = px / 2f
                val path = android.graphics.Path().apply {
                    moveTo(cx + 1.2f * u, cy - 6f * u)
                    lineTo(cx - 3.2f * u, cy + 0.8f * u)
                    lineTo(cx - 0.2f * u, cy + 0.8f * u)
                    lineTo(cx - 1.2f * u, cy + 6f * u)
                    lineTo(cx + 3.2f * u, cy - 0.8f * u)
                    lineTo(cx + 0.2f * u, cy - 0.8f * u)
                    close()
                }
                drawPath(path, fill)
            }
        }
        return GaugeLayers(bodyLayer, levelLayer, low = percent < LOW_BATTERY && !m.charging, bolt = bolt)
    }

    private fun ringLayers(context: Context, m: WidgetModel): RingLayers {
        val px = (56 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        fun layer(draw: Canvas.() -> Unit) = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { Canvas(it).draw() }
        val stroke = px * 7f / 64f
        val inset = stroke / 2 + px * 2f / 64f
        val box = RectF(inset, inset, px - inset, px - inset)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = android.graphics.Color.WHITE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val track = layer { drawArc(box, 0f, 360f, false, line) }
        val arcs = layer {
            for (span in m.spans) {
                val sweep = ((span.end - span.start) * 360f).coerceAtLeast(MIN_SWEEP)
                drawArc(box, -90f + span.start * 360f, sweep, false, line)
            }
        }
        if (m.status == WidgetModel.Status.NoRecorder) return RingLayers(track, arcs, null, null)
        val angle = Math.toRadians((m.now * 360f - 90f).toDouble())
        val r = box.width() / 2
        val cx = box.centerX() + r * Math.cos(angle).toFloat()
        val cy = box.centerY() + r * Math.sin(angle).toFloat()
        return RingLayers(
            track,
            arcs,
            halo = layer { drawCircle(cx, cy, stroke * 0.95f, fill) },
            buoy = layer { drawCircle(cx, cy, stroke * 0.7f, fill) },
        )
    }

    companion object {
        private const val MIN_SWEEP = 1.5f
        private const val LOW_BATTERY = 20

        /** Redraw every placed widget (the recorder's state or today's recordings changed). */
        suspend fun refresh(context: Context) {
            runCatching { RecorderWidget().updateAll(context) }
        }
    }
}

class RecorderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecorderWidget()
}
