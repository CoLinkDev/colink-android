package com.colink.android.network.transfer

private const val FILE_DATA_FRAME_VERSION: Byte = 0x01
private const val FILE_DATA_FRAME_HEADER_LEN = 8

enum class FileDataFrameKind(val wireValue: Byte) {
    Chunk(0x01),
    Ack(0x02),
    Finish(0x03),
    Retransmit(0x04),
    Cancel(0x05);

    companion object {
        fun fromWire(value: Byte): FileDataFrameKind? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class FileDataFrame(
    val kind: FileDataFrameKind,
    val index: UInt,
    val payload: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        val output = ByteArray(FILE_DATA_FRAME_HEADER_LEN + payload.size)
        output[0] = FILE_DATA_FRAME_VERSION
        output[1] = kind.wireValue
        output[4] = (index shr 24).toByte()
        output[5] = (index shr 16).toByte()
        output[6] = (index shr 8).toByte()
        output[7] = index.toByte()
        payload.copyInto(output, FILE_DATA_FRAME_HEADER_LEN)
        return output
    }

    override fun equals(other: Any?): Boolean =
        other is FileDataFrame &&
            kind == other.kind &&
            index == other.index &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + index.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun chunk(index: UInt, payload: ByteArray): FileDataFrame =
            FileDataFrame(FileDataFrameKind.Chunk, index, payload)

        fun ack(nextExpectedIndex: UInt): FileDataFrame =
            FileDataFrame(FileDataFrameKind.Ack, nextExpectedIndex)

        fun finish(totalChunks: UInt): FileDataFrame =
            FileDataFrame(FileDataFrameKind.Finish, totalChunks)

        fun retransmit(chunkIndex: UInt): FileDataFrame =
            FileDataFrame(FileDataFrameKind.Retransmit, chunkIndex)

        fun cancel(reason: String): FileDataFrame =
            FileDataFrame(FileDataFrameKind.Cancel, 0u, reason.toByteArray())

        fun decode(bytes: ByteArray): FileDataFrame? {
            if (bytes.size < FILE_DATA_FRAME_HEADER_LEN || bytes[0] != FILE_DATA_FRAME_VERSION) {
                return null
            }
            val kind = FileDataFrameKind.fromWire(bytes[1]) ?: return null
            val index =
                ((bytes[4].toUInt() and 0xffu) shl 24) or
                    ((bytes[5].toUInt() and 0xffu) shl 16) or
                    ((bytes[6].toUInt() and 0xffu) shl 8) or
                    (bytes[7].toUInt() and 0xffu)
            return FileDataFrame(kind, index, bytes.copyOfRange(FILE_DATA_FRAME_HEADER_LEN, bytes.size))
        }
    }
}
