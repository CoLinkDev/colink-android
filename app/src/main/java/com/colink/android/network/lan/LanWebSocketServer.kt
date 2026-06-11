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
import com.colink.android.util.CoLinkLog
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

const val LAN_PORT = 27_777
private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
private const val SWIM_MAX_BODY_BYTES = 16 * 1024
private const val REASON_HANDSHAKE_SIGNATURE_INVALID = "colink:handshake.signature_invalid.v1"
private const val REASON_HANDSHAKE_KEY_CHANGED = "colink:handshake.key_changed.v1"
private const val REASON_HANDSHAKE_USER_REJECTED = "colink:handshake.user_rejected.v1"

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
            CoLinkLog.d("LAN", "LAN server already running")
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

    suspend fun send(deviceId: String, message: BusinessEnvelope): Boolean {
        val connection = peers[deviceId] ?: return false
        val crypto = connection.crypto
        val payload = crypto.encrypt(message)
        val sent = runCatching {
            connection.session.sendPeerMessage("business.v1.message", payload)
            true
        }.getOrDefault(false)
        if (!sent) {
            peers.remove(deviceId, connection)
        }
        return sent
    }

    fun hasPeer(deviceId: String): Boolean =
        peers.containsKey(deviceId)

    suspend fun disconnect(deviceId: String) {
        peers.remove(deviceId)?.let { connection ->
            runCatching { connection.session.close() }
        }
    }

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
                CoLinkLog.w("Pairing", "inbound pairing failed request=${CoLinkLog.shortId(requestId)} reason=$reason")
                pairingRequestId = null
            }
        }
        try {
            val identity = settingsDataStore.currentDeviceIdentity()
                ?: run {
                    CoLinkLog.w("LAN", "closing inbound peer because local identity is missing")
                    return session.close()
                }
            val first = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) {
                session.readPeerEnvelope()
            } ?: run {
                CoLinkLog.w("LAN", "closing inbound peer because handshake timed out")
                return session.close()
            }
            if (first.type != "handshake.v1.request") {
                CoLinkLog.w("LAN", "rejecting inbound peer invalid first message type=${first.type}")
                session.sendPeerMessage(
                    "handshake.v1.reject",
                    HandshakeRejectPayload("invalid_handshake"),
                )
                return session.close()
            }

            val proof = json.decodeFromJsonElement(HandshakeProofPayload.serializer(), first.payload)
            if (!handshake.verifyProof(proof)) {
                CoLinkLog.w("LAN", "rejecting inbound peer invalid signature device=${CoLinkLog.shortId(proof.deviceId)}")
                session.sendPeerMessage(
                    "handshake.v1.reject",
                    HandshakeRejectPayload(REASON_HANDSHAKE_SIGNATURE_INVALID),
                )
                return session.close()
            }

            val trust = lanTrustStore.trustState(proof.deviceId, proof.publicKey)
            CoLinkLog.i(
                "LAN",
                "received inbound handshake device=${CoLinkLog.shortId(proof.deviceId)} name=${proof.name} trust=$trust",
            )
            if (trust == LanTrustState.KeyChanged) {
                lanTrustStore.clearLanPairing(proof.deviceId)
                deviceRepository.clearLanEndpoint(proof.deviceId)
                session.sendPeerMessage(
                    "handshake.v1.reject",
                    HandshakeRejectPayload(REASON_HANDSHAKE_KEY_CHANGED),
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
                    CoLinkLog.w("Pairing", "inbound pairing rejected device=${CoLinkLog.shortId(proof.deviceId)}")
                    session.sendPeerMessage(
                        "handshake.v1.reject",
                        HandshakeRejectPayload(REASON_HANDSHAKE_USER_REJECTED),
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

            val negotiationEnvelope = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) {
                session.readPeerEnvelope()
            }
            if (negotiationEnvelope == null) {
                failPairing("LAN connection ended")
                return session.close()
            }
            if (negotiationEnvelope.type != "business.v1.negotiate") {
                CoLinkLog.w("LAN", "invalid inbound negotiation type=${negotiationEnvelope.type}")
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
                CoLinkLog.w("LAN", "no compatible LAN encryption suite device=${CoLinkLog.shortId(proof.deviceId)}")
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
                CoLinkLog.i("LAN", "trusted inbound LAN peer device=${CoLinkLog.shortId(proof.deviceId)} name=${proof.name}")
                pairingCoordinator.complete(requestId)
                pairingRequestId = null
            }
            connectedPeerId = proof.deviceId
            peers.remove(proof.deviceId)?.let { existing ->
                runCatching { existing.session.close() }
            }
            peers[proof.deviceId] = ServerPeerConnection(session, crypto)
            markPeerEndpoint(proof.deviceId, session)
            CoLinkLog.i("LAN", "inbound LAN peer ready device=${CoLinkLog.shortId(proof.deviceId)}")
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
            CoLinkLog.w("LAN", "inbound peer handler failed device=${CoLinkLog.shortId(connectedPeerId)}", error)
            failPairing(error.message ?: "LAN pairing failed")
            throw error
        } finally {
            val id = connectedPeerId
            if (id != null) {
                CoLinkLog.w("LAN", "inbound LAN peer ended device=${CoLinkLog.shortId(id)}")
                peers.remove(id)
                listener?.onDisconnected(id)
            }
        }
    }

    private suspend fun ApplicationCall.handleSwimPing() {
        val startedAt = System.currentTimeMillis()
        val identity = settingsDataStore.currentDeviceIdentity()
        if (identity == null) {
            CoLinkLog.w("SWIM", "rejected SWIM request because local identity is missing")
            respond(HttpStatusCode.Unauthorized)
            return
        }
        if ((request.headers["Content-Length"]?.toLongOrNull() ?: 0L) > SWIM_MAX_BODY_BYTES) {
            CoLinkLog.w("SWIM", "rejected oversized SWIM request")
            respond(HttpStatusCode.PayloadTooLarge)
            return
        }
        val readStartedAt = System.currentTimeMillis()
        val body = runCatching { receiveText() }.getOrElse {
            CoLinkLog.w("SWIM", "rejected unreadable SWIM request elapsed=${elapsedSince(startedAt)}ms", it)
            respond(HttpStatusCode.BadRequest)
            return
        }
        val readMillis = elapsedSince(readStartedAt)
        val decodeStartedAt = System.currentTimeMillis()
        val swimRequest = runCatching {
            json.decodeFromString(SwimEnvelope.serializer(), body)
        }.getOrElse {
            CoLinkLog.w("SWIM", "rejected invalid SWIM request", it)
            respond(HttpStatusCode.BadRequest)
            return
        }
        val decodeMillis = elapsedSince(decodeStartedAt)

        val from = swimRequest.payload.from
        val host = request.local.remoteHost
        CoLinkLog.d(
            "SWIM",
            "handling ${swimRequest.type} from=${CoLinkLog.shortId(from)} host=$host gossip=${swimRequest.payload.gossip.size}",
        )
        val listenerStartedAt = System.currentTimeMillis()
        listener?.onSwimMessage(swimRequest, host)
        val listenerMillis = elapsedSince(listenerStartedAt)

        var relayMillis: Long? = null
        val responseBuildStartedAt = System.currentTimeMillis()
        val response = when (swimRequest.type) {
            "swim.ping" -> swimAck(identity, swimRequest.payload.seq)
            "swim.ping-req" -> {
                val target = swimRequest.payload.target
                when {
                    target.isNullOrBlank() -> {
                        CoLinkLog.w("SWIM", "rejected ping-req with missing target from=${CoLinkLog.shortId(from)}")
                        respond(HttpStatusCode.BadRequest)
                        return
                    }

                    target == identity.deviceId -> swimAck(identity, swimRequest.payload.seq)
                    else -> {
                        val device = deviceRepository.getDevice(target)
                        if (device?.localIp == null || device.localPort == null) {
                            CoLinkLog.w("SWIM", "ping-req target endpoint missing target=${CoLinkLog.shortId(target)}")
                            respond(HttpStatusCode.NotFound)
                            return
                        }
                        val relayStartedAt = System.currentTimeMillis()
                        lanSwimClient
                            .ping(
                                identity = identity,
                                ip = device.localIp,
                                port = device.localPort,
                                incarnation = currentSwimIncarnation(identity),
                                seq = swimRequest.payload.seq,
                                gossip = swimGossip(identity),
                            )
                            .getOrElse {
                                relayMillis = elapsedSince(relayStartedAt)
                                if (it.isExpectedSwimProbeFailure()) {
                                    CoLinkLog.d(
                                        "SWIM",
                                        "ping-req target probe timed out target=${CoLinkLog.shortId(target)} elapsed=${relayMillis}ms",
                                    )
                                } else {
                                    CoLinkLog.w("SWIM", "ping-req target probe failed target=${CoLinkLog.shortId(target)}", it)
                                }
                                val respondMillis = respondStatusWithTiming(HttpStatusCode.GatewayTimeout)
                                logHandledSwimRequest(
                                    type = swimRequest.type,
                                    from = from,
                                    host = host,
                                    status = HttpStatusCode.GatewayTimeout.value,
                                    startedAt = startedAt,
                                    readMillis = readMillis,
                                    decodeMillis = decodeMillis,
                                    listenerMillis = listenerMillis,
                                    responseBuildMillis = elapsedSince(responseBuildStartedAt),
                                    relayMillis = relayMillis,
                                    respondMillis = respondMillis,
                                )
                                return
                            }
                            .also {
                                relayMillis = elapsedSince(relayStartedAt)
                                listener?.onSwimMessage(it, null)
                            }
                    }
                }
            }

            else -> {
                CoLinkLog.w("SWIM", "rejected unknown SWIM type=${swimRequest.type}")
                respond(HttpStatusCode.BadRequest)
                return
            }
        }

        val responseBuildMillis = elapsedSince(responseBuildStartedAt)
        val respondStartedAt = System.currentTimeMillis()
        respondText(json.encodeToString(response), ContentType.Application.Json)
        val respondMillis = elapsedSince(respondStartedAt)
        logHandledSwimRequest(
            type = swimRequest.type,
            from = from,
            host = host,
            status = HttpStatusCode.OK.value,
            startedAt = startedAt,
            readMillis = readMillis,
            decodeMillis = decodeMillis,
            listenerMillis = listenerMillis,
            responseBuildMillis = responseBuildMillis,
            relayMillis = relayMillis,
            respondMillis = respondMillis,
        )
    }

    private suspend fun ApplicationCall.respondStatusWithTiming(status: HttpStatusCode): Long {
        val startedAt = System.currentTimeMillis()
        respond(status)
        return elapsedSince(startedAt)
    }

    private fun logHandledSwimRequest(
        type: String,
        from: String,
        host: String,
        status: Int,
        startedAt: Long,
        readMillis: Long,
        decodeMillis: Long,
        listenerMillis: Long,
        responseBuildMillis: Long,
        relayMillis: Long?,
        respondMillis: Long,
    ) {
        val relay = relayMillis?.let { " relay=${it}ms" } ?: ""
        CoLinkLog.d(
            "SWIM",
            "handled $type from=${CoLinkLog.shortId(from)} host=$host status=$status total=${elapsedSince(startedAt)}ms read=${readMillis}ms decode=${decodeMillis}ms listener=${listenerMillis}ms build=${responseBuildMillis}ms$relay respond=${respondMillis}ms",
        )
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

        fun currentSwimIncarnation(localDeviceId: String): Long
    }
}

private data class ServerPeerConnection(
    val session: DefaultWebSocketServerSession,
    val crypto: LanSessionCrypto,
)

private fun Throwable.isExpectedSwimProbeFailure(): Boolean =
    this is InterruptedIOException || cause?.isExpectedSwimProbeFailure() == true

private fun elapsedSince(startedAt: Long): Long =
    System.currentTimeMillis() - startedAt
