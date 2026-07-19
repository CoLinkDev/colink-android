package com.colink.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.ui.navigation.CoLinkNavGraph
import com.colink.android.ui.navigation.LaunchTarget
import com.colink.android.ui.theme.CoLinkTheme
import com.colink.android.util.CoLinkLog
import com.colink.android.util.LocaleHelper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.colink.android.network.ConnectionManager
import com.colink.android.network.lan.LAN_PORT
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pendingShareStore: PendingShareStore
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var connectionManager: ConnectionManager
    private var launchTarget by mutableStateOf<LaunchTarget?>(null)

    private var checkComplete by mutableStateOf(false)
    private var isPortOccupied by mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            CoLinkLog.i("Notification", "POST_NOTIFICATIONS permission granted=$granted")
        }

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.cachedLanguage(newBase)
        val wrappedContext = LocaleHelper.wrap(newBase, language)
        super.attachBaseContext(wrappedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isPortCheckDone) {
            isPortOccupied = isPortOccupiedCache
            checkComplete = true
            if (!isPortOccupied) {
                initNormalFlow(savedInstanceState)
            }
        } else {
            lifecycleScope.launch {
                val occupied = withContext(Dispatchers.IO) {
                    isLanPortOccupiedByAnotherProcess()
                }
                isPortOccupiedCache = occupied
                isPortCheckDone = true
                isPortOccupied = occupied
                checkComplete = true
                if (!occupied) {
                    initNormalFlow(savedInstanceState)
                }
            }
        }

        enableEdgeToEdge()

        setContent {
            CoLinkTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (!checkComplete) {
                        // Empty screen while checking port
                    } else if (isPortOccupied) {
                        AlertDialog(
                            onDismissRequest = { },
                            title = { Text(stringResource(R.string.port_occupied_title)) },
                            text = { Text(stringResource(R.string.port_occupied_message)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    finishAndRemoveTask()
                                    kotlin.system.exitProcess(0)
                                }) {
                                    Text(stringResource(R.string.exit_btn))
                                }
                            }
                        )
                    } else {
                        CoLinkNavGraph(
                            pendingShareStore = pendingShareStore,
                            launchTarget = launchTarget,
                            onLaunchTargetConsumed = { launchTarget = null },
                            onRequestNotificationPermission = ::requestNotificationPermission,
                        )
                    }
                }
            }
        }
    }

    private fun initNormalFlow(savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsDataStore.settings
                    .map { it.language }
                    .distinctUntilChanged()
                    .collect { lang ->
                        if (LocaleHelper.cachedLanguage(this@MainActivity) != lang) {
                            LocaleHelper.cacheLanguage(this@MainActivity, lang)
                            recreate()
                        }
                    }
            }
        }
        handleShareIntent(intent)
        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (checkComplete && !isPortOccupied) {
            handleShareIntent(intent)
            handleLaunchIntent(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            CoLinkLog.i("Notification", "requesting POST_NOTIFICATIONS permission")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            CoLinkLog.d("Notification", "POST_NOTIFICATIONS permission already granted")
        }
    }

    private fun isLanPortOccupiedByAnotherProcess(): Boolean {
        if (connectionManager.isLanServerRunning()) {
            return false
        }
        return try {
            java.net.ServerSocket(LAN_PORT).use { false }
        } catch (e: Exception) {
            true
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (text != null) {
            pendingShareStore.set(PendingShare.Text(text))
            return
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        pendingShareStore.set(PendingShare.File(uri))
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val deviceId = intent
            ?.getStringExtra(com.colink.android.notification.EXTRA_TARGET_DEVICE_ID)
            ?.takeIf { it.isNotBlank() }
        if (deviceId != null) {
            launchTarget = LaunchTarget(deviceId)
            intent.removeExtra(com.colink.android.notification.EXTRA_TARGET_DEVICE_ID)
        } else if (intent?.action != Intent.ACTION_SEND) {
            launchTarget = LaunchTarget()
        }
    }

    companion object {
        private var isPortCheckDone = false
        private var isPortOccupiedCache = false
    }
}
