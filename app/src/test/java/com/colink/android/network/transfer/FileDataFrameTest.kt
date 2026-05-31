package com.colink.android.network.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileDataFrameTest {
    @Test
    fun encodesAndDecodesChunkFrame() {
        val frame = FileDataFrame.chunk(7u, byteArrayOf(1, 2, 3))

        val encoded = frame.encode()

        assertArrayEquals(byteArrayOf(1, 1, 0, 0, 0, 0, 0, 7), encoded.copyOfRange(0, 8))
        assertEquals(frame, FileDataFrame.decode(encoded))
    }

    @Test
    fun rejectsInvalidFrames() {
        assertNull(FileDataFrame.decode(byteArrayOf(2, 1, 0, 0, 0, 0, 0, 1)))
        assertNull(FileDataFrame.decode(byteArrayOf(1, 9, 0, 0, 0, 0, 0, 1)))
        assertNull(FileDataFrame.decode(byteArrayOf(1, 1, 0)))
    }

    @Test
    fun buildsControlFrames() {
        assertEquals(FileDataFrameKind.Ack, FileDataFrame.ack(3u).kind)
        assertEquals(4u, FileDataFrame.finish(4u).index)
        assertEquals(FileDataFrameKind.Retransmit, FileDataFrame.retransmit(2u).kind)
        assertEquals(2u, FileDataFrame.retransmit(2u).index)
        assertArrayEquals("stop".toByteArray(), FileDataFrame.cancel("stop").payload)
    }
}
