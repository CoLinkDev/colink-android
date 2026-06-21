package com.colink.android.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.webkit.MimeTypeMap
import com.colink.android.R
import com.colink.android.domain.model.CloudConnectionState
import com.colink.android.domain.model.CloudStatus
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.domain.repository.MessageRepository
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.notification.CoLinkNotifier
import com.colink.android.network.cloud.CloudWebSocketClient
import com.colink.android.network.cloud.TicketProvider
import com.colink.android.network.lan.LanWebSocketClient
import com.colink.android.network.lan.LanSwimClient
import com.colink.android.network.lan.LanTrustStore
import com.colink.android.network.lan.LanWebSocketServer
import com.colink.android.network.lan.NsdDiscovery
import com.colink.android.network.lan.TransferConnection
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.BUSINESS_PROTOCOL_VERSION
import com.colink.android.network.message.CLIPBOARD_SYNC_TYPE
import com.colink.android.network.message.ClipboardSyncPayload
import com.colink.android.network.message.CloudClientEnvelope
import com.colink.android.network.message.CloudServerEnvelope
import com.colink.android.network.message.DeviceOnlinePayload
import com.colink.android.network.message.FILE_ACCEPT_TYPE
import com.colink.android.network.message.FILE_ACK_TYPE
import com.colink.android.network.message.FILE_CANCEL_TYPE
import com.colink.android.network.message.FILE_CHUNK_TYPE
import com.colink.android.network.message.FILE_DONE_TYPE
import com.colink.android.network.message.FILE_OFFER_TYPE
import com.colink.android.network.message.FILE_READY_TYPE
import com.colink.android.network.message.FILE_REJECT_TYPE
import com.colink.android.network.message.FILE_RETRANSMIT_TYPE
import com.colink.android.network.message.MUSIC_ALIVE_TYPE
import com.colink.android.network.message.MUSIC_LYRIC_TYPE
import com.colink.android.network.message.MUSIC_PROGRESS_TYPE
import com.colink.android.network.message.MUSIC_REQUEST_TYPE
import com.colink.android.network.message.MUSIC_TRACK_TYPE
import com.colink.android.network.message.MusicAlivePayload
import com.colink.android.network.message.MusicLyricPayload
import com.colink.android.network.message.MusicProgressPayload
import com.colink.android.network.message.MusicRequestPayload
import com.colink.android.network.message.MusicTrackPayload
import com.colink.android.network.message.SYSINFO_ALIVE_TYPE
import com.colink.android.network.message.SYSINFO_STATS_TYPE
import com.colink.android.network.message.SysInfoAlivePayload
import com.colink.android.network.message.SysInfoStatsPayload
import com.colink.android.network.message.FileAckPayload
import com.colink.android.network.message.FileAcceptPayload
import com.colink.android.network.message.FileCancelPayload
import com.colink.android.network.message.FileChunkPayload
import com.colink.android.network.message.FileDonePayload
import com.colink.android.network.message.FileOfferPayload
import com.colink.android.network.message.FileReadyPayload
import com.colink.android.network.message.FileRejectPayload
import com.colink.android.network.message.FileRetransmitPayload
import com.colink.android.network.message.SwimEnvelope
import com.colink.android.network.message.SwimGossip
import com.colink.android.network.message.TEXT_MESSAGE_TYPE
import com.colink.android.network.message.TextMessagePayload
import com.colink.android.network.message.checkBusinessProtocolVersion
import com.colink.android.network.message.supportsBusinessProtocolAtLeast
import com.colink.android.network.transfer.BuiltFileOffer
import com.colink.android.network.transfer.FileDataFrame
import com.colink.android.network.transfer.FileDataFrameKind
import com.colink.android.network.transfer.FileChecksumVerifier
import com.colink.android.network.music.MusicSyncManager
import com.colink.android.network.sysinfo.SysInfoSyncManager
import com.colink.android.util.LocaleHelper
import java.io.File
import java.io.InterruptedIOException
import java.io.InputStream
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.colink.android.util.CoLinkLog
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private const val MAX_TEXT_LENGTH = 10_000
private const val CLIPBOARD_MAX_BYTES = 1_048_576
private const val FILE_ACK_INTERVAL_CHUNKS = 7L
private const val LAN_SEND_WINDOW_CHUNKS = 8L
private const val RELAY_SEND_WINDOW_CHUNKS = FILE_ACK_INTERVAL_CHUNKS
private const val SWIM_PERIOD_MILLIS = 5_000L
private const val SWIM_SUSPECT_TIMEOUT_MILLIS = 3_000L
private const val SWIM_MAX_GOSSIP = 10
private const val SWIM_PROBE_BATCH_SIZE = 2
private const val SWIM_SUSPECT_MISSES = 2
private const val SWIM_PING_REQ_FANOUT = 2
private const val LAN_SEND_TIMEOUT_MILLIS = 15_000L
private const val FILE_OFFER_TIMEOUT_MILLIS = 60_000L
private const val REASON_AUTH_SIGNATURE_INVALID = "colink:auth.signature_invalid.v1"
private const val REASON_AUTH_KEY_CHANGED = "colink:auth.key_changed.v1"
private const val REASON_PAIRING_USER_REJECTED = "colink:pairing.user_rejected.v1"
private const val REASON_TRANSFER_USER_CANCELLED = "colink:transfer.user_cancelled.v1"
private const val REASON_TRANSFER_USER_REJECTED = "colink:transfer.user_rejected.v1"
private const val REASON_TRANSFER_CHECKSUM_MISMATCH = "colink:transfer.checksum_mismatch.v1"
private const val REASON_TRANSFER_GENERIC = "colink:transfer.generic.v1"

