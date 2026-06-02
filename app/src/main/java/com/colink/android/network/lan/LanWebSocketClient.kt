package com.colink.android.network.lan

import com.colink.android.crypto.Handshake
import com.colink.android.crypto.LanSessionCrypto
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.BusinessNegotiatePayload
import com.colink.android.network.message.EncryptedBusinessPayload
import com.colink.android.network.message.HandshakeAcceptPayload
import com.colink.android.network.message.HandshakeProofPayload
import com.colink.android.network.message.HandshakeRejectPayload
import com.colink.android.network.message.PeerEnvelope
import com.colink.android.network.transfer.FileDataFrame
import com.colink.android.util.CoLinkLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Singleton
class LanWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val handshake: Handshake,
    private val lanTrustStore: LanTrustStore,
    private val pairingCoordinator: LanPairingCoordinator,
) {
    private companion object {
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, ClientPeerConnection>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val lanOkHttpClient = okHttpClient
        .newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun connect(
        identity: DeviceIdentity,
        deviceId: String,
        ip: String,
        port: Int,
        allowPairing: Boolean,
        listener: Listener,
    ) {
        if (peers.containsKey(deviceId)) {
            CoLinkLog.d("LAN", "outbound peer already connected device=${CoLinkLog.shortId(deviceId)}")
            return
        }
        if (!connectingPeers.add(deviceId)) {
            CoLinkLog.d("LAN", "outbound peer already connecting device=${CoLinkLog.shortId(deviceId)}")
            return
        }
        CoLinkLog.i(
            "LAN",
            "connecting outbound peer device=${CoLinkLog.shortId(deviceId)} ip=$ip port=$port allowPairing=$allowPairing",
        )
        val request = Request.Builder()
            .url("ws://$ip:$port/peer")
            .build()
        lanOkHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                private val messages = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
                private var peerId: String? = null
                private var peerName: String? = null
                private var peerPublicKey: String? = null
                private var crypto: LanSessionCrypto? = null
                private var requestProof: HandshakeProofPayload? = null
                private var pairingRequestId: String? = null
                private var handshakeTimeoutJob: Job? = null
                private val processor = scope.launch {
                    for ((webSocket, text) in messages) {
                        runCatching {
                            handleMessage(webSocket, text, identity, deviceId, allowPairing, listener)
                        }.onFailure {
                            CoLinkLog.w("LAN", "outbound peer protocol error device=${CoLinkLog.shortId(deviceId)}", it)
                            failPairing(it.message ?: "LAN protocol error")
                            messages.close()
                            webSocket.close(1002, it.message ?: "LAN protocol error")
                        }
                    }
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    CoLinkLog.d("LAN", "outbound websocket opened device=${CoLinkLog.shortId(deviceId)}")
                    handshakeTimeoutJob = scope.launch {
                        delay(HANDSHAKE_TIMEOUT_MILLIS)
                        if (connectingPeers.contains(deviceId)) {
                            CoLinkLog.w("LAN", "outbound handshake timed out device=${CoLinkLog.shortId(deviceId)}")
                            webSocket.close(1002, "LAN handshake timed out")
                        }
                    }
                    val proof = handshake.buildProof(identity)
                    requestProof = proof
                    sendPeerMessage(
                        webSocket = webSocket,
                        type = "handshake.v1.request",
                        payload = proof,
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!messages.trySend(webSocket to text).isSuccess) {
                        webSocket.close(1002, "LAN protocol processor closed")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    CoLinkLog.w(
                        "LAN",
                        "outbound websocket closed device=${CoLinkLog.shortId(peerId ?: deviceId)} code=$code reason=$reason",
                    )
                    failPairing(reason.ifBlank { "LAN connection closed" })
                    messages.close()
                    processor.cancel()
                    handshakeTimeoutJob?.cancel()
                    connectingPeers.remove(peerId ?: deviceId)
                    peerId?.let { peers.remove(it) }
                    listener.onDisconnected(peerId ?: deviceId)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    CoLinkLog.w("LAN", "outbound websocket failed device=${CoLinkLog.shortId(peerId ?: deviceId)}", t)
                    failPairing(t.message ?: "LAN connection failed")
                    messages.close()
                    processor.cancel()
                    handshakeTimeoutJob?.cancel()
                    connectingPeers.remove(peerId ?: deviceId)
                    peerId?.let { peers.remove(it) }
                    listener.onDisconnected(peerId ?: deviceId)
                }

                private suspend fun handleMessage(
                    webSocket: WebSocket,
                    text: String,
                    identity: DeviceIdentity,
                    expectedDeviceId: String,
                    allowPairing: Boolean,
                    listener: Listener,
                ) {
                    val envelope = json.decodeFromString(PeerEnvelope.serializer(), text)
                    when (envelope.type) {
                        "handshake.v1.exchange" -> {
                            val proof = json.decodeFromJsonElement(
                                HandshakeProofPayload.serializer(),
                                envelope.payload,
                            )
                            require(proof.deviceId == expectedDeviceId) {
                                "LAN handshake device mismatch"
                            }
                            require(handshake.verifyProof(proof)) {
                                "signature invalid"
                            }
                            val trust = lanTrustStore.trustState(proof.deviceId, proof.publicKey)
                            CoLinkLog.i(
                                "LAN",
                                "received handshake exchange device=${CoLinkLog.shortId(proof.deviceId)} name=${proof.name} trust=$trust",
                            )
                            if (trust == LanTrustState.KeyChanged) {
                                lanTrustStore.clearLanPairing(proof.deviceId, proof.name, proof.publicKey)
                                sendPeerMessage(
                                    webSocket = webSocket,
                                    type = "handshake.v1.reject",
                                    payload = HandshakeRejectPayload("key_changed"),
                                )
                                listener.onKeyChanged(proof.deviceId, proof.name)
                                webSocket.close(1008, "key_changed")
                                return
                            }
                            if (trust == LanTrustState.Unknown) {
                                require(allowPairing) { "LAN device key is not trusted" }
                                val localProof = requireNotNull(requestProof) { "LAN request proof missing" }
                                val decision = pairingCoordinator.request(
                                    deviceId = proof.deviceId,
                                    name = proof.name,
                                    publicKey = proof.publicKey,
                                    code = handshake.pairingCode(
                                        identity.publicKey,
                                        proof.publicKey,
                                        localProof.nonce,
                                        proof.nonce,
                                    ),
                                    reason = "unknown_device",
                                )
                                pairingRequestId = decision.requestId
                                if (!decision.accepted) {
                                    pairingRequestId = null
                                    error("user cancelled LAN pairing")
                                }
                            }
                            peerId = proof.deviceId
                            peerName = proof.name
                            peerPublicKey = proof.publicKey
                        }

                        "handshake.v1.reject" -> {
                            val rejection = json.decodeFromJsonElement(
                                HandshakeRejectPayload.serializer(),
                                envelope.payload,
                            )
                            if (rejection.reason == "key_changed") {
                                val id = peerId ?: expectedDeviceId
                                val name = id
                                lanTrustStore.get(id)?.let {
                                    lanTrustStore.clearLanPairing(id, it.name, it.publicKey)
                                }
                                listener.onKeyChanged(id, name)
                            }
                            failPairing(rejection.reason)
                            handshakeTimeoutJob?.cancel()
                            connectingPeers.remove(peerId ?: expectedDeviceId)
                            webSocket.close(1008, rejection.reason)
                        }

                        "handshake.v1.accept" -> {
                            val accepted = json.decodeFromJsonElement(
                                HandshakeAcceptPayload.serializer(),
                                envelope.payload,
                            )
                            require(accepted.deviceId == expectedDeviceId) {
                                "LAN handshake confirmation device mismatch"
                            }
                            val peerId = requireNotNull(peerId) { "LAN peer proof missing" }
                            peers[peerId] = ClientPeerConnection(webSocket, null)
                            CoLinkLog.d("LAN", "handshake accepted device=${CoLinkLog.shortId(peerId)}")
                            sendPeerMessage(
                                webSocket = webSocket,
                                type = "business.v1.negotiate",
                                payload = BusinessNegotiatePayload(
                                    supported = LanSessionCrypto.supportedSuites,
                                    preferred = LanSessionCrypto.preferredSuite(),
                                ),
                            )
                        }

                        "business.v1.negotiate" -> {
                            val negotiation = json.decodeFromJsonElement(
                                BusinessNegotiatePayload.serializer(),
                                envelope.payload,
                            )
                            val suite = LanSessionCrypto.chooseSuite(
                                localSupported = LanSessionCrypto.supportedSuites,
                                peerSupported = negotiation.supported,
                                localIsInitiator = true,
                            )
                            require(suite != null) { "no compatible LAN encryption suite" }
                            crypto = LanSessionCrypto.create(
                                json = json,
                                suite = suite,
                                privateKey = identity.privateKey,
                                peerPublicKey = requireNotNull(peerPublicKey) {
                                    "LAN peer proof missing"
                                },
                                localIsInitiator = true,
                            )
                            val peerId = requireNotNull(peerId) { "LAN peer proof missing" }
                            pairingRequestId?.let { requestId ->
                                lanTrustStore.trust(
                                    deviceId = peerId,
                                    name = requireNotNull(peerName) { "LAN peer proof missing" },
                                    publicKey = requireNotNull(peerPublicKey) {
                                        "LAN peer proof missing"
                                    },
                                )
                                CoLinkLog.i("LAN", "trusted LAN peer device=${CoLinkLog.shortId(peerId)} name=$peerName")
                                pairingCoordinator.complete(requestId)
                                pairingRequestId = null
                            }
                            handshakeTimeoutJob?.cancel()
                            connectingPeers.remove(peerId)
                            peers[peerId] = ClientPeerConnection(webSocket, crypto)
                            CoLinkLog.i("LAN", "LAN peer ready device=${CoLinkLog.shortId(peerId)}")
                            listener.onConnected(peerId)
                        }

                        "business.v1.message" -> {
                            val sessionCrypto = requireNotNull(crypto) { "LAN crypto not ready" }
                            val payload = json.decodeFromJsonElement(
                                EncryptedBusinessPayload.serializer(),
                                envelope.payload,
                            )
                            listener.onMessage(
                                fromDeviceId = requireNotNull(peerId),
                                message = sessionCrypto.decrypt(payload),
                            )
                        }
                    }
                }

                private fun failPairing(reason: String) {
                    pairingRequestId?.let { requestId ->
                        pairingCoordinator.fail(requestId, reason)
                        CoLinkLog.w("Pairing", "outbound pairing failed request=${CoLinkLog.shortId(requestId)} reason=$reason")
                        pairingRequestId = null
                    }
                }
            },
        )
    }

    fun send(deviceId: String, message: BusinessEnvelope): Boolean {
        val connection = peers[deviceId] ?: return false
        val crypto = connection.crypto ?: return false
        val payload = crypto.encrypt(message)
        val sent = sendPeerMessage(connection.webSocket, "business.v1.message", payload)
        if (!sent) {
            peers.remove(deviceId, connection)
        }
        return sent
    }

    fun hasPeer(deviceId: String): Boolean =
        peers[deviceId]?.crypto != null

    fun disconnect(deviceId: String) {
        connectingPeers.remove(deviceId)
        peers.remove(deviceId)?.webSocket?.close(1000, "client closing")
    }

    fun disconnectAll() {
        connectingPeers.clear()
        peers.keys.toList().forEach(::disconnect)
    }

    fun connectTransfer(
        sessionId: String,
        token: String,
        ip: String,
        port: Int,
        listener: TransferListener,
    ) {
        val request = Request.Builder()
            .url("ws://$ip:$port/transfer/$sessionId?token=$token")
            .build()
        okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen(TransferConnection(webSocket))
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    FileDataFrame.decode(bytes.toByteArray())?.let(listener::onFrame)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onClosed(t.message ?: "transfer connection failed")
                }
            },
        )
    }

    private inline fun <reified T> sendPeerMessage(
        webSocket: WebSocket,
        type: String,
        payload: T,
    ): Boolean {
        val envelope = PeerEnvelope(
            type = type,
            payload = json.encodeToJsonElement(payload),
        )
        return webSocket.send(json.encodeToString(envelope))
    }

    interface Listener {
        fun onConnected(deviceId: String)

        fun onMessage(fromDeviceId: String, message: BusinessEnvelope)

        fun onDisconnected(deviceId: String)

        fun onKeyChanged(deviceId: String, name: String)
    }

    interface TransferListener {
        fun onOpen(connection: TransferConnection)

        fun onFrame(frame: FileDataFrame)

        fun onClosed(reason: String)
    }
}

class TransferConnection internal constructor(
    private val webSocket: WebSocket,
) {
    fun send(frame: FileDataFrame): Boolean =
        webSocket.send(okio.ByteString.of(*frame.encode()))

    fun close() {
        webSocket.close(1000, "transfer finished")
    }
}

private data class ClientPeerConnection(
    val webSocket: WebSocket,
    val crypto: LanSessionCrypto?,
)
