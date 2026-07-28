package com.colink.android.ui.devices

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.google.android.gms.tasks.OnFailureListener
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    modifier: Modifier = Modifier,
    onDeviceSelected: (String) -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val lanPairingCandidates by viewModel.lanPairingCandidates.collectAsStateWithLifecycle()
    val lanConnectionError by viewModel.lanConnectionError.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val availableDeviceCount = remember(devices) {
        devices.count { device -> device.online || device.lanAvailable }
    }
    val pairStringScanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build(),
        )
    }
    val sortedDevices = remember(devices, uiState.localDeviceId) {
        devices.sortedWith(
            compareBy<Device> {
                it.deviceId == uiState.localDeviceId || it.deviceSources.contains("local")
            }.thenByDescending { it.online || it.lanAvailable }
                .thenBy { it.name.ifBlank { it.deviceId } }
                .thenBy { it.deviceId },
        )
    }
    val sortedLanPairingCandidates = remember(lanPairingCandidates) {
        lanPairingCandidates.sortedWith(
            compareBy<LanPairingCandidate> { it.name.ifBlank { it.deviceId } }
                .thenBy { it.deviceId },
        )
    }

    LaunchedEffect(uiState.message) {
        val msg = uiState.message
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    lanConnectionError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearLanConnectionError,
            title = { Text(stringResource(R.string.lan_connection_failed_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::clearLanConnectionError) {
                    Text(stringResource(R.string.lan_pairing_close))
                }
            },
        )
    }

    ScreenColumn(
        title = stringResource(R.string.nav_devices),
        subtitle = stringResource(
            R.string.devices_subtitle,
            availableDeviceCount,
            devices.size
        ),
        modifier = modifier,
        action = {
            Row {
                IconButton(
                    onClick = {
                        pairStringScanner.startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let(viewModel::startPairStringPairing)
                            }
                            .addOnFailureListener(
                                OnFailureListener {
                                    Toast.makeText(
                                        context,
                                        R.string.pair_qr_scanner_unavailable,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = stringResource(R.string.pair_qr_scan),
                    )
                }
                IconButton(onClick = viewModel::createPairString) {
                    Icon(
                        imageVector = Icons.Default.QrCode,
                        contentDescription = stringResource(R.string.pair_qr_show),
                    )
                }
            }
        },
    ) {
        PullToRefreshBox(
            isRefreshing = uiState.loading,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 600.dp) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (devices.isEmpty() && lanPairingCandidates.isEmpty()) {
                            item(contentType = "empty") {
                                EmptyState(
                                    icon = Icons.Default.Devices,
                                    title = stringResource(R.string.no_devices_title),
                                    body = stringResource(R.string.no_devices_body),
                                )
                            }
                        } else {
                            gridItems(
                                items = sortedLanPairingCandidates,
                                key = { "lan-pairing-${it.deviceId}" },
                                contentType = { "lanPairingCandidate" },
                            ) { candidate ->
                                LanPairingCandidateCard(
                                    candidate = candidate,
                                    onPair = { viewModel.startLanPairing(candidate.deviceId) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            gridItems(
                                items = sortedDevices,
                                key = { it.deviceId },
                                contentType = { "device" },
                            ) { device ->
                                DeviceCard(
                                    name = device.name,
                                    type = device.type,
                                    lanAvailable = device.lanAvailable,
                                    lanState = device.lanState,
                                    online = device.online,
                                    cloudAvailable = device.cloudAvailable,
                                    isLocalDevice = device.deviceId == uiState.localDeviceId ||
                                        device.deviceSources.contains("local"),
                                    onClick = { onDeviceSelected(device.deviceId) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (devices.isEmpty() && lanPairingCandidates.isEmpty()) {
                            item(contentType = "empty") {
                                EmptyState(
                                    icon = Icons.Default.Devices,
                                    title = stringResource(R.string.no_devices_title),
                                    body = stringResource(R.string.no_devices_body),
                                )
                            }
                        } else {
                            items(
                                items = sortedLanPairingCandidates,
                                key = { "lan-pairing-${it.deviceId}" },
                                contentType = { "lanPairingCandidate" },
                            ) { candidate ->
                                LanPairingCandidateCard(
                                    candidate = candidate,
                                    onPair = { viewModel.startLanPairing(candidate.deviceId) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                            items(
                                items = sortedDevices,
                                key = { it.deviceId },
                                contentType = { "device" },
                            ) { device ->
                                DeviceCard(
                                    name = device.name,
                                    type = device.type,
                                    lanAvailable = device.lanAvailable,
                                    lanState = device.lanState,
                                    online = device.online,
                                    cloudAvailable = device.cloudAvailable,
                                    isLocalDevice = device.deviceId == uiState.localDeviceId ||
                                        device.deviceSources.contains("local"),
                                    onClick = { onDeviceSelected(device.deviceId) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.pairString?.let { pairString ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPairString,
            title = { Text(stringResource(R.string.pair_qr_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.pair_qr_description))
                    PairStringQrCode(pairString)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissPairString) {
                    Text(stringResource(R.string.lan_pairing_close))
                }
            },
        )
    }
}

@Composable
private fun PairStringQrCode(value: String) {
    val image = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = stringResource(R.string.pair_qr_title),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().size(256.dp),
    )
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
                    imageVector = deviceTypeIcon(candidate.type),
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
                        text = stringResource(R.string.lan_pairing_title),
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
                Text(stringResource(R.string.pair_btn), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    type: String,
    lanAvailable: Boolean,
    lanState: String,
    online: Boolean,
    cloudAvailable: Boolean,
    isLocalDevice: Boolean,
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
                        color = if (lanAvailable || online) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = deviceTypeIcon(type),
                    contentDescription = null,
                    tint = if (lanAvailable || online) {
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
                    text = name.ifBlank { stringResource(R.string.unnamed_device) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    if (isLocalDevice) {
                        BadgeChip(
                            text = stringResource(R.string.device_tag_local),
                            icon = Icons.Default.Computer,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else if (lanAvailable || lanState == "suspect" || online) {
                        if (lanState == "suspect") {
                            BadgeChip(
                                text = stringResource(R.string.device_tag_lan_suspect),
                                icon = Icons.Default.Wifi,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else if (lanAvailable) {
                            BadgeChip(
                                text = stringResource(R.string.route_lan),
                                icon = Icons.Default.Wifi,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        if (cloudAvailable) {
                            BadgeChip(
                                text = stringResource(R.string.device_tag_cloud),
                                icon = Icons.Default.Cloud,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    } else {
                        BadgeChip(
                            text = stringResource(R.string.device_tag_offline),
                            icon = Icons.Default.CloudOff,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun deviceTypeIcon(type: String): ImageVector =
    when (type.lowercase()) {
        "windows" -> Icons.Default.DesktopWindows
        "macos" -> Icons.Default.LaptopMac
        "linux" -> Icons.Default.Terminal
        "android" -> Icons.Default.Android
        "ios" -> Icons.Default.PhoneIphone
        else -> Icons.Default.Devices
    }
