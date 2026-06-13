package com.colink.android.ui.messages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.model.TextMessage
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.transfers.TransfersViewModel
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

private val openDocumentMimeTypes = arrayOf("*/*")



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    fixedDeviceId: String? = null,
    onConversationSelected: (String) -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: MessagesViewModel = hiltViewModel(),
    transferViewModel: TransfersViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val targetDevices by viewModel.targetDevices.collectAsStateWithLifecycle()
    val availableTargetDevices by viewModel.availableTargetDevices.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val timelineItems by viewModel.timelineItems.collectAsStateWithLifecycle()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val messageUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transferUiState by transferViewModel.uiState.collectAsStateWithLifecycle()

    val isConversationRoute = !fixedDeviceId.isNullOrBlank()
    var draft by rememberSaveable(selectedDeviceId) { mutableStateOf("") }
    var pendingFileUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFileTargetDeviceId by remember { mutableStateOf<String?>(null) }



    LaunchedEffect(fixedDeviceId) {
        if (!fixedDeviceId.isNullOrBlank()) {
            viewModel.selectDevice(fixedDeviceId)
        }
    }

    LaunchedEffect(targetDevices, selectedDeviceId, isConversationRoute) {
        if (!isConversationRoute) {
            viewModel.selectDevice(null)
            return@LaunchedEffect
        }
        val current = selectedDeviceId
        if (current != null && targetDevices.any { it.deviceId == current }) {
            return@LaunchedEffect
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            pendingFileTargetDeviceId = null
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val targetDeviceId = pendingFileTargetDeviceId
        pendingFileTargetDeviceId = null
        if (targetDeviceId != null) {
            transferViewModel.send(context.contentResolver, targetDeviceId, uri)
        } else {
            pendingFileUri = uri
        }
    }

    val pendingShare by pendingShareStore?.share?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<PendingShare?>(null) }

    LaunchedEffect(pendingShare) {
        val share = pendingShareStore?.consume()
        when (share) {
            is PendingShare.Text -> draft = share.text
            is PendingShare.File -> {
                pendingFileTargetDeviceId = null
                pendingFileUri = share.uri
            }
            null -> Unit
        }
    }

    LaunchedEffect(messageUiState.message) {
        val msg = messageUiState.message
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(transferUiState.message) {
        val msg = transferUiState.message
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            transferViewModel.clearMessage()
        }
    }

    if (!isConversationRoute) {
        ScreenColumn(
            title = stringResource(R.string.nav_messages),
            subtitle = stringResource(R.string.messages_choose_device),
            modifier = modifier,
        ) {
            ContactList(
                devices = targetDevices,
                onSelect = {
                    viewModel.selectDevice(it)
                    onConversationSelected(it)
                },
            )
        }
    } else {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        val device = selectedDevice
                        if (device != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            color = if (device.online || device.lanAvailable) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            shape = CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = deviceTypeIcon(device.type),
                                        contentDescription = null,
                                        tint = if (device.online || device.lanAvailable) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = device.name.ifBlank { stringResource(R.string.unnamed_device) },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = device.type.ifBlank { stringResource(R.string.unknown_type) },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (device.lanAvailable) {
                                            BadgeChip(
                                                text = stringResource(R.string.device_tag_lan),
                                                icon = Icons.Default.Wifi,
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        } else if (device.online) {
                                            BadgeChip(
                                                text = stringResource(R.string.device_tag_cloud),
                                                icon = Icons.Default.Cloud,
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        } else {
                                            BadgeChip(
                                                text = stringResource(R.string.device_tag_offline),
                                                icon = Icons.Default.Devices,
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(stringResource(R.string.messages_choose_device))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back_desc),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    )
                )
            }
        ) { innerPadding ->
            val device = selectedDevice
            if (device != null) {
                ConversationScreen(
                    device = device,
                    timelineItems = timelineItems,
                    isSendingMessage = messageUiState.sending,
                    isWorkingFiles = transferUiState.working,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSendText = {
                        val text = draft
                        viewModel.send(selectedDeviceId, text)
                        if (text.isNotBlank()) {
                            draft = ""
                        }
                    },
                    onPickFile = {
                        pendingFileTargetDeviceId = selectedDeviceId
                        filePicker.launch(openDocumentMimeTypes)
                    },
                    onAcceptTransfer = transferViewModel::accept,
                    onRejectTransfer = transferViewModel::reject,
                    modifier = Modifier.padding(
                        top = innerPadding.calculateTopPadding(),
                    ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = stringResource(R.string.device_not_found),
                        body = stringResource(R.string.refresh_body),
                    )
                }
            }
        }
    }

    val pendingUri = pendingFileUri
    if (pendingUri != null) {
        SelectDeviceDialog(
            devices = availableTargetDevices,
            initialDeviceId = selectedDeviceId,
            onDismiss = {
                pendingFileUri = null
                pendingFileTargetDeviceId = null
            },
            onSelect = { deviceId ->
                transferViewModel.send(context.contentResolver, deviceId, pendingUri)
                pendingFileUri = null
                pendingFileTargetDeviceId = null
            },
        )
    }
}

