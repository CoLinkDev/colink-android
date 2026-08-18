package com.colink.android.data.repository

import com.colink.android.data.local.db.dao.MessageDao
import com.colink.android.data.local.db.entity.MessageEntity
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.MessageDeliveryStatus
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
        deliveryStatus: MessageDeliveryStatus,
    ): Boolean =
        messageDao.insertIfAbsent(
            MessageEntity(
                messageId = messageId,
                deviceId = deviceId,
                direction = if (direction == MessageDirection.Incoming) "incoming" else "outgoing",
                text = text,
                route = route,
                deliveryStatus = deliveryStatus.name,
                createdAt = System.currentTimeMillis(),
            ),
        ) != -1L

    override suspend fun updateOutgoingTextMessageDelivery(
        messageId: String,
        deviceId: String,
        route: String,
        deliveryStatus: MessageDeliveryStatus,
    ) {
        messageDao.updateOutgoingDeliveryStatus(
            messageId = messageId,
            deviceId = deviceId,
            route = route,
            deliveryStatus = deliveryStatus.name,
        )
    }

    override suspend fun markOutgoingTextMessageReceiptReceived(
        messageId: String,
        deviceId: String,
        route: String,
    ): Boolean =
        messageDao.markOutgoingReceiptReceived(messageId, deviceId, route) > 0

    override suspend fun markSentOutgoingTextMessagesReceiptReceived(deviceId: String) {
        messageDao.markSentOutgoingMessagesReceiptReceived(deviceId)
    }
}
