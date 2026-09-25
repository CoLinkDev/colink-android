package com.colink.android.domain.model

enum class NoteSyncState {
    Synced,
    Pending,
    PendingDelete,
    Conflict,
    ConflictDelete,
}

data class Note(
    val id: String,
    val title: String,
    val markdown: String,
    val tagIds: List<String>,
    val attachmentIds: List<String>,
    val revision: Long,
    val baseRevision: Long,
    val syncState: NoteSyncState,
    val conflictKind: String? = null,
    val conflictTitle: String? = null,
    val conflictMarkdown: String? = null,
    val conflictTagIds: List<String> = emptyList(),
    val conflictAttachmentIds: List<String> = emptyList(),
    val conflictRevision: Long? = null,
    val updatedAt: Long = 0,
)

data class NoteTag(
    val id: String,
    val name: String,
    val revision: Long,
    val syncState: NoteSyncState,
)

data class NoteAttachment(
    val id: String,
    val kind: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val syncState: String,
)

data class NotesStorage(
    val usedBytes: Long,
    val limitBytes: Long,
    val remainingBytes: Long,
    val attachmentBytes: Long,
    val markdownBytes: Long,
)

data class NotesSyncOutcome(
    val status: String,
    val pushedNotes: Int,
    val pushedTags: Int,
    val pushedAttachments: Int,
    val pulledNotes: Int,
    val pulledTags: Int,
    val conflicts: Int,
    val repairedReferences: Int,
)
