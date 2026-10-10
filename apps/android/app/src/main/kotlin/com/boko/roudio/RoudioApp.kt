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

    val recorder by lazy {
        Recorder(PlaudRecorderSource(this, plaudTokens, appScope), PrefsRecorderStore(this), appScope)
    }
}
