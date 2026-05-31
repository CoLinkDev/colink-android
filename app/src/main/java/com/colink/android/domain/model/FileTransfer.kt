package com.colink.android.domain.model

data class FileTransfer(
    val sessionId: String,
    val deviceId: String,
    val direction: FileTransferDirection,
    val fileName: String,
    val fileSize: Long,
    val transferredBytes: Long,
    val totalChunks: Long,
    val status: String,
    val checksum: String,
    val route: String,
    val localUri: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class FileTransferDirection {
    Incoming,
    Outgoing,
}
