package com.colink.android.ui.devices

import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
import com.colink.android.network.RemoteCameraSupport
import com.colink.android.network.PeerProtocolVersions
import com.colink.android.network.SystemControlSupport
import com.colink.android.ui.camera.CameraControlCard
import com.colink.android.ui.castboard.CastBoardControlCard
import com.colink.android.ui.castboard.CastBoardViewModel
import com.colink.android.ui.components.CoLinkTextField
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.WarningCard
import com.colink.android.ui.components.isComputerDevice
import com.colink.android.ui.devicecontrol.DeviceMediaControlCard
import com.colink.android.ui.devicecontrol.DeviceMediaControlViewModel
import com.colink.android.ui.devicecontrol.DevicePowerControlCard
import com.colink.android.ui.devicecontrol.DevicePowerControlViewModel
import com.colink.android.ui.devicecontrol.WakeOnLanControlCard
import com.colink.android.ui.terminal.TerminalControlCard
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    deviceId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit = {},
    onStartCastBoard: (String) -> Unit = {},
    onStartTerminal: (String) -> Unit = {},
    onStartCamera: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = hiltViewModel(),
    castBoardViewModel: CastBoardViewModel = hiltViewModel(),
    powerControlViewModel: DevicePowerControlViewModel = hiltViewModel(),
    mediaControlViewModel: DeviceMediaControlViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val peerProtocolVersions by viewModel.peerProtocolVersions.collectAsStateWithLifecycle()
    val peerVersionRequestStates by viewModel.peerVersionRequestStates.collectAsStateWithLifecycle()
    val device = remember(devices, deviceId) {
        devices.firstOrNull { it.deviceId == deviceId }
    }
    val isLocalDevice = device?.deviceId == uiState.localDeviceId ||
        device?.deviceSources?.contains("local") == true
    var confirmAction by remember { mutableStateOf<DeviceAction?>(null) }
    var runningAction by remember { mutableStateOf<DeviceAction?>(null) }
    var isWarningDismissed by rememberSaveable(deviceId) { mutableStateOf(false) }
    val context = LocalContext.current
    val isComputer = device?.let(::isComputerDevice) == true
    val isRemoteDevice = !isLocalDevice && device != null
    val isReachable = device?.let { it.online || it.lanAvailable } == true

    val deviceProtocolVersions = peerProtocolVersions[deviceId]
    val deviceCapabilities = remember(deviceId, devices, deviceProtocolVersions) {
        DeviceCapabilities(
            powerSupport = powerControlViewModel.systemControlSupport(deviceId),
            mediaSupport = mediaControlViewModel.mediaControlSupport(deviceId),
            cameraSupport = powerControlViewModel.remoteCameraSupport(deviceId),
            terminalSupport = powerControlViewModel.terminalSupport(deviceId),
            wakeOnLanSupport = powerControlViewModel.wakeOnLanSupport(deviceId),
            delayedPowerSupport = powerControlViewModel.delayedPowerControlSupport(deviceId),
            systemControlQuerySupport = mediaControlViewModel.systemControlQuerySupport(deviceId),
            pendingPowerQuerySupport = powerControlViewModel.pendingPowerQuerySupport(deviceId),
        )
    }
    val peerVersionRequestState = peerVersionRequestStates[deviceId]
    val hasBusinessVersion = !deviceProtocolVersions?.businessVersion.isNullOrBlank()
    val waitingForPeerVersion = isRemoteDevice &&
        isReachable &&
        !hasBusinessVersion &&
        peerVersionRequestState?.failed != true
    val peerVersionFailed = isRemoteDevice &&
        isReachable &&
        !hasBusinessVersion &&
        peerVersionRequestState?.failed == true

    LaunchedEffect(isRemoteDevice, isReachable, isComputer) {
        if (isRemoteDevice && isReachable && isComputer) {
            powerControlViewModel.selectDevice(deviceId)
            castBoardViewModel.selectDevice(deviceId)
            mediaControlViewModel.selectDevice(deviceId)
            mediaControlViewModel.startSystemStatePolling()
        } else {
            mediaControlViewModel.stopSystemStatePolling()
        }
    }

    LaunchedEffect(deviceId, isRemoteDevice, isReachable) {
        if (isRemoteDevice && isReachable) {
            viewModel.requestPeerProtocolVersions(deviceId)
        }
    }

    DisposableEffect(Unit) {
        onDispose(mediaControlViewModel::stopSystemStatePolling)
    }

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
            val disabledFeatures = disabledFeatureNames(deviceCapabilities)
            if (waitingForPeerVersion) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (peerVersionFailed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.device_version_unknown_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.device_version_unknown_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = { viewModel.retryPeerProtocolVersions(deviceId) },
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.retry_btn))
                        }
                    }
                }
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                val isWideLayout = maxWidth >= 600.dp
                val contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)

                if (isWideLayout) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AnimatedVisibility(
                            visible = !isWarningDismissed && disabledFeatures.isNotEmpty(),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            WarningCard(
                                title = stringResource(R.string.device_version_old_warning_title),
                                body = disabledFeatures.joinToString(stringResource(R.string.detail_list_separator)),
                                actionLabel = stringResource(R.string.dismiss_btn),
                                onAction = { isWarningDismissed = true },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentPadding = contentPadding,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                deviceControlItems(
                                    device = device,
                                    isRemoteDevice = isRemoteDevice,
                                    isReachable = isReachable,
                                    isComputer = isComputer,
                                    capabilities = deviceCapabilities,
                                    onOpenChat = onOpenChat,
                                    onStartCamera = onStartCamera,
                                    onStartTerminal = onStartTerminal,
                                    onStartCastBoard = onStartCastBoard,
                                    castBoardViewModel = castBoardViewModel,
                                    mediaControlViewModel = mediaControlViewModel,
                                    powerControlViewModel = powerControlViewModel,
                                )
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentPadding = contentPadding,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                deviceManagementItems(
                                    device = device,
                                    isRemoteDevice = isRemoteDevice,
                                    isReachable = isReachable,
                                    isLocalDevice = isLocalDevice,
                                    actionsEnabled = runningAction == null,
                                    wakeOnLanSupport = deviceCapabilities.wakeOnLanSupport,
                                    protocolVersions = deviceProtocolVersions,
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
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = contentPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(contentType = "version-warning") {
                            AnimatedVisibility(
                                visible = !isWarningDismissed && disabledFeatures.isNotEmpty(),
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                WarningCard(
                                    title = stringResource(R.string.device_version_old_warning_title),
                                    body = disabledFeatures.joinToString(stringResource(R.string.detail_list_separator)),
                                    actionLabel = stringResource(R.string.dismiss_btn),
                                    onAction = { isWarningDismissed = true },
                                )
                            }
                        }
                        deviceControlItems(
                            device = device,
                            isRemoteDevice = isRemoteDevice,
                            isReachable = isReachable,
                            isComputer = isComputer,
                            capabilities = deviceCapabilities,
                            onOpenChat = onOpenChat,
                            onStartCamera = onStartCamera,
                            onStartTerminal = onStartTerminal,
                            onStartCastBoard = onStartCastBoard,
                            castBoardViewModel = castBoardViewModel,
                            mediaControlViewModel = mediaControlViewModel,
                            powerControlViewModel = powerControlViewModel,
                        )
                        deviceManagementItems(
                            device = device,
                            isRemoteDevice = isRemoteDevice,
                            isReachable = isReachable,
                            isLocalDevice = isLocalDevice,
                            actionsEnabled = runningAction == null,
                            wakeOnLanSupport = deviceCapabilities.wakeOnLanSupport,
                            protocolVersions = deviceProtocolVersions,
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
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
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

private data class DeviceCapabilities(
    val powerSupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
    val mediaSupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
    val cameraSupport: RemoteCameraSupport = RemoteCameraSupport.UNKNOWN,
    val terminalSupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
    val wakeOnLanSupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
    val delayedPowerSupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
    val systemControlQuerySupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
    val pendingPowerQuerySupport: SystemControlSupport = SystemControlSupport.UNKNOWN,
)

@Composable
private fun disabledFeatureNames(capabilities: DeviceCapabilities): List<String> {
    val names = mutableListOf<String>()
    if (capabilities.cameraSupport == RemoteCameraSupport.TOO_OLD) {
        names += stringResource(R.string.device_control_camera)
    }
    if (capabilities.terminalSupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_control_terminal)
    }
    if (capabilities.wakeOnLanSupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_wake_on_lan_title)
    }
    if (capabilities.mediaSupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_media_control_title)
    }
    if (capabilities.powerSupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_warning_power_control)
    }
    if (capabilities.delayedPowerSupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_warning_scheduled_power)
    }
    if (capabilities.pendingPowerQuerySupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_warning_pending_power)
    }
    if (capabilities.systemControlQuerySupport == SystemControlSupport.TOO_OLD) {
        names += stringResource(R.string.device_warning_state_query)
    }
    return names
}

