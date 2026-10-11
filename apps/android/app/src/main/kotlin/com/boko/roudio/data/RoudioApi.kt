package com.boko.roudio.data

import com.boko.roudio.BuildConfig
import com.boko.roudio.api.apis.AuthApi
import com.boko.roudio.api.apis.BackupsApi
import com.boko.roudio.api.apis.LibraryApi
import com.boko.roudio.api.apis.OrganiseApi
import com.boko.roudio.api.apis.PlaudApi
import com.boko.roudio.api.apis.RecordingsApi
import com.boko.roudio.api.infrastructure.Serializer
import com.boko.roudio.api.models.RefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Retrofit services from the generated client, with Roudio's session attached. */
class RoudioApi(private val session: SessionStore, baseUrl: String = BuildConfig.API_URL) {
    private val converter = Serializer.kotlinxSerializationJson.asConverterFactory("application/json".toMediaType())
    private val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    // Used only to refresh the session; it has no interceptors of its own.
    private val refreshAuth: AuthApi = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .addConverterFactory(converter)
        .client(OkHttpClient())
        .build()
        .create(AuthApi::class.java)

    private val client = OkHttpClient.Builder()
        .addInterceptor(AttachToken())
        .authenticator(RefreshOn401())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .addConverterFactory(converter)
        .client(client)
        .build()

    val auth: AuthApi = retrofit.create(AuthApi::class.java)
    val plaud: PlaudApi = retrofit.create(PlaudApi::class.java)
    val recordings: RecordingsApi = retrofit.create(RecordingsApi::class.java)
    val library: LibraryApi = retrofit.create(LibraryApi::class.java)
    val organise: OrganiseApi = retrofit.create(OrganiseApi::class.java)
    val backups: BackupsApi = retrofit.create(BackupsApi::class.java)

    @Synchronized
    private fun refresh(): String? {
        session.accessTokenIfFresh()?.let { return it }
        val refreshToken = session.refreshToken ?: return null
        val response = runBlocking { refreshAuth.refreshSession(RefreshRequest(refreshToken)) }
        val tokens = response.body()
        if (response.code() == 401) session.clear()
        if (!response.isSuccessful || tokens == null) return null
        session.store(tokens.accessToken, tokens.accessExpiresAt.toInstant(), tokens.refreshToken)
        return tokens.accessToken
    }

    private inner class AttachToken : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.encodedPath in UNAUTHENTICATED) return chain.proceed(request)
            val token = session.accessTokenIfFresh() ?: refresh()
            return chain.proceed(request.withToken(token))
        }
    }

    private inner class RefreshOn401 : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.url.encodedPath in UNAUTHENTICATED) return null
            if (response.priorResponse != null) return null // already retried once
            val token = refresh() ?: return null
            return response.request.withToken(token)
        }
    }

    private fun Request.withToken(token: String?): Request =
        if (token == null) this else newBuilder().header("Authorization", "Bearer $token").build()

    private companion object {
        val UNAUTHENTICATED = setOf("/auth/google", "/auth/refresh")
    }
}
