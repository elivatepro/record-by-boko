package com.boko.roudio.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.boko.roudio.MainActivity
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.recorder.RecordingControlReceiver
import com.boko.roudio.store.CallEntity
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three call signals, written by their listeners and read by [CallWatcher]. */
class CallSignalHub {
    val phone = MutableStateFlow<PhoneSignal>(PhoneSignal.Idle)
    val whatsApp = MutableStateFlow<WhatsAppSignal?>(null)
    val audioInCallSince = MutableStateFlow<Instant?>(null)

    val signals = combine(phone, whatsApp, audioInCallSince) { p, w, a -> CallSignals(p, w, a) }
}

/** Which of the optional call permissions are on (15d's Calls rows). */
object CallPermissions {
    fun phone(context: Context) = granted(context, Manifest.permission.READ_PHONE_STATE)

    /** Call log and contacts: the caller's name for "Call from Ada". */
    fun whosCalling(context: Context) =
        granted(context, Manifest.permission.READ_CALL_LOG) && granted(context, Manifest.permission.READ_CONTACTS)

    fun whatsApp(context: Context) = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Notification access is a system setting, not a pop-up: open Roudio's page in it. */
    fun whatsAppSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, CallNotificationListener::class.java).flattenToString(),
                )
        } else {
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Notices calls while Roudio runs in the background, and does what [CallRules] says: the heads-up
 * prompt, automatic start and stop, and saving each answered call. Runs inside RecorderService.
 */
class CallWatcher(private val context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext as RoudioApp
    private val hub = app.callSignals
    private var job: Job? = null
    private var call: ActiveCall? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    /** An AudioManager.OnModeChangedListener (Android 12+), kept untyped so older phones never load it. */
    private var modeListener: Any? = null

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION") // Still delivered, with "Who's calling" (call log) allowed.
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            onPhoneState(state, number)
        }
    }

    fun start() {
        if (job != null) return
        // Without the Phone permission this simply never arrives; registering early means a later
        // "Allow" works without restarting anything.
        ContextCompat.registerReceiver(
            context,
            phoneReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { mode -> onAudioMode(mode) }
            modeListener = listener
            audio.addOnModeChangedListener(ContextCompat.getMainExecutor(context), listener)
        }
        onAudioMode(audio.mode)
        job = scope.launch {
            combine(hub.signals, app.recorder.state, app.nudgeSettings.settings) { s, r, n -> Triple(s, r, n) }
                .collectLatest { (signals, recorder, settings) ->
                    // Re-check every second while something is waiting on time (the end grace,
                    // another app's voice chat settling); a new signal cancels this and starts over.
                    while (true) {
                        val (next, actions) = CallRules.evaluate(
                            call,
                            signals,
                            recorder,
                            CallSettings(prompts = settings.callPrompts, autoStart = settings.autoStartCalls),
                            Instant.now(),
                        )
                        call = next
                        actions.forEach(::run)
                        val waiting = next?.quietSince != null || (next == null && signals.audioInCallSince != null)
                        if (!waiting) break
                        delay(1_000)
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { context.unregisterReceiver(phoneReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let(audio::removeOnModeChangedListener)
        }
        modeListener = null
        context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
    }

    private fun onPhoneState(state: String, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val current = hub.phone.value as? PhoneSignal.Ringing
                if (current == null) hub.phone.value = PhoneSignal.Ringing(null)
                // The broadcast comes twice when the call log is allowed; the second one has the number.
                if (number != null && current?.name == null) {
                    scope.launch {
                        val name = withContext(Dispatchers.IO) { contactName(number) } ?: return@launch
                        if (hub.phone.value is PhoneSignal.Ringing) hub.phone.value = PhoneSignal.Ringing(name)
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> hub.phone.value = PhoneSignal.OffHook
            TelephonyManager.EXTRA_STATE_IDLE -> hub.phone.value = PhoneSignal.Idle
        }
    }

    private fun onAudioMode(mode: Int) {
        val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        hub.audioInCallSince.value = if (inCall) hub.audioInCallSince.value ?: Instant.now() else null
    }

    private fun run(action: CallAction) {
        val recorder = app.recorder
        when (action) {
            is CallAction.ShowPrompt -> showPrompt(action.call, action.canStart)
            CallAction.HidePrompt -> context.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
            CallAction.StartRecording -> if (!recorder.startRecording()) Log.i(TAG, "automatic start refused")
            CallAction.StopRecording -> if (!recorder.stopRecording()) Log.i(TAG, "automatic stop refused")
            is CallAction.Finished -> scope.launch(Dispatchers.IO) { save(action.call) }
        }
        // Named here: the fast build renames classes, so simpleName would log nonsense.
        val what = when (action) {
            is CallAction.ShowPrompt -> "prompt ${action.call.app} ${action.call.direction} canStart=${action.canStart}"
            CallAction.HidePrompt -> "hide prompt"
            CallAction.StartRecording -> "automatic start"
            CallAction.StopRecording -> "automatic stop"
            is CallAction.Finished -> "finished ${action.call.app} ${action.call.direction}"
        }
        Log.i(TAG, what)
    }

    private suspend fun save(call: FinishedCall) {
        val name = call.name ?: if (call.app == CallApp.Phone) callLogName(call) else null
        runCatching {
            app.database.calls().insert(
                CallEntity(
                    app = call.app.name,
                    direction = call.direction.name,
                    name = name,
                    startedAt = call.startedAt,
                    answeredAt = call.answeredAt,
                    endedAt = call.endedAt,
                    autoStarted = call.autoStarted,
                ),
            )
        }.onFailure { Log.w(TAG, "couldn't save the call", it) }
    }

    private fun showPrompt(call: ActiveCall, canStart: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        ensureChannel()
        val title = promptTitle(call.direction, call.name)
        val body = context.getString(R.string.call_prompt_body)
        val sub = when (call.app) {
            CallApp.Phone -> context.getString(R.string.call_prompt_phone)
            CallApp.WhatsApp -> context.getString(R.string.call_prompt_whatsapp)
            CallApp.Other -> null
        }
        val open = PendingIntent.getActivity(
            context,
            PROMPT_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun builder(title: String) = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(sub)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(PROMPT_TIMEOUT_MS)
        // The caller's name stays off the lock screen; the reminder itself shows.
        val public = builder(promptTitle(call.direction, null)).build()
        val notification = builder(title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .apply {
                if (canStart) {
                    addAction(
                        0,
                        context.getString(R.string.start_recording),
                        RecordingControlReceiver.pendingIntent(context, RecordingControlReceiver.ACTION_START, PROMPT_ID),
                    )
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
    }

    private fun promptTitle(direction: CallDirection, name: String?): String = when {
        name != null && direction == CallDirection.Incoming -> context.getString(R.string.call_prompt_from, name)
        name != null -> context.getString(R.string.call_prompt_with, name)
        direction == CallDirection.Incoming -> context.getString(R.string.call_prompt_incoming)
        direction == CallDirection.Outgoing -> context.getString(R.string.call_prompt_outgoing)
        else -> context.getString(R.string.call_prompt_on_call)
    }

    /** The contact's name for a number; needs Contacts ("Who's calling"). */
    private fun contactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    /** After a phone call, the name the call log saved (also covers calls out, which carry no number). */
    private fun callLogName(call: FinishedCall): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((call.startedAt.toEpochMilli() - CALL_LOG_SLACK_MS).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null }
        }.getOrNull()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.call_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private companion object {
        const val TAG = "Calls"
        const val CHANNEL_ID = "calls"
        const val PROMPT_ID = 30_000
        const val PROMPT_TIMEOUT_MS = 2 * 60 * 60_000L
        const val CALL_LOG_SLACK_MS = 60_000L
    }
}

/**
 * Notification access, for WhatsApp calls only: it looks at WhatsApp's own call notification
 * ("Incoming voice call", or a call under way) and nothing else. Other apps' notifications are
 * skipped by package before anything in them is read.
 */
class CallNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = rescan()

    override fun onListenerDisconnected() {
        (applicationContext as RoudioApp).callSignals.whatsApp.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in WHATSAPP) rescan()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in WHATSAPP) rescan()
    }

    private fun rescan() {
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        val signal = active.asSequence().filter { it.packageName in WHATSAPP }.mapNotNull { signal(it.notification) }
            // A ringing call outranks one that's ending.
            .sortedBy { !it.ringing }
            .firstOrNull()
        (applicationContext as RoudioApp).callSignals.whatsApp.value = signal
    }

    private fun signal(n: Notification): WhatsAppSignal? {
        if (n.category != Notification.CATEGORY_CALL) return null
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) n.extras.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
        val ringing = when (type) {
            CALL_TYPE_INCOMING -> true
            CALL_TYPE_ONGOING, CALL_TYPE_SCREENING -> false
            // No call style: incoming calls ring full screen; a call under way stays ongoing.
            else -> when {
                n.fullScreenIntent != null -> true
                n.flags and Notification.FLAG_ONGOING_EVENT != 0 -> false
                else -> return null // a missed call, not a call happening now
            }
        }
        val person = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                @Suppress("DEPRECATION") n.extras.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON)
            else -> null
        }?.name?.toString()
        val name = person ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return WhatsAppSignal(name?.takeIf(String::isNotBlank), ringing)
    }

    private companion object {
        val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
        const val CALL_TYPE_INCOMING = 1
        const val CALL_TYPE_ONGOING = 2
        const val CALL_TYPE_SCREENING = 3
    }
}
