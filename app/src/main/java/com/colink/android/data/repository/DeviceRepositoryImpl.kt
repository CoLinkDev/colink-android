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
import com.colink.android.network.cloud.CloudRuntimePeer
import com.colink.android.network.cloud.CloudRuntimeSnapshot
import com.colink.android.network.cloud.CloudRuntimeState
import com.colink.android.network.lan.LanRuntimePeer
import com.colink.android.network.lan.LanRuntimeState
import com.colink.android.util.CoLinkLog
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val deviceApi: DeviceApi,
    private val settingsDataStore: SettingsDataStore,
    private val deviceDao: DeviceDao,
    private val trustedPeerKeyDao: TrustedPeerKeyDao,
    private val keyManager: KeyManager,
    private val deviceNameProvider: DeviceNameProvider,
    private val lanRuntimeState: LanRuntimeState,
    private val cloudRuntimeState: CloudRuntimeState,
) : DeviceRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deviceSyncMutex = Mutex()

    override val devices: StateFlow<List<Device>> =
        combine(
            deviceDao.observeDevices(),
            lanRuntimeState.peers,
            cloudRuntimeState.snapshot,
        ) { entities, lanPeers, cloudSnapshot ->
            sortDevices(
                entities.map { entity ->
                    projectRuntimeDevice(
                        device = entity.toDomain(),
                        lanRuntime = lanPeers[entity.deviceId],
                        cloudRuntime = cloudSnapshot,
                    )
                },
            )
        }.stateIn(repositoryScope, SharingStarted.Eagerly, emptyList())

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
            deviceSyncMutex.withLock {
                val requestStartedAtNanos = System.nanoTime()
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
                        securityState = cached?.securityState?.takeIf { it != "unverified" } ?: "unverified",
                    )
                }

                cloudRuntimeState.replaceSnapshot(
                    peers = cloudDevices.associate { device ->
                        device.deviceId to CloudRuntimePeer(
                            online = device.online,
                            name = device.name,
                            type = device.type,
                        )
                    },
                    requestStartedAtNanos = requestStartedAtNanos,
                )
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
        deviceDao.getDevice(deviceId)
            ?.toDomain()
            ?.let { device -> projectRuntimeDevice(device, lanRuntimeState.peer(deviceId)) }

    override suspend fun markCloudPresence(
        deviceId: String,
        online: Boolean,
        name: String?,
        deviceType: String?,
    ): Result<Unit> =
        runCatching {
            cloudRuntimeState.updatePresence(
                deviceId = deviceId,
                online = online,
                name = name,
                type = deviceType,
            )
            val current = deviceDao.getDevice(deviceId)?.toDomain() ?: return@runCatching
            val updated = current.copy(
                name = name?.takeIf { it.isNotBlank() } ?: current.name,
                type = reconcileDeviceType(
                    incoming = deviceType ?: current.type,
                    previous = current.type,
                ),
            )
            if (updated.name != current.name || updated.type != current.type) {
                saveDevices(listOf(updated), replaceAll = false)
            }
            CoLinkLog.i(
                "Device",
                "marked cloud presence device=${CoLinkLog.shortId(deviceId)} online=$online",
            )
        }

    override suspend fun recordLanDeviceType(
        deviceId: String,
        deviceType: String,
    ): Result<Unit> =
        runCatching {
            val normalizedType = deviceType.normalizedDeviceType() ?: return@runCatching
            val current = deviceDao.getDevice(deviceId)?.toDomain() ?: return@runCatching
            if (!current.type.isUnknownDeviceType()) {
                return@runCatching
            }
            saveDevices(
                listOf(current.copy(type = normalizedType)),
                replaceAll = false,
            )
        }

    override suspend fun clearCloudPresence(): Result<Unit> =
        runCatching {
            cloudRuntimeState.clear()
            CoLinkLog.i("Device", "cleared cloud presence")
        }

    override suspend fun resetDevicePresence(): Result<Unit> =
        runCatching {
            cloudRuntimeState.clear()
        }

    override suspend fun listLocalDevices(): Result<List<Device>> =
        runCatching {
            val previous = deviceDao.getDevices().map { it.toDomain() }
            val localIdentity = settingsDataStore.currentDeviceIdentity()
            val keepCloudCatalog = settingsDataStore.currentSession() != null
            val reconciled = reconcileDevices(
                incoming = previous.filter { device ->
                    keepCloudCatalog && device.deviceSources.contains("cloud")
                },
                previous = previous,
                localIdentity = localIdentity,
                keepCloudState = keepCloudCatalog,
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
            device.copy(
                lastSeen = device.lastSeen ?: existing?.lastSeen,
                cloudAvailable = false,
                online = false,
                activeRoute = null,
                securityState = when {
                    trust?.isTrusted == true -> "verified"
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
                val trustedByLan = record.trustedByLan
                val trustedByCloud = record.trustedByCloud
                devices += Device(
                    deviceId = record.deviceId,
                    name = record.name,
                    type = reconcileDeviceType(existing?.type ?: "unknown"),
                    online = false,
                    lastSeen = existing?.lastSeen,
                    publicKey = record.publicKey,
                    publicKeyUpdatedAt = record.keyUpdatedAt,
                    cloudAvailable = false,
                    activeRoute = null,
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

    private fun projectRuntimeDevice(
        device: Device,
        lanRuntime: LanRuntimePeer?,
        cloudRuntime: CloudRuntimeSnapshot = cloudRuntimeState.snapshot.value,
    ): Device {
        val endpoint = lanRuntime?.endpoint
        val lanState = lanRuntime?.state ?: "unavailable"
        val lanAvailable = lanRuntime?.isReachable == true
        val cloudAvailable = cloudRuntime.peers[device.deviceId]?.online ?: false
        val isLocal = device.deviceSources.contains("local")
        return device.copy(
            type = reconcileDeviceType(device.type, lanType = lanRuntime?.type),
            localIp = endpoint?.ip,
            localPort = endpoint?.port,
            cloudAvailable = cloudAvailable,
            lanAvailable = lanAvailable,
            lanState = lanState,
            online = isLocal || cloudAvailable || lanAvailable,
            activeRoute = when {
                lanAvailable -> "lan"
                cloudAvailable -> "cloud"
                else -> null
            },
        )
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
        return Device(
            deviceId = identity.deviceId,
            name = identity.name,
            type = identity.type,
            online = false,
            lastSeen = current?.lastSeen,
            publicKey = identity.publicKey,
            publicKeyUpdatedAt = current?.publicKeyUpdatedAt,
            localIp = null,
            localPort = null,
            cloudAvailable = false,
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
        val entities = devices.map { it.toEntity() }
        if (replaceAll) {
            deviceDao.replaceAll(entities)
        } else if (entities.isNotEmpty()) {
            deviceDao.upsertAll(entities)
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
