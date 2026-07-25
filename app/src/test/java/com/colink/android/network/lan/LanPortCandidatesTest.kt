package com.colink.android.network.lan

import org.junit.Assert.assertEquals
import org.junit.Test

class LanPortCandidatesTest {
    @Test
    fun choosesTheNearestHigherPortOnTies() {
        assertEquals(
            listOf(27_777, 27_778, 27_776, 27_779, 27_775),
            lanPortCandidates().take(5).toList(),
        )
    }

    @Test
    fun staysWithinTheUnprivilegedRange() {
        assertEquals(listOf(1_024, 1_025, 1_026), lanPortCandidates(1_024).take(3).toList())
        assertEquals(listOf(65_535, 65_534, 65_533), lanPortCandidates(65_535).take(3).toList())
    }
}
