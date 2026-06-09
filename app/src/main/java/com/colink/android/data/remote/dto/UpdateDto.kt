package com.colink.android.data.remote.dto

import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.AppUpdateAsset
import kotlinx.serialization.Serializable

@Serializable
data class UpdateCheckResponseDto(
    val hasUpdate: Boolean,
    val latest: UpdateReleaseDto? = null,
)

@Serializable
data class UpdateReleaseDto(
    val version: String,
    val releaseNotes: String = "",
    val publishedAt: String,
    val assets: List<UpdateAssetDto> = emptyList(),
)

@Serializable
data class UpdateAssetDto(
    val name: String,
    val size: Long,
    val downloadUrl: String,
)

fun UpdateReleaseDto.toDomain(baseUrl: String): AppUpdate =
    AppUpdate(
        version = version,
        releaseNotes = releaseNotes,
        publishedAt = publishedAt,
        assets = assets.map { asset ->
            AppUpdateAsset(
                name = asset.name,
                size = asset.size,
                downloadUrl = resolveDownloadUrl(baseUrl, asset.downloadUrl),
            )
        },
    )

private fun resolveDownloadUrl(baseUrl: String, value: String): String =
    if (value.startsWith("http://") || value.startsWith("https://")) {
        value
    } else {
        com.colink.android.data.remote.api.apiEndpoint(baseUrl, value)
    }
