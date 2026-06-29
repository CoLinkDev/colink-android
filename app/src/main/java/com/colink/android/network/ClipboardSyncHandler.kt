package com.colink.android.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.message.BusinessEnvelope
import com.colink.android.network.message.CLIPBOARD_SYNC_TYPE
import com.colink.android.network.message.ClipboardSyncPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private const val CLIPBOARD_MAX_BYTES = 1_048_576

@Singleton
class ClipboardSyncHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val settingsDataStore: SettingsDataStore,
    private val deviceRepository: DeviceRepository,
) {
    private val clipboardManager = context.getSystemService(ClipboardManager::class.java)
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var suppressedHash: String? = null
    private var lastSentHash: String? = null

    fun start(
        scope: CoroutineScope,
        sendCloudBroadcast: (BusinessEnvelope) -> Result<String>,
        sendViaLan: suspend (String, BusinessEnvelope) -> Result<String>,
        sendBusinessMessage: suspend (String, BusinessEnvelope) -> Result<String>,
    ) {
        stop()
        val newListener = ClipboardManager.OnPrimaryClipChangedListener {
            scope.launch {
                broadcastLocalClipboard(sendCloudBroadcast, sendViaLan, sendBusinessMessage)
            }
        }
        clipboardManager.addPrimaryClipChangedListener(newListener)
        listener = newListener
    }

    fun stop() {
        listener?.let(clipboardManager::removePrimaryClipChangedListener)
        listener = null
    }

    suspend fun receive(business: BusinessEnvelope) {
        if (!settingsDataStore.currentSettings().enableClipboardSync) return
        val payload = runCatching {
            json.decodeFromJsonElement(ClipboardSyncPayload.serializer(), business.payload)
        }.getOrNull() ?: return

        suppressedHash = payload.hash()
        when (payload.contentType) {
            "text/plain" -> setText(payload)
            "text/html" -> setHtml(payload)
            "image/png", "image/jpeg" -> setImage(payload)
        }
    }

    private fun setText(payload: ClipboardSyncPayload) {
        val content = payload.content ?: return
        if (content.toByteArray().size <= CLIPBOARD_MAX_BYTES) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("CoLink", content))
        }
    }

    private fun setHtml(payload: ClipboardSyncPayload) {
        val content = payload.content ?: return
        if (content.toByteArray().size <= CLIPBOARD_MAX_BYTES) {
            clipboardManager.setPrimaryClip(
                ClipData.newHtmlText("CoLink", htmlPlainText(content), content),
            )
        }
    }

    private fun setImage(payload: ClipboardSyncPayload) {
        val data = payload.data ?: return
        if (data.toByteArray().size > CLIPBOARD_MAX_BYTES * 2) return
        val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return
        if (bytes.size > CLIPBOARD_MAX_BYTES) return
        val extension = if (payload.contentType == "image/png") "png" else "jpg"
        val file = File(context.cacheDir, "clipboard-${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        clipboardManager.setPrimaryClip(ClipData.newUri(context.contentResolver, "CoLink", uri))
    }

    private suspend fun broadcastLocalClipboard(
        sendCloudBroadcast: (BusinessEnvelope) -> Result<String>,
        sendViaLan: suspend (String, BusinessEnvelope) -> Result<String>,
        sendBusinessMessage: suspend (String, BusinessEnvelope) -> Result<String>,
    ) {
        if (!settingsDataStore.currentSettings().enableClipboardSync) return
        val clip = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 } ?: return
        val payload = payload(clip) ?: return
        val hash = payload.hash()
        if (suppressedHash == hash) {
            suppressedHash = null
            return
        }
        if (lastSentHash == hash) return
        lastSentHash = hash

        val identity = deviceRepository.localDeviceIdentity()
        val business = BusinessEnvelope(
            type = CLIPBOARD_SYNC_TYPE,
            payload = json.encodeToJsonElement(payload),
        )
        val cloudSent = sendCloudBroadcast(business).isSuccess
        deviceRepository.devices.first()
            .filter { device ->
                device.deviceId != identity?.deviceId && if (cloudSent) {
                    device.lanAvailable && !device.online
                } else {
                    device.online || device.lanAvailable
                }
            }
            .forEach { device ->
                if (cloudSent) {
                    sendViaLan(device.deviceId, business)
                } else {
                    sendBusinessMessage(device.deviceId, business)
                }
            }
    }

    private fun payload(clip: ClipData): ClipboardSyncPayload? {
        val item = clip.getItemAt(0)
        item.uri?.let { uri ->
            val contentType = context.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.toString().substringAfterLast('.', ""))
            if (contentType == "image/png" || contentType == "image/jpeg") {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(CLIPBOARD_MAX_BYTES + 1)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                } ?: return null
                if (bytes.size <= CLIPBOARD_MAX_BYTES) {
                    return ClipboardSyncPayload(contentType, null, Base64.getEncoder().encodeToString(bytes))
                }
            }
        }

        item.htmlText?.takeIf { it.isNotBlank() && it.toByteArray().size <= CLIPBOARD_MAX_BYTES }
            ?.let { return ClipboardSyncPayload("text/html", it, null) }
        val text = item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        if (text.toByteArray().size > CLIPBOARD_MAX_BYTES) return null
        return ClipboardSyncPayload("text/plain", text, null)
    }

    private fun ClipboardSyncPayload.hash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(contentType.toByteArray())
        content?.let { digest.update(it.toByteArray()) }
        data?.let { digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun htmlPlainText(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
            .ifBlank { html }
}
