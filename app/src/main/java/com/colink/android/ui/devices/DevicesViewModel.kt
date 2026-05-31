package com.colink.android.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.Device
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.DeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {
    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, message = null) }
            val session = authRepository.currentSession().getOrElse {
                _uiState.value = DevicesUiState(loading = false, message = it.message)
                return@launch
            }
            val result = deviceRepository.syncDevices(session)
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "Devices refreshed",
            )
        }
    }

    fun rotateKey(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.rotateDeviceKey(deviceId)
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "Device key rotated",
            )
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = null) }
            val result = deviceRepository.deleteDevice(deviceId)
            _uiState.value = DevicesUiState(
                message = result.exceptionOrNull()?.message ?: "Device deleted",
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
