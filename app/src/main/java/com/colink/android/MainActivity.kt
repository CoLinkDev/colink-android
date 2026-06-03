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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.share.PendingShare
import com.colink.android.share.PendingShareStore
import com.colink.android.ui.navigation.CoLinkNavGraph
import com.colink.android.ui.theme.CoLinkTheme
import com.colink.android.util.CoLinkLog
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pendingShareStore: PendingShareStore
    @Inject lateinit var settingsDataStore: SettingsDataStore

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            CoLinkLog.i("Notification", "POST_NOTIFICATIONS permission granted=$granted")
        }

    override fun attachBaseContext(newBase: Context) {
        val datastore = SettingsDataStore(newBase)
        val language = runBlocking {
            datastore.settings.map { it.language }.firstOrNull() ?: "system"
        }
        val wrappedContext = LocaleHelper.wrap(newBase, language)
        super.attachBaseContext(wrappedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var first = true
                settingsDataStore.settings
                    .map { it.language }
                    .distinctUntilChanged()
                    .collect { lang ->
                        if (first) {
                            first = false
                        } else {
                            // Language changed! Recreate the activity to apply.
                            recreate()
                        }
                    }
            }
        }

        enableEdgeToEdge()
        handleShareIntent(intent)
        setContent {
            CoLinkTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    CoLinkNavGraph(
                        pendingShareStore = pendingShareStore,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
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
}
