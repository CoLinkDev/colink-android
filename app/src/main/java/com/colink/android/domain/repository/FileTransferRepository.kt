package com.colink.android.domain.repository

import com.colink.android.domain.model.FileTransfer
import kotlinx.coroutines.flow.Flow

interface FileTransferRepository {
    val transfers: Flow<List<FileTransfer>>

    suspend fun save(transfer: FileTransfer)

    suspend fun updateActiveProgress(
        sessionId: String,
        transferredBytes: Long,
        updatedAt: Long,
    ): Boolean

    suspend fun get(sessionId: String): FileTransfer?

    suspend fun clearFinished()

    suspend fun failUnfinished(reason: String)
}
