package com.colink.android.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.domain.model.Device
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.RefreshableList
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.SnackbarOnMessage

@Composable
fun DeviceListScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmAction by remember { mutableStateOf<DeviceAction?>(null) }

    SnackbarOnMessage(
        message = uiState.message,
        snackbarHostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    ScreenColumn(
        title = "Devices",
        subtitle = "${devices.count { it.online || it.lanAvailable }} available · ${devices.size} total",
        action = {
            FilledTonalIconButton(onClick = viewModel::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
            }
        },
        modifier = modifier,
    ) {
        RefreshableList(
            isRefreshing = uiState.loading,
            onRefresh = viewModel::refresh,
        ) {
            if (devices.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.Devices,
                        title = "No devices yet",
                        body = "Sign in on another device, then refresh this list.",
                    )
                }
            } else {
                items(devices, key = { it.deviceId }) { device ->
                    DeviceCard(
                        device = device,
                        isLocalDevice = device.deviceId == uiState.localDeviceId,
                        onRotateKey = { confirmAction = DeviceAction.RotateKey(device.deviceId, device.name) },
                        onDelete = { confirmAction = DeviceAction.Delete(device.deviceId, device.name) },
                        onForgetTrust = { confirmAction = DeviceAction.ForgetTrust(device.deviceId, device.name) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    when (val action = confirmAction) {
        is DeviceAction.Delete -> ConfirmDeviceActionDialog(
            title = "Delete device",
            body = "Remove ${action.deviceName.ifBlank { "this device" }} from your account?",
            confirmText = "Delete",
            onDismiss = { confirmAction = null },
            onConfirm = {
                viewModel.deleteDevice(action.deviceId)
                confirmAction = null
            },
        )

        is DeviceAction.RotateKey -> ConfirmDeviceActionDialog(
            title = "Rotate key",
            body = "Create a new trusted key for ${action.deviceName.ifBlank { "this device" }}?",
            confirmText = "Rotate",
            onDismiss = { confirmAction = null },
            onConfirm = {
                viewModel.rotateKey(action.deviceId)
                confirmAction = null
            },
        )

        is DeviceAction.ForgetTrust -> ConfirmDeviceActionDialog(
            title = "Forget LAN trust",
            body = "Forget LAN trust for ${action.deviceName.ifBlank { "this device" }}?",
            confirmText = "Forget",
            onDismiss = { confirmAction = null },
            onConfirm = {
                viewModel.forgetLanTrust(action.deviceId)
                confirmAction = null
            },
        )

        null -> Unit
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DeviceCard(
    device: Device,
    isLocalDevice: Boolean,
    onRotateKey: () -> Unit,
    onDelete: () -> Unit,
    onForgetTrust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = when {
                    device.lanAvailable -> Icons.Default.Wifi
                    device.online -> Icons.Default.Cloud
                    else -> Icons.Default.Devices
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = device.name.ifBlank { "Unnamed device" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.type.ifBlank { "Unknown type" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isLocalDevice) {
                        BadgeChip(
                            text = "Local",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    BadgeChip(
                        text = when {
                            device.lanAvailable -> "LAN"
                            device.online -> "Cloud"
                            else -> "Offline"
                        },
                        containerColor = if (device.lanAvailable || device.online) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (device.lanAvailable || device.online) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    device.localIp?.takeIf { it.isNotBlank() }?.let {
                        BadgeChip(text = it)
                    }
                    if (device.type == "unknown" && device.deviceSources.contains("trusted_peer_key")) {
                        BadgeChip(text = "Trusted")
                    }
                }
            }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Device actions")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    if (isLocalDevice) {
                        DropdownMenuItem(
                            text = { Text("Rotate key") },
                            leadingIcon = { Icon(Icons.Default.VpnKey, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onRotateKey()
                            },
                        )
                    }
                    if (!isLocalDevice && device.deviceSources.contains("cloud")) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onDelete()
                            },
                        )
                    }
                    if (!isLocalDevice && device.type == "unknown" && device.deviceSources.contains("trusted_peer_key")) {
                        DropdownMenuItem(
                            text = { Text("Forget trust") },
                            leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onForgetTrust()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDeviceActionDialog(
    title: String,
    body: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private sealed interface DeviceAction {
    val deviceId: String
    val deviceName: String

    data class Delete(
        override val deviceId: String,
        override val deviceName: String,
    ) : DeviceAction

    data class RotateKey(
        override val deviceId: String,
        override val deviceName: String,
    ) : DeviceAction

    data class ForgetTrust(
        override val deviceId: String,
        override val deviceName: String,
    ) : DeviceAction
}
