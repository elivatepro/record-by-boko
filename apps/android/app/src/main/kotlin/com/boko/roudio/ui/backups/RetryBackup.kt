package com.boko.roudio.ui.backups

import com.boko.roudio.BuildConfig
import com.boko.roudio.RoudioApp
import com.boko.roudio.ui.settings.send
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Asks the server to back a recording up again (POST /recordings/{id}/backup, 03 and 14).
 *
 * The generated `BackupsApi.retryBackup` returns `Response<Any>`, and the kotlinx converter can't
 * build a converter for `Any`, so that call throws before it's sent. Until the spec gives the
 * endpoint a typed (or empty) answer, the request is made here with the session's token.
 */
suspend fun retryBackup(app: RoudioApp, recordingId: UUID): Boolean = withContext(Dispatchers.IO) {
    // Any call through the shared client refreshes an expired session first.
    if (app.session.accessTokenIfFresh() == null) send { app.api.backups.getSettings() }
    val token = app.session.accessTokenIfFresh() ?: return@withContext false
    val request = Request.Builder()
        .url("${BuildConfig.API_URL.trimEnd('/')}/recordings/$recordingId/backup")
        .header("Authorization", "Bearer $token")
        .post(ByteArray(0).toRequestBody())
        .build()
    try {
        client.newCall(request).execute().use { it.isSuccessful }
    } catch (e: IOException) {
        false
    }
}

/** Where a "Retry backup" button stands. */
enum class RetryState { Idle, Working, Started, Failed }

private val client = OkHttpClient()
