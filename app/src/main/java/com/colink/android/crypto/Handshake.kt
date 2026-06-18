package com.colink.android.crypto

import javax.inject.Inject

class Handshake @Inject constructor(
    private val keyManager: KeyManager,
) {
    fun signAuth(privateKey: String, from: String, timestamp: Long, nonce: String): String =
        keyManager.sign(privateKey, authBytes(from, timestamp, nonce))

    fun verifyAuth(publicKey: String, from: String, timestamp: Long, nonce: String, signature: String): Boolean =
        keyManager.verify(
            publicKey = publicKey,
            payload = authBytes(from, timestamp, nonce),
            signature = signature,
        )

    fun signKeyExchange(privateKey: String, from: String, to: String, ephemeralPublicKey: String, timestamp: Long): String =
        keyManager.sign(privateKey, keyExchangeBytes(from, to, ephemeralPublicKey, timestamp))

    fun verifyKeyExchange(publicKey: String, from: String, to: String, ephemeralPublicKey: String, timestamp: Long, signature: String): Boolean =
        keyManager.verify(
            publicKey = publicKey,
            payload = keyExchangeBytes(from, to, ephemeralPublicKey, timestamp),
            signature = signature,
        )

    fun pairingCode(
        publicKeyA: String,
        publicKeyB: String,
        nonceA: String,
        nonceB: String,
    ): String = keyManager.pairingCode(publicKeyA, publicKeyB, nonceA, nonceB)

    private fun authBytes(from: String, timestamp: Long, nonce: String): ByteArray =
        "from=$from\ntimestamp=$timestamp\nnonce=$nonce".toByteArray()

    private fun keyExchangeBytes(from: String, to: String, ephemeralPublicKey: String, timestamp: Long): ByteArray =
        "domain=colink-lan-key-exchange\nfrom=$from\nto=$to\nephemeralPublicKey=$ephemeralPublicKey\ntimestamp=$timestamp".toByteArray()

}
