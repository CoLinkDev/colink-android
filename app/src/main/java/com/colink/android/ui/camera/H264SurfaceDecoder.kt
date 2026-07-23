package com.colink.android.ui.camera

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.colink.android.util.CoLinkLog
import java.util.ArrayDeque

private const val MAX_PENDING_DECODER_FRAMES = 8
private const val DEBUG_REPORT_INTERVAL_MILLIS = 1_000L

internal data class H264DecoderDebugStats(
    val decoderName: String,
    val inputFps: Double,
    val inputKbps: Double,
    val outputFps: Double,
    val pendingFrames: Int,
    val sequenceGaps: Long,
    val missingFrames: Long,
    val droppedFrames: Long,
    val errors: Long,
    val restarts: Long,
    val waitingForKeyframe: Boolean,
    val lastSequence: Long?,
    val lastFrameBytes: Int,
)

/**
 * Low-latency H.264 surface decoder for remote camera frames.
 *
 * MediaCodec input availability is callback-driven. Frames are never discarded merely because
 * an input buffer is busy for a moment, while the bounded access-unit queue prevents stale video
 * from accumulating. A sequence gap drops the damaged GOP and resumes only from a keyframe.
 */
internal class H264SurfaceDecoder(
    private val onDebugStats: (H264DecoderDebugStats) -> Unit = {},
    private val onDecoderFailure: () -> Unit = {},
) {
    private val thread = HandlerThread("colink-camera-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val pendingFrames = ArrayDeque<PendingFrame>()
    private val availableInputs = ArrayDeque<Int>()
    private var decoder: MediaCodec? = null
    private var expectedSequence: Long? = null
    private var synchronized = false
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var surface: Surface? = null
    private var closed = false
    private var decoderName = "unconfigured"
    private var configureCount = 0L
    private var sequenceGaps = 0L
    private var missingFrames = 0L
    private var droppedFrames = 0L
    private var decoderErrors = 0L
    private var lastSequence: Long? = null
    private var lastFrameBytes = 0
    private var reportStartedAt = SystemClock.elapsedRealtime()
    private var reportInputFrames = 0
    private var reportInputBytes = 0L
    private var reportOutputFrames = 0

    fun configure(surface: Surface, width: Int, height: Int) {
        handler.post {
            if (closed) return@post
            this.surface = surface
            configuredWidth = width.coerceAtLeast(16)
            configuredHeight = height.coerceAtLeast(16)
            configureInternal()
        }
    }

    fun queue(bytes: ByteArray, sequence: Long, keyframe: Boolean, timestampUs: Long) {
        handler.post {
            if (closed) return@post
            queueInternal(PendingFrame(bytes, sequence, keyframe, timestampUs))
        }
    }

    private fun configureInternal() {
        releaseCodec()
        val outputSurface = surface?.takeIf { it.isValid } ?: return
        expectedSequence = null
        synchronized = false
        runCatching {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            configureCount += 1
            decoderName = codec.name
            try {
                codec.setCallback(
                    object : MediaCodec.Callback() {
                        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                            if (codec !== decoder) return
                            availableInputs.addLast(index)
                            drainInput(codec)
                        }

                        override fun onOutputBufferAvailable(
                            codec: MediaCodec,
                            index: Int,
                            info: MediaCodec.BufferInfo,
                        ) {
                            if (info.size > 0) reportOutputFrames += 1
                            runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
                            publishDebugIfDue()
                        }

                        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                            CoLinkLog.i(
                                "CameraDecoder",
                                "decoder=$decoderName output-format=$format",
                            )
                        }

                        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                            if (codec !== decoder) return
                            decoderErrors += 1
                            CoLinkLog.e(
                                "CameraDecoder",
                                "decoder=$decoderName error=${error.diagnosticInfo} recoverable=${error.isRecoverable} transient=${error.isTransient}",
                                error,
                            )
                            onDecoderFailure()
                            publishDebug(true)
                            releaseCodec()
                            if (!closed) configureInternal()
                        }
                    },
                    handler,
                )
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    configuredWidth,
                    configuredHeight,
                ).apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                codec.configure(format, outputSurface, null, 0)
                decoder = codec
                codec.start()
                val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    codec.codecInfo.isHardwareAccelerated
                } else {
                    null
                }
                CoLinkLog.i(
                    "CameraDecoder",
                    "configured decoder=$decoderName hardware=$hardware stream=${configuredWidth}x$configuredHeight lowLatency=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.R}",
                )
                publishDebug(true)
            } catch (error: Throwable) {
                runCatching { codec.release() }
                throw error
            }
        }.onFailure {
            decoderErrors += 1
            CoLinkLog.e(
                "CameraDecoder",
                "configure failed decoder=$decoderName stream=${configuredWidth}x$configuredHeight",
                it,
            )
            onDecoderFailure()
            decoder = null
            pendingFrames.clear()
            availableInputs.clear()
            synchronized = false
            publishDebug(true)
        }
    }

    private fun queueInternal(frame: PendingFrame) {
        reportInputFrames += 1
        reportInputBytes += frame.bytes.size
        lastSequence = frame.sequence
        lastFrameBytes = frame.bytes.size
        val codec = decoder
        if (codec == null) {
            droppedFrames += 1
            publishDebugIfDue()
            return
        }
        val expected = expectedSequence
        if (expected != null && frame.sequence != expected) {
            sequenceGaps += 1
            if (frame.sequence > expected) missingFrames += frame.sequence - expected
            val pendingDropped = pendingFrames.size
            droppedFrames += pendingDropped
            pendingFrames.clear()
            synchronized = false
            CoLinkLog.w(
                "CameraDecoder",
                "sequence gap expected=$expected actual=${frame.sequence} keyframe=${frame.keyframe} pendingDropped=$pendingDropped",
            )
        }
        expectedSequence = frame.sequence + 1
        if (!synchronized && !frame.keyframe) {
            droppedFrames += 1
            publishDebugIfDue()
            return
        }

        if (pendingFrames.size >= MAX_PENDING_DECODER_FRAMES) {
            val pendingDropped = pendingFrames.size
            droppedFrames += pendingDropped
            pendingFrames.clear()
            synchronized = false
            CoLinkLog.w(
                "CameraDecoder",
                "input queue overflow capacity=$MAX_PENDING_DECODER_FRAMES sequence=${frame.sequence} keyframe=${frame.keyframe} dropped=$pendingDropped",
            )
            if (!frame.keyframe) {
                droppedFrames += 1
                publishDebugIfDue()
                return
            }
        }
        if (frame.keyframe) synchronized = true
        pendingFrames.addLast(frame)
        drainInput(codec)
        publishDebugIfDue()
    }

    private fun drainInput(codec: MediaCodec) {
        while (codec === decoder && pendingFrames.isNotEmpty() && availableInputs.isNotEmpty()) {
            val index = availableInputs.removeFirst()
            val frame = pendingFrames.removeFirst()
            val input = codec.getInputBuffer(index)
            if (input == null || frame.bytes.size > input.capacity()) {
                droppedFrames += 1 + pendingFrames.size
                synchronized = false
                pendingFrames.clear()
                CoLinkLog.w(
                    "CameraDecoder",
                    "input rejected sequence=${frame.sequence} bytes=${frame.bytes.size} capacity=${input?.capacity() ?: 0}",
                )
                onDecoderFailure()
                runCatching { codec.queueInputBuffer(index, 0, 0, frame.timestampUs, 0) }
                continue
            }
            input.clear()
            input.put(frame.bytes)
            val flags = if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            runCatching {
                codec.queueInputBuffer(index, 0, frame.bytes.size, frame.timestampUs, flags)
            }.onFailure {
                decoderErrors += 1
                droppedFrames += 1 + pendingFrames.size
                synchronized = false
                pendingFrames.clear()
                CoLinkLog.e(
                    "CameraDecoder",
                    "queue input failed sequence=${frame.sequence} bytes=${frame.bytes.size}",
                    it,
                )
                onDecoderFailure()
                publishDebug(true)
            }
        }
    }

    private fun publishDebugIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - reportStartedAt >= DEBUG_REPORT_INTERVAL_MILLIS) publishDebug(false)
    }

    private fun publishDebug(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - reportStartedAt
        if (!force && elapsed < DEBUG_REPORT_INTERVAL_MILLIS) return
        val seconds = elapsed.coerceAtLeast(1) / 1_000.0
        onDebugStats(
            H264DecoderDebugStats(
                decoderName = decoderName,
                inputFps = reportInputFrames / seconds,
                inputKbps = reportInputBytes * 8 / seconds / 1_000,
                outputFps = reportOutputFrames / seconds,
                pendingFrames = pendingFrames.size,
                sequenceGaps = sequenceGaps,
                missingFrames = missingFrames,
                droppedFrames = droppedFrames,
                errors = decoderErrors,
                restarts = (configureCount - 1).coerceAtLeast(0),
                waitingForKeyframe = !synchronized,
                lastSequence = lastSequence,
                lastFrameBytes = lastFrameBytes,
            ),
        )
        if (elapsed >= DEBUG_REPORT_INTERVAL_MILLIS) {
            reportStartedAt = now
            reportInputFrames = 0
            reportInputBytes = 0
            reportOutputFrames = 0
        }
    }

    fun release() {
        handler.post {
            surface = null
            releaseCodec()
        }
    }

    private fun releaseCodec() {
        val codec = decoder
        decoder = null
        pendingFrames.clear()
        availableInputs.clear()
        expectedSequence = null
        synchronized = false
        if (codec != null) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            CoLinkLog.i("CameraDecoder", "released decoder=$decoderName")
        }
        publishDebug(true)
    }

    fun close() {
        handler.post {
            closed = true
            surface = null
            releaseCodec()
            thread.quitSafely()
        }
    }

    private data class PendingFrame(
        val bytes: ByteArray,
        val sequence: Long,
        val keyframe: Boolean,
        val timestampUs: Long,
    )
}
