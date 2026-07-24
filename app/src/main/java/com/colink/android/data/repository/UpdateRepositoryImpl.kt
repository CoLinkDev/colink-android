package com.colink.android.data.repository

import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.colink.android.BuildConfig
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.data.remote.api.UpdateApi
import com.colink.android.data.remote.api.apiEndpoint
import com.colink.android.data.remote.dto.requireData
import com.colink.android.data.remote.dto.toDomain
import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.AppUpdateAsset
import com.colink.android.domain.model.UpdateDownloadState
import com.colink.android.domain.repository.UpdateRepository
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class UpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val updateApi: UpdateApi,
    private val settingsDataStore: SettingsDataStore,
    private val okHttpClient: OkHttpClient,
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

    override fun downloadAndInstall(update: AppUpdate): Flow<UpdateDownloadState> =
        flow {
            val asset = update.assets.singleOrNull() ?: error("update does not contain a single installable asset")
            val apkFile = downloadApk(asset) { downloadedBytes, totalBytes ->
                emit(UpdateDownloadState.Downloading(downloadedBytes, totalBytes))
            }
            emit(UpdateDownloadState.Installing)
            startInstaller(apkFile)
        }.catch { error ->
            if (error is CancellationException) {
                throw error
            }
            CoLinkLog.w("Update", "update download failed", error)
            emit(UpdateDownloadState.Failed)
        }.flowOn(Dispatchers.IO)

    private suspend fun downloadApk(
        asset: AppUpdateAsset,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): java.io.File {
        val updateDirectory = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        check(updateDirectory.isDirectory) { "could not create update cache directory" }

        val apkFile = java.io.File(updateDirectory, "${safeApkName(asset.name)}.apk")
        val partialFile = java.io.File(updateDirectory, "${apkFile.name}.part")
        partialFile.delete()

        val request = Request.Builder().url(asset.downloadUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "update download failed with HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "update download has no body" }
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
            var downloadedBytes = 0L
            onProgress(downloadedBytes, totalBytes)

            body.byteStream().buffered().use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }
        }

        apkFile.delete()
        check(partialFile.renameTo(apkFile)) { "could not finalize update download" }
        return apkFile
    }

    private fun startInstaller(apkFile: java.io.File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun safeApkName(assetName: String): String =
        assetName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "colink-update" }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
