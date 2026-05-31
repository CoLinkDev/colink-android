package com.colink.android.ui.transfers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TransfersScreen(
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    viewModel: TransfersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.send(context.contentResolver, selectedDeviceId, uri)
    }

    LaunchedEffect(devices) {
        if (selectedDeviceId == null || devices.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull { it.online || it.lanAvailable }?.deviceId
        }
    }

    LaunchedEffect(pendingShareStore, selectedDeviceId) {
        if (selectedDeviceId == null) {
            return@LaunchedEffect
        }
        val share = pendingShareStore?.consume()
        if (share is PendingShare.File) {
            viewModel.send(context.contentResolver, selectedDeviceId, share.uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Transfers",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.UploadFile, contentDescription = "Send file")
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            devices.forEach { device ->
                FilterChip(
                    selected = selectedDeviceId == device.deviceId,
                    onClick = { selectedDeviceId = device.deviceId },
                    label = { Text(device.name) },
                    enabled = device.online || device.lanAvailable,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(transfers, key = { it.sessionId }) { transfer ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(transfer.fileName, fontWeight = FontWeight.SemiBold)
                            Text("${transfer.status} · ${transfer.route} · ${formatSize(transfer.fileSize)}")
                        }
                        if (
                            transfer.direction == FileTransferDirection.Incoming &&
                            transfer.status == "offered"
                        ) {
                            IconButton(onClick = { viewModel.accept(transfer.sessionId) }) {
                                Icon(Icons.Default.Check, contentDescription = "Accept transfer")
                            }
                            IconButton(onClick = { viewModel.reject(transfer.sessionId) }) {
                                Icon(Icons.Default.Close, contentDescription = "Reject transfer")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
