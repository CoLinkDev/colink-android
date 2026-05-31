package com.colink.android.crypto

import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.TextMessagePayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class LanSessionCryptoTest {
    private val json = Json
    private val keyManager = KeyManager()

    @Test
    fun encryptsAndDecryptsBusinessEnvelope() {
        val first = keyManager.generateKeyPair()
        val second = keyManager.generateKeyPair()
        val firstCrypto = LanSessionCrypto.create(
            json = json,
            privateKey = first.privateKey,
            peerPublicKey = second.publicKey,
            localIsInitiator = true,
        )
        val secondCrypto = LanSessionCrypto.create(
            json = json,
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
