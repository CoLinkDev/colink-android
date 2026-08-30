package com.colink.android.ui.devices

import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.ui.components.BadgeChip
import com.colink.android.ui.components.ContentGroupHeader
import com.colink.android.ui.components.ContentGroupItem
import com.colink.android.ui.components.ContentGroupSpacing
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.LocalAccountAction
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.components.ScreenHeader
import com.colink.android.ui.components.ScreenHeaderHeight
import com.google.android.gms.tasks.OnFailureListener
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val accountAction = LocalAccountAction.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var showPairingSheet by rememberSaveable { mutableStateOf(false) }
    var pairStringRequestPending by remember { mutableStateOf(false) }

    fun closeSearch() {
        searchQuery = ""
        isSearchActive = false
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

    fun startPairStringScan() {
        pairStringScanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let { pairString ->
                    showPairingSheet = false
                    pairStringRequestPending = false
                    viewModel.dismissPairString()
                    viewModel.startPairStringPairing(pairString)
                } ?: Toast.makeText(
                    context,
                    R.string.err_pair_qr_invalid,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .addOnCanceledListener {
                // Returning from the scanner is a normal user cancellation.
            }
            .addOnFailureListener(
                OnFailureListener { error ->
                    if (!isCodeScannerCancellation(error)) {
                        Toast.makeText(
                            context,
                            R.string.pair_qr_scanner_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
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

    val filteredDevices = remember(sortedDevices, searchQuery) {
        if (searchQuery.isBlank()) {
            sortedDevices
        } else {
            val q = searchQuery.trim()
            sortedDevices.filter { device ->
                device.name.contains(q, ignoreCase = true) ||
                    device.deviceId.contains(q, ignoreCase = true)
            }
        }
    }
    val filteredLanPairingCandidates = remember(sortedLanPairingCandidates, searchQuery) {
        if (searchQuery.isBlank()) {
            sortedLanPairingCandidates
        } else {
            val q = searchQuery.trim()
            sortedLanPairingCandidates.filter { candidate ->
                candidate.name.contains(q, ignoreCase = true) ||
                    candidate.deviceId.contains(q, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(uiState.message) {
        val msg = uiState.message
        if (!msg.isNullOrBlank()) {
            if (showPairingSheet && uiState.pairString == null) {
                showPairingSheet = false
                pairStringRequestPending = false
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(
        showPairingSheet,
        pairStringRequestPending,
        uiState.pairString,
        uiState.pairStringLoading,
    ) {
        when {
            uiState.pairStringLoading || uiState.pairString != null -> {
                pairStringRequestPending = false
            }
            showPairingSheet && !pairStringRequestPending -> {
                showPairingSheet = false
            }
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

    BackHandler(enabled = isSearchActive) {
        closeSearch()
    }

    ScreenColumn(
        title = stringResource(R.string.nav_devices),
        modifier = modifier,
        headerOverride = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScreenHeaderHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                AnimatedVisibility(
                    visible = !isSearchActive,
                    enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                    exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                ) {
                    ScreenHeader(
                        title = stringResource(R.string.nav_devices),
                        icon = Icons.Default.Devices,
                        action = {
                            Row {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = stringResource(R.string.search_devices_action),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        pairStringRequestPending = true
                                        viewModel.createPairString()
                                        showPairingSheet = true
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode,
                                        contentDescription = stringResource(R.string.pair_qr_action),
                                    )
                                }
                                if (!isLandscape) {
                                    accountAction?.invoke()
                                }
                            }
                        },
                    )
                }

                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        scaleIn(
                            transformOrigin = TransformOrigin(0.88f, 0.5f),
                            initialScale = 0.7f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ),
                    exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                        scaleOut(
                            transformOrigin = TransformOrigin(0.88f, 0.5f),
                            targetScale = 0.7f,
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                        ),
                ) {
                    DeviceSearchHeader(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onClose = {
                            closeSearch()
                        },
                        modifier = Modifier.fillMaxSize(),
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
            DeviceListContent(
                devices = filteredDevices,
                lanPairingCandidates = filteredLanPairingCandidates,
                localDeviceId = uiState.localDeviceId,
                emptyTitle = if (searchQuery.isBlank()) {
                    stringResource(R.string.no_devices_title)
                } else {
                    stringResource(R.string.search_no_results)
                },
                emptyBody = if (searchQuery.isBlank()) {
                    stringResource(R.string.no_devices_body)
                } else {
                    ""
                },
                onPairCandidate = { viewModel.startLanPairing(it) },
                onDeviceSelected = { deviceId ->
                    if (isSearchActive) {
                        closeSearch()
                    }
                    onDeviceSelected(deviceId)
                },
            )
        }
    }

    if (showPairingSheet) {
        val pairingSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = !isLandscape,
        )
        ModalBottomSheet(
            onDismissRequest = {
                showPairingSheet = false
                pairStringRequestPending = false
                viewModel.dismissPairString()
            },
            sheetState = pairingSheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val pairString = uiState.pairString
                Text(
                    text = stringResource(R.string.pair_qr_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.pair_qr_description),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (uiState.pairStringLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(256.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (pairString != null) {
                    PairStringQrCode(pairString)
                }
                TextButton(
                    onClick = {
                        pairStringRequestPending = true
                        viewModel.createPairString(!uiState.legacyPairString)
                    },
                    enabled = pairString != null && !uiState.pairStringLoading,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(
                        stringResource(
                            if (uiState.legacyPairString) {
                                R.string.pair_qr_switch_to_new
                            } else {
                                R.string.pair_qr_switch_to_legacy
                            },
                        ),
                    )
                }
                FilledTonalButton(
                    onClick = ::startPairStringScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.pair_qr_scan))
                }
            }
        }
    }
}

@Composable
private fun DeviceSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_devices_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceListContent(
    devices: List<Device>,
    lanPairingCandidates: List<LanPairingCandidate>,
    localDeviceId: String?,
    emptyTitle: String,
    emptyBody: String = "",
    onPairCandidate: (String) -> Unit,
    onDeviceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var keepTopAnchored by remember { mutableStateOf(true) }
    var trackedScrollInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            trackedScrollInProgress = true
            keepTopAnchored = false
        } else if (trackedScrollInProgress) {
            trackedScrollInProgress = false
            keepTopAnchored = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(devices, lanPairingCandidates) {
        if (keepTopAnchored) {
            listState.scrollToItem(0)
        }
    }

    DisposableEffect(lifecycleOwner, listState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keepTopAnchored = true
                coroutineScope.launch {
                    listState.scrollToItem(0)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (devices.isEmpty() && lanPairingCandidates.isEmpty()) {
            item(contentType = "empty") {
                EmptyState(
                    icon = Icons.Default.Devices,
                    title = emptyTitle,
                    body = emptyBody,
                )
            }
        } else {
            if (lanPairingCandidates.isNotEmpty()) {
                item(
                    key = "pairing-device-group-header",
                    contentType = "deviceGroupHeader",
                ) {
                    ContentGroupHeader(
                        title = stringResource(R.string.devices_section_pairable),
                    )
                }
                itemsIndexed(
                    items = lanPairingCandidates,
                    key = { _, candidate -> "pairing-device-${candidate.deviceId}" },
                    contentType = { _, _ -> "pairingDevice" },
                ) { index, candidate ->
                    ContentGroupItem(
                        isFirst = index == 0,
                        isLast = index == lanPairingCandidates.lastIndex,
                        modifier = Modifier.animateItem(),
                    ) {
                        LanPairingCandidateItem(
                            candidate = candidate,
                            onPair = { onPairCandidate(candidate.deviceId) },
                        )
                    }
                }
            }
            if (devices.isNotEmpty()) {
                if (lanPairingCandidates.isNotEmpty()) {
                    item(
                        key = "device-group-gap",
                        contentType = "deviceGroupGap",
                    ) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ContentGroupSpacing),
                        )
                    }
                }
                item(
                    key = "device-group-header",
                    contentType = "deviceGroupHeader",
                ) {
                    ContentGroupHeader(title = stringResource(R.string.devices_section_mine))
                }
                itemsIndexed(
                    items = devices,
                    key = { _, device -> "device-${device.deviceId}" },
                    contentType = { _, _ -> "device" },
                ) { index, device ->
                    ContentGroupItem(
                        isFirst = index == 0,
                        isLast = index == devices.lastIndex,
                        modifier = Modifier.animateItem(),
                    ) {
                        DeviceItem(
                            name = device.name,
                            type = device.type,
                            lanAvailable = device.lanAvailable,
                            lanState = device.lanState,
                            online = device.online,
                            cloudAvailable = device.cloudAvailable,
                            isLocalDevice = device.deviceId == localDeviceId ||
                                device.deviceSources.contains("local"),
                            onClick = { onDeviceSelected(device.deviceId) },
                        )
                    }
                }
            }
        }
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
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.pair_qr_title),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 256.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
    }
}

@Composable
private fun LanPairingCandidateItem(
    candidate: LanPairingCandidate,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPair)
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
            if (candidate.state == "suspect") {
                BadgeChip(
                    text = stringResource(R.string.device_tag_lan_suspect),
                    modifier = Modifier.padding(top = 2.dp),
                    icon = Icons.Default.Wifi,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.pair_btn),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun DeviceItem(
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
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

internal fun isCodeScannerCancellation(error: Exception): Boolean =
    error is java.util.concurrent.CancellationException ||
        (error is MlKitException &&
            error.errorCode in setOf(
                MlKitException.CANCELLED,
                MlKitException.CODE_SCANNER_CANCELLED,
                // play-services-code-scanner maps an empty Activity result to INTERNAL.
                // This is how its scanner Activity reports that the user returned without a code.
                MlKitException.INTERNAL,
            ))
