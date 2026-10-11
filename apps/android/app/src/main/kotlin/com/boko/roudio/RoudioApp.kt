package com.boko.roudio

import android.app.Application
import com.boko.roudio.data.ApiException
import com.boko.roudio.data.RoudioApi
import com.boko.roudio.data.SessionStore
import com.boko.roudio.data.errorDetail
import com.boko.roudio.recorder.PlaudRecorderSource
import com.boko.roudio.recorder.PlaudToken
import com.boko.roudio.recorder.PlaudTokens
import com.boko.roudio.recorder.PrefsRecorderStore
import com.boko.roudio.recorder.Recorder
import com.boko.roudio.store.RecordingStore
import com.boko.roudio.system.NetworkMonitor
import com.boko.roudio.store.RoomRecordingStore
import com.boko.roudio.store.RoudioDatabase
import com.boko.roudio.transfer.RecordingFiles
import com.boko.roudio.transfer.Transfers
import com.boko.roudio.transfer.Uploader
import com.boko.roudio.transfer.Uploads
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RoudioApp : Application() {
    /** Lives as long as the process; the recorder connection outlives any screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session by lazy { SessionStore(this) }
    val api by lazy { RoudioApi(session) }

    val plaudTokens by lazy {
        PlaudTokens(this) {
            val response = api.plaud.getPlaudToken()
            val token = response.body() ?: throw ApiException(response.errorDetail(), response.code())
            PlaudToken(token.accessToken, token.domain, token.expiresAt.toInstant())
        }
    }

    val plaudSource by lazy { PlaudRecorderSource(this, plaudTokens, appScope) }

    val recorder by lazy { Recorder(plaudSource, PrefsRecorderStore(this), appScope) }

    val database by lazy { RoudioDatabase.open(this) }
    val recordings: RecordingStore by lazy { RoomRecordingStore(database.recordings()) }
    val recordingFiles by lazy { RecordingFiles(File(filesDir, "recordings")) }

    /** Started by [com.boko.roudio.recorder.RecorderService], so it runs with the screen off. */
    val transfers by lazy {
        Transfers(plaudSource, recorder, recordings, recordingFiles, appScope, onPhone = { Uploads.enqueue(this) })
    }

    val network by lazy { NetworkMonitor(this) }

    val uploader by lazy { Uploader(api.recordings, recordings, recordingFiles) }

    override fun onCreate() {
        super.onCreate()
        // Anything left waiting from before (no network, app stopped) goes up when it can.
        Uploads.enqueue(this)
    }
}
