package com.colink.android.data.repository

import com.colink.android.data.local.db.dao.MessageDao
import com.colink.android.data.local.db.entity.MessageEntity
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.TextMessage
import com.colink.android.domain.repository.MessageRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
) : MessageRepository {
    override val messages: Flow<List<TextMessage>> =
        messageDao.observeMessages().map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveTextMessage(
        messageId: String,
        deviceId: String,
        direction: MessageDirection,
        text: String,
        route: String,
    ) {
        messageDao.insert(
            MessageEntity(
                messageId = messageId,
                deviceId = deviceId,
                direction = if (direction == MessageDirection.Incoming) "incoming" else "outgoing",
                text = text,
                route = route,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }
}
