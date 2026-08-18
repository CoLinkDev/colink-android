package com.colink.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.colink.android.data.local.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT 200")
    fun observeMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Query(
        """
        UPDATE messages
        SET
            route = CASE WHEN deliveryStatus = 'ReceiptReceived' THEN route ELSE :route END,
            deliveryStatus = CASE
                WHEN deliveryStatus = 'ReceiptReceived' THEN deliveryStatus
                ELSE :deliveryStatus
            END
        WHERE messageId = :messageId AND deviceId = :deviceId AND direction = 'outgoing'
        """,
    )
    suspend fun updateOutgoingDeliveryStatus(
        messageId: String,
        deviceId: String,
        route: String,
        deliveryStatus: String,
    ): Int

    @Query(
        """
        UPDATE messages
        SET route = :route, deliveryStatus = 'ReceiptReceived'
        WHERE messageId = :messageId AND deviceId = :deviceId AND direction = 'outgoing'
        """,
    )
    suspend fun markOutgoingReceiptReceived(messageId: String, deviceId: String, route: String): Int

    @Query(
        """
        UPDATE messages
        SET deliveryStatus = 'ReceiptReceived'
        WHERE deviceId = :deviceId AND direction = 'outgoing' AND deliveryStatus = 'Sent'
        """,
    )
    suspend fun markSentOutgoingMessagesReceiptReceived(deviceId: String): Int

    @Query("DELETE FROM messages")
    suspend fun clear()
}
