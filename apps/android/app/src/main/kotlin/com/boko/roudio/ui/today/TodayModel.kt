package com.boko.roudio.ui.today

import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingNow
import com.boko.roudio.store.BackupState
import com.boko.roudio.store.ConversationEntity
import com.boko.roudio.store.ProcessingState
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import com.boko.roudio.transfer.Transfers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What Today shows for one day: the dial's spans, how long was recorded, and a timeline row per
 * recording with its separate transfer/processing and backup words (spec §3). Pure, so the rules
 * are unit-tested; the screen only turns it into pixels and words.
 */
data class TodayModel(
    val day: LocalDate,
    val isToday: Boolean,
    val spans: List<DialSpan>,
    val recorded: Duration,
    /** Where "now" sits on the dial (0–1 from midnight); null on past days. */
    val now: Float?,
    /** Newest first. */
    val rows: List<TimelineRow>,
    /** Recordings anywhere that are on this phone but not on the server yet (S2). */
    val waitingToUpload: Int,
    /** Recordings still on the recorder, including the one being recorded (the chip). */
    val onRecorder: Int,
    /** Which kinds the dial shows, for the legend. */
    val kinds: Set<SpanKind> = spans.map { it.kind }.toSet(),
) {
    companion object {
        fun build(
            recordings: List<Recording>,
            recorder: RecorderState,
            progress: Transfers.Progress?,
            day: LocalDate,
            now: Instant,
            nowOffset: ZoneOffset,
            conversations: List<ConversationEntity> = emptyList(),
        ): TodayModel {
            val today = now.atOffset(nowOffset).toLocalDate()
            val open = recorder.openRecording()
            val onDay = recordings.filter { it.localStart().toLocalDate() == day || it.crossesInto(day) }

            val bySession = conversations.groupBy { it.serial to it.sessionId }
            val spans = onDay.flatMap { recording ->
                val written = recording.processing == ProcessingState.Done
                val kind = when {
                    recording.transfer.isOnRecorder() -> SpanKind.StillOnPlaud
                    written -> SpanKind.Quiet
                    else -> SpanKind.NotWrittenUp
                }
                val whole = listOfNotNull(span(recording.localStart(), recording.length(), day, kind))
                // Written up: the recording is quiet except where a conversation was found.
                val talk = if (written) {
                    bySession[recording.serial to recording.sessionId].orEmpty().mapNotNull { c ->
                        span(recording.localStart().plus(Duration.ofMillis(c.startMs)), Duration.ofMillis(c.endMs - c.startMs), day, SpanKind.Conversation)
                    }
                } else {
                    emptyList()
                }
                whole + talk
            }.toMutableList()
            // The file being recorded isn't saved yet; it runs from its start until now.
            val unlisted = open?.takeIf { start -> day == today && onDay.none { it.startedAt.epochSecond == start.epochSecond } }
            if (unlisted != null) {
                span(unlisted.atOffset(nowOffset).toLocalDateTime(), Duration.between(unlisted, now), day, SpanKind.StillOnPlaud)
                    ?.let(spans::add)
            }

            val rows = onDay.sortedByDescending { it.startedAt }.flatMap { recording ->
                val found = bySession[recording.serial to recording.sessionId].orEmpty()
                val talk = found.filter { it.day == day.toString() }
                if (recording.processing == ProcessingState.Done && talk.isNotEmpty()) {
                    talk.sortedByDescending { it.startMs }.map { conversationRow(recording, it) }
                } else if (recording.processing == ProcessingState.Done && found.isNotEmpty()) {
                    emptyList() // its conversations are all on the other side of midnight
                } else {
                    listOf(row(recording, progress, open))
                }
            }.toMutableList()
            val lastSeenRecording = recorder.recording?.value
            when {
                unlisted != null -> rows.add(0, openRow(unlisted.atOffset(nowOffset).toLocalTime().toHHmm(), RowStatus.RecordingNow))
                // Out of reach, and it was recording when last heard from (S1). Its start may be unknown.
                day == today && !recorder.isConnected && lastSeenRecording?.isRecording == true -> {
                    val start = lastSeenRecording.startedAt
                    if (start == null || onDay.none { it.startedAt.epochSecond == start.epochSecond }) {
                        val time = start?.atOffset(nowOffset)?.toLocalTime()?.toHHmm() ?: ""
                        rows.add(0, openRow(time, RowStatus.MaybeRecording))
                    }
                }
            }

            return TodayModel(
                day = day,
                isToday = day == today,
                spans = spans.sortedBy { it.startSecond },
                recorded = Duration.ofSeconds(spans.sumOf { (it.endSecond - it.startSecond).toLong() }),
                now = if (day == today) fractionOfDay(now.atOffset(nowOffset).toLocalTime().toSecondOfDay()) else null,
                rows = rows,
                waitingToUpload = recordings.count { it.transfer == TransferState.OnPhone || it.transfer == TransferState.Uploading },
                onRecorder = recordings.count { it.transfer.isOnRecorder() } + if (open != null) 1 else 0,
            )
        }

        private fun openRow(time: String, status: RowStatus) =
            TimelineRow(null, time, RowTitle.WaitingOnPlaud, status, backup = BackupWord.Later, stillOnPlaud = true)

        private fun conversationRow(recording: Recording, c: ConversationEntity): TimelineRow {
            val local = c.startedAt.atOffset(ZoneOffset.ofTotalSeconds(recording.zoneOffsetSeconds)).toLocalTime()
            return TimelineRow(
                recordingId = recording.id,
                time = local.toHHmm(),
                title = RowTitle.Conversation(c.title),
                status = RowStatus.WrittenUp,
                backup = recording.backupWord(),
                stillOnPlaud = false,
                conversationId = c.id,
                people = c.people,
                decisions = c.decisions,
            )
        }

        private fun Recording.backupWord(): BackupWord = when (backup) {
            BackupState.BackedUp -> BackupWord.BackedUp
            BackupState.Failed -> BackupWord.Failed
            else -> BackupWord.NotYet
        }

        private fun row(recording: Recording, progress: Transfers.Progress?, open: Instant?): TimelineRow {
            val status = when (recording.transfer) {
                TransferState.OnRecorder ->
                    if (open != null && recording.startedAt.epochSecond == open.epochSecond) RowStatus.RecordingNow else RowStatus.NotTransferred
                TransferState.Transferring ->
                    RowStatus.Transferring(progress?.takeIf { it.recordingId == recording.id }?.percent ?: 0)
                TransferState.OnPhone -> RowStatus.WaitingToUpload
                TransferState.Uploading -> {
                    val size = recording.fileBytes ?: 0
                    RowStatus.Uploading(if (size > 0) (recording.uploadedBytes * 100 / size).toInt() else 0)
                }
                TransferState.Uploaded -> when (recording.processing) {
                    ProcessingState.Waiting -> RowStatus.WaitingToWriteUp
                    ProcessingState.Failed -> RowStatus.WritingUpFailed
                    // Written up, and nothing in it sounded like a conversation.
                    ProcessingState.Done -> RowStatus.NothingSaid
                    else -> RowStatus.WritingUp
                }
            }
            val stillOnPlaud = recording.transfer.isOnRecorder()
            return TimelineRow(
                recordingId = recording.id,
                time = recording.localStart().toLocalTime().toHHmm(),
                title = if (stillOnPlaud) RowTitle.WaitingOnPlaud else RowTitle.Length(recording.length()),
                status = status,
                backup = if (stillOnPlaud) BackupWord.Later else recording.backupWord(),
                stillOnPlaud = stillOnPlaud,
                pendingId = recording.id.takeIf { recording.transfer == TransferState.Uploaded },
            )
        }

        private fun TransferState.isOnRecorder() = this == TransferState.OnRecorder || this == TransferState.Transferring

        /** The start of the recording being made now, when the recorder has said so on this connection. */
        private fun RecorderState.openRecording(): Instant? = (recordingNow as? RecordingNow.Recording)?.since

        /** Local wall-clock start, using the phone's offset when it was recorded (spec §4, "Times"). */
        private fun Recording.localStart() = startedAt.atOffset(ZoneOffset.ofTotalSeconds(zoneOffsetSeconds)).toLocalDateTime()

        private fun Recording.crossesInto(day: LocalDate): Boolean {
            val start = localStart()
            return start.toLocalDate().isBefore(day) && start.plus(length()).isAfter(day.atStartOfDay())
        }

        /**
         * The audio's length: measured once it's on the phone; before that, estimated from the size
         * the recorder reports at the Note Pro's ~33 kbps.
         */
        private fun Recording.length(): Duration =
            durationMs?.let(Duration::ofMillis) ?: Duration.ofSeconds(sizeOnRecorder * 8 / OPUS_BITS_PER_SECOND)

        private fun span(start: java.time.LocalDateTime, length: Duration, day: LocalDate, kind: SpanKind): DialSpan? {
            val dayStart = day.atStartOfDay()
            val dayEnd = dayStart.plusDays(1)
            val from = maxOf(start, dayStart)
            val to = minOf(start.plus(length), dayEnd)
            if (!to.isAfter(from)) return null
            return DialSpan(Duration.between(dayStart, from).seconds.toInt(), Duration.between(dayStart, to).seconds.toInt(), kind)
        }

        private fun fractionOfDay(seconds: Int) = seconds / DAY_SECONDS

        private fun java.time.LocalTime.toHHmm() = "%02d:%02d".format(hour, minute)

        private const val DAY_SECONDS = 86_400f
        private const val OPUS_BITS_PER_SECOND = 33_000L
    }
}

