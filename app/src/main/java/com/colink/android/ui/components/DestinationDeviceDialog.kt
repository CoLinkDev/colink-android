package com.colink.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.colink.android.R
import com.colink.android.domain.model.Device

@Composable
fun DestinationDeviceDialog(
    devices: List<Device>,
    initialDeviceId: String? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf(initialDeviceId) }

    LaunchedEffect(devices, initialDeviceId) {
        val availableDevices = devices.filter { it.online || it.lanAvailable }
        if (selectedId == null || availableDevices.none { it.deviceId == selectedId }) {
            selectedId = availableDevices.firstOrNull { it.deviceId == initialDeviceId }?.deviceId
                ?: availableDevices.firstOrNull()?.deviceId
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_destination_title)) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.no_devices_available))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = devices, key = { it.deviceId }) { device ->
                        val available = device.online || device.lanAvailable
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = available) { selectedId = device.deviceId }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = selectedId == device.deviceId,
                                onClick = { selectedId = device.deviceId },
                                enabled = available,
                            )
                            Column {
                                Text(
                                    device.name.ifBlank { stringResource(R.string.unnamed_device) },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    when {
                                        device.lanAvailable -> stringResource(R.string.lan_available_tag)
                                        device.online -> stringResource(R.string.cloud_available_tag)
                                        else -> stringResource(R.string.device_tag_offline)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedId?.let(onSelect) },
                enabled = devices.any { it.deviceId == selectedId && (it.online || it.lanAvailable) },
            ) {
                Text(stringResource(R.string.send_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        },
    )
}
