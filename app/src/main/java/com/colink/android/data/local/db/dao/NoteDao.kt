package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.colink.android.data.local.db.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE accountScope = :scope AND deleted = 0 AND syncState != :pendingDeleteState ORDER BY updatedAt DESC, noteId ASC")
    fun observeNotes(scope: String, pendingDeleteState: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE accountScope = :scope AND deleted = 0 AND syncState != :pendingDeleteState ORDER BY updatedAt DESC, noteId ASC")
    suspend fun listNotes(scope: String, pendingDeleteState: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE accountScope = :scope")
    suspend fun listAllRows(scope: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE accountScope = :scope AND noteId = :noteId LIMIT 1")
    suspend fun find(scope: String, noteId: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScoped(note: NoteEntity)

    @Transaction
    suspend fun upsert(scope: String, note: NoteEntity) = upsertScoped(note.copy(accountScope = scope))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllScoped(notes: List<NoteEntity>)

    @Transaction
    suspend fun upsertAll(scope: String, notes: List<NoteEntity>) {
        upsertAllScoped(notes.map { it.copy(accountScope = scope) })
    }

    @Query("DELETE FROM notes WHERE accountScope = :scope AND noteId = :noteId")
    suspend fun delete(scope: String, noteId: String)

    @Query("DELETE FROM notes WHERE accountScope = :scope")
    suspend fun deleteScope(scope: String)

    @Query("UPDATE notes SET syncState = :state WHERE accountScope = :scope AND noteId = :noteId")
    suspend fun updateState(scope: String, noteId: String, state: String)

    @Query("SELECT * FROM notes WHERE accountScope = :scope AND (syncState = :pendingState OR syncState = :pendingDeleteState) ORDER BY updatedAt ASC")
    suspend fun listPending(scope: String, pendingState: String, pendingDeleteState: String): List<NoteEntity>

    @Transaction
    suspend fun replaceSyncedNotes(scope: String, notes: List<NoteEntity>) {
        upsertAll(scope, notes)
    }
}