private fun LazyListScope.deviceControlItems(
    device: Device,
    isRemoteDevice: Boolean,
    isReachable: Boolean,
    isComputer: Boolean,
    capabilities: DeviceCapabilities,
    onOpenChat: (String) -> Unit,
    onStartCamera: (String) -> Unit,
    onStartTerminal: (String) -> Unit,
    onStartCastBoard: (String) -> Unit,
    castBoardViewModel: CastBoardViewModel,
    mediaControlViewModel: DeviceMediaControlViewModel,
    powerControlViewModel: DevicePowerControlViewModel,
) {
    if (isRemoteDevice && isReachable) {
        item(contentType = "chat") {
            ChatEntryCard(onClick = { onOpenChat(device.deviceId) })
        }
        if (capabilities.cameraSupport == RemoteCameraSupport.SUPPORTED) {
            item(contentType = "camera") {
                CameraControlCard(
                    deviceId = device.deviceId,
                    onOpen = onStartCamera,
                    support = capabilities.cameraSupport,
                )
            }
        }
    }
    if (isRemoteDevice && isReachable && isComputer) {
        if (capabilities.terminalSupport == SystemControlSupport.SUPPORTED) {
            item(contentType = "terminal") {
                TerminalControlCard(
                    deviceId = device.deviceId,
                    onOpen = onStartTerminal,
                    support = capabilities.terminalSupport,
                )
            }
        }
        item(contentType = "castboard") {
            CastBoardControlCard(
                onStartFullscreen = onStartCastBoard,
                viewModel = castBoardViewModel,
            )
        }
        if (capabilities.mediaSupport == SystemControlSupport.SUPPORTED) {
            item(contentType = "media") {
                DeviceMediaControlCard(
                    hasAvailableDevice = true,
                    support = capabilities.mediaSupport,
                    querySupport = capabilities.systemControlQuerySupport,
                    viewModel = mediaControlViewModel,
                )
            }
        }
        if (capabilities.powerSupport == SystemControlSupport.SUPPORTED) {
            item(contentType = "power") {
                DevicePowerControlCard(
                    support = capabilities.powerSupport,
                    pendingPowerQuerySupport = capabilities.pendingPowerQuerySupport,
                    viewModel = powerControlViewModel,
                )
            }
        }
    }
}

