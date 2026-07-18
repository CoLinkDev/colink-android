package com.colink.android.network.lan

import com.colink.android.crypto.Handshake
import com.colink.android.crypto.LanSessionCrypto
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.repository.DeviceRepository
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
import com.colink.android.network.message.SwimEnvelope
import com.colink.android.network.message.SwimGossip
import com.colink.android.network.message.SwimPayload
import com.colink.android.network.message.VersionAckPayload
import com.colink.android.network.message.checkBusinessProtocolVersion
import com.colink.android.network.message.checkLanProtocolVersion
import com.colink.android.network.message.negotiatedLanProtocolVersion
import com.colink.android.network.message.supportsLanKeyExchange
import com.colink.android.network.message.supportsLanKeyExchangeNonce
import com.colink.android.network.transfer.FileDataFrame
import com.colink.android.util.CoLinkLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.InterruptedIOException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

const val LAN_PORT = 27_777
private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
private const val PAIRING_TIMEOUT_MILLIS = 60_000L
private const val HEARTBEAT_INTERVAL_MILLIS = 15_000L
private const val KEEPALIVE_TIMEOUT_MILLIS = 45_000L
private const val KEY_EXCHANGE_TIMESTAMP_WINDOW_MILLIS = 30_000L
private const val SWIM_MAX_BODY_BYTES = 16 * 1024
private const val REASON_AUTH_UNKNOWN_DEVICE = "colink:auth.unknown_device.v1"
private const val REASON_AUTH_KEY_CHANGED = "colink:auth.key_changed.v1"
private const val REASON_PAIRING_USER_REJECTED = "colink:pairing.user_rejected.v1"
private const val REASON_KEY_EXCHANGE_SIGNATURE_INVALID = "colink:key_exchange.signature_invalid.v1"
private const val REASON_KEY_EXCHANGE_TIMESTAMP_EXPIRED = "colink:key_exchange.timestamp_expired.v1"
private const val REASON_KEY_EXCHANGE_GENERIC = "colink:key_exchange.generic.v1"
private const val MESSAGE_AUTH_UNKNOWN_DEVICE = "No trust record for this device"
private const val MESSAGE_AUTH_KEY_CHANGED = "Peer public key differs from stored trust record"
private const val MESSAGE_PAIRING_USER_REJECTED = "User declined the pairing request"
private const val MESSAGE_KEY_EXCHANGE_SIGNATURE_INVALID = "Ephemeral key signature verification failed"
private const val MESSAGE_KEY_EXCHANGE_TIMESTAMP_EXPIRED = "Ephemeral key timestamp expired"
private const val MESSAGE_KEY_EXCHANGE_GENERIC = "Ephemeral key exchange failed"

