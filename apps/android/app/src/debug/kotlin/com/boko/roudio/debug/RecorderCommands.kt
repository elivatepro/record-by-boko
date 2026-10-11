package com.boko.roudio.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.boko.roudio.RoudioApp
import com.boko.roudio.recorder.LinkPhase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
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
 * to; logs only whether it's bound and how many past bindings there are, never the ids),
 * files (lists the recordings), export --el session … (copies one to the app's cache and logs
 * its size, header, duration and SHA-256; nothing is deleted from the recorder; add --ez wifi true
 * to copy it over the recorder's Wi-Fi and log how long joining took), transfer (asks the
 * transfer engine to check now), fast (the next check goes over Wi-Fi), recordings (logs the
 * saved recordings and their tracks).
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
            "files" -> {
                val pending = goAsync()
                app.appScope.launch {
                    runCatching { app.plaudSource.listFiles() }
                        .onSuccess { files -> files.forEach { Log.i(TAG, "file $it start=${it.startedAt}") } }
                        .onFailure { Log.w(TAG, "file list failed", it) }
                    pending.finish()
                }
                return
            }
            "export" -> {
                val session = intent.getLongExtra("session", -1)
                val wifi = intent.getBooleanExtra("wifi", false)
                val pending = goAsync()
                app.appScope.launch {
                    runCatching { exportOne(app, session, wifi) }.onFailure { Log.w(TAG, "export failed", it) }
                    pending.finish()
                }
                return
            }
            "transfer" -> app.transfers.check()
            "fast" -> app.transfers.fastTransfer()
            "recordings" -> {
                val pending = goAsync()
                app.appScope.launch {
                    app.recordings.recordings.first().forEach {
                        Log.i(
                            TAG,
                            "recording ${it.id} session ${it.sessionId} start ${it.startedAt} transfer ${it.transfer} " +
                                "attempts ${it.transferAttempts} error ${it.transferError} file ${it.fileName} " +
                                "${it.fileBytes} bytes ${it.durationMs} ms sha ${it.sha256?.take(12)}",
                        )
                    }
                    pending.finish()
                }
                return
            }
            "forceclear" -> app.plaudSource.forceClearOnFirstLink = intent.getBooleanExtra("on", true)
            "state" -> Unit
            else -> Log.w(TAG, "unknown command $cmd")
        }
        Log.i(TAG, "state ${recorder.state.value}")
    }

    private suspend fun exportOne(app: RoudioApp, session: Long, wifi: Boolean) {
        val file = app.plaudSource.listFiles().firstOrNull { it.sessionId == session }
        if (file == null) {
            Log.w(TAG, "no recording with session $session")
            return
        }
        val target = File(app.cacheDir, "debug-export/$session.opus")
        if (wifi) {
            val joining = System.currentTimeMillis()
            app.plaudSource.openWifi()
            Log.i(TAG, "wifi ready in ${(System.currentTimeMillis() - joining) / 1000.0}s")
        }
        val started = System.currentTimeMillis()
        var last = -1
        try {
            app.plaudSource.export(file, target, wifi) { p -> if (p / 10 != last / 10) Log.i(TAG, "export $p%").also { last = p } }
        } finally {
            if (wifi) app.plaudSource.closeWifi()
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        val head = ByteArray(64).also { buffer -> target.inputStream().use { it.read(buffer) } }
        val sha = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        Log.i(
            TAG,
            "exported ${target.length()} bytes (recorder said ${file.sizeBytes}) in ${seconds}s; " +
                "starts ${String(head.copyOf(4), Charsets.US_ASCII)}; tags at ${String(head).indexOf("OpusHead")}; sha256 $sha",
        )
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
