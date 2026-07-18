package com.colink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.colink.android.MainActivity
import com.colink.android.R
import com.colink.android.domain.model.CloudStatus
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.notification.EXTRA_TARGET_DEVICE_ID
import com.colink.android.util.CoLinkLog
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.colink.android.util.TransferSpeedTracker

private const val CHANNEL_ID = "colink_connection"
private const val NOTIFICATION_ID = 1001
private const val TRANSFER_NOTIFICATION_ID = 1002
private const val TRANSFER_NOTIFICATION_TAG_PREFIX = "transfer:"
private const val MAIN_REQUEST_CODE = 100
private const val TARGET_DEVICE_REQUEST_CODE_BASE = 10_000
private const val TARGET_DEVICE_REQUEST_CODE_MASK = 0x0fffffff
private const val ACTION_OPEN_MAIN = "com.colink.android.action.OPEN_MAIN"
private const val ACTION_OPEN_DEVICE = "com.colink.android.action.OPEN_DEVICE"
private const val TRANSFER_NOTIFICATION_UPDATE_MILLIS = 1_500L

@AndroidEntryPoint
class CoLinkService : Service() {
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var fileTransferRepository: FileTransferRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private val transferNotificationKeys = mutableMapOf<String, String>()
    private val lastTransferProgressNotificationAt = mutableMapOf<String, Long>()
    private var legacyTransferNotificationCleared = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        CoLinkLog.i("Service", "CoLink service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoLinkLog.i("Service", "CoLink service start command startId=$startId")
        startForegroundCompat(buildServiceNotification())
        connectionManager.start()
        startNotificationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        CoLinkLog.i("Service", "CoLink service destroyed")
        notificationJob?.cancel()
        connectionManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): Notification {
        val localizedContext = LocaleHelper.localized(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_MAIN
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            MAIN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(localizedContext.getString(R.string.service_notification_title))
            .setContentText(localizedContext.getString(R.string.service_notification_desc))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(localizedContext.getString(R.string.service_notification_desc)),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun buildTransferNotification(transfer: FileTransfer): Notification {
        val localizedContext = LocaleHelper.localized(this)
        val targetDeviceId = transfer.deviceId.takeIf { it.isNotBlank() }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_DEVICE
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TARGET_DEVICE_ID, targetDeviceId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            targetDeviceId?.let { TARGET_DEVICE_REQUEST_CODE_BASE + (it.hashCode() and TARGET_DEVICE_REQUEST_CODE_MASK) }
                ?: MAIN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val transferContent = transfer.toNotificationContent()
        val isOngoing = transfer.status == "sending" || transfer.status == "receiving" || transfer.status == "verifying"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(transferContent.title)
            .setContentText(transferContent.text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(transferContent.text),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setRequestPromotedOngoing(isOngoing)
            .setShowWhen(!isOngoing)
            .apply {
                val percent = transfer.transferPercent()
                if (transfer.status == "sending" || transfer.status == "receiving") {
                    setShortCriticalText("$percent%")
                } else if (transfer.status == "verifying") {
                    setShortCriticalText(localizedContext.getString(R.string.status_verifying))
                } else {
                    setShortCriticalText(transfer.statusLabel())
                }
                
                when {
                    transferContent.indeterminateProgress -> setProgress(100, 0, true)
                    transferContent.progress != null -> setProgress(100, transferContent.progress, false)
                    else -> setProgress(0, 0, false)
                }
            }
            .build()
    }

    private fun startNotificationUpdates() {
        if (notificationJob?.isActive == true) {
            return
        }
        notificationJob = scope.launch {
            launch {
                connectionManager.cloudState.collectLatest { state ->
                    CoLinkLog.d("Service", "cloud status changed status=${state.status}")
                }
            }
            launch {
                fileTransferRepository.transfers.collectLatest { transfers ->
                    updateTransferNotification(transfers)
                }
            }
        }
    }

    private fun updateTransferNotification(transfers: List<FileTransfer>) {
        clearLegacyTransferNotification()

        val activeTransfers = transfers
            .filter { it.status == "sending" || it.status == "receiving" || it.status == "verifying" }
        val activeSessionIds = activeTransfers.mapTo(mutableSetOf()) { it.sessionId }

        transferNotificationKeys.keys
            .filter { it !in activeSessionIds }
            .toList()
            .forEach(::cancelTransferNotification)

        transfers
            .filter { it.sessionId !in activeSessionIds }
            .forEach { TransferSpeedTracker.remove(it.sessionId) }

        activeTransfers.forEach(::updateSeparateTransferNotification)
    }

    private fun updateSeparateTransferNotification(transfer: FileTransfer) {
        val notificationKey = transfer.notificationKey()
        if (transferNotificationKeys[transfer.sessionId] == notificationKey) {
            return
        }

        val isProgress = transfer.status == "sending" || transfer.status == "receiving"
        if (isProgress) {
            val now = System.currentTimeMillis()
            val previousUpdateAt = lastTransferProgressNotificationAt[transfer.sessionId] ?: 0L
            if (now - previousUpdateAt < TRANSFER_NOTIFICATION_UPDATE_MILLIS) {
                return
            }
            lastTransferProgressNotificationAt[transfer.sessionId] = now
        }

        transferNotificationKeys[transfer.sessionId] = notificationKey

        NotificationManagerCompat.from(this).notify(
            transferNotificationTag(transfer.sessionId),
            TRANSFER_NOTIFICATION_ID,
            buildTransferNotification(transfer)
        )
    }

    private fun cancelTransferNotification(sessionId: String) {
        NotificationManagerCompat.from(this).cancel(
            transferNotificationTag(sessionId),
            TRANSFER_NOTIFICATION_ID,
        )
        transferNotificationKeys.remove(sessionId)
        lastTransferProgressNotificationAt.remove(sessionId)
    }

    private fun clearLegacyTransferNotification() {
        if (legacyTransferNotificationCleared) {
            return
        }
        NotificationManagerCompat.from(this).cancel(TRANSFER_NOTIFICATION_ID)
        legacyTransferNotificationCleared = true
    }

    private fun transferNotificationTag(sessionId: String): String =
        "$TRANSFER_NOTIFICATION_TAG_PREFIX$sessionId"

    private fun FileTransfer.notificationKey(): String {
        return if (isProgressTransfer()) {
            "transfer:$sessionId:$status:${transferPercent()}"
        } else {
            "transfer:$sessionId:$status:$updatedAt"
        }
    }

    private fun FileTransfer.isProgressTransfer(): Boolean =
        status == "sending" || status == "receiving"

    private fun FileTransfer.toNotificationContent(): TransferNotificationContent {
        val localizedContext = LocaleHelper.localized(this@CoLinkService)
        val title = when (status) {
            "sending" -> localizedContext.getString(R.string.service_transfer_sending_title)
            "receiving" -> localizedContext.getString(R.string.service_transfer_receiving_title)
            "verifying" -> localizedContext.getString(R.string.service_transfer_verifying_title)
            "completed" -> localizedContext.getString(R.string.service_transfer_completed_title)
            "failed" -> localizedContext.getString(R.string.service_transfer_failed_title)
            "cancelled", "rejected" -> localizedContext.getString(R.string.service_transfer_cancelled_title)
            else -> localizedContext.getString(R.string.service_notification_title)
        }
        val percent = transferPercent()
        val displayName = fileName.ifBlank { localizedContext.getString(R.string.unnamed_file) }
        
        val text = if (status == "sending" || status == "receiving") {
            val speed = TransferSpeedTracker.getSpeed(sessionId, transferredBytes)
            val speedText = TransferSpeedTracker.formatSpeed(speed)
            val transferredText = TransferSpeedTracker.formatBytes(transferredBytes)
            val totalText = TransferSpeedTracker.formatBytes(fileSize)
            "$displayName\n$percent% · $speedText · $transferredText / $totalText"
        } else if (status == "verifying") {
            displayName
        } else {
            "$displayName\n${statusLabel()}"
        }
        
        return TransferNotificationContent(
            title = title,
            text = text,
            progress = if (status == "sending" || status == "receiving") percent else null,
            indeterminateProgress = status == "verifying",
        )
    }

    private fun FileTransfer.transferPercent(): Int {
        if (fileSize <= 0) {
            return 0
        }
        return ((transferredBytes * 100) / fileSize).coerceIn(0, 100).toInt()
    }

    private fun FileTransfer.statusLabel(): String =
        LocaleHelper.localized(this@CoLinkService).let { localizedContext ->
            when (status) {
                "completed" -> localizedContext.getString(R.string.status_completed)
                "verifying" -> localizedContext.getString(R.string.status_verifying)
                "failed" -> localizedContext.getString(R.string.status_failed)
                "cancelled" -> localizedContext.getString(R.string.status_cancelled)
                "rejected" -> localizedContext.getString(R.string.status_rejected)
                else -> status
            }
        }

    private data class TransferNotificationContent(
        val title: String,
        val text: String,
        val progress: Int?,
        val indeterminateProgress: Boolean,
    )

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val localizedContext = LocaleHelper.localized(this)
        val manager = localizedContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        fun start(context: Context) {
            CoLinkLog.i("Service", "starting CoLink service")
            val intent = Intent(context, CoLinkService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
