package com.colink.android.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.colink.android.domain.model.CloudStatus
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.service.CoLinkService
import com.colink.android.ui.auth.AuthScreen
import com.colink.android.ui.components.LoadingScreen
import com.colink.android.ui.devices.DeviceListScreen
import com.colink.android.ui.messages.MessageScreen
import com.colink.android.ui.settings.SettingsScreen
import com.colink.android.ui.transfers.TransfersScreen

private data class TopLevelRoute(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

private val topLevelRoutes =
    listOf(
        TopLevelRoute("devices", "Devices") {
            Icon(Icons.Default.Devices, contentDescription = null)
        },
        TopLevelRoute("messages", "Messages") {
            Icon(Icons.Default.Chat, contentDescription = null)
        },
        TopLevelRoute("transfers", "Transfers") {
            Icon(Icons.Default.SwapHoriz, contentDescription = null)
        },
        TopLevelRoute("settings", "Settings") {
            Icon(Icons.Default.Settings, contentDescription = null)
        },
    )

@Composable
fun CoLinkNavGraph(
    modifier: Modifier = Modifier,
    pendingShareStore: PendingShareStore? = null,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.authenticated) {
        if (uiState.authenticated) {
            CoLinkService.start(context)
        }
    }

    when {
        uiState.bootstrapping -> LoadingScreen(modifier)
        !uiState.authenticated -> AuthScreen(modifier)
        else -> {
            MainScaffold(
                cloudStatus = uiState.cloud.status,
                onLogout = viewModel::logout,
                pendingShareStore = pendingShareStore,
                modifier = modifier,
            )
            val request = uiState.pairingRequest
            if (request != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.respondPairing(request.requestId, false) },
                    title = { Text("LAN pairing") },
                    text = { Text("${request.name} wants to pair.\nCode: ${request.code}") },
                    confirmButton = {
                        Button(onClick = { viewModel.respondPairing(request.requestId, true) }) {
                            Text("Accept")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.respondPairing(request.requestId, false) }) {
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
    onLogout: () -> Unit,
    pendingShareStore: PendingShareStore?,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentDestination = backStack?.destination
    val pendingShare by pendingShareStore?.share?.collectAsStateWithLifecycle()
        ?: androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<PendingShare?>(null)
        }

    LaunchedEffect(pendingShare) {
        when (pendingShare) {
            is PendingShare.Text -> navController.navigate("messages")
            is PendingShare.File -> navController.navigate("transfers")
            null -> Unit
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (cloudStatus == CloudStatus.Connected) {
                                Icons.Default.Cloud
                            } else {
                                Icons.Default.CloudOff
                            },
                            contentDescription = null,
                        )
                        Text(" CoLink")
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                topLevelRoutes.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = item.icon,
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
        ) {
            composable("devices") { DeviceListScreen() }
            composable("messages") { MessageScreen(pendingShareStore = pendingShareStore) }
            composable("transfers") { TransfersScreen(pendingShareStore = pendingShareStore) }
            composable("settings") { SettingsScreen() }
        }
    }
}
