package com.colink.android.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.TextMessage

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val deviceId: String,
    val direction: String,
    val text: String,
    val route: String,
    val createdAt: Long,
) {
    fun toDomain(): TextMessage =
        TextMessage(
            messageId = messageId,
            deviceId = deviceId,
            direction = if (direction == "incoming") MessageDirection.Incoming else MessageDirection.Outgoing,
            text = text,
            route = route,
            createdAt = createdAt,
        )
}
