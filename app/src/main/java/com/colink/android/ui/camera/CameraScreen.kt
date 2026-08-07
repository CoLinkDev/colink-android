package com.colink.android.ui.camera

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.BuildConfig
import com.colink.android.R
import com.colink.android.network.CameraEvent
import com.colink.android.network.ConnectionManager
import com.colink.android.network.RemoteCameraProtocolException
import com.colink.android.network.RemoteCameraTimeoutException
import com.colink.android.network.RemoteCameraUnsupportedException
import com.colink.android.network.message.CameraEntry
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.lifecycle.HiltViewModel
import android.os.SystemClock
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CameraUiState(
    val cameras: List<CameraEntry> = emptyList(),
    val selected: CameraEntry? = null,
    val sessionId: String? = null,
    val codec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val transport: String? = null,
    val bitmap: Bitmap? = null,
    val pendingSessionId: String? = null,
    val loading: Boolean = false,
    val error: CameraUiError? = null,
)

enum class CameraUiError(val messageRes: Int) {
    UNSUPPORTED(R.string.device_control_unsupported),
    LOAD_FAILED(R.string.camera_error_load_failed),
    LIST_TIMEOUT(R.string.camera_error_list_timeout),
    OPEN_FAILED(R.string.camera_error_open_failed),
    OPEN_TIMEOUT(R.string.camera_error_open_timeout),
    ACCESS_DENIED(R.string.camera_error_access_denied),
    UNAVAILABLE(R.string.camera_error_unavailable),
    CODEC_UNSUPPORTED(R.string.camera_error_codec_unsupported),
    PROTOCOL(R.string.camera_error_protocol),
    HEARTBEAT_TIMEOUT(R.string.camera_error_heartbeat_timeout),
    DISCONNECTED(R.string.camera_error_disconnected),
    PLAYBACK_FAILED(R.string.camera_error_playback_failed),
    CLOSE_FAILED(R.string.camera_error_close_failed),
}

private const val CAMERA_OPEN_TIMEOUT_MILLIS = 20_000L
private const val CAMERA_REASON_REJECTED = "colink:camera.rejected.v1"
private const val CAMERA_REASON_NOT_AVAILABLE = "colink:camera.not_available.v1"
private const val CAMERA_REASON_NO_COMMON_CODEC = "colink:camera.no_common_codec.v1"
private const val CAMERA_REASON_SESSION_CONFLICT = "colink:camera.session_conflict.v1"
private const val CAMERA_REASON_ALIVE_TIMEOUT = "colink:camera.alive_timeout.v1"
private const val CAMERA_REASON_DEVICE_LOST = "colink:camera.device_lost.v1"
private const val CAMERA_REASON_LIST_FAILED = "colink:camera.list_failed.v1"

data class CameraDebugUiState(
    val sessionId: String = "",
    val codec: String = "",
    val transport: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val elapsedSeconds: Long = 0,
    val receiveFps: Double = 0.0,
    val receiveKbps: Double = 0.0,
    val lastSequence: Long? = null,
    val lastFrameBytes: Int = 0,
    val keyframes: Long = 0,
    val sequenceGaps: Long = 0,
    val missingFrames: Long = 0,
    val delayDriftMs: Long = 0,
    val lastNalTypes: String = "",
    val decoderName: String = "",
    val decoderInputFps: Double = 0.0,
    val decoderInputKbps: Double = 0.0,
    val decoderOutputFps: Double = 0.0,
    val decoderQueue: Int = 0,
    val decoderGaps: Long = 0,
    val decoderMissingFrames: Long = 0,
    val decoderDrops: Long = 0,
    val decoderErrors: Long = 0,
    val decoderRestarts: Long = 0,
    val waitingForKeyframe: Boolean = true,
)

data class RemoteCameraFrame(
    val sequence: Long,
    val keyframe: Boolean,
    val timestampUs: Long,
    val bytes: ByteArray,
)

