package com.boko.roudio.data

import android.content.Context
import androidx.core.content.edit
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Roudio's own session. The access token stays in memory; the refresh token is kept in
 * app-private storage so the app stays signed in across restarts.
 * TODO(milestone 7): wrap the refresh token with an Android Keystore key.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Volatile var accessToken: String? = null
        private set
    @Volatile private var accessExpiresAt: Instant = Instant.EPOCH

    private val _signedIn = MutableStateFlow(refreshToken != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)

    fun accessTokenIfFresh(now: Instant = Instant.now()): String? =
        accessToken?.takeIf { accessExpiresAt.isAfter(now.plusSeconds(30)) }

    fun store(access: String, accessExpiresAt: Instant, refresh: String) {
        accessToken = access
        this.accessExpiresAt = accessExpiresAt
        prefs.edit { putString(KEY_REFRESH, refresh) }
        _signedIn.value = true
    }

    fun clear() {
        accessToken = null
        accessExpiresAt = Instant.EPOCH
        prefs.edit { remove(KEY_REFRESH) }
        _signedIn.value = false
    }

    private companion object {
        const val KEY_REFRESH = "refresh_token"
    }
}
