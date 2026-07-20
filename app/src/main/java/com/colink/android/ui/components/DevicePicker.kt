package com.colink.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.domain.model.Device

@Composable
fun DevicePicker(
    devices: List<Device>,
    selectedDeviceId: String?,
    onSelectedDeviceChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        devices.forEach { device ->
            val available = device.online || device.lanAvailable
            val selected = selectedDeviceId == device.deviceId
            FilterChip(
                selected = selected,
                onClick = { onSelectedDeviceChange(device.deviceId) },
                label = { Text(device.name.ifBlank { device.type.ifBlank { stringResource(R.string.unnamed_device) } }) },
                enabled = available,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = available,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = Color.Transparent,
                ),
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
