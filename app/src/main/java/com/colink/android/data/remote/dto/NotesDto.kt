package com.colink.android.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class NoteDto(
    val noteId: String,
    val title: String,
    val markdown: String,
    val tagIds: List<String>,
    val attachments: List<AttachmentDto>,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class NoteSummaryDto(
    val noteId: String,
    val title: String,
    val tagIds: List<String>,
    val attachmentCount: Int,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class AttachmentDto(
    val attachmentId: String,
    val kind: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val createdAt: String,
)

@Serializable
data class TagDto(
    val tagId: String,
    val name: String,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class NoteCreateRequestDto(
    val noteId: String,
    val title: String,
    val markdown: String,
    val tagIds: List<String>,
    val attachmentIds: List<String>,
)

@Serializable
data class NoteUpdateRequestDto(
    val baseRevision: Long,
    val title: String,
    val markdown: String,
    val tagIds: List<String>,
    val attachmentIds: List<String>,
)

@Serializable
data class TagCreateRequestDto(
    val tagId: String,
    val name: String,
)

@Serializable
data class TagUpdateRequestDto(
    val baseRevision: Long,
    val name: String,
)

@Serializable
data class NoteDeleteDto(
    val noteId: String,
    val revision: Long,
    val deletedAt: String? = null,
)

@Serializable
data class TagDeleteDto(
    val tagId: String,
    val revision: Long,
    val deletedAt: String? = null,
)

@Serializable
data class NotesListDto(
    val notes: List<NoteSummaryDto>,
    val nextPageToken: String? = null,
)

@Serializable
data class TagListDto(
    val tags: List<TagDto>,
)

@Serializable
data class SnapshotDto(
    val notes: List<NoteDto>,
    val tags: List<TagDto>,
    val nextPageToken: String? = null,
    val cursor: String,
)

@Serializable
data class ChangeEntryDto(
    val type: String,
    val operation: String,
    val note: NoteDto? = null,
    val tag: TagDto? = null,
    val noteId: String? = null,
    val tagId: String? = null,
    val revision: Long? = null,
)

@Serializable
data class ChangesDto(
    val changes: List<ChangeEntryDto>,
    val nextCursor: String,
    val hasMore: Boolean,
)

@Serializable
data class StorageDto(
    val usedBytes: Long,
    val limitBytes: Long,
    val remainingBytes: Long,
    val attachmentBytes: Long,
    val markdownBytes: Long,
    val maxAttachmentBytes: Long,
    val maxMarkdownBytes: Long,
)

@Serializable
data class AttachmentReferencesDto(
    val noteIds: List<String>,
    val nextPageToken: String? = null,
)
