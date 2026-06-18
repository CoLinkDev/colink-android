package com.colink.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {
    private val keyManager = KeyManager()
    private val handshake = Handshake(keyManager)

    @Test
    fun signsAndVerifiesAuthResponse() {
        val keys = keyManager.generateKeyPair()

        val signature = handshake.signAuth(
            privateKey = keys.privateKey,
            from = "device",
            timestamp = 1716451200000,
            nonce = "nonce",
        )

        assertTrue(handshake.verifyAuth(keys.publicKey, "device", 1716451200000, "nonce", signature))
        assertFalse(handshake.verifyAuth(keys.publicKey, "other-device", 1716451200000, "nonce", signature))
    }

    @Test
    fun pairingCodeIsStableAcrossKeyOrder() {
        val codeA = handshake.pairingCode("b", "a", "nonce-a", "nonce-b")
        val codeB = handshake.pairingCode("a", "b", "nonce-a", "nonce-b")

        assertEquals(codeA, codeB)
        assertEquals(6, codeA.length)
        assertEquals("893018", codeA)
    }
}
