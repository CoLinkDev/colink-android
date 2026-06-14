package com.colink.android.domain.model

data class DeviceIdentity(
    val userId: String?,
    val deviceId: String,
    val name: String,
    val type: String,
    val publicKey: String,
    val privateKey: String,
    val cloudKeySyncPending: Boolean = false,
)
