package com.colink.android.crypto

import com.colink.android.domain.model.DeviceIdentity
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

        val proof = handshake.buildProof(identity)

        assertEquals(identity.deviceId, proof.deviceId)
        assertEquals(identity.publicKey, proof.publicKey)
        assertEquals(identity.name, proof.name)
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
}