@Singleton
class LanWebSocketServer @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val deviceRepository: DeviceRepository,
    private val json: Json,
    private val handshake: Handshake,
    private val lanSwimClient: LanSwimClient,
    private val lanTrustStore: LanTrustStore,
    private val pairingCoordinator: LanPairingCoordinator,
) {
    private val peers = ConcurrentHashMap<String, ServerPeerConnection>()
    private val transferTokens = ConcurrentHashMap<String, String>()
    private val transferConnections = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    private var engine: ApplicationEngine? = null
    private var listener: Listener? = null

    fun start(listener: Listener) {
        if (engine != null) {
            this.listener = listener
            return
        }
        this.listener = listener
        engine = embeddedServer(CIO, host = "0.0.0.0", port = LAN_PORT) {
            install(WebSockets)
            routing {
                post("/peer/swim/v1") { call.handleSwimPing() }
                webSocket("/peer") { handlePeer(this) }
                webSocket("/transfer/{sessionId}") { handleTransfer(this) }
            }
        }.start(wait = false)
        CoLinkLog.i("LAN", "LAN server started port=$LAN_PORT")
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        engine = null
        peers.clear()
        transferTokens.clear()
        transferConnections.clear()
        CoLinkLog.i("LAN", "LAN server stopped")
    }

    suspend fun send(
        deviceId: String,
        message: BusinessEnvelope,
        correlationId: String? = null,
        envelopeId: String? = null,
    ): Boolean {
        val connection = peers[deviceId] ?: return false
        val payload = connection.crypto.encrypt(message)
        val sent = runCatching {
            if (envelopeId == null) {
                connection.session.sendLanMessage(
                    connection.identity,
                    deviceId,
                    "business.v1.message",
                    payload,
                    correlationId,
                    connection.sequence,
                )
            } else {
                connection.session.sendLanMessageWithId(
                    connection.identity,
                    deviceId,
                    "business.v1.message",
                    payload,
                    envelopeId,
                    correlationId,
                    connection.sequence,
                )
            }
            true
        }.getOrDefault(false)
        if (!sent) {
            peers.remove(deviceId, connection)
        }
        return sent
    }

    fun hasPeer(deviceId: String): Boolean = peers.containsKey(deviceId)

    fun isRunning(): Boolean = engine != null

    fun peerBusinessVersion(deviceId: String): String? = peers[deviceId]?.businessVersion

    suspend fun disconnect(deviceId: String) {
        peers.remove(deviceId)?.let { runCatching { it.session.close() } }
    }

    fun registerTransferToken(sessionId: String, token: String) {
        transferTokens[sessionId] = token
    }

    suspend fun unregisterTransfer(sessionId: String) {
        transferTokens.remove(sessionId)
        transferConnections.remove(sessionId)?.let { runCatching { it.close() } }
    }

    suspend fun sendTransferFrame(sessionId: String, frame: FileDataFrame): Boolean {
        val session = transferConnections[sessionId] ?: return false
        return runCatching {
            session.send(Frame.Binary(fin = true, data = frame.encode()))
            true
        }.getOrDefault(false)
    }

    private suspend fun handlePeer(session: DefaultWebSocketServerSession) {
        val identity = settingsDataStore.currentDeviceIdentity() ?: return session.close()
        val state = ServerPeerState(identity = identity, expectedDeviceId = null, allowPairing = true, initiator = false)
        var connectedPeerId: String? = null
        var keepaliveJob: Job? = null
        try {
            sendHello(session, identity)
            val ready = runPeerHandshake(session, state)
            if (ready == null) {
                session.close()
                return
            }
            connectedPeerId = ready.peerId
            peers.remove(ready.peerId)?.let { runCatching { it.session.close() } }
            val connection = ServerPeerConnection(session, ready.crypto, identity, ready.businessVersion, state.sequence)
            peers[ready.peerId] = connection
            markPeerEndpoint(ready.peerId, session)
            listener?.onConnected(ready.peerId)
            keepaliveJob = launchKeepaliveMonitor(ready.peerId, connection)
            while (true) {
                val remainingMillis = KEEPALIVE_TIMEOUT_MILLIS - (System.currentTimeMillis() - connection.lastApplicationActivityMillis)
                if (remainingMillis <= 0) {
                    session.close()
                    break
                }
                val frame = withTimeoutOrNull(remainingMillis) {
                    session.incoming.receiveCatching().getOrNull()
                } ?: continue
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val envelope = runCatching { json.decodeFromString(LanEnvelope.serializer(), text) }.getOrNull() ?: continue
                if (envelope.from != ready.peerId || envelope.to != identity.deviceId) {
                    continue
                }
                if (envelope.type == "heartbeat.v1.ping") {
                    connection.touchApplicationActivity()
                    session.sendLanMessage(identity, ready.peerId, "heartbeat.v1.pong", EmptyPayload, envelope.id, connection.sequence)
                    continue
                }
                if (envelope.type == "heartbeat.v1.pong") {
                    if (connection.consumeHeartbeat(envelope.correlationId)) {
                        connection.touchApplicationActivity()
                    }
                    continue
                }
                if (envelope.type != "business.v1.message") {
                    connection.touchApplicationActivity()
                    continue
                }
                connection.touchApplicationActivity()
                val payload = runCatching {
                    json.decodeFromJsonElement(EncryptedBusinessPayload.serializer(), envelope.payload)
                }.getOrNull() ?: continue
                val message = runCatching { ready.crypto.decrypt(payload) }.getOrNull() ?: continue
                listener?.onMessage(ready.peerId, envelope.id, envelope.correlationId, message)
            }
        } finally {
            keepaliveJob?.cancel()
            connectedPeerId?.let {
                peers.remove(it)
                listener?.onDisconnected(it)
            }
        }
    }

    private suspend fun runPeerHandshake(session: DefaultWebSocketServerSession, state: ServerPeerState): ReadyPeer? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < PAIRING_TIMEOUT_MILLIS) {
            val text = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) { session.readTextMessage() } ?: return null
            if (!state.helloAckReceived) {
                val ack = runCatching { json.decodeFromString(ProtocolHelloAckEnvelope.serializer(), text) }.getOrNull()
                if (ack?.type == "protocol.hello-ack") {
                    if (!ack.payload.compatible) {
                        state.rejectProtocol()
                    } else {
                        state.markHelloAckReceived()
                    }
                    continue
                }
            }

            if (!state.helloReceived) {
                val hello = runCatching { json.decodeFromString(ProtocolHelloEnvelope.serializer(), text) }.getOrNull()
                if (hello?.type != "protocol.hello") {
                    continue
                }
                state.receiveHello(hello.payload.deviceId, hello.payload.protocolVersion)
                val compatibility = checkLanProtocolVersion(hello.payload.protocolVersion)
                sendHelloAck(session, VersionAckPayload(compatibility.compatible, compatibility.reason, compatibility.message))
                state.markHelloAckSent()
                if (!compatibility.compatible) {
                    state.rejectProtocol()
                    continue
                }
                val record = lanTrustStore.get(hello.payload.deviceId)
                if (record?.let { it.trustedByLan || it.trustedByCloud } == true) {
                    state.prepareAuthentication(record.publicKey, record.name)
                }
                continue
            }

            if (!state.helloAckReceived) {
                if (!state.helloAckSent || state.protocolRejected) {
                    continue
                }
            }

            val envelope = runCatching { json.decodeFromString(LanEnvelope.serializer(), text) }.getOrNull() ?: continue
            val peerId = state.peerId ?: continue
            if (envelope.from != peerId || envelope.to != state.identity.deviceId) {
                continue
            }
            when (envelope.type) {
                "auth.v1.challenge" -> handleAuthChallenge(session, state, envelope)
                "auth.v1.response" -> handleAuthResponse(session, state, envelope)
                "auth.v1.verified" -> state.markPeerVerified()
                "auth.v1.reject" -> {
                    val rejection = runCatching {
                        json.decodeFromJsonElement(LanRejectPayload.serializer(), envelope.payload)
                    }.getOrNull() ?: continue
                    if (rejection.reason == REASON_AUTH_KEY_CHANGED) {
                        abortAuthForKeyChange(state)
                    }
                }
                "pairing.v1.request" -> handlePairingRequest(session, state, envelope)
                "pairing.v1.complete" -> handlePairingComplete(state)
                "pairing.v1.reject" -> {
                    val rejection = runCatching {
                        json.decodeFromJsonElement(LanRejectPayload.serializer(), envelope.payload)
                    }.getOrNull() ?: continue
                    state.pairingRequestId?.let { pairingCoordinator.fail(it, rejection.message.ifBlank { rejection.reason }) }
                    continue
                }
                "business.v1.version" -> handleBusinessVersion(session, state, envelope)
                "business.v1.version-ack" -> handleBusinessVersionAck(state, envelope)
                "business.v1.key-exchange-nonce" -> handleBusinessKeyExchangeNonce(session, state, envelope)
                "business.v1.key-exchange" -> handleBusinessKeyExchange(session, state, envelope)
                "business.v1.key-exchange-reject" -> {
                    state.rejectKeyExchange()
                    continue
                }
                "business.v1.negotiate" -> {
                    if (state.authAborted || !state.negotiationReady) {
                        continue
                    }
                    val crypto = handleBusinessNegotiate(session, state, envelope)
                    if (crypto != null) {
                        return ReadyPeer(peerId, crypto, state.peerBusinessVersion)
                    }
                }
            }
            if (!state.authAborted && state.isSecurityReady()) {
                sendBusinessVersion(session, state)
                if (state.businessVersionReady && state.requiresKeyExchange) {
                    if (state.requiresKeyExchangeNonce) {
                        sendBusinessKeyExchangeNonce(session, state)
                    } else {
                        sendBusinessKeyExchange(session, state)
                    }
                }
                if (state.negotiationReady) {
                    sendBusinessNegotiate(session, state)
                }
            }
        }
        return null
    }

    private suspend fun handleAuthChallenge(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope) {
        val peerId = state.peerId ?: return
        val publicKey = state.peerPublicKey
        if (publicKey == null) {
            session.sendLanMessage(state.identity, peerId, "auth.v1.reject", LanRejectPayload(REASON_AUTH_UNKNOWN_DEVICE, MESSAGE_AUTH_UNKNOWN_DEVICE), envelope.id, state.sequence)
            return
        }
        val challenge = runCatching {
            json.decodeFromJsonElement(AuthChallengePayload.serializer(), envelope.payload)
        }.getOrNull() ?: return
        if (state.localNonce == null) {
            state.ensureLocalNonce { UUID.randomUUID().toString().replace("-", "") }
            session.sendLanMessage(state.identity, peerId, "auth.v1.challenge", AuthChallengePayload(state.localNonce!!), sequence = state.sequence)
        }
        val timestamp = System.currentTimeMillis()
        val signature = handshake.signAuth(state.identity.privateKey, state.identity.deviceId, timestamp, challenge.nonce)
        session.sendLanMessageWithTimestamp(state.identity, peerId, "auth.v1.response", timestamp, AuthResponsePayload(signature), envelope.id, state.sequence)
        state.markAuthResponseSent()
    }

    private suspend fun handleAuthResponse(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope) {
        val peerId = state.peerId ?: return
        val nonce = state.localNonce ?: return
        val publicKey = state.peerPublicKey ?: return
        val response = runCatching {
            json.decodeFromJsonElement(AuthResponsePayload.serializer(), envelope.payload)
        }.getOrNull() ?: return
        val valid = handshake.verifyAuth(publicKey, envelope.from, envelope.timestamp, nonce, response.signature)
        if (valid) {
            state.markLocalVerified()
            session.sendLanMessage(state.identity, peerId, "auth.v1.verified", EmptyPayload, envelope.id, state.sequence)
        } else {
            session.sendLanMessage(state.identity, peerId, "auth.v1.reject", LanRejectPayload(REASON_AUTH_KEY_CHANGED, MESSAGE_AUTH_KEY_CHANGED), envelope.id, state.sequence)
            abortAuthForKeyChange(state)
        }
    }

    private suspend fun abortAuthForKeyChange(state: ServerPeerState) {
        if (state.authAborted) {
            return
        }
        val peerId = state.peerId ?: return
        state.abortAuthentication()
        lanTrustStore.clearLanPairing(peerId)
        listener?.onKeyChanged(peerId, state.peerName ?: peerId)
    }

    private suspend fun handlePairingRequest(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope) {
        val peerId = state.peerId ?: return
        val request = runCatching {
            json.decodeFromJsonElement(PairingIdentityPayload.serializer(), envelope.payload)
        }.getOrNull() ?: return
        state.receivePairingPeer(
            request.publicKey,
            request.name,
            request.nonce,
            UUID.randomUUID().toString().replace("-", ""),
        )
        session.sendLanMessage(
            state.identity,
            peerId,
            "pairing.v1.exchange",
            PairingIdentityPayload(state.identity.publicKey, state.identity.name, state.localNonce!!),
            envelope.id,
            state.sequence,
        )
        val decision = pairingCoordinator.request(
            deviceId = peerId,
            name = request.name,
            publicKey = request.publicKey,
            code = handshake.pairingCode(request.publicKey, state.identity.publicKey, request.nonce, state.localNonce!!),
            reason = "unknown_device",
        )
        state.setPairingRequest(decision.requestId)
        if (decision.accepted) {
            session.sendLanMessage(state.identity, peerId, "pairing.v1.confirm", EmptyPayload, envelope.id, state.sequence)
        } else {
            state.setPairingRequest(null)
            session.sendLanMessage(state.identity, peerId, "pairing.v1.reject", LanRejectPayload(REASON_PAIRING_USER_REJECTED, MESSAGE_PAIRING_USER_REJECTED), envelope.id, state.sequence)
        }
    }

    private suspend fun handlePairingComplete(state: ServerPeerState) {
        val peerId = state.peerId ?: return
        val publicKey = state.peerPublicKey ?: return
        val name = state.peerName ?: peerId
        lanTrustStore.trust(peerId, name, publicKey)
        state.pairingRequestId?.let { pairingCoordinator.complete(it) }
        state.completePairing()
    }

    private suspend fun sendBusinessNegotiate(session: DefaultWebSocketServerSession, state: ServerPeerState) {
        val peerId = state.peerId ?: return
        if (state.sentBusinessNegotiate || !state.negotiationReady) {
            return
        }
        state.markBusinessNegotiateSent()
        session.sendLanMessage(
            state.identity,
            peerId,
            "business.v1.negotiate",
            BusinessNegotiatePayload(LanSessionCrypto.supportedSuites, LanSessionCrypto.preferredSuite()),
            sequence = state.sequence,
        )
    }

    private suspend fun sendBusinessKeyExchange(session: DefaultWebSocketServerSession, state: ServerPeerState) {
        val peerId = state.peerId ?: return
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
                to = peerId,
                ephemeralPublicKey = ephemeral.publicKey,
                localNonce = state.localKeyExchangeNonce ?: return,
                peerNonce = state.peerKeyExchangeNonce ?: return,
            )
        } else {
            handshake.signKeyExchange(
                privateKey = state.identity.privateKey,
                from = state.identity.deviceId,
                to = peerId,
                ephemeralPublicKey = ephemeral.publicKey,
                timestamp = timestamp,
            )
        }
        state.markKeyExchangeSent()
        session.sendLanMessageWithTimestamp(
            state.identity,
            peerId,
            "business.v1.key-exchange",
            timestamp,
            BusinessKeyExchangePayload(ephemeral.publicKey, signature),
            sequence = state.sequence,
        )
    }

    private suspend fun sendBusinessKeyExchangeNonce(session: DefaultWebSocketServerSession, state: ServerPeerState) {
        val peerId = state.peerId ?: return
        if (state.sentKeyExchangeNonce || state.keyExchangeRejected) {
            return
        }
        val nonce = state.ensureLocalKeyExchangeNonce()
        state.markKeyExchangeNonceSent()
        session.sendLanMessage(
            state.identity,
            peerId,
            "business.v1.key-exchange-nonce",
            BusinessKeyExchangeNoncePayload(nonce),
            sequence = state.sequence,
        )
    }

    private suspend fun handleBusinessVersion(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope) {
        val peerId = state.peerId ?: return
        val payload = runCatching {
            json.decodeFromJsonElement(BusinessVersionPayload.serializer(), envelope.payload)
        }.getOrNull()
        val compatibility = payload
            ?.let { checkBusinessProtocolVersion(it.businessVersion) }
            ?: checkBusinessProtocolVersion("")
        session.sendLanMessage(
            state.identity,
            peerId,
            "business.v1.version-ack",
            BusinessVersionAckPayload(compatibility.compatible, compatibility.reason, compatibility.message),
            envelope.id,
            state.sequence,
        )
        if (compatibility.compatible) {
            state.receiveBusinessVersion(payload?.businessVersion)
            sendBusinessVersion(session, state)
        } else {
            state.rejectBusinessVersion()
        }
    }

    private fun handleBusinessVersionAck(state: ServerPeerState, envelope: LanEnvelope) {
        val ack = runCatching {
            json.decodeFromJsonElement(BusinessVersionAckPayload.serializer(), envelope.payload)
        }.getOrNull() ?: return
        if (ack.compatible) {
            state.acknowledgeBusinessVersion()
        } else {
            state.rejectBusinessVersion()
        }
    }

    private suspend fun handleBusinessKeyExchange(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope) {
        val peerId = state.peerId ?: return
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
            rejectKeyExchange(session, state, envelope.id, REASON_KEY_EXCHANGE_GENERIC, MESSAGE_KEY_EXCHANGE_GENERIC)
            return
        }
        if (!state.requiresKeyExchangeNonce && kotlin.math.abs(System.currentTimeMillis() - envelope.timestamp) > KEY_EXCHANGE_TIMESTAMP_WINDOW_MILLIS) {
            rejectKeyExchange(session, state, envelope.id, REASON_KEY_EXCHANGE_TIMESTAMP_EXPIRED, MESSAGE_KEY_EXCHANGE_TIMESTAMP_EXPIRED)
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
            rejectKeyExchange(session, state, envelope.id, REASON_KEY_EXCHANGE_SIGNATURE_INVALID, MESSAGE_KEY_EXCHANGE_SIGNATURE_INVALID)
            return
        }
        state.receivePeerEphemeralKey(payload.ephemeralPublicKey)
        if (!state.sentKeyExchange) {
            sendBusinessKeyExchange(session, state)
        }
        if (state.negotiationReady) {
            sendBusinessNegotiate(session, state)
        }
    }

    private suspend fun handleBusinessKeyExchangeNonce(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope) {
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
            sendBusinessKeyExchangeNonce(session, state)
        }
        if (state.keyExchangeNonceReady) {
            sendBusinessKeyExchange(session, state)
        }
    }

    private suspend fun rejectKeyExchange(session: DefaultWebSocketServerSession, state: ServerPeerState, correlationId: String, reason: String, message: String) {
        val peerId = state.peerId ?: return
        state.rejectKeyExchange()
        session.sendLanMessage(
            state.identity,
            peerId,
            "business.v1.key-exchange-reject",
            LanRejectPayload(reason, message),
            correlationId,
            state.sequence,
        )
    }

    private suspend fun sendBusinessVersion(session: DefaultWebSocketServerSession, state: ServerPeerState) {
        val peerId = state.peerId ?: return
        if (state.sentBusinessVersion || state.businessRejected) {
            return
        }
        state.markBusinessVersionSent()
        session.sendLanMessage(
            state.identity,
            peerId,
            "business.v1.version",
            BusinessVersionPayload(BUSINESS_PROTOCOL_VERSION),
            sequence = state.sequence,
        )
    }

    private suspend fun handleBusinessNegotiate(session: DefaultWebSocketServerSession, state: ServerPeerState, envelope: LanEnvelope): LanSessionCrypto? {
        val peerId = state.peerId ?: return null
        if (!state.sentBusinessNegotiate) {
            sendBusinessNegotiate(session, state)
        }
        val negotiation = runCatching {
            json.decodeFromJsonElement(BusinessNegotiatePayload.serializer(), envelope.payload)
        }.getOrNull() ?: return null
        val suite = LanSessionCrypto.chooseSuite(LanSessionCrypto.supportedSuites, negotiation.supported, false) ?: return null
        val publicKey = state.peerPublicKey ?: return null
        return if (state.requiresKeyExchange) {
            val localEphemeral = state.localEphemeralKeyPair ?: return null
            val peerEphemeral = state.peerEphemeralPublicKey ?: return null
            LanSessionCrypto.createWithEphemeralKeys(
                json = json,
                suite = suite,
                localEphemeralPrivateKey = localEphemeral.privateKeyBytes,
                localEphemeralPublicKey = localEphemeral.publicKey,
                peerEphemeralPublicKey = peerEphemeral,
                localDeviceId = state.identity.deviceId,
                peerDeviceId = peerId,
                protocolVersion = state.negotiatedProtocolVersion,
                localIsInitiator = false,
            )
        } else {
            LanSessionCrypto.create(json, suite, state.identity.privateKey, publicKey, false)
        }
    }

    private suspend fun ApplicationCall.handleSwimPing() {
        val startedAt = System.currentTimeMillis()
        val identity = settingsDataStore.currentDeviceIdentity()
        if (identity == null) {
            respond(HttpStatusCode.Unauthorized)
            return
        }
        if ((request.headers["Content-Length"]?.toLongOrNull() ?: 0L) > SWIM_MAX_BODY_BYTES) {
            respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        val body = runCatching { receiveText() }.getOrElse {
            respond(HttpStatusCode.BadRequest)
            return
        }
        val swimRequest = runCatching { json.decodeFromString(SwimEnvelope.serializer(), body) }.getOrElse {
            respond(HttpStatusCode.BadRequest)
            return
        }
        val from = swimRequest.payload.from
        val host = request.local.remoteHost
        listener?.onSwimMessage(swimRequest, host)
        val response = when (swimRequest.type) {
            "swim.ping" -> swimAck(identity, swimRequest.payload.seq)
            "swim.ping-req" -> {
                val target = swimRequest.payload.target
                if (target.isNullOrBlank()) {
                    respond(HttpStatusCode.BadRequest)
                    return
                }
                if (target == identity.deviceId) {
                    swimAck(identity, swimRequest.payload.seq)
                } else {
                    val device = deviceRepository.getDevice(target)
                    if (device?.localIp == null || device.localPort == null) {
                        respond(HttpStatusCode.NotFound)
                        return
                    }
                    lanSwimClient.ping(
                        identity = identity,
                        ip = device.localIp,
                        port = device.localPort,
                        incarnation = currentSwimIncarnation(identity),
                        seq = swimRequest.payload.seq,
                        gossip = swimGossip(identity),
                    ).getOrElse {
                        if (!it.isExpectedSwimProbeFailure()) {
                            CoLinkLog.w("SWIM", "ping-req target probe failed target=${CoLinkLog.shortId(target)}", it)
                        }
                        respond(HttpStatusCode.GatewayTimeout)
                        return
                    }.also {
                        if (!it.isTargetAck(target)) {
                            respond(HttpStatusCode.GatewayTimeout)
                            return
                        }
                        listener?.onSwimMessage(it, null)
                    }
                }
            }
            else -> {
                respond(HttpStatusCode.BadRequest)
                return
            }
        }
        respondText(json.encodeToString(response), ContentType.Application.Json)
        CoLinkLog.d("SWIM", "handled ${swimRequest.type} from=${CoLinkLog.shortId(from)} host=$host total=${elapsedSince(startedAt)}ms")
    }

    private fun swimAck(identity: DeviceIdentity, seq: Long): SwimEnvelope =
        SwimEnvelope(
            type = "swim.ack",
            payload = SwimPayload(
                seq = seq,
                from = identity.deviceId,
                incarnation = currentSwimIncarnation(identity),
                gossip = swimGossip(identity),
            ),
        )

    private fun currentSwimIncarnation(identity: DeviceIdentity): Long =
        listener?.currentSwimIncarnation(identity.deviceId) ?: System.currentTimeMillis()

    private fun swimGossip(identity: DeviceIdentity): List<SwimGossip> =
        listener?.currentSwimGossip(identity.deviceId)?.takeIf { it.isNotEmpty() }
            ?: listOf(SwimGossip(identity.deviceId, "alive", System.currentTimeMillis()))

    private suspend fun handleTransfer(session: DefaultWebSocketServerSession) {
        val sessionId = session.call.parameters["sessionId"]?.takeIf { it.isNotBlank() } ?: return session.close()
        val token = session.call.request.queryParameters["token"].orEmpty()
        val expected = transferTokens.remove(sessionId)
        if (expected == null || expected != token) {
            session.close()
            return
        }
        transferConnections[sessionId] = session
        listener?.onTransferConnected(sessionId)
        try {
            for (frame in session.incoming) {
                val bytes = (frame as? Frame.Binary)?.data ?: continue
                val dataFrame = FileDataFrame.decode(bytes) ?: continue
                listener?.onTransferFrame(sessionId, dataFrame)
            }
        } finally {
            transferConnections.remove(sessionId)
            listener?.onTransferClosed(sessionId)
        }
    }

    private suspend fun markPeerEndpoint(deviceId: String, session: DefaultWebSocketServerSession) {
        deviceRepository.markLanEndpoint(deviceId, session.call.request.local.remoteHost, LAN_PORT)
    }

    private suspend fun sendHello(session: DefaultWebSocketServerSession, identity: DeviceIdentity) {
        session.send(
            Frame.Text(
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
            ),
        )
    }

    private suspend fun sendHelloAck(session: DefaultWebSocketServerSession, payload: VersionAckPayload) {
        session.send(
            Frame.Text(
                json.encodeToString(
                    ProtocolHelloAckEnvelope(
                        type = "protocol.hello-ack",
                        payload = payload,
                    ),
                ),
            ),
        )
    }

    private suspend fun DefaultWebSocketServerSession.readTextMessage(): String? {
        while (true) {
            val frame = incoming.receiveCatching().getOrNull() ?: return null
            val text = (frame as? Frame.Text)?.readText() ?: continue
            return text
        }
    }

    private suspend inline fun <reified T> DefaultWebSocketServerSession.sendLanMessage(
        identity: DeviceIdentity,
        to: String,
        type: String,
        payload: T,
        correlationId: String? = null,
        sequence: LanSequence? = null,
    ) {
        sendLanMessageWithTimestamp(identity, to, type, System.currentTimeMillis(), payload, correlationId, sequence)
    }

    private suspend inline fun <reified T> DefaultWebSocketServerSession.sendLanMessageWithTimestamp(
        identity: DeviceIdentity,
        to: String,
        type: String,
        timestamp: Long,
        payload: T,
        correlationId: String? = null,
        sequence: LanSequence? = null,
    ) {
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
        send(Frame.Text(json.encodeToString(envelope)))
    }

    private fun launchKeepaliveMonitor(deviceId: String, connection: ServerPeerConnection) =
        CoroutineScope(Dispatchers.IO).launch {
            while (peers[deviceId] === connection) {
                val inactiveMillis = System.currentTimeMillis() - connection.lastApplicationActivityMillis
                if (inactiveMillis >= KEEPALIVE_TIMEOUT_MILLIS) {
                    peers.remove(deviceId, connection)
                    runCatching { connection.session.close() }
                    return@launch
                }
                val pingId = UUID.randomUUID().toString()
                connection.rememberHeartbeat(pingId)
                runCatching {
                    connection.session.sendLanMessageWithId(connection.identity, deviceId, "heartbeat.v1.ping", EmptyPayload, pingId, sequence = connection.sequence)
                }.onFailure {
                    peers.remove(deviceId, connection)
                    runCatching { connection.session.close() }
                    return@launch
                }
                delay(HEARTBEAT_INTERVAL_MILLIS)
            }
        }

    private suspend inline fun <reified T> DefaultWebSocketServerSession.sendLanMessageWithId(
        identity: DeviceIdentity,
        to: String,
        type: String,
        payload: T,
        id: String,
        correlationId: String? = null,
        sequence: LanSequence? = null,
    ) {
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
        send(Frame.Text(json.encodeToString(envelope)))
    }

    interface Listener {
        fun onConnected(deviceId: String)
        fun onMessage(
            fromDeviceId: String,
            envelopeId: String,
            correlationId: String?,
            message: BusinessEnvelope,
        )
        fun onDisconnected(deviceId: String)
        fun onKeyChanged(deviceId: String, name: String)
        fun onTransferConnected(sessionId: String)
        fun onTransferFrame(sessionId: String, frame: FileDataFrame)
        fun onTransferClosed(sessionId: String)
        fun onSwimMessage(message: SwimEnvelope, sourceIp: String?)
        fun currentSwimGossip(localDeviceId: String): List<SwimGossip>
        fun currentSwimIncarnation(localDeviceId: String): Long
    }
}

