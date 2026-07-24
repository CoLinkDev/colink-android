package com.colink.android.data.repository

import android.os.Build
import com.colink.android.BuildConfig
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.remote.api.UpdateApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.requireData
import com.colink.android.data.remote.dto.toDomain
import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.repository.UpdateRepository
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepositoryImpl @Inject constructor(
    private val updateApi: UpdateApi,
    private val settingsDataStore: SettingsDataStore,
) : UpdateRepository {
    override suspend fun checkForUpdate(): Result<AppUpdate?> =
        runCatching {
            val settings = settingsDataStore.currentSettings()
            val architecture = selectUpdateArchitecture(Build.SUPPORTED_ABIS)
                ?: error("unsupported update architecture")
            val query = listOf(
                "platform" to "android",
                "arch" to architecture,
                "version" to BuildConfig.VERSION_NAME,
            ).joinToString("&") { (name, value) ->
                "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
            }
            val response = updateApi
                .checkUpdate(
                    apiEndpoint(settings.serverUrl, "/api/v1/update/check?$query"),
                )
                .requireData()

            if (!response.hasUpdate) {
                null
            } else {
                response.latest?.toDomain(settings.serverUrl)
            }
        }
}
