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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {
    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val session = authRepository.currentSession().getOrElse {
                _error.value = it.message
                return@launch
            }
            val result = deviceRepository.syncDevices(session)
            _error.value = result.exceptionOrNull()?.message
        }
    }

    fun rotateKey(deviceId: String) {
        viewModelScope.launch {
            _error.value = deviceRepository.rotateDeviceKey(deviceId).exceptionOrNull()?.message
        }
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            _error.value = deviceRepository.deleteDevice(deviceId).exceptionOrNull()?.message
        }
    }
}