/** A stretch of the 24-hour dial, in seconds from midnight; [start]/[end] as fractions of the day. */
data class DialSpan(val startSecond: Int, val endSecond: Int, val kind: SpanKind) {
    val start: Float get() = startSecond / 86_400f
    val end: Float get() = endSecond / 86_400f
}

/**
 * [Conversation] and [Quiet] once written up; [NotWrittenUp] before that (not yet split);
 * [StillOnPlaud] not transferred yet.
 */
enum class SpanKind { Conversation, Quiet, NotWrittenUp, StillOnPlaud }

/** The separate backup word on each row (spec §3: processing never implies backed up). */
enum class BackupWord { Later, NotYet, BackedUp, Failed }

data class TimelineRow(
    val recordingId: Long?,
    val time: String,
    val title: RowTitle,
    val status: RowStatus,
    val backup: BackupWord,
    val stillOnPlaud: Boolean,
    /** Set on written-up rows: tapping opens the conversation. */
    val conversationId: String? = null,
    /** Set on recordings on the server but not written up: tapping opens S3 / S8. */
    val pendingId: Long? = null,
    /** On written-up rows: who spoke ("Daniel, Speaker 2") and how many decisions. */
    val people: String = "",
    val decisions: Int = 0,
)

sealed interface RowTitle {
    data object WaitingOnPlaud : RowTitle

    /** No title until it's written up; its length stands in. */
    data class Length(val length: Duration) : RowTitle

    data class Conversation(val title: String) : RowTitle
}

/** The transfer / processing word. The backup word is separate ([TimelineRow.backup]). */
sealed interface RowStatus {
    data object RecordingNow : RowStatus

    /** The recorder is out of reach and was recording when last heard from (S1). */
    data object MaybeRecording : RowStatus

    data object NotTransferred : RowStatus

    data class Transferring(val percent: Int) : RowStatus

    data object WaitingToUpload : RowStatus

    data class Uploading(val percent: Int) : RowStatus

    data object WaitingToWriteUp : RowStatus

    data object WritingUp : RowStatus

    data object WritingUpFailed : RowStatus

    data object WrittenUp : RowStatus

    /** Written up with no conversation found; the row stays so the audio can still be played. */
    data object NothingSaid : RowStatus
}
