package com.colink.android.crypto

import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.EncryptedBusinessPayload
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

private const val AES_256_GCM = "x25519-aes-256-gcm"
private const val HKDF_SALT = "colink-lan-v1"
private const val HKDF_INFO = "encryption"

class LanSessionCrypto(
    private val json: Json,
    private val key: ByteArray,
    private val outboundRole: Byte,
) {
    private var counter = 0L
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun encrypt(message: BusinessEnvelope): EncryptedBusinessPayload {
        val nonce = nextNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        val ciphertext = cipher.doFinal(json.encodeToString(message).toByteArray())
        return EncryptedBusinessPayload(
            ciphertext = encoder.encodeToString(ciphertext),
            nonce = encoder.encodeToString(nonce),
        )
    }

    fun decrypt(payload: EncryptedBusinessPayload): BusinessEnvelope {
        val nonce = decoder.decode(payload.nonce)
        require(nonce.size == 12) { "LAN nonce length invalid" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce),
        )
        val plaintext = cipher.doFinal(decoder.decode(payload.ciphertext))
        return json.decodeFromString(BusinessEnvelope.serializer(), plaintext.decodeToString())
    }

    private fun nextNonce(): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = outboundRole
        ByteBuffer.wrap(nonce, 4, Long.SIZE_BYTES).putLong(counter)
        counter += 1
        return nonce
    }

    companion object {
        val supportedSuites: List<String> = listOf(AES_256_GCM)

        fun preferredSuite(): String = AES_256_GCM

        fun chooseSuite(
            localSupported: List<String>,
            peerSupported: List<String>,
            localIsInitiator: Boolean,
        ): String? {
            val ordered = if (localIsInitiator) localSupported else peerSupported
            val other = if (localIsInitiator) peerSupported else localSupported
            return ordered.firstOrNull { it == AES_256_GCM && other.contains(it) }
        }

        fun create(
            json: Json,
            privateKey: String,
            peerPublicKey: String,
            localIsInitiator: Boolean,
        ): LanSessionCrypto =
            LanSessionCrypto(
                json = json,
                key = deriveSessionKey(privateKey, peerPublicKey),
                outboundRole = if (localIsInitiator) 0 else 1,
            )

        private fun deriveSessionKey(privateKey: String, peerPublicKey: String): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(X25519PrivateKeyParameters(ed25519PrivateToX25519(privateKey), 0))
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(
                X25519PublicKeyParameters(ed25519PublicToX25519(peerPublicKey), 0),
                sharedSecret,
                0,
            )

            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(
                HKDFParameters(
                    sharedSecret,
                    HKDF_SALT.toByteArray(),
                    HKDF_INFO.toByteArray(),
                ),
            )
            val key = ByteArray(32)
            hkdf.generateBytes(key, 0, key.size)
            return key
        }

        private fun ed25519PrivateToX25519(privateKey: String): ByteArray {
            val seed = Base64.getDecoder().decode(privateKey)
            require(seed.size == 32) { "private key length invalid" }
            val digest = MessageDigest.getInstance("SHA-512").digest(seed)
            return digest.copyOfRange(0, 32).also { scalar ->
                scalar[0] = (scalar[0].toInt() and 248).toByte()
                scalar[31] = (scalar[31].toInt() and 127).toByte()
                scalar[31] = (scalar[31].toInt() or 64).toByte()
            }
        }

        private fun ed25519PublicToX25519(publicKey: String): ByteArray {
            val compressed = Base64.getDecoder().decode(publicKey)
            require(compressed.size == 32) { "public key length invalid" }

            val yBytes = compressed.copyOf()
            yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
            val y = littleEndianToBigInteger(yBytes)
            val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
            val one = BigInteger.ONE
            val u = one.add(y).mod(p)
                .multiply(one.subtract(y).mod(p).modInverse(p))
                .mod(p)
            return bigIntegerToLittleEndian(u)
        }

        private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger =
            BigInteger(1, bytes.reversedArray())

        private fun bigIntegerToLittleEndian(value: BigInteger): ByteArray {
            val bigEndian = value.toByteArray()
            val normalized = ByteArray(32)
            var source = bigEndian.size - 1
            var target = 0
            while (source >= 0 && target < normalized.size) {
                normalized[target] = bigEndian[source]
                source -= 1
                target += 1
            }
            return normalized
        }
    }
}
