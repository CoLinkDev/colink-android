package com.colink.android.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.LanPairingCandidate
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val localDeviceId: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lanPairingCandidates: StateFlow<List<LanPairingCandidate>> =
        connectionManager.lanPairingCandidates.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _uiState.update { it.copy(localDeviceId = identity?.deviceId) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            val result = authRepository.currentSession()
                .fold(
                    onSuccess = { session -> deviceRepository.syncDevices(session) },
                    onFailure = { deviceRepository.listLocalDevices() },
                )
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "Devices refreshed",
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun rotateKey(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.rotateDeviceKey(deviceId)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "Device key rotated",
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.deleteDevice(deviceId)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "Device deleted",
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun forgetLanTrust(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.forgetLanTrust(deviceId)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "LAN trust forgotten",
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun startLanPairing(deviceId: String) {
        connectionManager.startLanPairing(deviceId)
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
