package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.colink.android.data.local.db.entity.NoteTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteTagDao {
    @Query("SELECT * FROM note_tags WHERE accountScope = :scope AND deleted = 0 AND syncState != :pendingDeleteState ORDER BY createdAt ASC, tagId ASC")
    fun observeTags(scope: String, pendingDeleteState: String): Flow<List<NoteTagEntity>>

    @Query("SELECT * FROM note_tags WHERE accountScope = :scope AND deleted = 0 AND syncState != :pendingDeleteState ORDER BY createdAt ASC, tagId ASC")
    suspend fun listTags(scope: String, pendingDeleteState: String): List<NoteTagEntity>

    @Query("SELECT * FROM note_tags WHERE accountScope = :scope")
    suspend fun listAllRows(scope: String): List<NoteTagEntity>

    @Query("SELECT * FROM note_tags WHERE accountScope = :scope AND tagId = :tagId LIMIT 1")
    suspend fun find(scope: String, tagId: String): NoteTagEntity?

    @Query("SELECT * FROM note_tags WHERE accountScope = :scope AND nameNormalized = :normalized AND deleted = 0 LIMIT 1")
    suspend fun findByNormalizedName(scope: String, normalized: String): NoteTagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScoped(tag: NoteTagEntity)

    @Transaction
    suspend fun upsert(scope: String, tag: NoteTagEntity) = upsertScoped(tag.copy(accountScope = scope))

    @Query("DELETE FROM note_tags WHERE accountScope = :scope AND tagId = :tagId")
    suspend fun delete(scope: String, tagId: String)

    @Query("DELETE FROM note_tags WHERE accountScope = :scope")
    suspend fun deleteScope(scope: String)
}
