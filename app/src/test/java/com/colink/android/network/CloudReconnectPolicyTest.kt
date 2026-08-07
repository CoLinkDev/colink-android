package com.colink.android.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudReconnectPolicyTest {
    @Test
    fun usesRequestedBackoffSequence() {
        val delays = (1..12).map(::cloudBackoffDelay)

        assertEquals(
            listOf(
                1_000L,
                1_000L,
                1_000L,
                2_000L,
                2_000L,
                2_000L,
                4_000L,
                8_000L,
                16_000L,
                30_000L,
                30_000L,
                30_000L,
            ),
            delays,
        )
    }
}
