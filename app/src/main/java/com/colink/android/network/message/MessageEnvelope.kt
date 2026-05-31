package com.colink.android.network.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val TEXT_MESSAGE_TYPE = "message.v1.text"
const val CLIPBOARD_SYNC_TYPE = "clipboard.v1.sync"
const val FILE_OFFER_TYPE = "file.v2.offer"
const val FILE_ACCEPT_TYPE = "file.v2.accept"
const val FILE_REJECT_TYPE = "file.v2.reject"
const val FILE_CANCEL_TYPE = "file.v2.cancel"
const val FILE_READY_TYPE = "file.v2.ready"
const val FILE_CHUNK_TYPE = "file.v2.chunk"
const val FILE_ACK_TYPE = "file.v2.ack"
const val FILE_RETRANSMIT_TYPE = "file.v2.retransmit"
const val FILE_DONE_TYPE = "file.v2.done"

@Serializable
data class BusinessEnvelope(
    @SerialName("type") val type: String,
    val payload: JsonElement,
)

@Serializable
data class CloudClientEnvelope(
    val id: String,
    @SerialName("type") val type: String,
    val to: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class CloudServerEnvelope(
    val id: String? = null,
    @SerialName("type") val type: String,
    val from: String? = null,
    val to: String? = null,
    val payload: JsonElement? = null,
    val timestamp: Long? = null,
)

@Serializable
data class TextMessagePayload(
    val messageId: String,
    val text: String,
)

@Serializable
data class ClipboardSyncPayload(
    val contentType: String,
    val content: String? = null,
    val data: String? = null,
)

@Serializable
data class FileOfferPayload(
    val sessionId: String,
    val fileName: String,
    val fileSize: Long,
    val totalChunks: Long,
    val chunkSize: Long,
    val checksum: String,
)

@Serializable
data class FileAcceptPayload(
    val sessionId: String,
    val transferToken: String,
)

@Serializable
data class FileRejectPayload(
    val sessionId: String,
    val reason: String,
)

@Serializable
data class FileCancelPayload(
    val sessionId: String,
    val reason: String,
)

@Serializable
data class FileReadyPayload(
    val sessionId: String,
)

@Serializable
data class FileChunkPayload(
    val sessionId: String,
    val chunkIndex: Long,
    val data: String,
)

@Serializable
data class FileAckPayload(
    val sessionId: String,
    val nextExpectedIndex: Long,
)

@Serializable
data class FileRetransmitPayload(
    val sessionId: String,
    val chunkIndex: Long,
)

@Serializable
data class FileDonePayload(
    val sessionId: String,
    val success: Boolean,
    val reason: String? = null,
)

@Serializable
data class DeviceOnlinePayload(
    val name: String,
    @SerialName("type") val type: String,
)

@Serializable
data class PeerEnvelope(
    @SerialName("type") val type: String,
    val payload: JsonElement,
)

@Serializable
data class HandshakeProofPayload(
    val deviceId: String,
    val publicKey: String,
    val name: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String,
)

@Serializable
data class HandshakeAcceptPayload(
    val deviceId: String,
)

@Serializable
data class HandshakeRejectPayload(
    val reason: String,
)

@Serializable
data class BusinessNegotiatePayload(
    val supported: List<String>,
    val preferred: String,
)

@Serializable
data class EncryptedBusinessPayload(
    val ciphertext: String,
    val nonce: String,
)

@Serializable
data class SwimEnvelope(
    @SerialName("type") val type: String,
    val payload: SwimPayload,
)

@Serializable
data class SwimPayload(
    val seq: Long,
    val from: String,
    val target: String? = null,
    val gossip: List<SwimGossip>,
)

@Serializable
data class SwimGossip(
    val deviceId: String,
    val state: String,
    val incarnation: Long,
)
