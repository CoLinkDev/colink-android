package com.colink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.colink.android.notification.ACTION_FILE_TRANSFER_ACCEPT
import com.colink.android.notification.ACTION_FILE_TRANSFER_REJECT
import com.colink.android.notification.EXTRA_FILE_SESSION_ID
import com.colink.android.notification.CoLinkNotifier
import com.colink.android.network.ConnectionManager
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FileTransferActionReceiver : BroadcastReceiver() {
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var notifier: CoLinkNotifier

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val sessionId = intent.getStringExtra(EXTRA_FILE_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = when (action) {
                    ACTION_FILE_TRANSFER_ACCEPT -> connectionManager.acceptFileOffer(sessionId)
                    ACTION_FILE_TRANSFER_REJECT -> connectionManager.rejectFileOffer(sessionId)
                    else -> null
                }
                notifier.cancelFileOffer(sessionId)
                if (result?.isFailure == true) {
                    CoLinkLog.w(
                        "Notification",
                        "file transfer action failed session=${CoLinkLog.shortId(sessionId)} action=$action",
                        result.exceptionOrNull(),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
