package com.colink.android.domain.model

data class CloudConnectionState(
    val status: CloudStatus = CloudStatus.Disconnected,
    val attempt: Int = 0,
    val lastError: String? = null,
) {
    val connected: Boolean get() = status == CloudStatus.Connected
}

enum class CloudStatus {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
}
