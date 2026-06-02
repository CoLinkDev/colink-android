package com.colink.android.data.local

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceNameProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun defaultDeviceName(): String {
        val settingsName = systemDeviceName()
        if (settingsName != null) {
            return settingsName
        }
        return modelDeviceName()
    }

    private fun systemDeviceName(): String? =
        listOf(
            "global.device_name" to {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            },
            "secure.bluetooth_name" to {
                Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            },
        ).firstNotNullOfOrNull { (source, read) ->
            runCatching {
                read().clean()
            }.onFailure { error ->
                CoLinkLog.w("DeviceName", "failed to read Android device name source=$source", error)
            }.getOrNull()
        }

    private fun modelDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.clean()
        val model = Build.MODEL.clean()
        return when {
            model == null -> "Android"
            manufacturer == null -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "${manufacturer.capitalized()} $model"
        }
    }

    private fun String?.clean(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.capitalized(): String =
        replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase(Locale.ROOT)
            } else {
                char.toString()
            }
        }
}
