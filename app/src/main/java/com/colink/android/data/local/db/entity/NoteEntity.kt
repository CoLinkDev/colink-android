package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import com.colink.android.domain.model.Note
import com.colink.android.domain.model.NoteSyncState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun decodeIds(raw: String): List<String> =
    runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

fun encodeIds(ids: List<String>): String = json.encodeToString(ids)

@Entity(
    tableName = "notes",
    primaryKeys = ["accountScope", "noteId"],
    indices = [
        Index(
            value = ["accountScope", "updatedAt", "noteId"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
        ),
    ],
)
data class NoteEntity(
    val accountScope: String = "",
    val noteId: String,
    val title: String,
    val markdown: String,
    val tagIds: String,
    val attachmentIds: String,
    val revision: Long,
    val baseRevision: Long,
    val ancestorRevision: Long,
    val syncState: String,
    val conflictKind: String?,
    val conflictTitle: String?,
    val conflictMarkdown: String?,
    val conflictTagIds: String?,
    val conflictAttachmentIds: String?,
    val conflictRevision: Long?,
    val ancestorTitle: String,
    val ancestorMarkdown: String,
    val ancestorTagIds: String,
    val ancestorAttachmentIds: String,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain(): Note =
        Note(
            id = noteId,
            title = title,
            markdown = markdown,
            tagIds = decodeIds(tagIds),
            attachmentIds = decodeIds(attachmentIds),
            revision = revision,
            baseRevision = baseRevision,
            syncState = NoteSyncState.entries.firstOrNull { it.name == syncState } ?: NoteSyncState.Pending,
            conflictKind = conflictKind,
            conflictTitle = conflictTitle,
            conflictMarkdown = conflictMarkdown,
            conflictTagIds = conflictTagIds?.let { decodeIds(it) } ?: emptyList(),
            conflictAttachmentIds = conflictAttachmentIds?.let { decodeIds(it) } ?: emptyList(),
            conflictRevision = conflictRevision,
            updatedAt = updatedAt,
        )

    companion object {
        fun newPending(
            noteId: String,
            title: String,
            markdown: String,
            tagIds: List<String>,
            attachmentIds: List<String>,
            now: Long,
        ): NoteEntity =
            NoteEntity(
                accountScope = "",
                noteId = noteId,
                title = title,
                markdown = markdown,
                tagIds = encodeIds(tagIds),
                attachmentIds = encodeIds(attachmentIds),
                revision = 0,
                baseRevision = 0,
                ancestorRevision = 0,
                syncState = NoteSyncState.Pending.name,
                conflictKind = null,
                conflictTitle = null,
                conflictMarkdown = null,
                conflictTagIds = null,
                conflictAttachmentIds = null,
                conflictRevision = null,
                ancestorTitle = "",
                ancestorMarkdown = "",
                ancestorTagIds = "[]",
                ancestorAttachmentIds = "[]",
                deleted = false,
                createdAt = now,
                updatedAt = now,
            )
    }
}