@HiltViewModel
class CameraViewModel @Inject constructor(private val connection: ConnectionManager) : ViewModel() {
    private val _state = MutableStateFlow(CameraUiState())
    val state = _state.asStateFlow()
    private val _h264Frames = MutableSharedFlow<RemoteCameraFrame>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val h264Frames = _h264Frames.asSharedFlow()
    private val _debugState = MutableStateFlow(CameraDebugUiState())
    val debugState = _debugState.asStateFlow()
    private var deviceId: String? = null
    private var aliveJob: Job? = null
    private var openTimeoutJob: Job? = null
    private var debugStartedAt = SystemClock.elapsedRealtime()
    private var debugWindowStartedAt = debugStartedAt
    private var debugReceivedFrames = 0
    private var debugReceivedBytes = 0L
    private var debugDecodedImages = 0
    private var debugExpectedSequence: Long? = null
    private var debugLastSequence: Long? = null
    private var debugLastFrameBytes = 0
    private var debugKeyframes = 0L
    private var debugSequenceGaps = 0L
    private var debugMissingFrames = 0L
    private var debugBaseArrivalMs: Long? = null
    private var debugBaseTimestampMs: Long? = null
    private var debugDelayDriftMs = 0L
    private var debugLastNalTypes = ""
    private var debugLastLogAt = debugStartedAt

