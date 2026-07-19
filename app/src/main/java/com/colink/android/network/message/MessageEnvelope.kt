package com.colink.android.network.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val LAN_PROTOCOL_VERSION = "1.2.0"
const val BUSINESS_PROTOCOL_VERSION = "1.7.0"
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
const val MUSIC_TRACK_TYPE = "music.v1.track"
const val MUSIC_LYRIC_TYPE = "music.v1.lyric"
const val MUSIC_PROGRESS_TYPE = "music.v1.progress"
const val MUSIC_ALIVE_TYPE = "music.v1.alive"
const val MUSIC_REQUEST_TYPE = "music.v1.request"
const val SYSINFO_STATS_TYPE = "sysinfo.v1.stats"
const val SYSINFO_ALIVE_TYPE = "sysinfo.v1.alive"
const val FS_ROOTS_TYPE = "fs.v1.roots"
const val FS_ROOTS_RESULT_TYPE = "fs.v1.roots-result"
const val FS_LIST_TYPE = "fs.v1.list"
const val FS_LIST_RESULT_TYPE = "fs.v1.list-result"
const val FS_STAT_TYPE = "fs.v1.stat"
const val FS_STAT_RESULT_TYPE = "fs.v1.stat-result"
const val FS_DOWNLOAD_TYPE = "fs.v1.download"
const val FS_ERROR_TYPE = "fs.v1.error"
const val SYSTEM_CONTROL_COMMAND_TYPE = "system-control.v1.command"
const val SYSTEM_CONTROL_QUERY_TYPE = "system-control.v1.query"
const val SYSTEM_CONTROL_RESULT_TYPE = "system-control.v1.result"
const val SYSTEM_CONTROL_ERROR_TYPE = "system-control.v1.error"

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
    val correlationId: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class CloudServerEnvelope(
    val id: String? = null,
    @SerialName("type") val type: String,
    val from: String? = null,
    val to: String? = null,
    val correlationId: String? = null,
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
    val message: String,
    val details: JsonElement? = null,
)

@Serializable
data class FileCancelPayload(
    val sessionId: String,
    val reason: String,
    val message: String,
    val details: JsonElement? = null,
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
    val message: String? = null,
    val details: JsonElement? = null,
)

@Serializable
data class MusicTrackPayload(
    val trackId: String? = null,
    val title: String? = null,
    val artists: List<String>? = null,
    val album: String? = null,
    val source: String? = null,
    val coverUrl: String? = null,
    val coverData: String? = null,
    val duration: Long? = null,
)

@Serializable
data class MusicLyricLinePayload(
    val time: Long,
    val text: String,
)

@Serializable
data class MusicLyricPayload(
    val trackId: String,
    val lines: List<MusicLyricLinePayload>? = null,
    val translatedLines: List<MusicLyricLinePayload>? = null,
)

@Serializable
data class MusicProgressPayload(
    val trackId: String,
    val progress: Long,
    val paused: Boolean,
)

@Serializable
object MusicAlivePayload

@Serializable
object MusicRequestPayload

@Serializable
data class SysInfoStatsPayload(
    val cpu: Double,
    val mem: Double,
    val gpu: Double? = null,
    @SerialName("net_up") val netUp: Double? = null,
    @SerialName("net_down") val netDown: Double? = null,
    @SerialName("disk_read") val diskRead: Double? = null,
    @SerialName("disk_write") val diskWrite: Double? = null,
)

@Serializable
object SysInfoAlivePayload

@Serializable
data class DeviceOnlinePayload(
    val name: String,
    @SerialName("type") val type: String,
    val businessVersion: String,
)

@Serializable
data class ProtocolHelloEnvelope(
    @SerialName("type") val type: String,
    val payload: ProtocolHelloPayload,
)

@Serializable
data class ProtocolHelloPayload(
    val deviceId: String,
    val protocolVersion: String,
    val extensions: JsonElement,
)

@Serializable
data class ProtocolHelloAckEnvelope(
    @SerialName("type") val type: String,
    val payload: VersionAckPayload,
)

