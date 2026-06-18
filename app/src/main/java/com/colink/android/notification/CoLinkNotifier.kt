package com.colink.android.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.colink.android.MainActivity
import com.colink.android.R
import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.notification.ACTION_FILE_TRANSFER_ACCEPT
import com.colink.android.notification.ACTION_FILE_TRANSFER_REJECT
import com.colink.android.notification.ACTION_LAN_PAIRING_ACCEPT
import com.colink.android.notification.ACTION_LAN_PAIRING_REJECT
import com.colink.android.notification.EXTRA_FILE_SESSION_ID
import com.colink.android.notification.EXTRA_PAIRING_REQUEST_ID
import com.colink.android.notification.EXTRA_TARGET_DEVICE_ID
import com.colink.android.util.CoLinkLog
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val EVENT_CHANNEL_ID = "colink_events"
private const val PAIRING_NOTIFICATION_ID = 2001
private const val MAIN_REQUEST_CODE = 100
private const val TARGET_DEVICE_REQUEST_CODE_BASE = 10_000
private const val TARGET_DEVICE_REQUEST_CODE_MASK = 0x0fffffff
private const val ACTION_OPEN_MAIN = "com.colink.android.action.OPEN_MAIN"
private const val ACTION_OPEN_DEVICE = "com.colink.android.action.OPEN_DEVICE"

@Singleton
class CoLinkNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensureEventChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val localizedContext = LocaleHelper.localized(context)
        val manager = localizedContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            EVENT_CHANNEL_ID,
            localizedContext.getString(R.string.notification_events_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
    }

    suspend fun notifyEvent(title: String, text: String) {
        ensureEventChannel()
        if (!canNotify("event")) {
            return
        }
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(context).notify(uniqueNotificationId(), notification)
        CoLinkLog.d("Notification", "posted event notification title=$title")
    }

    suspend fun notifyMessageReceived(deviceId: String, deviceName: String, text: String) {
        ensureEventChannel()
        if (!canNotify("message")) {
            return
        }
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(deviceName)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text),
            )
            .setContentIntent(mainActivityIntent(deviceId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(context).notify(uniqueNotificationId(), notification)
        CoLinkLog.d("Notification", "posted message notification device=${CoLinkLog.shortId(deviceId)}")
    }

    suspend fun notifyFileOffer(
        sessionId: String,
        deviceId: String,
        deviceName: String,
        fileName: String,
    ) {
        val localizedContext = LocaleHelper.localized(context)
        ensureEventChannel()
        if (!canNotify("file offer")) {
            return
        }
        val body = localizedContext.getString(R.string.notification_file_offer_body, deviceName, fileName)
        val notificationId = fileOfferNotificationId(sessionId)
        val acceptIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            Intent(context, com.colink.android.service.FileTransferActionReceiver::class.java).apply {
                action = ACTION_FILE_TRANSFER_ACCEPT
                putExtra(EXTRA_FILE_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            Intent(context, com.colink.android.service.FileTransferActionReceiver::class.java).apply {
                action = ACTION_FILE_TRANSFER_REJECT
                putExtra(EXTRA_FILE_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(deviceName)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body),
            )
            .setContentIntent(mainActivityIntent(deviceId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(
                android.R.drawable.ic_delete,
                localizedContext.getString(R.string.reject_btn),
                rejectIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_save,
                localizedContext.getString(R.string.accept_btn),
                acceptIntent,
            )
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        CoLinkLog.i(
            "Notification",
            "posted file offer notification session=${CoLinkLog.shortId(sessionId)} device=${CoLinkLog.shortId(deviceId)}",
        )
    }

    fun cancelFileOffer(sessionId: String) {
        NotificationManagerCompat.from(context).cancel(fileOfferNotificationId(sessionId))
    }

    suspend fun notifyLanPairingRequest(request: LanPairingRequest) {
        val localizedContext = LocaleHelper.localized(context)
        ensureEventChannel()
        if (!canNotify("lan pairing")) {
            return
        }
        val deviceName = request.name.ifBlank { request.deviceId }
        val title = localizedContext.getString(R.string.notification_lan_pairing_title)
        val text = localizedContext.getString(R.string.notification_lan_pairing_text, deviceName, request.code)
        val body = localizedContext.getString(R.string.notification_lan_pairing_body, deviceName, request.code)
        val acceptIntent = PendingIntent.getBroadcast(
            context,
            PAIRING_NOTIFICATION_ID,
            Intent(context, com.colink.android.service.LanPairingActionReceiver::class.java).apply {
                action = ACTION_LAN_PAIRING_ACCEPT
                putExtra(EXTRA_PAIRING_REQUEST_ID, request.requestId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectIntent = PendingIntent.getBroadcast(
            context,
            PAIRING_NOTIFICATION_ID + 1,
            Intent(context, com.colink.android.service.LanPairingActionReceiver::class.java).apply {
                action = ACTION_LAN_PAIRING_REJECT
                putExtra(EXTRA_PAIRING_REQUEST_ID, request.requestId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body),
            )
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(
                android.R.drawable.ic_delete,
                localizedContext.getString(R.string.reject_btn),
                rejectIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_save,
                localizedContext.getString(R.string.accept_btn),
                acceptIntent,
            )
            .build()
        NotificationManagerCompat.from(context).notify(PAIRING_NOTIFICATION_ID, notification)
        CoLinkLog.i("Notification", "posted LAN pairing notification device=${CoLinkLog.shortId(request.deviceId)}")
    }

    fun cancelLanPairingRequest() {
        NotificationManagerCompat.from(context).cancel(PAIRING_NOTIFICATION_ID)
    }

    private suspend fun canNotify(reason: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            CoLinkLog.w("Notification", "notification skipped because system notifications are disabled reason=$reason")
            return false
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            CoLinkLog.w("Notification", "notification skipped because POST_NOTIFICATIONS is not granted reason=$reason")
            return false
        }
        return true
    }

    private fun mainActivityIntent(deviceId: String? = null): PendingIntent {
        val targetDeviceId = deviceId?.takeIf { it.isNotBlank() }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (targetDeviceId == null) {
                action = ACTION_OPEN_MAIN
                removeExtra(EXTRA_TARGET_DEVICE_ID)
            } else {
                action = ACTION_OPEN_DEVICE
                putExtra(EXTRA_TARGET_DEVICE_ID, targetDeviceId)
            }
        }
        return PendingIntent.getActivity(
            context,
            targetDeviceId?.let { TARGET_DEVICE_REQUEST_CODE_BASE + (it.hashCode() and TARGET_DEVICE_REQUEST_CODE_MASK) }
                ?: MAIN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun fileOfferNotificationId(sessionId: String): Int =
        FILE_OFFER_NOTIFICATION_ID_BASE + (sessionId.hashCode() and 0x7fffffff)

    private fun uniqueNotificationId(): Int =
        (System.currentTimeMillis() and 0x7fffffff).toInt()

    companion object {
        private const val FILE_OFFER_NOTIFICATION_ID_BASE = 3000
    }
}
