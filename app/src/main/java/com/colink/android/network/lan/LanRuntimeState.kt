package com.colink.android.network.lan

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LanEndpoint(
    val ip: String,
    val port: Int,
)

data class LanRuntimePeer(
    val endpoint: LanEndpoint? = null,
    val name: String? = null,
    val type: String? = null,
    val state: String = "unavailable",
) {
    val isReachable: Boolean
        get() = endpoint != null && state == "alive"
}

@Singleton
class LanRuntimeState @Inject constructor() {
    private val _peers = MutableStateFlow<Map<String, LanRuntimePeer>>(emptyMap())
    val peers: StateFlow<Map<String, LanRuntimePeer>> = _peers.asStateFlow()

    fun endpoint(deviceId: String): LanEndpoint? = _peers.value[deviceId]?.endpoint

    fun peer(deviceId: String): LanRuntimePeer? = _peers.value[deviceId]

    fun updateDiscovery(deviceId: String, endpoint: LanEndpoint, name: String, type: String) {
        _peers.update { peers ->
            val current = peers[deviceId] ?: LanRuntimePeer()
            peers + (deviceId to current.copy(
                endpoint = endpoint,
                name = name.takeIf { it.isNotBlank() } ?: current.name,
                type = type.takeIf { it.isNotBlank() } ?: current.type,
            ))
        }
    }

    fun removeDiscovery(deviceId: String) {
        _peers.update { peers ->
            val current = peers[deviceId] ?: return@update peers
            if (current.state == "unavailable") peers - deviceId
            else peers + (deviceId to current.copy(endpoint = null))
        }
    }

    fun updateMemberState(deviceId: String, state: String) {
        _peers.update { peers ->
            val current = peers[deviceId] ?: LanRuntimePeer()
            peers + (deviceId to current.copy(state = state))
        }
    }

    fun clear() {
        _peers.value = emptyMap()
    }
}
