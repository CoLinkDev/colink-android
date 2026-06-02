package com.colink.android.ui.components

import com.colink.android.domain.model.Device

fun devicesWithoutLocalDevice(
    devices: List<Device>,
    localDeviceId: String?,
): List<Device> =
    devices.filterNot { device ->
        device.deviceId == localDeviceId || device.deviceSources.contains("local")
    }
