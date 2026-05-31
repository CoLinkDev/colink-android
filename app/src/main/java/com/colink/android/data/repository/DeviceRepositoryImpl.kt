package com.colink.android.data.repository

import android.os.Build
import com.colink.android.crypto.KeyManager
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.toEntity
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import com.colink.android.data.remote.api.DeviceApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.DeviceNameUpdateRequestDto
import com.colink.android.data.remote.dto.DeviceRegisterRequestDto
import com.colink.android.data.remote.dto.DeviceRotateKeyRequestDto
import com.colink.android.data.remote.dto.requireData
import com.colink.android.data.remote.dto.requireOk
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.model.Session
import com.colink.android.domain.repository.DeviceRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceApi: DeviceApi,
    private val settingsDataStore: SettingsDataStore,
    private val deviceDao: DeviceDao,
    private val trustedPeerKeyDao: TrustedPeerKeyDao,
    private val keyManager: KeyManager,
) : DeviceRepository {
    override val devices: Flow<List<Device>> =
        deviceDao.observeDevices().map { entities -> entities.map { it.toDomain() } }

    override suspend fun ensureDeviceIdentity(session: Session): Result<DeviceIdentity> =
        runCatching {
            val identity = ensureLocalDeviceIdentity(session.userId)
            if (identity.userId == session.userId && identity.deviceSecret.isNotBlank()) {
                identity
            } else {
                registerLocalDevice(session, identity)
            }
        }

    override suspend fun localDeviceIdentity(): DeviceIdentity? =
        settingsDataStore.currentDeviceIdentity()

    override suspend fun syncDevices(session: Session): Result<List<Device>> =
        runCatching {
            val response = deviceApi
                .listDevices(
                    url = apiEndpoint(settingsDataStore.currentSettings().serverUrl, "/api/v1/devices"),
                    authorization = bearer(session.accessToken),
                )
                .requireData()
            val previous = deviceDao.getDevices().associateBy { it.deviceId }
            val devices = response.devices.map { dto ->
                val incoming = dto.toDomain()
                val cached = previous[incoming.deviceId]
                incoming.copy(
                    lanAvailable = cached?.lanAvailable ?: false,
                    localIp = incoming.localIp ?: cached?.localIp,
                    localPort = incoming.localPort ?: cached?.localPort,
                    cloudAvailable = incoming.online,
                    activeRoute = when {
                        cached?.lanAvailable == true -> "lan"
                        incoming.online -> "cloud"
                        else -> null
                    },
                )
            }
            deviceDao.clear()
            deviceDao.upsertAll(devices.map { it.toEntity() })
            devices
                .filter { it.publicKey.isNotBlank() }
                .forEach { device ->
                    val existing = trustedPeerKeyDao.get(device.deviceId)
                    if (
                        existing == null ||
                        (
                            device.publicKeyUpdatedAt != null &&
                                device.publicKeyUpdatedAt > existing.keyUpdatedAt
                            )
                    ) {
                        trustedPeerKeyDao.upsert(
                            TrustedPeerKeyEntity(
                                deviceId = device.deviceId,
                                name = device.name,
                                publicKey = device.publicKey,
                                keyUpdatedAt = device.publicKeyUpdatedAt ?: existing?.keyUpdatedAt ?: 0,
                                trustedAt = existing?.trustedAt,
                            ),
                        )
                    }
                }
            devices
        }

    override suspend fun getDevice(deviceId: String): Device? =
        deviceDao.getDevice(deviceId)?.toDomain()

    override suspend fun markLanEndpoint(deviceId: String, ip: String, port: Int): Result<Unit> =
        runCatching {
            val current = deviceDao.getDevice(deviceId)?.toDomain() ?: return@runCatching
            deviceDao.upsertAll(
                listOf(
                    current.copy(
                        localIp = ip,
                        localPort = port,
                        lanAvailable = true,
                        activeRoute = "lan",
                        online = true,
                    ).toEntity(),
                ),
            )
        }

    override suspend fun clearLanEndpoint(deviceId: String): Result<Unit> =
        runCatching {
            val current = deviceDao.getDevice(deviceId)?.toDomain() ?: return@runCatching
            deviceDao.upsertAll(
                listOf(
                    current.copy(
                        localIp = null,
                        localPort = null,
                        lanAvailable = false,
                        online = current.cloudAvailable,
                        activeRoute = if (current.cloudAvailable) "cloud" else null,
                    ).toEntity(),
                ),
            )
        }

    override suspend fun clearAllLanEndpoints(): Result<Unit> =
        runCatching {
            deviceDao.clearAllLanEndpoints()
        }

    override suspend fun updateDeviceName(deviceId: String, name: String): Result<Unit> =
        runCatching {
            val session = requireStoredSession()
            deviceApi
                .updateDeviceName(
                    url = apiEndpoint(
                        settingsDataStore.currentSettings().serverUrl,
                        "/api/v1/devices/$deviceId",
                    ),
                    authorization = bearer(session.accessToken),
                    request = DeviceNameUpdateRequestDto(name.trim()),
                )
                .requireOk()

            val identity = settingsDataStore.currentDeviceIdentity()
            if (identity?.deviceId == deviceId) {
                settingsDataStore.saveDeviceIdentity(identity.copy(name = name.trim()))
            }
            syncDevices(session).getOrThrow()
        }

    override suspend fun deleteDevice(deviceId: String): Result<Unit> =
        runCatching {
            val session = requireStoredSession()
            deviceApi
                .deleteDevice(
                    url = apiEndpoint(
                        settingsDataStore.currentSettings().serverUrl,
                        "/api/v1/devices/$deviceId",
                    ),
                    authorization = bearer(session.accessToken),
                )
                .requireOk()
            deviceDao.delete(deviceId)

            if (settingsDataStore.currentDeviceIdentity()?.deviceId == deviceId) {
                settingsDataStore.clearDeviceIdentity()
                settingsDataStore.clearSession()
                deviceDao.clear()
            }
        }

    override suspend fun rotateDeviceKey(deviceId: String): Result<Unit> =
        runCatching {
            val session = requireStoredSession()
            val generated = keyManager.generateKeyPair()
            deviceApi
                .rotateDeviceKey(
                    url = apiEndpoint(
                        settingsDataStore.currentSettings().serverUrl,
                        "/api/v1/devices/$deviceId/key",
                    ),
                    authorization = bearer(session.accessToken),
                    request = DeviceRotateKeyRequestDto(generated.publicKey),
                )
                .requireOk()

            val identity = settingsDataStore.currentDeviceIdentity()
            if (identity?.deviceId == deviceId) {
                settingsDataStore.saveDeviceIdentity(
                    identity.copy(
                        publicKey = generated.publicKey,
                        privateKey = generated.privateKey,
                    ),
                )
            }
            trustedPeerKeyDao.delete(deviceId)
            syncDevices(session).getOrThrow()
        }

    private suspend fun ensureLocalDeviceIdentity(userId: String): DeviceIdentity {
        val existing = settingsDataStore.currentDeviceIdentity()
        if (existing != null && existing.userId == userId) {
            return existing
        }
        val generated = keyManager.generateKeyPair()
        val settings = settingsDataStore.currentSettings()
        val name = settings.deviceName.ifBlank { Build.MODEL.ifBlank { "Android" } }
        val identity = DeviceIdentity(
            userId = userId,
            deviceId = UUID.randomUUID().toString(),
            deviceSecret = "",
            name = name,
            type = "android",
            publicKey = generated.publicKey,
            privateKey = generated.privateKey,
        )
        settingsDataStore.saveDeviceIdentity(identity)
        return identity
    }

    private suspend fun registerLocalDevice(
        session: Session,
        identity: DeviceIdentity,
    ): DeviceIdentity {
        val settings = settingsDataStore.currentSettings()
        val name = settings.deviceName.ifBlank { identity.name.ifBlank { Build.MODEL.ifBlank { "Android" } } }
        val response = deviceApi
            .registerDevice(
                url = apiEndpoint(settings.serverUrl, "/api/v1/devices"),
                authorization = bearer(session.accessToken),
                request = DeviceRegisterRequestDto(
                    deviceId = identity.deviceId,
                    name = name,
                    type = identity.type,
                    publicKey = identity.publicKey,
                ),
            )
            .requireData()

        val registered = DeviceIdentity(
            userId = session.userId,
            deviceId = response.deviceId.ifBlank { identity.deviceId },
            deviceSecret = response.deviceSecret,
            name = name,
            type = identity.type,
            publicKey = identity.publicKey,
            privateKey = identity.privateKey,
        )
        settingsDataStore.saveDeviceIdentity(registered)
        return registered
    }

    private suspend fun requireStoredSession(): Session =
        settingsDataStore.currentSession() ?: error("not logged in")

    private fun bearer(token: String): String = "Bearer $token"
}
