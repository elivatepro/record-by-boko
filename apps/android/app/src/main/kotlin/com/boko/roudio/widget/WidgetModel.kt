package com.boko.roudio.widget

import com.boko.roudio.recorder.RecordControl
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingNow
import com.boko.roudio.store.Recording
import com.boko.roudio.ui.today.DialSpan
import com.boko.roudio.ui.today.TodayModel
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * What the home-screen widget (16) shows. Pure, so the wording rules are unit-tested; the Glance
 * code only lays it out. The ring reuses Today's spans so the two always agree.
 */
data class WidgetModel(
    val status: Status,
    /** The recorder's model name, for "Note Pro 64 %". */
    val modelName: String?,
    val battery: Int?,
    /** On its charger (only when [battery] is shown). */
    val charging: Boolean,
    /** Recording: when it started. Not recording: when today's last recording ended. */
    val since: LocalTime?,
    /** Not connected: when the recorder was last heard from. */
    val lastHeard: LocalTime?,
    val captured: Duration,
    val spans: List<DialSpan>,
    /** Where now sits on the ring, 0–1 from midnight. */
    val now: Float,
    val control: RecordControl,
) {
    enum class Status { Recording, NotRecording, NotConnected, NoRecorder }

    companion object {
        fun build(recorder: RecorderState, recordings: List<Recording>, now: Instant, offset: ZoneOffset): WidgetModel {
            val today = TodayModel.build(recordings, recorder, null, now.atOffset(offset).toLocalDate(), now, offset)
            val status = when {
                recorder.linked == null -> Status.NoRecorder
                !recorder.isConnected -> Status.NotConnected
                recorder.recordingNow is RecordingNow.Recording -> Status.Recording
                // Connected, but it hasn't said yet: treat as not connected rather than guess.
                recorder.recordingNow == RecordingNow.Unknown -> Status.NotConnected
                else -> Status.NotRecording
            }
            fun local(i: Instant) = i.atOffset(offset).toLocalTime()
            val since = when (status) {
                Status.Recording -> (recorder.recordingNow as RecordingNow.Recording).since?.let(::local)
                Status.NotRecording -> today.spans.maxOfOrNull { it.endSecond }
                    ?.takeIf { it < SECONDS_PER_DAY }
                    ?.let { LocalTime.ofSecondOfDay(it.toLong()) }
                else -> null
            }
            return WidgetModel(
                status = status,
                modelName = recorder.linked?.model?.shortName,
                battery = recorder.battery?.value?.percent?.takeIf { status == Status.Recording || status == Status.NotRecording },
                charging = recorder.battery?.value?.charging == true && (status == Status.Recording || status == Status.NotRecording),
                since = since,
                lastHeard = recorder.lastSeen?.takeIf { status == Status.NotConnected }?.let(::local),
                captured = today.recorded,
                spans = if (status == Status.NoRecorder) emptyList() else today.spans,
                now = today.now ?: 0f,
                control = if (status == Status.NotConnected) RecordControl.Unavailable else recorder.recordControl,
            )
        }

        private const val SECONDS_PER_DAY = 86_400
    }
}
