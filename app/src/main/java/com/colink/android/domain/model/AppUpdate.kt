package com.colink.android.domain.model

data class AppUpdate(
    val version: String,
    val releaseNotes: String,
    val publishedAt: String,
    val assets: List<AppUpdateAsset>,
)

data class AppUpdateAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)
