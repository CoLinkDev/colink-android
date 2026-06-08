package com.colink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.colink.android.network.lan.LanPairingCoordinator
import com.colink.android.notification.ACTION_LAN_PAIRING_ACCEPT
import com.colink.android.notification.ACTION_LAN_PAIRING_REJECT
import com.colink.android.notification.EXTRA_PAIRING_REQUEST_ID
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LanPairingActionReceiver : BroadcastReceiver() {
    @Inject lateinit var pairingCoordinator: LanPairingCoordinator

    override fun onReceive(context: Context, intent: Intent?) {
        val requestId = intent
            ?.getStringExtra(EXTRA_PAIRING_REQUEST_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        when (intent.action) {
            ACTION_LAN_PAIRING_ACCEPT -> pairingCoordinator.respond(requestId, true)
            ACTION_LAN_PAIRING_REJECT -> pairingCoordinator.respond(requestId, false)
        }
    }
}
