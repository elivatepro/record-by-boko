package com.boko.roudio.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.Response

/** The `detail` of a Roudio API error: a stable code plus a plain-language message. */
@Serializable
data class ApiErrorDetail(val code: String, val message: String, val email: String? = null)

@Serializable
private data class ErrorBody(val detail: ApiErrorDetail)

private val json = Json { ignoreUnknownKeys = true }

fun Response<*>.errorDetail(): ApiErrorDetail? = runCatching {
    json.decodeFromString<ErrorBody>(errorBody()?.string().orEmpty()).detail
}.getOrNull()

class ApiException(val detail: ApiErrorDetail?, httpCode: Int) :
    Exception(detail?.message ?: "Roudio's server answered $httpCode. Try again.")
