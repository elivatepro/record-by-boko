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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        keeper?.stop()
        (application as RoudioApp).transfers.stop()
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Only what the notification shows, so it isn't re-posted for every battery tick. */
    private data class NotificationContent(
        val serial: String?,
        val connected: Boolean,
        val bluetoothOn: Boolean,
        val battery: Int?,
        val lastSeenMinute: Long?,
    ) {
        constructor(state: RecorderState) : this(
            serial = state.linked?.serial,
            connected = state.isConnected,
            bluetoothOn = state.bluetoothOn,
            battery = state.battery?.value?.percent,
            lastSeenMinute = state.lastSeen?.epochSecond?.div(60),
        )
    }

    private fun notification(state: RecorderState, content: NotificationContent = NotificationContent(state)): Notification {
        ensureChannel()
        val linked = state.linked
        val title = linked?.let { "${it.model.displayName} · ${it.serial.serialEnding}" }
            ?: getString(R.string.app_name)
        val text = when {
            !content.bluetoothOn -> getString(R.string.recorder_notification_bluetooth_off)
            content.connected && content.battery != null ->
                getString(R.string.recorder_notification_connected_battery, content.battery)
            content.connected -> getString(R.string.recorder_notification_connected)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
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

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
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
