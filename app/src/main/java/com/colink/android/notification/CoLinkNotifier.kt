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
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val EVENT_CHANNEL_ID = "colink_events"
private const val PAIRING_NOTIFICATION_ID = 2001

@Singleton
class CoLinkNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
) {
    fun ensureEventChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            EVENT_CHANNEL_ID,
            "CoLink events",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    suspend fun notifyEvent(title: String, text: String) {
        ensureEventChannel()
        if (!canNotify("event")) {
            return
        }
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.colink_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(uniqueNotificationId(), notification)
        CoLinkLog.d("Notification", "posted event notification title=$title")
    }

    suspend fun notifyLanPairingRequest(request: LanPairingRequest) {
        ensureEventChannel()
        if (!canNotify("lan pairing")) {
            return
        }
        val deviceName = request.name.ifBlank { request.deviceId }
        val notification = NotificationCompat.Builder(context, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.colink_logo)
            .setContentTitle("LAN pairing request")
            .setContentText("$deviceName wants to pair. Code: ${request.code}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$deviceName wants to pair with this device.\nCode: ${request.code}"),
            )
            .setContentIntent(mainActivityIntent())
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(PAIRING_NOTIFICATION_ID, notification)
        CoLinkLog.i("Notification", "posted LAN pairing notification device=${CoLinkLog.shortId(request.deviceId)}")
    }

    fun cancelLanPairingRequest() {
        NotificationManagerCompat.from(context).cancel(PAIRING_NOTIFICATION_ID)
    }

    private suspend fun canNotify(reason: String): Boolean {
        if (!settingsDataStore.currentSettings().notifications) {
            CoLinkLog.d("Notification", "notification skipped because app setting is disabled reason=$reason")
            return false
        }
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

    private fun mainActivityIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun uniqueNotificationId(): Int =
        (System.currentTimeMillis() and 0x7fffffff).toInt()
}
