package com.colink.android.network.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
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
}
