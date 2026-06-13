package com.colink.android.network.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.colink.android.network.message.FileOfferPayload
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FILE_CHUNK_SIZE = 512 * 1024L

data class BuiltFileOffer(
    val payload: FileOfferPayload,
    val localUri: String,
)

suspend fun buildFileOffer(contentResolver: ContentResolver, uri: Uri): BuiltFileOffer =
    withContext(Dispatchers.IO) {
        val metadata = contentResolver.readFileMetadata(uri)
        val totalChunks = if (metadata.size == 0L) {
            0
        } else {
            (metadata.size + FILE_CHUNK_SIZE - 1) / FILE_CHUNK_SIZE
        }
        BuiltFileOffer(
            payload = FileOfferPayload(
                sessionId = UUID.randomUUID().toString(),
                fileName = metadata.name,
                fileSize = metadata.size,
                totalChunks = totalChunks,
                chunkSize = FILE_CHUNK_SIZE,
                checksum = contentResolver.fileChecksum(uri),
            ),
            localUri = uri.toString(),
        )
    }

private data class FileMetadata(
    val name: String,
    val size: Long,
)

private fun ContentResolver.readFileMetadata(uri: Uri): FileMetadata {
    var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "file"
    var size = -1L
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)?.ifBlank { name } ?: name
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    if (size < 0) {
        size = openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
    }
    require(size >= 0) { "file size is unavailable" }
    return FileMetadata(name = name, size = size)
}
