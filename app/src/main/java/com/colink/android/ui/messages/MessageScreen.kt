package com.colink.android.ui.messages

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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MessageScreen(
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(devices) {
        if (selectedDeviceId == null || devices.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = devices.firstOrNull { it.online || it.lanAvailable }?.deviceId
        }
    }

    LaunchedEffect(pendingShareStore) {
        val share = pendingShareStore?.consume()
        if (share is PendingShare.Text) {
            draft = share.text
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Messages", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Text") },
                minLines = 1,
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    viewModel.send(selectedDeviceId, draft)
                    draft = ""
                },
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send message")
            }
        }

        Spacer(Modifier.height(12.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(messages, key = { it.messageId }) { message ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${message.direction.name} via ${message.route}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(message.text)
                    }
                }
            }
        }
    }
}
