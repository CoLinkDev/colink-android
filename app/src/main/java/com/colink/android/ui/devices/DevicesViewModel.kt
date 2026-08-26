package com.colink.android.ui.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.PeerProtocolVersions
import com.colink.android.network.lan.LanRuntimeState
import com.colink.android.network.lan.LanTrustStore
import com.colink.android.network.lan.PairStringException
import com.colink.android.util.LocaleHelper
import com.colink.android.util.ProtocolReasonFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val localDeviceId: String? = null,
    val pairString: String? = null,
    val pairStringLoading: Boolean = false,
    val legacyPairString: Boolean = false,
)

data class PeerVersionRequestState(
    val waiting: Boolean = false,
    val failed: Boolean = false,
)

private const val PEER_VERSION_REQUEST_TIMEOUT_MILLIS = 15_000L

@HiltViewModel
class DevicesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val lanRuntimeState: LanRuntimeState,
    private val lanTrustStore: LanTrustStore,
) : ViewModel() {
    val devices: StateFlow<List<Device>> = deviceRepository.devices

    val lanPairingCandidates: StateFlow<List<LanPairingCandidate>> =
        combine(
            lanRuntimeState.peers,
            deviceRepository.devices,
            lanTrustStore.trustedPeers,
        ) { peers, devices, trustedPeers ->
            Triple(
                peers,
                devices.associateBy { it.deviceId },
                trustedPeers
                    .asSequence()
                    .filter { it.trustedByLan }
                    .mapTo(mutableSetOf()) { it.deviceId },
            )
        }.map { (peers, knownDevicesById, lanTrustedDeviceIds) ->
            peers.mapNotNull { (deviceId, peer) ->
                val endpoint = peer.endpoint ?: return@mapNotNull null
                if (peer.state !in setOf("alive", "suspect") || deviceId in lanTrustedDeviceIds) {
                    return@mapNotNull null
                }
                val knownDevice = knownDevicesById[deviceId]
                val name = knownDevice?.name?.takeIf { it.isNotBlank() }
                    ?: peer.name?.takeIf { it.isNotBlank() }
                    ?: deviceId
                val type = knownDevice?.type?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?: peer.type
                    ?: "unknown"
                LanPairingCandidate(
                    deviceId = deviceId,
                    name = name,
                    type = type,
                    ip = endpoint.ip,
                    port = endpoint.port,
                    state = peer.state,
                )
            }.sortedWith(
                compareBy<LanPairingCandidate, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.deviceId },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lanConnectionError: StateFlow<String?> =
        connectionManager.lanConnectionError
    val peerProtocolVersions: StateFlow<Map<String, PeerProtocolVersions>> =
        connectionManager.peerProtocolVersions

    private val _uiState = MutableStateFlow(DevicesUiState())
    private val _peerVersionRequestStates =
        MutableStateFlow<Map<String, PeerVersionRequestState>>(emptyMap())
    private val peerVersionRequestJobs = mutableMapOf<String, Job>()
    val peerVersionRequestStates: StateFlow<Map<String, PeerVersionRequestState>> =
        _peerVersionRequestStates.asStateFlow()
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _uiState.update { it.copy(localDeviceId = identity?.deviceId) }
            refreshDevices()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshDevices()
        }
    }

    fun rotateKey(deviceId: String) {
        val localizedContext = LocaleHelper.localized(context)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.rotateDeviceKey(deviceId)
            if (result.isSuccess) {
                connectionManager.restartLanAfterKeyRotation()
            }
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: localizedContext.getString(R.string.device_key_rotated),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun renameDevice(deviceId: String, name: String) {
        val localizedContext = LocaleHelper.localized(context)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.updateDeviceName(deviceId, name)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: localizedContext.getString(R.string.device_renamed),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun deleteDevice(deviceId: String) {
        val localizedContext = LocaleHelper.localized(context)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.deleteDevice(deviceId)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: localizedContext.getString(R.string.device_deleted),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun forgetLanTrust(deviceId: String) {
        val localizedContext = LocaleHelper.localized(context)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.forgetLanTrust(deviceId)
            if (result.isSuccess) {
                connectionManager.disconnectLanPeer(deviceId)
            }
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: localizedContext.getString(R.string.lan_trust_forgotten),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun startLanPairing(deviceId: String) {
        connectionManager.startLanPairing(deviceId)
    }

    fun createPairString(legacy: Boolean = false) {
        _uiState.update { it.copy(pairStringLoading = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.createPairString(legacy).fold(
                onSuccess = { pairString ->
                    _uiState.update {
                        it.copy(
                            pairString = pairString,
                            pairStringLoading = false,
                            legacyPairString = legacy,
                            message = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            pairStringLoading = false,
                            message = ProtocolReasonFormatter.format(
                                LocaleHelper.localized(context),
                                error.message,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun startPairStringPairing(pairString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.startPairStringPairing(pairString).onFailure { error ->
                val reason = (error as? PairStringException)?.reason ?: error.message
                _uiState.update {
                    it.copy(
                        message = ProtocolReasonFormatter.format(LocaleHelper.localized(context), reason),
                    )
                }
            }
        }
    }

    fun dismissPairString() {
        _uiState.update {
            it.copy(
                pairString = null,
                pairStringLoading = false,
                legacyPairString = false,
            )
        }
    }

    fun requestPeerProtocolVersions(deviceId: String) {
        if (deviceId.isBlank()) {
            return
        }
        if (peerProtocolVersions.value[deviceId]?.businessVersion?.isNotBlank() == true) {
            return
        }
        if (peerVersionRequestJobs[deviceId]?.isActive == true) {
            return
        }
        peerVersionRequestJobs.remove(deviceId)?.cancel()
        _peerVersionRequestStates.update {
            it + (deviceId to PeerVersionRequestState(waiting = true))
        }
        connectionManager.requestPeerProtocolVersions(deviceId)
        val job = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + PEER_VERSION_REQUEST_TIMEOUT_MILLIS
            while (true) {
                if (peerProtocolVersions.value[deviceId]?.businessVersion?.isNotBlank() == true) {
                    _peerVersionRequestStates.update {
                        it + (deviceId to PeerVersionRequestState())
                    }
                    break
                }
                if (System.currentTimeMillis() >= deadline) {
                    _peerVersionRequestStates.update {
                        it + (deviceId to PeerVersionRequestState(waiting = false, failed = true))
                    }
                    break
                }
                delay(250)
            }
            peerVersionRequestJobs.remove(deviceId)
        }
        peerVersionRequestJobs[deviceId] = job
    }

    fun retryPeerProtocolVersions(deviceId: String) {
        peerVersionRequestJobs.remove(deviceId)?.cancel()
        _peerVersionRequestStates.update {
            it + (deviceId to PeerVersionRequestState())
        }
        requestPeerProtocolVersions(deviceId)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun clearLanConnectionError() {
        connectionManager.clearLanConnectionError()
    }

    private suspend fun refreshDevices() {
        _uiState.update { it.copy(loading = true, message = null) }
        val (lanResult, deviceResult) = coroutineScope {
            val lanRefresh = async {
                runCatching { connectionManager.refreshLanForDeviceList() }
            }
            val deviceRefresh = async {
                if (connectionManager.cloudState.value.connected) {
                    authRepository.currentSession()
                        .fold(
                            onSuccess = { session -> deviceRepository.syncDevices(session) },
                            onFailure = { deviceRepository.listLocalDevices() },
                        )
                } else {
                    deviceRepository.listLocalDevices()
                }
            }
            lanRefresh.await() to deviceRefresh.await()
        }
        val identity = deviceRepository.localDeviceIdentity()
        _uiState.update {
            it.copy(
                loading = false,
                message = deviceResult.exceptionOrNull()?.message
                    ?: lanResult.exceptionOrNull()?.message,
                localDeviceId = identity?.deviceId,
            )
        }
    }
}
