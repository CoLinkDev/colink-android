package com.colink.android.network.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDataFrameTest {
    @Test
    fun `round trips binary camera frame`() {
        val frame = CameraDataFrame("h264", true, 42, 1_400, byteArrayOf(0, 0, 0, 1, 0x65))

        assertEquals(frame, CameraDataFrame.decode(frame.encode()))
    }

    @Test
    fun `rejects mismatched payload length`() {
        val bytes = CameraDataFrame("webp", true, 1, 2, byteArrayOf(1, 2)).encode()
        bytes[15] = 3

        assertNull(CameraDataFrame.decode(bytes))
    }
}
