package com.colink.android.ui.castboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.colink.android.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.devicesWithoutLocalDevice
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.castboard.bridge.MusicBridge
import kotlinx.coroutines.delay

@Composable
fun CastBoardScreen(
    onStartFullscreen: (String) -> Unit,
    viewModel: CastBoardViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val localDeviceId by viewModel.localDeviceId.collectAsStateWithLifecycle()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val availableDevices = remember(devices, localDeviceId) {
        devicesWithoutLocalDevice(devices, localDeviceId)
            .filter { (it.online || it.lanAvailable) && isComputer(it.type) }
    }

    LaunchedEffect(availableDevices, selectedDeviceId) {
        if (selectedDeviceId == null || availableDevices.none { it.deviceId == selectedDeviceId }) {
            availableDevices.firstOrNull()?.deviceId?.let(viewModel::selectDevice)
        }
    }

    ScreenColumn(
        title = stringResource(R.string.nav_castboard),
        subtitle = stringResource(R.string.castboard_subtitle),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (availableDevices.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Devices,
                title = stringResource(R.string.no_devices_title),
                body = stringResource(R.string.no_devices_body),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
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
    requestedSourceDeviceId: String? = null,
    onClose: () -> Unit,
    viewModel: CastBoardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val musicState by viewModel.musicState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val sourceDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val sourceDevice = remember(devices, sourceDeviceId) {
        devices.firstOrNull { it.deviceId == sourceDeviceId }
    }
    val bridge = remember { MusicBridge() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsRevealTick by remember { mutableStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val castBoardUrl = remember(context) { castBoardUrl(context) }
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    fun revealControls() {
        controlsVisible = true
        controlsRevealTick += 1
    }

    val resolutionText = remember(viewportSize) {
        val resolution = viewportSize
        if (resolution.width > 0 && resolution.height > 0) {
            "${resolution.width} x ${resolution.height}"
        } else {
            "--"
        }
    }

    LaunchedEffect(requestedSourceDeviceId) {
        viewModel.bindSourceDevice(requestedSourceDeviceId)
    }

    LaunchedEffect(musicState) {
        bridge.sync(musicState)
    }

    val statusText = when (connectionStatus) {
        CastBoardConnectionStatus.WaitingForDevice -> stringResource(R.string.castboard_status_waiting)
        CastBoardConnectionStatus.Connected -> stringResource(R.string.castboard_status_connected)
        CastBoardConnectionStatus.Idle -> null
    }
    val statusColor = when (connectionStatus) {
        CastBoardConnectionStatus.Connected -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    LaunchedEffect(controlsVisible, controlsRevealTick, connectionStatus) {
        if (connectionStatus == CastBoardConnectionStatus.WaitingForDevice) {
            controlsVisible = true
        } else if (controlsVisible && connectionStatus == CastBoardConnectionStatus.Connected) {
            delay(5_000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bridge.unbind()
            webView?.destroy()
        }
    }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF050608))
            .onSizeChanged { viewportSize = it },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webViewContext ->
                WebView.setWebContentsDebuggingEnabled(true)
                if (BuildConfig.DEBUG) {
                    
                }
                WebView(webViewContext).apply {
                    tag = castBoardUrl
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    val webSettings = this.settings
                    webSettings.javaScriptEnabled = true
                    webSettings.domStorageEnabled = true
                    webSettings.allowFileAccess = false
                    webSettings.allowContentAccess = true
                    webSettings.textZoom = 100
                    webSettings.setSupportZoom(false)
                    webSettings.builtInZoomControls = false
                    webSettings.displayZoomControls = false
                    webSettings.loadWithOverviewMode = false
                    webSettings.useWideViewPort = true
                    webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            revealControls()
                        }
                        false
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        webSettings.forceDark = WebSettings.FORCE_DARK_OFF
                    }
                    webSettings.cacheMode = WebSettings.LOAD_DEFAULT
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? =
                            request?.url?.let(assetLoader::shouldInterceptRequest)

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            bridge.markPageReady()
                        }
                    }
                    bridge.bind(this)
                    loadUrl(castBoardUrl)
                }.also {
                    webView = it
                }
            },
            update = { view ->
                if (view.tag != castBoardUrl) {
                    bridge.markPageLoading()
                    view.tag = castBoardUrl
                    view.loadUrl(castBoardUrl)
                } else {
                    bridge.sync(musicState)
                }
            },
        )

        if (controlsVisible) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.End,
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
                        if (statusText != null) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColor,
                            )
                        }
                        Text(
                            text = resolutionText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_btn))
                    }
                }
            }
        }
    }
}

private fun castBoardUrl(context: Context): String {
    val devUrl = BuildConfig.CASTBOARD_DEV_URL.trim()
    val rawLang = com.colink.android.util.LocaleHelper.cachedLanguage(context)
    val lang = if (rawLang == "system") {
        java.util.Locale.getDefault().toLanguageTag()
    } else {
        rawLang
    }
    var baseUrl = if (BuildConfig.DEBUG && devUrl.isNotEmpty()) {
        devUrl
    } else if (BuildConfig.DEBUG) {
        "https://appassets.androidplatform.net/assets/castboard/index.html?debug=1"
    } else {
        "https://appassets.androidplatform.net/assets/castboard/index.html"
    }
    if (BuildConfig.DEBUG && !baseUrl.contains("debug=")) {
        baseUrl = if (baseUrl.contains("?")) {
            "$baseUrl&debug=1"
        } else {
            "$baseUrl?debug=1"
        }
    }
    return if (baseUrl.contains("?")) {
        "$baseUrl&lang=$lang"
    } else {
        "$baseUrl?lang=$lang"
    }
}

private tailrec fun Context.findActivity(): Activity {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> error("Activity context required")
    }
}

private fun isComputer(type: String): Boolean {
    val lower = type.lowercase()
    return lower == "windows" || lower == "macos" || lower == "linux"
}

