package com.colink.android.domain.model

data class AppSettings(
    val serverUrl: String,
    val autoStartOnBoot: Boolean = false,
    val deviceName: String = "",
    val language: String = "system",
)
