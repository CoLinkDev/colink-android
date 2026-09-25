package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import com.colink.android.domain.model.NoteSyncState

@Entity(
    tableName = "note_tags",
    primaryKeys = ["accountScope", "tagId"],
    indices = [Index(value = ["accountScope", "nameNormalized"])],
)
data class NoteTagEntity(
    val accountScope: String = "",
    val tagId: String,
    val name: String,
    val nameNormalized: String,
    val revision: Long,
    val baseRevision: Long,
    val syncState: String,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain(): com.colink.android.domain.model.NoteTag =
        com.colink.android.domain.model.NoteTag(
            id = tagId,
            name = name,
            revision = revision,
            syncState = NoteSyncState.entries.firstOrNull { it.name == syncState } ?: NoteSyncState.Pending,
        )
}
