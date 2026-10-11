package com.boko.roudio.ui.settings

import java.io.IOException
import kotlinx.serialization.SerializationException
import retrofit2.Response

/**
 * How the Search, Review, Settings, Updates and Backups screens call the server: no exceptions
 * reach the UI, only "got it" or "couldn't" (offline, server down or an error answer).
 */

/** The answer's body, or null when the server couldn't be reached or said no. */
suspend fun <T : Any> fetch(call: suspend () -> Response<T>): T? = try {
    val response = call()
    if (response.isSuccessful) response.body() else null
} catch (e: IOException) {
    null
} catch (e: SerializationException) {
    null
}

/** True when the server took the change. For calls that answer 204 with no body. */
suspend fun send(call: suspend () -> Response<*>): Boolean = try {
    call().isSuccessful
} catch (e: IOException) {
    false
}
