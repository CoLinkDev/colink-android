package com.colink.android.network.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRuntimeStateTest {
    @Test
    fun presenceAfterSnapshotRequestWinsOverStaleSnapshot() {
        val state = CloudRuntimeState()
        val requestStartedAtNanos = System.nanoTime()

        state.updatePresence(deviceId = "desktop", online = true)
        state.replaceSnapshot(
            peers = mapOf("desktop" to CloudRuntimePeer(online = false)),
            requestStartedAtNanos = requestStartedAtNanos,
        )

        assertTrue(state.snapshot.value.peers.getValue("desktop").online)
    }

    @Test
    fun clearMakesMissingPeersOfflineImmediately() {
        val state = CloudRuntimeState()
        state.updatePresence(deviceId = "desktop", online = true)

        state.clear()

        assertFalse(state.snapshot.value.peers.containsKey("desktop"))
    }
}
