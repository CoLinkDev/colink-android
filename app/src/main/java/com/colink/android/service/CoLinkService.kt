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

private const val CHANNEL_ID = "colink_connection"
private const val NOTIFICATION_ID = 1001
private const val TRANSFER_RESULT_VISIBLE_MILLIS = 5_000L
private const val TRANSFER_NOTIFICATION_UPDATE_MILLIS = 1_500L

@AndroidEntryPoint
class CoLinkService : Service() {
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var fileTransferRepository: FileTransferRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var transferResultRestoreJob: Job? = null
    private var hasActiveTransfer = false
    private var lastNotificationKey: String? = null
    private var lastTransferProgressNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        CoLinkLog.i("Service", "CoLink service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoLinkLog.i("Service", "CoLink service start command startId=$startId")
        startForegroundCompat(buildNotification())
        connectionManager.start()
        startNotificationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        CoLinkLog.i("Service", "CoLink service destroyed")
        notificationJob?.cancel()
        transferResultRestoreJob?.cancel()
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
        lastNotificationKey = "normal"
    }

    private fun buildNotification(transfer: FileTransfer? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            transfer?.deviceId?.takeIf { it.isNotBlank() }?.let {
                putExtra(EXTRA_TARGET_DEVICE_ID, it)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val transferContent = transfer?.toNotificationContent()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(transferContent?.title ?: getString(R.string.service_notification_title))
            .setContentText(transferContent?.text ?: getString(R.string.service_notification_desc))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(transferContent?.text ?: getString(R.string.service_notification_desc)),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setShowWhen(false)
            .apply {
                when {
                    transferContent?.indeterminateProgress == true -> setProgress(100, 0, true)
                    transferContent?.progress != null -> setProgress(100, transferContent.progress, false)
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
        val activeTransfer = transfers
            .filter { it.status == "sending" || it.status == "receiving" || it.status == "verifying" }
            .maxByOrNull { it.updatedAt }
        if (activeTransfer != null) {
            hasActiveTransfer = true
            transferResultRestoreJob?.cancel()
            updateForegroundNotification(activeTransfer)
            return
        }

        hasActiveTransfer = false
        val latestFinished = transfers
            .filter { it.status in setOf("completed", "failed", "cancelled", "rejected") }
            .maxByOrNull { it.updatedAt }
        if (
            latestFinished == null ||
            System.currentTimeMillis() - latestFinished.updatedAt > TRANSFER_RESULT_VISIBLE_MILLIS
        ) {
            transferResultRestoreJob?.cancel()
            updateForegroundNotification(null)
            return
        }

        transferResultRestoreJob?.cancel()
        updateForegroundNotification(latestFinished)
        transferResultRestoreJob = scope.launch {
            delay(TRANSFER_RESULT_VISIBLE_MILLIS)
            if (!hasActiveTransfer) {
                updateForegroundNotification(null)
            }
        }
    }

    private fun updateForegroundNotification(transfer: FileTransfer?) {
        val notificationKey = transfer.notificationKey()
        if (lastNotificationKey == notificationKey) {
            return
        }
        if (transfer != null && transfer.isProgressTransfer()) {
            val now = System.currentTimeMillis()
            val previous = lastNotificationKey
            val sameTransfer = previous?.startsWith("transfer:${transfer.sessionId}:${transfer.status}:") == true
            if (sameTransfer && now - lastTransferProgressNotificationAt < TRANSFER_NOTIFICATION_UPDATE_MILLIS) {
                return
            }
            lastTransferProgressNotificationAt = now
        }
        lastNotificationKey = notificationKey
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(transfer))
    }

    private fun FileTransfer?.notificationKey(): String {
        if (this == null) {
            return "normal"
        }
        return if (isProgressTransfer()) {
            "transfer:$sessionId:$status:${transferPercent()}"
        } else {
            "transfer:$sessionId:$status:$updatedAt"
        }
    }

    private fun FileTransfer.isProgressTransfer(): Boolean =
        status == "sending" || status == "receiving"

    private fun FileTransfer.toNotificationContent(): TransferNotificationContent {
        val title = when (status) {
            "sending" -> getString(R.string.service_transfer_sending_title)
            "receiving" -> getString(R.string.service_transfer_receiving_title)
            "verifying" -> getString(R.string.service_transfer_verifying_title)
            "completed" -> getString(R.string.service_transfer_completed_title)
            "failed" -> getString(R.string.service_transfer_failed_title)
            "cancelled", "rejected" -> getString(R.string.service_transfer_cancelled_title)
            else -> getString(R.string.service_notification_title)
        }
        val percent = transferPercent()
        val detail = if (status == "sending" || status == "receiving") {
            getString(
                R.string.service_transfer_progress,
                fileName.ifBlank { getString(R.string.unnamed_file) },
                percent,
                formatBytes(transferredBytes),
                formatBytes(fileSize),
            )
        } else if (status == "verifying") {
            getString(
                R.string.service_transfer_verifying_text,
                fileName.ifBlank { getString(R.string.unnamed_file) },
            )
        } else {
            getString(
                R.string.service_transfer_result,
                fileName.ifBlank { getString(R.string.unnamed_file) },
                statusLabel(),
            )
        }
        return TransferNotificationContent(
            title = title,
            text = detail,
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
        when (status) {
            "completed" -> getString(R.string.status_completed)
            "verifying" -> getString(R.string.status_verifying)
            "failed" -> getString(R.string.status_failed)
            "cancelled" -> getString(R.string.status_cancelled)
            "rejected" -> getString(R.string.status_rejected)
            else -> status
        }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.coerceAtLeast(0).toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
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
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
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
