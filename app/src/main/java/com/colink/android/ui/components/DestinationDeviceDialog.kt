package com.colink.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val availableDevices = devices.filter { it.online || it.lanAvailable }

    LaunchedEffect(availableDevices, initialDeviceId) {
        if (selectedId == null || availableDevices.none { it.deviceId == selectedId }) {
            selectedId = availableDevices.firstOrNull { it.deviceId == initialDeviceId }?.deviceId
                ?: availableDevices.firstOrNull()?.deviceId
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.select_destination_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            if (availableDevices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.no_devices_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(
                        items = availableDevices,
                        key = { _, device -> device.deviceId },
                    ) { index, device ->
                        val isFirst = index == 0
                        val isLast = index == availableDevices.lastIndex
                        val isSelected = selectedId == device.deviceId
                        val itemShape = contentGroupShape(isFirst = isFirst, isLast = isLast)

                        val containerColor by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            label = "deviceItemContainerColor",
                        )
                        val iconTint by animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            label = "deviceItemIconTint",
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(itemShape)
                                .clickable { selectedId = device.deviceId },
                            shape = itemShape,
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = device.name.ifBlank { stringResource(R.string.unnamed_device) },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = deviceTypeIcon(device.type),
                                            contentDescription = null,
                                            tint = iconTint,
                                            modifier = Modifier.size(14.dp),
                                        )

                                        val hasRoute = device.lanAvailable || device.cloudAvailable || device.online
                                        if (hasRoute) {
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(10.dp)
                                                    .background(
                                                        color = if (isSelected) {
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                                        } else {
                                                            MaterialTheme.colorScheme.outlineVariant
                                                        },
                                                    ),
                                            )
                                            if (device.lanAvailable) {
                                                Icon(
                                                    imageVector = Icons.Default.Wifi,
                                                    contentDescription = stringResource(R.string.route_lan),
                                                    tint = iconTint,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                            if (device.cloudAvailable || (device.online && !device.lanAvailable)) {
                                                Icon(
                                                    imageVector = Icons.Default.Cloud,
                                                    contentDescription = stringResource(R.string.route_cloud),
                                                    tint = iconTint,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedId = device.deviceId },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedId?.let(onSelect) },
                enabled = availableDevices.any { it.deviceId == selectedId },
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

private fun deviceTypeIcon(type: String): ImageVector =
    when (type.lowercase()) {
        "windows" -> Icons.Default.DesktopWindows
        "macos" -> Icons.Default.LaptopMac
        "linux" -> Icons.Default.Terminal
        "android" -> Icons.Default.Android
        "ios" -> Icons.Default.PhoneIphone
        else -> Icons.Default.Devices
    }
