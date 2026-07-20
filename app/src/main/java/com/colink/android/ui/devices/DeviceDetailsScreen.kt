package com.colink.android.ui.devices

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.colink.android.domain.model.Device
import com.colink.android.ui.components.EmptyState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(
    deviceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val device = remember(devices, deviceId) {
        devices.firstOrNull { it.deviceId == deviceId }
    }
    val isLocalDevice = device?.deviceId == uiState.localDeviceId
    var confirmAction by remember { mutableStateOf<DeviceAction?>(null) }
    var runningAction by remember { mutableStateOf<DeviceAction?>(null) }
    val context = LocalContext.current

    LaunchedEffect(uiState.message) {
        val msg = uiState.message
        if (!msg.isNullOrBlank() && runningAction == null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    if (runningAction != null && uiState.message != null) {
        val dialogTitle = when (runningAction) {
            is DeviceAction.RotateKey -> stringResource(R.string.rotate_key_title)
            is DeviceAction.Rename -> stringResource(R.string.rename_device_title)
            is DeviceAction.Delete -> stringResource(R.string.delete_device_title)
            is DeviceAction.ForgetTrust -> stringResource(R.string.forget_lan_trust_title)
            null -> ""
        }
        AlertDialog(
            onDismissRequest = {
                viewModel.clearMessage()
                val wasDestructive = runningAction is DeviceAction.Delete || runningAction is DeviceAction.ForgetTrust
                runningAction = null
                if (wasDestructive) {
                    onBack()
                }
            },
            title = { Text(dialogTitle) },
            text = { Text(uiState.message.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearMessage()
                        val wasDestructive = runningAction is DeviceAction.Delete || runningAction is DeviceAction.ForgetTrust
                        runningAction = null
                        if (wasDestructive) {
                            onBack()
                        }
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = device?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.device_details_title),
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
                            contentDescription = stringResource(R.string.back_desc)
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
                    title = stringResource(R.string.device_not_found),
                    body = stringResource(R.string.refresh_body),
                    action = {
                        TextButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh_btn))
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
                item(contentType = "actions") {
                    DeviceActionsCard(
                        device = device,
                        isLocalDevice = isLocalDevice,
                        onRotateKey = {
                            confirmAction = DeviceAction.RotateKey(device.deviceId, device.name)
                        },
                        onRename = {
                            confirmAction = DeviceAction.Rename(device.deviceId, device.name)
                        },
                        onDelete = {
                            confirmAction = DeviceAction.Delete(device.deviceId, device.name)
                        },
                        onForgetTrust = {
                            confirmAction = DeviceAction.ForgetTrust(device.deviceId, device.name)
                        },
                    )
                }
                item(contentType = "info") {
                    DeviceInformationCard(
                        device = device,
                        isLocalDevice = isLocalDevice,
                    )
                }
            }
        }
    }

    when (val action = confirmAction) {
        is DeviceAction.Delete -> {
            val devName = action.deviceName.ifBlank { stringResource(R.string.unnamed_device) }
            ConfirmDeviceActionDialog(
                title = stringResource(R.string.delete_device_title),
                body = stringResource(R.string.delete_device_body, devName),
                confirmText = stringResource(R.string.delete_btn),
                onDismiss = { confirmAction = null },
                onConfirm = {
                    viewModel.deleteDevice(action.deviceId)
                    runningAction = action
                    confirmAction = null
                },
            )
        }

        is DeviceAction.RotateKey -> {
            val devName = action.deviceName.ifBlank { stringResource(R.string.unnamed_device) }
            ConfirmDeviceActionDialog(
                title = stringResource(R.string.rotate_key_title),
                body = stringResource(R.string.rotate_key_body, devName),
                confirmText = stringResource(R.string.rotate_btn),
                onDismiss = { confirmAction = null },
                onConfirm = {
                    viewModel.rotateKey(action.deviceId)
                    runningAction = action
                    confirmAction = null
                },
            )
        }

        is DeviceAction.Rename -> {
            RenameDeviceDialog(
                initialName = action.deviceName,
                onDismiss = { confirmAction = null },
                onConfirm = { name ->
                    viewModel.renameDevice(action.deviceId, name)
                    runningAction = action
                    confirmAction = null
                },
            )
        }

        is DeviceAction.ForgetTrust -> {
            val devName = action.deviceName.ifBlank { stringResource(R.string.unnamed_device) }
            ConfirmDeviceActionDialog(
                title = stringResource(R.string.forget_lan_trust_title),
                body = stringResource(R.string.forget_lan_trust_body, devName),
                confirmText = stringResource(R.string.forget_btn),
                onDismiss = { confirmAction = null },
                onConfirm = {
                    viewModel.forgetLanTrust(action.deviceId)
                    runningAction = action
                    confirmAction = null
                },
            )
        }

        null -> Unit
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DeviceActionsCard(
    device: Device,
    isLocalDevice: Boolean,
    onRotateKey: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onForgetTrust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canDelete = !isLocalDevice && device.deviceSources.contains("cloud")
    val canForgetTrust = !isLocalDevice &&
        device.deviceSources.contains("trusted_peer_key")

    if (!isLocalDevice && !canDelete && !canForgetTrust) {
        return
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.actions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLocalDevice) {
                    Button(onClick = onRename) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.rename_device_btn))
                    }
                    Button(onClick = onRotateKey) {
                        Icon(Icons.Default.VpnKey, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.rotate_key_title))
                    }
                }
                if (canDelete) {
                    OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_from_cloud_btn))
                    }
                }
                if (canForgetTrust) {
                    OutlinedButton(onClick = onForgetTrust) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.forget_lan_trust_btn))
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
        DetailRowData(stringResource(R.string.label_name), device.name.ifBlank { stringResource(R.string.unnamed_device) }),
        DetailRowData(stringResource(R.string.label_device_id), device.deviceId, mono = true),
        DetailRowData(stringResource(R.string.label_platform), formatPlatformName(device.type)),
        DetailRowData(stringResource(R.string.label_fetch_source), describeSources(device, isLocalDevice)),
        DetailRowData(stringResource(R.string.label_local_device), formatBoolean(isLocalDevice)),
        DetailRowData(stringResource(R.string.label_cloud_available), formatBoolean(device.cloudAvailable)),
        DetailRowData(stringResource(R.string.label_lan_available), formatBoolean(device.lanAvailable)),
        DetailRowData(stringResource(R.string.label_lan_endpoint), formatLanEndpoint(device)),
        DetailRowData(stringResource(R.string.label_active_route), formatRoute(device.activeRoute)),
        DetailRowData(stringResource(R.string.label_trusted_by_lan), formatBoolean(device.trustedByLan)),
        DetailRowData(stringResource(R.string.label_trusted_by_cloud), formatBoolean(device.trustedByCloud)),
        DetailRowData(stringResource(R.string.label_last_alive), device.lastSeen ?: stringResource(R.string.never_connected)),
        DetailRowData(
            label = stringResource(R.string.label_public_key_fingerprint),
            value = fingerprint.ifBlank { stringResource(R.string.value_none) },
            mono = true,
        ),
        DetailRowData(stringResource(R.string.label_public_key), device.publicKey.ifBlank { stringResource(R.string.value_none) }, mono = true, maxLines = 6),
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.info_title),
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
private fun RenameDeviceDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var nameError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.device_name_label)) },
                    singleLine = true,
                    isError = nameError,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    supportingText = {
                        if (nameError) {
                            Text(stringResource(R.string.err_device_name_required))
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isBlank()) {
                        nameError = true
                    } else {
                        onConfirm(trimmed)
                    }
                },
            ) {
                Text(stringResource(R.string.save_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        },
    )
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
                Text(stringResource(R.string.cancel_btn))
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

    data class Rename(
        override val deviceId: String,
        override val deviceName: String,
    ) : DeviceAction

    data class ForgetTrust(
        override val deviceId: String,
        override val deviceName: String,
    ) : DeviceAction
}

