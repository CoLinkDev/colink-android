package com.colink.android.data.local.db.entity

import androidx.room.Entity

@Entity(tableName = "note_sync_kv", primaryKeys = ["accountScope", "key"])
data class NoteSyncKvEntity(
    val accountScope: String = "",
    val key: String,
    val value: String,
)
