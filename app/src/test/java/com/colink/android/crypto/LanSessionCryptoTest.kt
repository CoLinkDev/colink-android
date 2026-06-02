package com.colink.android.crypto

import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.TextMessagePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanSessionCryptoTest {
    private val json = Json
    private val keyManager = KeyManager()

    @Test
    fun encryptsAndDecryptsBusinessEnvelopeWithAesGcm() {
        encryptsAndDecryptsBusinessEnvelope("x25519-aes-256-gcm")
    }

    @Test
    fun encryptsAndDecryptsBusinessEnvelopeWithChaCha20Poly1305() {
        encryptsAndDecryptsBusinessEnvelope("x25519-chacha20-poly1305")
    }

    @Test
    fun choosesSuiteByInitiatorOrder() {
        val local = listOf("x25519-chacha20-poly1305", "x25519-aes-256-gcm")
        val peer = listOf("x25519-aes-256-gcm", "x25519-chacha20-poly1305")

        assertEquals(
            "x25519-chacha20-poly1305",
            LanSessionCrypto.chooseSuite(local, peer, localIsInitiator = true),
        )
        assertEquals(
            "x25519-aes-256-gcm",
            LanSessionCrypto.chooseSuite(local, peer, localIsInitiator = false),
        )
        assertNull(
            LanSessionCrypto.chooseSuite(
                localSupported = listOf("none"),
                peerSupported = peer,
                localIsInitiator = true,
            ),
        )
    }

    private fun encryptsAndDecryptsBusinessEnvelope(suite: String) {
        val first = keyManager.generateKeyPair()
        val second = keyManager.generateKeyPair()
        val firstCrypto = LanSessionCrypto.create(
            json = json,
            suite = suite,
            privateKey = first.privateKey,
            peerPublicKey = second.publicKey,
            localIsInitiator = true,
        )
        val secondCrypto = LanSessionCrypto.create(
            json = json,
            suite = suite,
            privateKey = second.privateKey,
            peerPublicKey = first.publicKey,
            localIsInitiator = false,
        )
        val message = BusinessEnvelope(
            type = "message.v1.text",
            payload = json.encodeToJsonElement(
                TextMessagePayload(
                    messageId = "message",
                    text = "hello",
                ),
            ),
        )

        val encrypted = firstCrypto.encrypt(message)
        val decrypted = secondCrypto.decrypt(encrypted)

        assertEquals(message, decrypted)
    }
}
