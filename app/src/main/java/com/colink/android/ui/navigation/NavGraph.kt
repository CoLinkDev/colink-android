package com.colink.android.ui.navigation

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login

import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices

import androidx.compose.material.icons.filled.Settings
import com.colink.android.ui.components.BadgeChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
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
import com.colink.android.ui.terminal.TerminalScreen
import com.colink.android.ui.castboard.CastBoardActivity
import com.colink.android.ui.camera.CameraScreen
import com.colink.android.ui.devices.DeviceScreen
import com.colink.android.ui.devices.DevicesViewModel
import com.colink.android.ui.components.DestinationDeviceDialog
import com.colink.android.ui.components.LoadingScreen
import com.colink.android.ui.devices.DeviceListScreen
import com.colink.android.ui.filesystem.RemoteFilesystemScreen
import com.colink.android.ui.messages.ConversationScreen
import com.colink.android.ui.messages.MessagesViewModel
import com.colink.android.ui.onboarding.OnboardingScreen
import com.colink.android.ui.settings.SettingsScreen
import com.colink.android.ui.components.AppUpdateDialog
import com.colink.android.ui.components.LocalAccountAction
import com.colink.android.ui.transfers.TransfersViewModel
import kotlinx.coroutines.flow.StateFlow

private val PageTransitionEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val SecondaryPageTransitionEasing = CubicBezierEasing(0.5f, 0f, 0f, 1f)
private const val SecondaryPageTransitionDurationMillis = 500
private val secondaryPageRoutes =
    setOf(
        "device/{deviceId}",
        "conversation/{deviceId}",
        "filesystem/{deviceId}",
        "terminal/{deviceId}",
        "camera/{deviceId}",
    )
private val deviceChildPageRoutes =
    setOf(
        "conversation/{deviceId}",
        "filesystem/{deviceId}",
        "terminal/{deviceId}",
        "camera/{deviceId}",
    )

private data class TopLevelRoute(
    val route: String,
    val labelResId: Int,
    val icon: ImageVector,
)

private val topLevelRoutes =
    listOf(
        TopLevelRoute("devices", R.string.nav_devices, Icons.Default.Devices),
        TopLevelRoute("settings", R.string.settings_title, Icons.Default.Settings),
    )

@Composable
fun CoLinkNavGraph(
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    launchTarget: LaunchTarget? = null,
    onLaunchTargetConsumed: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val bootstrapping by viewModel.bootstrapping.collectAsStateWithLifecycle()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(bootstrapping, onboardingCompleted) {
        if (!bootstrapping && onboardingCompleted) {
            CoLinkRuntimeStarter.ensureStarted(context)
        }
    }

    when {
        bootstrapping -> LoadingScreen(modifier)
        !onboardingCompleted -> OnboardingScreen(
            onComplete = viewModel::completeOnboarding,
            modifier = modifier,
        )
        else -> {
            MainScaffold(
                cloudStatus = viewModel.cloudStatus,
                authenticated = viewModel.authenticated,
                accountName = viewModel.accountName,
                accountEmail = viewModel.accountEmail,
                serverUrl = viewModel.serverUrl,
                onLogout = viewModel::logout,
                launchTarget = launchTarget,
                onLaunchTargetConsumed = onLaunchTargetConsumed,
                modifier = modifier,
            )
            SystemShareDialogHost(pendingShareStore)
            PairingRequestDialogHost(
                pairingRequest = viewModel.pairingRequest,
                onAccept = { requestId -> viewModel.respondPairing(requestId, true) },
                onReject = { requestId -> viewModel.respondPairing(requestId, false) },
                onClear = viewModel::clearPairing,
                onCancel = viewModel::cancelPairing,
            )
            UpdateDialogHost(
                update = availableUpdate,
                downloadState = updateDownloadState,
                onDismiss = viewModel::dismissUpdate,
                onUpdate = viewModel::startUpdate,
                onInstallerReturned = viewModel::onInstallerReturned,
            )
        }
    }
}

@Composable
private fun UpdateDialogHost(
    update: AppUpdate?,
    downloadState: com.colink.android.domain.model.UpdateDownloadState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onInstallerReturned: () -> Unit,
) {
    AppUpdateDialog(
        update = update,
        downloadState = downloadState,
        onDismiss = onDismiss,
        onUpdate = onUpdate,
        onInstallerReturned = onInstallerReturned,
    )
}

