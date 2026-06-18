package com.colink.android.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

data class GeneratedKeyPair(
    val publicKey: String,
    val privateKey: String,
)

@Singleton
class KeyManager @Inject constructor() {
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun generateKeyPair(): GeneratedKeyPair {
        val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()
        return GeneratedKeyPair(
            publicKey = encoder.encodeToString(publicKey.encoded),
            privateKey = encoder.encodeToString(privateKey.encoded),
        )
    }

    fun sign(privateKey: String, payload: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(decoder.decode(privateKey), 0))
        signer.update(payload, 0, payload.size)
        return encoder.encodeToString(signer.generateSignature())
    }

    fun verify(publicKey: String, payload: ByteArray, signature: String): Boolean {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(decoder.decode(publicKey), 0))
        verifier.update(payload, 0, payload.size)
        return verifier.verifySignature(decoder.decode(signature))
    }

    fun pairingCode(
        publicKeyA: String,
        publicKeyB: String,
        nonceA: String,
        nonceB: String,
    ): String {
        val keys = listOf(publicKeyA, publicKeyB).sorted()
        val canonical = "domain=colink-lan-pairing-code\n" +
            "publicKeyA=${keys[0]}\n" +
            "publicKeyB=${keys[1]}\n" +
            "nonceA=$nonceA\n" +
            "nonceB=$nonceB"
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(canonical.toByteArray())
        }.digest()
        val value = BigInteger(1, digest.copyOfRange(0, 8)).mod(BigInteger.valueOf(1_000_000))
        return "%06d".format(value.toInt())
    }
}
