package com.colink.android.domain.model

data class AppSettings(
    val serverUrl: String,
    val autoStartOnBoot: Boolean = false,
    val lanDiscovery: Boolean = true,
    val notifications: Boolean = true,
    val deviceName: String = "",
    val language: String = "system",
    val castBoardResolutionWidth: Int = 0,
    val castBoardResolutionHeight: Int = 0,
)