    init {
        viewModelScope.launch {
            connection.cameraEvents.collect { event ->
                when (event) {
                    is CameraEvent.Opened -> if (event.sessionId == _state.value.pendingSessionId) {
                        openTimeoutJob?.cancel()
                        _state.value = _state.value.copy(
                            sessionId = event.sessionId,
                            pendingSessionId = null,
                            codec = event.codec,
                            width = event.width,
                            height = event.height,
                            fps = event.fps,
                            transport = event.transport,
                            bitmap = null,
                            loading = false,
                        )
                        resetDebug(event)
                        startAlive(event.sessionId)
                    }
                    is CameraEvent.Frame -> Unit
                    is CameraEvent.Closed -> if (ownsSession(event.sessionId)) {
                        clear(cameraClosedError(event.reason))
                    }
                    is CameraEvent.Failed -> if (ownsSession(event.sessionId)) {
                        clear(
                            if (event.sessionId == _state.value.pendingSessionId) {
                                cameraOpenError(event.reason)
                            } else {
                                CameraUiError.PLAYBACK_FAILED
                            },
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            connection.cameraFrames.collectLatest { event ->
                if (event.sessionId != _state.value.sessionId) return@collectLatest
                val bytes = event.data
                recordDebugFrame(event)
                if (event.codec == "h264") {
                    _h264Frames.tryEmit(
                        RemoteCameraFrame(
                            event.sequence,
                            event.keyframe,
                            event.timestampMs * 1_000L,
                            bytes,
                        ),
                    )
                } else {
                    val bitmap = runCatching {
                        withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }.getOrNull()
                    if (bitmap == null) {
                        failSession(event.sessionId, CameraUiError.PLAYBACK_FAILED)
                        return@collectLatest
                    }
                    debugDecodedImages += 1
                    _state.value = _state.value.copy(bitmap = bitmap)
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                publishDebug()
            }
        }
    }

    fun load(id: String) {
        deviceId = id
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            connection.listRemoteCameras(id)
                .onSuccess { list ->
                    _state.value = _state.value.copy(cameras = list, selected = list.firstOrNull(), loading = false)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = cameraListError(error),
                    )
                }
        }
    }

    fun select(camera: CameraEntry) {
        _state.value = _state.value.copy(selected = camera)
    }

    fun open() {
        val id = deviceId ?: return
        val camera = _state.value.selected ?: return
        val sessionId = UUID.randomUUID().toString()
        _state.value = _state.value.copy(
            pendingSessionId = sessionId,
            loading = true,
            error = null,
        )
        startOpenTimeout(sessionId)
        viewModelScope.launch {
            connection.openRemoteCamera(id, camera.cameraId, sessionId)
                .onFailure { error ->
                    if (_state.value.pendingSessionId == sessionId) {
                        clear(cameraOpenError(error))
                    }
                }
        }
    }

    fun close() {
        val id = deviceId
        val session = _state.value.sessionId ?: _state.value.pendingSessionId
        if (id != null && session != null) {
            viewModelScope.launch {
                connection.closeRemoteCamera(id, session).onFailure {
                    _state.value = _state.value.copy(error = CameraUiError.CLOSE_FAILED)
                }
            }
        }
        clear(null)
    }

    internal fun onDecoderDebug(stats: H264DecoderDebugStats) {
        viewModelScope.launch {
            _debugState.value = _debugState.value.copy(
                decoderName = stats.decoderName,
                decoderInputFps = stats.inputFps,
                decoderInputKbps = stats.inputKbps,
                decoderOutputFps = stats.outputFps,
                decoderQueue = stats.pendingFrames,
                decoderGaps = stats.sequenceGaps,
                decoderMissingFrames = stats.missingFrames,
                decoderDrops = stats.droppedFrames,
                decoderErrors = stats.errors,
                decoderRestarts = stats.restarts,
                waitingForKeyframe = stats.waitingForKeyframe,
            )
        }
    }

    internal fun onDecoderFailure() {
        viewModelScope.launch {
            _state.value.sessionId?.let { sessionId ->
                failSession(sessionId, CameraUiError.PLAYBACK_FAILED)
            }
        }
    }

    private fun startAlive(session: String) {
        aliveJob?.cancel()
        aliveJob = viewModelScope.launch {
            while (true) {
                val id = deviceId ?: break
                if (connection.sendCameraAlive(id, session).isFailure) {
                    failSession(session, CameraUiError.DISCONNECTED)
                    break
                }
                delay(5_000)
            }
        }
    }

    private fun startOpenTimeout(sessionId: String) {
        openTimeoutJob?.cancel()
        openTimeoutJob = viewModelScope.launch {
            delay(CAMERA_OPEN_TIMEOUT_MILLIS)
            if (_state.value.pendingSessionId == sessionId) {
                deviceId?.let { connection.closeRemoteCamera(it, sessionId) }
                clear(CameraUiError.OPEN_TIMEOUT)
            }
        }
    }

    private fun ownsSession(sessionId: String): Boolean =
        sessionId == _state.value.sessionId || sessionId == _state.value.pendingSessionId

    private fun failSession(sessionId: String, error: CameraUiError) {
        if (!ownsSession(sessionId)) return
        deviceId?.let { deviceId ->
            viewModelScope.launch { connection.closeRemoteCamera(deviceId, sessionId) }
        }
        clear(error)
    }

    private fun cameraListError(error: Throwable): CameraUiError = when (error) {
        is RemoteCameraUnsupportedException -> CameraUiError.UNSUPPORTED
        is RemoteCameraTimeoutException -> CameraUiError.LIST_TIMEOUT
        is RemoteCameraProtocolException -> when (error.reason) {
            CAMERA_REASON_LIST_FAILED -> CameraUiError.LOAD_FAILED
            else -> CameraUiError.PROTOCOL
        }
        else -> CameraUiError.LOAD_FAILED
    }

    private fun cameraOpenError(error: Throwable): CameraUiError = when (error) {
        is RemoteCameraUnsupportedException -> CameraUiError.UNSUPPORTED
        is RemoteCameraProtocolException -> CameraUiError.PROTOCOL
        else -> CameraUiError.OPEN_FAILED
    }

    private fun cameraOpenError(reason: String?): CameraUiError = when (reason) {
        CAMERA_REASON_REJECTED -> CameraUiError.ACCESS_DENIED
        CAMERA_REASON_NOT_AVAILABLE -> CameraUiError.UNAVAILABLE
        CAMERA_REASON_NO_COMMON_CODEC -> CameraUiError.CODEC_UNSUPPORTED
        CAMERA_REASON_SESSION_CONFLICT -> CameraUiError.PROTOCOL
        else -> CameraUiError.OPEN_FAILED
    }

    private fun cameraClosedError(reason: String?): CameraUiError = when (reason) {
        CAMERA_REASON_ALIVE_TIMEOUT -> CameraUiError.HEARTBEAT_TIMEOUT
        CAMERA_REASON_DEVICE_LOST -> CameraUiError.DISCONNECTED
        else -> CameraUiError.DISCONNECTED
    }

    private fun clear(error: CameraUiError?) {
        aliveJob?.cancel()
        openTimeoutJob?.cancel()
        _state.value = _state.value.copy(
            sessionId = null,
            pendingSessionId = null,
            codec = null,
            fps = 0,
            transport = null,
            bitmap = null,
            loading = false,
            error = error,
        )
        _debugState.value = CameraDebugUiState()
        resetDebugCounters()
    }

    private fun resetDebug(event: CameraEvent.Opened) {
        resetDebugCounters()
        _debugState.value = CameraDebugUiState(
            sessionId = event.sessionId,
            codec = event.codec,
            transport = event.transport,
            width = event.width,
            height = event.height,
            fps = event.fps,
        )
        if (BuildConfig.DEBUG) {
            CoLinkLog.i(
                "CameraViewer",
                "session=${CoLinkLog.shortId(event.sessionId)} opened transport=${event.transport} " +
                    "codec=${event.codec} stream=${event.width}x${event.height}@${event.fps}",
            )
        }
    }

    private fun resetDebugCounters() {
        val now = SystemClock.elapsedRealtime()
        debugStartedAt = now
        debugWindowStartedAt = now
        debugLastLogAt = now
        debugReceivedFrames = 0
        debugReceivedBytes = 0
        debugDecodedImages = 0
        debugExpectedSequence = null
        debugLastSequence = null
        debugLastFrameBytes = 0
        debugKeyframes = 0
        debugSequenceGaps = 0
        debugMissingFrames = 0
        debugBaseArrivalMs = null
        debugBaseTimestampMs = null
        debugDelayDriftMs = 0
        debugLastNalTypes = ""
    }

    private fun recordDebugFrame(event: CameraEvent.Frame) {
        val now = SystemClock.elapsedRealtime()
        debugReceivedFrames += 1
        debugReceivedBytes += event.data.size
        debugLastSequence = event.sequence
        debugLastFrameBytes = event.data.size
        val expected = debugExpectedSequence
        if (expected != null && event.sequence != expected) {
            debugSequenceGaps += 1
            if (event.sequence > expected) debugMissingFrames += event.sequence - expected
            if (BuildConfig.DEBUG) {
                CoLinkLog.w(
                    "CameraViewer",
                    "session=${CoLinkLog.shortId(event.sessionId)} sequence gap expected=$expected " +
                        "actual=${event.sequence} keyframe=${event.keyframe}",
                )
            }
        }
        debugExpectedSequence = event.sequence + 1
        val baseArrival = debugBaseArrivalMs
        val baseTimestamp = debugBaseTimestampMs
        if (baseArrival == null || baseTimestamp == null) {
            debugBaseArrivalMs = now
            debugBaseTimestampMs = event.timestampMs
        } else {
            debugDelayDriftMs = now - baseArrival - (event.timestampMs - baseTimestamp)
        }
        if (event.keyframe) {
            debugKeyframes += 1
            debugLastNalTypes = if (event.codec == "h264") annexBNalTypeNames(event.data) else event.codec
            if (BuildConfig.DEBUG) {
                CoLinkLog.d(
                    "CameraViewer",
                    "session=${CoLinkLog.shortId(event.sessionId)} keyframe sequence=${event.sequence} " +
                        "bytes=${event.data.size} nal=$debugLastNalTypes",
                )
            }
        }
    }

    private fun publishDebug() {
        val current = _debugState.value
        if (current.sessionId.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - debugWindowStartedAt).coerceAtLeast(1)
        val seconds = elapsed / 1_000.0
        val receiveFps = debugReceivedFrames / seconds
        val receiveKbps = debugReceivedBytes * 8 / seconds / 1_000
        val next = current.copy(
            elapsedSeconds = (now - debugStartedAt) / 1_000,
            receiveFps = receiveFps,
            receiveKbps = receiveKbps,
            lastSequence = debugLastSequence,
            lastFrameBytes = debugLastFrameBytes,
            keyframes = debugKeyframes,
            sequenceGaps = debugSequenceGaps,
            missingFrames = debugMissingFrames,
            delayDriftMs = debugDelayDriftMs,
            lastNalTypes = debugLastNalTypes,
            decoderInputFps = if (current.codec == "h264") current.decoderInputFps else receiveFps,
            decoderInputKbps = if (current.codec == "h264") current.decoderInputKbps else receiveKbps,
            decoderOutputFps = if (current.codec == "h264") current.decoderOutputFps else debugDecodedImages / seconds,
            waitingForKeyframe = if (current.codec == "h264") current.waitingForKeyframe else false,
        )
        _debugState.value = next
        if (BuildConfig.DEBUG && now - debugLastLogAt >= 2_000) {
            CoLinkLog.d(
                "CameraViewer",
                "session=${CoLinkLog.shortId(next.sessionId)} transport=${next.transport} codec=${next.codec} " +
                    "receive=${oneDecimal(next.receiveFps)}fps/${next.receiveKbps.toInt()}kbps " +
                    "decode=${oneDecimal(next.decoderOutputFps)}fps queue=${next.decoderQueue} " +
                    "gaps=${next.sequenceGaps}/${next.missingFrames} decoderGaps=${next.decoderGaps}/${next.decoderMissingFrames} " +
                    "drops=${next.decoderDrops} errors=${next.decoderErrors} drift=${next.delayDriftMs}ms sync=${!next.waitingForKeyframe}",
            )
            debugLastLogAt = now
        }
        debugWindowStartedAt = now
        debugReceivedFrames = 0
        debugReceivedBytes = 0
        debugDecodedImages = 0
    }

    override fun onCleared() {
        close()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraScreen(deviceId: String, onBack: () -> Unit, viewModel: CameraViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val debugState by viewModel.debugState.collectAsState()
    val errorMessage = state.error?.let { error -> stringResource(error.messageRes) }
    var isFullScreen by rememberSaveable { mutableStateOf(false) }
    var areControlsVisible by rememberSaveable { mutableStateOf(true) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var currentRecorder by remember { mutableStateOf<H264MuxerRecorder?>(null) }

    val snapshotSavedMsg = stringResource(R.string.camera_snapshot_saved)
    val recordSavedMsg = stringResource(R.string.camera_record_saved)

    fun takeSnapshot() {
        val bitmap = state.bitmap
        if (bitmap != null) {
            if (saveBitmapToGallery(context, bitmap)) {
                Toast.makeText(context, snapshotSavedMsg, Toast.LENGTH_SHORT).show()
            }
        } else {
            surfaceViewRef?.let { surfaceView ->
                captureSurfaceViewBitmap(surfaceView) { capturedBitmap ->
                    if (capturedBitmap != null && saveBitmapToGallery(context, capturedBitmap)) {
                        Toast.makeText(context, snapshotSavedMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun toggleRecording() {
        if (!isRecording) {
            val recordFile = File(context.cacheDir, "camera_rec_${System.currentTimeMillis()}.mp4")
            val recorder = H264MuxerRecorder(recordFile, state.width, state.height)
            currentRecorder = recorder
            isRecording = true
        } else {
            isRecording = false
            val recorder = currentRecorder
            currentRecorder = null
            if (recorder != null) {
                recorder.stop()
                if (recorder.outputFile.exists() && recorder.outputFile.length() > 0) {
                    saveVideoToGallery(context, recorder.outputFile)
                    Toast.makeText(context, recordSavedMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(deviceId) { viewModel.load(deviceId) }
    LaunchedEffect(state.sessionId) {
        if (state.sessionId == null) {
            isFullScreen = false
            if (isRecording) {
                isRecording = false
                currentRecorder?.stop()
                currentRecorder = null
            }
        }
    }

    LaunchedEffect(isFullScreen) {
        if (isFullScreen) {
            areControlsVisible = true
        }
    }

    LaunchedEffect(isFullScreen, areControlsVisible) {
        if (isFullScreen && areControlsVisible) {
            delay(3_000L)
            areControlsVisible = false
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            viewModel.h264Frames.collect { frame ->
                currentRecorder?.writeFrame(frame)
            }
        }
    }

    DisposableEffect(isFullScreen) {
        val activity = context.findActivity()
        if (isFullScreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(isFullScreen, areControlsVisible) {
        val activity = context.findActivity()
        if (activity != null) {
            val window = activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullScreen && !areControlsVisible) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val activity = context.findActivity()
            if (activity != null) {
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        if (isFullScreen) {
            isFullScreen = false
        } else {
            viewModel.close()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text(stringResource(R.string.device_control_camera)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.close(); onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.load(deviceId) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        }
                    }
                )
            }
        },
        containerColor = if (isFullScreen) Color.Black else MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullScreen) PaddingValues(0.dp) else padding)
                .background(if (isFullScreen) Color.Black else Color.Transparent)
                .then(
                    if (isFullScreen) {
                        Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            areControlsVisible = !areControlsVisible
                        }
                    } else Modifier
                )
        ) {
            if (state.sessionId == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.camera_select_placeholder),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (state.cameras.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                state.cameras.forEach { camera ->
                                    val selected = camera == state.selected
                                    FilterChip(
                                        selected = selected,
                                        onClick = { viewModel.select(camera) },
                                        label = { Text(camera.label) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = selected,
                                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                                            selectedBorderColor = Color.Transparent,
                                        ),
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (selected) Icons.Default.Check else Icons.Default.Videocam,
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = viewModel::open,
                            enabled = state.selected != null && !state.loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            }
                            Text(if (state.loading) stringResource(R.string.camera_connecting) else stringResource(R.string.camera_open))
                        }
                    }

                    errorMessage?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isFullScreen) {
                                Modifier
                            } else {
                                Modifier
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            }
                        ),
                    verticalArrangement = if (isFullScreen) Arrangement.Center else Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = if (isFullScreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier.fillMaxWidth()
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        CameraVideoContent(
                            state = state,
                            h264Frames = viewModel.h264Frames,
                            onDecoderDebug = viewModel::onDecoderDebug,
                            onDecoderFailure = viewModel::onDecoderFailure,
                            isFullScreen = isFullScreen,
                            onSurfaceViewCreated = { surfaceViewRef = it },
                        )
                        if (!isFullScreen) {
                            IconButton(
                                onClick = { isFullScreen = true },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    if (!isFullScreen) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CameraActionButton(
                                label = stringResource(R.string.camera_snapshot),
                                icon = Icons.Default.CameraAlt,
                                onClick = ::takeSnapshot,
                            )
                            CameraActionButton(
                                label = stringResource(if (isRecording) R.string.camera_record_stop else R.string.camera_record_start),
                                icon = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Videocam,
                                isRecording = isRecording,
                                onClick = ::toggleRecording,
                            )
                            CameraActionButton(
                                label = stringResource(R.string.camera_close),
                                icon = Icons.Default.Close,
                                destructive = true,
                                onClick = viewModel::close,
                            )
                        }

                        CameraDebugPanel(debugState)

                        errorMessage?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isFullScreen && areControlsVisible,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = CircleShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CameraActionButton(
                                label = stringResource(R.string.camera_snapshot),
                                icon = Icons.Default.CameraAlt,
                                onClick = ::takeSnapshot,
                            )
                            CameraActionButton(
                                label = stringResource(if (isRecording) R.string.camera_record_stop else R.string.camera_record_start),
                                icon = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Videocam,
                                isRecording = isRecording,
                                onClick = ::toggleRecording,
                            )
                            CameraActionButton(
                                label = stringResource(R.string.camera_close),
                                icon = Icons.Default.Close,
                                destructive = true,
                                onClick = {
                                    isFullScreen = false
                                    viewModel.close()
                                },
                            )
                            IconButton(onClick = { isFullScreen = false }) {
                                Icon(
                                    Icons.Default.FullscreenExit,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    isRecording: Boolean = false,
) {
    Button(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        colors = if (destructive || isRecording) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun CameraVideoContent(
    state: CameraUiState,
    h264Frames: Flow<RemoteCameraFrame>,
    onDecoderDebug: (H264DecoderDebugStats) -> Unit,
    onDecoderFailure: () -> Unit,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    onSurfaceViewCreated: (SurfaceView?) -> Unit = {},
) {
    val bitmap = state.bitmap
    if (state.codec == "h264") {
        H264VideoSurface(
            state.width,
            state.height,
            requireNotNull(state.sessionId),
            h264Frames,
            onDecoderDebug,
            onDecoderFailure,
            modifier = modifier,
            isFullScreen = isFullScreen,
            onSurfaceViewCreated = onSurfaceViewCreated,
        )
    } else if (bitmap == null) {
        Box(
            modifier = modifier
                .then(
                    if (isFullScreen) {
                        Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    } else {
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.camera_waiting_video),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        RemoteBitmap(bitmap, state.width, state.height, modifier = modifier, isFullScreen = isFullScreen)
    }
}

@Composable
private fun RemoteBitmap(
    bitmap: Bitmap,
    width: Int,
    height: Int,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
) {
    DisposableEffect(bitmap) {
        onDispose {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
    val aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .then(
                if (isFullScreen) {
                    Modifier.fillMaxHeight().aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                }
            ),
    )
}

@Composable
private fun H264VideoSurface(
    width: Int,
    height: Int,
    sessionId: String,
    frames: Flow<RemoteCameraFrame>,
    onDebugStats: (H264DecoderDebugStats) -> Unit,
    onDecoderFailure: () -> Unit,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    onSurfaceViewCreated: (SurfaceView?) -> Unit = {},
) {
    val currentOnDebugStats = rememberUpdatedState(onDebugStats)
    val currentOnDecoderFailure = rememberUpdatedState(onDecoderFailure)
    val decoder = remember(sessionId) {
        H264SurfaceDecoder(
            onDebugStats = { stats -> currentOnDebugStats.value(stats) },
            onDecoderFailure = { currentOnDecoderFailure.value() },
        )
    }
    val frameWidth = width.coerceAtLeast(16)
    val frameHeight = height.coerceAtLeast(16)
    DisposableEffect(decoder) { onDispose(decoder::close) }
    LaunchedEffect(decoder, frames) {
        frames.collect { frame ->
            decoder.queue(frame.bytes, frame.sequence, frame.keyframe, frame.timestampUs)
        }
    }
    val aspectRatio = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFixedSize(frameWidth, frameHeight)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        decoder.configure(holder.surface, frameWidth, frameHeight)
                        onSurfaceViewCreated(this@apply)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurfaceViewCreated(null)
                        decoder.release()
                    }
                })
            }
        },
        modifier = modifier
            .then(
                if (isFullScreen) {
                    Modifier.fillMaxHeight().aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                }
            ),
    )
}

@Composable
private fun CameraDebugPanel(state: CameraDebugUiState) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.camera_debug_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CameraDebugRow(
                        stringResource(R.string.camera_debug_session),
                        if (state.sessionId.isBlank()) {
                            stringResource(R.string.camera_debug_unknown)
                        } else {
                            "${CoLinkLog.shortId(state.sessionId)} · ${state.elapsedSeconds}s"
                        },
                    )
                    CameraDebugRow(
                        stringResource(R.string.camera_debug_stream),
                        stringResource(
                            R.string.camera_debug_stream_value,
                            state.codec,
                            state.transport,
                            state.width,
                            state.height,
                            state.fps,
                        ),
                    )
                    CameraDebugRow(
                        stringResource(R.string.camera_debug_receive),
                        stringResource(
                            R.string.camera_debug_receive_value,
                            state.receiveFps,
                            state.receiveKbps,
                            state.lastFrameBytes,
                        ),
                    )
                    CameraDebugRow(
                        stringResource(R.string.camera_debug_decoder),
                        stringResource(
                            R.string.camera_debug_decoder_value,
                            state.decoderName.ifBlank { "—" },
                            state.decoderOutputFps,
                            state.decoderQueue,
                            stringResource(
                                if (state.waitingForKeyframe) R.string.camera_debug_waiting_keyframe
                                else R.string.camera_debug_synced,
                            ),
                        ),
                    )
                    CameraDebugRow(
                        stringResource(R.string.camera_debug_integrity),
                        stringResource(
                            R.string.camera_debug_integrity_value,
                            state.sequenceGaps,
                            state.missingFrames,
                            state.decoderGaps,
                            state.decoderMissingFrames,
                            state.decoderDrops,
                            state.decoderErrors,
                            state.decoderRestarts,
                        ),
                    )
                    CameraDebugRow(
                        stringResource(R.string.camera_debug_frame),
                        stringResource(
                            R.string.camera_debug_frame_value,
                            state.lastSequence?.toString() ?: "—",
                            state.keyframes,
                            state.delayDriftMs,
                            state.lastNalTypes.ifBlank { "—" },
                        ),
                    )
                }
            }
        }
    }
}

private fun captureSurfaceViewBitmap(surfaceView: SurfaceView, onResult: (Bitmap?) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && surfaceView.holder.surface.isValid) {
        val width = surfaceView.width.coerceAtLeast(1)
        val height = surfaceView.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())
        runCatching {
            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    onResult(bitmap)
                } else {
                    onResult(null)
                }
            }, handler)
        }.onFailure {
            onResult(null)
        }
    } else {
        onResult(null)
    }
}

private fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    return runCatching {
        val filename = "CoLink_Camera_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CoLink")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        true
    }.getOrDefault(false)
}

private fun saveVideoToGallery(context: Context, videoFile: File): Boolean {
    return runCatching {
        val filename = "CoLink_Record_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CoLink")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false
        resolver.openOutputStream(uri)?.use { output ->
            videoFile.inputStream().use { input -> input.copyTo(output) }
        }
        videoFile.delete()
        true
    }.getOrDefault(false)
}

private class H264MuxerRecorder(
    val outputFile: File,
    val width: Int,
    val height: Int,
) {
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var isStarted = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var baseTimestampUs: Long = 0L

    fun writeFrame(frame: RemoteCameraFrame) {
        if (!isStarted) {
            if (sps == null || pps == null) {
                val pair = extractSpsPps(frame.bytes)
                if (pair.first != null) sps = pair.first
                if (pair.second != null) pps = pair.second
            }
            val currentSps = sps
            val currentPps = pps
            if (currentSps != null && currentPps != null && frame.keyframe) {
                runCatching {
                    val mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width.coerceAtLeast(16), height.coerceAtLeast(16))
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(currentSps))
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(currentPps))
                    trackIndex = mediaMuxer.addTrack(format)
                    mediaMuxer.start()
                    muxer = mediaMuxer
                    isStarted = true
                    baseTimestampUs = frame.timestampUs
                }
            }
        }

        if (isStarted && muxer != null && trackIndex >= 0) {
            runCatching {
                val avccData = annexBToAvcc(frame.bytes)
                val buffer = ByteBuffer.wrap(avccData)
                val bufferInfo = MediaCodec.BufferInfo()
                val pts = (frame.timestampUs - baseTimestampUs).coerceAtLeast(0)
                bufferInfo.set(0, avccData.size, pts, if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer?.writeSampleData(trackIndex, buffer, bufferInfo)
            }
        }
    }

    fun stop(): Boolean {
        return runCatching {
            if (isStarted) {
                muxer?.stop()
                muxer?.release()
                muxer = null
                isStarted = false
                true
            } else false
        }.getOrDefault(false)
    }
}

private fun annexBToAvcc(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var i = 0
    val len = bytes.size
    val nals = mutableListOf<ByteArray>()
    
    while (i < len) {
        val startCodeLen = when {
            i + 3 < len && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
            i + 2 < len && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (startCodeLen > 0) {
            val start = i + startCodeLen
            i = start
            while (i < len && !(i + 3 < len && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && (bytes[i + 2] == 1.toByte() || (i + 3 < len && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte())))) {
                i++
            }
            if (i > start) nals.add(bytes.copyOfRange(start, i))
        } else {
            i++
        }
    }

    for (nal in nals) {
        val size = nal.size
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(nal, 0, size)
    }
    return if (out.size() > 0) out.toByteArray() else bytes
}

private fun extractSpsPps(bytes: ByteArray): Pair<ByteArray?, ByteArray?> {
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    var i = 0
    val len = bytes.size
    while (i < len) {
        val startCodeLen = when {
            i + 3 < len && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
            i + 2 < len && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
            else -> 0
        }
        if (startCodeLen > 0) {
            val nalStart = i + startCodeLen
            var nalEnd = len
            var j = nalStart
            while (j < len) {
                if (j + 3 < len && bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 0.toByte() && bytes[j + 3] == 1.toByte()) {
                    nalEnd = j
                    break
                }
                if (j + 2 < len && bytes[j] == 0.toByte() && bytes[j + 1] == 0.toByte() && bytes[j + 2] == 1.toByte()) {
                    nalEnd = j
                    break
                }
                j++
            }
            if (nalStart < nalEnd) {
                val nalType = bytes[nalStart].toInt() and 0x1F
                val nalData = bytes.copyOfRange(i, nalEnd)
                if (nalType == 7 && sps == null) sps = nalData
                if (nalType == 8 && pps == null) pps = nalData
            }
            i = nalEnd
        } else {
            i++
        }
    }
    return Pair(sps, pps)
}

@Composable
private fun CameraDebugRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun oneDecimal(value: Double): String = ((value * 10).toInt() / 10.0).toString()

private fun annexBNalTypeNames(bytes: ByteArray): String {
    val names = mutableListOf<String>()
    var index = 0
    while (index + 3 < bytes.size) {
        val offset = when {
            bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 1.toByte() ->
                index + 3
            index + 4 < bytes.size &&
                bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte() &&
                bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte() ->
                index + 4
            else -> {
                index += 1
                continue
            }
        }
        if (offset >= bytes.size) break
        names += when (val type = bytes[offset].toInt() and 0x1f) {
            1 -> "P"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            9 -> "AUD"
            else -> type.toString()
        }
        index = offset + 1
    }
    return names.joinToString(",")
}