@Serializable
data class VersionAckPayload(
    val compatible: Boolean,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
data class LanEnvelope(
    val id: String,
    @SerialName("type") val type: String,
    val from: String,
    val to: String,
    val seq: Long,
    val timestamp: Long,
    val correlationId: String? = null,
    val payload: JsonElement,
)

@Serializable
data class AuthChallengePayload(
    val nonce: String,
)

@Serializable
data class AuthResponsePayload(
    val signature: String,
)

@Serializable
data class PairingIdentityPayload(
    val publicKey: String,
    val name: String,
    val nonce: String,
)

@Serializable
object EmptyPayload

@Serializable
data class LanRejectPayload(
    val reason: String,
    val message: String,
    val details: JsonElement? = null,
)

@Serializable
data class BusinessNegotiatePayload(
    val supported: List<String>,
    val preferred: String,
)

@Serializable
data class BusinessVersionPayload(
    val businessVersion: String,
)

@Serializable
data class BusinessVersionAckPayload(
    val compatible: Boolean,
    val reason: String? = null,
    val message: String? = null,
)

@Serializable
data class BusinessKeyExchangePayload(
    val ephemeralPublicKey: String,
    val signature: String,
)

@Serializable
object FsRootsPayload

@Serializable
data class FsRootsResultPayload(
    val roots: List<FsRootEntry>,
)

@Serializable
data class FsRootEntry(
    val path: String,
    val label: String? = null,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
)

@Serializable
data class FsListPayload(
    val path: String,
    val offset: Long? = null,
    val limit: Long? = null,
)

@Serializable
data class FsListResultPayload(
    val path: String,
    val entries: List<FsEntry>,
    val total: Long,
    val offset: Long,
    val hasMore: Boolean,
)

@Serializable
data class FsEntry(
    val name: String,
    val kind: String,
    val size: Long? = null,
    val modified: Long? = null,
    val created: Long? = null,
    val readonly: Boolean,
    val hidden: Boolean,
)

@Serializable
data class FsStatPayload(
    val path: String,
)

@Serializable
data class FsStatResultPayload(
    val path: String,
    val exists: Boolean,
    val kind: String? = null,
    val size: Long? = null,
    val modified: Long? = null,
    val created: Long? = null,
    val readonly: Boolean? = null,
    val hidden: Boolean? = null,
)

@Serializable
data class FsDownloadPayload(
    val path: String,
)

@Serializable
data class FsErrorPayload(
    val reason: String,
    val message: String,
    val details: JsonElement? = null,
)

enum class SystemControlAction(val wireValue: String) {
    Sleep("sleep"),
    Shutdown("shutdown"),
    Lock("lock"),
    Play("play"),
    Pause("pause"),
    Next("next"),
    Previous("previous"),
    SetVolume("set-volume"),
    Mute("mute"),
    ;

    val minimumBusinessProtocolMinor: Int
        get() = when (this) {
            Sleep,
            Shutdown,
            Lock,
            -> 5

            Play,
            Pause,
            Next,
            Previous,
            SetVolume,
            Mute,
            -> 6
        }

    val requiresVolume: Boolean
        get() = this == SetVolume

    companion object {
        fun fromWireValue(value: String): SystemControlAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

@Serializable
data class SystemControlCommandPayload(
    val action: String,
    val volume: Int? = null,
)

@Serializable
data class SystemControlQueryPayload(
    val fields: List<String>,
)

@Serializable
data class SystemControlResultPayload(
    val volume: Int? = null,
    val muted: Boolean? = null,
    val playback: String? = null,
)

@Serializable
data class SystemControlErrorPayload(
    val reason: String,
    val message: String,
    val details: JsonElement? = null,
)

@Serializable
data class BusinessKeyExchangeNoncePayload(
    val nonce: String,
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
    val incarnation: Long? = null,
    val target: String? = null,
    val gossip: List<SwimGossip>,
)

@Serializable
data class SwimGossip(
    val deviceId: String,
    val state: String,
    val incarnation: Long,
)
