package com.colink.android.network.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamHostTest {
    @Test
    fun `converts length-prefixed access unit to Annex B`() {
        val accessUnit = byteArrayOf(0, 0, 0, 2, 0x65, 0x01, 0, 0, 0, 2, 0x41, 0x02)

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x41, 0x02),
            normalizeAnnexB(accessUnit),
        )
    }

    @Test
    fun `extracts parameter sets from AVC configuration record`() {
        val configuration = byteArrayOf(
            1, 0x42, 0, 0x1f, -1, -31,
            0, 2, 0x67, 0x01,
            1, 0, 2, 0x68, 0x02,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x67, 0x01, 0, 0, 0, 1, 0x68, 0x02),
            normalizeAnnexB(configuration),
        )
    }

    @Test
    fun `finds both parameter sets after an access unit delimiter`() {
        val accessUnit = byteArrayOf(
            0, 0, 0, 1, 0x09,
            0, 0, 0, 1, 0x67,
            0, 0, 1, 0x68,
            0, 0, 0, 1, 0x65,
        )

        assertTrue(containsRequiredAnnexBParameterSets(accessUnit))
    }

    @Test
    fun `rejects an access unit with only one parameter set`() {
        val accessUnit = byteArrayOf(
            0, 0, 0, 1, 0x67,
            0, 0, 0, 1, 0x65,
        )

        assertFalse(containsRequiredAnnexBParameterSets(accessUnit))
    }

    @Test
    fun `selects at most five logarithmically spread camera sizes`() {
        val selected = selectRepresentativeCameraSizes(
            listOf(
                160 to 90,
                240 to 135,
                320 to 180,
                480 to 270,
                640 to 360,
                960 to 540,
                1280 to 720,
                1920 to 1080,
                0 to 0,
                640 to 360,
            ),
        )

        assertEquals(5, selected.size)
        assertEquals(1920 to 1080, selected.first())
        assertEquals(160 to 90, selected.last())
        assertTrue(
            selected.zipWithNext().all { (larger, smaller) ->
                val largerArea = larger.first.toDouble() * larger.second
                val smallerArea = smaller.first.toDouble() * smaller.second
                largerArea / smallerArea <= 4.01
            },
        )
    }

    @Test
    fun `holds only the first keyframe for a configuration boundary`() {
        val pending = PendingCameraConfig(width = 1280, height = 720, fps = 30)
        val delta = EncodedCameraFrame("session", "h264", false, 41, 1_000, byteArrayOf(1), true)
        val keyframe = EncodedCameraFrame("session", "h264", true, 42, 1_100, byteArrayOf(2), true)
        val laterKeyframe = EncodedCameraFrame("session", "h264", true, 43, 1_200, byteArrayOf(3), true)

        pending.expectGeneration(2)
        assertNull(pending.hold(1, keyframe))
        assertNull(pending.hold(2, delta))
        assertFalse(pending.result.isCompleted)
        val change = pending.hold(2, keyframe)
        assertEquals(42L, change?.effectiveFromSequence)
        assertEquals(1280, change?.width)
        assertSame(keyframe, pending.heldFrame)
        assertTrue(pending.result.isCompleted)
        assertNull(pending.hold(2, laterKeyframe))
        assertSame(keyframe, pending.heldFrame)
    }
}
