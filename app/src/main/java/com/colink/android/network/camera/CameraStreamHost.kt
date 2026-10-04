package com.colink.android.network.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import com.colink.android.BuildConfig
import com.colink.android.network.message.CameraAlivePayload
import com.colink.android.network.message.CameraCapabilities
import com.colink.android.network.message.CameraEntry
import com.colink.android.network.message.CameraFpsRange
import com.colink.android.network.message.CameraOpenAckPayload
import com.colink.android.network.message.CameraOpenPayload
import com.colink.android.network.message.CameraReadyPayload
import com.colink.android.network.message.CameraResolution
import com.colink.android.network.message.CameraV2ConfigPayload
import com.colink.android.network.message.CameraV2OpenAckPayload
import com.colink.android.network.message.CameraV2OpenPayload
import com.colink.android.util.CoLinkLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

private const val CAMERA_ALIVE_TIMEOUT_MILLIS = 15_000L
private const val CAMERA_DEBUG_REPORT_MILLIS = 2_000L
private const val AVC_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
private const val CAMERA_CONFIG_TIMEOUT_MILLIS = 10_000L
private const val MAX_RESOLUTION_TIERS = 5

class CameraStreamHost(
    private val context: Context,
    private val onFrame: (String, EncodedCameraFrame) -> Boolean,
    private val onClosed: (String, String, String, Boolean) -> Unit,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("colink-camera-stream").apply { start() }
    private val handler = Handler(thread.looper)
    private val sessions = ConcurrentHashMap<String, CameraSession>()
    private val avcEncoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
        info.isEncoder && info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } &&
            runCatching {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in
                    info.getCapabilitiesForType(AVC_MIME).colorFormats
            }.getOrDefault(false)
    }
    private val h264Available = avcEncoderInfo != null

    fun list(): List<CameraEntry> = cameraManager.cameraIdList.mapNotNull { cameraId ->
        runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
                ?.sortedByDescending { it.width.toLong() * it.height }
                ?.take(12)
                ?.map { CameraResolution(it.width, it.height) }
                .orEmpty()
            val fps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.let { ranges -> CameraFpsRange(ranges.minOf { it.lower }, ranges.maxOf { it.upper }) }
            val position = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> null
            }
            CameraEntry(cameraId, "Camera $cameraId", position, fps?.let { CameraCapabilities(sizes, it) })
        }.getOrNull()
    }

    fun listV2(): List<CameraEntry> {
        if (!h264Available) return emptyList()
        return cameraManager.cameraIdList.mapNotNull { cameraId ->
            runCatching {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val configurations = promisedConfigurations(cameraId)
                if (configurations.isEmpty()) return@runCatching null
                val grouped = configurations.groupBy { it.width to it.height }
                val resolutions = grouped.entries
                    .sortedByDescending { (size, _) -> size.first.toLong() * size.second }
                    .map { (size, modes) ->
                        CameraResolution(
                            width = size.first,
                            height = size.second,
                            fps = modes.map { CameraFpsRange(it.fps, it.fps) }.distinct(),
                        )
                    }
                val position = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> null
                }
                CameraEntry(
                    cameraId = cameraId,
                    label = "Camera $cameraId",
                    position = position,
                    capabilities = CameraCapabilities(resolutions),
                )
            }.getOrNull()
        }
    }

    fun open(controllerId: String, payload: CameraOpenPayload): CameraOpenAckPayload {
        if (sessions.containsKey(payload.sessionId)) {
            return reject(payload.sessionId, "colink:camera.session_conflict.v1", "Camera session already exists")
        }
        if (sessions.values.any { it.cameraId == payload.cameraId }) {
            return reject(payload.sessionId, "colink:camera.busy.v1", "Camera is already in use")
        }
        val supportedCodecs = if (h264Available) setOf("h264", "webp", "jpeg") else setOf("webp", "jpeg")
        val codec = payload.preferredCodecs.firstOrNull { it in supportedCodecs }
            ?: return reject(payload.sessionId, "colink:camera.no_common_codec.v1", "No supported Android camera codec was offered")
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return reject(payload.sessionId, "colink:camera.rejected.v1", "Camera permission has not been granted")
        }
        val configuration = runCatching { selectConfiguration(payload, codec) }.getOrElse {
            return reject(payload.sessionId, "colink:camera.not_available.v1", "Requested camera is unavailable")
        }
        val session = CameraSession(
            controllerId,
            payload.sessionId,
            payload.cameraId,
            codec,
            configuration.width,
            configuration.height,
            configuration.fps,
            configuration.fpsRange,
            v2 = false,
        )
        sessions[payload.sessionId] = session
        CoLinkLog.i(
            "CameraHost",
            "session=${CoLinkLog.shortId(payload.sessionId)} camera=${payload.cameraId} codec=$codec " +
                "requested=${payload.preferredWidth}x${payload.preferredHeight}@${payload.preferredFps} " +
                "selected=${configuration.width}x${configuration.height}@${configuration.fps} range=${configuration.fpsRange}",
        )
        scheduleAliveTimeout(session)
        runCatching { startCapture(session) }.getOrElse { error ->
            sessions.remove(payload.sessionId)
            session.close(handler)
            return reject(payload.sessionId, "colink:camera.rejected.v1", error.message ?: "Camera access was denied")
        }
        return CameraOpenAckPayload(
            sessionId = payload.sessionId,
            accepted = true,
            negotiatedCodec = codec,
            width = configuration.width,
            height = configuration.height,
            fps = configuration.fps,
        )
    }

    fun openV2(controllerId: String, payload: CameraV2OpenPayload): CameraV2OpenAckPayload {
        if (sessions.containsKey(payload.sessionId)) {
            return rejectV2(payload.sessionId, "colink:camera.session_conflict.v1", "Camera session already exists")
        }
        if (sessions.values.any { it.cameraId == payload.cameraId }) {
            return rejectV2(payload.sessionId, "colink:camera.busy.v1", "Camera is already in use")
        }
        if ("h264" !in payload.preferredCodecs || !h264Available) {
            return rejectV2(payload.sessionId, "colink:camera.no_common_codec.v1", "No supported H.264 camera codec was offered")
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return rejectV2(payload.sessionId, "colink:camera.rejected.v1", "Camera permission has not been granted")
        }
        val configuration = runCatching {
            promisedConfigurations(payload.cameraId).firstOrNull {
                it.width == payload.width && it.height == payload.height && it.fps == payload.fps
            }
        }.getOrNull() ?: return rejectV2(
            payload.sessionId,
            "colink:camera.unsupported_params.v1",
            "Requested camera mode was not promised",
        )
        val session = CameraSession(
            controllerId = controllerId,
            sessionId = payload.sessionId,
            cameraId = payload.cameraId,
            codec = "h264",
            width = configuration.width,
            height = configuration.height,
            fps = configuration.fps,
            fpsRange = configuration.fpsRange,
            v2 = true,
        )
        sessions[payload.sessionId] = session
        scheduleAliveTimeout(session)
        runCatching { startCapture(session) }.getOrElse { error ->
            sessions.remove(payload.sessionId)
            session.close(handler)
            return rejectV2(
                payload.sessionId,
                "colink:camera.rejected.v1",
                error.message ?: "Camera access was denied",
            )
        }
        return CameraV2OpenAckPayload(
            sessionId = payload.sessionId,
            accepted = true,
            negotiatedCodec = "h264",
            width = configuration.width,
            height = configuration.height,
            fps = configuration.fps,
        )
    }

    fun ready(controllerId: String, payload: CameraReadyPayload, v2: Boolean = false) {
        if (payload.transport != "lan" && payload.transport != "relay") return
        sessions[payload.sessionId]?.takeIf { it.controllerId == controllerId && it.v2 == v2 }?.apply {
            ready = true
            transport = payload.transport
            CoLinkLog.i(
                "CameraHost",
                "session=${CoLinkLog.shortId(sessionId)} ready transport=$transport codec=$codec stream=${width}x$height@$fps",
            )
            requestKeyframeIfStreaming()
        }
    }

    fun alive(controllerId: String, payload: CameraAlivePayload, v2: Boolean = false) {
        sessions[payload.sessionId]?.takeIf { it.controllerId == controllerId && it.v2 == v2 }?.apply {
            lastAliveAt = System.currentTimeMillis()
            scheduleAliveTimeout(this)
            requestKeyframeIfStreaming()
        }
    }

    fun close(controllerId: String, sessionId: String, v2: Boolean = false) {
        sessions[sessionId]?.takeIf { it.controllerId == controllerId && it.v2 == v2 }?.let { session ->
            if (sessions.remove(sessionId, session)) {
                session.logClose("remote_close")
                session.close(handler)
            }
        }
    }

    suspend fun configureV2(controllerId: String, payload: CameraV2ConfigPayload): CameraModeChange {
        val session = sessions[payload.sessionId]
            ?.takeIf { it.controllerId == controllerId && it.v2 }
            ?: throw CameraConfigException(
                "colink:camera.session_not_found.v1",
                "Camera session does not exist",
            )
        val configuration = runCatching {
            promisedConfigurations(session.cameraId).firstOrNull {
                it.width == payload.width && it.height == payload.height && it.fps == payload.fps
            }
        }.getOrNull() ?: throw CameraConfigException(
            "colink:camera.unsupported_params.v1",
            "Requested camera mode was not promised",
        )
        val pending = synchronized(session) {
            if (session.pendingConfig != null) {
                throw CameraConfigException(
                    "colink:camera.config_rejected.v1",
                    "Camera reconfiguration is already in progress",
                )
            }
            PendingCameraConfig(
                width = configuration.width,
                height = configuration.height,
                fps = configuration.fps,
            ).also { next ->
                session.pendingConfig = next
                session.width = configuration.width
                session.height = configuration.height
                session.fps = configuration.fps
                session.fpsRange = configuration.fpsRange
                session.markNeedsKeyframe()
            }
        }
        handler.post {
            if (sessions[payload.sessionId] !== session) {
                pending.result.completeExceptionally(
                    CameraConfigException(
                        "colink:camera.session_not_found.v1",
                        "Camera session was closed",
                    ),
                )
                return@post
            }
            session.closePipeline(handler) {
                if (sessions[payload.sessionId] !== session) return@closePipeline
                runCatching { startCapture(session) }.onFailure { error ->
                    pending.result.completeExceptionally(error)
                    failSession(session)
                }
            }
        }
        return try {
            withTimeout(CAMERA_CONFIG_TIMEOUT_MILLIS) { pending.result.await() }
        } catch (error: Throwable) {
            if (sessions.remove(payload.sessionId, session)) {
                session.close(handler)
                onClosed(
                    session.controllerId,
                    session.sessionId,
                    "colink:camera.config_failed.v1",
                    true,
                )
            }
            throw error
        }
    }

    fun commitV2Configuration(
        controllerId: String,
        sessionId: String,
        effectiveFromSequence: Long,
    ): Boolean {
        val session = sessions[sessionId]
            ?.takeIf { it.controllerId == controllerId && it.v2 }
            ?: return false
        val pending = synchronized(session) {
            val pending = session.pendingConfig ?: return false
            val held = pending.heldFrame
                ?.takeIf { it.sequence == effectiveFromSequence }
                ?: return false
            pending to held
        }
        return try {
            deliverFrame(session, pending.second)
            true
        } finally {
            synchronized(session) {
                if (session.pendingConfig === pending.first) session.pendingConfig = null
            }
        }
    }

    fun abortV2Configuration(controllerId: String, sessionId: String) {
        sessions[sessionId]
            ?.takeIf { it.controllerId == controllerId && it.v2 }
            ?.let { session ->
                if (sessions.remove(sessionId, session)) {
                    session.close(handler)
                    onClosed(
                        session.controllerId,
                        session.sessionId,
                        "colink:camera.config_failed.v1",
                        true,
                    )
                }
            }
    }

    fun closeForDevice(controllerId: String) {
        sessions.values.filter { it.controllerId == controllerId }.forEach { session ->
            if (sessions.remove(session.sessionId, session)) session.close(handler)
        }
    }

    fun closeAll() {
        sessions.entries.forEach { (sessionId, session) ->
            if (sessions.remove(sessionId, session)) session.close(handler)
        }
    }

    private fun selectConfiguration(payload: CameraOpenPayload, codec: String): CameraConfiguration {
        val characteristics = cameraManager.getCameraCharacteristics(payload.cameraId)
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val requestedWidth = (payload.preferredWidth ?: 1280).coerceIn(160, 1280)
        val requestedHeight = (payload.preferredHeight ?: 720).coerceIn(120, 720)
        val requestedPixels = requestedWidth.toLong() * requestedHeight
        val sizes = if (codec == "h264") map.getOutputSizes(MediaCodec::class.java) else map.getOutputSizes(ImageFormat.JPEG)
        // Prefer the closest size that does not substantially exceed the request. Oversized
        // 1080p/4K captures crush encode + LAN throughput and were a major source of stutter.
        val size = requireNotNull(
            sizes
                ?.filter { it.width.toLong() * it.height <= requestedPixels * 5 / 4 }
                ?.minByOrNull {
                    kotlin.math.abs(it.width - requestedWidth) + kotlin.math.abs(it.height - requestedHeight)
                }
                ?: sizes?.filter { it.width.toLong() * it.height <= 1280L * 720L }
                    ?.minByOrNull {
                        kotlin.math.abs(it.width - requestedWidth) + kotlin.math.abs(it.height - requestedHeight)
                    }
                ?: sizes?.minByOrNull { it.width.toLong() * it.height },
        )
        val requestedFps = (payload.preferredFps ?: 15).coerceIn(1, 24)
        val fpsRange = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.minByOrNull { range ->
                val applied = requestedFps.coerceIn(range.lower, range.upper)
                kotlin.math.abs(applied - requestedFps) * 100 + (range.upper - range.lower)
            }
        val fps = fpsRange?.let { requestedFps.coerceIn(it.lower, it.upper) } ?: requestedFps
        return CameraConfiguration(size.width, size.height, fps, fpsRange)
    }

    private fun promisedConfigurations(cameraId: String): List<CameraConfiguration> {
        val encoderInfo = avcEncoderInfo ?: return emptyList()
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val sizes = streamMap.getOutputSizes(MediaCodec::class.java).orEmpty()
        val fixedFpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .filter { it.lower == it.upper && it.lower in 1..60 }
            .distinctBy { it.lower }
        if (fixedFpsRanges.isEmpty()) return emptyList()
        val videoCapabilities = encoderInfo.getCapabilitiesForType(AVC_MIME).videoCapabilities
            ?: return emptyList()
        val candidates = sizes.flatMap { size ->
            fixedFpsRanges.mapNotNull { range ->
                val supported = runCatching {
                    videoCapabilities.areSizeAndRateSupported(
                        size.width,
                        size.height,
                        range.lower.toDouble(),
                    )
                }.getOrDefault(false)
                if (supported) {
                    CameraConfiguration(size.width, size.height, range.lower, range)
                } else {
                    null
                }
            }
        }
        val selectedSizes = selectRepresentativeCameraSizes(
            candidates.map { it.width to it.height }.distinct(),
            MAX_RESOLUTION_TIERS,
        ).toSet()
        return candidates
            .filter { (it.width to it.height) in selectedSizes }
            .distinctBy { Triple(it.width, it.height, it.fps) }
            .sortedWith(
                compareByDescending<CameraConfiguration> { it.width.toLong() * it.height }
                    .thenBy { it.fps },
            )
    }

    private fun startCapture(session: CameraSession) {
        val generation = session.beginPipeline()
        val surface = if (session.codec == "h264") {
            startH264Encoder(session, generation)
        } else {
            startImageReader(session, generation)
        }
        cameraManager.openCamera(session.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!session.isCurrentPipeline(generation) || sessions[session.sessionId] !== session) {
                    device.close()
                    return
                }
                session.device = device
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        if (!session.isCurrentPipeline(generation) || sessions[session.sessionId] !== session) {
                            captureSession.close()
                            return
                        }
                        session.captureSession = captureSession
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            session.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }.build()
                        captureSession.setRepeatingRequest(request, null, handler)
                        CoLinkLog.i(
                            "CameraHost",
                            "session=${CoLinkLog.shortId(session.sessionId)} capture configured camera=${session.cameraId} " +
                                "stream=${session.width}x${session.height}@${session.fps} range=${session.fpsRange}",
                        )
                    }
                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        CoLinkLog.e(
                            "CameraHost",
                            "session=${CoLinkLog.shortId(session.sessionId)} capture configuration failed",
                        )
                        if (session.isCurrentPipeline(generation)) failSession(session)
                    }
                }, handler)
            }
            override fun onDisconnected(device: CameraDevice) {
                CoLinkLog.w(
                    "CameraHost",
                    "session=${CoLinkLog.shortId(session.sessionId)} camera disconnected",
                )
                if (session.isCurrentPipeline(generation)) failSession(session)
            }
            override fun onError(device: CameraDevice, error: Int) {
                CoLinkLog.e(
                    "CameraHost",
                    "session=${CoLinkLog.shortId(session.sessionId)} camera error=$error",
                )
                if (session.isCurrentPipeline(generation)) failSession(session)
            }
            override fun onClosed(device: CameraDevice) {
                session.handleDeviceClosed(device)?.invoke()
            }
        }, handler)
    }

    private fun startImageReader(session: CameraSession, generation: Long): Surface {
        // Keep only the latest capture; older JPEG frames are useless for live preview.
        val reader = ImageReader.newInstance(session.width, session.height, ImageFormat.JPEG, 2)
        session.reader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!session.isCurrentPipeline(generation)) return@setOnImageAvailableListener
                if (!session.canSend() || !session.shouldCaptureImage()) return@setOnImageAvailableListener
                val buffer = image.planes.firstOrNull()?.buffer ?: return@setOnImageAvailableListener
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                // Avoid JPEG→Bitmap→WebP re-encode on the capture thread; it tanks FPS.
                // Prefer native JPEG when the negotiated codec is jpeg, and only convert for webp.
                val bytes = if (session.codec == "webp") jpegToWebp(jpeg) else jpeg
                sendFrame(session, generation, bytes, true)
            } finally {
                image.close()
            }
        }, handler)
        return reader.surface
    }

    private fun startH264Encoder(session: CameraSession, generation: Long): Surface {
        val encoderInfo = requireNotNull(avcEncoderInfo) { "No surface-input H.264 encoder is available" }
        val encoder = MediaCodec.createByCodecName(encoderInfo.name)
        session.encoder = encoder
        val codecCapabilities = encoder.codecInfo.getCapabilitiesForType(AVC_MIME)
        val encoderCapabilities = codecCapabilities.encoderCapabilities
        val videoCapabilities = requireNotNull(codecCapabilities.videoCapabilities)
        val targetBitrate = (session.width.toLong() * session.height * session.fps / 7)
            .coerceIn(400_000L, 12_000_000L)
            .toInt()
        val bitrate = targetBitrate.coerceIn(
            videoCapabilities.bitrateRange.lower,
            videoCapabilities.bitrateRange.upper,
        )
        val format = MediaFormat.createVideoFormat(AVC_MIME, session.width, session.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, session.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (
                encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                ) == true
            ) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, session.fps.toFloat())
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }
        CoLinkLog.i(
            "CameraHost",
                "session=${CoLinkLog.shortId(session.sessionId)} encoder=${encoder.name} " +
                    "stream=${session.width}x${session.height}@${session.fps} bitrate=${bitrate / 1_000}kbps " +
                    "cbr=${encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true}",
        )
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (!session.isCurrentPipeline(generation)) return
                    if (info.size <= 0) return
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (!codecConfig && !session.canSend()) {
                        session.markNeedsKeyframe()
                        return
                    }
                    if (session.waitingForKeyframe && !keyframe) return
                    val buffer = codec.getOutputBuffer(index) ?: return
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also(buffer::get)
                    if (codecConfig) {
                        session.parameterSets = normalizeAnnexB(bytes)
                        if (BuildConfig.DEBUG) {
                            CoLinkLog.d(
                                "CameraHost",
                                "session=${CoLinkLog.shortId(session.sessionId)} codec-config bytes=${bytes.size} " +
                                    "nal=${annexBNalTypeNames(session.parameterSets)}",
                            )
                        }
                        return
                    }
                    val accessUnit = normalizeAnnexB(bytes)
                    val payload = if (
                        keyframe &&
                        session.parameterSets.isNotEmpty() &&
                        !containsRequiredAnnexBParameterSets(accessUnit)
                    ) {
                        session.parameterSets + accessUnit
                    } else {
                        accessUnit
                    }
                    if (keyframe && !containsRequiredAnnexBParameterSets(payload)) {
                        CoLinkLog.e(
                            "CameraHost",
                            "session=${CoLinkLog.shortId(session.sessionId)} encoder produced a keyframe without SPS/PPS",
                        )
                        handler.post {
                            if (session.isCurrentPipeline(generation)) failSession(session)
                        }
                        return
                    }
                    if (keyframe) session.keyframeProduced()
                    session.waitingForKeyframe = false
                    if (keyframe && BuildConfig.DEBUG) {
                        CoLinkLog.d(
                            "CameraHost",
                            "session=${CoLinkLog.shortId(session.sessionId)} keyframe bytes=${payload.size} " +
                                "nal=${annexBNalTypeNames(payload)} params=${containsRequiredAnnexBParameterSets(payload)}",
                        )
                    }
                    sendFrame(session, generation, payload, keyframe)
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                CoLinkLog.e(
                    "CameraHost",
                    "session=${CoLinkLog.shortId(session.sessionId)} encoder error=${error.diagnosticInfo}",
                    error,
                )
                if (session.isCurrentPipeline(generation)) failSession(session)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (!session.isCurrentPipeline(generation)) return
                session.parameterSets = listOf("csd-0", "csd-1").mapNotNull { key ->
                    format.getByteBuffer(key)?.let(::readBuffer)?.let(::normalizeAnnexB)
                }.fold(ByteArray(0)) { result, bytes -> result + bytes }
                CoLinkLog.i(
                    "CameraHost",
                    "session=${CoLinkLog.shortId(session.sessionId)} encoder format changed format=$format " +
                        "parameterBytes=${session.parameterSets.size} nal=${annexBNalTypeNames(session.parameterSets)}",
                )
            }
        }, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        return surface
    }

    private fun sendFrame(session: CameraSession, generation: Long, bytes: ByteArray, keyframe: Boolean) {
        val (frame, heldForConfig) = synchronized(session) {
            val pending = session.pendingConfig
            if (pending != null && !pending.accepts(generation)) return
            if (pending?.heldFrame != null) return
            if (pending != null && !keyframe) return
            EncodedCameraFrame(
                sessionId = session.sessionId,
                codec = session.codec,
                keyframe = keyframe,
                sequence = session.sequence++,
                timestampMs = System.currentTimeMillis() - session.startedAt,
                bytes = bytes,
                v2 = session.v2,
            ).let { encoded -> encoded to (pending?.hold(generation, encoded) != null) }
        }
        if (heldForConfig) return
        deliverFrame(session, frame)
    }

    private fun deliverFrame(session: CameraSession, frame: EncodedCameraFrame) {
        val accepted = onFrame(session.controllerId, frame)
        session.recordFrame(frame.bytes.size, frame.keyframe, accepted)
        session.debugSummary()?.let { CoLinkLog.d("CameraHost", it) }
        if (!accepted && session.codec == "h264") {
            if (frame.keyframe) {
                CoLinkLog.w(
                    "CameraHost",
                    "session=${CoLinkLog.shortId(session.sessionId)} keyframe rejected sequence=${frame.sequence} transport=${session.transport}",
                )
            }
            session.markNeedsKeyframe()
            session.requestKeyframeIfStreaming()
        }
    }

    private fun scheduleAliveTimeout(session: CameraSession) {
        session.timeoutRunnable?.let(handler::removeCallbacks)
        val task = Runnable {
            val lastActivity = session.lastAliveAt.takeIf { it > 0 } ?: session.startedAt
            if (System.currentTimeMillis() - lastActivity < CAMERA_ALIVE_TIMEOUT_MILLIS) {
                scheduleAliveTimeout(session)
                return@Runnable
            }
            if (sessions.remove(session.sessionId, session)) {
                CoLinkLog.w(
                    "CameraHost",
                    "session=${CoLinkLog.shortId(session.sessionId)} heartbeat timed out",
                )
                session.logClose("alive_timeout")
                session.close(handler)
                onClosed(session.controllerId, session.sessionId, "colink:camera.alive_timeout.v1", session.v2)
            }
        }
        session.timeoutRunnable = task
        handler.postDelayed(task, CAMERA_ALIVE_TIMEOUT_MILLIS)
    }

    private fun failSession(session: CameraSession) {
        if (sessions.remove(session.sessionId, session)) {
            session.logClose("device_lost")
            session.close(handler)
            onClosed(session.controllerId, session.sessionId, "colink:camera.device_lost.v1", session.v2)
        }
    }

    private fun jpegToWebp(jpeg: ByteArray): ByteArray {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options))
        return try {
            ByteArrayOutputStream().use { output ->
                val quality = 55
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, output)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, output)
                }
                check(ok)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun reject(sessionId: String, reason: String, message: String) = CameraOpenAckPayload(
        sessionId = sessionId, accepted = false, reason = reason, message = message,
    )

    private fun rejectV2(sessionId: String, reason: String, message: String) = CameraV2OpenAckPayload(
        sessionId = sessionId, accepted = false, reason = reason, message = message,
    )

    private data class CameraConfiguration(
        val width: Int,
        val height: Int,
        val fps: Int,
        val fpsRange: Range<Int>?,
    )

    private class CameraSession(
        val controllerId: String,
        val sessionId: String,
        val cameraId: String,
        val codec: String,
        var width: Int,
        var height: Int,
        var fps: Int,
        var fpsRange: Range<Int>?,
        val v2: Boolean,
        var ready: Boolean = false,
        var lastAliveAt: Long = 0L,
        var sequence: Long = 0L,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        var reader: ImageReader? = null
        var encoder: MediaCodec? = null
        var device: CameraDevice? = null
        var captureSession: CameraCaptureSession? = null
        var parameterSets = ByteArray(0)
        var waitingForKeyframe = true
        var transport = "pending"
        private var keyframeRequested = false
        private var lastImageFrameAt = 0L
        private var reportStartedAt = System.currentTimeMillis()
        private var reportFrames = 0
        private var reportBytes = 0L
        private var reportSent = 0
        private var reportKeyframes = 0
        private var reportDrops = 0
        private var totalFrames = 0L
        private var totalSent = 0L
        private var totalDrops = 0L
        var timeoutRunnable: Runnable? = null
        var pendingConfig: PendingCameraConfig? = null
        private var pipelineGeneration = 0L
        private var pipelineClosedAction: (() -> Unit)? = null

        fun canSend() = ready && System.currentTimeMillis() - lastAliveAt <= CAMERA_ALIVE_TIMEOUT_MILLIS

        @Synchronized
        fun beginPipeline(): Long {
            pipelineGeneration += 1
            pendingConfig?.expectGeneration(pipelineGeneration)
            parameterSets = ByteArray(0)
            waitingForKeyframe = true
            keyframeRequested = false
            lastImageFrameAt = 0L
            return pipelineGeneration
        }

        @Synchronized
        fun isCurrentPipeline(generation: Long) = pipelineGeneration == generation

        @Synchronized
        fun handleDeviceClosed(closedDevice: CameraDevice): (() -> Unit)? {
            if (device === closedDevice) device = null
            return pipelineClosedAction.also { pipelineClosedAction = null }
        }

        fun shouldCaptureImage(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastImageFrameAt < 1_000L / fps.coerceAtLeast(1)) return false
            lastImageFrameAt = now
            return true
        }

        fun requestKeyframeIfStreaming() {
            if (codec != "h264" || !canSend() || keyframeRequested) return
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            keyframeRequested = true
        }

        fun markNeedsKeyframe() {
            waitingForKeyframe = true
            keyframeRequested = false
        }

        fun keyframeProduced() {
            keyframeRequested = false
        }

        fun recordFrame(bytes: Int, keyframe: Boolean, accepted: Boolean) {
            reportFrames += 1
            reportBytes += bytes
            totalFrames += 1
            if (keyframe) reportKeyframes += 1
            if (accepted) {
                reportSent += 1
                totalSent += 1
            } else {
                reportDrops += 1
                totalDrops += 1
            }
        }

        fun debugSummary(): String? {
            if (!BuildConfig.DEBUG) return null
            val now = System.currentTimeMillis()
            val elapsed = now - reportStartedAt
            if (elapsed < CAMERA_DEBUG_REPORT_MILLIS) return null
            val seconds = elapsed.coerceAtLeast(1) / 1_000.0
            val message =
                "session=${CoLinkLog.shortId(sessionId)} transport=$transport codec=$codec " +
                    "encode=${"%.1f".format(reportFrames / seconds)}fps send=${"%.1f".format(reportSent / seconds)}fps " +
                    "bitrate=${(reportBytes * 8 / seconds / 1_000).toInt()}kbps keyframes=$reportKeyframes " +
                    "drops=$reportDrops waitingKeyframe=$waitingForKeyframe"
            reportStartedAt = now
            reportFrames = 0
            reportBytes = 0
            reportSent = 0
            reportKeyframes = 0
            reportDrops = 0
            return message
        }

        fun logClose(reason: String) {
            CoLinkLog.i(
                "CameraHost",
                "session=${CoLinkLog.shortId(sessionId)} stopped reason=$reason frames=$totalFrames sent=$totalSent drops=$totalDrops",
            )
        }

        fun closePipeline(handler: Handler, onClosed: (() -> Unit)? = null) {
            synchronized(this) {
                pipelineGeneration += 1
                pipelineClosedAction = onClosed
            }
            val oldCaptureSession = captureSession.also { captureSession = null }
            val oldDevice = device
            val oldReader = reader.also { reader = null }
            val oldEncoder = encoder.also { encoder = null }
            runCatching { oldCaptureSession?.close() }
            runCatching { oldReader?.close() }
            runCatching { oldEncoder?.stop() }
            runCatching { oldEncoder?.release() }
            if (oldDevice != null) {
                runCatching { oldDevice.close() }.onFailure {
                    handleDeviceClosed(oldDevice)?.let { action -> handler.post { action() } }
                }
            } else {
                onClosed?.let { action -> handler.post { action() } }
            }
        }

        fun close(handler: Handler) {
            timeoutRunnable?.let(handler::removeCallbacks)
            timeoutRunnable = null
            synchronized(this) {
                pendingConfig?.result?.completeExceptionally(
                    CameraConfigException(
                        "colink:camera.session_not_found.v1",
                        "Camera session was closed",
                    ),
                )
                pendingConfig = null
                pipelineClosedAction = null
            }
            closePipeline(handler)
        }
    }
}

