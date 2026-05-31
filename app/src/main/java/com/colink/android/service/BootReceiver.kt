package com.colink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.colink.android.data.local.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val dataStore = SettingsDataStore(context.applicationContext)
            val settings = dataStore.currentSettings()
            val session = dataStore.currentSession()
            if (settings.autoStartOnBoot && session != null) {
                CoLinkService.start(context.applicationContext)
            }
            result.finish()
        }
    }
}
