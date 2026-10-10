package com.boko.roudio

import android.app.Application
import com.boko.roudio.data.RoudioApi
import com.boko.roudio.data.SessionStore

class RoudioApp : Application() {
    val session by lazy { SessionStore(this) }
    val api by lazy { RoudioApi(session) }
}
