package com.colink.android.data.local.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanMacHistoryTest {
    @Test
    fun normalizesWakeOnLanMacAddress() {
        assertEquals("01:23:45:67:89:AB", normalizeWakeOnLanMac(" 01-23-45-67-89-ab "))
        assertNull(normalizeWakeOnLanMac("01:23:45:67:89"))
    }

    @Test
    fun movesRepeatedAddressToFrontAndCapsHistory() {
        val history = (0..8).map { index ->
            "00:00:00:00:00:${index.toString(16).padStart(2, '0').uppercase()}"
        }

        val updated = updateRecentWakeOnLanMacs(history, "00:00:00:00:00:03")

        assertEquals("00:00:00:00:00:03", updated.first())
        assertEquals(8, updated.size)
        assertEquals(false, "00:00:00:00:00:08" in updated)
    }

    @Test
    fun dropsMalformedStoredAddresses() {
        assertEquals(
            listOf("01:23:45:67:89:AB"),
            decodeRecentWakeOnLanMacs("invalid,01:23:45:67:89:ab,01:23:45:67:89:ab"),
        )
    }
}
