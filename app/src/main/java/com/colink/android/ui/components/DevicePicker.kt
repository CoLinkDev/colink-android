package com.colink.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.domain.model.Device

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DevicePicker(
    devices: List<Device>,
    selectedDeviceId: String?,
    onSelectedDeviceChange: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        devices.forEach { device ->
            val available = device.online || device.lanAvailable
            FilterChip(
                selected = selectedDeviceId == device.deviceId,
                onClick = { onSelectedDeviceChange(device.deviceId) },
                label = { Text(device.name.ifBlank { device.type.ifBlank { stringResource(R.string.unnamed_device) } }) },
                enabled = available,
                leadingIcon = {
                    Icon(
                        imageVector = when {
                            device.lanAvailable -> Icons.Default.Wifi
                            device.online -> Icons.Default.Cloud
                            else -> Icons.Default.Devices
                        },
                        contentDescription = null,
                    )
                },
            )
        }
    }
}
