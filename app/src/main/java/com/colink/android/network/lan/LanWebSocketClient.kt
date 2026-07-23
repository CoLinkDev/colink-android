package com.colink.android.network.lan

import com.colink.android.crypto.Handshake
import com.colink.android.crypto.LanSessionCrypto
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.message.AuthChallengePayload
import com.colink.android.network.message.AuthResponsePayload
import com.colink.android.network.message.BUSINESS_PROTOCOL_VERSION
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.BusinessKeyExchangeNoncePayload
import com.colink.android.network.message.BusinessKeyExchangePayload
import com.colink.android.network.message.BusinessNegotiatePayload
import com.colink.android.network.message.BusinessVersionAckPayload
import com.colink.android.network.message.BusinessVersionPayload
import com.colink.android.network.message.EmptyPayload
import com.colink.android.network.message.EncryptedBusinessPayload
import com.colink.android.network.message.LAN_PROTOCOL_VERSION
import com.colink.android.network.message.LanEnvelope
import com.colink.android.network.message.LanRejectPayload
import com.colink.android.network.message.PairingIdentityPayload
import com.colink.android.network.message.ProtocolHelloAckEnvelope
import com.colink.android.network.message.ProtocolHelloEnvelope
import com.colink.android.network.message.ProtocolHelloPayload
import com.colink.android.network.message.VersionAckPayload
import com.colink.android.network.message.VersionCompatibility
import com.colink.android.network.message.checkBusinessProtocolVersion
import com.colink.android.network.message.checkLanProtocolVersion
import com.colink.android.network.message.negotiatedLanProtocolVersion
import com.colink.android.network.message.supportsLanKeyExchange
import com.colink.android.network.message.supportsLanKeyExchangeNonce
import com.colink.android.network.transfer.FileDataFrame
import com.colink.android.network.camera.CameraDataFrame
import com.colink.android.util.CoLinkLog
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.serialization.json.buildJsonObject
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
        const val PAIRING_TIMEOUT_MILLIS = 60_000L
        const val HEARTBEAT_INTERVAL_MILLIS = 15_000L
        const val KEEPALIVE_TIMEOUT_MILLIS = 45_000L
        const val KEY_EXCHANGE_TIMESTAMP_WINDOW_MILLIS = 30_000L
        const val REASON_AUTH_KEY_CHANGED = "colink:auth.key_changed.v1"
        const val REASON_PAIRING_USER_REJECTED = "colink:pairing.user_rejected.v1"
        const val REASON_KEY_EXCHANGE_SIGNATURE_INVALID = "colink:key_exchange.signature_invalid.v1"
        const val REASON_KEY_EXCHANGE_TIMESTAMP_EXPIRED = "colink:key_exchange.timestamp_expired.v1"
        const val REASON_KEY_EXCHANGE_GENERIC = "colink:key_exchange.generic.v1"
        const val MESSAGE_AUTH_KEY_CHANGED = "Peer public key differs from stored trust record"
        const val MESSAGE_PAIRING_USER_REJECTED = "User declined the pairing request"
        const val MESSAGE_KEY_EXCHANGE_SIGNATURE_INVALID = "Ephemeral key signature verification failed"
        const val MESSAGE_KEY_EXCHANGE_TIMESTAMP_EXPIRED = "Ephemeral key timestamp expired"
        const val MESSAGE_KEY_EXCHANGE_GENERIC = "Ephemeral key exchange failed"
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val peers = ConcurrentHashMap<String, ClientPeerConnection>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val connectingWebSockets = ConcurrentHashMap<String, WebSocket>()
    private val cameraConnections = ConcurrentHashMap<String, WebSocket>()

    fun connect(
        identity: DeviceIdentity,
        deviceId: String,
        ip: String,
        port: Int,
        allowPairing: Boolean,
        listener: Listener,
    ) {
        if (peers.containsKey(deviceId) || !connectingPeers.add(deviceId)) {
            return
        }
        val request = Request.Builder().url("ws://$ip:$port/peer").build()
        okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                private val messages = Channel<Pair<WebSocket, String>>(Channel.UNLIMITED)
                private val state = ClientPeerState(identity, expectedDeviceId = deviceId, allowPairing = allowPairing, initiator = true)
                private var connected = false
                private var failureReported = false
                private var timeoutJob: Job? = null
                private val processor = scope.launch {
                    for ((webSocket, text) in messages) {
                        runCatching {
                            handleMessage(webSocket, text, state, listener)
                        }.onFailure {
                            CoLinkLog.w("LAN", "outbound LAN protocol handler failed device=${CoLinkLog.shortId(deviceId)}", it)
                            reportConnectionFailed(deviceId, it.message ?: "LAN protocol error", listener)
                        }
                    }
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connectingWebSockets[deviceId] = webSocket
                    timeoutJob = scope.launch {
                        delay(HANDSHAKE_TIMEOUT_MILLIS)
                        if (connectingPeers.contains(deviceId)) {
                            reportConnectionFailed(deviceId, "LAN handshake timed out", listener)
                            webSocket.close(1000, "LAN handshake timed out")
                        }
                    }
                    sendHello(webSocket, identity)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages.trySend(webSocket to text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    cleanup(reason.ifBlank { "LAN connection closed" }, listener)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    cleanup(t.message ?: "LAN connection failed", listener)
                }

                private fun cleanup(reason: String, listener: Listener) {
                    state.pairingRequestId?.let { pairingCoordinator.fail(it, reason) }
                    messages.close()
                    processor.cancel()
                    timeoutJob?.cancel()
                    connectingPeers.remove(deviceId)
                    connectingWebSockets.remove(deviceId)
                    state.peerId?.let { peers.remove(it)?.keepaliveJob?.cancel() }
                    if (!connected) {
                        reportConnectionFailed(deviceId, reason, listener)
                    }
                    listener.onDisconnected(state.peerId ?: deviceId)
                }

                private fun reportConnectionFailed(deviceId: String, reason: String, listener: Listener) {
                    if (connected || failureReported) {
                        return
                    }
                    failureReported = true
                    listener.onConnectionFailed(deviceId, reason)
                }

                private suspend fun handleMessage(
                    webSocket: WebSocket,
                    text: String,
                    state: ClientPeerState,
                    listener: Listener,
                ) {
                    if (!state.helloAckReceived) {
                        val ack = runCatching {
                            json.decodeFromString(ProtocolHelloAckEnvelope.serializer(), text)
                        }.getOrNull()
                        if (ack?.type == "protocol.hello-ack") {
                            if (!ack.payload.compatible) {
                                reportConnectionFailed(state.expectedDeviceId, ack.payload.message ?: ack.payload.reason ?: "LAN protocol version incompatible", listener)
                                return
                            }
                            state.markHelloAckReceived()
                            if (state.helloReceived) {
                                startAuthOrPairing(webSocket, state, listener)
                            }
                            return
                        }
                    }

                    if (!state.helloReceived) {
                        val hello = runCatching {
                            json.decodeFromString(ProtocolHelloEnvelope.serializer(), text)
                        }.getOrNull()
                        if (hello?.type != "protocol.hello") {
                            return
                        }
                        if (hello.payload.deviceId != state.expectedDeviceId) {
                            reportConnectionFailed(state.expectedDeviceId, "LAN hello device mismatch", listener)
                            return
                        }
                        val compatibility = checkLanProtocolVersion(hello.payload.protocolVersion)
                        sendHelloAck(webSocket, compatibility)
                        state.markHelloAckSent()
                        if (!compatibility.compatible) {
                            reportConnectionFailed(state.expectedDeviceId, compatibility.message ?: compatibility.reason ?: "LAN protocol version incompatible", listener)
                            return
                        }
                        state.receiveHello(hello.payload.deviceId, hello.payload.protocolVersion)
                        if (state.helloAckReceived) {
                            startAuthOrPairing(webSocket, state, listener)
                        }
                        return
                    }

                    if (!state.helloAckReceived) {
                        return
                    }

                    val envelope = runCatching {
                        json.decodeFromString(LanEnvelope.serializer(), text)
                    }.getOrNull() ?: return
                    if (envelope.to != state.identity.deviceId || envelope.from != state.expectedDeviceId) {
                        return
                    }
                    when (envelope.type) {
                        "heartbeat.v1.ping" -> {
                            peers[envelope.from]?.touchApplicationActivity()
                            sendLanMessage(webSocket, state.identity, state.expectedDeviceId, "heartbeat.v1.pong", EmptyPayload, envelope.id, sequence = peers[envelope.from]?.sequence ?: state.sequence)
                        }
                        "heartbeat.v1.pong" -> {
                            if (peers[envelope.from]?.consumeHeartbeat(envelope.correlationId) == true) {
                                peers[envelope.from]?.touchApplicationActivity()
                            }
                        }
                        "auth.v1.challenge" -> handleAuthChallenge(webSocket, state, envelope)
                        "auth.v1.response" -> handleAuthResponse(webSocket, state, envelope, listener)
                        "auth.v1.verified" -> state.markPeerVerified()
                        "auth.v1.reject" -> {
                            val rejection = runCatching {
                                json.decodeFromJsonElement(LanRejectPayload.serializer(), envelope.payload)
                            }.getOrNull() ?: return
                            if (rejection.reason == REASON_AUTH_KEY_CHANGED) {
                                abortAuthForKeyChange(state, listener)
                            } else if (state.allowPairing) {
                                startPairing(webSocket, state)
                            } else {
                                reportConnectionFailed(state.expectedDeviceId, rejection.message.ifBlank { rejection.reason }, listener)
                            }
                        }
                        "pairing.v1.exchange" -> handlePairingExchange(webSocket, state, envelope)
                        "pairing.v1.confirm" -> handlePairingConfirm(webSocket, state, envelope)
                        "pairing.v1.reject" -> {
                            val rejection = runCatching {
                                json.decodeFromJsonElement(LanRejectPayload.serializer(), envelope.payload)
                            }.getOrNull() ?: return
                            val message = rejection.message.ifBlank { rejection.reason }
                            state.pairingRequestId?.let { pairingCoordinator.fail(it, message) }
                            reportConnectionFailed(state.expectedDeviceId, message, listener)
                        }
                        "business.v1.version" -> handleBusinessVersion(webSocket, state, envelope)
                        "business.v1.version-ack" -> handleBusinessVersionAck(state, envelope, listener)
                        "business.v1.key-exchange-nonce" -> handleBusinessKeyExchangeNonce(webSocket, state, envelope)
                        "business.v1.key-exchange" -> handleBusinessKeyExchange(webSocket, state, envelope, listener)
                        "business.v1.key-exchange-reject" -> {
                            val rejection = runCatching {
                                json.decodeFromJsonElement(LanRejectPayload.serializer(), envelope.payload)
                            }.getOrNull() ?: return
                            state.rejectKeyExchange()
                            reportConnectionFailed(state.expectedDeviceId, rejection.message.ifBlank { rejection.reason }, listener)
                        }
                        "business.v1.negotiate" -> handleBusinessNegotiate(webSocket, state, envelope, listener)
                        "business.v1.message" -> {
                            peers[envelope.from]?.touchApplicationActivity()
                            handleBusinessMessage(state, envelope, listener)
                        }
                        else -> peers[envelope.from]?.touchApplicationActivity()
                    }
                    if (!state.authAborted) {
                        maybeSecurityReady(webSocket, state)
                    }
                }

                private suspend fun startAuthOrPairing(webSocket: WebSocket, state: ClientPeerState, listener: Listener) {
                    val record = lanTrustStore.get(state.expectedDeviceId)
                    if (record?.let { it.trustedByLan || it.trustedByCloud } == true) {
                        state.prepareAuthentication(
                            record.publicKey,
                            record.name,
                            UUID.randomUUID().toString().replace("-", ""),
                        )
                        sendLanMessage(webSocket, state.identity, state.expectedDeviceId, "auth.v1.challenge", AuthChallengePayload(state.localNonce!!), sequence = state.sequence)
                    } else if (state.allowPairing) {
                        startPairing(webSocket, state)
                    } else {
                        reportConnectionFailed(state.expectedDeviceId, "LAN device key is not trusted", listener)
                    }
                }

                private fun handleAuthChallenge(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope) {
                    val challenge = runCatching {
                        json.decodeFromJsonElement(AuthChallengePayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    if (state.localNonce == null) {
                        val nonce = state.ensureLocalNonce { UUID.randomUUID().toString().replace("-", "") }
                        sendLanMessage(webSocket, state.identity, state.expectedDeviceId, "auth.v1.challenge", AuthChallengePayload(nonce), sequence = state.sequence)
                    }
                    val timestamp = System.currentTimeMillis()
                    val signature = handshake.signAuth(state.identity.privateKey, state.identity.deviceId, timestamp, challenge.nonce)
                    sendLanMessageWithTimestamp(
                        webSocket = webSocket,
                        identity = state.identity,
                        to = state.expectedDeviceId,
                        type = "auth.v1.response",
                        timestamp = timestamp,
                        payload = AuthResponsePayload(signature),
                        correlationId = envelope.id,
                        sequence = state.sequence,
                    )
                    state.markAuthResponseSent()
                }

                private suspend fun handleAuthResponse(
                    webSocket: WebSocket,
                    state: ClientPeerState,
                    envelope: LanEnvelope,
                    listener: Listener,
                ) {
                    val nonce = state.localNonce ?: return
                    val publicKey = state.peerPublicKey ?: return
                    val response = runCatching {
                        json.decodeFromJsonElement(AuthResponsePayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    val valid = handshake.verifyAuth(publicKey, envelope.from, envelope.timestamp, nonce, response.signature)
                    if (valid) {
                        sendLanMessage(webSocket, state.identity, state.expectedDeviceId, "auth.v1.verified", EmptyPayload, envelope.id, sequence = state.sequence)
                        state.markLocalVerified()
                    } else {
                        sendLanMessage(
                            webSocket,
                            state.identity,
                            state.expectedDeviceId,
                            "auth.v1.reject",
                            LanRejectPayload(REASON_AUTH_KEY_CHANGED, MESSAGE_AUTH_KEY_CHANGED),
                            envelope.id,
                            sequence = state.sequence,
                        )
                        abortAuthForKeyChange(state, listener)
                    }
                }

                private suspend fun abortAuthForKeyChange(state: ClientPeerState, listener: Listener) {
                    if (state.authAborted) {
                        return
                    }
                    state.abortAuthentication()
                    lanTrustStore.clearLanPairing(state.expectedDeviceId)
                    listener.onKeyChanged(state.expectedDeviceId, state.peerName ?: state.expectedDeviceId)
                    reportConnectionFailed(state.expectedDeviceId, REASON_AUTH_KEY_CHANGED, listener)
                }

                private fun maybeSecurityReady(webSocket: WebSocket, state: ClientPeerState) {
                    if (state.crypto == null && state.isSecurityReady()) {
                        sendBusinessVersion(webSocket, state)
                    }
                    if (state.crypto == null && state.businessVersionReady && state.requiresKeyExchange) {
                        if (state.requiresKeyExchangeNonce) {
                            sendBusinessKeyExchangeNonce(webSocket, state)
                        } else {
                            sendBusinessKeyExchange(webSocket, state)
                        }
                    }
                    if (state.crypto == null && state.negotiationReady) {
                        sendBusinessNegotiate(webSocket, state)
                    }
                }

                private fun startPairing(webSocket: WebSocket, state: ClientPeerState) {
                    state.startPairing(UUID.randomUUID().toString().replace("-", ""))
                    sendLanMessage(
                        webSocket,
                        state.identity,
                        state.expectedDeviceId,
                        "pairing.v1.request",
                        PairingIdentityPayload(state.identity.publicKey, state.identity.name, state.localNonce!!),
                        sequence = state.sequence,
                    )
                }

                private suspend fun handlePairingExchange(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope) {
                    val payload = runCatching {
                        json.decodeFromJsonElement(PairingIdentityPayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    state.receivePairingPeer(payload.publicKey, payload.name, payload.nonce)
                    val decision = pairingCoordinator.request(
                        deviceId = state.expectedDeviceId,
                        name = payload.name,
                        publicKey = payload.publicKey,
                        code = handshake.pairingCode(state.identity.publicKey, payload.publicKey, state.localNonce.orEmpty(), payload.nonce),
                        reason = "unknown_device",
                    )
                    state.setPairingRequest(decision.requestId)
                    if (!decision.accepted) {
                        state.setPairingRequest(null)
                        sendLanMessage(webSocket, state.identity, state.expectedDeviceId, "pairing.v1.reject", LanRejectPayload(REASON_PAIRING_USER_REJECTED, MESSAGE_PAIRING_USER_REJECTED), envelope.id, sequence = state.sequence)
                    }
                }

                private suspend fun handlePairingConfirm(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope) {
                    val publicKey = state.peerPublicKey ?: return
                    val name = state.peerName ?: state.expectedDeviceId
                    lanTrustStore.trust(state.expectedDeviceId, name, publicKey)
                    state.pairingRequestId?.let { pairingCoordinator.complete(it) }
                    state.setPairingRequest(null)
                    sendLanMessage(webSocket, state.identity, state.expectedDeviceId, "pairing.v1.complete", EmptyPayload, envelope.id, sequence = state.sequence)
                    state.completePairing()
                    sendBusinessVersion(webSocket, state)
                }

                private fun sendBusinessNegotiate(webSocket: WebSocket, state: ClientPeerState) {
                    if (state.sentBusinessNegotiate || !state.negotiationReady) {
                        return
                    }
                    state.markBusinessNegotiateSent()
                    sendLanMessage(
                        webSocket,
                        state.identity,
                        state.expectedDeviceId,
                        "business.v1.negotiate",
                        BusinessNegotiatePayload(LanSessionCrypto.supportedSuites, LanSessionCrypto.preferredSuite()),
                        sequence = state.sequence,
                    )
                }

                private fun sendBusinessKeyExchange(webSocket: WebSocket, state: ClientPeerState) {
                    if (state.sentKeyExchange || state.keyExchangeRejected || (state.requiresKeyExchangeNonce && !state.keyExchangeNonceReady)) {
                        return
                    }
                    val ephemeral = state.localEphemeralKeyPair ?: LanSessionCrypto.generateEphemeralKeyPair().also {
                        state.setLocalEphemeralKeyPair(it)
                    }
                    val timestamp = System.currentTimeMillis()
                    val signature = if (state.requiresKeyExchangeNonce) {
                        handshake.signKeyExchangeV2(
                            privateKey = state.identity.privateKey,
                            from = state.identity.deviceId,
                            to = state.expectedDeviceId,
                            ephemeralPublicKey = ephemeral.publicKey,
                            localNonce = state.localKeyExchangeNonce ?: return,
                            peerNonce = state.peerKeyExchangeNonce ?: return,
                        )
                    } else {
                        handshake.signKeyExchange(
                            privateKey = state.identity.privateKey,
                            from = state.identity.deviceId,
                            to = state.expectedDeviceId,
                            ephemeralPublicKey = ephemeral.publicKey,
                            timestamp = timestamp,
                        )
                    }
                    state.markKeyExchangeSent()
                    sendLanMessageWithTimestamp(
                        webSocket = webSocket,
                        identity = state.identity,
                        to = state.expectedDeviceId,
                        type = "business.v1.key-exchange",
                        timestamp = timestamp,
                        payload = BusinessKeyExchangePayload(ephemeral.publicKey, signature),
                        sequence = state.sequence,
                    )
                }

                private fun sendBusinessKeyExchangeNonce(webSocket: WebSocket, state: ClientPeerState) {
                    if (state.sentKeyExchangeNonce || state.keyExchangeRejected) {
                        return
                    }
                    val nonce = state.ensureLocalKeyExchangeNonce()
                    state.markKeyExchangeNonceSent()
                    sendLanMessage(
                        webSocket,
                        state.identity,
                        state.expectedDeviceId,
                        "business.v1.key-exchange-nonce",
                        BusinessKeyExchangeNoncePayload(nonce),
                        sequence = state.sequence,
                    )
                }

                private fun handleBusinessVersion(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope) {
                    val payload = runCatching {
                        json.decodeFromJsonElement(BusinessVersionPayload.serializer(), envelope.payload)
                    }.getOrNull()
                    val compatibility = payload
                        ?.let { checkBusinessProtocolVersion(it.businessVersion) }
                        ?: checkBusinessProtocolVersion("")
                    sendLanMessage(
                        webSocket,
                        state.identity,
                        state.expectedDeviceId,
                        "business.v1.version-ack",
                        BusinessVersionAckPayload(compatibility.compatible, compatibility.reason, compatibility.message),
                        envelope.id,
                        sequence = state.sequence,
                    )
                    if (compatibility.compatible) {
                        state.receiveBusinessVersion(payload?.businessVersion)
                        sendBusinessVersion(webSocket, state)
                    } else {
                        state.rejectBusinessVersion()
                    }
                }

                private fun handleBusinessVersionAck(state: ClientPeerState, envelope: LanEnvelope, listener: Listener) {
                    val ack = runCatching {
                        json.decodeFromJsonElement(BusinessVersionAckPayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    if (ack.compatible) {
                        state.acknowledgeBusinessVersion()
                    } else {
                        state.rejectBusinessVersion()
                        reportConnectionFailed(state.expectedDeviceId, ack.message ?: ack.reason ?: "business protocol version incompatible", listener)
                    }
                }

                private fun handleBusinessKeyExchange(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope, listener: Listener) {
                    if (!state.requiresKeyExchange || !state.businessVersionReady) {
                        return
                    }
                    if (state.requiresKeyExchangeNonce && !state.keyExchangeNonceReady) {
                        return
                    }
                    val publicKey = state.peerPublicKey ?: return
                    val payload = runCatching {
                        json.decodeFromJsonElement(BusinessKeyExchangePayload.serializer(), envelope.payload)
                    }.getOrNull() ?: run {
                        rejectKeyExchange(webSocket, state, envelope.id, REASON_KEY_EXCHANGE_GENERIC, MESSAGE_KEY_EXCHANGE_GENERIC)
                        reportConnectionFailed(state.expectedDeviceId, MESSAGE_KEY_EXCHANGE_GENERIC, listener)
                        return
                    }
                    if (!state.requiresKeyExchangeNonce && kotlin.math.abs(System.currentTimeMillis() - envelope.timestamp) > KEY_EXCHANGE_TIMESTAMP_WINDOW_MILLIS) {
                        rejectKeyExchange(webSocket, state, envelope.id, REASON_KEY_EXCHANGE_TIMESTAMP_EXPIRED, MESSAGE_KEY_EXCHANGE_TIMESTAMP_EXPIRED)
                        reportConnectionFailed(state.expectedDeviceId, MESSAGE_KEY_EXCHANGE_TIMESTAMP_EXPIRED, listener)
                        return
                    }
                    val valid = if (state.requiresKeyExchangeNonce) {
                        handshake.verifyKeyExchangeV2(
                            publicKey = publicKey,
                            from = envelope.from,
                            to = envelope.to,
                            ephemeralPublicKey = payload.ephemeralPublicKey,
                            localNonce = state.peerKeyExchangeNonce ?: return,
                            peerNonce = state.localKeyExchangeNonce ?: return,
                            signature = payload.signature,
                        )
                    } else {
                        handshake.verifyKeyExchange(
                            publicKey = publicKey,
                            from = envelope.from,
                            to = envelope.to,
                            ephemeralPublicKey = payload.ephemeralPublicKey,
                            timestamp = envelope.timestamp,
                            signature = payload.signature,
                        )
                    }
                    if (!valid) {
                        rejectKeyExchange(webSocket, state, envelope.id, REASON_KEY_EXCHANGE_SIGNATURE_INVALID, MESSAGE_KEY_EXCHANGE_SIGNATURE_INVALID)
                        reportConnectionFailed(state.expectedDeviceId, MESSAGE_KEY_EXCHANGE_SIGNATURE_INVALID, listener)
                        return
                    }
                    state.receivePeerEphemeralKey(payload.ephemeralPublicKey)
                    if (!state.sentKeyExchange) {
                        sendBusinessKeyExchange(webSocket, state)
                    }
                }

                private fun handleBusinessKeyExchangeNonce(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope) {
                    if (!state.requiresKeyExchangeNonce) {
                        return
                    }
                    val payload = runCatching {
                        json.decodeFromJsonElement(BusinessKeyExchangeNoncePayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    state.receivePeerKeyExchangeNonce(payload.nonce)
                    if (!state.businessVersionReady) {
                        return
                    }
                    if (!state.sentKeyExchangeNonce) {
                        sendBusinessKeyExchangeNonce(webSocket, state)
                    }
                    if (state.keyExchangeNonceReady) {
                        sendBusinessKeyExchange(webSocket, state)
                    }
                }

                private fun rejectKeyExchange(webSocket: WebSocket, state: ClientPeerState, correlationId: String, reason: String, message: String) {
                    state.rejectKeyExchange()
                    sendLanMessage(
                        webSocket,
                        state.identity,
                        state.expectedDeviceId,
                        "business.v1.key-exchange-reject",
                        LanRejectPayload(reason, message),
                        correlationId,
                        sequence = state.sequence,
                    )
                }

                private fun sendBusinessVersion(webSocket: WebSocket, state: ClientPeerState) {
                    if (state.sentBusinessVersion || state.businessRejected) {
                        return
                    }
                    state.markBusinessVersionSent()
                    sendLanMessage(
                        webSocket,
                        state.identity,
                        state.expectedDeviceId,
                        "business.v1.version",
                        BusinessVersionPayload(BUSINESS_PROTOCOL_VERSION),
                        sequence = state.sequence,
                    )
                }

                private fun handleBusinessNegotiate(webSocket: WebSocket, state: ClientPeerState, envelope: LanEnvelope, listener: Listener) {
                    if (state.crypto != null) {
                        return
                    }
                    if (!state.negotiationReady) {
                        return
                    }
                    val negotiation = runCatching {
                        json.decodeFromJsonElement(BusinessNegotiatePayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    val suite = LanSessionCrypto.chooseSuite(LanSessionCrypto.supportedSuites, negotiation.supported, true)
                    if (suite == null) {
                        reportConnectionFailed(state.expectedDeviceId, "no compatible LAN encryption suite", listener)
                        return
                    }
                    if (!state.sentBusinessNegotiate) {
                        sendBusinessNegotiate(webSocket, state)
                    }
                    val publicKey = state.peerPublicKey ?: return
                    val crypto = if (state.requiresKeyExchange) {
                        val localEphemeral = state.localEphemeralKeyPair ?: return
                        val peerEphemeral = state.peerEphemeralPublicKey ?: return
                        LanSessionCrypto.createWithEphemeralKeys(
                            json = json,
                            suite = suite,
                            localEphemeralPrivateKey = localEphemeral.privateKeyBytes,
                            localEphemeralPublicKey = localEphemeral.publicKey,
                            peerEphemeralPublicKey = peerEphemeral,
                            localDeviceId = state.identity.deviceId,
                            peerDeviceId = state.expectedDeviceId,
                            protocolVersion = state.negotiatedProtocolVersion,
                            localIsInitiator = true,
                        )
                    } else {
                        LanSessionCrypto.create(json, suite, state.identity.privateKey, publicKey, true)
                    }
                    state.establishCrypto(crypto)
                    val peerId = state.expectedDeviceId
                    connectingPeers.remove(peerId)
                    connectingWebSockets.remove(peerId)
                    val connection = ClientPeerConnection(webSocket, state.crypto, state.identity, state.peerBusinessVersion, state.sequence)
                    peers[peerId] = connection
                    connection.keepaliveJob = launchKeepaliveMonitor(peerId, webSocket)
                    connected = true
                    timeoutJob?.cancel()
                    CoLinkLog.i("LAN", "LAN peer ready device=${CoLinkLog.shortId(peerId)}")
                    listener.onConnected(peerId)
                }

                private fun handleBusinessMessage(state: ClientPeerState, envelope: LanEnvelope, listener: Listener) {
                    val crypto = state.crypto ?: return
                    val payload = runCatching {
                        json.decodeFromJsonElement(EncryptedBusinessPayload.serializer(), envelope.payload)
                    }.getOrNull() ?: return
                    val message = runCatching { crypto.decrypt(payload) }.getOrNull() ?: return
                    listener.onMessage(envelope.from, envelope.id, envelope.correlationId, message)
                }
            },
        )
    }

    fun send(
        deviceId: String,
        message: BusinessEnvelope,
        correlationId: String? = null,
        envelopeId: String? = null,
    ): Boolean {
        val connection = peers[deviceId] ?: return false
        val crypto = connection.crypto ?: return false
        val payload = crypto.encrypt(message)
        val identity = connection.identity ?: return false
        val sent = if (envelopeId == null) {
            sendLanMessage(
                connection.webSocket,
                identity,
                deviceId,
                "business.v1.message",
                payload,
                correlationId,
                sequence = connection.sequence,
            )
        } else {
            sendLanMessageWithId(
                connection.webSocket,
                identity,
                deviceId,
                "business.v1.message",
                payload,
                envelopeId,
                correlationId,
                sequence = connection.sequence,
            )
        }
        if (!sent && peers.remove(deviceId, connection)) {
            connection.keepaliveJob?.cancel()
        }
        return sent
    }

    fun hasPeer(deviceId: String): Boolean = peers[deviceId]?.crypto != null

    fun queuedBytes(deviceId: String): Long = peers[deviceId]?.webSocket?.queueSize() ?: 0L

    fun peerBusinessVersion(deviceId: String): String? = peers[deviceId]?.businessVersion

    fun disconnect(deviceId: String) {
        connectingPeers.remove(deviceId)
        connectingWebSockets.remove(deviceId)?.close(1000, "client closing")
        peers.remove(deviceId)?.let { connection ->
            connection.keepaliveJob?.cancel()
            connection.webSocket.close(1000, "client closing")
        }
    }

    fun disconnectAll() {
        connectingPeers.clear()
        connectingWebSockets.keys.toList().forEach { connectingWebSockets.remove(it)?.close(1000, "client closing") }
        peers.keys.toList().forEach(::disconnect)
        cameraConnections.keys.toList().forEach(::disconnectCamera)
    }

    fun connectTransfer(sessionId: String, token: String, ip: String, port: Int, listener: TransferListener) {
        val request = Request.Builder().url("ws://$ip:$port/transfer/$sessionId?token=$token").build()
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

    fun connectCamera(sessionId: String, token: String, ip: String, port: Int, listener: CameraListener) {
        val request = Request.Builder().url("ws://$ip:$port/camera-stream/$sessionId?token=$token").build()
        okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    cameraConnections[sessionId] = webSocket
                    CoLinkLog.i(
                        "CameraLAN",
                        "viewer data stream opened session=${CoLinkLog.shortId(sessionId)} endpoint=$ip:$port",
                    )
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    CameraDataFrame.decode(bytes.toByteArray())?.let(listener::onFrame)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    cameraConnections.remove(sessionId, webSocket)
                    CoLinkLog.i(
                        "CameraLAN",
                        "viewer data stream closed session=${CoLinkLog.shortId(sessionId)} code=$code reason=$reason",
                    )
                    listener.onClosed(reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    cameraConnections.remove(sessionId, webSocket)
                    CoLinkLog.w(
                        "CameraLAN",
                        "viewer data stream failed session=${CoLinkLog.shortId(sessionId)} status=${response?.code}",
                        t,
                    )
                    listener.onClosed(t.message ?: "camera connection failed")
                }
            },
        )
    }

    fun disconnectCamera(sessionId: String) {
        cameraConnections.remove(sessionId)?.close(1000, "camera session closed")
    }

    private fun sendHello(webSocket: WebSocket, identity: DeviceIdentity): Boolean =
        webSocket.send(
            json.encodeToString(
                ProtocolHelloEnvelope(
                    type = "protocol.hello",
                    payload = ProtocolHelloPayload(
                        deviceId = identity.deviceId,
                        protocolVersion = LAN_PROTOCOL_VERSION,
                        extensions = buildJsonObject {},
                    ),
                ),
            ),
        )

    private fun sendHelloAck(webSocket: WebSocket, compatibility: VersionCompatibility): Boolean =
        webSocket.send(
            json.encodeToString(
                ProtocolHelloAckEnvelope(
                    type = "protocol.hello-ack",
                    payload = VersionAckPayload(compatibility.compatible, compatibility.reason, compatibility.message),
                ),
            ),
        )

    private inline fun <reified T> sendLanMessage(
        webSocket: WebSocket,
        identity: DeviceIdentity,
        to: String,
        type: String,
        payload: T,
        correlationId: String? = null,
        sequence: LanSequence? = null,
    ): Boolean =
        sendLanMessageWithTimestamp(webSocket, identity, to, type, System.currentTimeMillis(), payload, correlationId, sequence)

    private inline fun <reified T> sendLanMessageWithTimestamp(
        webSocket: WebSocket,
        identity: DeviceIdentity,
        to: String,
        type: String,
        timestamp: Long,
        payload: T,
        correlationId: String? = null,
        sequence: LanSequence? = null,
    ): Boolean {
        val envelope = LanEnvelope(
            id = UUID.randomUUID().toString(),
            type = type,
            from = identity.deviceId,
            to = to,
            seq = sequence?.next() ?: 1,
            timestamp = timestamp,
            correlationId = correlationId,
            payload = json.encodeToJsonElement(payload),
        )
        return webSocket.send(json.encodeToString(envelope))
    }

    private inline fun <reified T> sendLanMessageWithId(
        webSocket: WebSocket,
        identity: DeviceIdentity,
        to: String,
        type: String,
        payload: T,
        id: String,
        correlationId: String? = null,
        sequence: LanSequence? = null,
    ): Boolean {
        val envelope = LanEnvelope(
            id = id,
            type = type,
            from = identity.deviceId,
            to = to,
            seq = sequence?.next() ?: 1,
            timestamp = System.currentTimeMillis(),
            correlationId = correlationId,
            payload = json.encodeToJsonElement(payload),
        )
        return webSocket.send(json.encodeToString(envelope))
    }

    private fun launchKeepaliveMonitor(deviceId: String, webSocket: WebSocket): Job =
        scope.launch {
            while (true) {
                val connection = peers[deviceId] ?: return@launch
                val inactiveMillis = System.currentTimeMillis() - connection.lastApplicationActivityMillis
                if (inactiveMillis >= KEEPALIVE_TIMEOUT_MILLIS) {
                    if (peers.remove(deviceId, connection)) {
                        webSocket.close(1000, "LAN keepalive timeout")
                    }
                    return@launch
                }
                val pingId = UUID.randomUUID().toString()
                connection.rememberHeartbeat(pingId)
                if (!sendLanMessageWithId(webSocket, connection.identity ?: return@launch, deviceId, "heartbeat.v1.ping", EmptyPayload, pingId, sequence = connection.sequence)) {
                    if (peers.remove(deviceId, connection)) {
                        webSocket.close(1000, "LAN keepalive send failed")
                    }
                    return@launch
                }
                delay(HEARTBEAT_INTERVAL_MILLIS)
            }
        }

    interface Listener {
        fun onConnected(deviceId: String)
        fun onMessage(
            fromDeviceId: String,
            envelopeId: String,
            correlationId: String?,
            message: BusinessEnvelope,
        )
        fun onConnectionFailed(deviceId: String, reason: String)
        fun onDisconnected(deviceId: String)
        fun onKeyChanged(deviceId: String, name: String)
    }

    interface TransferListener {
        fun onOpen(connection: TransferConnection)
        fun onFrame(frame: FileDataFrame)
        fun onClosed(reason: String)
    }

    interface CameraListener {
        fun onOpen()
        fun onFrame(frame: CameraDataFrame)
        fun onClosed(reason: String)
    }
}

class TransferConnection internal constructor(
    private val webSocket: WebSocket,
) {
    fun send(frame: FileDataFrame): Boolean = webSocket.send(okio.ByteString.of(*frame.encode()))
    fun close() {
        webSocket.close(1000, "transfer finished")
    }
}

private class ClientPeerState(
    val identity: DeviceIdentity,
    val expectedDeviceId: String,
    val allowPairing: Boolean,
    val initiator: Boolean,
    val sequence: LanSequence = LanSequence(),
) {
    var helloReceived = false
        private set
    var helloAckSent = false
        private set
    var helloAckReceived = false
        private set
    var peerId: String? = null
        private set
    var peerProtocolVersion: String? = null
        private set
    var peerName: String? = null
        private set
    var peerPublicKey: String? = null
        private set
    var localNonce: String? = null
        private set
    var peerNonce: String? = null
        private set
    var sentAuthResponse = false
        private set
    var localVerified = false
        private set
    var peerVerified = false
        private set
    var sentBusinessNegotiate = false
        private set
    var sentBusinessVersion = false
        private set
    var peerBusinessVersion: String? = null
        private set
    var peerBusinessVersionReceived = false
        private set
    var businessVersionAckReceived = false
        private set
    var businessRejected = false
        private set
    var sentKeyExchange = false
        private set
    var sentKeyExchangeNonce = false
        private set
    var localKeyExchangeNonce: String? = null
        private set
    var peerKeyExchangeNonce: String? = null
        private set
    var peerEphemeralPublicKey: String? = null
        private set
    var keyExchangeRejected = false
        private set
    var localEphemeralKeyPair: com.colink.android.crypto.LanEphemeralKeyPair? = null
        private set
    var crypto: LanSessionCrypto? = null
        private set
    var authAborted = false
        private set
    var pairingRequestId: String? = null
        private set
    var pairingComplete = false
        private set

    fun receiveHello(deviceId: String, protocolVersion: String) {
        peerId = deviceId
        peerProtocolVersion = protocolVersion
        helloReceived = true
    }
    fun markHelloAckSent() { helloAckSent = true }
    fun markHelloAckReceived() { helloAckReceived = true }
    fun prepareAuthentication(publicKey: String, name: String, nonce: String) {
        peerPublicKey = publicKey
        peerName = name
        localNonce = nonce
    }
    fun ensureLocalNonce(create: () -> String): String = localNonce ?: create().also { localNonce = it }
    fun markAuthResponseSent() { sentAuthResponse = true }
    fun markLocalVerified() { localVerified = true }
    fun markPeerVerified() { peerVerified = true }
    fun abortAuthentication() { authAborted = true }
    fun startPairing(nonce: String) { localNonce = nonce }
    fun receivePairingPeer(publicKey: String, name: String, nonce: String) {
        peerPublicKey = publicKey
        peerName = name
        peerNonce = nonce
    }
    fun setPairingRequest(requestId: String?) { pairingRequestId = requestId }
    fun completePairing() {
        pairingRequestId = null
        pairingComplete = true
    }
    fun markBusinessNegotiateSent() { sentBusinessNegotiate = true }
    fun setLocalEphemeralKeyPair(value: com.colink.android.crypto.LanEphemeralKeyPair) { localEphemeralKeyPair = value }
    fun ensureLocalKeyExchangeNonce(): String = localKeyExchangeNonce ?: randomKeyExchangeNonce().also { localKeyExchangeNonce = it }
    fun markKeyExchangeNonceSent() { sentKeyExchangeNonce = true }
    fun receivePeerKeyExchangeNonce(value: String) { peerKeyExchangeNonce = value }
    fun markKeyExchangeSent() { sentKeyExchange = true }
    fun receiveBusinessVersion(version: String?) {
        peerBusinessVersion = version
        peerBusinessVersionReceived = true
    }
    fun acknowledgeBusinessVersion() { businessVersionAckReceived = true }
    fun rejectBusinessVersion() { businessRejected = true }
    fun receivePeerEphemeralKey(value: String) { peerEphemeralPublicKey = value }
    fun rejectKeyExchange() { keyExchangeRejected = true }
    fun markBusinessVersionSent() { sentBusinessVersion = true }
    fun establishCrypto(value: LanSessionCrypto) { crypto = value }
    val businessVersionReady: Boolean
        get() = peerBusinessVersionReceived && businessVersionAckReceived && !businessRejected

    val requiresKeyExchange: Boolean
        get() = peerProtocolVersion?.let(::supportsLanKeyExchange) == true

    val requiresKeyExchangeNonce: Boolean
        get() = peerProtocolVersion?.let(::supportsLanKeyExchangeNonce) == true

    val keyExchangeNonceReady: Boolean
        get() = !requiresKeyExchangeNonce || (sentKeyExchangeNonce && peerKeyExchangeNonce != null)

    val negotiatedProtocolVersion: String
        get() = negotiatedLanProtocolVersion(peerProtocolVersion ?: LAN_PROTOCOL_VERSION)

    val negotiationReady: Boolean
        get() = businessVersionReady && (!requiresKeyExchange || (keyExchangeNonceReady && sentKeyExchange && peerEphemeralPublicKey != null && !keyExchangeRejected))

    fun isSecurityReady(): Boolean =
        pairingComplete || (localVerified && peerVerified && sentAuthResponse)
}

private fun randomKeyExchangeNonce(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}

private data class ClientPeerConnection(
    val webSocket: WebSocket,
    val crypto: LanSessionCrypto?,
    val identity: DeviceIdentity? = null,
    val businessVersion: String? = null,
    val sequence: LanSequence = LanSequence(),
) {
    @Volatile
    var lastApplicationActivityMillis: Long = System.currentTimeMillis()
        private set

    @Volatile
    var keepaliveJob: Job? = null

    private val pendingHeartbeats = ConcurrentHashMap.newKeySet<String>()

    fun touchApplicationActivity() {
        lastApplicationActivityMillis = System.currentTimeMillis()
    }

    fun rememberHeartbeat(id: String) {
        pendingHeartbeats.add(id)
    }

    fun consumeHeartbeat(correlationId: String?): Boolean =
        correlationId != null && pendingHeartbeats.remove(correlationId)
}

internal class LanSequence {
    private var next = 1L

    @Synchronized
    fun next(): Long {
        val current = next
        next += 1
        return current
    }
}