@Composable
private fun ContactList(
    devices: List<Device>,
    onSelect: (String) -> Unit,
) {
    if (devices.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.Chat,
            title = stringResource(R.string.messages_contacts_empty_title),
            body = stringResource(R.string.messages_contacts_empty_body),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = devices,
            key = { it.deviceId },
        ) { device ->
            ContactCard(
                device = device,
                onClick = { onSelect(device.deviceId) },
            )
        }
    }
}

@Composable
private fun ContactCard(
    device: Device,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (device.lanAvailable || device.online) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = deviceTypeIcon(device.type),
                    contentDescription = null,
                    tint = if (device.lanAvailable || device.online) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = device.name.ifBlank { stringResource(R.string.unnamed_device) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.type.ifBlank { stringResource(R.string.unknown_type) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    if (device.lanAvailable) {
                        BadgeChip(
                            text = stringResource(R.string.device_tag_lan),
                            icon = Icons.Default.Wifi,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else if (device.online) {
                        BadgeChip(
                            text = stringResource(R.string.device_tag_cloud),
                            icon = Icons.Default.Cloud,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        BadgeChip(
                            text = stringResource(R.string.device_tag_offline),
                            icon = Icons.Default.Devices,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (device.deviceSources.contains("trusted_peer_key")) {
                        BadgeChip(
                            text = stringResource(R.string.device_tag_trusted),
                            icon = Icons.Default.Verified,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationScreen(
    device: Device,
    timelineItems: List<TimelineItem>,
    isSendingMessage: Boolean,
    isWorkingFiles: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    onPickFile: () -> Unit,
    onAcceptTransfer: (String) -> Unit,
    onRejectTransfer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sendEnabled = device.online || device.lanAvailable

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (isWorkingFiles) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
        }

        ConversationThread(
            timelineItems = timelineItems,
            onAcceptTransfer = onAcceptTransfer,
            onRejectTransfer = onRejectTransfer,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        ChatInputBar(
            draft = draft,
            onDraftChange = onDraftChange,
            onSendText = onSendText,
            onSendFile = onPickFile,
            sendEnabled = draft.isNotBlank() && !isSendingMessage && sendEnabled,
            placeholderText = stringResource(R.string.message_placeholder),
        )
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    onSendFile: () -> Unit,
    sendEnabled: Boolean,
    placeholderText: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalIconButton(
                onClick = onSendFile,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.send_file_desc),
                    modifier = Modifier.size(20.dp),
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholderText) },
                maxLines = 4,
                shape = RoundedCornerShape(26.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )

            FilledIconButton(
                enabled = sendEnabled,
                onClick = onSendText,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_message_desc),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ConversationThread(
    timelineItems: List<TimelineItem>,
    onAcceptTransfer: (String) -> Unit,
    onRejectTransfer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(timelineItems.size) {
        if (timelineItems.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (timelineItems.isEmpty()) {
            item(contentType = "empty") {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = stringResource(R.string.no_messages_title),
                    body = stringResource(R.string.no_messages_body),
                )
            }
        } else {
            items(
                items = timelineItems,
                key = { it.id },
            ) { item ->
                when (item) {
                    is TimelineItem.Message -> {
                        MessageBubble(message = item.message)
                    }
                    is TimelineItem.Transfer -> {
                        TransferBubble(
                            transfer = item.transfer,
                            onAccept = { onAcceptTransfer(item.transfer.sessionId) },
                            onReject = { onRejectTransfer(item.transfer.sessionId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: TextMessage, modifier: Modifier = Modifier) {
    val outgoing = message.direction == MessageDirection.Outgoing
    val timeText = remember(message.createdAt) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAt))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (outgoing) 16.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 16.dp,
            ),
            color = if (outgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (outgoing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.widthIn(min = 40.dp, max = 320.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        val isFailed = message.route == "failed"
        val isSending = message.route == "sending"
        val routeName = when (message.route) {
            "lan" -> stringResource(R.string.route_lan)
            "cloud" -> stringResource(R.string.route_cloud)
            "sending" -> stringResource(R.string.route_sending)
            "failed" -> stringResource(R.string.route_failed)
            else -> message.route
        }
        val textColor = when {
            isFailed -> MaterialTheme.colorScheme.error
            isSending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        }
        Text(
            text = "${timeText} · ${routeName}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = textColor,
            modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
        )
    }
}

@Composable
private fun TransferBubble(
    transfer: FileTransfer,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outgoing = transfer.direction == FileTransferDirection.Outgoing
    val timeText = remember(transfer.updatedAt) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(transfer.updatedAt))
    }
    val sizeText = remember(transfer.fileSize) {
        formatSize(transfer.fileSize)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (outgoing) 16.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 16.dp,
            ),
            color = if (outgoing) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (outgoing) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = if (outgoing) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                },
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (transfer.direction == FileTransferDirection.Incoming) {
                                Icons.Default.FileUpload
                            } else {
                                Icons.Default.UploadFile
                            },
                            contentDescription = null,
                            tint = if (outgoing) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transfer.fileName.ifBlank { stringResource(R.string.unnamed_file) },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = sizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (outgoing) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                if (transfer.totalChunks > 0 && transfer.status in setOf("receiving", "sending", "verifying")) {
                    val progress = (transfer.transferredBytes.toFloat() / transfer.fileSize.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (transfer.status == "verifying") {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                            trackColor = if (outgoing) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                            trackColor = if (outgoing) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val statusText = statusLabel(transfer.status)
                    BadgeChip(
                        text = statusText,
                        containerColor = if (transfer.status == "completed") {
                            if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer
                        } else {
                            if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (transfer.status == "completed") {
                            if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )

                    if (
                        transfer.direction == FileTransferDirection.Incoming &&
                        transfer.status == "offered"
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = onReject,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.reject_btn))
                        }
                        Button(
                            onClick = onAccept,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(stringResource(R.string.accept_btn))
                        }
                    }
                }

                transfer.error?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = com.colink.android.util.ProtocolReasonFormatter.format(LocalContext.current, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        val routeName = when (transfer.route) {
            "lan" -> stringResource(R.string.route_lan)
            "cloud" -> stringResource(R.string.route_cloud)
            else -> transfer.route
        }
        Text(
            text = "${timeText} · ${routeName}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
        )
    }
}

@Composable
private fun SelectDeviceDialog(
    devices: List<Device>,
    initialDeviceId: String? = null,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var selectedId by remember(devices, initialDeviceId) {
        mutableStateOf(
            devices.firstOrNull { it.deviceId == initialDeviceId }?.deviceId
                ?: devices.firstOrNull()?.deviceId,
        )
    }

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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = devices,
                        key = { it.deviceId },
                    ) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = device.deviceId }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(
                                selected = selectedId == device.deviceId,
                                onClick = { selectedId = device.deviceId },
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
                                    color = MaterialTheme.colorScheme.primary,
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
                enabled = selectedId != null,
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

@Composable
private fun statusLabel(status: String): String =
    when (status) {
        "completed" -> stringResource(R.string.status_completed)
        "receiving" -> stringResource(R.string.status_receiving)
        "sending" -> stringResource(R.string.status_sending)
        "verifying" -> stringResource(R.string.status_verifying)
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

private fun deviceTypeIcon(type: String) =
    when (type.lowercase()) {
        "windows" -> Icons.Default.DesktopWindows
        "macos" -> Icons.Default.LaptopMac
        "linux" -> Icons.Default.Computer
        "android" -> Icons.Default.Android
        "ios" -> Icons.Default.PhoneIphone
        else -> Icons.Default.Devices
    }
