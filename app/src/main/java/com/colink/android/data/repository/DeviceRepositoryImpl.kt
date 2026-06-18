package com.colink.android.data.repository

import com.colink.android.crypto.KeyManager
import com.colink.android.data.local.DeviceNameProvider
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.local.db.dao.DeviceDao
import com.colink.android.data.local.db.dao.TrustedPeerKeyDao
import com.colink.android.data.local.db.entity.TrustedPeerKeyEntity
import com.colink.android.data.local.db.entity.isTrusted
import com.colink.android.data.local.db.entity.toEntity
import com.colink.android.data.remote.api.DeviceApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.DeviceNameUpdateRequestDto
import com.colink.android.data.remote.dto.DeviceKeyUpdateRequestDto
import com.colink.android.data.remote.dto.DeviceRegisterRequestDto
import com.colink.android.data.remote.dto.requireData
import com.colink.android.data.remote.dto.requireOk
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.model.Session
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.util.CoLinkLog
import java.time.Instant
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
        deviceDao.observeDevices().map { entities -> sortDevices(entities.map { it.toDomain() }) }

    override suspend fun ensureLocalDeviceIdentity(): Result<DeviceIdentity> =
        runCatching {
            ensureLocalDeviceIdentityRecord()
        }

    override suspend fun ensureDeviceIdentity(session: Session): Result<DeviceIdentity> =
        runCatching {
            val identity = ensureLocalDeviceIdentityRecord()
            registerLocalDevice(session, identity)
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
                val lanState = cached?.lanState
                    ?.takeIf { it.isLiveLanState() }
                    ?: "unavailable"
                incoming.copy(
                    lanAvailable = lanState.isLiveLanState(),
                    lanState = lanState,
                    localIp = incoming.localIp ?: cached?.localIp,
                    localPort = incoming.localPort ?: cached?.localPort,
                    cloudAvailable = incoming.online,
                    activeRoute = when {
                        lanState.isLiveLanState() -> "lan"
                        incoming.online -> "cloud"
                        else -> null
                    },
                    securityState = if (lanState.isLiveLanState()) {
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

    override suspend fun syncPendingDeviceKey(session: Session): Result<Unit> =
        runCatching {
            val identity = settingsDataStore.currentDeviceIdentity() ?: return@runCatching
            if (!identity.cloudKeySyncPending || identity.userId != session.userId) {
                return@runCatching
            }

            syncLocalDeviceKey(session, identity)
        }

    override suspend fun getDevice(deviceId: String): Device? =
        deviceDao.getDevice(deviceId)?.toDomain()

    override suspend fun markLanEndpoint(
        deviceId: String,
        ip: String?,
        port: Int?,
        deviceType: String?,
        lanState: String,
    ): Result<Unit> =
        runCatching {
            val current = deviceDao.getDevice(deviceId)?.toDomain()
                ?: trustedPeerDevice(deviceId)
                ?: return@runCatching
            val nextLanState = lanState.takeIf { it.isLiveLanState() } ?: "unavailable"
            val lastAlive = if (nextLanState == "alive") {
                Instant.now().toString()
            } else {
                current.lastSeen
            }
            saveDevices(
                listOf(
                    current.copy(
                        type = reconcileDeviceType(current.type, lanType = deviceType),
                        lastSeen = lastAlive,
                        localIp = ip ?: current.localIp,
                        localPort = port ?: current.localPort,
                        lanAvailable = nextLanState.isLiveLanState(),
                        lanState = nextLanState,
                        activeRoute = if (nextLanState.isLiveLanState()) "lan" else current.activeRoute,
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
                        lanState = "unavailable",
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

    override suspend fun resetDevicePresence(): Result<Unit> =
        runCatching {
            val localDeviceId = settingsDataStore.currentDeviceIdentity()?.deviceId
            val devices = deviceDao.getDevices().map { it.toDomain() }.map { device ->
                val isLocal = device.deviceId == localDeviceId
                device.copy(
                    online = isLocal,
                    cloudAvailable = false,
                    localIp = null,
                    localPort = null,
                    lanAvailable = false,
                    lanState = "unavailable",
                    activeRoute = null,
                    deviceSources = if (isLocal) mergeSources(device.deviceSources, "local") else device.deviceSources,
                )
            }
            saveDevices(devices)
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
                registerLocalDevice(session, rotated)
                syncDevices(session).getOrThrow()
            } else {
                listLocalDevices().getOrThrow()
            }
        }

    override suspend fun forgetLanTrust(deviceId: String): Result<Unit> =
        runCatching {
            trustedPeerKeyDao.clearLanTrust(deviceId)
            if (trustedPeerKeyDao.get(deviceId)?.isTrusted != true) {
                deviceDao.delete(deviceId)
            }
            listLocalDevices().getOrThrow()
        }

    override suspend fun clearCloudTrust(): Result<Unit> =
        runCatching {
            trustedPeerKeyDao.clearCloudTrust()
            trustedPeerKeyDao.deleteUntrusted()
            deviceDao.clear()
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

    private suspend fun syncLocalDeviceKey(session: Session, identity: DeviceIdentity) {
        deviceApi
            .updateDeviceKey(
                url = apiEndpoint(
                    settingsDataStore.currentSettings().serverUrl,
                    "/api/v1/devices/${identity.deviceId}/key",
                ),
                authorization = bearer(session.accessToken),
                request = DeviceKeyUpdateRequestDto(identity.publicKey),
            )
            .requireOk()
        settingsDataStore.saveDeviceIdentity(identity.copy(cloudKeySyncPending = false))
        CoLinkLog.i(
            "Device",
            "synced pending local device key device=${CoLinkLog.shortId(identity.deviceId)}",
        )
    }

    private suspend fun ensureTrustedPeerKeysForDevices(
        devices: List<Device>,
        localDeviceId: String?,
    ) {
        val cloudDevices = devices
            .filter { it.deviceId != localDeviceId && it.publicKey.isNotBlank() }
        val cloudDeviceIds = cloudDevices.map { it.deviceId }.toSet()
        val now = System.currentTimeMillis()

        cloudDevices.forEach { device ->
            val existing = trustedPeerKeyDao.get(device.deviceId)
            if (existing == null) {
                trustedPeerKeyDao.upsert(
                    TrustedPeerKeyEntity(
                        deviceId = device.deviceId,
                        name = device.name,
                        publicKey = device.publicKey,
                        keyUpdatedAt = device.publicKeyUpdatedAt ?: now,
                        trustedByLan = false,
                        trustedByCloud = true,
                    ),
                )
                return@forEach
            }

            val keyDiffers = existing.publicKey != device.publicKey
            val cloudTimestampNewer = device.publicKeyUpdatedAt != null &&
                device.publicKeyUpdatedAt > existing.keyUpdatedAt
            val acceptCloudKey = keyDiffers && cloudTimestampNewer
            val nextUpdatedAt = when {
                acceptCloudKey -> requireNotNull(device.publicKeyUpdatedAt)
                !keyDiffers && device.publicKeyUpdatedAt != null &&
                    device.publicKeyUpdatedAt > existing.keyUpdatedAt -> device.publicKeyUpdatedAt
                else -> existing.keyUpdatedAt
            }

            trustedPeerKeyDao.upsert(
                existing.copy(
                    name = device.name,
                    publicKey = if (acceptCloudKey) device.publicKey else existing.publicKey,
                    keyUpdatedAt = nextUpdatedAt,
                    trustedByLan = if (acceptCloudKey) false else existing.trustedByLan,
                    trustedByCloud = !keyDiffers || acceptCloudKey,
                ),
            )
        }

        trustedPeerKeyDao.getAll()
            .filter { it.deviceId != localDeviceId && it.deviceId !in cloudDeviceIds && it.trustedByCloud }
            .forEach { record ->
                trustedPeerKeyDao.upsert(record.copy(trustedByCloud = false))
            }
    }

    private fun trustedSources(record: TrustedPeerKeyEntity?): List<String> =
        buildList {
            if (record?.trustedByCloud == true) {
                add("cloud")
            }
            if (record?.trustedByLan == true) {
                add("trusted_peer_key")
            }
        }

    private suspend fun trustedPeerKeysById(): Map<String, TrustedPeerKeyEntity> =
        trustedPeerKeyDao.getAll()
            .filter { it.isTrusted }
            .associateBy { it.deviceId }

    private suspend fun reconcileDevices(
        incoming: List<Device>,
        previous: List<Device>,
        localIdentity: DeviceIdentity?,
        keepCloudState: Boolean = true,
    ): List<Device> {
        val trustedById = trustedPeerKeysById()
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
            val trust = trustedById[device.deviceId]
            val trustedByLan = trust?.trustedByLan == true
            val trustedByCloud = trust?.trustedByCloud == true
            val lanState = when {
                device.lanState.isLiveLanState() -> device.lanState
                existing?.lanState?.isLiveLanState() == true -> existing.lanState
                else -> "unavailable"
            }
            val lanAvailable = lanState.isLiveLanState()
            val cloudAvailable = device.cloudAvailable
            device.copy(
                lastSeen = device.lastSeen ?: existing?.lastSeen,
                localIp = device.localIp ?: existing?.localIp,
                localPort = device.localPort ?: existing?.localPort,
                lanAvailable = lanAvailable,
                lanState = lanState,
                cloudAvailable = cloudAvailable,
                online = cloudAvailable || lanAvailable,
                activeRoute = when {
                    lanAvailable -> "lan"
                    cloudAvailable -> "cloud"
                    else -> null
                },
                securityState = when {
                    trust?.isTrusted == true -> "verified"
                    lanAvailable -> "verified"
                    device.securityState != "unverified" -> device.securityState
                    else -> existing?.securityState ?: "unverified"
                },
                type = reconcileDeviceType(
                    incoming = device.type,
                    previous = existing?.type,
                ),
                deviceSources = mergeSources(device.deviceSources, *trustedSources(trust).toTypedArray()),
                trustedByLan = trustedByLan,
                trustedByCloud = trustedByCloud,
            )
        }.toMutableList()

        val knownIds = devices.map { it.deviceId }.toSet()
        trustedById.values
            .filter { it.deviceId != localIdentity?.deviceId && it.deviceId !in knownIds }
            .forEach { record ->
                val existing = previousById[record.deviceId]
                val lanState = when {
                    existing?.lanState?.isLiveLanState() == true -> existing.lanState
                    else -> "unavailable"
                }
                val lanAvailable = lanState.isLiveLanState()
                val trustedByLan = record.trustedByLan
                val trustedByCloud = record.trustedByCloud
                devices += Device(
                    deviceId = record.deviceId,
                    name = record.name,
                    type = reconcileDeviceType(existing?.type ?: "unknown"),
                    online = lanAvailable,
                    lastSeen = existing?.lastSeen,
                    publicKey = record.publicKey,
                    publicKeyUpdatedAt = record.keyUpdatedAt,
                    localIp = existing?.localIp,
                    localPort = existing?.localPort,
                    cloudAvailable = false,
                    lanAvailable = lanAvailable,
                    lanState = lanState,
                    activeRoute = if (lanAvailable) "lan" else null,
                    deviceSources = trustedSources(record),
                    trustedByLan = trustedByLan,
                    trustedByCloud = trustedByCloud,
                    securityState = "verified",
                )
            }

        if (localIdentity != null && devices.none { it.deviceId == localIdentity.deviceId }) {
            devices += localDeviceInfo(localIdentity, keepCloudState = keepCloudState)
        }

        return sortDevices(devices)
    }

    private fun sortDevices(devices: List<Device>): List<Device> =
        devices.sortedWith(
            compareBy<Device> { deviceSortGroup(it) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.deviceId },
        )

    private fun deviceSortGroup(device: Device): Int =
        when {
            device.deviceSources.contains("local") -> 0
            device.cloudAvailable || device.deviceSources.contains("cloud") -> 1
            device.lanAvailable || device.trustedByLan || device.deviceSources.contains("trusted_peer_key") -> 2
            else -> 3
        }

    private suspend fun trustedPeerDevice(deviceId: String): Device? {
        val record = trustedPeerKeyDao.get(deviceId)?.takeIf { it.isTrusted } ?: return null
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
            lanState = "unavailable",
            deviceSources = trustedSources(record),
            trustedByLan = record.trustedByLan,
            trustedByCloud = record.trustedByCloud,
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
            lanState = "unavailable",
            activeRoute = null,
            deviceSources = sources,
            trustedByLan = false,
            trustedByCloud = false,
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

    private fun String?.isLiveLanState(): Boolean =
        when (this?.trim()?.lowercase()) {
            "alive", "suspect" -> true
            else -> false
        }

    private fun String.isUnknownDeviceType(): Boolean {
        val value = trim()
        return value.isEmpty() || value.equals("unknown", ignoreCase = true)
    }

    private val knownDeviceTypes = setOf("windows", "macos", "linux", "android", "ios")

    private fun bearer(token: String): String = "Bearer $token"
}
