package com.colink.android.domain.model

data class TextMessage(
    val messageId: String,
    val deviceId: String,
    val direction: MessageDirection,
    val text: String,
    val route: String,
    val deliveryStatus: MessageDeliveryStatus,
    val createdAt: Long,
)

enum class MessageDirection {
    Incoming,
    Outgoing,
}

enum class MessageDeliveryStatus {
    Sending,
    Sent,
    ReceiptReceived,
}
