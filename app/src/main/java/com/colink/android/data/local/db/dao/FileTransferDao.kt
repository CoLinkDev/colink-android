package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.colink.android.data.local.db.entity.FileTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC LIMIT :limit")
    fun observeTransfers(limit: Int = 200): Flow<List<FileTransferEntity>>

    @Query("SELECT * FROM file_transfers WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getTransfer(sessionId: String): FileTransferEntity?

    @Upsert
    suspend fun upsert(transfer: FileTransferEntity)

    @Query("DELETE FROM file_transfers WHERE status NOT IN ('offered', 'sending', 'receiving', 'verifying')")
    suspend fun clearFinished()

    @Query(
        """
        UPDATE file_transfers
        SET status = 'failed',
            error = :reason,
            updatedAt = :updatedAt
        WHERE status IN ('offered', 'accepted', 'sending', 'receiving', 'verifying')
        """,
    )
    suspend fun failUnfinished(reason: String, updatedAt: Long)
}
