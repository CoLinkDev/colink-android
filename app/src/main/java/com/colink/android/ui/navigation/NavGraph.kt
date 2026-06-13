package com.colink.android.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.CloudStatus
import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.service.CoLinkRuntimeStarter
import com.colink.android.ui.auth.AuthDialogContent
import com.colink.android.ui.devices.DeviceDetailsScreen
import com.colink.android.ui.components.LoadingScreen
import com.colink.android.ui.devices.DeviceListScreen
import com.colink.android.ui.castboard.CastBoardActivity
import com.colink.android.ui.castboard.CastBoardScreen
import com.colink.android.ui.messages.MessageScreen
import com.colink.android.ui.settings.SettingsScreen
import com.colink.android.ui.navigation.LaunchTarget
import com.colink.android.util.CoLinkLog
import kotlinx.coroutines.flow.StateFlow

private data class TopLevelRoute(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector,
)

private val topLevelRoutes =
    listOf(
        TopLevelRoute("devices", R.string.nav_devices, Icons.Default.Devices),
        TopLevelRoute("messages", R.string.nav_messages, Icons.AutoMirrored.Filled.Chat),
        TopLevelRoute("castboard", R.string.nav_castboard, Icons.Default.Cast),
        TopLevelRoute("settings", R.string.nav_settings, Icons.Default.Settings),
    )

@Composable
fun CoLinkNavGraph(
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    launchTarget: LaunchTarget? = null,
    onLaunchTargetConsumed: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val bootstrapping by viewModel.bootstrapping.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(bootstrapping) {
        if (!bootstrapping) {
            CoLinkRuntimeStarter.ensureStarted(context)
        }
    }

    LaunchedEffect(bootstrapping, notificationsEnabled) {
        if (!bootstrapping && notificationsEnabled) {
            onRequestNotificationPermission()
        }
    }

    when {
        bootstrapping -> LoadingScreen(modifier)
        else -> {
            MainScaffold(
                cloudStatus = viewModel.cloudStatus,
                authenticated = viewModel.authenticated,
                onLogout = viewModel::logout,
                pendingShareStore = pendingShareStore,
                launchTarget = launchTarget,
                onLaunchTargetConsumed = onLaunchTargetConsumed,
                modifier = modifier,
            )
            PairingRequestDialogHost(
                pairingRequest = viewModel.pairingRequest,
                onAccept = { requestId -> viewModel.respondPairing(requestId, true) },
                onReject = { requestId -> viewModel.respondPairing(requestId, false) },
                onClear = viewModel::clearPairing,
                onCancel = viewModel::cancelPairing,
            )
            UpdateDialogHost(
                update = availableUpdate,
                onDismiss = viewModel::dismissUpdate,
            )
        }
    }
}

@Composable
private fun UpdateDialogHost(
    update: AppUpdate?,
    onDismiss: () -> Unit,
) {
    val current = update ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val asset = current.assets.firstOrNull()
    val body = buildString {
        append(stringResource(R.string.update_available_body, current.version))
        val notes = current.releaseNotes.trim()
        if (notes.isNotEmpty()) {
            append("\n\n")
            append(notes)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = { Text(body) },
        confirmButton = {
            if (asset != null) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(asset.downloadUrl)))
                        }.onFailure { error ->
                            CoLinkLog.w("Update", "open update download failed", error)
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.update_download_btn))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later_btn))
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScaffold(
    cloudStatus: StateFlow<CloudStatus>,
    authenticated: StateFlow<Boolean>,
    onLogout: () -> Unit,
    pendingShareStore: PendingShareStore?,
    launchTarget: LaunchTarget?,
    onLaunchTargetConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
            val isAuthenticated by authenticated.collectAsStateWithLifecycle()
            var showAccountDialog by rememberSaveable { mutableStateOf(false) }

            LaunchedEffect(isAuthenticated) {
                if (isAuthenticated) {
                    showAccountDialog = false
                }
            }

            LaunchedEffect(pendingShare) {
                if (pendingShare == null) {
                    return@LaunchedEffect
                }
                if (rootNavController.currentBackStackEntry?.destination?.route != "main") {
                    rootNavController.popBackStack("main", inclusive = false)
                }
                nestedNavController.navigateTopLevel("messages")
            }

            LaunchedEffect(launchTarget) {
                val target = launchTarget
                if (target == null) {
                    return@LaunchedEffect
                }
                rootNavController.navigate("conversation/${Uri.encode(target.deviceId)}")
                onLaunchTargetConsumed()
            }

            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    MainTopBar(
                        cloudStatus = cloudStatus,
                        authenticated = authenticated,
                        onAccountClick = { showAccountDialog = true },
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
                    enterTransition = {
                        val initialIndex = topLevelRoutes.indexOfFirst { it.route == initialState.destination.route }.takeIf { it >= 0 } ?: 0
                        val targetIndex = topLevelRoutes.indexOfFirst { it.route == targetState.destination.route }.takeIf { it >= 0 } ?: 0
                        val isLeftToRight = targetIndex >= initialIndex
                        slideIntoContainer(
                            towards = if (isLeftToRight) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(300)
                        ) + fadeIn(animationSpec = tween(300))
                    },
                    exitTransition = {
                        val initialIndex = topLevelRoutes.indexOfFirst { it.route == initialState.destination.route }.takeIf { it >= 0 } ?: 0
                        val targetIndex = topLevelRoutes.indexOfFirst { it.route == targetState.destination.route }.takeIf { it >= 0 } ?: 0
                        val isLeftToRight = targetIndex >= initialIndex
                        slideOutOfContainer(
                            towards = if (isLeftToRight) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300))
                    },
                    popEnterTransition = {
                        val initialIndex = topLevelRoutes.indexOfFirst { it.route == initialState.destination.route }.takeIf { it >= 0 } ?: 0
                        val targetIndex = topLevelRoutes.indexOfFirst { it.route == targetState.destination.route }.takeIf { it >= 0 } ?: 0
                        val isLeftToRight = targetIndex >= initialIndex
                        slideIntoContainer(
                            towards = if (isLeftToRight) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(300)
                        ) + fadeIn(animationSpec = tween(300))
                    },
                    popExitTransition = {
                        val initialIndex = topLevelRoutes.indexOfFirst { it.route == initialState.destination.route }.takeIf { it >= 0 } ?: 0
                        val targetIndex = topLevelRoutes.indexOfFirst { it.route == targetState.destination.route }.takeIf { it >= 0 } ?: 0
                        val isLeftToRight = targetIndex >= initialIndex
                        slideOutOfContainer(
                            towards = if (isLeftToRight) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(300)
                        ) + fadeOut(animationSpec = tween(300))
                    },
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
                            onConversationSelected = { deviceId ->
                                rootNavController.navigate("conversation/${Uri.encode(deviceId)}")
                            },
                        )
                    }
                    composable("castboard") {
                        CastBoardScreen(
                            onStartFullscreen = { deviceId ->
                                context.startActivity(CastBoardActivity.createIntent(context, deviceId))
                            },
                        )
                    }
                    composable("settings") { SettingsScreen() }
                }
            }

            if (showAccountDialog) {
                AccountDialog(
                    authenticated = isAuthenticated,
                    onLogout = {
                        onLogout()
                        showAccountDialog = false
                    },
                    onAuthenticated = { showAccountDialog = false },
                    onDismiss = { showAccountDialog = false },
                )
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
            route = "conversation/{deviceId}",
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
            MessageScreen(
                snackbarHostState = snackbarHostState,
                pendingShareStore = pendingShareStore,
                fixedDeviceId = entry.arguments?.getString("deviceId").orEmpty(),
                onBack = { rootNavController.popBackStack() },
            )
        }

    }
}

