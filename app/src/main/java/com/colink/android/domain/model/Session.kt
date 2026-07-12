package com.colink.android.domain.model

data class Session(
    val userId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresAt: Long,
    val accessTokenRefreshAt: Long,
    val username: String? = null,
    val email: String? = null,
) {
    fun isExpiringSoon(now: Long = System.currentTimeMillis()): Boolean =
        accessTokenRefreshAt <= now
}
