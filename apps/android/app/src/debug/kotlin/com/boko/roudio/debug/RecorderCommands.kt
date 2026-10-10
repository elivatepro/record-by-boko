package com.boko.roudio.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.boko.roudio.RoudioApp
import com.boko.roudio.recorder.LinkPhase
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Debug builds only. Drives the recorder from adb:
 *
 *     adb shell am broadcast -n com.boko.roudio/.debug.RecorderCommands --es cmd search
 *
 * Commands: search, link [--es serial …] (default: nearest found), refresh, reconnect,
 * disconnect, unlink, state, binding --es serial … (asks Plaud's cloud who the recorder is bound
 * to; logs only whether it's bound and how many past bindings there are, never the ids).
 * Each one logs the resulting state under the tag "RecorderDebug".
 */
class RecorderCommands : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RoudioApp
        val recorder = app.recorder
        when (val cmd = intent.getStringExtra("cmd")) {
            "search" -> recorder.search()
            "link" -> {
                val found = (recorder.state.value.link as? LinkPhase.Searching)?.found.orEmpty()
                val serial = intent.getStringExtra("serial")
                val target = found.firstOrNull { serial == null || it.serial == serial }
                if (target == null) Log.w(TAG, "nothing to link; search first") else recorder.link(target)
            }
            "refresh" -> recorder.refresh()
            "reconnect" -> recorder.reconnect()
            "disconnect" -> recorder.clearLink()
            "unlink" -> {
                val pending = goAsync()
                app.appScope.launch {
                    recorder.unlink()
                    Log.i(TAG, "state ${recorder.state.value}")
                    pending.finish()
                }
                return
            }
            "binding" -> {
                val serial = intent.getStringExtra("serial") ?: return
                val pending = goAsync()
                app.appScope.launch {
                    runCatching { logBinding(app, serial) }.onFailure { Log.w(TAG, "binding query failed", it) }
                    pending.finish()
                }
                return
            }
            "state" -> Unit
            else -> Log.w(TAG, "unknown command $cmd")
        }
        Log.i(TAG, "state ${recorder.state.value}")
    }

    private fun logBinding(app: RoudioApp, serial: String) {
        val token = kotlinx.coroutines.runBlocking { app.plaudTokens.current() }
        val type = if (serial.startsWith("882")) "notepins" else "notepro"
        val request = Request.Builder()
            .url("https://${token.domain}/developer/api/open/partner/sdk/binding?type=$type&sn=$serial")
            .header("Authorization", "Bearer ${token.accessToken}")
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            val body = response.body.string()
            val json = runCatching { JSONObject(body) }.getOrNull()
            val history = json?.optJSONArray("bind_history")
            val ids = (0 until (history?.length() ?: 0)).map { history!!.optString(it) }
            Log.i(
                TAG,
                "binding: HTTP ${response.code} is_bind=${json?.opt("is_bind")} " +
                    "history=${ids.size} distinct=${ids.distinct().size} " +
                    "keys=${json?.keys()?.asSequence()?.toList()}",
            )
        }
    }

    private companion object {
        const val TAG = "RecorderDebug"
    }
}
