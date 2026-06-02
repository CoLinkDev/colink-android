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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val scope = CoroutineScope(Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, ClientPeerConnection>()
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
            return
        }
        val request = Request.Builder()
            .url("ws://$ip:$port/peer")
            .build()
        lanOkHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                private var peerId: String? = null
                private var peerName: String? = null
                private var peerPublicKey: String? = null
                private var crypto: LanSessionCrypto? = null
                private var requestProof: HandshakeProofPayload? = null
                private var trustAfterAccept = false

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val proof = handshake.buildProof(identity)
                    requestProof = proof
                    sendPeerMessage(
                        webSocket = webSocket,
                        type = "handshake.v1.request",
                        payload = proof,
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch {
                        runCatching {
                            handleMessage(webSocket, text, identity, deviceId, allowPairing, listener)
                        }.onFailure {
                            webSocket.close(1002, it.message ?: "LAN protocol error")
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    peerId?.let { peers.remove(it) }
                    listener.onDisconnected(peerId ?: deviceId)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
                                val accepted = pairingCoordinator.request(
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
                                require(accepted) { "user cancelled LAN pairing" }
                                trustAfterAccept = true
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
                            if (trustAfterAccept) {
                                lanTrustStore.trust(
                                    deviceId = peerId,
                                    name = requireNotNull(peerName) { "LAN peer proof missing" },
                                    publicKey = requireNotNull(peerPublicKey) {
                                        "LAN peer proof missing"
                                    },
                                )
                                trustAfterAccept = false
                            }
                            peers[peerId] = ClientPeerConnection(webSocket, null)
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
                            peers[peerId] = ClientPeerConnection(webSocket, crypto)
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
            },
        )
    }

    fun send(deviceId: String, message: BusinessEnvelope): Boolean {
        val connection = peers[deviceId] ?: return false
        val crypto = connection.crypto ?: return false
        val payload = crypto.encrypt(message)
        return sendPeerMessage(connection.webSocket, "business.v1.message", payload)
    }

    fun hasPeer(deviceId: String): Boolean =
        peers[deviceId]?.crypto != null

    fun disconnect(deviceId: String) {
        peers.remove(deviceId)?.webSocket?.close(1000, "client closing")
    }

    fun disconnectAll() {
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
