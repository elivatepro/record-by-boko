package com.boko.roudio.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.boko.roudio.MainActivity
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.system.PhoneSettings
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.boko.roudio.nudge.NudgeRunner
import com.boko.roudio.widget.RecorderWidget
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the recorder connection and runs transfers while the screen is off (spec §2, "Transfer
 * service"). A
 * connected-device foreground service: its notification stays for as long as it runs, and comes
 * straight back if it's swiped away.
 * Runs whenever a recorder is linked; stops itself after unlinking.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var keeper: KeepConnected? = null
    private var watcher: Job? = null
    private var bluetoothWatcher: Job? = null
    private var widgetWatcher: Job? = null
    private var nudgeWatcher: Job? = null

    /** What the widget shows from the recorder, so it isn't redrawn for nothing. */
    private data class WidgetKey(
        val linked: Boolean,
        val connected: Boolean,
        val control: RecordControl,
        val battery: Int?,
        val since: java.time.Instant?,
    ) {
        constructor(state: RecorderState) : this(
            linked = state.linked != null,
            connected = state.isConnected,
            control = state.recordControl,
            battery = state.battery?.value?.percent,
            since = (state.recordingNow as? RecordingNow.Recording)?.since,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = (application as RoudioApp).recorder
        val state = recorder.state.value
        if (state.linked == null || !canConnect(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Android refuses when started from the background without an exemption.
            Log.w(TAG, "can't start in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (keeper == null) {
            keeper = KeepConnected(recorder, scope).also { it.start() }
            (application as RoudioApp).transfers.start()
            watcher = scope.launch {
                recorder.state.map(::NotificationContent).distinctUntilChanged().collect { content ->
                    val latest = recorder.state.value
                    if (latest.linked == null) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification(latest, content))
                    }
                }
            }
            // The widget follows the recorder and today's recordings, and ticks while recording.
            widgetWatcher = scope.launch {
                val app = application as RoudioApp
                kotlinx.coroutines.flow.combine(
                    recorder.state.map { WidgetKey(it) }.distinctUntilChanged(),
                    app.recordings.recordings,
                ) { key, _ -> key }.collectLatest { key ->
                    while (true) {
                        RecorderWidget.refresh(this@RecorderService)
                        if (key.control != RecordControl.Stop) break
                        kotlinx.coroutines.delay(WIDGET_TICK_MS)
                    }
                }
            }
            // Nudges: every minute, and as soon as the recorder's state changes.
            nudgeWatcher = scope.launch {
                val app = application as RoudioApp
                val runner = NudgeRunner(this@RecorderService, { app.nudgeSettings.settings.value })
                kotlinx.coroutines.flow.combine(recorder.state, app.recordings.recordings) { s, r -> s to r }
                    .collectLatest { (state, recordings) ->
                        while (true) {
                            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { runner.check(state, recordings) } }
                                .onFailure { Log.w(TAG, "nudge check failed", it) }
                            kotlinx.coroutines.delay(NUDGE_TICK_MS)
                        }
                    }
            }
            // Each time Bluetooth goes off (or is off when the service starts), ask to turn it on.
            bluetoothWatcher = scope.launch {
                recorder.state.map { it.bluetoothOn }.distinctUntilChanged().collect { on ->
                    if (!on) askForBluetooth()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        keeper?.stop()
        (application as RoudioApp).transfers.stop()
        watcher?.cancel()
        bluetoothWatcher?.cancel()
        widgetWatcher?.cancel()
        nudgeWatcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Shows "turn on Bluetooth?" over whatever is on screen. Android only allows that from the
     * background with Display over other apps (and Xiaomi's background pop-ups) switched on;
     * otherwise it's quietly refused and the notification's Turn on button is the way.
     */
    private fun askForBluetooth() {
        if (!PhoneSettings.granted(this, PhoneSettings.nearbyDevices)) return
        try {
            startActivity(TurnOnBluetoothActivity.intent(this))
        } catch (e: Exception) {
            Log.w(TAG, "can't ask to turn on Bluetooth", e)
        }
    }

    /** Only what the notification shows, so it isn't re-posted for every battery tick. */
    private data class NotificationContent(
        val serial: String?,
        val connected: Boolean,
        val bluetoothOn: Boolean,
        val battery: Int?,
        val lastSeenMinute: Long?,
        val control: RecordControl,
        val recordingSince: java.time.Instant?,
    ) {
        constructor(state: RecorderState) : this(
            serial = state.linked?.serial,
            connected = state.isConnected,
            bluetoothOn = state.bluetoothOn,
            battery = state.battery?.value?.percent,
            lastSeenMinute = state.lastSeen?.epochSecond?.div(60),
            control = state.recordControl,
            recordingSince = (state.recordingNow as? RecordingNow.Recording)?.since,
        )
    }

    private fun notification(state: RecorderState, content: NotificationContent = NotificationContent(state)): Notification {
        ensureChannel()
        val linked = state.linked
        val device = linked?.let { "${it.model.displayName} · ${it.serial.serialEnding}" }
            ?: getString(R.string.app_name)
        val recording = content.connected && (content.control == RecordControl.Stop || content.control == RecordControl.Stopping)
        // While recording, the title is what the HyperOS island and the lock screen show first.
        val title = if (recording) getString(R.string.recorder_notification_recording) else device
        val text = when {
            !content.bluetoothOn -> getString(R.string.recorder_notification_bluetooth_off)
            recording -> recordingText(device, content)
            content.connected -> connectedText(content)
            state.lastSeen != null -> getString(
                R.string.recorder_notification_not_connected_seen,
                TIME.format(state.lastSeen.atZone(ZoneId.systemDefault())),
            )
            else -> getString(R.string.recorder_notification_looking)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reshow = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, RecorderService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val turnOn = PendingIntent.getActivity(
            this,
            2,
            TurnOnBluetoothActivity.intent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // While recording: a waveform in the status bar, a running timer, a silent channel that
        // HyperOS shows on the lock screen and in the status bar, and Android 16's status-bar chip
        // when the system allows it. Nothing in it is private, so it shows in full when locked.
        val since = content.recordingSince.takeIf { recording }
        return NotificationCompat.Builder(this, if (recording) RECORDING_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(if (recording) R.drawable.ic_stat_recording else R.drawable.ic_stat_recorder)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (since != null) {
                    setWhen(since.toEpochMilli())
                    setShowWhen(true)
                    setUsesChronometer(true)
                }
                if (recording) {
                    setRequestPromotedOngoing(true)
                    setShortCriticalText(getString(R.string.recorder_notification_recording))
                }
            }
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(if (content.bluetoothOn) open else turnOn)
            .apply { if (!content.bluetoothOn) addAction(0, getString(R.string.turn_on_bluetooth), turnOn) }
            .apply {
                // Only when the recorder has said what it's doing on this connection (never a guess).
                when (content.control) {
                    RecordControl.Start -> addAction(
                        0,
                        getString(R.string.start_recording),
                        RecordingControlReceiver.pendingIntent(this@RecorderService, RecordingControlReceiver.ACTION_START),
                    )
                    RecordControl.Stop -> addAction(
                        0,
                        getString(R.string.stop_recording),
                        RecordingControlReceiver.pendingIntent(this@RecorderService, RecordingControlReceiver.ACTION_STOP),
                    )
                    else -> Unit
                }
            }
            // Android 14+ lets foreground-service notifications be swiped away; bring it straight
            // back so the recorder's state is always in the shade.
            .setDeleteIntent(reshow)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Under the "Recording" title: "Plaud Note Pro · 1437 · since 1:03 AM · battery 64%", or "Stopping recording…". */
    private fun recordingText(device: String, content: NotificationContent): String {
        if (content.control == RecordControl.Stopping) return getString(R.string.recorder_notification_stopping)
        val parts = listOfNotNull(
            device,
            content.recordingSince?.let { getString(R.string.recorder_notification_since, TIME.format(it.atZone(ZoneId.systemDefault()))) },
            content.battery?.let { getString(R.string.recorder_notification_battery, it) },
        )
        return parts.joinToString(" · ")
    }

    /** "Recording since 09:12 · battery 64%", "Not recording · battery 64%", "Starting recording…". */
    private fun connectedText(content: NotificationContent): String {
        val state = when (content.control) {
            RecordControl.Starting -> return getString(R.string.recorder_notification_starting)
            RecordControl.Stopping -> return getString(R.string.recorder_notification_stopping)
            RecordControl.Stop -> content.recordingSince
                ?.let { getString(R.string.recorder_notification_recording_since, TIME.format(it.atZone(ZoneId.systemDefault()))) }
                ?: getString(R.string.recorder_notification_recording)
            RecordControl.Start -> getString(R.string.recorder_notification_not_recording)
            RecordControl.Unavailable -> getString(R.string.recorder_notification_connected)
        }
        return content.battery?.let { getString(R.string.recorder_notification_with_battery, state, it) } ?: state
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(RECORDING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RECORDING_CHANNEL_ID,
                    getString(R.string.recording_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recorder_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val CHANNEL_ID = "recorder"
        private const val RECORDING_CHANNEL_ID = "recording"
        private const val WIDGET_TICK_MS = 5 * 60_000L
        private const val NUDGE_TICK_MS = 60_000L
        private const val NOTIFICATION_ID = 1
        private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        /** A connected-device service needs the Bluetooth permission before it may start. */
        fun canConnect(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** Start the service if a recorder is linked. Safe to call often. */
        fun startIfLinked(context: Context) {
            val app = context.applicationContext as RoudioApp
            if (app.recorder.state.value.linked == null || !canConnect(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecorderService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "can't start the recorder service", e)
            }
        }
    }
}

/** Brings the service back after a phone restart or an app update (needs Autostart on Xiaomi). */
class RecorderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> RecorderService.startIfLinked(context)
        }
    }
}
