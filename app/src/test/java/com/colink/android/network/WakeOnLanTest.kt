package com.colink.android.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun buildsStandardMagicPacket() {
        val packet = WakeOnLan.buildMagicPacket("01:23:45:67:89:ab")
            ?: error("expected a magic packet")

        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, packet.copyOfRange(0, 6))
        assertArrayEquals(
            byteArrayOf(1, 35, 69, 103, 137.toByte(), 171.toByte()),
            packet.copyOfRange(6, 12),
        )
        assertArrayEquals(
            byteArrayOf(1, 35, 69, 103, 137.toByte(), 171.toByte()),
            packet.copyOfRange(96, 102),
        )
    }

    @Test
    fun rejectsInvalidMacAddress() {
        assertNull(WakeOnLan.buildMagicPacket("01:23:45:67:89"))
    }
}
