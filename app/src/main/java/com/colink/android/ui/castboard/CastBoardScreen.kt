package com.colink.android.ui.castboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.colink.android.R
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.model.Device
import com.colink.android.ui.components.DevicePicker
import com.colink.android.ui.components.EmptyState
import com.colink.android.ui.components.ScreenColumn
import com.colink.android.ui.castboard.bridge.MusicBridge
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_CASTBOARD_RESOLUTION_DIMENSION = 120
private const val MAX_CASTBOARD_RESOLUTION_DIMENSION = 7680

@Composable
fun CastBoardScreen(
    onStartFullscreen: (String) -> Unit,
    viewModel: CastBoardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nativeResolution = remember(context) { context.currentDeviceResolution() }
    val settingsSavedMessage = stringResource(R.string.settings_saved)
    val availableDevices = remember(devices) {
        devices.filter { it.online || it.lanAvailable }
    }

    LaunchedEffect(viewModel, settingsSavedMessage) {
        viewModel.resolutionSavedEvents.collect {
            Toast.makeText(context, settingsSavedMessage, Toast.LENGTH_SHORT).show()
        }
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
                CastBoardResolutionPicker(
                    nativeResolution = nativeResolution,
                    configuredWidth = settings.castBoardResolutionWidth,
                    configuredHeight = settings.castBoardResolutionHeight,
                    onResolutionChange = viewModel::saveCastBoardResolution,
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
private fun CastBoardResolutionPicker(
    nativeResolution: IntSize,
    configuredWidth: Int,
    configuredHeight: Int,
    onResolutionChange: (Int, Int) -> Unit,
) {
    val usesNativeResolution = configuredWidth <= 0 || configuredHeight <= 0
    val effectiveResolution = if (usesNativeResolution) {
        nativeResolution
    } else {
        IntSize(configuredWidth, configuredHeight)
    }
    var widthText by rememberSaveable { mutableStateOf("") }
    var heightText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(effectiveResolution) {
        widthText = effectiveResolution.width.toString()
        heightText = effectiveResolution.height.toString()
    }

    val customWidth = parseResolutionDimension(widthText)
    val customHeight = parseResolutionDimension(heightText)
    val canApplyCustom = customWidth != null && customHeight != null

    fun selectPreset(resolution: IntSize?) {
        if (resolution == null) {
            widthText = nativeResolution.width.toString()
            heightText = nativeResolution.height.toString()
            onResolutionChange(0, 0)
            return
        }
        widthText = resolution.width.toString()
        heightText = resolution.height.toString()
        onResolutionChange(resolution.width, resolution.height)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(R.string.castboard_resolution_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.castboard_resolution_body,
                    nativeResolution.formatResolution(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ResolutionPresetButton(
                label = stringResource(R.string.castboard_resolution_native),
                selected = usesNativeResolution,
                modifier = Modifier.weight(1f),
                onClick = { selectPreset(null) },
            )
            listOf(0.75f, 0.5f, 0.25f).forEach { scale ->
                val preset = nativeResolution.scaledBy(scale)
                ResolutionPresetButton(
                    label = "${(scale * 100).roundToInt()}%",
                    selected = !usesNativeResolution && effectiveResolution == preset,
                    modifier = Modifier.weight(1f),
                    onClick = { selectPreset(preset) },
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = { widthText = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.castboard_resolution_width)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = heightText,
                onValueChange = { heightText = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.castboard_resolution_height)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            Button(
                enabled = canApplyCustom,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (customWidth != null && customHeight != null) {
                        onResolutionChange(customWidth, customHeight)
                    }
                },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringResource(R.string.castboard_resolution_apply),
                )
            }
        }
    }
}

@Composable
private fun ResolutionPresetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label, maxLines = 1)
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
    val density = LocalDensity.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val musicState by viewModel.musicState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val sourceDeviceId by viewModel.selectedDeviceId.collectAsStateWithLifecycle()
    val sourceDevice = remember(devices, sourceDeviceId) {
        devices.firstOrNull { it.deviceId == sourceDeviceId }
    }
    val bridge = remember { MusicBridge() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsRevealTick by remember { mutableStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val targetResolution = remember(settings.castBoardResolutionWidth, settings.castBoardResolutionHeight) {
        settings.castBoardTargetResolution()
    }
    val fittedViewportSize = remember(viewportSize, targetResolution) {
        targetResolution?.fitInside(viewportSize)
    }
    val castBoardUrl = remember(targetResolution) {
        castBoardUrl(targetResolution)
    }

    fun revealControls() {
        controlsVisible = true
        controlsRevealTick += 1
    }

    val resolutionText = remember(viewportSize, targetResolution) {
        val resolution = targetResolution ?: viewportSize
        if (resolution.width > 0 && resolution.height > 0) {
            resolution.formatResolution()
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

    LaunchedEffect(controlsVisible, controlsRevealTick) {
        if (controlsVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(sourceDeviceId, sourceDevice?.online, sourceDevice?.lanAvailable) {
        val unavailable = sourceDeviceId != null && sourceDevice != null && !sourceDevice.online && !sourceDevice.lanAvailable
        if (unavailable) {
            onClose()
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        onDispose {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
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
        val webViewModifier = if (
            targetResolution != null &&
            fittedViewportSize != null &&
            fittedViewportSize.width > 0 &&
            fittedViewportSize.height > 0
        ) {
            Modifier
                .align(Alignment.Center)
                .size(
                    width = with(density) { fittedViewportSize.width.toDp() },
                    height = with(density) { fittedViewportSize.height.toDp() },
                )
        } else {
            Modifier.fillMaxSize()
        }

        AndroidView(
            modifier = webViewModifier,
            factory = { webViewContext ->
                WebView(webViewContext).apply {
                    tag = castBoardUrl
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    val webSettings = this.settings
                    webSettings.javaScriptEnabled = true
                    webSettings.domStorageEnabled = true
                    webSettings.allowFileAccess = true
                    webSettings.allowContentAccess = true
                    webSettings.textZoom = 100
                    webSettings.setSupportZoom(false)
                    webSettings.builtInZoomControls = false
                    webSettings.displayZoomControls = false
                    webSettings.loadWithOverviewMode = false
                    webSettings.useWideViewPort = true
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

private fun AppSettings.castBoardTargetResolution(): IntSize? {
    return if (castBoardResolutionWidth > 0 && castBoardResolutionHeight > 0) {
        IntSize(
            castBoardResolutionWidth.coerceAtMost(MAX_CASTBOARD_RESOLUTION_DIMENSION),
            castBoardResolutionHeight.coerceAtMost(MAX_CASTBOARD_RESOLUTION_DIMENSION),
        )
    } else {
        null
    }
}

private fun IntSize.fitInside(viewport: IntSize): IntSize {
    if (width <= 0 || height <= 0 || viewport.width <= 0 || viewport.height <= 0) {
        return IntSize.Zero
    }

    val scale = min(viewport.width / width.toFloat(), viewport.height / height.toFloat())
    return IntSize(
        width = max(1, (width * scale).roundToInt()),
        height = max(1, (height * scale).roundToInt()),
    )
}

private fun castBoardUrl(targetResolution: IntSize?): String {
    val params = mutableListOf("host=android")
    if (targetResolution != null) {
        params += "width=${targetResolution.width}"
        params += "height=${targetResolution.height}"
    }
    return "file:///android_asset/castboard/index.html#${params.joinToString("&")}"
}

private fun parseResolutionDimension(value: String): Int? {
    return value.toIntOrNull()
        ?.takeIf { it in MIN_CASTBOARD_RESOLUTION_DIMENSION..MAX_CASTBOARD_RESOLUTION_DIMENSION }
}

private fun IntSize.scaledBy(scale: Float): IntSize =
    IntSize(
        width = (width * scale).roundToInt()
            .coerceIn(MIN_CASTBOARD_RESOLUTION_DIMENSION, MAX_CASTBOARD_RESOLUTION_DIMENSION),
        height = (height * scale).roundToInt()
            .coerceIn(MIN_CASTBOARD_RESOLUTION_DIMENSION, MAX_CASTBOARD_RESOLUTION_DIMENSION),
    )

private fun IntSize.formatResolution(): String = "$width x $height"

private fun Context.currentDeviceResolution(): IntSize {
    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val rawSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.maximumWindowMetrics.bounds
        IntSize(bounds.width(), bounds.height())
    } else {
        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        IntSize(metrics.widthPixels, metrics.heightPixels)
    }
    return IntSize(
        width = max(rawSize.width, rawSize.height).coerceAtLeast(1),
        height = min(rawSize.width, rawSize.height).coerceAtLeast(1),
    )
}

private tailrec fun Context.findActivity(): Activity {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> error("Activity context required")
    }
}
