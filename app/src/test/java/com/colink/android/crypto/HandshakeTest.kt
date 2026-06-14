package com.colink.android.crypto

import com.colink.android.domain.model.DeviceIdentity
import com.colink.android.network.message.HandshakeProofPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {
    private val keyManager = KeyManager()
    private val handshake = Handshake(keyManager)

    @Test
    fun buildsAndVerifiesProtocolProof() {
        val keys = keyManager.generateKeyPair()
        val identity = DeviceIdentity(
            userId = "user",
            deviceId = "device",
            name = "Android",
            type = "android",
            publicKey = keys.publicKey,
            privateKey = keys.privateKey,
        )

        val proof = handshake.buildProof(identity, hasTrust = false)

        assertEquals(identity.deviceId, proof.deviceId)
        assertEquals(identity.publicKey, proof.publicKey)
        assertEquals(identity.name, proof.name)
        assertFalse(proof.hasTrust)
        assertTrue(handshake.verifyProof(proof))

        val oldTimestamp = proof.timestamp - 31_000
        val oldProof = proof.copy(
            timestamp = oldTimestamp,
            signature = keyManager.sign(
                keys.privateKey,
                "${proof.deviceId}$oldTimestamp${proof.nonce}".toByteArray(),
            ),
        )
        assertTrue(handshake.verifyProof(oldProof))
        assertFalse(handshake.verifyProof(proof.copy(timestamp = oldTimestamp)))
    }

    @Test
    fun pairingCodeIsStableAcrossKeyOrder() {
        val codeA = handshake.pairingCode("b", "a", "nonce-a", "nonce-b")
        val codeB = handshake.pairingCode("a", "b", "nonce-a", "nonce-b")

        assertEquals(codeA, codeB)
        assertEquals(6, codeA.length)
    }

    @Test
    fun missingHasTrustDefaultsToTrue() {
        val payload = Json.decodeFromString(
            HandshakeProofPayload.serializer(),
            """
            {
              "deviceId": "device",
              "publicKey": "key",
              "name": "Android",
              "timestamp": 1716451200000,
              "nonce": "nonce",
              "signature": "signature"
            }
            """.trimIndent(),
        )

        assertTrue(payload.hasTrust)
    }
}
