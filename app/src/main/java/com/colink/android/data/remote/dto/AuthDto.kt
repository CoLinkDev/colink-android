package com.colink.android.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    val identifier: String,
    val password: String,
)

@Serializable
data class RegisterRequestDto(
    val email: String,
    val username: String,
    val password: String,
)

@Serializable
data class RefreshRequestDto(
    val refreshToken: String,
)

@Serializable
data class LogoutRequestDto(
    val refreshToken: String,
)

@Serializable
data class AuthResponseDto(
    val userId: String,
    val token: String,
    val refreshToken: String,
    val expiresIn: Long? = null,
    val refreshExpiresIn: Long? = null,
)

@Serializable
data class RefreshResponseDto(
    val token: String,
    val refreshToken: String,
    val expiresIn: Long? = null,
    val refreshExpiresIn: Long? = null,
)

@Serializable
data class UserProfileDto(
    val userId: String,
    val username: String,
    val email: String,
)
