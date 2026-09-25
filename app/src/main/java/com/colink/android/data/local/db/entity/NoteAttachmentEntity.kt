package com.colink.android.data.local.db.entity

import androidx.room.Entity

@Entity(tableName = "note_attachments", primaryKeys = ["accountScope", "attachmentId"])
data class NoteAttachmentEntity(
    val accountScope: String = "",
    val attachmentId: String,
    val kind: String,
    val fileName: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
    val syncState: String,
    val deleted: Boolean,
    val createdAt: Long,
)