private fun LazyListScope.deviceManagementItems(
    device: Device,
    isRemoteDevice: Boolean,
    isReachable: Boolean,
    isLocalDevice: Boolean,
    actionsEnabled: Boolean,
    wakeOnLanSupport: SystemControlSupport,
    protocolVersions: PeerProtocolVersions?,
    onRotateKey: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onForgetTrust: () -> Unit,
) {
    val showWakeOnLan = isLocalDevice ||
        (isRemoteDevice && isReachable && wakeOnLanSupport == SystemControlSupport.SUPPORTED)
    if (showWakeOnLan) {
        item(contentType = "wol") {
            WakeOnLanControlCard(
                selectedDevice = device,
                support = if (isLocalDevice) SystemControlSupport.SUPPORTED else wakeOnLanSupport,
                sendFromLocal = isLocalDevice,
            )
        }
    }
    item(contentType = "info") {
        DeviceInformationCard(
            device = device,
            isLocalDevice = isLocalDevice,
            protocolVersions = protocolVersions,
            actionsEnabled = actionsEnabled,
            onRotateKey = onRotateKey,
            onRename = onRename,
            onDelete = onDelete,
            onForgetTrust = onForgetTrust,
        )
    }
}

@Composable
private fun ChatEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_swap_horizontal),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.enter_chat_btn),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.chat_card_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DeviceActionButtons(
    device: Device,
    isLocalDevice: Boolean,
    actionsEnabled: Boolean,
    onRotateKey: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onForgetTrust: () -> Unit,
) {
    val canDelete = !isLocalDevice && !device.online && device.deviceSources.contains("cloud")
    val canForgetTrust = !isLocalDevice &&
        device.deviceSources.contains("trusted_peer_key")

    if (!isLocalDevice && !canDelete && !canForgetTrust) {
        return
    }

    FlowRow(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isLocalDevice) {
            Button(onClick = onRename, enabled = actionsEnabled) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.rename_device_btn))
            }
            Button(onClick = onRotateKey, enabled = actionsEnabled) {
                Icon(Icons.Default.VpnKey, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.rotate_key_title))
            }
        }
        if (canDelete) {
            OutlinedButton(onClick = onDelete, enabled = actionsEnabled) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.delete_from_cloud_btn))
            }
        }
        if (canForgetTrust) {
            OutlinedButton(onClick = onForgetTrust, enabled = actionsEnabled) {
                Icon(Icons.Default.LinkOff, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.forget_lan_trust_btn))
            }
        }
    }
}

