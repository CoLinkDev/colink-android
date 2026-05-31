package com.colink.android.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WsTicketRequestDto(
    val deviceId: String,
)

@Serializable
data class WsTicketResponseDto(
    val ticket: String,
    val expiresIn: Int = 30,
)
