package com.colink.android.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiEnvelope<T>(
    val code: Int,
    val data: T? = null,
    val message: String,
)

@Serializable
data class ErrorEnvelope(
    val code: Int,
    val data: JsonElement? = null,
    val message: String,
)

fun <T> ApiEnvelope<T>.requireData(): T {
    if (code != 0) {
        throw ApiException(code, message)
    }
    return data ?: throw ApiException(code, "missing response data")
}

fun ApiEnvelope<*>.requireOk() {
    if (code != 0) {
        throw ApiException(code, message)
    }
}

class ApiException(
    val code: Int,
    override val message: String,
) : RuntimeException(message)
