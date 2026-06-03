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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
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
    @ApplicationContext private val context: Context,
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
        viewModelScope.launch(Dispatchers.IO) {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _uiState.update { it.copy(localDeviceId = identity?.deviceId) }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loading = true, message = null) }
            val result = authRepository.currentSession()
                .fold(
                    onSuccess = { session -> deviceRepository.syncDevices(session) },
                    onFailure = { deviceRepository.listLocalDevices() },
                )
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message,
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun rotateKey(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.rotateDeviceKey(deviceId)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: context.getString(R.string.device_key_rotated),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun renameDevice(deviceId: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.updateDeviceName(deviceId, name)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: context.getString(R.string.device_renamed),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.deleteDevice(deviceId)
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: context.getString(R.string.device_deleted),
                localDeviceId = identity?.deviceId,
            )
        }
    }

    fun forgetLanTrust(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.forgetLanTrust(deviceId)
            if (result.isSuccess) {
                connectionManager.refreshLanPairingCandidate(deviceId)
            }
            val identity = deviceRepository.localDeviceIdentity()
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message
                    ?: context.getString(R.string.lan_trust_forgotten),
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
