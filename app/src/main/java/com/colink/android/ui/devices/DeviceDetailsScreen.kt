package com.colink.android.ui.devices

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.domain.model.Device
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.SnackbarOnMessage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Box

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(
    deviceId: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.deviceId == deviceId }
    val isLocalDevice = device?.deviceId == uiState.localDeviceId
    var confirmAction by remember { mutableStateOf<DeviceAction?>(null) }

    SnackbarOnMessage(
        message = uiState.message,
        snackbarHostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = device?.name?.takeIf { it.isNotBlank() } ?: "Device details",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (device != null) {
                            Text(
                                text = formatPlatformName(device.type),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { innerPadding ->
        if (device == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Default.Devices,
                    title = "Device not found",
                    body = "Refresh the device list and try again.",
                    action = {
                        TextButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DeviceActionsCard(
                        device = device,
                        isLocalDevice = isLocalDevice,
                        onRotateKey = {
                            confirmAction = DeviceAction.RotateKey(device.deviceId, device.name)
                        },
                        onDelete = {
                            confirmAction = DeviceAction.Delete(device.deviceId, device.name)
                        },
                        onForgetTrust = {
                            confirmAction = DeviceAction.ForgetTrust(device.deviceId, device.name)
                        },
                    )
                }
                item {
                    DeviceInformationCard(
                        device = device,
                        isLocalDevice = isLocalDevice,
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
                onBack()
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
                onBack()
            },
        )

        null -> Unit
    }
}


@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DeviceActionsCard(
    device: Device,
    isLocalDevice: Boolean,
    onRotateKey: () -> Unit,
    onDelete: () -> Unit,
    onForgetTrust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canDelete = !isLocalDevice && device.deviceSources.contains("cloud")
    val canForgetTrust = !isLocalDevice &&
        device.type == "unknown" &&
        device.deviceSources.contains("trusted_peer_key")

    if (!isLocalDevice && !canDelete && !canForgetTrust) {
        return
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLocalDevice) {
                    Button(onClick = onRotateKey) {
                        Icon(Icons.Default.VpnKey, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rotate key")
                    }
                }
                if (canDelete) {
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete")
                    }
                }
                if (canForgetTrust) {
                    OutlinedButton(onClick = onForgetTrust) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Forget trust")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceInformationCard(
    device: Device,
    isLocalDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    val fingerprint = remember(device.publicKey) {
        publicKeyFingerprint(device.publicKey)
    }
    val rows = listOf(
        DetailRowData("Name", device.name.ifBlank { "Unnamed device" }),
        DetailRowData("Device ID", device.deviceId, mono = true),
        DetailRowData("Platform", formatPlatformName(device.type)),
        DetailRowData("Fetch source", describeSources(device, isLocalDevice)),
        DetailRowData("Local reachable", formatBoolean(isLocalDevice)),
        DetailRowData("Cloud available", formatBoolean(device.cloudAvailable)),
        DetailRowData("LAN available", formatLanAvailability(device)),
        DetailRowData("LAN endpoint", formatLanEndpoint(device)),
        DetailRowData("Active route", formatRoute(device.activeRoute)),
        DetailRowData("Security state", formatSecurityState(device.securityState)),
        DetailRowData("Last seen", device.lastSeen ?: "Never connected"),
        DetailRowData(
            label = "Public key fingerprint",
            value = fingerprint.ifBlank { "None" },
            mono = true,
        ),
        DetailRowData("Public key", device.publicKey.ifBlank { "None" }, mono = true, maxLines = 6),
    )

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            rows.forEach { row ->
                DetailRow(row = row)
            }
        }
    }
}

@Composable
private fun DetailRow(row: DetailRowData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (row.mono) FontFamily.Monospace else null,
            maxLines = row.maxLines,
            overflow = TextOverflow.Ellipsis,
        )
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

private data class DetailRowData(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    val maxLines: Int = Int.MAX_VALUE,
)

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

private fun formatBoolean(value: Boolean): String =
    if (value) "Yes" else "No"

private fun formatPlatformName(value: String): String =
    when (value.lowercase()) {
        "windows" -> "Windows"
        "macos" -> "macOS"
        "linux" -> "Linux"
        "android" -> "Android"
        "ios" -> "iOS"
        "unknown", "" -> "Unknown"
        else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun describeSources(device: Device, isLocalDevice: Boolean): String {
    val sources = buildList {
        if (isLocalDevice || device.deviceSources.contains("local")) {
            add("Local identity")
        }
        if (device.deviceSources.contains("cloud")) {
            add("Server device list")
        }
        if (device.deviceSources.contains("trusted_peer_key")) {
            add("Trusted peer key")
        }
    }
    return sources.ifEmpty { listOf("Server device list") }.joinToString()
}

private fun formatLanAvailability(device: Device): String =
    if (device.lanAvailable) "LAN" else "No"

private fun formatLanEndpoint(device: Device): String {
    val ip = device.localIp?.takeIf { it.isNotBlank() } ?: return "None"
    val port = device.localPort
    return if (port != null && port > 0) "$ip:$port" else ip
}

private fun formatRoute(value: String?): String =
    when (value) {
        "lan" -> "LAN"
        "cloud" -> "Cloud"
        null, "" -> "None"
        else -> value
    }

private fun formatSecurityState(value: String): String =
    when (value) {
        "verified" -> "Verified"
        "unverified" -> "Unverified"
        "trusted" -> "Trusted"
        "unknown" -> "Unknown"
        "key_changed" -> "Key changed"
        else -> value.ifBlank { "Unknown" }
    }

private fun publicKeyFingerprint(publicKey: String): String {
    if (publicKey.isBlank()) {
        return ""
    }
    val bytes = runCatching {
        Base64.decode(publicKey, Base64.DEFAULT)
    }.getOrElse {
        publicKey.toByteArray(StandardCharsets.UTF_8)
    }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
