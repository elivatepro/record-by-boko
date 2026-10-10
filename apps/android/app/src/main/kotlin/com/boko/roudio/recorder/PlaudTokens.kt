package com.boko.roudio.recorder

import android.content.Context
import androidx.core.content.edit
import java.time.Duration
import java.time.Instant

/** The Plaud SDK user token from our backend (`POST /plaud/token`). Never log it. */
data class PlaudToken(val accessToken: String, val domain: String, val expiresAt: Instant)

/**
 * Keeps the last Plaud token in app-private storage until it expires, so the recorder can
 * reconnect after a restart without internet. Renews it when less than [RENEW_BEFORE] is left.
 * TODO(milestone 7): wrap it with an Android Keystore key, like the session.
 */
class PlaudTokens(context: Context, private val fetch: suspend () -> PlaudToken) {
    private val prefs = context.getSharedPreferences("plaud", Context.MODE_PRIVATE)

    suspend fun current(now: Instant = Instant.now()): PlaudToken {
        val cached = cached()
        if (cached != null && cached.expiresAt.isAfter(now.plus(RENEW_BEFORE))) return cached
        return try {
            fetch().also(::save)
        } catch (e: Exception) {
            // Offline: a token that hasn't expired yet still works.
            cached?.takeIf { it.expiresAt.isAfter(now) } ?: throw e
        }
    }

    fun clear() = prefs.edit { clear() }

    private fun cached(): PlaudToken? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val domain = prefs.getString(KEY_DOMAIN, null) ?: return null
        return PlaudToken(token, domain, Instant.ofEpochMilli(prefs.getLong(KEY_EXPIRES, 0)))
    }

    private fun save(token: PlaudToken) = prefs.edit {
        putString(KEY_TOKEN, token.accessToken)
        putString(KEY_DOMAIN, token.domain)
        putLong(KEY_EXPIRES, token.expiresAt.toEpochMilli())
    }

    private companion object {
        val RENEW_BEFORE: Duration = Duration.ofHours(2)
        const val KEY_TOKEN = "token"
        const val KEY_DOMAIN = "domain"
        const val KEY_EXPIRES = "expires_at"
    }
}
