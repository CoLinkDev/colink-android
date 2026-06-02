package com.colink.android.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.TextMessage
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.SnackbarOnMessage
import java.text.DateFormat
import java.util.Date

@Composable
fun MessageScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val targetDevices = remember(devices, uiState.localDeviceId) {
        devicesWithoutLocalDevice(devices, uiState.localDeviceId)
    }

    LaunchedEffect(targetDevices) {
        if (selectedDeviceId == null || targetDevices.none { it.deviceId == selectedDeviceId }) {
            selectedDeviceId = targetDevices.firstOrNull { it.online || it.lanAvailable }?.deviceId
        }
    }

    val pendingShare by pendingShareStore?.share?.collectAsStateWithLifecycle()
        ?: androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<PendingShare?>(null)
        }

    LaunchedEffect(pendingShare) {
        val share = pendingShareStore?.consume()
        if (share is PendingShare.Text) {
            draft = share.text
        }
    }

    SnackbarOnMessage(
        message = uiState.message,
        snackbarHostState = snackbarHostState,
        onConsumed = viewModel::clearMessage,
    )

    ScreenColumn(
        title = "Messages",
        subtitle = selectedDeviceName(targetDevices, selectedDeviceId) ?: "Choose an available device",
        modifier = modifier,
    ) {
        DevicePicker(
            devices = targetDevices,
            selectedDeviceId = selectedDeviceId,
            onSelectedDeviceChange = { selectedDeviceId = it },
        )

        if (uiState.sending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true
        ) {
            if (messages.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "No messages",
                        body = "Pick a device and send the first message.",
                    )
                }
            } else {
                items(messages.asReversed(), key = { it.messageId }) { message ->
                    MessageCard(message = message, modifier = Modifier.animateItem())
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                minLines = 1,
                maxLines = 4,
                supportingText = {
                    Text("${draft.trim().length}/10000")
                },
            )
            FilledIconButton(
                enabled = draft.isNotBlank() && !uiState.sending,
                onClick = {
                    val text = draft
                    viewModel.send(selectedDeviceId, text)
                    if (text.isNotBlank()) {
                        draft = ""
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message")
            }
        }
    }
}

@Composable
private fun MessageCard(message: TextMessage, modifier: Modifier = Modifier) {
    val outgoing = message.direction == MessageDirection.Outgoing
    
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "via ${message.route}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "·",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        
        Surface(
            shape = MaterialTheme.shapes.large.copy(
                bottomEnd = if (outgoing) androidx.compose.foundation.shape.CornerSize(4.dp) else androidx.compose.foundation.shape.CornerSize(24.dp),
                bottomStart = if (!outgoing) androidx.compose.foundation.shape.CornerSize(4.dp) else androidx.compose.foundation.shape.CornerSize(24.dp),
            ),
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
    }
}

private fun selectedDeviceName(
    devices: List<Device>,
    selectedDeviceId: String?,
): String? =
    devices.firstOrNull { it.deviceId == selectedDeviceId }?.name?.takeIf { it.isNotBlank() }
