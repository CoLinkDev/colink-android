package com.colink.android.domain.model

private const val EXPIRY_BUFFER_MILLIS = 60_000L

data class Session(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
    val email: String? = null,
) {
    fun isExpiringSoon(now: Long = System.currentTimeMillis()): Boolean =
        accessTokenExpiresAt <= now + EXPIRY_BUFFER_MILLIS
}
