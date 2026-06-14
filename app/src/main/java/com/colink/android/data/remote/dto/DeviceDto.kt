package com.colink.android.data.remote.dto

import com.colink.android.domain.model.Device
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRegisterRequestDto(
    val deviceId: String,
    val name: String,
    @SerialName("type") val type: String,
    val publicKey: String,
)

@Serializable
data class DeviceRegisterResponseDto(
    val deviceId: String,
)

@Serializable
data class DeviceListResponseDto(
    val devices: List<DeviceDto>,
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val name: String,
    @SerialName("type") val type: String,
    val online: Boolean,
    val lastSeen: String? = null,
    val publicKey: String,
    val publicKeyUpdatedAt: String? = null,
) {
    fun toDomain(): Device =
        Device(
            deviceId = deviceId,
            name = name,
            type = type,
            online = online,
            lastSeen = lastSeen,
            publicKey = publicKey,
            publicKeyUpdatedAt = parseTimestampMillis(publicKeyUpdatedAt),
            lanAvailable = false,
            lanState = "unavailable",
            cloudAvailable = online,
            activeRoute = if (online) "cloud" else null,
        )
}

@Serializable
data class DeviceNameUpdateRequestDto(
    val name: String,
)

@Serializable
data class DeviceKeyUpdateRequestDto(
    val publicKey: String,
)

private fun parseTimestampMillis(value: String?): Long? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
        }
