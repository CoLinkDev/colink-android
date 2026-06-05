package com.colink.android.ui.castboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.castboard.bridge.MusicBridge

@Composable
fun CastBoardScreen(
    onStartFullscreen: (String) -> Unit,
    viewModel: CastBoardViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val availableDevices = remember(devices) {
        devices.filter { it.online || it.lanAvailable }
    }

    LaunchedEffect(availableDevices, selectedDeviceId) {
        if (selectedDeviceId == null || availableDevices.none { it.deviceId == selectedDeviceId }) {
            availableDevices.firstOrNull()?.deviceId?.let(viewModel::selectDevice)
        }
    }

    ScreenColumn(
        title = stringResource(R.string.nav_castboard),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (availableDevices.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Devices,
                title = stringResource(R.string.no_devices_title),
                body = stringResource(R.string.no_devices_body),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DevicePicker(
                    devices = availableDevices,
                    selectedDeviceId = selectedDeviceId,
                    onSelectedDeviceChange = viewModel::selectDevice,
                )
                val canStart = selectedDeviceId != null &&
                    availableDevices.any { it.deviceId == selectedDeviceId }
                Button(
                    enabled = canStart,
                    onClick = { selectedDeviceId?.let(onStartFullscreen) },
                ) {
                    Icon(Icons.Default.Cast, contentDescription = null)
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.castboard_start),
                    )
                }
            }
        }
    }
}

@Composable
fun CastBoardFullScreen(
    onClose: () -> Unit,
    viewModel: CastBoardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val musicState by viewModel.musicState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val sourceDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val sourceDevice = remember(devices, sourceDeviceId) {
        devices.firstOrNull { it.deviceId == sourceDeviceId }
    }
    val bridge = remember { MusicBridge() }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(musicState) {
        bridge.sync(musicState)
    }

    LaunchedEffect(sourceDeviceId, sourceDevice?.online, sourceDevice?.lanAvailable) {
        val unavailable = sourceDeviceId != null && sourceDevice != null && !sourceDevice.online && !sourceDevice.lanAvailable
        if (unavailable) {
            onClose()
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        onDispose {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bridge.unbind()
            webView?.destroy()
        }
    }

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            bridge.markPageReady()
                        }
                    }
                    bridge.bind(this)
                    loadUrl("file:///android_asset/castboard/index.html")
                }.also {
                    webView = it
                }
            },
            update = {
                bridge.sync(musicState)
            },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.nav_castboard),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = sourceDevice?.name ?: sourceDeviceId.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_btn))
        }
    }
}

private tailrec fun Context.findActivity(): Activity {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> error("Activity context required")
    }
}