@Composable
private fun AccountDialog(
    authenticated: Boolean,
    onLogout: () -> Unit,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (authenticated) Icons.Default.AccountCircle else Icons.AutoMirrored.Filled.Login,
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.cloud_account_title)) },
        text = {
            if (authenticated) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cloud_account_connected),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.cloud_account_connected_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AuthDialogContent(
                    onAuthenticated = onAuthenticated,
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            if (authenticated) {
                TextButton(onClick = onLogout) {
                    Text(stringResource(R.string.logout_btn))
                }
            }
        },
        dismissButton = {
            if (authenticated) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel_btn))
                }
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainTopBar(
    cloudStatus: StateFlow<CloudStatus>,
    authenticated: StateFlow<Boolean>,
    onAccountClick: () -> Unit,
) {
    val status by cloudStatus.collectAsStateWithLifecycle()
    val isAuthenticated by authenticated.collectAsStateWithLifecycle()
    val accountIcon = if (isAuthenticated) {
        Icons.Default.AccountCircle
    } else {
        Icons.AutoMirrored.Filled.Login
    }
    val accountTint = when {
        !isAuthenticated -> MaterialTheme.colorScheme.onSurfaceVariant
        status == CloudStatus.Connected -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

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
            IconButton(onClick = onAccountClick) {
                Icon(
                    imageVector = accountIcon,
                    contentDescription = stringResource(R.string.account_desc),
                    tint = accountTint,
                )
            }
        },
    )
}

@Composable
private fun PairingRequestDialogHost(
    pairingRequest: StateFlow<LanPairingRequest?>,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onClear: (String) -> Unit,
    onCancel: (LanPairingRequest) -> Unit,
) {
    val request by pairingRequest.collectAsStateWithLifecycle()
    val current = request ?: return
    AlertDialog(
        onDismissRequest = {
            if (current.error != null) {
                onClear(current.requestId)
            } else if (!current.waiting) {
                onReject(current.requestId)
            }
        },
        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
        title = { Text(stringResource(R.string.lan_pairing_title)) },
        text = {
            val deviceName = current.name.ifBlank { current.deviceId }
            val mainText = stringResource(R.string.lan_pairing_wants_to_pair, deviceName, current.code)
            val body = when {
                current.error != null -> {
                    val errMsg = com.colink.android.util.ProtocolReasonFormatter.format(
                        androidx.compose.ui.platform.LocalContext.current,
                        current.error
                    )
                    "$mainText\n\n$errMsg"
                }
                current.waiting -> "$mainText\n\n" + stringResource(R.string.lan_pairing_waiting)
                else -> mainText
            }
            Text(body)
        },
        confirmButton = {
            TextButton(
                enabled = !current.waiting,
                onClick = {
                    if (current.error != null) {
                        onClear(current.requestId)
                    } else {
                        onAccept(current.requestId)
                    }
                },
            ) {
                val btnText = if (current.error != null) {
                    stringResource(R.string.lan_pairing_close)
                } else if (current.waiting) {
                    stringResource(R.string.lan_pairing_waiting_btn)
                } else {
                    stringResource(R.string.lan_pairing_accept)
                }
                Text(btnText)
            }
        },
        dismissButton = {
            TextButton(
                enabled = current.error == null,
                onClick = {
                    if (current.waiting) {
                        onCancel(current)
                    } else {
                        onReject(current.requestId)
                    }
                },
            ) {
                Text(
                    if (current.waiting) {
                        stringResource(R.string.cancel_btn)
                    } else {
                        stringResource(R.string.lan_pairing_reject)
                    },
                )
            }
        },
    )
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