private class ServerPeerState(
    val identity: DeviceIdentity,
    val expectedDeviceId: String?,
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
    var protocolRejected = false
        private set
    var peerId: String? = expectedDeviceId
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
    fun rejectProtocol() { protocolRejected = true }
    fun prepareAuthentication(publicKey: String, name: String) {
        peerPublicKey = publicKey
        peerName = name
    }
    fun ensureLocalNonce(create: () -> String): String = localNonce ?: create().also { localNonce = it }
    fun markAuthResponseSent() { sentAuthResponse = true }
    fun markLocalVerified() { localVerified = true }
    fun markPeerVerified() { peerVerified = true }
    fun abortAuthentication() { authAborted = true }
    fun receivePairingPeer(
        publicKey: String,
        name: String,
        peerNonce: String,
        localNonce: String,
    ) {
        peerPublicKey = publicKey
        peerName = name
        this.peerNonce = peerNonce
        this.localNonce = localNonce
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

private data class ReadyPeer(
    val peerId: String,
    val crypto: LanSessionCrypto,
    val businessVersion: String?,
)

private data class ServerPeerConnection(
    val session: DefaultWebSocketServerSession,
    val crypto: LanSessionCrypto,
    val identity: DeviceIdentity,
    val businessVersion: String? = null,
    val sequence: LanSequence = LanSequence(),
) {
    @Volatile
    var lastApplicationActivityMillis: Long = System.currentTimeMillis()
        private set

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

private fun SwimEnvelope.isTargetAck(target: String): Boolean =
    type == "swim.ack" && payload.from == target

private fun Throwable.isExpectedSwimProbeFailure(): Boolean =
    this is InterruptedIOException || cause?.isExpectedSwimProbeFailure() == true

private fun elapsedSince(startedAt: Long): Long =
    System.currentTimeMillis() - startedAt
