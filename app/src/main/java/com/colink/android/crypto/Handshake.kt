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

    fun signKeyExchangeV2(
        privateKey: String,
        from: String,
        to: String,
        ephemeralPublicKey: String,
        localNonce: String,
        peerNonce: String,
    ): String = keyManager.sign(privateKey, keyExchangeV2Bytes(from, to, ephemeralPublicKey, localNonce, peerNonce))

    fun verifyKeyExchangeV2(
        publicKey: String,
        from: String,
        to: String,
        ephemeralPublicKey: String,
        localNonce: String,
        peerNonce: String,
        signature: String,
    ): Boolean =
        keyManager.verify(
            publicKey = publicKey,
            payload = keyExchangeV2Bytes(from, to, ephemeralPublicKey, localNonce, peerNonce),
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

    private fun keyExchangeV2Bytes(from: String, to: String, ephemeralPublicKey: String, localNonce: String, peerNonce: String): ByteArray =
        "domain=colink-lan-key-exchange-v2\nfrom=$from\nto=$to\nephemeralPublicKey=$ephemeralPublicKey\nlocalNonce=$localNonce\npeerNonce=$peerNonce".toByteArray()

}
