package com.colink.android.network.camera

private const val CAMERA_FRAME_HEADER_SIZE = 16

data class CameraDataFrame(
    val codec: String,
    val keyframe: Boolean,
    val sequence: Long,
    val timestampMs: Long,
    val payload: ByteArray,
) {
    fun encode(): ByteArray {
        val output = ByteArray(CAMERA_FRAME_HEADER_SIZE + payload.size)
        output[0] = 0x01
        output[1] = 0x01
        output[2] = codecWireValue(codec) ?: error("unsupported camera codec")
        output[3] = if (keyframe) 0x01 else 0x00
        writeUInt(output, 4, sequence)
        writeUInt(output, 8, timestampMs)
        writeUInt(output, 12, payload.size.toLong())
        payload.copyInto(output, CAMERA_FRAME_HEADER_SIZE)
        return output
    }

    override fun equals(other: Any?): Boolean =
        other is CameraDataFrame &&
            codec == other.codec &&
            keyframe == other.keyframe &&
            sequence == other.sequence &&
            timestampMs == other.timestampMs &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = codec.hashCode()
        result = 31 * result + keyframe.hashCode()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): CameraDataFrame? {
            if (bytes.size < CAMERA_FRAME_HEADER_SIZE || bytes[0] != 0x01.toByte() || bytes[1] != 0x01.toByte()) {
                return null
            }
            val payloadSize = readUInt(bytes, 12)
            if (payloadSize > Int.MAX_VALUE || bytes.size != CAMERA_FRAME_HEADER_SIZE + payloadSize.toInt()) {
                return null
            }
            return CameraDataFrame(
                codec = codecName(bytes[2]) ?: return null,
                keyframe = bytes[3].toInt() and 0x01 != 0,
                sequence = readUInt(bytes, 4),
                timestampMs = readUInt(bytes, 8),
                payload = bytes.copyOfRange(CAMERA_FRAME_HEADER_SIZE, bytes.size),
            )
        }
    }
}

private fun codecWireValue(codec: String): Byte? = when (codec) {
    "jpeg" -> 0x01
    "h264" -> 0x02
    "webp" -> 0x03
    else -> null
}

private fun codecName(value: Byte): String? = when (value) {
    0x01.toByte() -> "jpeg"
    0x02.toByte() -> "h264"
    0x03.toByte() -> "webp"
    else -> null
}

private fun writeUInt(output: ByteArray, offset: Int, value: Long) {
    val normalized = value.coerceIn(0, 0xffff_ffffL)
    output[offset] = (normalized shr 24).toByte()
    output[offset + 1] = (normalized shr 16).toByte()
    output[offset + 2] = (normalized shr 8).toByte()
    output[offset + 3] = normalized.toByte()
}

private fun readUInt(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xffL) shl 24) or
        ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
        ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
        (bytes[offset + 3].toLong() and 0xffL)