class CameraConfigException(val reason: String, message: String) : IllegalStateException(message)

data class CameraModeChange(
    val sessionId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val effectiveFromSequence: Long,
)

internal class PendingCameraConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val result: CompletableDeferred<CameraModeChange> = CompletableDeferred(),
) {
    var heldFrame: EncodedCameraFrame? = null
        private set
    private var expectedGeneration: Long? = null

    fun expectGeneration(generation: Long) {
        expectedGeneration = generation
    }

    fun accepts(generation: Long): Boolean = expectedGeneration == generation

    fun hold(generation: Long, frame: EncodedCameraFrame): CameraModeChange? {
        if (!accepts(generation) || !frame.keyframe || heldFrame != null) return null
        heldFrame = frame
        return CameraModeChange(
            sessionId = frame.sessionId,
            width = width,
            height = height,
            fps = fps,
            effectiveFromSequence = frame.sequence,
        ).also { result.complete(it) }
    }
}

internal fun selectRepresentativeCameraSizes(
    sizes: List<Pair<Int, Int>>,
    limit: Int = MAX_RESOLUTION_TIERS,
): List<Pair<Int, Int>> {
    if (limit <= 0) return emptyList()
    val ordered = sizes
        .filter { (width, height) -> width > 0 && height > 0 }
        .distinct()
        .sortedWith(compareBy<Pair<Int, Int>> { it.first.toLong() * it.second }.thenBy { it.first }.thenBy { it.second })
    if (ordered.size <= limit) return ordered.asReversed()
    if (limit == 1) return listOf(ordered.last())
    val selected = mutableListOf(0, ordered.lastIndex)
    val logArea = { index: Int ->
        kotlin.math.ln(ordered[index].first.toDouble() * ordered[index].second)
    }
    val minimum = logArea(0)
    val maximum = logArea(ordered.lastIndex)
    for (tier in 1 until limit - 1) {
        val target = minimum + (maximum - minimum) * tier / (limit - 1)
        val nearest = ordered.indices.minWithOrNull(
            compareBy<Int> { kotlin.math.abs(logArea(it) - target) }.thenBy { it },
        )
        if (nearest != null && nearest !in selected) selected += nearest
    }
    while (selected.size < limit) {
        val next = ordered.indices
            .filterNot(selected::contains)
            .maxByOrNull { index ->
                selected.minOf { selectedIndex ->
                    kotlin.math.abs(logArea(index) - logArea(selectedIndex))
                }
            } ?: break
        selected += next
    }
    return selected.sorted().map(ordered::get).asReversed()
}

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
    return names.joinToString(",").ifBlank { "unknown" }
}

