package com.colink.android.network.cloud

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CloudRuntimePeer(
    val online: Boolean,
    val name: String? = null,
    val type: String? = null,
    internal val observedAtNanos: Long = System.nanoTime(),
)

data class CloudRuntimeSnapshot(
    val peers: Map<String, CloudRuntimePeer> = emptyMap(),
)

/**
 * In-memory cloud reachability. The persisted device record is a catalog cache;
 * websocket presence is the source of truth while the cloud connection is active.
 */
@Singleton
class CloudRuntimeState @Inject constructor() {
    private val _snapshot = MutableStateFlow(CloudRuntimeSnapshot())
    val snapshot: StateFlow<CloudRuntimeSnapshot> = _snapshot.asStateFlow()

    fun updatePresence(
        deviceId: String,
        online: Boolean,
        name: String? = null,
        type: String? = null,
    ) {
        _snapshot.update { current ->
            val previous = current.peers[deviceId]
            val peer = CloudRuntimePeer(
                online = online,
                name = name?.takeIf { it.isNotBlank() } ?: previous?.name,
                type = type?.takeIf { it.isNotBlank() } ?: previous?.type,
            )
            current.copy(peers = current.peers + (deviceId to peer))
        }
    }

    /**
     * Applies the complete cloud device snapshot without overwriting websocket
     * presence observed after the corresponding request started.
     */
    fun replaceSnapshot(
        peers: Map<String, CloudRuntimePeer>,
        requestStartedAtNanos: Long,
    ) {
        _snapshot.update { current ->
            val merged = peers.toMutableMap()
            current.peers.forEach { (deviceId, peer) ->
                if (peer.observedAtNanos >= requestStartedAtNanos) {
                    merged[deviceId] = peer
                }
            }
            CloudRuntimeSnapshot(peers = merged)
        }
    }

    fun clear() {
        _snapshot.value = CloudRuntimeSnapshot()
    }
}
