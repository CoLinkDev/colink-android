package com.colink.android.domain.repository

import com.colink.android.domain.model.Note
import com.colink.android.domain.model.NoteAttachment
import com.colink.android.domain.model.NoteTag
import com.colink.android.domain.model.NotesStorage
import com.colink.android.domain.model.NotesSyncOutcome
import java.io.File
import kotlinx.coroutines.flow.Flow

interface NotesRepository {
    val notes: Flow<List<Note>>
    val tags: Flow<List<NoteTag>>

    suspend fun refreshLocals()

    suspend fun getNote(noteId: String): Note?

    suspend fun upsertNote(
        noteId: String?,
        title: String,
        markdown: String,
        tagIds: List<String>,
        attachmentIds: List<String>,
    ): Result<Note>

    suspend fun deleteNote(noteId: String): Result<Unit>

    suspend fun createTag(name: String): Result<NoteTag>

    suspend fun renameTag(tagId: String, name: String): Result<NoteTag>

    suspend fun deleteTag(tagId: String): Result<Unit>

    suspend fun stageAttachment(uri: String, kind: String, fileName: String): Result<NoteAttachment>

    suspend fun listAttachments(): List<NoteAttachment>

    suspend fun removeAttachment(attachmentId: String): Result<Unit>

    suspend fun ensureAttachmentCached(attachmentId: String): Result<File>

    suspend fun fetchStorage(): Result<NotesStorage>

    suspend fun sync(): Result<NotesSyncOutcome>

    suspend fun resolveConflict(
        noteId: String,
        resolution: String,
        mergedTitle: String? = null,
        mergedMarkdown: String? = null,
    ): Result<Note>
}