data class EncodedCameraFrame(
    val sessionId: String,
    val codec: String,
    val keyframe: Boolean,
    val sequence: Long,
    val timestampMs: Long,
    val bytes: ByteArray,
    val v2: Boolean,
)

private fun readBuffer(buffer: ByteBuffer): ByteArray {
    val duplicate = buffer.duplicate()
    return ByteArray(duplicate.remaining()).also(duplicate::get)
}

internal fun containsRequiredAnnexBParameterSets(bytes: ByteArray): Boolean {
    var i = 0
    var hasSps = false
    var hasPps = false
    while (i + 3 < bytes.size) {
        val start = when {
            bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> i + 3
            i + 4 <= bytes.size &&
                bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> i + 4
            else -> {
                i += 1
                continue
            }
        }
        if (start >= bytes.size) break
        val nalType = bytes[start].toInt() and 0x1f
        hasSps = hasSps || nalType == 7
        hasPps = hasPps || nalType == 8
        if (hasSps && hasPps) return true
        i = start + 1
    }
    return false
}

internal fun normalizeAnnexB(bytes: ByteArray): ByteArray {
    if (bytes.size < 4 || bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        (bytes[2] == 1.toByte() || bytes[2] == 0.toByte() && bytes[3] == 1.toByte())) return bytes
    if (bytes.size >= 7 && bytes[0] == 1.toByte()) {
        return normalizeAvcConfigurationRecord(bytes) ?: bytes
    }
    val output = ByteArrayOutputStream(bytes.size + 16)
    var offset = 0
    while (offset + 4 <= bytes.size) {
        val length = ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        if (length <= 0 || offset + 4 + length > bytes.size) return bytes
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(bytes, offset + 4, length)
        offset += 4 + length
    }
    return if (offset == bytes.size) output.toByteArray() else bytes
}

private fun normalizeAvcConfigurationRecord(bytes: ByteArray): ByteArray? {
    val output = ByteArrayOutputStream(bytes.size + 16)
    var offset = 6
    val spsCount = bytes[5].toInt() and 0x1f
    repeat(spsCount) {
        if (offset + 2 > bytes.size) return null
        val length = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        offset += 2
        if (length <= 0 || offset + length > bytes.size) return null
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(bytes, offset, length)
        offset += length
    }
    if (offset >= bytes.size) return null
    val ppsCount = bytes[offset++].toInt() and 0xff
    repeat(ppsCount) {
        if (offset + 2 > bytes.size) return null
        val length = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
        offset += 2
        if (length <= 0 || offset + length > bytes.size) return null
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(bytes, offset, length)
        offset += length
    }
    return output.toByteArray().takeIf { it.isNotEmpty() }
}
