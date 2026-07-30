package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.colink.android.domain.model.Device

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val type: String,
    val lastSeen: String?,
    val publicKey: String,
    val publicKeyUpdatedAt: Long?,
    val deviceSources: String,
    val trustedByLan: Boolean,
    val trustedByCloud: Boolean,
    val securityState: String,
) {
    fun toDomain(): Device =
        Device(
            deviceId = deviceId,
            name = name,
            type = type,
            online = false,
            lastSeen = lastSeen,
            publicKey = publicKey,
            publicKeyUpdatedAt = publicKeyUpdatedAt,
            cloudAvailable = false,
            activeRoute = null,
            deviceSources = deviceSources
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            trustedByLan = trustedByLan,
            trustedByCloud = trustedByCloud,
            securityState = securityState,
        )
}

fun Device.toEntity(): DeviceEntity =
    DeviceEntity(
        deviceId = deviceId,
        name = name,
        type = type,
        lastSeen = lastSeen,
        publicKey = publicKey,
        publicKeyUpdatedAt = publicKeyUpdatedAt,
        deviceSources = deviceSources.distinct().joinToString(","),
        trustedByLan = trustedByLan,
        trustedByCloud = trustedByCloud,
        securityState = securityState,
    )
