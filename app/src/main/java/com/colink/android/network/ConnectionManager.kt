package com.colink.android.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.webkit.MimeTypeMap
import com.colink.android.domain.model.CloudConnectionState
import com.colink.android.domain.model.CloudStatus
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.domain.repository.MessageRepository
import com.colink.android.MainActivity
import com.colink.android.R
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.network.cloud.CloudWebSocketClient
import com.colink.android.network.cloud.TicketProvider
import com.colink.android.network.lan.LanWebSocketClient
import com.colink.android.network.lan.LanSwimClient
import com.colink.android.network.lan.LanTrustStore
import com.colink.android.network.lan.LanWebSocketServer
import com.colink.android.network.lan.NsdDiscovery
import com.colink.android.network.lan.TransferConnection
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.CLIPBOARD_SYNC_TYPE
import com.colink.android.network.message.ClipboardSyncPayload
import com.colink.android.network.message.CloudClientEnvelope
import com.colink.android.network.message.CloudServerEnvelope
import com.colink.android.network.message.FILE_ACCEPT_TYPE
import com.colink.android.network.message.FILE_ACK_TYPE
import com.colink.android.network.message.FILE_CANCEL_TYPE
import com.colink.android.network.message.FILE_CHUNK_TYPE
import com.colink.android.network.message.FILE_DONE_TYPE
import com.colink.android.network.message.FILE_OFFER_TYPE
import com.colink.android.network.message.FILE_READY_TYPE
import com.colink.android.network.message.FILE_REJECT_TYPE
import com.colink.android.network.message.FILE_RETRANSMIT_TYPE
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
import com.colink.android.network.transfer.BuiltFileOffer
import com.colink.android.network.transfer.FileDataFrame
import com.colink.android.network.transfer.FileDataFrameKind
import com.colink.android.network.transfer.blake3Checksum
import java.io.File
import java.io.InputStream
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private const val MAX_TEXT_LENGTH = 10_000
private const val CLIPBOARD_MAX_BYTES = 1_048_576
private const val FILE_ACK_INTERVAL_CHUNKS = 7L
private const val LAN_SEND_WINDOW_CHUNKS = 8L
private const val RELAY_SEND_WINDOW_CHUNKS = FILE_ACK_INTERVAL_CHUNKS
private const val SWIM_PERIOD_MILLIS = 1_000L
private const val SWIM_SUSPECT_TIMEOUT_MILLIS = 5_000L
private const val SWIM_MAX_GOSSIP = 10
private const val SWIM_PING_REQ_FANOUT = 2
private const val NOTIFICATION_CHANNEL_ID = "colink_events"
private const val FILE_OFFER_TIMEOUT_MILLIS = 60_000L

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _cloudState = MutableStateFlow(CloudConnectionState())
    private val _lanPairingCandidates = MutableStateFlow<List<LanPairingCandidate>>(emptyList())
    private var connectionJob: Job? = null
    private var swimJob: Job? = null
    private var suspectJob: Job? = null
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransferState>()
    private val outgoingTransfers = ConcurrentHashMap<String, OutgoingTransferState>()
    private val ackSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val swimEndpoints = ConcurrentHashMap<String, LanEndpoint>()
    private val swimNames = ConcurrentHashMap<String, String>()
    private val swimMembers = ConcurrentHashMap<String, MemberRecord>()
    private val gossipQueue = ArrayDeque<SwimGossip>()
    private val swimLock = Any()
    private var swimSeq = 0L
    private var probeCursor = 0
    private val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardSuppressedHash: String? = null
    private var clipboardLastSentHash: String? = null

    val cloudState: StateFlow<CloudConnectionState> = _cloudState.asStateFlow()

    val lanPairingCandidates: StateFlow<List<LanPairingCandidate>> =
        _lanPairingCandidates.asStateFlow()

    fun start() {
        ensureNotificationChannel()
        scope.launch {
            deviceRepository.ensureLocalDeviceIdentity()
            fileTransferRepository.failUnfinished("app restarted")
            if (settingsDataStore.currentSettings().lanDiscovery) {
                startLan()
            }
            if (settingsDataStore.currentSession() != null && connectionJob?.isActive != true) {
                connectionJob = scope.launch { runCloudLoop() }
            } else if (settingsDataStore.currentSession() == null) {
                _cloudState.value = CloudConnectionState()
            }
        }
        startClipboardSync()
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        swimJob?.cancel()
        swimJob = null
        suspectJob?.cancel()
        suspectJob = null
        stopClipboardSync()
        broadcastLeft()
        stopLan()
        incomingTransfers.clear()
        outgoingTransfers.clear()
        ackSignals.values.forEach { it.complete(Unit) }
        ackSignals.clear()
        swimEndpoints.clear()
        swimNames.clear()
        swimMembers.clear()
        _lanPairingCandidates.value = emptyList()
        synchronized(swimLock) {
            gossipQueue.clear()
            probeCursor = 0
        }
        cloudWebSocketClient.close()
        _cloudState.value = CloudConnectionState()
    }

    fun startCloud() {
        scope.launch {
            if (settingsDataStore.currentSession() != null && connectionJob?.isActive != true) {
                connectionJob = scope.launch { runCloudLoop() }
            }
        }
    }

    fun stopCloud() {
        connectionJob?.cancel()
        connectionJob = null
        cloudWebSocketClient.close()
        _cloudState.value = CloudConnectionState()
        scope.launch { deviceRepository.listLocalDevices() }
    }

    fun applySettings(settings: AppSettings) {
        if (settings.lanDiscovery) {
            startLan()
        } else {
            stopLan()
        }
        scope.launch {
            if (settingsDataStore.currentSession() != null && connectionJob?.isActive != true) {
                connectionJob = scope.launch { runCloudLoop() }
            }
        }
    }

    private fun startLan() {
        lanWebSocketServer.start(lanListener)
        startLanDiscovery()
        startSwimLoops()
    }

    private fun stopLan() {
        swimJob?.cancel()
        swimJob = null
        suspectJob?.cancel()
        suspectJob = null
        lanWebSocketClient.disconnectAll()
        lanWebSocketServer.stop()
        nsdDiscovery.stop()
        swimEndpoints.clear()
        swimNames.clear()
        swimMembers.clear()
        _lanPairingCandidates.value = emptyList()
        synchronized(swimLock) {
            gossipQueue.clear()
            probeCursor = 0
        }
        scope.launch { deviceRepository.clearAllLanEndpoints() }
    }

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
            val route = sendViaLan(targetDeviceId, business).getOrElse {
                sendViaCloud(targetDeviceId, business)
                "cloud"
            }

            if (route == "cloud") {
                check(_cloudState.value.connected) { "cloud connection is not ready" }
            }
            messageRepository.saveTextMessage(
                messageId = messageId,
                deviceId = targetDeviceId,
                direction = MessageDirection.Outgoing,
                text = trimmed,
                route = route,
            )
        }

    private suspend fun sendViaLan(targetDeviceId: String, business: BusinessEnvelope): Result<String> =
        runCatching {
            if (lanWebSocketServer.send(targetDeviceId, business)) {
                return@runCatching "lan"
            }
            if (lanWebSocketClient.send(targetDeviceId, business)) {
                return@runCatching "lan"
            }
            val identity = deviceRepository.localDeviceIdentity()
                ?: error("current device is not registered")
            val device = deviceRepository.getDevice(targetDeviceId)
                ?: error("target device not found")
            if (!device.lanAvailable || device.localIp == null || device.localPort == null) {
                error("LAN peer is not available")
            }
            lanWebSocketClient.connect(
                identity = identity,
                deviceId = device.deviceId,
                ip = device.localIp,
                port = device.localPort,
                allowPairing = false,
                listener = lanClientListener,
            )
            error("LAN peer is connecting")
        }

    private fun sendViaCloud(targetDeviceId: String, business: BusinessEnvelope) {
        val envelope = CloudClientEnvelope(
            id = UUID.randomUUID().toString(),
            type = "relay",
            to = targetDeviceId,
            payload = json.encodeToJsonElement(business),
        )

        check(cloudWebSocketClient.send(envelope)) { "cloud connection is not ready" }
    }

    private suspend fun saveInboundBusinessMessage(
        fromDeviceId: String,
        business: BusinessEnvelope,
        route: String,
    ) {
        when (business.type) {
            TEXT_MESSAGE_TYPE -> saveInboundTextMessage(fromDeviceId, business, route)
            CLIPBOARD_SYNC_TYPE -> handleClipboardSync(fromDeviceId, business)
            FILE_OFFER_TYPE -> handleFileOffer(fromDeviceId, business, route)
            FILE_ACCEPT_TYPE -> handleFileAccept(fromDeviceId, business)
            FILE_READY_TYPE -> handleFileReady(fromDeviceId, business)
            FILE_CHUNK_TYPE -> handleFileChunk(fromDeviceId, business, route)
            FILE_ACK_TYPE -> handleFileAck(business)
            FILE_RETRANSMIT_TYPE -> handleFileRetransmit(business, lan = false)
            FILE_REJECT_TYPE -> handleFileReject(business)
            FILE_CANCEL_TYPE -> handleFileCancel(business)
            FILE_DONE_TYPE -> handleFileDone(business)
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
        notifyEvent(
            title = "Message from ${deviceRepository.getDevice(fromDeviceId)?.name ?: fromDeviceId}",
            text = textPayload.text,
        )
    }

    private suspend fun handleClipboardSync(
        fromDeviceId: String,
        business: BusinessEnvelope,
    ) {
        val payload = runCatching {
            json.decodeFromJsonElement(ClipboardSyncPayload.serializer(), business.payload)
        }.getOrNull() ?: return
        val hash = payload.clipboardHash()
        clipboardSuppressedHash = hash
        when (payload.contentType) {
            "text/plain", "text/html" -> {
                val content = payload.content ?: return
                if (content.toByteArray().size > CLIPBOARD_MAX_BYTES) {
                    return
                }
                clipboardManager.setPrimaryClip(ClipData.newPlainText("CoLink", content))
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
        notifyEvent(
            title = "Clipboard synced",
            text = deviceRepository.getDevice(fromDeviceId)?.name ?: fromDeviceId,
        )
    }

    private suspend fun handleFileOffer(
        fromDeviceId: String,
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
        notifyEvent(
            title = "File offer",
            text = "${deviceRepository.getDevice(fromDeviceId)?.name ?: fromDeviceId}: ${payload.fileName}",
        )
        scheduleOfferExpiry(payload.sessionId)
    }

    suspend fun acceptFileOffer(sessionId: String): Result<Unit> =
        runCatching {
            val transfer = fileTransferRepository.get(sessionId) ?: error("transfer not found")
            require(transfer.status == "offered") { "transfer is no longer available" }
            val token = UUID.randomUUID().toString().replace("-", "")
            val tempFile = File.createTempFile(
                "colink-${sessionId}-",
                ".part",
            )
            incomingTransfers[sessionId] = IncomingTransferState(
                deviceId = transfer.deviceId,
                expectedChunks = transfer.totalChunks,
                receivedChunks = 0,
                tempFile = tempFile,
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
            )
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
            sendBusinessMessage(
                targetDeviceId = targetDeviceId,
                business = BusinessEnvelope(
                    type = FILE_OFFER_TYPE,
                    payload = json.encodeToJsonElement(offer.payload),
                ),
            )
        }

    suspend fun rejectFileOffer(sessionId: String, reason: String = "user rejected"): Result<Unit> =
        runCatching {
            val transfer = fileTransferRepository.get(sessionId) ?: error("transfer not found")
            fileTransferRepository.save(
                transfer.copy(
                    status = "rejected",
                    error = reason,
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
                        ),
                    ),
                ),
            )
        }

    fun startLanPairing(deviceId: String) {
        scope.launch {
            val identity = deviceRepository.localDeviceIdentity() ?: return@launch
            val candidate = _lanPairingCandidates.value.firstOrNull { it.deviceId == deviceId }
                ?: return@launch
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

    fun refreshLanPairingCandidate(deviceId: String) {
        scope.launch {
            refreshPairingCandidate(deviceId)
        }
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
                                cancelTransfer(payload.sessionId, "LAN data connection failed: $reason")
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
        )
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
                    cancelTransfer(sessionId, "transfer timed out")
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
                    cancelTransfer(sessionId, "transfer timed out")
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
                )
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
                error = payload.reason,
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
                error = payload.reason,
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
                error = payload.reason,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun sendBusinessMessage(targetDeviceId: String, business: BusinessEnvelope) {
        sendViaLan(targetDeviceId, business).getOrElse {
            sendViaCloud(targetDeviceId, business)
        }
    }

    private suspend fun routeForDevice(deviceId: String): String {
        val device = deviceRepository.getDevice(deviceId)
        return if (device?.lanAvailable == true) "lan" else "cloud"
    }

    private val lanClientListener =
        object : LanWebSocketClient.Listener {
            override fun onConnected(deviceId: String) {
                scope.launch {
                    removePairingCandidate(deviceId)
                    deviceRepository.listLocalDevices()
                }
            }

            override fun onMessage(fromDeviceId: String, message: BusinessEnvelope) {
                scope.launch { saveInboundBusinessMessage(fromDeviceId, message, "lan") }
            }

            override fun onDisconnected(deviceId: String) {
                scope.launch { deviceRepository.clearLanEndpoint(deviceId) }
            }

            override fun onKeyChanged(deviceId: String, name: String) {
                scope.launch { handleLanKeyChanged(deviceId, name) }
            }
        }

    private suspend fun handleTransferFrame(sessionId: String, frame: FileDataFrame) {
        val state = incomingTransfers[sessionId] ?: return
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
        val chunksComplete = state.receivedChunks == state.expectedChunks
        val checksumMatches = chunksComplete && state.tempFile.blake3Checksum() == transfer.checksum
        val success = chunksComplete && checksumMatches
        val reason = when {
            success -> null
            !chunksComplete -> "missing chunks"
            else -> "checksum mismatch"
        }
        val finalUri = if (success) {
            saveReceivedFileToDownloads(state.tempFile, transfer.fileName)
        } else {
            state.tempFile.delete()
            null
        }
        fileTransferRepository.save(
            transfer.copy(
                status = if (success) "completed" else "failed",
                transferredBytes = if (success) transfer.fileSize else transfer.transferredBytes,
                localUri = finalUri ?: transfer.localUri,
                error = reason,
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
                    ),
                ),
            ),
        )
        notifyEvent(
            title = if (success) "File received" else "File transfer failed",
            text = transfer.fileName,
        )
    }

    private val lanListener =
        object : LanWebSocketServer.Listener {
            override fun onConnected(deviceId: String) {
                scope.launch {
                    removePairingCandidate(deviceId)
                    deviceRepository.listLocalDevices()
                }
            }

            override fun onMessage(fromDeviceId: String, message: BusinessEnvelope) {
                scope.launch { saveInboundBusinessMessage(fromDeviceId, message, "lan") }
            }

            override fun onDisconnected(deviceId: String) {
                scope.launch { deviceRepository.clearLanEndpoint(deviceId) }
            }

            override fun onKeyChanged(deviceId: String, name: String) {
                scope.launch { handleLanKeyChanged(deviceId, name) }
            }

            override fun onTransferConnected(sessionId: String) = Unit

            override fun onTransferFrame(sessionId: String, frame: FileDataFrame) {
                scope.launch { handleTransferFrame(sessionId, frame) }
            }

            override fun onTransferClosed(sessionId: String) {
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
                scope.launch { processSwimMessage(message, sourceIp) }
            }

            override fun currentSwimGossip(localDeviceId: String): List<SwimGossip> =
                gossipBatch(localDeviceId)
        }

    private fun startLanDiscovery() {
        scope.launch {
            val identity = deviceRepository.localDeviceIdentity() ?: return@launch
            nsdDiscovery.start(
                serviceName = "colink-${identity.deviceId.take(8)}",
                port = com.colink.android.network.lan.LAN_PORT,
                deviceId = identity.deviceId,
                deviceName = identity.name,
                listener = nsdListener,
            )
        }
    }

    private val nsdListener =
        object : NsdDiscovery.Listener {
            override fun onServiceResolved(deviceId: String, name: String, ip: String, port: Int) {
                scope.launch {
                    val identity = deviceRepository.localDeviceIdentity() ?: return@launch
                    if (deviceId == identity.deviceId) {
                        return@launch
                    }
                    swimEndpoints[deviceId] = LanEndpoint(ip, port)
                    name.takeIf { it.isNotBlank() }?.let { swimNames[deviceId] = it }
                    refreshPairingCandidate(deviceId)
                    val response = lanSwimClient.ping(
                        identity = identity,
                        ip = ip,
                        port = port,
                        seq = nextSwimSeq(),
                        gossip = gossipBatch(identity.deviceId),
                    ).getOrNull() ?: return@launch
                    if (response.type == "swim.ack" && response.payload.from == deviceId) {
                        processSwimMessage(response, ip)
                    }
                }
            }

            override fun onServiceLost(deviceId: String) = Unit
        }

    private suspend fun runCloudLoop() {
        var attempt = 0
        while (scope.isActive) {
            if (settingsDataStore.currentSession() == null) {
                _cloudState.value = CloudConnectionState()
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
                        scope.launch {
                            val session = authRepository.currentSession().getOrNull() ?: return@launch
                            deviceRepository.syncDevices(session)
                        }
                    }

                    override fun onMessage(message: CloudServerEnvelope) {
                        scope.launch { handleCloudMessage(message) }
                    }

                    override fun onClosed(reason: String?) {
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
            attempt += 1
            _cloudState.value =
                CloudConnectionState(CloudStatus.Reconnecting, attempt, reason)
            delay(backoffDelay(attempt))
        }
    }

    private suspend fun handleCloudMessage(message: CloudServerEnvelope) {
        when (message.type) {
            "device.online", "device.offline" -> {
                val session = authRepository.currentSession().getOrNull() ?: return
                deviceRepository.syncDevices(session)
            }
            "relay" -> {
                val from = message.from ?: return
                val payload = message.payload ?: return
                val business = runCatching {
                    json.decodeFromJsonElement(BusinessEnvelope.serializer(), payload)
                }.getOrNull() ?: return
                saveInboundBusinessMessage(from, business, "cloud")
            }
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
        return "$scheme://${uri.rawAuthority}$basePath/ws/v1?ticket=$encodedTicket"
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
            )
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

    suspend fun cancelTransfer(sessionId: String, reason: String = "user cancelled"): Result<Unit> =
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
                    error = reason,
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
                        ),
                    ),
                ),
            )
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
                if (!lanTrustStore.isLanTrusted(message.payload.from)) {
                    deviceRepository.clearLanEndpoint(message.payload.from)
                }
            }
        }
        observeSwimAlive(identity.deviceId, message.payload.from)
        message.payload.gossip.forEach { entry ->
            if (entry.deviceId == identity.deviceId && entry.state == MemberState.Suspect.wireValue) {
                pushGossip(
                    SwimGossip(
                        deviceId = identity.deviceId,
                        state = MemberState.Alive.wireValue,
                        incarnation = System.currentTimeMillis(),
                    ),
                )
            } else {
                mergeSwimMember(identity.deviceId, message.payload.from, entry)
            }
        }
    }

    private suspend fun observeSwimAlive(localDeviceId: String, deviceId: String) {
        if (deviceId == localDeviceId) {
            return
        }
        markMember(localDeviceId, deviceId, MemberState.Alive, incarnation = null, explicit = false)
    }

    private suspend fun mergeSwimMember(
        localDeviceId: String,
        originDeviceId: String,
        entry: SwimGossip,
    ) {
        if (entry.incarnation > System.currentTimeMillis() + 5 * 60 * 1000) {
            return
        }
        val state = MemberState.fromWire(entry.state) ?: return
        if (state == MemberState.Left && originDeviceId != entry.deviceId) {
            return
        }
        markMember(
            localDeviceId = localDeviceId,
            deviceId = entry.deviceId,
            state = state,
            incarnation = entry.incarnation,
            explicit = true,
        )
    }

    private suspend fun markMember(
        localDeviceId: String,
        deviceId: String,
        state: MemberState,
        incarnation: Long?,
        explicit: Boolean,
    ) {
        if (deviceId == localDeviceId) {
            return
        }
        val now = System.currentTimeMillis()
        val nextIncarnation = incarnation ?: swimMembers[deviceId]?.incarnation ?: now
        val existing = swimMembers[deviceId]
        if (!shouldAcceptMemberUpdate(existing, state, nextIncarnation, explicit)) {
            return
        }
        swimMembers[deviceId] = MemberRecord(state, nextIncarnation, now)
        pushGossip(
            SwimGossip(
                deviceId = deviceId,
                state = state.wireValue,
                incarnation = nextIncarnation,
            ),
        )
        when (state) {
            MemberState.Alive -> {
                val endpoint = swimEndpoints[deviceId]
                val lanTrusted = lanTrustStore.isLanTrusted(deviceId)
                if (endpoint != null) {
                    if (lanTrusted) {
                        deviceRepository.markLanEndpoint(deviceId, endpoint.ip, endpoint.port)
                    } else {
                        deviceRepository.clearLanEndpoint(deviceId)
                    }
                    updatePairingCandidate(deviceId, endpoint, state)
                }
                if (!lanTrusted) {
                    return
                }
                if (
                    shouldInitiate(localDeviceId, deviceId) &&
                    !lanWebSocketClient.hasPeer(deviceId) &&
                    !lanWebSocketServer.hasPeer(deviceId)
                ) {
                    val identity = deviceRepository.localDeviceIdentity() ?: return
                    val peer = deviceRepository.getDevice(deviceId) ?: return
                    val ip = peer.localIp ?: endpoint?.ip ?: return
                    val port = peer.localPort ?: endpoint?.port ?: return
                    lanWebSocketClient.connect(
                        identity = identity,
                        deviceId = peer.deviceId,
                        ip = ip,
                        port = port,
                        allowPairing = false,
                        listener = lanClientListener,
                    )
                }
            }

            MemberState.Dead,
            MemberState.Left,
            -> {
                removePairingCandidate(deviceId)
                deviceRepository.clearLanEndpoint(deviceId)
                lanWebSocketClient.disconnect(deviceId)
            }

            MemberState.Suspect -> Unit
        }
    }

    private fun shouldAcceptMemberUpdate(
        existing: MemberRecord?,
        state: MemberState,
        incarnation: Long,
        explicit: Boolean,
    ): Boolean =
        when {
            existing == null -> true
            incarnation < existing.incarnation -> false
            incarnation > existing.incarnation -> true
            explicit -> existing.state !in setOf(MemberState.Dead, MemberState.Left) &&
                state.priority > existing.state.priority

            else -> state != existing.state
        }

    private suspend fun updatePairingCandidate(
        deviceId: String,
        endpoint: LanEndpoint,
        state: MemberState,
    ) {
        if (state != MemberState.Alive || lanTrustStore.isLanTrusted(deviceId)) {
            removePairingCandidate(deviceId)
            return
        }
        val name = swimNames[deviceId]
            ?.takeIf { it.isNotBlank() }
            ?: deviceId
        val candidates = _lanPairingCandidates.value
            .filterNot { it.deviceId == deviceId }
            .plus(
                LanPairingCandidate(
                    deviceId = deviceId,
                    name = name,
                    ip = endpoint.ip,
                    port = endpoint.port,
                    state = state.wireValue,
                ),
            )
            .sortedBy { it.deviceId }
        _lanPairingCandidates.value = candidates
    }

    private fun removePairingCandidate(deviceId: String) {
        _lanPairingCandidates.value = _lanPairingCandidates.value
            .filterNot { it.deviceId == deviceId }
    }

    private suspend fun refreshPairingCandidate(deviceId: String) {
        val state = swimMembers[deviceId]?.state ?: return
        val endpoint = swimEndpoints[deviceId] ?: return
        updatePairingCandidate(deviceId, endpoint, state)
    }

    private suspend fun handleLanKeyChanged(deviceId: String, name: String) {
        deviceRepository.clearLanEndpoint(deviceId)
        lanWebSocketClient.disconnect(deviceId)
        name.takeIf { it.isNotBlank() }?.let { swimNames[deviceId] = it }
        refreshPairingCandidate(deviceId)
        notifyEvent(
            title = "LAN key changed",
            text = "${name.ifBlank { deviceId }} needs LAN pairing again",
        )
    }

    private fun startSwimLoops() {
        swimJob?.cancel()
        suspectJob?.cancel()
        swimJob = scope.launch {
            while (isActive) {
                delay(SWIM_PERIOD_MILLIS)
                probeNextMember()
            }
        }
        suspectJob = scope.launch {
            while (isActive) {
                delay(500)
                promoteExpiredSuspects()
            }
        }
    }

    private suspend fun probeNextMember() {
        val identity = deviceRepository.localDeviceIdentity() ?: return
        val target = nextProbeTarget(identity.deviceId) ?: return
        val endpoint = swimEndpoints[target] ?: return
        val ack = lanSwimClient.ping(
            identity = identity,
            ip = endpoint.ip,
            port = endpoint.port,
            seq = nextSwimSeq(),
            gossip = gossipBatch(identity.deviceId),
        ).getOrNull()
        if (ack != null) {
            processSwimMessage(ack, null)
            markMember(identity.deviceId, target, MemberState.Alive, null, explicit = false)
            return
        }

        val indirectAck = indirectTargets(identity.deviceId, target)
            .firstNotNullOfOrNull { intermediary ->
                val intermediaryEndpoint = swimEndpoints[intermediary] ?: return@firstNotNullOfOrNull null
                lanSwimClient.pingReq(
                    identity = identity,
                    ip = intermediaryEndpoint.ip,
                    port = intermediaryEndpoint.port,
                    targetDeviceId = target,
                    seq = nextSwimSeq(),
                    gossip = gossipBatch(identity.deviceId),
                ).getOrNull()
            }
        if (indirectAck != null) {
            processSwimMessage(indirectAck, null)
            markMember(identity.deviceId, target, MemberState.Alive, null, explicit = false)
        } else {
            markMember(identity.deviceId, target, MemberState.Suspect, null, explicit = false)
        }
    }

    private fun nextProbeTarget(localDeviceId: String): String? {
        val candidates = swimMembers
            .filter { (deviceId, member) ->
                deviceId != localDeviceId &&
                    member.state in setOf(MemberState.Alive, MemberState.Suspect) &&
                    swimEndpoints.containsKey(deviceId)
            }
            .keys
            .sorted()
        if (candidates.isEmpty()) {
            return null
        }
        val target = candidates[probeCursor % candidates.size]
        probeCursor = (probeCursor + 1) % candidates.size
        return target
    }

    private fun indirectTargets(localDeviceId: String, targetDeviceId: String): List<String> =
        swimMembers
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
        val now = System.currentTimeMillis()
        swimMembers
            .filter { (_, member) ->
                member.state == MemberState.Suspect &&
                    now - member.updatedAt >= SWIM_SUSPECT_TIMEOUT_MILLIS
            }
            .keys
            .toList()
            .forEach { deviceId ->
                markMember(identity.deviceId, deviceId, MemberState.Dead, null, explicit = false)
            }
    }

    private fun pushGossip(entry: SwimGossip) {
        synchronized(swimLock) {
            gossipQueue.addLast(entry)
            while (gossipQueue.size > SWIM_MAX_GOSSIP * 4) {
                gossipQueue.removeFirst()
            }
        }
    }

    private fun gossipBatch(localDeviceId: String): List<SwimGossip> {
        val self = SwimGossip(
            deviceId = localDeviceId,
            state = MemberState.Alive.wireValue,
            incarnation = System.currentTimeMillis(),
        )
        synchronized(swimLock) {
            return (listOf(self) + gossipQueue.toList().asReversed()).take(SWIM_MAX_GOSSIP)
        }
    }

    private fun nextSwimSeq(): Long =
        synchronized(swimLock) {
            swimSeq += 1
            swimSeq
        }

    private fun broadcastLeft() {
        val identity = runCatching { kotlinx.coroutines.runBlocking { deviceRepository.localDeviceIdentity() } }
            .getOrNull()
            ?: return
        val entry = SwimGossip(
            deviceId = identity.deviceId,
            state = MemberState.Left.wireValue,
            incarnation = System.currentTimeMillis(),
        )
        pushGossip(entry)
        val targets = swimEndpoints.values.toList()
        runCatching {
            kotlinx.coroutines.runBlocking {
                targets.forEach { endpoint ->
                    lanSwimClient.ping(
                        identity = identity,
                        ip = endpoint.ip,
                        port = endpoint.port,
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
        deviceRepository.devices.first()
            .filter { it.deviceId != identity?.deviceId && (it.online || it.lanAvailable) }
            .forEach { device ->
                sendBusinessMessage(
                    targetDeviceId = device.deviceId,
                    business = BusinessEnvelope(
                        type = CLIPBOARD_SYNC_TYPE,
                        payload = json.encodeToJsonElement(payload),
                    ),
                )
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
        if (!settingsDataStore.currentSettings().notifications) {
            return
        }
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.colink_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(UUID.randomUUID().hashCode(), notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "CoLink events",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    private fun ClipboardSyncPayload.clipboardHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(contentType.toByteArray())
        content?.let { digest.update(it.toByteArray()) }
        data?.let { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private data class IncomingTransferState(
    val deviceId: String,
    val expectedChunks: Long,
    var receivedChunks: Long,
    val tempFile: File,
    var route: String = "cloud",
    val windowSize: Long = LAN_SEND_WINDOW_CHUNKS,
    val reorderBuffer: TreeMap<Long, ByteArray> = TreeMap(),
)

private data class OutgoingTransferState(
    val deviceId: String,
    val localUri: String,
    val chunkSize: Int,
    @Volatile var acknowledgedChunks: Long = 0,
    @Volatile var transferConnection: TransferConnection? = null,
)

private data class LanEndpoint(
    val ip: String,
    val port: Int,
)

private data class MemberRecord(
    val state: MemberState,
    val incarnation: Long,
    val updatedAt: Long,
)

private enum class MemberState(
    val wireValue: String,
    val priority: Int,
) {
    Alive("alive", 0),
    Suspect("suspect", 1),
    Dead("dead", 2),
    Left("left", 3);

    companion object {
        fun fromWire(value: String): MemberState? =
            entries.firstOrNull { it.wireValue == value }
    }
}
