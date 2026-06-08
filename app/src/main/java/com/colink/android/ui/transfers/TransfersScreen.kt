package com.colink.android.ui.transfers

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.StateFlow

private val openDocumentMimeTypes = arrayOf("*/*")

@Composable
fun TransfersScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    viewModel: TransfersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pickedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmTransfer by rememberSaveable { mutableStateOf<TransferDecision?>(null) }
    val targetDevices = remember(devices, uiState.localDeviceId) {
        devicesWithoutLocalDevice(devices, uiState.localDeviceId)
            .filter { it.online || it.lanAvailable }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            pickedFileUri = uri
        }
    }

    LaunchedEffect(pendingShareStore) {
        val share = pendingShareStore?.consume()
        if (share is PendingShare.File) {
            pickedFileUri = share.uri
        }
    }

    val uriToSend = pickedFileUri
    if (uriToSend != null) {
        SelectDeviceDialog(
            devices = targetDevices,
            onDismiss = { pickedFileUri = null },
            onSelect = { deviceId ->
                viewModel.send(context.contentResolver, deviceId, uriToSend)
                pickedFileUri = null
            }
        )
    }

    LaunchedEffect(uiState.message) {
        val msg = uiState.message
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    ScreenColumn(
        title = stringResource(R.string.nav_transfers),
        subtitle = stringResource(R.string.transfers_subtitle),
        action = {
            FilledTonalIconButton(
                enabled = !uiState.working,
                onClick = { filePicker.launch(openDocumentMimeTypes) },
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = stringResource(R.string.send_file_desc)
                )
            }
        },
        modifier = modifier,
    ) {
        if (uiState.working) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        TransferList(
            transfers = viewModel.transfers,
            working = uiState.working,
            onPickFile = { filePicker.launch(openDocumentMimeTypes) },
            onAccept = { transfer ->
                confirmTransfer = TransferDecision.Accept(transfer.sessionId, transfer.fileName)
            },
            onReject = { transfer ->
                confirmTransfer = TransferDecision.Reject(transfer.sessionId, transfer.fileName)
            },
        )
    }

    when (val decision = confirmTransfer) {
        is TransferDecision.Accept -> {
            val fName = decision.fileName.ifBlank { stringResource(R.string.unnamed_file) }
            ConfirmTransferDialog(
                title = stringResource(R.string.accept_file_title),
                body = stringResource(R.string.accept_file_body, fName),
                confirmText = stringResource(R.string.accept_btn),
                onDismiss = { confirmTransfer = null },
                onConfirm = {
                    viewModel.accept(decision.sessionId)
                    confirmTransfer = null
                },
            )
        }

        is TransferDecision.Reject -> {
            val fName = decision.fileName.ifBlank { stringResource(R.string.unnamed_file) }
            ConfirmTransferDialog(
                title = stringResource(R.string.reject_file_title),
                body = stringResource(R.string.reject_file_body, fName),
                confirmText = stringResource(R.string.reject_btn),
                onDismiss = { confirmTransfer = null },
                onConfirm = {
                    viewModel.reject(decision.sessionId)
                    confirmTransfer = null
                },
            )
        }

        null -> Unit
    }
}

@Composable
private fun TransferList(
    transfers: StateFlow<List<FileTransfer>>,
    working: Boolean,
    onPickFile: () -> Unit,
    onAccept: (FileTransfer) -> Unit,
    onReject: (FileTransfer) -> Unit,
) {
    val transferItems by transfers.collectAsStateWithLifecycle()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (transferItems.isEmpty()) {
            item(contentType = "empty") {
                EmptyState(
                    icon = Icons.Default.FolderOff,
                    title = stringResource(R.string.no_transfers_title),
                    body = stringResource(R.string.no_transfers_body),
                    action = {
                        Button(
                            enabled = !working,
                            onClick = onPickFile,
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Text(stringResource(R.string.send_file_btn))
                        }
                    },
                )
            }
        } else {
            items(
                items = transferItems,
                key = { it.sessionId },
                contentType = { "transfer" },
            ) { transfer ->
                TransferCard(
                    transfer = transfer,
                    onAccept = { onAccept(transfer) },
                    onReject = { onReject(transfer) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun TransferCard(
    transfer: FileTransfer,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        val sizeStr = remember(transfer.fileSize) {
            formatSize(transfer.fileSize)
        }
        val dateStr = remember(transfer.updatedAt) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(transfer.updatedAt))
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (transfer.direction == FileTransferDirection.Incoming) {
                        Icons.Default.FileUpload
                    } else {
                        Icons.Default.UploadFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        transfer.fileName.ifBlank { stringResource(R.string.unnamed_file) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val directionStr = if (transfer.direction == FileTransferDirection.Incoming) {
                        stringResource(R.string.direction_incoming)
                    } else {
                        stringResource(R.string.direction_outgoing)
                    }
                    Text(
                        text = "$directionStr · $sizeStr · $dateStr",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (transfer.totalChunks > 0 && transfer.status in setOf("receiving", "sending")) {
                LinearProgressIndicator(
                    progress = {
                        (transfer.transferredBytes.toFloat() / transfer.fileSize.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BadgeChip(
                    text = statusLabel(transfer.status),
                    containerColor = if (transfer.status == "completed") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (transfer.status == "completed") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                val routeName = when (transfer.route) {
                    "lan" -> stringResource(R.string.route_lan)
                    "cloud" -> stringResource(R.string.route_cloud)
                    else -> transfer.route
                }
                BadgeChip(text = routeName)
                if (
                    transfer.direction == FileTransferDirection.Incoming &&
                    transfer.status == "offered"
                ) {
                    TextButton(onClick = onReject) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text(stringResource(R.string.reject_btn))
                    }
                    Button(onClick = onAccept) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text(stringResource(R.string.accept_btn))
                    }
                }
            }

            transfer.error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConfirmTransferDialog(
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

@Composable
private fun SelectDeviceDialog(
    devices: List<Device>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selectedId by remember { mutableStateOf(devices.firstOrNull()?.deviceId) }

    LaunchedEffect(devices) {
        if (selectedId == null || devices.none { it.deviceId == selectedId }) {
            selectedId = devices.firstOrNull()?.deviceId
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = devices,
                        key = { it.deviceId },
                        contentType = { "device" },
                    ) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = device.deviceId }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = selectedId == device.deviceId,
                                onClick = { selectedId = device.deviceId },
                            )
                            Column {
                                Text(device.name.ifBlank { stringResource(R.string.unnamed_device) }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        device.lanAvailable -> stringResource(R.string.lan_available_tag)
                                        device.online -> stringResource(R.string.cloud_available_tag)
                                        else -> stringResource(R.string.device_tag_offline)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedId?.let { onSelect(it) } },
                enabled = selectedId != null
            ) {
                Text(stringResource(R.string.send_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_btn))
            }
        }
    )
}

@Composable
private fun statusLabel(status: String): String =
    when (status) {
        "completed" -> stringResource(R.string.status_completed)
        "receiving" -> stringResource(R.string.status_receiving)
        "sending" -> stringResource(R.string.status_sending)
        "offered" -> stringResource(R.string.status_offered)
        "failed" -> stringResource(R.string.status_failed)
        "rejected" -> stringResource(R.string.status_rejected)
        "cancelled" -> stringResource(R.string.status_cancelled)
        else -> status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024 * 1024)} GB"
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

private sealed interface TransferDecision {
    val sessionId: String
    val fileName: String

    data class Accept(
        override val sessionId: String,
        override val fileName: String,
    ) : TransferDecision

    data class Reject(
        override val sessionId: String,
        override val fileName: String,
    ) : TransferDecision
}
