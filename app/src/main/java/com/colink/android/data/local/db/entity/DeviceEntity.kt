package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.colink.android.domain.model.Device

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val type: String,
    val online: Boolean,
    val lastSeen: String?,
    val publicKey: String,
    val publicKeyUpdatedAt: Long?,
    val localIp: String?,
    val localPort: Int?,
    val cloudAvailable: Boolean,
    val lanAvailable: Boolean,
    val activeRoute: String?,
) {
    fun toDomain(): Device =
        Device(
            deviceId = deviceId,
            name = name,
            type = type,
            online = online,
            lastSeen = lastSeen,
            publicKey = publicKey,
            publicKeyUpdatedAt = publicKeyUpdatedAt,
            localIp = localIp,
            localPort = localPort,
            cloudAvailable = cloudAvailable,
            lanAvailable = lanAvailable,
            activeRoute = activeRoute,
        )
}

fun Device.toEntity(): DeviceEntity =
    DeviceEntity(
        deviceId = deviceId,
        name = name,
        type = type,
        online = online,
        lastSeen = lastSeen,
        publicKey = publicKey,
        publicKeyUpdatedAt = publicKeyUpdatedAt,
        localIp = localIp,
        localPort = localPort,
        cloudAvailable = cloudAvailable,
        lanAvailable = lanAvailable,
        activeRoute = activeRoute,
    )
