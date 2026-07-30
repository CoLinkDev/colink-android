package com.colink.android.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryRefreshPolicyTest {
    @Test
    fun ignoresSwimMessagesFromTheKnownEndpoint() {
        assertFalse(
            shouldRefreshMdnsDiscovery(
                endpointIp = "192.168.1.8",
                sourceIp = "192.168.1.8",
                previousRefreshAt = null,
                now = 100_000,
            ),
        )
    }

    @Test
    fun rateLimitsChangedSourceAddressesPerDevice() {
        assertFalse(
            shouldRefreshMdnsDiscovery(
                endpointIp = "192.168.1.8",
                sourceIp = "192.168.1.9",
                previousRefreshAt = 100_000,
                now = 100_000 + MDNS_REFRESH_MIN_INTERVAL_MILLIS - 1,
            ),
        )
        assertTrue(
            shouldRefreshMdnsDiscovery(
                endpointIp = "192.168.1.8",
                sourceIp = "192.168.1.9",
                previousRefreshAt = 100_000,
                now = 100_000 + MDNS_REFRESH_MIN_INTERVAL_MILLIS,
            ),
        )
    }
}
