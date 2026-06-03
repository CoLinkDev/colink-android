package com.colink.android.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
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
    val labelResId: Int,
    val icon: ImageVector,
)

private val topLevelRoutes =
    listOf(
        TopLevelRoute("devices", R.string.nav_devices, Icons.Default.Devices),
        TopLevelRoute("messages", R.string.nav_messages, Icons.AutoMirrored.Filled.Chat),
        TopLevelRoute("transfers", R.string.nav_transfers, Icons.Default.SwapHoriz),
        TopLevelRoute("settings", R.string.nav_settings, Icons.Default.Settings),
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
                cloudStatus = uiState.cloudStatus,
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
                    title = { Text(stringResource(R.string.lan_pairing_title)) },
                    text = {
                        val deviceName = request.name.ifBlank { request.deviceId }
                        val mainText = stringResource(R.string.lan_pairing_wants_to_pair, deviceName, request.code)
                        val body = when {
                            request.error != null ->
                                "$mainText\n\n${request.error}"
                            request.waiting ->
                                "$mainText\n\n" + stringResource(R.string.lan_pairing_waiting)
                            else ->
                                mainText
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
                            val btnText = if (request.error != null) {
                                stringResource(R.string.lan_pairing_close)
                            } else if (request.waiting) {
                                stringResource(R.string.lan_pairing_waiting_btn)
                            } else {
                                stringResource(R.string.lan_pairing_accept)
                            }
                            Text(btnText)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !request.waiting && request.error == null,
                            onClick = { viewModel.respondPairing(request.requestId, false) },
                        ) {
                            Text(stringResource(R.string.lan_pairing_reject))
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
    val rootNavController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingShare by pendingShareStore?.share?.collectAsStateWithLifecycle()
        ?: remember {
            androidx.compose.runtime.mutableStateOf<PendingShare?>(null)
        }

    NavHost(
        navController = rootNavController,
        startDestination = "main",
        modifier = modifier,
    ) {
        composable("main") {
            val nestedNavController = rememberNavController()

            LaunchedEffect(pendingShare) {
                val share = pendingShare ?: return@LaunchedEffect
                if (rootNavController.currentBackStackEntry?.destination?.route != "main") {
                    rootNavController.popBackStack("main", inclusive = false)
                }
                when (share) {
                    is PendingShare.Text -> nestedNavController.navigateTopLevel("messages")
                    is PendingShare.File -> nestedNavController.navigateTopLevel("transfers")
                }
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        modifier = Modifier.statusBarsPadding(),
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        title = {
                            Text("CoLink", fontWeight = FontWeight.Bold)
                        },
                        actions = {
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
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            IconButton(onClick = { rootNavController.navigate("cloud-account") }) {
                                Icon(
                                    imageVector = if (authenticated) {
                                        Icons.Default.AccountCircle
                                    } else {
                                        Icons.AutoMirrored.Filled.Login
                                    },
                                    contentDescription = stringResource(R.string.account_desc),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    MainBottomBar(navController = nestedNavController)
                },
            ) { innerPadding ->
                NavHost(
                    navController = nestedNavController,
                    startDestination = "devices",
                    modifier = Modifier.padding(innerPadding),
                    enterTransition = { fadeIn(animationSpec = tween(durationMillis = 90)) },
                    exitTransition = { fadeOut(animationSpec = tween(durationMillis = 70)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 90)) },
                    popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 70)) },
                ) {
                    composable("devices") {
                        DeviceListScreen(
                            snackbarHostState = snackbarHostState,
                            onDeviceSelected = { deviceId ->
                                rootNavController.navigate("device/${Uri.encode(deviceId)}")
                            },
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
                }
            }
        }

        composable(
            route = "device/{deviceId}",
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            }
        ) { entry ->
            DeviceDetailsScreen(
                deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                snackbarHostState = snackbarHostState,
                onBack = { rootNavController.popBackStack() },
            )
        }

        composable(
            route = "cloud-account",
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                  towards = AnimatedContentTransitionScope.SlideDirection.Right,
                  animationSpec = tween(300)
                )
            }
        ) {
            CloudAccountScreen(
                authenticated = authenticated,
                onLogout = onLogout,
            )
        }
    }
}

@Composable
private fun MainBottomBar(navController: NavHostController) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    NavigationBar {
        topLevelRoutes.forEach { item ->
            val selected = currentDestination?.isTopLevelSelected(item.route) == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigateTopLevel(item.route)
                    }
                },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelResId)) },
            )
        }
    }
}

private fun androidx.navigation.NavDestination.isTopLevelSelected(route: String): Boolean {
    return hierarchy.any { it.route == route }
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
