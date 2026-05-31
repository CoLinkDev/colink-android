package com.colink.android.data.remote.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceDtoTest {
    @Test
    fun mapsPublicKeyUpdatedAtToMillis() {
        val device = DeviceDto(
            deviceId = "device",
            name = "Desktop",
            type = "windows",
            online = true,
            lastSeen = null,
            publicKey = "key",
            publicKeyUpdatedAt = "2026-05-23T10:30:00Z",
        ).toDomain()

        assertEquals(1_779_532_200_000L, device.publicKeyUpdatedAt)
        assertEquals(true, device.cloudAvailable)
    }

    @Test
    fun ignoresInvalidPublicKeyTimestamp() {
        val device = DeviceDto(
            deviceId = "device",
            name = "Desktop",
            type = "windows",
            online = false,
            lastSeen = null,
            publicKey = "key",
            publicKeyUpdatedAt = "invalid",
        ).toDomain()

        assertNull(device.publicKeyUpdatedAt)
    }
}
