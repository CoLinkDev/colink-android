package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "diagnostic_logs",
    indices = [Index(value = ["createdAt"])],
)
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val level: String,
    val component: String,
    val message: String,
)
