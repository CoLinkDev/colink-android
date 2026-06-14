package com.colink.android.crypto

import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.message.HandshakeProofPayload
import java.util.UUID
import javax.inject.Inject

class Handshake @Inject constructor(
    private val keyManager: KeyManager,
) {
    fun buildProof(identity: DeviceIdentity, hasTrust: Boolean): HandshakeProofPayload {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val timestamp = System.currentTimeMillis()
        val proof = proofBytes(identity.deviceId, timestamp, nonce)
        return HandshakeProofPayload(
            deviceId = identity.deviceId,
            publicKey = identity.publicKey,
            name = identity.name,
            timestamp = timestamp,
            nonce = nonce,
            signature = keyManager.sign(identity.privateKey, proof),
            hasTrust = hasTrust,
        )
    }

    fun verifyProof(payload: HandshakeProofPayload): Boolean =
        keyManager.verify(
            publicKey = payload.publicKey,
            payload = proofBytes(payload.deviceId, payload.timestamp, payload.nonce),
            signature = payload.signature,
        )

    fun pairingCode(
        publicKeyA: String,
        publicKeyB: String,
        nonceA: String,
        nonceB: String,
    ): String = keyManager.pairingCode(publicKeyA, publicKeyB, nonceA, nonceB)

    private fun proofBytes(deviceId: String, timestamp: Long, nonce: String): ByteArray =
        "$deviceId$timestamp$nonce".toByteArray()

}
