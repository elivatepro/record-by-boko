package com.boko.roudio.nudge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.boko.roudio.MainActivity
import com.boko.roudio.R
import com.boko.roudio.recorder.RecorderState
import com.boko.roudio.recorder.RecordingControlReceiver
import com.boko.roudio.store.Recording
import com.boko.roudio.store.TransferState
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The phone's calendar. Behind an interface so the rules can be tested with a fake. */
interface CalendarSource {
    fun allowed(): Boolean

    fun eventsBetween(from: Instant, until: Instant): List<CalendarEvent>
}

/** Reads event instances through CalendarContract (READ_CALENDAR). Declined invitations are skipped. */
class PhoneCalendar(private val context: Context) : CalendarSource {
    override fun allowed() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun eventsBetween(from: Instant, until: Instant): List<CalendarEvent> {
        if (!allowed()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilli())
            ContentUris.appendId(it, until.toEpochMilli())
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (c.getInt(4) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        add(CalendarEvent(c.getLong(0), c.getString(1).orEmpty(), Instant.ofEpochMilli(c.getLong(2)), c.getInt(3) == 1))
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "calendar refused", e)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "Nudges"
    }
}

/** [NudgeMemory] in SharedPreferences, so a restart doesn't repeat a nudge. */
class NudgeMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nudge_memory", Context.MODE_PRIVATE)

    fun load() = NudgeMemory(
        fired = prefs.getStringSet(FIRED, emptySet()).orEmpty().toSet(),
        idleSince = prefs.getLong(IDLE_SINCE, 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond),
    )

    fun save(memory: NudgeMemory) {
        prefs.edit().putStringSet(FIRED, memory.fired).putLong(IDLE_SINCE, memory.idleSince?.epochSecond ?: 0).apply()
    }

    private companion object {
        const val FIRED = "fired"
        const val IDLE_SINCE = "idle_since"
    }
}

/**
 * Runs the rules once: gathers the inputs, posts what's due, saves the memory. RecorderService calls
 * it every minute and whenever the recorder's state changes.
 */
class NudgeRunner(
    private val context: Context,
    private val settings: () -> NudgeSettings,
    private val calendar: CalendarSource = PhoneCalendar(context),
    private val memory: NudgeMemoryStore = NudgeMemoryStore(context),
) {
    fun check(recorder: RecorderState, recordings: List<Recording>, now: Instant = Instant.now()) {
        val s = settings()
        val waiting = recordings.filter { it.transfer == TransferState.OnRecorder || it.transfer == TransferState.Transferring }
        val wantCalendar = s.meetings && calendar.allowed()
        val input = NudgeInputs(
            settings = s,
            recorder = recorder,
            now = now,
            local = LocalDateTime.ofInstant(now, ZoneId.systemDefault()),
            calendarAllowed = wantCalendar,
            events = if (wantCalendar) calendar.eventsBetween(now.minus(NudgeRules.MEETING_WINDOW), now.plusSeconds(60)) else emptyList(),
            waiting = waiting.size,
            waitingLength = Duration.ofSeconds(waiting.sumOf { it.sizeOnRecorder * 8 / OPUS_BITS_PER_SECOND }),
        )
        val (due, next) = NudgeRules.evaluate(input, memory.load())
        memory.save(next)
        due.forEach(::post)
    }

    private fun post(nudge: Nudge) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val id = notificationId(nudge)
        val (title, text) = words(nudge)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (nudge is Nudge.Meeting) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        val canStart = when (nudge) {
            is Nudge.Meeting -> nudge.canStart
            is Nudge.Idle -> true
            else -> false
        }
        if (canStart) {
            builder.addAction(0, context.getString(R.string.start_recording), RecordingControlReceiver.pendingIntent(context, RecordingControlReceiver.ACTION_START, id))
        }
        if (nudge is Nudge.Meeting) {
            builder.addAction(0, context.getString(R.string.nudge_not_this_one), RecordingControlReceiver.pendingIntent(context, RecordingControlReceiver.ACTION_DISMISS, id))
        }
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
        Log.i(TAG, "nudge ${nudge::class.simpleName}")
    }

    private fun words(n: Nudge): Pair<String, String> = when (n) {
        is Nudge.Meeting -> context.getString(R.string.nudge_meeting_title, n.title.ifBlank { context.getString(R.string.nudge_meeting_untitled) }) to
            context.getString(R.string.nudge_meeting_body)
        is Nudge.Idle -> context.getString(R.string.nudge_idle_title, n.minutes) to context.getString(R.string.nudge_idle_body)
        is Nudge.BatteryLow -> context.getString(R.string.nudge_battery_title, n.percent) to context.getString(R.string.nudge_battery_body)
        is Nudge.ChargeTonight -> context.getString(R.string.nudge_charge_title) to context.getString(R.string.nudge_charge_body, n.percent)
        is Nudge.StorageFull -> context.getString(R.string.nudge_storage_title) to
            context.resources.getQuantityString(R.plurals.nudge_storage_body, n.waiting, n.waiting, length(n.waitingLength))
    }

    private fun length(d: Duration): String =
        if (d >= Duration.ofHours(1)) {
            context.getString(R.string.hours_minutes, d.toHours().toInt(), (d.toMinutes() % 60).toInt())
        } else {
            context.getString(R.string.minutes_only, d.toMinutes().toInt().coerceAtLeast(1))
        }

    private fun notificationId(n: Nudge): Int = when (n) {
        is Nudge.Meeting -> MEETING_BASE + (n.key.hashCode() and 0xFFF)
        is Nudge.Idle -> IDLE_ID
        is Nudge.BatteryLow -> BATTERY_ID
        is Nudge.ChargeTonight -> CHARGE_ID
        is Nudge.StorageFull -> STORAGE_ID
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.nudge_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private companion object {
        const val TAG = "Nudges"
        const val CHANNEL_ID = "nudges"
        const val MEETING_BASE = 10_000
        const val IDLE_ID = 20_000
        const val BATTERY_ID = 20_001
        const val CHARGE_ID = 20_002
        const val STORAGE_ID = 20_003
        const val OPUS_BITS_PER_SECOND = 33_000L
    }
}
