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
import com.colink.android.MainActivity
import com.colink.android.R
import com.colink.android.domain.model.CloudStatus
import com.colink.android.network.ConnectionManager
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "colink_connection"
private const val NOTIFICATION_ID = 1001

@AndroidEntryPoint
class CoLinkService : Service() {
    @Inject lateinit var connectionManager: ConnectionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

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

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun startNotificationUpdates() {
        if (notificationJob?.isActive == true) {
            return
        }
        notificationJob = scope.launch {
            connectionManager.cloudState.collectLatest { state ->
                CoLinkLog.d("Service", "cloud status changed status=${state.status}")
            }
        }
    }

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
