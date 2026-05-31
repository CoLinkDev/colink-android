package com.colink.android.ui.transfers

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.SnackbarOnMessage
import java.text.DateFormat
import java.util.Date

@Composable
fun TransfersScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    viewModel: TransfersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pickedFileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmTransfer by rememberSaveable { mutableStateOf<TransferDecision?>(null) }

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
            devices = devices,
            onDismiss = { pickedFileUri = null },
            onSelect = { deviceId ->
                viewModel.send(context.contentResolver, deviceId, uriToSend)
                pickedFileUri = null
            }
        )
    }

    SnackbarOnMessage(
        message = uiState.message,
        snackbarHostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    ScreenColumn(
        title = "Transfers",
        subtitle = "Tap + to select a file and send",
        action = {
            FilledTonalIconButton(
                enabled = !uiState.working,
                onClick = { filePicker.launch(arrayOf("*/*")) },
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = "Send file")
            }
        },
        modifier = modifier,
    ) {
        if (uiState.working) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (transfers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.FolderOff,
                        title = "No transfers",
                        body = "Tap the + button to select a file to send.",
                        action = {
                            Button(
                                enabled = !uiState.working,
                                onClick = { filePicker.launch(arrayOf("*/*")) },
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null)
                                Text("Send file")
                            }
                        },
                    )
                }
            } else {
                items(transfers, key = { it.sessionId }) { transfer ->
                    TransferCard(
                        transfer = transfer,
                        onAccept = { confirmTransfer = TransferDecision.Accept(transfer.sessionId, transfer.fileName) },
                        onReject = { confirmTransfer = TransferDecision.Reject(transfer.sessionId, transfer.fileName) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    when (val decision = confirmTransfer) {
        is TransferDecision.Accept -> ConfirmTransferDialog(
            title = "Accept file",
            body = "Receive ${decision.fileName.ifBlank { "this file" }}?",
            confirmText = "Accept",
            onDismiss = { confirmTransfer = null },
            onConfirm = {
                viewModel.accept(decision.sessionId)
                confirmTransfer = null
            },
        )

        is TransferDecision.Reject -> ConfirmTransferDialog(
            title = "Reject file",
            body = "Reject ${decision.fileName.ifBlank { "this file" }}?",
            confirmText = "Reject",
            onDismiss = { confirmTransfer = null },
            onConfirm = {
                viewModel.reject(decision.sessionId)
                confirmTransfer = null
            },
        )

        null -> Unit
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
                        transfer.fileName.ifBlank { "Unnamed file" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${transfer.direction.name} · ${formatSize(transfer.fileSize)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(transfer.updatedAt))}",
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
                BadgeChip(text = transfer.route)
                if (
                    transfer.direction == FileTransferDirection.Incoming &&
                    transfer.status == "offered"
                ) {
                    TextButton(onClick = onReject) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text("Reject")
                    }
                    Button(onClick = onAccept) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("Accept")
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
                Text("Cancel")
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
    var selectedId by remember { mutableStateOf(devices.firstOrNull { it.online || it.lanAvailable }?.deviceId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select destination") },
        text = {
            if (devices.isEmpty()) {
                Text("No devices available.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices) { device ->
                        val available = device.online || device.lanAvailable
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = available) { selectedId = device.deviceId }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = selectedId == device.deviceId,
                                onClick = { selectedId = device.deviceId },
                                enabled = available
                            )
                            Column {
                                Text(device.name.ifBlank { "Unnamed device" }, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        device.lanAvailable -> "LAN Available"
                                        device.online -> "Cloud Available"
                                        else -> "Offline"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun statusLabel(status: String): String =
    status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

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
