package com.colink.android.ui.navigation

data class LaunchTarget(
    val deviceId: String,
    val token: Long = System.nanoTime(),
)
