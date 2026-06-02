package com.colink.android.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.RefreshableList
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.SnackbarOnMessage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults

@Composable
fun DeviceListScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onDeviceSelected: (String) -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val lanPairingCandidates by viewModel.lanPairingCandidates.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
            if (devices.isEmpty() && lanPairingCandidates.isEmpty()) {
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
                        onClick = { onDeviceSelected(device.deviceId) },
                        modifier = Modifier.animateItem()
                    )
                }
                items(lanPairingCandidates, key = { "lan-pairing-${it.deviceId}" }) { candidate ->
                    LanPairingCandidateCard(
                        candidate = candidate,
                        onPair = { viewModel.startLanPairing(candidate.deviceId) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LanPairingCandidateCard(
    candidate: LanPairingCandidate,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = candidate.name.ifBlank { candidate.deviceId },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidate.deviceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    BadgeChip(
                        text = "LAN pairing",
                        icon = Icons.Default.SyncAlt,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            TextButton(
                onClick = onPair,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.SyncAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pair", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    isLocalDevice: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        device.lanAvailable -> Icons.Default.Wifi
                        device.online -> Icons.Default.Cloud
                        else -> Icons.Default.Devices
                    },
                    contentDescription = null,
                    tint = if (device.lanAvailable || device.online) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = device.name.ifBlank { "Unnamed device" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (device.lanAvailable) {
                        BadgeChip(
                            text = "LAN",
                            icon = Icons.Default.Wifi,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else if (device.online) {
                        BadgeChip(
                            text = "Cloud",
                            icon = Icons.Default.Cloud,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        BadgeChip(
                            text = "Offline",
                            icon = Icons.Default.CloudOff,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (device.type == "unknown" && device.deviceSources.contains("trusted_peer_key")) {
                        BadgeChip(
                            text = "Trusted",
                            icon = Icons.Default.Verified,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
