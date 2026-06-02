package com.colink.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.colink.android.R
import com.colink.android.domain.model.CloudStatus
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.service.CoLinkService
import com.colink.android.ui.auth.CloudAccountScreen
import com.colink.android.ui.devices.DeviceDetailsScreen
import com.colink.android.ui.components.LoadingScreen
import com.colink.android.ui.devices.DeviceListScreen
import com.colink.android.ui.messages.MessageScreen
import com.colink.android.ui.settings.SettingsScreen
import com.colink.android.ui.transfers.TransfersScreen

private data class TopLevelRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelRoutes =
    listOf(
        TopLevelRoute("devices", "Devices", Icons.Default.Devices),
        TopLevelRoute("messages", "Messages", Icons.AutoMirrored.Filled.Chat),
        TopLevelRoute("transfers", "Transfers", Icons.Default.SwapHoriz),
        TopLevelRoute("settings", "Settings", Icons.Default.Settings),
    )

@Composable
fun CoLinkNavGraph(
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    onRequestNotificationPermission: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(uiState.bootstrapping) {
        if (!uiState.bootstrapping) {
            CoLinkService.start(context)
        }
    }

    LaunchedEffect(uiState.bootstrapping, uiState.notificationsEnabled) {
        if (!uiState.bootstrapping && uiState.notificationsEnabled) {
            onRequestNotificationPermission()
        }
    }

    when {
        uiState.bootstrapping -> LoadingScreen(modifier)
        else -> {
            MainScaffold(
                cloudStatus = uiState.cloud.status,
                authenticated = uiState.authenticated,
                onLogout = viewModel::logout,
                pendingShareStore = pendingShareStore,
                modifier = modifier,
            )
            val request = uiState.pairingRequest
            if (request != null) {
                AlertDialog(
                    onDismissRequest = {
                        if (request.error != null) {
                            viewModel.clearPairing(request.requestId)
                        } else if (!request.waiting) {
                            viewModel.respondPairing(request.requestId, false)
                        }
                    },
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    title = { Text("LAN pairing") },
                    text = {
                        val deviceName = request.name.ifBlank { request.deviceId }
                        val body = when {
                            request.error != null ->
                                "$deviceName wants to pair with this device.\nCode: ${request.code}\n\n${request.error}"
                            request.waiting ->
                                "$deviceName wants to pair with this device.\nCode: ${request.code}\n\nWaiting for the other device..."
                            else ->
                                "$deviceName wants to pair with this device.\nCode: ${request.code}"
                        }
                        Text(body)
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !request.waiting,
                            onClick = {
                                if (request.error != null) {
                                    viewModel.clearPairing(request.requestId)
                                } else {
                                    viewModel.respondPairing(request.requestId, true)
                                }
                            },
                        ) {
                            Text(if (request.error != null) "Close" else if (request.waiting) "Waiting" else "Accept")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !request.waiting && request.error == null,
                            onClick = { viewModel.respondPairing(request.requestId, false) },
                        ) {
                            Text("Reject")
                        }
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScaffold(
    cloudStatus: CloudStatus,
    authenticated: Boolean,
    onLogout: () -> Unit,
    pendingShareStore: PendingShareStore?,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val pendingShare by pendingShareStore?.share?.collectAsStateWithLifecycle()
        ?: remember {
            androidx.compose.runtime.mutableStateOf<PendingShare?>(null)
        }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pendingShare) {
        when (pendingShare) {
            is PendingShare.Text -> navController.navigateTopLevel("messages")
            is PendingShare.File -> navController.navigateTopLevel("transfers")
            null -> Unit
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.colink_logo),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                        Text("CoLink")
                    }
                },
                navigationIcon = {
                    Icon(
                        imageVector = if (cloudStatus == CloudStatus.Connected) {
                            Icons.Default.Cloud
                        } else {
                            Icons.Default.CloudOff
                        },
                        contentDescription = cloudStatus.name,
                        tint = if (cloudStatus == CloudStatus.Connected) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                },
                actions = {
                    IconButton(onClick = { navController.navigateAccount() }) {
                        Icon(
                            imageVector = if (authenticated) {
                                Icons.Default.AccountCircle
                            } else {
                                Icons.AutoMirrored.Filled.Login
                            },
                            contentDescription = "Cloud account",
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                topLevelRoutes.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination?.isTopLevelSelected(item.route) == true,
                        onClick = { navController.navigateTopLevel(item.route) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "devices",
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(durationMillis = 150, delayMillis = 50)) },
            exitTransition = { fadeOut(animationSpec = tween(durationMillis = 50)) },
            popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 150, delayMillis = 50)) },
            popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 50)) },
        ) {
            composable("devices") {
                DeviceListScreen(
                    snackbarHostState = snackbarHostState,
                    onDeviceSelected = { deviceId ->
                        navController.navigate("device/${Uri.encode(deviceId)}")
                    },
                )
            }
            composable("device/{deviceId}") { entry ->
                DeviceDetailsScreen(
                    deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                    snackbarHostState = snackbarHostState,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("messages") {
                MessageScreen(
                    snackbarHostState = snackbarHostState,
                    pendingShareStore = pendingShareStore,
                )
            }
            composable("transfers") {
                TransfersScreen(
                    snackbarHostState = snackbarHostState,
                    pendingShareStore = pendingShareStore,
                )
            }
            composable("settings") { SettingsScreen(snackbarHostState = snackbarHostState) }
            composable("cloud-account") {
                CloudAccountScreen(
                    authenticated = authenticated,
                    onLogout = onLogout,
                )
            }
        }
    }
}

private fun androidx.navigation.NavDestination.isTopLevelSelected(route: String): Boolean {
    val selected = hierarchy.any { it.route == route }
    return selected || (route == "devices" && this.route == "device/{deviceId}")
}

private fun androidx.navigation.NavController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun androidx.navigation.NavController.navigateAccount() {
    navigate("cloud-account") {
        launchSingleTop = true
    }
}
