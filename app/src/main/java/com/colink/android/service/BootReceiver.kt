package com.colink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.util.CoLinkLog
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
            if (settings.autoStartOnBoot) {
                CoLinkLog.i("Service", "boot receiver starting service")
                CoLinkRuntimeStarter.ensureStarted(context)
            } else {
                CoLinkLog.d("Service", "boot receiver skipped service start")
            }
            result.finish()
        }
    }
}
