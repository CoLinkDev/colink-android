package com.colink.android.domain.model

data class Device(
    val deviceId: String,
    val name: String,
    val type: String,
    val online: Boolean,
    val lastSeen: String?,
    val publicKey: String,
    val publicKeyUpdatedAt: Long? = null,
    val localIp: String? = null,
    val localPort: Int? = null,
    val cloudAvailable: Boolean = online,
    val lanAvailable: Boolean = false,
    val activeRoute: String? = null,
    val deviceSources: List<String> = emptyList(),
    val securityState: String = "unverified",
)
