package com.colink.android.domain.repository

import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.TextMessage
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    val messages: Flow<List<TextMessage>>

    suspend fun saveTextMessage(
        messageId: String,
        deviceId: String,
        direction: MessageDirection,
        text: String,
        route: String,
    )
}
