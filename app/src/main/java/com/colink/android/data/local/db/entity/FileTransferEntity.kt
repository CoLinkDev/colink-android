package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val sessionId: String,
    val deviceId: String,
    val direction: String,
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
) {
    fun toDomain(): FileTransfer =
        FileTransfer(
            sessionId = sessionId,
            deviceId = deviceId,
            direction = if (direction == "incoming") {
                FileTransferDirection.Incoming
            } else {
                FileTransferDirection.Outgoing
            },
            fileName = fileName,
            fileSize = fileSize,
            transferredBytes = transferredBytes,
            totalChunks = totalChunks,
            status = status,
            checksum = checksum,
            route = route,
            localUri = localUri,
            error = error,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

fun FileTransfer.toEntity(): FileTransferEntity =
    FileTransferEntity(
        sessionId = sessionId,
        deviceId = deviceId,
        direction = if (direction == FileTransferDirection.Incoming) "incoming" else "outgoing",
        fileName = fileName,
        fileSize = fileSize,
        transferredBytes = transferredBytes,
        totalChunks = totalChunks,
        status = status,
        checksum = checksum,
        route = route,
        localUri = localUri,
        error = error,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