@Composable
private fun SystemShareDialogHost(
    pendingShareStore: PendingShareStore?,
    messagesViewModel: MessagesViewModel = hiltViewModel(),
    transfersViewModel: TransfersViewModel = hiltViewModel(),
) {
    val pendingShare by pendingShareStore?.share?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<PendingShare?>(null) }
    val targetDevices by messagesViewModel.targetDevices.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val share = pendingShare ?: return

    DestinationDeviceDialog(
        devices = targetDevices,
        onDismiss = { pendingShareStore?.consume() },
        onSelect = { deviceId ->
            when (share) {
                is PendingShare.Text -> messagesViewModel.send(deviceId, share.text)
                is PendingShare.File -> transfersViewModel.send(context.contentResolver, deviceId, share.uri)
            }
            pendingShareStore?.consume()
        },
    )
}

@Composable
private fun InterruptibleSecondaryPage(
    interrupted: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier = if (interrupted) Modifier.alpha(0f) else Modifier) {
        content()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MainScaffold(
    cloudStatus: StateFlow<CloudStatus>,
    authenticated: StateFlow<Boolean>,
    accountName: StateFlow<String>,
    accountEmail: StateFlow<String>,
    serverUrl: StateFlow<String>,
    onLogout: () -> Unit,
    launchTarget: LaunchTarget?,
    onLaunchTargetConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rootNavController = rememberNavController()
    val nestedNavController = rememberNavController()
    var handledLaunchTargetToken by remember { mutableStateOf<Long?>(null) }
    var previousRootEntryId by remember { mutableStateOf<String?>(null) }
    var previousRootRoute by remember { mutableStateOf<String?>(null) }
    var seenRootEntryIds by remember { mutableStateOf(emptySet<String>()) }
    var exitingSecondaryPageEntryId by remember { mutableStateOf<String?>(null) }
    var interruptedSecondaryPageEntryId by remember { mutableStateOf<String?>(null) }
    var pendingSecondaryPageRoute by remember { mutableStateOf<String?>(null) }
    val rootBackStackEntry by rootNavController.currentBackStackEntryAsState()
    val secondaryPageScrimAlpha by animateFloatAsState(
        targetValue = if (rootBackStackEntry?.destination?.route in secondaryPageRoutes) 1f else 0f,
        animationSpec = tween(
            durationMillis = SecondaryPageTransitionDurationMillis,
            easing = SecondaryPageTransitionEasing,
        ),
        label = "secondary page scrim",
    )
    val conversationScrimAlpha by animateFloatAsState(
        targetValue = if (rootBackStackEntry?.destination?.route == "filesystem/{deviceId}") 1f else 0f,
        animationSpec = tween(
            durationMillis = SecondaryPageTransitionDurationMillis,
            easing = SecondaryPageTransitionEasing,
        ),
        label = "conversation scrim",
    )
    val deviceScrimAlpha by animateFloatAsState(
        targetValue = if (rootBackStackEntry?.destination?.route in deviceChildPageRoutes) 1f else 0f,
        animationSpec = tween(
            durationMillis = SecondaryPageTransitionDurationMillis,
            easing = SecondaryPageTransitionEasing,
        ),
        label = "device scrim",
    )
    LaunchedEffect(rootBackStackEntry?.id) {
        val currentEntry = rootBackStackEntry ?: return@LaunchedEffect
        if (
            currentEntry.id in seenRootEntryIds &&
                previousRootRoute in secondaryPageRoutes
        ) {
            exitingSecondaryPageEntryId = previousRootEntryId
        }
        seenRootEntryIds = seenRootEntryIds + currentEntry.id
        previousRootEntryId = currentEntry.id
        previousRootRoute = currentEntry.destination.route
    }

    LaunchedEffect(pendingSecondaryPageRoute) {
        val route = pendingSecondaryPageRoute ?: return@LaunchedEffect
        withFrameNanos { }
        pendingSecondaryPageRoute = null
        rootNavController.navigate(route) {
            launchSingleTop = true
        }
    }

    fun requestSecondaryPage(route: String) {
        val currentEntry = rootNavController.currentBackStackEntry
        if (currentEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
            rootNavController.navigate(route) {
                launchSingleTop = true
            }
        } else if (
            currentEntry?.destination?.route in setOf("main", "device/{deviceId}", "conversation/{deviceId}") &&
                exitingSecondaryPageEntryId != null
        ) {
            interruptedSecondaryPageEntryId = exitingSecondaryPageEntryId
            pendingSecondaryPageRoute = route
        }
    }

    LaunchedEffect(launchTarget?.token) {
        val target = launchTarget ?: return@LaunchedEffect
        if (handledLaunchTargetToken == target.token) {
            return@LaunchedEffect
        }
        handledLaunchTargetToken = target.token

        if (rootNavController.currentBackStackEntry?.destination?.route != "main") {
            rootNavController.popBackStack("main", inclusive = false)
        }
        target.deviceId?.let { deviceId ->
            rootNavController.navigate("conversation/${Uri.encode(deviceId)}") {
                launchSingleTop = true
            }
        }
        onLaunchTargetConsumed()
    }

    NavHost(
        navController = rootNavController,
        startDestination = "main",
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(
                    durationMillis = SecondaryPageTransitionDurationMillis,
                    easing = SecondaryPageTransitionEasing,
                ),
            )
        },
        exitTransition = {
            if (
                (initialState.destination.route == "device/{deviceId}" &&
                    targetState.destination.route in secondaryPageRoutes) ||
                    (initialState.destination.route == "conversation/{deviceId}" &&
                        targetState.destination.route == "filesystem/{deviceId}")
            ) {
                ExitTransition.None
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(
                        durationMillis = SecondaryPageTransitionDurationMillis,
                        easing = SecondaryPageTransitionEasing,
                    ),
                )
            }
        },
        popEnterTransition = {
            if (
                (initialState.destination.route in secondaryPageRoutes &&
                    targetState.destination.route == "device/{deviceId}") ||
                    (initialState.destination.route == "filesystem/{deviceId}" &&
                        targetState.destination.route == "conversation/{deviceId}")
            ) {
                EnterTransition.None
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(
                        durationMillis = SecondaryPageTransitionDurationMillis,
                        easing = SecondaryPageTransitionEasing,
                    ),
                )
            }
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(
                    durationMillis = SecondaryPageTransitionDurationMillis,
                    easing = SecondaryPageTransitionEasing,
                ),
            )
        },
    ) {
        composable(
            route = "main",
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }
        ) {
            val devicesViewModel: DevicesViewModel = hiltViewModel()
            val isAuthenticated by authenticated.collectAsStateWithLifecycle()
            val accountName by accountName.collectAsStateWithLifecycle()
            val accountEmail by accountEmail.collectAsStateWithLifecycle()
            val serverUrl by serverUrl.collectAsStateWithLifecycle()
            val currentCloudStatus by cloudStatus.collectAsStateWithLifecycle()
            var showAccountDialog by rememberSaveable { mutableStateOf(false) }

            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            val accountAction: @Composable () -> Unit = {
                AccountIconButton(
                    cloudStatus = cloudStatus,
                    authenticated = authenticated,
                    onAccountClick = { showAccountDialog = true },
                )
            }

            CompositionLocalProvider(LocalAccountAction provides accountAction) {
                Box {
                    if (isLandscape) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            MainNavigationRail(
                                navController = nestedNavController,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .displayCutoutPadding()
                                    .statusBarsPadding()
                                    .navigationBarsPadding(),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .statusBarsPadding()
                                    .navigationBarsPadding(),
                            ) {
                                MainTopLevelNavHost(
                                    navController = nestedNavController,
                                    devicesViewModel = devicesViewModel,
                                    requestSecondaryPage = ::requestSecondaryPage,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    } else {
                        Scaffold(
                            contentWindowInsets = WindowInsets(0.dp),
                            bottomBar = {
                                MainBottomBar(navController = nestedNavController)
                            },
                        ) { innerPadding ->
                            MainTopLevelNavHost(
                                navController = nestedNavController,
                                devicesViewModel = devicesViewModel,
                                requestSecondaryPage = ::requestSecondaryPage,
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(innerPadding),
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(secondaryPageScrimAlpha)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                    )
                }
            }

            if (showAccountDialog) {
                AccountDialog(
                    authenticated = isAuthenticated,
                    accountName = accountName,
                    accountEmail = accountEmail,
                    serverUrl = serverUrl,
                    cloudStatus = currentCloudStatus,
                    onLogout = {
                        onLogout()
                        showAccountDialog = false
                    },
                    onAuthenticated = {},
                    onDismiss = { showAccountDialog = false },
                )
            }
        }

        composable(route = "device/{deviceId}") { entry ->
            InterruptibleSecondaryPage(entry.id == interruptedSecondaryPageEntryId) {
                Box {
                    DeviceScreen(
                        deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                        onBack = { rootNavController.popBackStack() },
                        onOpenChat = { deviceId -> requestSecondaryPage("conversation/${Uri.encode(deviceId)}") },
                        onStartCastBoard = { deviceId ->
                            context.startActivity(CastBoardActivity.createIntent(context, deviceId))
                        },
                        onStartTerminal = { deviceId -> requestSecondaryPage("terminal/${Uri.encode(deviceId)}") },
                        onStartCamera = { deviceId -> requestSecondaryPage("camera/${Uri.encode(deviceId)}") },
                    )

                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(deviceScrimAlpha)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                    )
                }
            }
        }

        composable(route = "conversation/{deviceId}") { entry ->
            InterruptibleSecondaryPage(entry.id == interruptedSecondaryPageEntryId) {
                Box {
                    ConversationScreen(
                        deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                        onBrowseDeviceFiles = { deviceId -> requestSecondaryPage("filesystem/${Uri.encode(deviceId)}") },
                        onBack = { rootNavController.popBackStack() },
                        modifier = Modifier,
                    )

                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(conversationScrimAlpha)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                    )
                }
            }
        }

        composable(route = "filesystem/{deviceId}") { entry ->
            InterruptibleSecondaryPage(entry.id == interruptedSecondaryPageEntryId) {
                RemoteFilesystemScreen(
                    onBack = { rootNavController.popBackStack() },
                    modifier = Modifier,
                )
            }
        }

        composable(route = "terminal/{deviceId}") { entry ->
            InterruptibleSecondaryPage(entry.id == interruptedSecondaryPageEntryId) {
                TerminalScreen(
                    deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                    onBack = { rootNavController.popBackStack() },
                    viewModel = hiltViewModel(),
                )
            }
        }
        composable(route = "camera/{deviceId}") { entry ->
            InterruptibleSecondaryPage(entry.id == interruptedSecondaryPageEntryId) {
                CameraScreen(
                    deviceId = entry.arguments?.getString("deviceId").orEmpty(),
                    onBack = { rootNavController.popBackStack() },
                    viewModel = hiltViewModel(),
                )
            }
        }

    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AccountDialog(
    authenticated: Boolean,
    accountName: String,
    accountEmail: String,
    serverUrl: String,
    cloudStatus: CloudStatus,
    onLogout: () -> Unit,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val animateDismiss = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cloud_account_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgeChip(
                        text = stringResource(
                            if (authenticated) R.string.cloud_account_logged_in else R.string.cloud_account_not_logged_in
                        ),
                        icon = if (authenticated) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.Login,
                        containerColor = if (authenticated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (authenticated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BadgeChip(
                        text = stringResource(
                            if (cloudStatus == CloudStatus.Connected) R.string.cloud_status_connected else R.string.cloud_status_disconnected
                        ),
                        icon = if (cloudStatus == CloudStatus.Connected) Icons.Default.Cloud else Icons.Default.CloudOff,
                        containerColor = if (cloudStatus == CloudStatus.Connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (cloudStatus == CloudStatus.Connected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (authenticated) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.cloud_account_connected_body,
                            accountName,
                            serverUrl,
                            accountEmail,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(stringResource(R.string.logout_btn))
                    }
                    TextButton(
                        onClick = { animateDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cancel_btn))
                    }
                }
            } else {
                AuthDialogContent(
                    onAuthenticated = onAuthenticated,
                    onDismiss = { animateDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Devices, contentDescription = null) },
        title = { Text(stringResource(R.string.lan_pairing_title)) },
        text = {
            val deviceName = current.name.ifBlank { current.deviceId }
            val mainText = if (current.initiatedLocally) {
                stringResource(R.string.lan_pairing_verify_code, deviceName, current.code)
            } else {
                stringResource(R.string.lan_pairing_wants_to_pair, deviceName, current.code)
            }
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
            if (current.initiatedLocally && current.error == null) {
                null
            } else {
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
            }
        },
        dismissButton = if (current.error == null) {
            {
                TextButton(
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
                            stringResource(R.string.reject_btn)
                        },
                    )
                }
            }
        } else {
            null
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
                    if (!selected && navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                        navController.navigateTopLevel(item.route)
                    }
                },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelResId)) },
                alwaysShowLabel = false,
            )
        }
    }
}

@Composable
private fun MainTopLevelNavHost(
    navController: NavHostController,
    devicesViewModel: DevicesViewModel,
    requestSecondaryPage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = "devices",
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable("devices") {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                DeviceListScreen(
                    onDeviceSelected = { deviceId -> requestSecondaryPage("device/${Uri.encode(deviceId)}") },
                    viewModel = devicesViewModel,
                )
            }
        }
        composable("settings") {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun AccountIconButton(
    cloudStatus: StateFlow<CloudStatus>,
    authenticated: StateFlow<Boolean>,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
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

    IconButton(
        onClick = onAccountClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = accountIcon,
            contentDescription = stringResource(R.string.account_desc),
            tint = accountTint,
        )
    }
}

@Composable
private fun MainNavigationRail(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination

    NavigationRail(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            topLevelRoutes.forEachIndexed { index, item ->
                val selected = currentDestination?.isTopLevelSelected(item.route) == true
                NavigationRailItem(
                    selected = selected,
                    onClick = {
                        if (!selected && navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                            navController.navigateTopLevel(item.route)
                        }
                    },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.labelResId)) },
                    alwaysShowLabel = true,
                )
                Spacer(modifier = Modifier.weight(1f))
            }
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
