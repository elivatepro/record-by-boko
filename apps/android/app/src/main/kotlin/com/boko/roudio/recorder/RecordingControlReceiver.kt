package com.boko.roudio.recorder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.boko.roudio.RoudioApp

/**
 * Start / Stop from outside the app (the recorder notification, the widget, nudges). [Recorder]
 * refuses anything that isn't safe, so a stale button can't start or stop on a guess.
 */
class RecordingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recorder = (context.applicationContext as RoudioApp).recorder
        // A nudge's button: the nudge has done its job once tapped.
        intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0).takeIf { it != 0 }?.let {
            context.getSystemService(NotificationManager::class.java).cancel(it)
        }
        val done = when (intent.action) {
            ACTION_START -> recorder.startRecording()
            ACTION_STOP -> recorder.stopRecording()
            else -> return
        }
        if (!done) Log.i(TAG, "${intent.action} ignored: ${recorder.state.value.recordControl}")
    }

    companion object {
        private const val TAG = "RecordingControl"
        const val ACTION_START = "com.boko.roudio.action.START_RECORDING"
        const val ACTION_STOP = "com.boko.roudio.action.STOP_RECORDING"

        /** "Not this one" on a nudge: only closes it. */
        const val ACTION_DISMISS = "com.boko.roudio.action.DISMISS_NUDGE"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** [notificationId]: a nudge to close when the button is tapped (0 for none). */
        fun pendingIntent(context: Context, action: String, notificationId: Int = 0): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode() * 31 + notificationId,
            Intent(context, RecordingControlReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
