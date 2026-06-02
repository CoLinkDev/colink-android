package com.colink.android.network.lan

import com.colink.android.crypto.Handshake
import com.colink.android.crypto.LanSessionCrypto
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.BusinessNegotiatePayload
import com.colink.android.network.message.EncryptedBusinessPayload
import com.colink.android.network.message.HandshakeAcceptPayload
import com.colink.android.network.message.HandshakeProofPayload
import com.colink.android.network.message.HandshakeRejectPayload
import com.colink.android.network.message.PeerEnvelope
import com.colink.android.network.message.SwimEnvelope
import com.colink.android.network.message.SwimGossip
import com.colink.android.network.message.SwimPayload
import com.colink.android.network.transfer.FileDataFrame
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

const val LAN_PORT = 27_777
private const val SWIM_MAX_BODY_BYTES = 16 * 1024

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
            install(WebSockets) {
                pingPeriodMillis = 15_000
                timeoutMillis = 45_000
            }
            routing {
                post("/peer/swim/v1") {
                    call.handleSwimPing()
                }
                webSocket("/peer") {
                    handlePeer(this)
                }
                webSocket("/transfer/{sessionId}") {
                    handleTransfer(this)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        engine = null
        peers.clear()
        transferTokens.clear()
        transferConnections.clear()
    }

    suspend fun send(deviceId: String, message: BusinessEnvelope): Boolean {
        val connection = peers[deviceId] ?: return false
        val crypto = connection.crypto
        val payload = crypto.encrypt(message)
        return runCatching {
            connection.session.sendPeerMessage("business.v1.message", payload)
            true
        }.getOrDefault(false)
    }

    fun hasPeer(deviceId: String): Boolean =
        peers.containsKey(deviceId)

    fun registerTransferToken(sessionId: String, token: String) {
        transferTokens[sessionId] = token
    }

    suspend fun unregisterTransfer(sessionId: String) {
        transferTokens.remove(sessionId)
        transferConnections.remove(sessionId)?.let { session ->
            runCatching { session.close() }
        }
    }

    suspend fun sendTransferFrame(sessionId: String, frame: FileDataFrame): Boolean {
        val session = transferConnections[sessionId] ?: return false
        return runCatching {
            session.send(Frame.Binary(fin = true, data = frame.encode()))
            true
        }.getOrDefault(false)
    }

    private suspend fun handlePeer(session: DefaultWebSocketServerSession) {
        var connectedPeerId: String? = null
        var crypto: LanSessionCrypto? = null
        var pairingRequestId: String? = null
        fun failPairing(reason: String) {
            pairingRequestId?.let { requestId ->
                pairingCoordinator.fail(requestId, reason)
                pairingRequestId = null
            }
        }
        try {
            val identity = settingsDataStore.currentDeviceIdentity()
                ?: return session.close()
            val first = session.readPeerEnvelope() ?: return session.close()
            if (first.type != "handshake.v1.request") {
                session.sendPeerMessage(
                    "handshake.v1.reject",
                    HandshakeRejectPayload("invalid_handshake"),
                )
                return session.close()
            }

            val proof = json.decodeFromJsonElement(HandshakeProofPayload.serializer(), first.payload)
            if (!handshake.verifyProof(proof)) {
                session.sendPeerMessage(
                    "handshake.v1.reject",
                    HandshakeRejectPayload("signature_invalid"),
                )
                return session.close()
            }

            val trust = lanTrustStore.trustState(proof.deviceId, proof.publicKey)
            if (trust == LanTrustState.KeyChanged) {
                lanTrustStore.clearLanPairing(proof.deviceId, proof.name, proof.publicKey)
                deviceRepository.clearLanEndpoint(proof.deviceId)
                session.sendPeerMessage(
                    "handshake.v1.reject",
                    HandshakeRejectPayload("key_changed"),
                )
                listener?.onKeyChanged(proof.deviceId, proof.name)
                return session.close()
            }

            val exchangeProof = handshake.buildProof(identity)
            session.sendPeerMessage("handshake.v1.exchange", exchangeProof)

            if (trust == LanTrustState.Unknown) {
                val decision = pairingCoordinator.request(
                    deviceId = proof.deviceId,
                    name = proof.name,
                    publicKey = proof.publicKey,
                    code = handshake.pairingCode(
                        proof.publicKey,
                        identity.publicKey,
                        proof.nonce,
                        exchangeProof.nonce,
                    ),
                    reason = "unknown_device",
                )
                pairingRequestId = decision.requestId
                if (!decision.accepted) {
                    pairingRequestId = null
                    session.sendPeerMessage(
                        "handshake.v1.reject",
                        HandshakeRejectPayload("user_rejected"),
                    )
                    return session.close()
                }
            }

            session.sendPeerMessage(
                "handshake.v1.accept",
                HandshakeAcceptPayload(identity.deviceId),
            )
            session.sendPeerMessage(
                "business.v1.negotiate",
                BusinessNegotiatePayload(
                    supported = LanSessionCrypto.supportedSuites,
                    preferred = LanSessionCrypto.preferredSuite(),
                ),
            )

            val negotiationEnvelope = session.readPeerEnvelope()
            if (negotiationEnvelope == null) {
                failPairing("LAN connection ended")
                return session.close()
            }
            if (negotiationEnvelope.type != "business.v1.negotiate") {
                failPairing("invalid LAN encryption negotiation type")
                return session.close()
            }
            val negotiation = json.decodeFromJsonElement(
                BusinessNegotiatePayload.serializer(),
                negotiationEnvelope.payload,
            )
            val suite = LanSessionCrypto.chooseSuite(
                localSupported = LanSessionCrypto.supportedSuites,
                peerSupported = negotiation.supported,
                localIsInitiator = false,
            )
            if (suite == null) {
                failPairing("no compatible LAN encryption suite")
                return session.close()
            }

            crypto = LanSessionCrypto.create(
                json = json,
                suite = suite,
                privateKey = identity.privateKey,
                peerPublicKey = proof.publicKey,
                localIsInitiator = false,
            )
            pairingRequestId?.let { requestId ->
                lanTrustStore.trust(proof.deviceId, proof.name, proof.publicKey)
                pairingCoordinator.complete(requestId)
                pairingRequestId = null
            }
            connectedPeerId = proof.deviceId
            peers[proof.deviceId] = ServerPeerConnection(session, crypto)
            markPeerEndpoint(proof.deviceId, session)
            listener?.onConnected(proof.deviceId)

            for (frame in session.incoming) {
                val text = (frame as? Frame.Text)?.readText() ?: continue
                val envelope = json.decodeFromString(PeerEnvelope.serializer(), text)
                if (envelope.type != "business.v1.message") {
                    continue
                }
                val payload = json.decodeFromJsonElement(
                    EncryptedBusinessPayload.serializer(),
                    envelope.payload,
                )
                listener?.onMessage(proof.deviceId, crypto.decrypt(payload))
            }
        } catch (error: Throwable) {
            failPairing(error.message ?: "LAN pairing failed")
            throw error
        } finally {
            val id = connectedPeerId
            if (id != null) {
                peers.remove(id)
                deviceRepository.clearLanEndpoint(id)
                listener?.onDisconnected(id)
            }
        }
    }

    private suspend fun ApplicationCall.handleSwimPing() {
        val identity = settingsDataStore.currentDeviceIdentity()
        if (identity == null) {
            respond(HttpStatusCode.Unauthorized)
            return
        }
        if ((request.headers["Content-Length"]?.toLongOrNull() ?: 0L) > SWIM_MAX_BODY_BYTES) {
            respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        val swimRequest = runCatching {
            json.decodeFromString(SwimEnvelope.serializer(), receiveText())
        }.getOrElse {
            respond(HttpStatusCode.BadRequest)
            return
        }

        val from = swimRequest.payload.from
        val host = request.local.remoteHost
        if (from.isNotBlank() && lanTrustStore.isLanTrusted(from)) {
            deviceRepository.markLanEndpoint(from, host, LAN_PORT)
        }
        listener?.onSwimMessage(swimRequest, host)

        val response = when (swimRequest.type) {
            "swim.ping" -> swimAck(identity, swimRequest.payload.seq)
            "swim.ping-req" -> {
                val target = swimRequest.payload.target
                when {
                    target.isNullOrBlank() -> {
                        respond(HttpStatusCode.BadRequest)
                        return
                    }

                    target == identity.deviceId -> swimAck(identity, swimRequest.payload.seq)
                    else -> {
                        val device = deviceRepository.getDevice(target)
                        if (device?.localIp == null || device.localPort == null) {
                            respond(HttpStatusCode.NotFound)
                            return
                        }
                        lanSwimClient
                            .ping(
                                identity = identity,
                                ip = device.localIp,
                                port = device.localPort,
                                seq = swimRequest.payload.seq,
                                gossip = swimGossip(identity),
                            )
                            .getOrElse {
                                respond(HttpStatusCode.GatewayTimeout)
                                return
                            }
                            .also { listener?.onSwimMessage(it, null) }
                    }
                }
            }

            else -> {
                respond(HttpStatusCode.BadRequest)
                return
            }
        }

        respond(response)
    }

    private fun swimAck(identity: DeviceIdentity, seq: Long): SwimEnvelope =
        SwimEnvelope(
            type = "swim.ack",
            payload = SwimPayload(
                seq = seq,
                from = identity.deviceId,
                gossip = swimGossip(identity),
            ),
        )

    private fun selfGossip(identity: DeviceIdentity): List<SwimGossip> =
        listOf(
            SwimGossip(
                deviceId = identity.deviceId,
                state = "alive",
                incarnation = System.currentTimeMillis(),
            ),
        )

    private fun swimGossip(identity: DeviceIdentity): List<SwimGossip> =
        listener?.currentSwimGossip(identity.deviceId)?.takeIf { it.isNotEmpty() }
            ?: selfGossip(identity)

    private suspend fun handleTransfer(session: DefaultWebSocketServerSession) {
        val sessionId = session.call.parameters["sessionId"]?.takeIf { it.isNotBlank() }
            ?: return session.close()
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

    private suspend fun markPeerEndpoint(
        deviceId: String,
        session: DefaultWebSocketServerSession,
    ) {
        val host = session.call.request.local.remoteHost
        deviceRepository.markLanEndpoint(deviceId, host, LAN_PORT)
    }

    private suspend fun DefaultWebSocketServerSession.readPeerEnvelope(): PeerEnvelope? {
        for (frame in incoming) {
            val text = (frame as? Frame.Text)?.readText() ?: continue
            return json.decodeFromString(PeerEnvelope.serializer(), text)
        }
        return null
    }

    private suspend inline fun <reified T> DefaultWebSocketServerSession.sendPeerMessage(
        type: String,
        payload: T,
    ) {
        val envelope = PeerEnvelope(
            type = type,
            payload = json.encodeToJsonElement(payload),
        )
        send(Frame.Text(json.encodeToString(envelope)))
    }

    interface Listener {
        fun onConnected(deviceId: String)

        fun onMessage(fromDeviceId: String, message: BusinessEnvelope)

        fun onDisconnected(deviceId: String)

        fun onKeyChanged(deviceId: String, name: String)

        fun onTransferConnected(sessionId: String)

        fun onTransferFrame(sessionId: String, frame: FileDataFrame)

        fun onTransferClosed(sessionId: String)

        fun onSwimMessage(message: SwimEnvelope, sourceIp: String?)

        fun currentSwimGossip(localDeviceId: String): List<SwimGossip>
    }
}

private data class ServerPeerConnection(
    val session: DefaultWebSocketServerSession,
    val crypto: LanSessionCrypto,
)
