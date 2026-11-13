package com.boko.roudio.recorder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.boko.roudio.RoudioApp

/**
 * Start / Stop from outside the app (the recorder notification, later the widget). [Recorder]
 * refuses anything that isn't safe, so a stale button can't start or stop on a guess.
 */
class RecordingControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recorder = (context.applicationContext as RoudioApp).recorder
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

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, RecordingControlReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
