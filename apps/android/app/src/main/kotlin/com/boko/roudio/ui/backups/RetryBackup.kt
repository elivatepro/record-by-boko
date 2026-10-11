package com.boko.roudio.ui.backups

import com.boko.roudio.RoudioApp
import java.io.IOException
import java.util.UUID

/** Asks the server to back a recording up again (POST /recordings/{id}/backup, 03 and 14). */
suspend fun retryBackup(app: RoudioApp, recordingId: UUID): Boolean = try {
    app.api.backups.retryBackup(recordingId).isSuccessful
} catch (e: IOException) {
    false
}

/** Where a "Retry backup" button stands. */
enum class RetryState { Idle, Working, Started, Failed }