@Composable
private fun formatBoolean(value: Boolean): String =
    if (value) stringResource(R.string.value_yes) else stringResource(R.string.value_no)

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

@Composable
private fun describeSources(device: Device, isLocalDevice: Boolean): String {
    val sources = buildList {
        if (isLocalDevice || device.deviceSources.contains("local")) {
            add(stringResource(R.string.source_local_identity))
        }
        if (device.deviceSources.contains("cloud")) {
            add(stringResource(R.string.source_server_device_list))
        }
        if (device.deviceSources.contains("trusted_peer_key")) {
            add(stringResource(R.string.source_trusted_peer_key))
        }
    }
    return sources.ifEmpty { listOf(stringResource(R.string.source_server_device_list)) }.joinToString()
}

@Composable
private fun formatLanEndpoint(device: Device): String {
    val ip = device.localIp?.takeIf { it.isNotBlank() } ?: return stringResource(R.string.value_none)
    val port = device.localPort
    return if (port != null && port > 0) "$ip:$port" else ip
}

@Composable
private fun formatRoute(value: String?): String =
    when (value) {
        "lan" -> stringResource(R.string.route_lan)
        "cloud" -> stringResource(R.string.route_cloud)
        null, "" -> stringResource(R.string.value_none)
        else -> value
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
