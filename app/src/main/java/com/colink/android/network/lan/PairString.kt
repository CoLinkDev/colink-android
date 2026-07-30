package com.colink.android.network.lan

import com.colink.android.domain.model.DeviceIdentity
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PAIR_STRING_RECOMMENDED_TTL_MILLIS = 60 * 60 * 1_000L

data class ParsedPairString(
    val raw: String,
    val version: PairStringVersion,
    val deviceId: String,
    val publicKey: String,
    val token: String,
    val expiresAt: Long?,
)

enum class PairStringVersion {
    V1,
    V2,
}

class PairStringException(
    val reason: String,
    message: String,
) : IllegalArgumentException(message)

@Singleton
class PairStringStore @Inject constructor(
    private val json: Json,
) {
    private val records = mutableMapOf<String, PairStringRecord>()

    @Synchronized
    fun issue(identity: DeviceIdentity, legacy: Boolean = false): String {
        val now = System.currentTimeMillis()
        val tokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val expiresAt = now + PAIR_STRING_RECOMMENDED_TTL_MILLIS
        records.entries.removeIf { (_, record) ->
            record.expiresAt <= now || record.state !in setOf(PairStringState.Active, PairStringState.Reserved)
        }
        records[token] = PairStringRecord(
            deviceId = identity.deviceId,
            publicKey = identity.publicKey,
            expiresAt = expiresAt,
        )
        if (legacy) {
            val payload = PairStringPayload(
                deviceId = identity.deviceId,
                publicKey = identity.publicKey,
                token = token,
                expiresAt = expiresAt,
                name = identity.name,
                platform = identity.type,
            )
            val data = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            return "colink://pair/v1?data=$data"
        }
        val publicKey = Base64.getDecoder().decode(identity.publicKey)
        require(publicKey.size == 32) { "Device identity is invalid" }
        val payload = ByteBuffer.allocate(80)
            .put(uuidBytes(UUID.fromString(identity.deviceId)))
            .put(publicKey)
            .put(tokenBytes)
            .array()
        return "colink://pair/v2?d=${Base64.getUrlEncoder().withoutPadding().encodeToString(payload)}"
    }

    @Synchronized
    fun reserve(value: String, identity: DeviceIdentity): ParsedPairString = reserve(parse(value), identity)

    @Synchronized
    fun reserve(pairString: ParsedPairString, identity: DeviceIdentity): ParsedPairString {
        val now = System.currentTimeMillis()
        val record = records[pairString.token]
            ?: throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        if (record.expiresAt <= now || pairString.expiresAt?.let { it <= now } == true) {
            record.state = PairStringState.Cancelled
            throw failure(REASON_PAIR_STRING_EXPIRED, "Pair string has expired")
        }
        if (record.deviceId != identity.deviceId ||
            pairString.deviceId != identity.deviceId ||
            (pairString.expiresAt != null && record.expiresAt != pairString.expiresAt) ||
            !samePublicKey(record.publicKey, pairString.publicKey) ||
            !samePublicKey(identity.publicKey, pairString.publicKey)
        ) {
            throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        }
        if (record.state != PairStringState.Active) {
            throw failure(REASON_PAIR_STRING_UNAVAILABLE, "Pair string is unavailable")
        }
        record.state = PairStringState.Reserved
        return pairString
    }

    @Synchronized
    fun consume(token: String) {
        records[token]?.let { record ->
            if (record.state == PairStringState.Reserved) {
                record.state = PairStringState.Consumed
            }
        }
    }

    @Synchronized
    fun cancel(token: String) {
        records[token]?.let { record ->
            if (record.state == PairStringState.Reserved) {
                record.state = PairStringState.Cancelled
            }
        }
    }

    @Synchronized
    fun clear() {
        records.clear()
    }

    fun parse(value: String): ParsedPairString {
        val uri = runCatching { URI(value) }.getOrElse {
            throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        }
        if (uri.scheme != "colink" || uri.authority != "pair") {
            throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        }
        val parameter = when (uri.path) {
            "/v1" -> "data"
            "/v2" -> "d"
            else -> throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        }
        val data = uri.rawQuery
            ?.takeIf { it.startsWith("$parameter=") && !it.contains('&') }
            ?.removePrefix("$parameter=")
            ?.takeIf { it.isNotBlank() }
            ?: throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        if (uri.path == "/v2") {
            val payload = runCatching { Base64.getUrlDecoder().decode(data) }.getOrElse {
                throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
            }
            if (payload.size != 80) {
                throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
            }
            val bytes = ByteBuffer.wrap(payload)
            val deviceId = UUID(bytes.long, bytes.long).toString()
            val publicKey = ByteArray(32).also(bytes::get)
            val token = ByteArray(32).also(bytes::get)
            return ParsedPairString(
                raw = value,
                version = PairStringVersion.V2,
                deviceId = deviceId,
                publicKey = Base64.getEncoder().encodeToString(publicKey),
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                expiresAt = null,
            )
        }
        val payload = runCatching {
            val bytes = Base64.getUrlDecoder().decode(data)
            json.decodeFromString(PairStringPayload.serializer(), bytes.toString(Charsets.UTF_8))
        }.getOrElse {
            throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        }
        if (payload.deviceId.isBlank() || payload.expiresAt <= 0L ||
            !isPublicKey(payload.publicKey) || !isToken(payload.token)
        ) {
            throw failure(REASON_PAIR_STRING_INVALID, "Pair string is invalid")
        }
        return ParsedPairString(value, PairStringVersion.V1, payload.deviceId, payload.publicKey, payload.token, payload.expiresAt)
    }

    private fun isPublicKey(value: String): Boolean =
        runCatching { Base64.getDecoder().decode(value).size == 32 }.getOrDefault(false)

    private fun isToken(value: String): Boolean =
        runCatching { Base64.getUrlDecoder().decode(value).size == 32 }.getOrDefault(false)

    private fun samePublicKey(left: String, right: String): Boolean = runCatching {
        MessageDigest.isEqual(Base64.getDecoder().decode(left), Base64.getDecoder().decode(right))
    }.getOrDefault(false)

    private fun uuidBytes(value: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()

    private fun failure(reason: String, message: String): PairStringException = PairStringException(reason, message)
}

@Serializable
private data class PairStringPayload(
    val deviceId: String,
    val publicKey: String,
    val token: String,
    val expiresAt: Long,
    val name: String? = null,
    val platform: String? = null,
)

private data class PairStringRecord(
    val deviceId: String,
    val publicKey: String,
    val expiresAt: Long,
    var state: PairStringState = PairStringState.Active,
)

private enum class PairStringState {
    Active,
    Reserved,
    Consumed,
    Cancelled,
}

internal const val REASON_PAIR_STRING_INVALID = "colink:pairing.pair_string_invalid.v1"
internal const val REASON_PAIR_STRING_EXPIRED = "colink:pairing.pair_string_expired.v1"
internal const val REASON_PAIR_STRING_UNAVAILABLE = "colink:pairing.pair_string_unavailable.v1"
internal const val REASON_PAIR_STRING_LOCAL_IDENTITY_UNAVAILABLE = "colink:pairing.local_identity_unavailable.v1"
internal const val REASON_PAIR_STRING_DEVICE_UNAVAILABLE = "colink:pairing.device_unavailable.v1"
internal const val REASON_PAIR_STRING_ALREADY_TRUSTED = "colink:pairing.already_trusted.v1"
