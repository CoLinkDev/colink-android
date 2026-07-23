package com.colink.android.ui.camera

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(deviceId: String, onBack: () -> Unit, viewModel: CameraViewModel) {
    val state by viewModel.state.collectAsState()
    val debugState by viewModel.debugState.collectAsState()
    val errorMessage = state.error?.let { error -> stringResource(error.messageRes) }
    LaunchedEffect(deviceId) { viewModel.load(deviceId) }
    BackHandler { viewModel.close(); onBack() }

    Scaffold(
        topBar = {
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state.sessionId == null) {
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
                                modifier = Modifier
                                    .size(ButtonDefaults.IconSize),
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
            } else {
                val bitmap = state.bitmap
                if (state.codec == "h264") {
                    H264VideoSurface(
                        state.width,
                        state.height,
                        requireNotNull(state.sessionId),
                        viewModel.h264Frames,
                        viewModel::onDecoderDebug,
                        viewModel::onDecoderFailure,
                    )
                } else if (bitmap == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
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
                    RemoteBitmap(bitmap, state.width, state.height)
                }

                Button(
                    onClick = viewModel::close,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.camera_close))
                }

                CameraDebugPanel(debugState)
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RemoteBitmap(bitmap: Bitmap, width: Int, height: Int) {
    DisposableEffect(bitmap) {
        onDispose {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f),
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
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFixedSize(frameWidth, frameHeight)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        decoder.configure(holder.surface, frameWidth, frameHeight)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = decoder.release()
                })
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f),
    )
}

@Composable
private fun CameraDebugPanel(state: CameraDebugUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.camera_debug_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
