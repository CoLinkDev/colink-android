package com.colink.android.ui.navigation

data class LaunchTarget(
    val deviceId: String? = null,
    val token: Long = System.nanoTime(),
)
