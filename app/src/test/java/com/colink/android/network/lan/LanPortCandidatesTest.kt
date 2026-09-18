package com.colink.android.network.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LanPortCandidatesTest {
    @Test
    fun startsWithPreferredThenCoversRandomRangeWithoutDuplicates() {
        val candidates = lanPortCandidates(random = Random(0)).toList()

        assertEquals(LAN_PORT, candidates.first())
        assertEquals(
            (20_000..65_535).filter { it != LAN_PORT },
            candidates.drop(1).sorted(),
        )
    }

    @Test
    fun keepsPreferredPortOutsideTheRandomFallbackRange() {
        val candidates = lanPortCandidates(preferredPort = 1_024, random = Random(1)).toList()

        assertEquals(1_024, candidates.first())
        assertEquals((20_000..65_535).toList(), candidates.drop(1).sorted())
    }

    @Test
    fun doesNotGenerateRandomFallbacksWhenPreferredPortSucceeds() {
        val failingRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("fallback ports should remain lazy")
        }

        assertEquals(LAN_PORT, lanPortCandidates(random = failingRandom).first())
    }

    @Test
    fun randomFallbacksStayWithinTheConfiguredRange() {
        val fallbackPorts = lanPortCandidates(random = Random(2)).drop(1).take(1_000).toList()

        assertTrue(fallbackPorts.all { it in 20_000..65_535 && it != LAN_PORT })
        assertEquals(fallbackPorts.size, fallbackPorts.distinct().size)
    }
}
