package com.colink.android.data.repository

import com.colink.android.crypto.KeyManager
import com.colink.android.data.local.DeviceNameProvider
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import com.colink.android.data.local.db.entity.toEntity
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
import com.colink.android.util.CoLinkLog
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
    private val deviceNameProvider: DeviceNameProvider,
) : DeviceRepository {
    override val devices: Flow<List<Device>> =
        deviceDao.observeDevices().map { entities -> entities.map { it.toDomain() } }

    override suspend fun ensureLocalDeviceIdentity(): Result<DeviceIdentity> =
        runCatching {
            ensureLocalDeviceIdentityRecord()
        }

    override suspend fun ensureDeviceIdentity(session: Session): Result<DeviceIdentity> =
        runCatching {
            val identity = ensureLocalDeviceIdentityRecord()
            when {
                identity.userId == null -> registerLocalDevice(session, identity)
                identity.userId == session.userId && !identity.deviceSecret.isNullOrBlank() -> {
                    syncLocalDeviceKeyIfPending(session, identity)
                    identity
                }
                identity.userId == session.userId -> registerLocalDevice(session, identity)
                else -> error("current device is bound to another account")
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
            val previous = deviceDao.getDevices().map { it.toDomain() }
            val localIdentity = settingsDataStore.currentDeviceIdentity()
            val cloudDevices = response.devices.map { dto ->
                val incoming = dto.toDomain().copy(deviceSources = listOf("cloud"))
                val cached = previous.firstOrNull { it.deviceId == incoming.deviceId }
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
                    securityState = if (cached?.lanAvailable == true) {
                        "verified"
                    } else {
                        cached?.securityState?.takeIf { it != "unverified" } ?: "unverified"
                    },
                )
            }

            ensureTrustedPeerKeysForDevices(cloudDevices, localIdentity?.deviceId)
            val reconciled = reconcileDevices(
                incoming = cloudDevices,
                previous = previous,
                localIdentity = localIdentity,
            )
            saveDevices(reconciled)
            CoLinkLog.i("Device", "synced devices count=${reconciled.size}")
            reconciled
        }

    override suspend fun getDevice(deviceId: String): Device? =
        deviceDao.getDevice(deviceId)?.toDomain()

    override suspend fun markLanEndpoint(
        deviceId: String,
        ip: String,
        port: Int,
        deviceType: String?,
    ): Result<Unit> =
        runCatching {
            val current = deviceDao.getDevice(deviceId)?.toDomain()
                ?: trustedPeerDevice(deviceId)
                ?: return@runCatching
            saveDevices(
                listOf(
                    current.copy(
                        type = reconcileDeviceType(current.type, lanType = deviceType),
                        localIp = ip,
                        localPort = port,
                        lanAvailable = true,
                        activeRoute = "lan",
                        online = true,
                        securityState = "verified",
                    ),
                ),
                replaceAll = false,
            )
            CoLinkLog.i(
                "Device",
                "marked LAN endpoint device=${CoLinkLog.shortId(deviceId)} ip=$ip port=$port",
            )
        }

    override suspend fun clearLanEndpoint(deviceId: String): Result<Unit> =
        runCatching {
            val current = deviceDao.getDevice(deviceId)?.toDomain() ?: return@runCatching
            saveDevices(
                listOf(
                    current.copy(
                        localIp = null,
                        localPort = null,
                        lanAvailable = false,
                        online = current.cloudAvailable,
                        activeRoute = if (current.cloudAvailable) "cloud" else null,
                    ),
                ),
                replaceAll = false,
            )
            CoLinkLog.i("Device", "cleared LAN endpoint device=${CoLinkLog.shortId(deviceId)}")
        }

    override suspend fun clearAllLanEndpoints(): Result<Unit> =
        runCatching {
            deviceDao.clearAllLanEndpoints()
            listLocalDevices().getOrThrow()
            CoLinkLog.i("Device", "cleared all LAN endpoints")
        }

    override suspend fun listLocalDevices(): Result<List<Device>> =
        runCatching {
            val previous = deviceDao.getDevices().map { it.toDomain() }
            val localIdentity = settingsDataStore.currentDeviceIdentity()
            val reconciled = reconcileDevices(
                incoming = emptyList(),
                previous = previous,
                localIdentity = localIdentity,
                keepCloudState = settingsDataStore.currentSession() != null,
            )
            saveDevices(reconciled)
            reconciled
        }

    override suspend fun updateDeviceName(deviceId: String, name: String): Result<Unit> =
        runCatching {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "device name is empty" }
            val identity = settingsDataStore.currentDeviceIdentity()
            if (identity?.deviceId == deviceId) {
                val updated = identity.copy(name = trimmed)
                settingsDataStore.saveDeviceIdentity(updated)
                val session = settingsDataStore.currentSession()
                if (session != null && updated.userId == session.userId) {
                    syncLocalDeviceName(session, updated)
                }
                listLocalDevices().getOrThrow()
                return@runCatching
            }

            val session = requireStoredSession()
            deviceApi
                .updateDeviceName(
                    url = apiEndpoint(
                        settingsDataStore.currentSettings().serverUrl,
                        "/api/v1/devices/$deviceId",
                    ),
                    authorization = bearer(session.accessToken),
                    request = DeviceNameUpdateRequestDto(trimmed),
                )
                .requireOk()
            syncDevices(session).getOrThrow()
        }

    override suspend fun deleteDevice(deviceId: String): Result<Unit> =
        runCatching {
            require(settingsDataStore.currentDeviceIdentity()?.deviceId != deviceId) {
                "the local device cannot be deleted here"
            }
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
            syncDevices(session).getOrThrow()
        }

    override suspend fun rotateDeviceKey(deviceId: String): Result<Unit> =
        runCatching {
            val identity = settingsDataStore.currentDeviceIdentity()
            require(identity?.deviceId == deviceId) {
                "only the local device key can be rotated here"
            }

            val generated = keyManager.generateKeyPair()
            val rotated = identity.copy(
                publicKey = generated.publicKey,
                privateKey = generated.privateKey,
                cloudKeySyncPending = true,
            )
            settingsDataStore.saveDeviceIdentity(rotated)

            val session = settingsDataStore.currentSession()
            if (session != null && rotated.userId == session.userId) {
                syncLocalDeviceKeyIfPending(session, rotated)
                syncDevices(session).getOrThrow()
            } else {
                listLocalDevices().getOrThrow()
            }
        }

    override suspend fun forgetLanTrust(deviceId: String): Result<Unit> =
        runCatching {
            trustedPeerKeyDao.delete(deviceId)
            deviceDao.delete(deviceId)
            listLocalDevices().getOrThrow()
        }

    private suspend fun ensureLocalDeviceIdentityRecord(): DeviceIdentity {
        val existing = settingsDataStore.currentDeviceIdentity()
        if (existing != null) {
            return existing
        }

        val generated = keyManager.generateKeyPair()
        val name = deviceNameProvider.defaultDeviceName()
        return DeviceIdentity(
            userId = null,
            deviceId = UUID.randomUUID().toString(),
            deviceSecret = null,
            name = name,
            type = "android",
            publicKey = generated.publicKey,
            privateKey = generated.privateKey,
        ).also { identity ->
            settingsDataStore.saveDeviceIdentity(identity)
            CoLinkLog.i(
                "Device",
                "created local identity device=${CoLinkLog.shortId(identity.deviceId)} name=${identity.name}",
            )
        }
    }

    private suspend fun registerLocalDevice(
        session: Session,
        identity: DeviceIdentity,
    ): DeviceIdentity {
        val settings = settingsDataStore.currentSettings()
        val name = identity.name.ifBlank { deviceNameProvider.defaultDeviceName() }
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

        return identity.copy(
            userId = session.userId,
            deviceId = response.deviceId.ifBlank { identity.deviceId },
            deviceSecret = response.deviceSecret,
            name = name,
            cloudKeySyncPending = false,
        ).also { registered ->
            settingsDataStore.saveDeviceIdentity(registered)
            CoLinkLog.i(
                "Device",
                "registered local device device=${CoLinkLog.shortId(registered.deviceId)} name=${registered.name}",
            )
        }
    }

    private suspend fun syncLocalDeviceName(session: Session, identity: DeviceIdentity) {
        runCatching {
            deviceApi
                .updateDeviceName(
                    url = apiEndpoint(
                        settingsDataStore.currentSettings().serverUrl,
                        "/api/v1/devices/${identity.deviceId}",
                    ),
                    authorization = bearer(session.accessToken),
                    request = DeviceNameUpdateRequestDto(identity.name),
                )
                .requireOk()
            CoLinkLog.i(
                "Device",
                "synced local device name device=${CoLinkLog.shortId(identity.deviceId)} name=${identity.name}",
            )
        }.onFailure { error ->
            CoLinkLog.w(
                "Device",
                "failed to sync local device name device=${CoLinkLog.shortId(identity.deviceId)}",
                error,
            )
        }
    }

    private suspend fun syncLocalDeviceKeyIfPending(
        session: Session,
        identity: DeviceIdentity,
    ) {
        if (!identity.cloudKeySyncPending || identity.userId != session.userId) {
            return
        }
        runCatching {
            deviceApi
                .rotateDeviceKey(
                    url = apiEndpoint(
                        settingsDataStore.currentSettings().serverUrl,
                        "/api/v1/devices/${identity.deviceId}/key",
                    ),
                    authorization = bearer(session.accessToken),
                    request = DeviceRotateKeyRequestDto(identity.publicKey),
                )
                .requireOk()
        }.onSuccess {
            val latest = settingsDataStore.currentDeviceIdentity()
            if (latest?.deviceId == identity.deviceId && latest.publicKey == identity.publicKey) {
                settingsDataStore.saveDeviceIdentity(latest.copy(cloudKeySyncPending = false))
            }
        }
    }

    private suspend fun ensureTrustedPeerKeysForDevices(
        devices: List<Device>,
        localDeviceId: String?,
    ) {
        devices
            .filter { it.deviceId != localDeviceId && it.publicKey.isNotBlank() }
            .forEach { device ->
                val existing = trustedPeerKeyDao.get(device.deviceId)
                val shouldUpdateKey = existing == null ||
                    (device.publicKeyUpdatedAt != null && device.publicKeyUpdatedAt > existing.keyUpdatedAt)
                val nextKey = if (shouldUpdateKey) device.publicKey else existing.publicKey
                val nextUpdatedAt = if (shouldUpdateKey) {
                    device.publicKeyUpdatedAt ?: existing?.keyUpdatedAt ?: 0L
                } else {
                    existing.keyUpdatedAt
                }
                trustedPeerKeyDao.upsert(
                    TrustedPeerKeyEntity(
                        deviceId = device.deviceId,
                        name = device.name,
                        publicKey = nextKey,
                        keyUpdatedAt = nextUpdatedAt,
                        trustedAt = if (shouldUpdateKey && existing != null) null else existing?.trustedAt,
                    ),
                )
            }
    }

    private suspend fun reconcileDevices(
        incoming: List<Device>,
        previous: List<Device>,
        localIdentity: DeviceIdentity?,
        keepCloudState: Boolean = true,
    ): List<Device> {
        val previousById = previous.associateBy { it.deviceId }
        val incomingById = incoming.associateBy { it.deviceId }.toMutableMap()
        if (localIdentity != null && !incomingById.containsKey(localIdentity.deviceId)) {
            previousById[localIdentity.deviceId]?.let { incomingById[localIdentity.deviceId] = it }
        }

        val devices = incomingById.values.map { device ->
            if (localIdentity?.deviceId == device.deviceId) {
                return@map localDeviceInfo(localIdentity, device, keepCloudState)
            }
            val existing = previousById[device.deviceId]
            val lanAvailable = existing?.lanAvailable == true || device.lanAvailable
            val cloudAvailable = device.cloudAvailable
            device.copy(
                localIp = device.localIp ?: existing?.localIp,
                localPort = device.localPort ?: existing?.localPort,
                lanAvailable = lanAvailable,
                cloudAvailable = cloudAvailable,
                online = cloudAvailable || lanAvailable,
                activeRoute = when {
                    lanAvailable -> "lan"
                    cloudAvailable -> "cloud"
                    else -> null
                },
                securityState = when {
                    lanAvailable -> "verified"
                    device.securityState != "unverified" -> device.securityState
                    else -> existing?.securityState ?: "unverified"
                },
                type = reconcileDeviceType(
                    incoming = device.type,
                    previous = existing?.type,
                ),
                deviceSources = mergeSources(device.deviceSources),
            )
        }.toMutableList()

        val knownIds = devices.map { it.deviceId }.toSet()
        trustedPeerKeyDao.getAll()
            .filter { it.deviceId != localIdentity?.deviceId && it.deviceId !in knownIds }
            .forEach { record ->
                val existing = previousById[record.deviceId]
                val lanAvailable = existing?.lanAvailable == true
                devices += Device(
                    deviceId = record.deviceId,
                    name = record.name,
                    type = reconcileDeviceType(existing?.type ?: "unknown"),
                    online = lanAvailable,
                    lastSeen = null,
                    publicKey = record.publicKey,
                    publicKeyUpdatedAt = record.keyUpdatedAt,
                    localIp = existing?.localIp,
                    localPort = existing?.localPort,
                    cloudAvailable = false,
                    lanAvailable = lanAvailable,
                    activeRoute = if (lanAvailable) "lan" else null,
                    deviceSources = listOf("trusted_peer_key"),
                    securityState = "verified",
                )
            }

        if (localIdentity != null && devices.none { it.deviceId == localIdentity.deviceId }) {
            devices += localDeviceInfo(localIdentity, keepCloudState = keepCloudState)
        }

        return devices.sortedWith(
            compareByDescending<Device> { it.online }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.deviceId },
        )
    }

    private suspend fun trustedPeerDevice(deviceId: String): Device? {
        val record = trustedPeerKeyDao.get(deviceId) ?: return null
        return Device(
            deviceId = record.deviceId,
            name = record.name,
            type = "unknown",
            online = false,
            lastSeen = null,
            publicKey = record.publicKey,
            publicKeyUpdatedAt = record.keyUpdatedAt,
            cloudAvailable = false,
            lanAvailable = false,
            deviceSources = listOf("trusted_peer_key"),
            securityState = "verified",
        )
    }

    private fun localDeviceInfo(
        identity: DeviceIdentity,
        current: Device? = null,
        keepCloudState: Boolean = true,
    ): Device {
        val sources = if (keepCloudState) {
            mergeSources(current?.deviceSources.orEmpty(), "local")
        } else {
            listOf("local")
        }
        val cloudAvailable = keepCloudState &&
            sources.contains("cloud") &&
            current?.cloudAvailable == true

        return Device(
            deviceId = identity.deviceId,
            name = identity.name,
            type = identity.type,
            online = true,
            lastSeen = current?.lastSeen,
            publicKey = identity.publicKey,
            publicKeyUpdatedAt = current?.publicKeyUpdatedAt,
            localIp = null,
            localPort = null,
            cloudAvailable = cloudAvailable,
            lanAvailable = false,
            activeRoute = null,
            deviceSources = sources,
            securityState = "verified",
        )
    }

    private suspend fun saveDevices(
        devices: List<Device>,
        replaceAll: Boolean = true,
    ) {
        if (replaceAll) {
            deviceDao.clear()
        }
        if (devices.isNotEmpty()) {
            deviceDao.upsertAll(devices.map { it.toEntity() })
        }
    }

    private suspend fun requireStoredSession(): Session =
        settingsDataStore.currentSession() ?: error("not logged in")

    private fun mergeSources(
        current: List<String>,
        vararg extras: String,
    ): List<String> =
        (extras.toList() + current)
            .filter { it in setOf("local", "cloud", "trusted_peer_key") }
            .distinct()

    private fun reconcileDeviceType(
        incoming: String,
        previous: String? = null,
        lanType: String? = null,
    ): String {
        if (!incoming.isUnknownDeviceType()) {
            return incoming.trim()
        }
        val normalizedLanType = lanType.normalizedDeviceType()
        if (normalizedLanType != null) {
            return normalizedLanType
        }
        val normalizedPreviousType = previous.normalizedDeviceType()
        if (normalizedPreviousType != null) {
            return normalizedPreviousType
        }
        return incoming.trim().ifEmpty { "unknown" }
    }

    private fun String?.normalizedDeviceType(): String? {
        val value = this?.trim().orEmpty().lowercase()
        return value.takeIf { it in knownDeviceTypes }
    }

    private fun String.isUnknownDeviceType(): Boolean {
        val value = trim()
        return value.isEmpty() || value.equals("unknown", ignoreCase = true)
    }

    private val knownDeviceTypes = setOf("windows", "macos", "linux", "android", "ios")

    private fun bearer(token: String): String = "Bearer $token"
}
