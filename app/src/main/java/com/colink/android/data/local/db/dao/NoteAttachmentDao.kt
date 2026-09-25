package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.colink.android.data.local.db.entity.NoteAttachmentEntity
import com.colink.android.data.local.db.entity.NoteSyncKvEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteAttachmentDao {
    @Query("SELECT * FROM note_attachments WHERE accountScope = :scope AND deleted = 0 ORDER BY createdAt ASC, attachmentId ASC")
    fun observeAttachments(scope: String): Flow<List<NoteAttachmentEntity>>

    @Query("SELECT * FROM note_attachments WHERE accountScope = :scope AND deleted = 0 ORDER BY createdAt ASC, attachmentId ASC")
    suspend fun listAttachments(scope: String): List<NoteAttachmentEntity>

    @Query("SELECT * FROM note_attachments WHERE accountScope = :scope")
    suspend fun listAllRows(scope: String): List<NoteAttachmentEntity>

    @Query("SELECT * FROM note_attachments WHERE accountScope = :scope AND attachmentId = :attachmentId LIMIT 1")
    suspend fun find(scope: String, attachmentId: String): NoteAttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScoped(attachment: NoteAttachmentEntity)

    @Transaction
    suspend fun upsert(scope: String, attachment: NoteAttachmentEntity) =
        upsertScoped(attachment.copy(accountScope = scope))

    @Query("DELETE FROM note_attachments WHERE accountScope = :scope AND attachmentId = :attachmentId")
    suspend fun delete(scope: String, attachmentId: String)

    @Query("DELETE FROM note_attachments WHERE accountScope = :scope")
    suspend fun deleteScope(scope: String)
}

@Dao
interface NoteSyncKvDao {
    @Query("SELECT value FROM note_sync_kv WHERE accountScope = :scope AND `key` = :key LIMIT 1")
    suspend fun read(scope: String, key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeScoped(entry: NoteSyncKvEntity)

    @Transaction
    suspend fun write(scope: String, entry: NoteSyncKvEntity) =
        writeScoped(entry.copy(accountScope = scope))

    @Query("DELETE FROM note_sync_kv WHERE accountScope = :scope AND `key` = :key")
    suspend fun delete(scope: String, key: String)

    @Query("DELETE FROM note_sync_kv WHERE accountScope = :scope")
    suspend fun deleteScope(scope: String)
}
