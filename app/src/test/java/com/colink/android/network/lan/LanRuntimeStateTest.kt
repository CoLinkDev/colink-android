package com.colink.android.network.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRuntimeStateTest {
    @Test
    fun discoveryEndpointAndSwimStateRemainIndependent() {
        val state = LanRuntimeState()

        state.updateDiscovery(
            deviceId = "device-a",
            endpoint = LanEndpoint("192.168.1.8", 27_778),
            name = "Desktop",
            type = "windows",
        )
        state.updateMemberState("device-a", "alive")

        assertEquals(LanEndpoint("192.168.1.8", 27_778), state.endpoint("device-a"))
        assertTrue(requireNotNull(state.peer("device-a")).isReachable)

        state.updateMemberState("device-a", "suspect")
        assertEquals(LanEndpoint("192.168.1.8", 27_778), state.endpoint("device-a"))
        assertTrue(requireNotNull(state.peer("device-a")).isReachable)

        state.updateMemberState("device-a", "dead")
        assertEquals(LanEndpoint("192.168.1.8", 27_778), state.endpoint("device-a"))
        assertFalse(requireNotNull(state.peer("device-a")).isReachable)
    }

    @Test
    fun lostDiscoveryInvalidatesOnlyTheEndpoint() {
        val state = LanRuntimeState()
        state.updateDiscovery(
            deviceId = "device-a",
            endpoint = LanEndpoint("192.168.1.8", 27_778),
            name = "Desktop",
            type = "windows",
        )
        state.updateMemberState("device-a", "alive")

        state.removeDiscovery("device-a")

        assertNull(state.endpoint("device-a"))
        assertFalse(requireNotNull(state.peer("device-a")).isReachable)
        assertEquals("alive", requireNotNull(state.peer("device-a")).state)
    }
}