@Composable
private fun DeviceInformationCard(
    device: Device,
    isLocalDevice: Boolean,
    protocolVersions: PeerProtocolVersions?,
    actionsEnabled: Boolean,
    onRotateKey: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onForgetTrust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fingerprint = remember(device.publicKey) {
        publicKeyFingerprint(device.publicKey)
    }
    val rows = listOf(
        DetailRowData(stringResource(R.string.label_name), device.name.ifBlank { stringResource(R.string.unnamed_device) }),
        DetailRowData(stringResource(R.string.label_device_id), device.deviceId, mono = true),
        DetailRowData(stringResource(R.string.label_platform), formatPlatformName(device.type)),
        DetailRowData(
            stringResource(R.string.label_protocol_versions),
            formatProtocolVersions(protocolVersions),
        ),
        DetailRowData(stringResource(R.string.label_fetch_source), describeSources(device, isLocalDevice)),
        DetailRowData(stringResource(R.string.label_reachability), formatReachability(device)),
        DetailRowData(stringResource(R.string.label_trust_source), formatTrustSources(device)),
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
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
            DeviceActionButtons(
                device = device,
                isLocalDevice = isLocalDevice,
                actionsEnabled = actionsEnabled,
                onRotateKey = onRotateKey,
                onRename = onRename,
                onDelete = onDelete,
                onForgetTrust = onForgetTrust,
            )
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
                CoLinkTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.device_name_label)) },
                    singleLine = true,
                    isError = nameError,
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
    return sources
        .ifEmpty { listOf(stringResource(R.string.source_server_device_list)) }
        .joinToString(stringResource(R.string.detail_list_separator))
}

private fun lanEndpoint(device: Device): String? {
    val ip = device.localIp?.takeIf { it.isNotBlank() } ?: return null
    val port = device.localPort
    return if (port != null && port > 0) "$ip:$port" else ip
}

@Composable
private fun formatProtocolVersions(versions: PeerProtocolVersions?): String =
    buildList {
        versions?.p2pVersion?.takeIf(String::isNotBlank)?.let { add("P2P-v$it") }
        versions?.businessVersion?.takeIf(String::isNotBlank)?.let { add("Business-v$it") }
    }.ifEmpty {
        listOf(stringResource(R.string.value_none))
    }.joinToString(stringResource(R.string.detail_list_separator))

@Composable
private fun formatReachability(device: Device): String =
    buildList {
        if (device.cloudAvailable) add(stringResource(R.string.value_cloud))
        if (device.lanAvailable) {
            val lan = stringResource(R.string.value_lan)
            val endpoint = lanEndpoint(device)
            add(
                endpoint?.let {
                    stringResource(R.string.reachability_lan_with_endpoint, lan, it)
                } ?: lan,
            )
        }
    }.ifEmpty {
        listOf(stringResource(R.string.value_unreachable))
    }.joinToString(stringResource(R.string.detail_list_separator))

@Composable
private fun formatTrustSources(device: Device): String =
    buildList {
        if (device.trustedByCloud) add(stringResource(R.string.value_cloud))
        if (device.trustedByLan) add(stringResource(R.string.value_lan))
    }.ifEmpty {
        listOf(stringResource(R.string.value_untrusted))
    }.joinToString(stringResource(R.string.detail_list_separator))

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
