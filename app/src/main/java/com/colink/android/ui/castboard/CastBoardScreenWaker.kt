package com.colink.android.ui.castboard

import android.content.Context
import android.os.PowerManager
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val WAKE_DURATION_MILLIS = 5_000L
private const val WAKE_LOCK_TAG = "com.colink.android:castboard-reconnect"

class CastBoardScreenWaker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Suppress("DEPRECATION")
    fun wakeForReconnect() {
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (powerManager.isInteractive) {
            return
        }
        CoLinkLog.i("CastBoard", "waking screen after source device reconnected")
        powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG,
        ).acquire(WAKE_DURATION_MILLIS)
    }
}
