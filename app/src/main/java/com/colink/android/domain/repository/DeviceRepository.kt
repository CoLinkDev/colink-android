package com.colink.android.domain.repository

import com.colink.android.domain.model.Device
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface DeviceRepository {
    val devices: Flow<List<Device>>

    suspend fun ensureLocalDeviceIdentity(): Result<DeviceIdentity>

    suspend fun ensureDeviceIdentity(session: Session): Result<DeviceIdentity>

    suspend fun localDeviceIdentity(): DeviceIdentity?

    suspend fun syncDevices(session: Session): Result<List<Device>>

    suspend fun syncPendingDeviceKey(session: Session): Result<Unit>

    suspend fun getDevice(deviceId: String): Device?

    suspend fun markCloudPresence(
        deviceId: String,
        online: Boolean,
        name: String? = null,
        deviceType: String? = null,
    ): Result<Unit>

    suspend fun clearCloudPresence(): Result<Unit>

    suspend fun markLanEndpoint(
        deviceId: String,
        ip: String? = null,
        port: Int? = null,
        deviceType: String? = null,
        lanState: String = "alive",
    ): Result<Unit>

    suspend fun clearLanEndpoint(deviceId: String): Result<Unit>

    suspend fun clearAllLanEndpoints(): Result<Unit>

    suspend fun resetDevicePresence(): Result<Unit>

    suspend fun listLocalDevices(): Result<List<Device>>

    suspend fun updateDeviceName(deviceId: String, name: String): Result<Unit>

    suspend fun deleteDevice(deviceId: String): Result<Unit>

    suspend fun rotateDeviceKey(deviceId: String): Result<Unit>

    suspend fun forgetLanTrust(deviceId: String): Result<Unit>

    suspend fun clearCloudTrust(): Result<Unit>
}