@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ticketProvider: TicketProvider,
    private val cloudWebSocketClient: CloudWebSocketClient,
    private val json: Json,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val messageRepository: MessageRepository,
    private val fileTransferRepository: FileTransferRepository,
    private val settingsDataStore: SettingsDataStore,
    private val lanWebSocketClient: LanWebSocketClient,
    private val lanWebSocketServer: LanWebSocketServer,
    private val lanSwimClient: LanSwimClient,
    private val lanTrustStore: LanTrustStore,
    private val nsdDiscovery: NsdDiscovery,
    private val musicSyncManager: MusicSyncManager,
    private val sysInfoSyncManager: SysInfoSyncManager,
    private val notifier: CoLinkNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _cloudState = MutableStateFlow(CloudConnectionState())
    private val _lanPairingCandidates = MutableStateFlow<List<LanPairingCandidate>>(emptyList())
    private val _lanConnectionError = MutableStateFlow<String?>(null)
    private var connectionJob: Job? = null
    private var swimJob: Job? = null
    private var suspectJob: Job? = null
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransferState>()
    private val outgoingTransfers = ConcurrentHashMap<String, OutgoingTransferState>()
    private val incomingFileOfferCorrelationIds = ConcurrentHashMap<String, String>()
    private val ackSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val swimEndpoints = ConcurrentHashMap<String, LanEndpoint>()
    private val swimNames = ConcurrentHashMap<String, String>()
    private val swimTypes = ConcurrentHashMap<String, String>()
    private val cloudBusinessVersions = ConcurrentHashMap<String, String>()
    private val swimMembership = SwimMembership(maxGossip = SWIM_MAX_GOSSIP)
    private val swimLock = Any()
    private val lanPeerLock = Any()
    private val pendingLanSends = mutableMapOf<String, ArrayDeque<PendingLanSend>>()
    private val lanConnectingPeers = mutableSetOf<String>()
    private val devicePageLanConnections = mutableSetOf<String>()
    private val started = AtomicBoolean(false)
    private val lanGeneration = AtomicLong(0)
    private var swimSeq = 0L
    private val probeQueue = ArrayDeque<String>()
    private var probeRoundCandidates = emptyList<String>()
    private val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val lanNetworks = ConcurrentHashMap.newKeySet<Network>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val lanSuspendedForNetworkLoss = AtomicBoolean(false)
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardSuppressedHash: String? = null
    private var clipboardLastSentHash: String? = null

    val cloudState: StateFlow<CloudConnectionState> = _cloudState.asStateFlow()

    val lanPairingCandidates: StateFlow<List<LanPairingCandidate>> =
        _lanPairingCandidates.asStateFlow()
    val lanConnectionError: StateFlow<String?> = _lanConnectionError.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) {
            CoLinkLog.d("Connection", "connection manager already started")
            return
        }
        notifier.ensureEventChannel()
        CoLinkLog.i("Connection", "starting connection manager")
        startNetworkMonitoring()
        scope.launch {
            authRepository.bootstrap()
                .onFailure { error -> CoLinkLog.e("Connection", "failed to bootstrap runtime", error) }
            if (!started.get()) {
                return@launch
            }
            fileTransferRepository.failUnfinished("app restarted")
            startLan()
            if (settingsDataStore.currentSession() != null && connectionJob?.isActive != true) {
                connectionJob = scope.launch { runCloudLoop() }
            } else if (settingsDataStore.currentSession() == null) {
                _cloudState.value = CloudConnectionState()
                cloudBusinessVersions.clear()
                CoLinkLog.d("Cloud", "cloud loop skipped because no session exists")
            }
            if (settingsDataStore.currentSettings().enableClipboardSync) {
                withContext(Dispatchers.Main) {
                    startClipboardSync()
                }
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }
        CoLinkLog.i("Connection", "stopping connection manager")
        stopNetworkMonitoring()
        lanSuspendedForNetworkLoss.set(false)
        lanNetworks.clear()
        connectionJob?.cancel()
        connectionJob = null
        cloudBusinessVersions.clear()
        swimJob?.cancel()
        swimJob = null
        suspectJob?.cancel()
        suspectJob = null
        stopClipboardSync()
        broadcastLeft()
        stopLan()
        musicSyncManager.reset()
        incomingTransfers.clear()
        outgoingTransfers.clear()
        ackSignals.values.forEach { it.complete(Unit) }
        ackSignals.clear()
        swimEndpoints.clear()
        swimNames.clear()
        swimTypes.clear()
        swimMembership.clear()
        _lanPairingCandidates.value = emptyList()
        failPendingLanSends("LAN services stopped")
        synchronized(swimLock) {
            probeQueue.clear()
            probeRoundCandidates = emptyList()
        }
        cloudWebSocketClient.close()
        _cloudState.value = CloudConnectionState()
    }

    fun isLanServerRunning(): Boolean = lanWebSocketServer.isRunning()

    fun startCloud() {
        CoLinkLog.i("Cloud", "start cloud requested")
        scope.launch {
            if (settingsDataStore.currentSession() != null && connectionJob?.isActive != true) {
                connectionJob = scope.launch { runCloudLoop() }
            }
        }
    }

    fun stopCloud() {
        CoLinkLog.i("Cloud", "stop cloud requested")
        connectionJob?.cancel()
        connectionJob = null
        cloudWebSocketClient.close()
        _cloudState.value = CloudConnectionState()
        scope.launch { deviceRepository.listLocalDevices() }
    }

    fun applySettings(settings: AppSettings) {
        CoLinkLog.i("Settings", "applying settings")
        startLan()
        scope.launch {
            if (settingsDataStore.currentSession() != null && connectionJob?.isActive != true) {
                connectionJob = scope.launch { runCloudLoop() }
            }
            withContext(Dispatchers.Main) {
                if (settings.enableClipboardSync) {
                    startClipboardSync()
                } else {
                    stopClipboardSync()
                }
            }
        }
    }

    suspend fun sendMusicAlive(targetDeviceId: String): Result<Unit> =
        sendBusinessMessage(
            targetDeviceId,
            BusinessEnvelope(
                type = MUSIC_ALIVE_TYPE,
                payload = json.encodeToJsonElement(MusicAlivePayload),
            ),
        ).map { Unit }

    suspend fun sendMusicRequest(targetDeviceId: String): Result<Unit> =
        sendBusinessMessage(
            targetDeviceId,
            BusinessEnvelope(
                type = MUSIC_REQUEST_TYPE,
                payload = json.encodeToJsonElement(MusicRequestPayload),
            ),
        ).map { Unit }

    suspend fun sendSysInfoAlive(targetDeviceId: String): Result<Unit> {
        if (!supportsPeerSysInfo(targetDeviceId)) {
            return Result.success(Unit)
        }
        return sendBusinessMessage(
            targetDeviceId,
            BusinessEnvelope(
                type = SYSINFO_ALIVE_TYPE,
                payload = json.encodeToJsonElement(SysInfoAlivePayload),
            ),
        ).map { Unit }
    }

    private fun startLan() {
        lanGeneration.incrementAndGet()
        CoLinkLog.i("LAN", "starting LAN services")
        lanWebSocketServer.start(lanListener)
        scope.launch {
            val identity = deviceRepository.localDeviceIdentity() ?: run {
                CoLinkLog.w("LAN", "LAN discovery skipped because local identity is missing")
                return@launch
            }
            swimMembership.ensureLocalStarted(identity.deviceId)
            startLanDiscovery(identity)
        }
        startSwimLoops()
    }

    private fun restartLan() {
        stopLan()
        startLan()
    }

    private fun stopLan() {
        val generation = lanGeneration.get()
        CoLinkLog.i("LAN", "stopping LAN services")
        swimJob?.cancel()
        swimJob = null
        suspectJob?.cancel()
        suspectJob = null
        lanWebSocketClient.disconnectAll()
        lanWebSocketServer.stop()
        nsdDiscovery.stop()
        swimEndpoints.clear()
        swimNames.clear()
        swimTypes.clear()
        swimMembership.clear()
        _lanPairingCandidates.value = emptyList()
        synchronized(swimLock) {
            probeQueue.clear()
            probeRoundCandidates = emptyList()
        }
        scope.launch {
            if (lanGeneration.get() == generation) {
                deviceRepository.clearAllLanEndpoints()
            }
        }
    }

    private fun startNetworkMonitoring() {
        if (networkCallback != null) {
            return
        }
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkAvailable(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (isLanNetwork(capabilities)) {
                    handleNetworkAvailable(network)
                }
            }

            override fun onLost(network: Network) {
                if (!lanNetworks.remove(network)) {
                    return
                }
                if (!lanSuspendedForNetworkLoss.compareAndSet(false, true)) {
                    return
                }
                CoLinkLog.i("Connection", "network lost, stopping LAN services")
                scope.launch { stopLan() }
            }
        }
        networkCallback = callback
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
        }.onFailure { error ->
            networkCallback = null
            CoLinkLog.w("Connection", "failed to register network callback", error)
        }
    }

    private fun stopNetworkMonitoring() {
        val manager = connectivityManager ?: return
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            manager.unregisterNetworkCallback(callback)
        }.onFailure { error ->
            CoLinkLog.w("Connection", "failed to unregister network callback", error)
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        if (!shouldRestartLan(network)) {
            return
        }
        lanNetworks.add(network)
        if (!lanSuspendedForNetworkLoss.compareAndSet(true, false)) {
            return
        }
        CoLinkLog.i("Connection", "network available, restarting LAN services")
        scope.launch { restartLan() }
    }

    private fun shouldRestartLan(network: Network): Boolean {
        val manager = connectivityManager ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return isLanNetwork(capabilities)
    }

    private fun isLanNetwork(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    suspend fun sendText(targetDeviceId: String, text: String): Result<Unit> =
        runCatching {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "message is empty" }
            require(trimmed.length <= MAX_TEXT_LENGTH) { "message is too long" }

            val messageId = UUID.randomUUID().toString()
            val payload = TextMessagePayload(messageId = messageId, text = trimmed)
            val business = BusinessEnvelope(
                type = TEXT_MESSAGE_TYPE,
                payload = json.encodeToJsonElement(payload),
            )

            // Save outgoing message to DB immediately in "sending" state
            messageRepository.saveTextMessage(
                messageId = messageId,
                deviceId = targetDeviceId,
                direction = MessageDirection.Outgoing,
                text = trimmed,
                route = "sending",
            )

            val routeResult = runCatching {
                sendBusinessMessage(targetDeviceId, business).getOrThrow()
            }

            val finalRoute = if (routeResult.isSuccess) routeResult.getOrThrow() else "failed"
            messageRepository.saveTextMessage(
                messageId = messageId,
                deviceId = targetDeviceId,
                direction = MessageDirection.Outgoing,
                text = trimmed,
                route = finalRoute,
            )

            routeResult.getOrThrow()
            Unit
        }

    private suspend fun sendViaLan(
        targetDeviceId: String,
        business: BusinessEnvelope,
        correlationId: String? = null,
    ): Result<String> =
        runCatching {
            if (trySendViaExistingLan(targetDeviceId, business, correlationId)) {
                return@runCatching "lan"
            }

            val pending = PendingLanSend(
                message = business,
                correlationId = correlationId,
                result = CompletableDeferred<Result<Unit>>(),
            )
            val shouldConnect = synchronized(lanPeerLock) {
                pendingLanSends.getOrPut(targetDeviceId) { ArrayDeque() }.addLast(pending)
                lanConnectingPeers.add(targetDeviceId)
            }

            flushPendingLanSends(targetDeviceId)
            if (pending.result.isCompleted) {
                pending.result.await().getOrThrow()
                return@runCatching "lan"
            }

            if (shouldConnect) {
                startOnDemandLanPeerConnection(targetDeviceId)
                    .onFailure { error ->
                        failPendingLanSends(targetDeviceId, error.message ?: "LAN peer connection failed")
                    }
            }

            val result = withTimeoutOrNull(LAN_SEND_TIMEOUT_MILLIS) {
                pending.result.await()
            } ?: run {
                removePendingLanSend(targetDeviceId, pending)
                error("LAN peer connection timed out")
            }
            result.getOrThrow()
            "lan"
        }

    private fun sendViaCloud(
        targetDeviceId: String,
        business: BusinessEnvelope,
        correlationId: String? = null,
    ): Result<String> =
        runCatching {
            check(_cloudState.value.connected) { "cloud connection is not ready" }
            ensureCloudBusinessCompatible(targetDeviceId)
            val envelope = CloudClientEnvelope(
                id = UUID.randomUUID().toString(),
                type = "relay",
                to = targetDeviceId,
                correlationId = correlationId,
                payload = json.encodeToJsonElement(business),
            )

            check(cloudWebSocketClient.send(envelope)) { "cloud connection is not ready" }
            "cloud"
        }

    private fun sendCloudBroadcast(business: BusinessEnvelope, correlationId: String? = null): Result<String> =
        runCatching {
            check(_cloudState.value.connected) { "cloud connection is not ready" }
            ensureKnownCloudBusinessVersionsCompatible()
            val envelope = CloudClientEnvelope(
                id = UUID.randomUUID().toString(),
                type = "broadcast",
                correlationId = correlationId,
                payload = json.encodeToJsonElement(business),
            )

            check(cloudWebSocketClient.send(envelope)) { "cloud connection is not ready" }
            "cloud"
        }

    private suspend fun trySendViaExistingLan(
        targetDeviceId: String,
        business: BusinessEnvelope,
        correlationId: String? = null,
    ): Boolean {
        if (lanWebSocketServer.send(targetDeviceId, business, correlationId)) {
            return true
        }
        return lanWebSocketClient.send(targetDeviceId, business, correlationId)
    }

    private fun hasLanPeer(deviceId: String): Boolean =
        lanWebSocketServer.hasPeer(deviceId) || lanWebSocketClient.hasPeer(deviceId)

    private fun supportsPeerSysInfo(deviceId: String): Boolean {
        val peerVersion = lanWebSocketServer.peerBusinessVersion(deviceId)
            ?: lanWebSocketClient.peerBusinessVersion(deviceId)
            ?: cloudBusinessVersions[deviceId]
            ?: return false
        return supportsBusinessProtocolAtLeast(peerVersion, major = 1, minor = 1)
    }

    private suspend fun startOnDemandLanPeerConnection(deviceId: String): Result<Unit> =
        runCatching {
            if (hasLanPeer(deviceId)) {
                synchronized(lanPeerLock) {
                    lanConnectingPeers.remove(deviceId)
                }
                flushPendingLanSends(deviceId)
                return@runCatching
            }
            val identity = deviceRepository.localDeviceIdentity()
                ?: error("current device is not registered")
            val device = deviceRepository.getDevice(deviceId)
                ?: error("target device not found")
            if (!device.lanAvailable || device.localIp == null || device.localPort == null) {
                error("LAN peer is not available")
            }
            if (!lanTrustStore.isTrusted(deviceId)) {
                error("LAN peer is not trusted")
            }
            lanWebSocketClient.connect(
                identity = identity,
                deviceId = device.deviceId,
                ip = device.localIp,
                port = device.localPort,
                allowPairing = false,
                listener = lanClientListener,
            )
        }

    private suspend fun handleLanPeerConnected(deviceId: String) {
        synchronized(lanPeerLock) {
            lanConnectingPeers.remove(deviceId)
            devicePageLanConnections.remove(deviceId)
        }
        val identity = deviceRepository.localDeviceIdentity()
        if (identity != null && lanWebSocketClient.hasPeer(deviceId) && lanWebSocketServer.hasPeer(deviceId)) {
            if (shouldInitiate(identity.deviceId, deviceId)) {
                CoLinkLog.d("LAN", "closing duplicate inbound peer device=${CoLinkLog.shortId(deviceId)}")
                lanWebSocketServer.disconnect(deviceId)
            } else {
                CoLinkLog.d("LAN", "closing duplicate outbound peer device=${CoLinkLog.shortId(deviceId)}")
                lanWebSocketClient.disconnect(deviceId)
            }
        }
        flushPendingLanSends(deviceId)
        val endpointSynced = syncKnownLanEndpoint(deviceId)
        removePairingCandidate(deviceId)
        if (endpointSynced) {
            return
        }
        deviceRepository.listLocalDevices()
    }

    private suspend fun handleLanPeerDisconnected(deviceId: String) {
        val wasConnecting = synchronized(lanPeerLock) {
            devicePageLanConnections.remove(deviceId)
            lanConnectingPeers.remove(deviceId)
        }
        if (wasConnecting && !hasLanPeer(deviceId)) {
            failPendingLanSends(deviceId, "LAN peer disconnected")
            return
        }
        flushPendingLanSends(deviceId)
    }

    private suspend fun flushPendingLanSends(deviceId: String) {
        if (!hasLanPeer(deviceId)) {
            return
        }
        while (true) {
            val pending = synchronized(lanPeerLock) {
                val queue = pendingLanSends[deviceId] ?: return@synchronized null
                if (queue.isEmpty()) {
                    pendingLanSends.remove(deviceId)
                    null
                } else {
                    queue.removeFirst().also {
                        if (queue.isEmpty()) {
                            pendingLanSends.remove(deviceId)
                        }
                    }
                }
            } ?: return

            if (trySendViaExistingLan(deviceId, pending.message, pending.correlationId)) {
                pending.result.complete(Result.success(Unit))
            } else {
                pending.result.complete(Result.failure(IllegalStateException("LAN peer is unavailable")))
            }
        }
    }

    private fun removePendingLanSend(deviceId: String, pending: PendingLanSend) {
        synchronized(lanPeerLock) {
            val queue = pendingLanSends[deviceId] ?: return@synchronized
            queue.remove(pending)
            if (queue.isEmpty()) {
                pendingLanSends.remove(deviceId)
                lanConnectingPeers.remove(deviceId)
            }
        }
    }

    private fun failPendingLanSends(reason: String) {
        val pending = synchronized(lanPeerLock) {
            lanConnectingPeers.clear()
            devicePageLanConnections.clear()
            pendingLanSends.values.flatMap { it.toList() }.also {
                pendingLanSends.clear()
            }
        }
        pending.forEach {
            it.result.complete(Result.failure(IllegalStateException(reason)))
        }
    }

    private fun failPendingLanSends(deviceId: String, reason: String) {
        val pending = synchronized(lanPeerLock) {
            lanConnectingPeers.remove(deviceId)
            devicePageLanConnections.remove(deviceId)
            pendingLanSends.remove(deviceId)?.toList() ?: emptyList()
        }
        pending.forEach {
            it.result.complete(Result.failure(IllegalStateException(reason)))
        }
    }

    private suspend fun saveInboundBusinessMessage(
        fromDeviceId: String,
        envelopeId: String?,
        business: BusinessEnvelope,
        route: String,
    ) {
        when (business.type) {
            TEXT_MESSAGE_TYPE -> saveInboundTextMessage(fromDeviceId, business, route)
            CLIPBOARD_SYNC_TYPE -> handleClipboardSync(fromDeviceId, business)
            FILE_OFFER_TYPE -> handleFileOffer(fromDeviceId, envelopeId, business, route)
            FILE_ACCEPT_TYPE -> handleFileAccept(fromDeviceId, business)
            FILE_READY_TYPE -> handleFileReady(fromDeviceId, business)
            FILE_CHUNK_TYPE -> handleFileChunk(fromDeviceId, business, route)
            FILE_ACK_TYPE -> handleFileAck(business)
            FILE_RETRANSMIT_TYPE -> handleFileRetransmit(business, lan = false)
            FILE_REJECT_TYPE -> handleFileReject(business)
            FILE_CANCEL_TYPE -> handleFileCancel(business)
            FILE_DONE_TYPE -> handleFileDone(business)
            MUSIC_TRACK_TYPE -> runCatching {
                json.decodeFromJsonElement(MusicTrackPayload.serializer(), business.payload)
            }.getOrNull()?.let { musicSyncManager.acceptTrack(fromDeviceId, it) }
            MUSIC_LYRIC_TYPE -> runCatching {
                json.decodeFromJsonElement(MusicLyricPayload.serializer(), business.payload)
            }.getOrNull()?.let { musicSyncManager.acceptLyric(fromDeviceId, it) }
            MUSIC_PROGRESS_TYPE -> runCatching {
                json.decodeFromJsonElement(MusicProgressPayload.serializer(), business.payload)
            }.getOrNull()?.let { musicSyncManager.acceptProgress(fromDeviceId, it) }
            SYSINFO_STATS_TYPE -> runCatching {
                json.decodeFromJsonElement(SysInfoStatsPayload.serializer(), business.payload)
            }.getOrNull()?.let { sysInfoSyncManager.acceptStats(fromDeviceId, it) }
        }
    }

    private suspend fun saveInboundTextMessage(
        fromDeviceId: String,
        business: BusinessEnvelope,
        route: String,
    ) {
        val textPayload = runCatching {
            json.decodeFromJsonElement(TextMessagePayload.serializer(), business.payload)
        }.getOrNull() ?: return
        messageRepository.saveTextMessage(
            messageId = textPayload.messageId,
            deviceId = fromDeviceId,
            direction = MessageDirection.Incoming,
            text = textPayload.text,
            route = route,
        )
        notifier.notifyMessageReceived(
            deviceId = fromDeviceId,
            deviceName = deviceRepository.getDevice(fromDeviceId)?.name ?: fromDeviceId,
            text = textPayload.text,
        )
    }

    private suspend fun handleClipboardSync(
        fromDeviceId: String,
        business: BusinessEnvelope,
    ) {
        if (!settingsDataStore.currentSettings().enableClipboardSync) {
            return
        }
        val payload = runCatching {
            json.decodeFromJsonElement(ClipboardSyncPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val hash = payload.clipboardHash()
        clipboardSuppressedHash = hash
        when (payload.contentType) {
            "text/plain" -> {
                val content = payload.content ?: return
                if (content.toByteArray().size > CLIPBOARD_MAX_BYTES) {
                    return
                }
                clipboardManager.setPrimaryClip(ClipData.newPlainText("CoLink", content))
            }

            "text/html" -> {
                val content = payload.content ?: return
                if (content.toByteArray().size > CLIPBOARD_MAX_BYTES) {
                    return
                }
                clipboardManager.setPrimaryClip(
                    ClipData.newHtmlText("CoLink", htmlClipboardPlainText(content), content),
                )
            }

            "image/png", "image/jpeg" -> {
                val data = payload.data ?: return
                if (data.toByteArray().size > CLIPBOARD_MAX_BYTES * 2) {
                    return
                }
                val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return
                if (bytes.size > CLIPBOARD_MAX_BYTES) {
                    return
                }
                val file = File(
                    context.cacheDir,
                    "clipboard-${System.currentTimeMillis()}.${
                        if (payload.contentType == "image/png") "png" else "jpg"
                    }",
                )
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                clipboardManager.setPrimaryClip(
                    ClipData.newUri(context.contentResolver, "CoLink", uri),
                )
            }

            else -> Unit
        }
    }

    private suspend fun handleFileOffer(
        fromDeviceId: String,
        envelopeId: String?,
        business: BusinessEnvelope,
        route: String,
    ) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileOfferPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        fileTransferRepository.save(
            FileTransfer(
                sessionId = payload.sessionId,
                deviceId = fromDeviceId,
                direction = FileTransferDirection.Incoming,
                fileName = payload.fileName,
                fileSize = payload.fileSize,
                transferredBytes = 0,
                totalChunks = payload.totalChunks,
                status = "offered",
                checksum = payload.checksum,
                route = route,
                localUri = null,
                error = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        envelopeId?.let { incomingFileOfferCorrelationIds[payload.sessionId] = it }
        notifier.notifyFileOffer(
            sessionId = payload.sessionId,
            deviceId = fromDeviceId,
            deviceName = deviceRepository.getDevice(fromDeviceId)?.name ?: fromDeviceId,
            fileName = payload.fileName,
        )
        scheduleOfferExpiry(payload.sessionId)
    }

    suspend fun acceptFileOffer(sessionId: String): Result<Unit> =
        runCatching {
            val transfer = fileTransferRepository.get(sessionId) ?: error("transfer not found")
            require(transfer.status == "offered") { "transfer is no longer available" }
            val token = UUID.randomUUID().toString().replace("-", "")
            val verifier = FileChecksumVerifier.from(transfer.checksum)
            val tempFile = File.createTempFile(
                "colink-${sessionId}-",
                ".part",
            )
            incomingTransfers[sessionId] = IncomingTransferState(
                deviceId = transfer.deviceId,
                expectedChunks = transfer.totalChunks,
                receivedChunks = 0,
                tempFile = tempFile,
                verifier = verifier,
                route = transfer.route,
            )
            lanWebSocketServer.registerTransferToken(sessionId, token)
            val accepted = transfer.copy(
                status = "receiving",
                localUri = tempFile.absolutePath,
                updatedAt = System.currentTimeMillis(),
            )
            fileTransferRepository.save(accepted)
            sendBusinessMessage(
                targetDeviceId = transfer.deviceId,
                business = BusinessEnvelope(
                    type = FILE_ACCEPT_TYPE,
                    payload = json.encodeToJsonElement(
                        FileAcceptPayload(
                            sessionId = sessionId,
                            transferToken = token,
                        ),
                    ),
                ),
                correlationId = incomingFileOfferCorrelationIds.remove(sessionId),
            ).getOrThrow()
            if (transfer.totalChunks == 0L) {
                finishIncomingTransfer(sessionId, incomingTransfers[sessionId] ?: return@runCatching, accepted)
            }
        }

    suspend fun sendFileOffer(targetDeviceId: String, offer: BuiltFileOffer): Result<Unit> =
        runCatching {
            val now = System.currentTimeMillis()
            val route = routeForDevice(targetDeviceId)
            fileTransferRepository.save(
                FileTransfer(
                    sessionId = offer.payload.sessionId,
                    deviceId = targetDeviceId,
                    direction = FileTransferDirection.Outgoing,
                    fileName = offer.payload.fileName,
                    fileSize = offer.payload.fileSize,
                    transferredBytes = 0,
                    totalChunks = offer.payload.totalChunks,
                    status = "offered",
                    checksum = offer.payload.checksum,
                    route = route,
                    localUri = offer.localUri,
                    error = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            outgoingTransfers[offer.payload.sessionId] = OutgoingTransferState(
                deviceId = targetDeviceId,
                localUri = offer.localUri,
                chunkSize = offer.payload.chunkSize.toInt(),
            )
            scheduleOfferExpiry(offer.payload.sessionId)
            val actualRoute = sendBusinessMessage(
                targetDeviceId = targetDeviceId,
                business = BusinessEnvelope(
                    type = FILE_OFFER_TYPE,
                    payload = json.encodeToJsonElement(offer.payload),
                ),
            ).getOrThrow()
            fileTransferRepository.get(offer.payload.sessionId)?.let { transfer ->
                if (transfer.route != actualRoute) {
                    fileTransferRepository.save(
                        transfer.copy(
                            route = actualRoute,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

    suspend fun rejectFileOffer(sessionId: String, reason: String = REASON_TRANSFER_USER_REJECTED): Result<Unit> =
        runCatching {
            val transfer = fileTransferRepository.get(sessionId) ?: error("transfer not found")
            val message = transferErrorMessage(reason)
            fileTransferRepository.save(
                transfer.copy(
                    status = "rejected",
                    error = transferRecordError(reason, message),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            sendBusinessMessage(
                targetDeviceId = transfer.deviceId,
                business = BusinessEnvelope(
                    type = FILE_REJECT_TYPE,
                    payload = json.encodeToJsonElement(
                        FileRejectPayload(
                            sessionId = sessionId,
                            reason = reason,
                            message = message,
                        ),
                    ),
                ),
                correlationId = incomingFileOfferCorrelationIds.remove(sessionId),
            ).getOrThrow()
        }

    fun startLanPairing(deviceId: String) {
        scope.launch {
            val identity = deviceRepository.localDeviceIdentity() ?: return@launch
            val candidate = _lanPairingCandidates.value.firstOrNull { it.deviceId == deviceId }
                ?: return@launch
            synchronized(lanPeerLock) {
                devicePageLanConnections.add(deviceId)
            }
            lanWebSocketClient.connect(
                identity = identity,
                deviceId = candidate.deviceId,
                ip = candidate.ip,
                port = candidate.port,
                allowPairing = true,
                listener = lanClientListener,
            )
        }
    }

    fun cancelLanPairing(deviceId: String) {
        scope.launch {
            lanWebSocketClient.disconnect(deviceId)
            lanWebSocketServer.disconnect(deviceId)
            synchronized(lanPeerLock) {
                lanConnectingPeers.remove(deviceId)
                devicePageLanConnections.remove(deviceId)
            }
        }
    }

    fun refreshLanPairingCandidate(deviceId: String) {
        scope.launch {
            refreshPairingCandidate(deviceId)
        }
    }

    fun clearLanConnectionError() {
        _lanConnectionError.value = null
    }

    private suspend fun handleFileAccept(fromDeviceId: String, business: BusinessEnvelope) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileAcceptPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val transfer = fileTransferRepository.get(payload.sessionId) ?: return
        if (transfer.deviceId != fromDeviceId) {
            return
        }
        fileTransferRepository.save(
            transfer.copy(
                status = "accepted",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val outgoing = outgoingTransfers[payload.sessionId] ?: return
        val device = deviceRepository.getDevice(fromDeviceId) ?: return
        if (device.lanAvailable && device.localIp != null && device.localPort != null) {
            var opened = false
            lanWebSocketClient.connectTransfer(
                sessionId = payload.sessionId,
                token = payload.transferToken,
                ip = device.localIp,
                port = device.localPort,
                listener = object : LanWebSocketClient.TransferListener {
                    override fun onOpen(connection: TransferConnection) {
                        opened = true
                        outgoing.transferConnection = connection
                        scope.launch { sendLanFileData(payload.sessionId, outgoing, connection) }
                    }

                    override fun onFrame(frame: FileDataFrame) {
                        scope.launch { handleOutgoingTransferFrame(payload.sessionId, frame) }
                    }

                    override fun onClosed(reason: String) {
                        scope.launch {
                            val transfer = fileTransferRepository.get(payload.sessionId) ?: return@launch
                            if (transfer.status == "completed" || transfer.status == "cancelled") {
                                return@launch
                            }
                            if (!opened || transfer.status == "sending") {
                                cancelTransfer(payload.sessionId, REASON_TRANSFER_GENERIC, "LAN data connection failed: $reason")
                            }
                        }
                    }
                },
            )
            return
        }

        sendRelayFileData(payload.sessionId, outgoing)
    }

    private suspend fun sendLanFileData(
        sessionId: String,
        outgoing: OutgoingTransferState,
        connection: TransferConnection,
    ) {
        val transfer = fileTransferRepository.get(sessionId) ?: return
        fileTransferRepository.save(
            transfer.copy(
                status = "sending",
                route = "lan",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        sendBusinessMessage(
            targetDeviceId = outgoing.deviceId,
            business = BusinessEnvelope(
                type = FILE_READY_TYPE,
                payload = json.encodeToJsonElement(FileReadyPayload(sessionId)),
            ),
        ).getOrElse {
            cancelTransfer(sessionId, REASON_TRANSFER_GENERIC, it.message ?: "File route is unavailable")
            return
        }
        val uri = Uri.parse(outgoing.localUri)
        var index = 0u
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(outgoing.chunkSize)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                if (!waitForSendWindow(sessionId, index.toLong(), LAN_SEND_WINDOW_CHUNKS)) {
                    cancelTransfer(sessionId, REASON_TRANSFER_GENERIC, "Transfer timed out")
                    return
                }
                val chunk = buffer.copyOf(read)
                check(connection.send(FileDataFrame.chunk(index, chunk))) { "LAN transfer send failed" }
                index += 1u
            }
        } ?: error("file is unavailable")
        connection.send(FileDataFrame.finish(index))
    }

    private suspend fun sendRelayFileData(sessionId: String, outgoing: OutgoingTransferState) {
        val transfer = fileTransferRepository.get(sessionId) ?: return
        fileTransferRepository.save(
            transfer.copy(
                status = "sending",
                route = "cloud",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val encoder = Base64.getEncoder()
        val uri = Uri.parse(outgoing.localUri)
        var index = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(outgoing.chunkSize)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                if (!waitForSendWindow(sessionId, index, RELAY_SEND_WINDOW_CHUNKS)) {
                    cancelTransfer(sessionId, REASON_TRANSFER_GENERIC, "Transfer timed out")
                    return
                }
                val data = encoder.encodeToString(buffer.copyOf(read))
                sendViaCloud(
                    outgoing.deviceId,
                    BusinessEnvelope(
                        type = FILE_CHUNK_TYPE,
                        payload = json.encodeToJsonElement(
                            FileChunkPayload(
                                sessionId = sessionId,
                                chunkIndex = index,
                                data = data,
                            ),
                        ),
                    ),
                ).getOrElse {
                    CoLinkLog.w(
                        "Transfer",
                        "relay chunk send failed session=${CoLinkLog.shortId(sessionId)} index=$index",
                        it,
                    )
                    cancelTransfer(sessionId, REASON_TRANSFER_GENERIC, it.message ?: "Cloud connection is not ready")
                    return
                }
                index += 1
            }
        } ?: error("file is unavailable")
    }

    private suspend fun handleFileReady(fromDeviceId: String, business: BusinessEnvelope) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileReadyPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val transfer = fileTransferRepository.get(payload.sessionId) ?: return
        if (transfer.deviceId != fromDeviceId || transfer.status != "receiving") {
            return
        }
        val state = incomingTransfers[payload.sessionId]
        if (state != null) {
            state.route = "lan"
        }
        fileTransferRepository.save(
            transfer.copy(
                route = "lan",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun handleFileChunk(
        fromDeviceId: String,
        business: BusinessEnvelope,
        route: String,
    ) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileChunkPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val state = incomingTransfers[payload.sessionId] ?: return
        val transfer = fileTransferRepository.get(payload.sessionId) ?: return
        if (transfer.deviceId != fromDeviceId) {
            return
        }
        state.route = route
        val bytes = runCatching { Base64.getDecoder().decode(payload.data) }.getOrNull() ?: return
        processIncomingChunk(
            sessionId = payload.sessionId,
            state = state,
            transfer = transfer,
            chunkIndex = payload.chunkIndex,
            bytes = bytes,
            finishWhenComplete = true,
        )
    }

    private suspend fun handleFileAck(business: BusinessEnvelope) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileAckPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        processFileAck(payload.sessionId, payload.nextExpectedIndex)
    }

    private suspend fun handleFileRetransmit(business: BusinessEnvelope, lan: Boolean) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileRetransmitPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        retransmitFileChunk(payload.sessionId, payload.chunkIndex, lan)
    }

    private suspend fun handleFileReject(business: BusinessEnvelope) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileRejectPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val transfer = fileTransferRepository.get(payload.sessionId) ?: return
        fileTransferRepository.save(
            transfer.copy(
                status = "rejected",
                error = transferRecordError(payload.reason, payload.message),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun handleFileCancel(business: BusinessEnvelope) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileCancelPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val transfer = fileTransferRepository.get(payload.sessionId) ?: return
        incomingTransfers.remove(payload.sessionId)?.tempFile?.delete()
        outgoingTransfers.remove(payload.sessionId)
        ackSignals.remove(payload.sessionId)?.complete(Unit)
        lanWebSocketServer.unregisterTransfer(payload.sessionId)
        fileTransferRepository.save(
            transfer.copy(
                status = "cancelled",
                error = transferRecordError(payload.reason, payload.message),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun handleFileDone(business: BusinessEnvelope) {
        val payload = runCatching {
            json.decodeFromJsonElement(FileDonePayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val transfer = fileTransferRepository.get(payload.sessionId) ?: return
        outgoingTransfers.remove(payload.sessionId)?.transferConnection?.close()
        incomingTransfers.remove(payload.sessionId)
        ackSignals.remove(payload.sessionId)?.complete(Unit)
        lanWebSocketServer.unregisterTransfer(payload.sessionId)
        fileTransferRepository.save(
            transfer.copy(
                status = if (payload.success) "completed" else "failed",
                transferredBytes = if (payload.success) transfer.fileSize else transfer.transferredBytes,
                error = transferRecordError(payload.reason, payload.message),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun transferErrorMessage(reason: String): String =
        when (reason) {
            REASON_TRANSFER_USER_CANCELLED -> "User cancelled the transfer"
            REASON_TRANSFER_USER_REJECTED -> "User rejected the file"
            REASON_TRANSFER_CHECKSUM_MISMATCH -> "File checksum verification failed"
            REASON_TRANSFER_GENERIC -> "Generic transfer failure"
            else -> reason
        }

    private fun transferRecordError(reason: String?, message: String?): String? =
        when {
            reason == null -> message
            reason == REASON_TRANSFER_GENERIC -> message ?: reason
            reason.startsWith("colink:") -> reason
            else -> message ?: reason
        }

    private suspend fun sendBusinessMessage(
        targetDeviceId: String,
        business: BusinessEnvelope,
        correlationId: String? = null,
    ): Result<String> {
        val localizedContext = LocaleHelper.localized(context)
        val lanResult = sendViaLan(targetDeviceId, business, correlationId)
        if (lanResult.isSuccess) {
            return lanResult
        }

        val cloudResult = sendViaCloud(targetDeviceId, business, correlationId)
        if (cloudResult.isSuccess) {
            return cloudResult
        }

        val lanError = lanResult.exceptionOrNull()
        val cloudError = cloudResult.exceptionOrNull()
        val error = IllegalStateException(
            localizedContext.getString(R.string.message_route_unavailable),
            cloudError ?: lanError,
        )
        CoLinkLog.w(
            "Connection",
            "business send failed device=${CoLinkLog.shortId(targetDeviceId)} type=${business.type} " +
                "lan=${lanError?.message ?: "failed"} cloud=${cloudError?.message ?: "failed"}",
            error,
        )
        return Result.failure(error)
    }

    private suspend fun routeForDevice(deviceId: String): String {
        val device = deviceRepository.getDevice(deviceId)
        return if (device?.lanAvailable == true) "lan" else "cloud"
    }

    private val lanClientListener =
        object : LanWebSocketClient.Listener {
            override fun onConnected(deviceId: String) {
                CoLinkLog.i("LAN", "outbound peer connected device=${CoLinkLog.shortId(deviceId)}")
                scope.launch {
                    handleLanPeerConnected(deviceId)
                }
            }

            override fun onMessage(fromDeviceId: String, envelopeId: String, message: BusinessEnvelope) {
                CoLinkLog.d(
                    "LAN",
                    "received outbound peer message device=${CoLinkLog.shortId(fromDeviceId)} type=${message.type}",
                )
                scope.launch { saveInboundBusinessMessage(fromDeviceId, envelopeId, message, "lan") }
            }

            override fun onConnectionFailed(deviceId: String, reason: String) {
                CoLinkLog.w("LAN", "outbound peer connection failed device=${CoLinkLog.shortId(deviceId)} reason=$reason")
                val localizedContext = LocaleHelper.localized(context)
                val showDevicePageError = synchronized(lanPeerLock) {
                    devicePageLanConnections.remove(deviceId)
                }
                if (showDevicePageError) {
                    _lanConnectionError.value = localizedContext.getString(
                        R.string.lan_connection_failed_message,
                        lanFailureReasonText(reason),
                    )
                }
                failPendingLanSends(deviceId, reason)
            }

            override fun onDisconnected(deviceId: String) {
                CoLinkLog.w("LAN", "outbound peer disconnected device=${CoLinkLog.shortId(deviceId)}")
                scope.launch {
                    handleLanPeerDisconnected(deviceId)
                }
            }

            override fun onKeyChanged(deviceId: String, name: String) {
                CoLinkLog.w("LAN", "outbound peer key changed device=${CoLinkLog.shortId(deviceId)} name=$name")
                scope.launch { handleLanKeyChanged(deviceId, name) }
            }
        }

    private suspend fun handleTransferFrame(sessionId: String, frame: FileDataFrame) {
        val state = incomingTransfers[sessionId] ?: return
        state.frameMutex.withLock {
            if (incomingTransfers[sessionId] !== state) {
                return
            }
            handleTransferFrameLocked(sessionId, frame, state)
        }
    }

    private suspend fun handleTransferFrameLocked(
        sessionId: String,
        frame: FileDataFrame,
        state: IncomingTransferState,
    ) {
        val transfer = fileTransferRepository.get(sessionId) ?: return
        when (frame.kind) {
            FileDataFrameKind.Chunk -> {
                state.route = "lan"
                processIncomingChunk(
                    sessionId = sessionId,
                    state = state,
                    transfer = transfer.copy(route = "lan"),
                    chunkIndex = frame.index.toLong(),
                    bytes = frame.payload,
                    finishWhenComplete = false,
                )
            }

            FileDataFrameKind.Finish -> {
                state.finishReceived = true
                if (state.receivedChunks < state.expectedChunks) {
                    sendRetransmit(state.deviceId, sessionId, state.receivedChunks, lan = true)
                } else {
                    finishIncomingTransfer(sessionId, state, transfer.copy(route = "lan"))
                }
            }

            FileDataFrameKind.Cancel -> {
                val reason = frame.payload.decodeToString().ifBlank { "cancelled" }
                incomingTransfers.remove(sessionId)
                lanWebSocketServer.unregisterTransfer(sessionId)
                state.tempFile.delete()
                fileTransferRepository.save(
                    transfer.copy(
                        status = "cancelled",
                        error = reason,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            FileDataFrameKind.Ack -> processFileAck(sessionId, frame.index.toLong())
            FileDataFrameKind.Retransmit -> retransmitFileChunk(
                sessionId = sessionId,
                chunkIndex = frame.index.toLong(),
                lan = true,
            )
        }
    }

    private suspend fun handleOutgoingTransferFrame(sessionId: String, frame: FileDataFrame) {
        when (frame.kind) {
            FileDataFrameKind.Ack -> processFileAck(sessionId, frame.index.toLong())
            FileDataFrameKind.Retransmit -> retransmitFileChunk(sessionId, frame.index.toLong(), lan = true)
            FileDataFrameKind.Cancel -> {
                val reason = frame.payload.decodeToString().ifBlank { "cancelled" }
                val transfer = fileTransferRepository.get(sessionId) ?: return
                outgoingTransfers.remove(sessionId)?.transferConnection?.close()
                ackSignals.remove(sessionId)?.complete(Unit)
                fileTransferRepository.save(
                    transfer.copy(
                        status = "cancelled",
                        error = reason,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            FileDataFrameKind.Chunk,
            FileDataFrameKind.Finish,
            -> Unit
        }
    }

    private suspend fun finishIncomingTransfer(
        sessionId: String,
        state: IncomingTransferState,
        transfer: FileTransfer,
    ) {
        val localizedContext = LocaleHelper.localized(context)
        val chunksComplete = state.receivedChunks == state.expectedChunks
        val verifyingTransfer = if (chunksComplete) {
            transfer.copy(
                status = "verifying",
                transferredBytes = transfer.fileSize,
                updatedAt = System.currentTimeMillis(),
            ).also { fileTransferRepository.save(it) }
        } else {
            transfer
        }
        val checksumMatches = chunksComplete && state.verifier.verify()
        val success = chunksComplete && checksumMatches
        val reason = when {
            success -> null
            !chunksComplete -> REASON_TRANSFER_GENERIC
            else -> REASON_TRANSFER_CHECKSUM_MISMATCH
        }
        val message = when {
            success -> null
            !chunksComplete -> "Missing file chunks"
            else -> transferErrorMessage(REASON_TRANSFER_CHECKSUM_MISMATCH)
        }
        val finalUri = if (success) {
            saveReceivedFileToDownloads(state.tempFile, verifyingTransfer.fileName)
        } else {
            state.tempFile.delete()
            null
        }
        fileTransferRepository.save(
            verifyingTransfer.copy(
                status = if (success) "completed" else "failed",
                transferredBytes = if (success) verifyingTransfer.fileSize else transfer.transferredBytes,
                localUri = finalUri ?: verifyingTransfer.localUri,
                error = transferRecordError(reason, message),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        incomingTransfers.remove(sessionId)
        lanWebSocketServer.unregisterTransfer(sessionId)
        sendBusinessMessage(
            targetDeviceId = state.deviceId,
            business = BusinessEnvelope(
                type = FILE_DONE_TYPE,
                payload = json.encodeToJsonElement(
                    FileDonePayload(
                        sessionId = sessionId,
                        success = success,
                        reason = reason,
                        message = message,
                    ),
                ),
            ),
        )
        notifyEvent(
            title = if (success) {
                localizedContext.getString(R.string.notification_file_received_title)
            } else {
                localizedContext.getString(R.string.notification_file_transfer_failed_title)
            },
            text = verifyingTransfer.fileName,
        )
    }

    private val lanListener =
        object : LanWebSocketServer.Listener {
            override fun onConnected(deviceId: String) {
                CoLinkLog.i("LAN", "inbound peer connected device=${CoLinkLog.shortId(deviceId)}")
                scope.launch {
                    handleLanPeerConnected(deviceId)
                }
            }

            override fun onMessage(fromDeviceId: String, envelopeId: String, message: BusinessEnvelope) {
                CoLinkLog.d(
                    "LAN",
                    "received inbound peer message device=${CoLinkLog.shortId(fromDeviceId)} type=${message.type}",
                )
                scope.launch { saveInboundBusinessMessage(fromDeviceId, envelopeId, message, "lan") }
            }

            override fun onDisconnected(deviceId: String) {
                CoLinkLog.w("LAN", "inbound peer disconnected device=${CoLinkLog.shortId(deviceId)}")
                scope.launch {
                    handleLanPeerDisconnected(deviceId)
                }
            }

            override fun onKeyChanged(deviceId: String, name: String) {
                CoLinkLog.w("LAN", "inbound peer key changed device=${CoLinkLog.shortId(deviceId)} name=$name")
                scope.launch { handleLanKeyChanged(deviceId, name) }
            }

            override fun onTransferConnected(sessionId: String) = Unit

            override fun onTransferFrame(sessionId: String, frame: FileDataFrame) {
                CoLinkLog.d("Transfer", "received LAN transfer frame session=${CoLinkLog.shortId(sessionId)} kind=${frame.kind}")
                scope.launch { handleTransferFrame(sessionId, frame) }
            }

            override fun onTransferClosed(sessionId: String) {
                CoLinkLog.w("Transfer", "LAN transfer closed session=${CoLinkLog.shortId(sessionId)}")
                scope.launch {
                    val transfer = fileTransferRepository.get(sessionId) ?: return@launch
                    if (transfer.status == "receiving") {
                        fileTransferRepository.save(
                            transfer.copy(
                                status = "failed",
                                error = "LAN transfer closed",
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    incomingTransfers.remove(sessionId)
                }
            }

            override fun onSwimMessage(message: SwimEnvelope, sourceIp: String?) {
                CoLinkLog.d(
                    "SWIM",
                    "received ${message.type} from=${CoLinkLog.shortId(message.payload.from)} sourceIp=$sourceIp gossip=${message.payload.gossip.size}",
                )
                scope.launch { processSwimMessage(message, sourceIp) }
            }

            override fun currentSwimGossip(localDeviceId: String): List<SwimGossip> =
                gossipBatch(localDeviceId)

            override fun currentSwimIncarnation(localDeviceId: String): Long =
                swimMembership.localIncarnation(localDeviceId)
        }

    private fun startLanDiscovery(identity: DeviceIdentity) {
        CoLinkLog.i(
            "LAN",
            "starting discovery device=${CoLinkLog.shortId(identity.deviceId)} name=${identity.name}",
        )
        nsdDiscovery.start(
            serviceName = "colink-${identity.deviceId.take(8)}",
            port = com.colink.android.network.lan.LAN_PORT,
            deviceId = identity.deviceId,
            deviceName = identity.name,
            deviceType = identity.type,
            listener = nsdListener,
        )
    }

    private fun lanFailureReasonText(reason: String): String =
        when (reason) {
            "signature invalid",
            "signature_invalid",
            REASON_AUTH_SIGNATURE_INVALID,
            -> LocaleHelper.localized(context).getString(R.string.lan_connection_failed_signature)

            "key_changed",
            REASON_AUTH_KEY_CHANGED,
            -> LocaleHelper.localized(context).getString(R.string.lan_connection_failed_key_changed)

            "user_rejected",
            REASON_PAIRING_USER_REJECTED,
            -> LocaleHelper.localized(context).getString(R.string.lan_connection_failed_user_rejected)

            "LAN device key is not trusted" -> LocaleHelper.localized(context).getString(R.string.lan_connection_failed_untrusted)
            else -> reason.ifBlank { LocaleHelper.localized(context).getString(R.string.lan_connection_failed_unknown) }
        }

    private val nsdListener =
        object : NsdDiscovery.Listener {
            override fun onServiceResolved(
                deviceId: String,
                name: String,
                type: String,
                ip: String,
                port: Int,
            ) {
                CoLinkLog.i(
                    "LAN",
                    "resolved service device=${CoLinkLog.shortId(deviceId)} name=$name type=$type ip=$ip port=$port",
                )
                scope.launch {
                    val identity = deviceRepository.localDeviceIdentity() ?: return@launch
                    if (deviceId == identity.deviceId) {
                        return@launch
                    }
                    swimEndpoints[deviceId] = LanEndpoint(ip, port)
                    name.takeIf { it.isNotBlank() }?.let { swimNames[deviceId] = it }
                    val normalizedType = type.normalizedDeviceType()
                    normalizedType?.let { swimTypes[deviceId] = it }
                    syncKnownLanEndpoint(deviceId)
                    val response = lanSwimClient.ping(
                        identity = identity,
                        ip = ip,
                        port = port,
                        incarnation = swimMembership.localIncarnation(identity.deviceId),
                        seq = nextSwimSeq(),
                        gossip = gossipBatch(identity.deviceId),
                    ).onFailure { error ->
                        CoLinkLog.w(
                            "SWIM",
                            "discovery ping failed device=${CoLinkLog.shortId(deviceId)} ip=$ip port=$port",
                            error,
                        )
                    }.getOrNull() ?: return@launch
                    if (response.type == "swim.ack" && response.payload.from == deviceId) {
                        processSwimMessage(response, ip)
                    }
                }
            }

            override fun onServiceLost(deviceId: String) {
                CoLinkLog.w("LAN", "service lost device=${CoLinkLog.shortId(deviceId)}")
            }
        }

    private suspend fun runCloudLoop() {
        var attempt = 0
        while (scope.isActive) {
            if (settingsDataStore.currentSession() == null) {
                _cloudState.value = CloudConnectionState()
                cloudBusinessVersions.clear()
                connectionJob = null
                return
            }
            val closed = CompletableDeferred<String?>()
            _cloudState.value =
                CloudConnectionState(
                    status = if (attempt == 0) CloudStatus.Connecting else CloudStatus.Reconnecting,
                    attempt = attempt,
                )

            val ticketResult = ticketProvider.obtainTicket()
            if (ticketResult.isFailure) {
                val error = ticketResult.exceptionOrNull()
                attempt += 1
                _cloudState.value =
                    CloudConnectionState(CloudStatus.Reconnecting, attempt, error?.message)
                delay(backoffDelay(attempt))
                continue
            }
            val ticket = ticketResult.getOrThrow()

            cloudWebSocketClient.connect(
                url = buildWsUrl(ticket.serverUrl, ticket.ticket),
                listener = object : CloudWebSocketClient.Listener {
                    override fun onOpen() {
                        _cloudState.value = CloudConnectionState(CloudStatus.Connected)
                        CoLinkLog.i("Cloud", "cloud websocket connected")
                        scope.launch {
                            val session = authRepository.currentSession().getOrNull() ?: return@launch
                            deviceRepository.ensureDeviceIdentity(session).getOrThrow()
                            deviceRepository.syncPendingDeviceKey(session)
                            deviceRepository.syncDevices(session)
                        }
                    }

                    override fun onMessage(message: CloudServerEnvelope) {
                        CoLinkLog.d("Cloud", "received cloud message type=${message.type} from=${CoLinkLog.shortId(message.from)}")
                        scope.launch { handleCloudMessage(message) }
                    }

                    override fun onClosed(reason: String?) {
                        CoLinkLog.w("Cloud", "cloud websocket closed reason=${reason ?: "unknown"}")
                        if (!closed.isCompleted) {
                            closed.complete(reason)
                        }
                    }
                },
            )

            val pingJob = scope.launch {
                while (isActive) {
                    delay(30_000)
                    cloudWebSocketClient.send(
                        CloudClientEnvelope(
                            id = UUID.randomUUID().toString(),
                            type = "ping",
                        ),
                    )
                }
            }

            val reason = closed.await()
            pingJob.cancel()
            cloudBusinessVersions.clear()
            attempt += 1
            CoLinkLog.w("Cloud", "cloud loop reconnecting attempt=$attempt reason=${reason ?: "unknown"}")
            _cloudState.value =
                CloudConnectionState(CloudStatus.Reconnecting, attempt, reason)
            delay(backoffDelay(attempt))
        }
    }

    private suspend fun handleCloudMessage(message: CloudServerEnvelope) {
        when (message.type) {
            "device.online" -> {
                val payload = rememberCloudBusinessVersion(message)
                message.from?.let { deviceId ->
                    deviceRepository.markCloudPresence(
                        deviceId = deviceId,
                        online = true,
                        name = payload?.name,
                        deviceType = payload?.type,
                    )
                }
            }
            "device.offline" -> {
                message.from?.let { deviceId ->
                    cloudBusinessVersions.remove(deviceId)
                    deviceRepository.markCloudPresence(
                        deviceId = deviceId,
                        online = false,
                    )
                }
            }
            "relay", "broadcast" -> {
                val from = message.from ?: return
                val payload = message.payload ?: return
                val business = runCatching {
                    json.decodeFromJsonElement(BusinessEnvelope.serializer(), payload)
                }.getOrNull() ?: return
                saveInboundBusinessMessage(from, message.id, business, "cloud")
            }
        }
    }

    private fun rememberCloudBusinessVersion(message: CloudServerEnvelope): DeviceOnlinePayload? {
        val from = message.from ?: return null
        val payload = message.payload ?: return null
        val online = runCatching {
            json.decodeFromJsonElement(DeviceOnlinePayload.serializer(), payload)
        }.getOrNull() ?: return null
        cloudBusinessVersions[from] = online.businessVersion
        return online
    }

    private fun ensureCloudBusinessCompatible(targetDeviceId: String) {
        val peerVersion = cloudBusinessVersions[targetDeviceId] ?: return
        val compatibility = checkBusinessProtocolVersion(peerVersion)
        check(compatibility.compatible) {
            compatibility.message ?: compatibility.reason ?: "business protocol version incompatible"
        }
    }

    private fun ensureKnownCloudBusinessVersionsCompatible() {
        val incompatible = cloudBusinessVersions.entries.firstOrNull { (_, version) ->
            !checkBusinessProtocolVersion(version).compatible
        } ?: return
        val compatibility = checkBusinessProtocolVersion(incompatible.value)
        check(false) {
            compatibility.message ?: compatibility.reason ?: "business protocol version incompatible"
        }
    }

    private fun buildWsUrl(serverUrl: String, ticket: String): String {
        val uri = URI(serverUrl.trim())
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        val basePath = uri.rawPath
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() && it != "/" }
            ?: ""
        val encodedTicket = URLEncoder.encode(ticket, Charsets.UTF_8.name())
        val encodedBusinessVersion = URLEncoder.encode(BUSINESS_PROTOCOL_VERSION, Charsets.UTF_8.name())
        return "$scheme://${uri.rawAuthority}$basePath/ws/v1?ticket=$encodedTicket&businessVersion=$encodedBusinessVersion"
    }

    private fun backoffDelay(attempt: Int): Long =
        when (attempt) {
            0, 1 -> 1_000
            2 -> 2_000
            3 -> 4_000
            4 -> 8_000
            else -> 30_000
        }

    private fun shouldInitiate(localDeviceId: String, peerDeviceId: String): Boolean =
        localDeviceId < peerDeviceId

    private suspend fun processIncomingChunk(
        sessionId: String,
        state: IncomingTransferState,
        transfer: FileTransfer,
        chunkIndex: Long,
        bytes: ByteArray,
        finishWhenComplete: Boolean,
    ) {
        when {
            chunkIndex < state.receivedChunks -> sendAck(state.deviceId, sessionId, state.receivedChunks, state.route == "lan")
            chunkIndex > state.receivedChunks -> {
                if (chunkIndex - state.receivedChunks <= state.windowSize) {
                    state.reorderBuffer.putIfAbsent(chunkIndex, bytes)
                }
                sendRetransmit(state.deviceId, sessionId, state.receivedChunks, state.route == "lan")
            }
            else -> {
                var deltaBytes = appendIncomingChunk(state, bytes).toLong()
                while (true) {
                    val buffered = state.reorderBuffer.remove(state.receivedChunks) ?: break
                    deltaBytes += appendIncomingChunk(state, buffered)
                }
                val current = fileTransferRepository.get(sessionId) ?: transfer
                val nextBytes = (current.transferredBytes + deltaBytes).coerceAtMost(current.fileSize)
                fileTransferRepository.save(
                    current.copy(
                        transferredBytes = nextBytes,
                        route = state.route,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                if (shouldSendFileAck(state.receivedChunks, state.expectedChunks)) {
                    sendAck(state.deviceId, sessionId, state.receivedChunks, state.route == "lan")
                }
                if (finishWhenComplete && state.receivedChunks == state.expectedChunks) {
                    finishIncomingTransfer(sessionId, state, current.copy(transferredBytes = nextBytes))
                }
                if (!finishWhenComplete && state.finishReceived) {
                    if (state.receivedChunks == state.expectedChunks) {
                        finishIncomingTransfer(sessionId, state, current.copy(transferredBytes = nextBytes))
                    } else {
                        sendRetransmit(state.deviceId, sessionId, state.receivedChunks, state.route == "lan")
                    }
                }
            }
        }
    }

    private fun scheduleOfferExpiry(sessionId: String) {
        scope.launch {
            delay(FILE_OFFER_TIMEOUT_MILLIS)
            val transfer = fileTransferRepository.get(sessionId) ?: return@launch
            if (transfer.status != "offered") {
                return@launch
            }
            incomingTransfers.remove(sessionId)?.tempFile?.delete()
            outgoingTransfers.remove(sessionId)
            fileTransferRepository.save(
                transfer.copy(
                    status = "failed",
                    error = "offer expired",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun appendIncomingChunk(state: IncomingTransferState, bytes: ByteArray): Int {
        state.tempFile.appendBytes(bytes)
        state.verifier.update(bytes)
        state.receivedChunks += 1
        return bytes.size
    }

    private suspend fun processFileAck(sessionId: String, nextExpectedIndex: Long) {
        val transfer = fileTransferRepository.get(sessionId) ?: return
        val outgoing = outgoingTransfers[sessionId]
        if (outgoing != null && nextExpectedIndex > outgoing.acknowledgedChunks) {
            outgoing.acknowledgedChunks = nextExpectedIndex.coerceAtMost(transfer.totalChunks)
            ackSignals.remove(sessionId)?.complete(Unit)
        }
        val acknowledgedBytes = acknowledgedBytes(
            fileSize = transfer.fileSize,
            totalChunks = transfer.totalChunks,
            nextExpectedIndex = nextExpectedIndex,
        )
        if (acknowledgedBytes > transfer.transferredBytes) {
            fileTransferRepository.save(
                transfer.copy(
                    transferredBytes = acknowledgedBytes,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun waitForSendWindow(
        sessionId: String,
        nextChunkIndex: Long,
        windowSize: Long,
    ): Boolean {
        while (true) {
            val outgoing = outgoingTransfers[sessionId] ?: return false
            if (nextChunkIndex - outgoing.acknowledgedChunks < windowSize) {
                return true
            }
            val signal = CompletableDeferred<Unit>()
            ackSignals[sessionId] = signal
            withTimeoutOrNull(30_000) { signal.await() } ?: return false
        }
    }

    private suspend fun retransmitFileChunk(
        sessionId: String,
        chunkIndex: Long,
        lan: Boolean,
    ) {
        if (chunkIndex < 0) {
            return
        }
        val outgoing = outgoingTransfers[sessionId] ?: return
        val offset = chunkIndex * outgoing.chunkSize
        val bytes = readChunk(Uri.parse(outgoing.localUri), offset, outgoing.chunkSize) ?: return
        if (lan) {
            val frame = FileDataFrame.chunk(chunkIndex.toUInt(), bytes)
            outgoing.transferConnection?.send(frame)
                ?: lanWebSocketServer.sendTransferFrame(sessionId, frame)
        } else {
            sendViaCloud(
                outgoing.deviceId,
                BusinessEnvelope(
                    type = FILE_CHUNK_TYPE,
                    payload = json.encodeToJsonElement(
                        FileChunkPayload(
                            sessionId = sessionId,
                            chunkIndex = chunkIndex,
                            data = Base64.getEncoder().encodeToString(bytes),
                        ),
                    ),
                ),
            ).onFailure {
                CoLinkLog.w(
                    "Transfer",
                    "relay chunk retransmit failed session=${CoLinkLog.shortId(sessionId)} index=$chunkIndex",
                    it,
                )
            }
        }
    }

    private fun readChunk(uri: Uri, offset: Long, chunkSize: Int): ByteArray? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(chunkSize)
            val read = input.read(buffer)
            if (read <= 0) {
                return null
            }
            return buffer.copyOf(read)
        }
        return null
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) {
                    break
                }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private suspend fun sendAck(
        deviceId: String,
        sessionId: String,
        nextExpectedIndex: Long,
        lan: Boolean,
    ) {
        if (lan) {
            lanWebSocketServer.sendTransferFrame(
                sessionId,
                FileDataFrame.ack(nextExpectedIndex.toUInt()),
            )
            return
        }
        sendBusinessMessage(
            targetDeviceId = deviceId,
            business = BusinessEnvelope(
                type = FILE_ACK_TYPE,
                payload = json.encodeToJsonElement(
                    FileAckPayload(
                        sessionId = sessionId,
                        nextExpectedIndex = nextExpectedIndex,
                    ),
                ),
            ),
        )
    }

    private suspend fun sendRetransmit(
        deviceId: String,
        sessionId: String,
        chunkIndex: Long,
        lan: Boolean,
    ) {
        if (lan) {
            lanWebSocketServer.sendTransferFrame(
                sessionId,
                FileDataFrame.retransmit(chunkIndex.toUInt()),
            )
            return
        }
        sendBusinessMessage(
            targetDeviceId = deviceId,
            business = BusinessEnvelope(
                type = FILE_RETRANSMIT_TYPE,
                payload = json.encodeToJsonElement(
                    FileRetransmitPayload(
                        sessionId = sessionId,
                        chunkIndex = chunkIndex,
                    ),
                ),
            ),
        )
    }

    suspend fun cancelTransfer(
        sessionId: String,
        reason: String = REASON_TRANSFER_USER_CANCELLED,
        message: String = transferErrorMessage(reason),
    ): Result<Unit> =
        runCatching {
            val transfer = fileTransferRepository.get(sessionId) ?: error("transfer not found")
            incomingTransfers.remove(sessionId)?.tempFile?.delete()
            outgoingTransfers.remove(sessionId)?.transferConnection?.send(FileDataFrame.cancel(reason))
            ackSignals.remove(sessionId)?.complete(Unit)
            lanWebSocketServer.sendTransferFrame(sessionId, FileDataFrame.cancel(reason))
            lanWebSocketServer.unregisterTransfer(sessionId)
            fileTransferRepository.save(
                transfer.copy(
                    status = "cancelled",
                    error = transferRecordError(reason, message),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            sendBusinessMessage(
                targetDeviceId = transfer.deviceId,
                business = BusinessEnvelope(
                    type = FILE_CANCEL_TYPE,
                    payload = json.encodeToJsonElement(
                        FileCancelPayload(
                            sessionId = sessionId,
                            reason = reason,
                            message = message,
                        ),
                    ),
                ),
            ).getOrThrow()
        }

    private fun shouldSendFileAck(nextExpectedIndex: Long, totalChunks: Long): Boolean =
        nextExpectedIndex >= totalChunks || nextExpectedIndex % FILE_ACK_INTERVAL_CHUNKS == 0L

    private fun acknowledgedBytes(
        fileSize: Long,
        totalChunks: Long,
        nextExpectedIndex: Long,
    ): Long {
        if (fileSize <= 0 || totalChunks <= 0 || nextExpectedIndex <= 0) {
            return 0
        }
        return (nextExpectedIndex.coerceAtMost(totalChunks) * fileSize / totalChunks)
            .coerceAtMost(fileSize)
    }

    private suspend fun processSwimMessage(message: SwimEnvelope, sourceIp: String?) {
        val identity = deviceRepository.localDeviceIdentity() ?: return
        sourceIp?.let { ip ->
            if (message.payload.from != identity.deviceId) {
                swimEndpoints[message.payload.from] = LanEndpoint(ip, com.colink.android.network.lan.LAN_PORT)
                CoLinkLog.d(
                    "SWIM",
                    "remembered endpoint device=${CoLinkLog.shortId(message.payload.from)} ip=$ip",
                )
                if (!lanTrustStore.isTrusted(message.payload.from)) {
                    deviceRepository.clearLanEndpoint(message.payload.from)
                }
                syncKnownLanEndpoint(message.payload.from)
            }
        }
        observeSwimAlive(identity.deviceId, message.payload.from, message.payload.incarnation)
        message.payload.gossip.forEach { entry ->
            if (entry.deviceId == identity.deviceId && entry.state == MemberState.Suspect.wireValue) {
                CoLinkLog.d("SWIM", "received suspicion for local device; refuting")
                swimMembership.refuteSelf(identity.deviceId, entry.incarnation)
            } else {
                mergeSwimMember(identity.deviceId, message.payload.from, entry)
            }
        }
    }

    private suspend fun observeSwimAlive(localDeviceId: String, deviceId: String, incarnation: Long?) {
        swimMembership.observeAlive(localDeviceId, deviceId, incarnation)
            ?.let { applyMemberUpdate(it) }
    }

    private suspend fun mergeSwimMember(
        localDeviceId: String,
        originDeviceId: String,
        entry: SwimGossip,
    ) {
        swimMembership.mergeMember(
            localDeviceId = localDeviceId,
            originDeviceId = originDeviceId,
            entry = entry,
        )?.let { applyMemberUpdate(it) }
    }

    private suspend fun markMember(
        localDeviceId: String,
        deviceId: String,
        state: MemberState,
        incarnation: Long?,
        explicit: Boolean,
    ) {
        swimMembership.markMember(localDeviceId, deviceId, state, incarnation, explicit)
            ?.let { applyMemberUpdate(it) }
    }

    private suspend fun applyMemberUpdate(update: MemberUpdate) {
        val deviceId = update.deviceId
        val state = update.state
        CoLinkLog.i(
            "SWIM",
            "member update device=${CoLinkLog.shortId(deviceId)} state=${state.wireValue} incarnation=${update.incarnation}",
        )
        when (state) {
            MemberState.Alive -> {
                val endpoint = swimEndpoints[deviceId]
                val lanTrusted = lanTrustStore.isTrusted(deviceId)
                if (endpoint != null) {
                    if (lanTrusted) {
                        CoLinkLog.d("LAN", "marking LAN endpoint device=${CoLinkLog.shortId(deviceId)} ip=${endpoint.ip} port=${endpoint.port}")
                        deviceRepository.markLanEndpoint(
                            deviceId,
                            endpoint.ip,
                            endpoint.port,
                            swimTypes[deviceId],
                            state.wireValue,
                        )
                    } else {
                        CoLinkLog.d("LAN", "clearing untrusted LAN endpoint device=${CoLinkLog.shortId(deviceId)}")
                        deviceRepository.clearLanEndpoint(deviceId)
                    }
                    updatePairingCandidate(deviceId, endpoint, state)
                }
                if (!lanTrusted) {
                    return
                }
            }

            MemberState.Dead,
            MemberState.Left,
            -> {
                CoLinkLog.w("SWIM", "clearing LAN endpoint because member is ${state.wireValue} device=${CoLinkLog.shortId(deviceId)}")
                removePairingCandidate(deviceId)
                deviceRepository.clearLanEndpoint(deviceId)
                lanWebSocketClient.disconnect(deviceId)
                lanWebSocketServer.disconnect(deviceId)
            }

            MemberState.Suspect -> {
                val endpoint = swimEndpoints[deviceId]
                val lanTrusted = lanTrustStore.isTrusted(deviceId)
                if (endpoint != null) {
                    if (lanTrusted) {
                        CoLinkLog.d(
                            "LAN",
                            "marking suspect LAN endpoint device=${CoLinkLog.shortId(deviceId)} ip=${endpoint.ip} port=${endpoint.port}",
                        )
                        deviceRepository.markLanEndpoint(
                            deviceId,
                            endpoint.ip,
                            endpoint.port,
                            swimTypes[deviceId],
                            state.wireValue,
                        )
                    } else {
                        CoLinkLog.d("LAN", "clearing untrusted suspect LAN endpoint device=${CoLinkLog.shortId(deviceId)}")
                        deviceRepository.clearLanEndpoint(deviceId)
                    }
                    updatePairingCandidate(deviceId, endpoint, state)
                }
                if (!lanTrusted) {
                    return
                }
            }
        }
    }

    private suspend fun updatePairingCandidate(
        deviceId: String,
        endpoint: LanEndpoint,
        state: MemberState,
    ) {
        if (state != MemberState.Alive || lanTrustStore.isTrusted(deviceId)) {
            removePairingCandidate(deviceId)
            return
        }
        val name = swimNames[deviceId]
            ?.takeIf { it.isNotBlank() }
            ?: deviceId
        val type = swimTypes[deviceId] ?: "unknown"
        val candidates = _lanPairingCandidates.value
            .filterNot { it.deviceId == deviceId }
            .plus(
                LanPairingCandidate(
                    deviceId = deviceId,
                    name = name,
                    type = type,
                    ip = endpoint.ip,
                    port = endpoint.port,
                    state = state.wireValue,
                ),
            )
            .sortedWith(
                compareBy<LanPairingCandidate, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.deviceId },
            )
        _lanPairingCandidates.value = candidates
        CoLinkLog.i("LAN", "updated pairing candidate device=${CoLinkLog.shortId(deviceId)} name=$name ip=${endpoint.ip} port=${endpoint.port}")
    }

    private fun removePairingCandidate(deviceId: String) {
        if (_lanPairingCandidates.value.any { it.deviceId == deviceId }) {
            CoLinkLog.i("LAN", "removed pairing candidate device=${CoLinkLog.shortId(deviceId)}")
        }
        _lanPairingCandidates.value = _lanPairingCandidates.value
            .filterNot { it.deviceId == deviceId }
    }

    private suspend fun refreshPairingCandidate(deviceId: String) {
        val state = swimMembership.memberState(deviceId) ?: return
        val endpoint = swimEndpoints[deviceId] ?: return
        updatePairingCandidate(deviceId, endpoint, state)
    }

    private suspend fun syncKnownLanEndpoint(deviceId: String): Boolean {
        val state = swimMembership.memberState(deviceId) ?: return false
        val endpoint = swimEndpoints[deviceId] ?: return false
        when (state) {
            MemberState.Alive,
            MemberState.Suspect,
            -> {
                if (lanTrustStore.isTrusted(deviceId)) {
                    deviceRepository.markLanEndpoint(
                        deviceId,
                        endpoint.ip,
                        endpoint.port,
                        swimTypes[deviceId],
                        state.wireValue,
                    )
                    updatePairingCandidate(deviceId, endpoint, state)
                    return true
                }
                updatePairingCandidate(deviceId, endpoint, state)
            }

            MemberState.Dead,
            MemberState.Left,
            -> removePairingCandidate(deviceId)
        }
        return false
    }

    private suspend fun handleLanKeyChanged(deviceId: String, name: String) {
        val localizedContext = LocaleHelper.localized(context)
        deviceRepository.clearLanEndpoint(deviceId)
        name.takeIf { it.isNotBlank() }?.let { swimNames[deviceId] = it }
        refreshPairingCandidate(deviceId)
        val peerName = name.ifBlank { deviceId }
        notifyEvent(
            title = localizedContext.getString(R.string.notification_lan_key_changed_title),
            text = localizedContext.getString(R.string.notification_lan_key_changed_body, peerName),
        )
    }

    private fun startSwimLoops() {
        CoLinkLog.i("SWIM", "starting SWIM loops")
        swimJob?.cancel()
        suspectJob?.cancel()
        swimJob = scope.launch {
            while (isActive) {
                delay(SWIM_PERIOD_MILLIS)
                probeNextMembers()
            }
        }
        suspectJob = scope.launch {
            while (isActive) {
                delay(500)
                promoteExpiredSuspects()
            }
        }
    }

    private suspend fun probeNextMembers() {
        val identity = deviceRepository.localDeviceIdentity() ?: return
        val targets = nextProbeTargets(identity.deviceId)
        for (target in targets) {
            probeMember(identity, target)
        }
    }

    private suspend fun probeMember(identity: DeviceIdentity, target: String) {
        val endpoint = swimEndpoints[target] ?: return
        CoLinkLog.d("SWIM", "probing target=${CoLinkLog.shortId(target)} ip=${endpoint.ip} port=${endpoint.port}")
        val ack = lanSwimClient.ping(
            identity = identity,
            ip = endpoint.ip,
            port = endpoint.port,
            incarnation = swimMembership.localIncarnation(identity.deviceId),
            seq = nextSwimSeq(),
            gossip = gossipBatch(identity.deviceId),
        ).onFailure { error ->
            if (error.isExpectedSwimProbeFailure()) {
                CoLinkLog.d("SWIM", "direct probe timed out target=${CoLinkLog.shortId(target)}")
            } else {
                CoLinkLog.w("SWIM", "direct probe failed target=${CoLinkLog.shortId(target)}", error)
            }
        }.getOrNull()
        if (ack != null) {
            processSwimMessage(ack, null)
            if (ack.isTargetAck(target)) {
                return
            }
            CoLinkLog.w(
                "SWIM",
                "direct probe identity mismatch target=${CoLinkLog.shortId(target)} from=${CoLinkLog.shortId(ack.payload.from)}",
            )
        }

        val indirectAck = firstSuccessfulIndirectAck(identity, target)
        if (indirectAck != null) {
            processSwimMessage(indirectAck, null)
        } else {
            val missedProbes = swimMembership.recordProbeMiss(target)
            if (missedProbes < SWIM_SUSPECT_MISSES) {
                CoLinkLog.d(
                    "SWIM",
                    "probe missed; keeping member alive target=${CoLinkLog.shortId(target)} missed=$missedProbes threshold=$SWIM_SUSPECT_MISSES",
                )
                return
            }
            CoLinkLog.w("SWIM", "marking member suspect target=${CoLinkLog.shortId(target)} missed=$missedProbes")
            markMember(identity.deviceId, target, MemberState.Suspect, null, explicit = false)
        }
    }

    private suspend fun firstSuccessfulIndirectAck(
        identity: DeviceIdentity,
        target: String,
    ): SwimEnvelope? = coroutineScope {
        val requests = indirectTargets(identity.deviceId, target)
            .mapNotNull { intermediary ->
                swimEndpoints[intermediary]?.let { endpoint -> intermediary to endpoint }
            }
        if (requests.isEmpty()) {
            return@coroutineScope null
        }

        val results = Channel<Pair<String, Result<SwimEnvelope>>>(Channel.UNLIMITED)
        val jobs = requests.map { (intermediary, endpoint) ->
            launch {
                val result = lanSwimClient.pingReq(
                    identity = identity,
                    ip = endpoint.ip,
                    port = endpoint.port,
                    targetDeviceId = target,
                    incarnation = swimMembership.localIncarnation(identity.deviceId),
                    seq = nextSwimSeq(),
                    gossip = gossipBatch(identity.deviceId),
                )
                results.send(intermediary to result)
            }
        }

        try {
            repeat(jobs.size) {
                val (intermediary, result) = results.receive()
                result.getOrNull()?.let { ack ->
                    if (ack.isTargetAck(target)) {
                        jobs.forEach { it.cancel() }
                        return@coroutineScope ack
                    }
                    CoLinkLog.w(
                        "SWIM",
                        "indirect probe identity mismatch target=${CoLinkLog.shortId(target)} intermediary=${CoLinkLog.shortId(intermediary)} from=${CoLinkLog.shortId(ack.payload.from)}",
                    )
                }
                result.exceptionOrNull()?.let { error ->
                    CoLinkLog.d(
                        "SWIM",
                        "indirect probe failed target=${CoLinkLog.shortId(target)} intermediary=${CoLinkLog.shortId(intermediary)} error=${error.message}",
                    )
                }
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    private fun nextProbeTargets(localDeviceId: String): List<String> {
        val candidates = swimMembership.membersSnapshot()
            .filter { (deviceId, member) ->
                deviceId != localDeviceId &&
                    member.state in setOf(MemberState.Alive, MemberState.Suspect) &&
                    swimEndpoints.containsKey(deviceId)
            }
            .keys
            .sorted()
        if (candidates.isEmpty()) {
            synchronized(swimLock) {
                probeQueue.clear()
                probeRoundCandidates = emptyList()
            }
            return emptyList()
        }
        return synchronized(swimLock) {
            if (probeQueue.isEmpty() || probeRoundCandidates != candidates) {
                probeRoundCandidates = candidates
                probeQueue.clear()
                probeQueue.addAll(candidates.shuffled())
            }
            buildList {
                repeat(SWIM_PROBE_BATCH_SIZE) {
                    if (probeQueue.isNotEmpty()) {
                        add(probeQueue.removeFirst())
                    }
                }
            }
        }
    }

    private fun indirectTargets(localDeviceId: String, targetDeviceId: String): List<String> =
        swimMembership.membersSnapshot()
            .filter { (deviceId, member) ->
                deviceId != localDeviceId &&
                    deviceId != targetDeviceId &&
                    member.state == MemberState.Alive &&
                    swimEndpoints.containsKey(deviceId)
            }
            .keys
            .sorted()
            .take(SWIM_PING_REQ_FANOUT)

    private suspend fun promoteExpiredSuspects() {
        val identity = deviceRepository.localDeviceIdentity() ?: return
        swimMembership
            .promoteExpiredSuspects(identity.deviceId, SWIM_SUSPECT_TIMEOUT_MILLIS)
            .forEach { update ->
                applyMemberUpdate(update)
        }
    }

    private fun gossipBatch(localDeviceId: String): List<SwimGossip> {
        swimMembership.ensureLocalStarted(localDeviceId)
        return swimMembership.gossipBatch()
    }

    private fun nextSwimSeq(): Long =
        synchronized(swimLock) {
            swimSeq += 1
            swimSeq
        }

    private fun broadcastLeft() {
        val targets = swimEndpoints.values.toList()
        if (targets.isEmpty()) {
            return
        }
        scope.launch {
            val identity = deviceRepository.localDeviceIdentity() ?: return@launch
            val entry = swimMembership.leaveSelf(identity.deviceId)
            runCatching {
                targets.forEach { endpoint ->
                    lanSwimClient.ping(
                        identity = identity,
                        ip = endpoint.ip,
                        port = endpoint.port,
                        incarnation = swimMembership.localIncarnation(identity.deviceId),
                        seq = nextSwimSeq(),
                        gossip = listOf(entry),
                    )
                }
            }
        }
    }

    private fun startClipboardSync() {
        stopClipboardSync()
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            scope.launch { broadcastLocalClipboard() }
        }
        clipboardManager.addPrimaryClipChangedListener(listener)
        clipboardListener = listener
    }

    private fun stopClipboardSync() {
        clipboardListener?.let { clipboardManager.removePrimaryClipChangedListener(it) }
        clipboardListener = null
    }

    private suspend fun broadcastLocalClipboard() {
        if (!settingsDataStore.currentSettings().enableClipboardSync) {
            return
        }
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount <= 0) {
            return
        }
        val payload = clipboardPayload(clip) ?: return
        val hash = payload.clipboardHash()
        if (clipboardSuppressedHash == hash) {
            clipboardSuppressedHash = null
            return
        }
        if (clipboardLastSentHash == hash) {
            return
        }
        clipboardLastSentHash = hash
        val identity = deviceRepository.localDeviceIdentity()
        val business = BusinessEnvelope(
            type = CLIPBOARD_SYNC_TYPE,
            payload = json.encodeToJsonElement(payload),
        )
        val cloudSent = sendCloudBroadcast(business).isSuccess
        deviceRepository.devices.first()
            .filter {
                it.deviceId != identity?.deviceId && if (cloudSent) {
                    it.lanAvailable && !it.online
                } else {
                    it.online || it.lanAvailable
                }
            }
            .forEach { device ->
                if (cloudSent) {
                    sendViaLan(device.deviceId, business)
                } else {
                    sendBusinessMessage(
                        targetDeviceId = device.deviceId,
                        business = business,
                    )
                }
            }
    }

    private fun clipboardPayload(clip: ClipData): ClipboardSyncPayload? {
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val contentType = context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(uri.toString().substringAfterLast('.', ""))
            if (contentType == "image/png" || contentType == "image/jpeg") {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CLIPBOARD_MAX_BYTES + 1)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                } ?: return null
                if (bytes.size <= CLIPBOARD_MAX_BYTES) {
                    return ClipboardSyncPayload(
                        contentType = contentType,
                        content = null,
                        data = Base64.getEncoder().encodeToString(bytes),
                    )
                }
            }
        }
        val html = item.htmlText?.takeIf { it.isNotBlank() }
        if (html != null && html.toByteArray().size <= CLIPBOARD_MAX_BYTES) {
            return ClipboardSyncPayload(
                contentType = "text/html",
                content = html,
                data = null,
            )
        }

        val text = item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (text.toByteArray().size > CLIPBOARD_MAX_BYTES) {
            return null
        }
        return ClipboardSyncPayload(
            contentType = "text/plain",
            content = text,
            data = null,
        )
    }

    private suspend fun saveReceivedFileToDownloads(tempFile: File, fileName: String): String? {
        val safeName = fileName.substringAfterLast('/').ifBlank { "file" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            tempFile.delete()
            uri.toString()
        } else {
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            directory.mkdirs()
            val target = uniqueFile(directory, safeName)
            tempFile.copyTo(target, overwrite = false)
            tempFile.delete()
            target.absolutePath
        }
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = File(directory, fileName)
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isBlank()) {
                "$base ($index)"
            } else {
                "$base ($index).$ext"
            }
            candidate = File(directory, nextName)
            index += 1
        }
        return candidate
    }

    private suspend fun notifyEvent(title: String, text: String) {
        notifier.notifyEvent(title, text)
    }

    private fun ClipboardSyncPayload.clipboardHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(contentType.toByteArray())
        content?.let { digest.update(it.toByteArray()) }
        data?.let { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun htmlClipboardPlainText(html: String): String {
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
            .ifBlank { html }
    }

    private fun String.normalizedDeviceType(): String? {
        val value = trim().lowercase()
        return value.takeIf { it in knownDeviceTypes }
    }

    private val knownDeviceTypes = setOf("windows", "macos", "linux", "android", "ios")
}

private data class IncomingTransferState(
    val deviceId: String,
    val expectedChunks: Long,
    var receivedChunks: Long,
    val tempFile: File,
    val verifier: FileChecksumVerifier,
    var route: String = "cloud",
    val windowSize: Long = LAN_SEND_WINDOW_CHUNKS,
    val reorderBuffer: TreeMap<Long, ByteArray> = TreeMap(),
    var finishReceived: Boolean = false,
    val frameMutex: Mutex = Mutex(),
)

private data class OutgoingTransferState(
    val deviceId: String,
    val localUri: String,
    val chunkSize: Int,
    @Volatile var acknowledgedChunks: Long = 0,
    @Volatile var transferConnection: TransferConnection? = null,
)

private data class PendingLanSend(
    val message: BusinessEnvelope,
    val correlationId: String? = null,
    val result: CompletableDeferred<Result<Unit>>,
)

private data class LanEndpoint(
    val ip: String,
    val port: Int,
)

private fun Throwable.isExpectedSwimProbeFailure(): Boolean =
    this is InterruptedIOException || cause?.isExpectedSwimProbeFailure() == true

private fun SwimEnvelope.isTargetAck(targetDeviceId: String): Boolean =
    type == "swim.ack" && payload.from == targetDeviceId
