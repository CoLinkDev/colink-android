package com.colink.android.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.TextMessage
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.MessageRepository
import com.colink.android.network.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MessagesViewModel @Inject constructor(
    deviceRepository: DeviceRepository,
    messageRepository: MessageRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<TextMessage>> =
        messageRepository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun send(targetDeviceId: String?, text: String) {
        viewModelScope.launch {
            if (targetDeviceId == null) {
                _error.value = "select a target device"
                return@launch
            }
            val result = connectionManager.sendText(targetDeviceId, text)
            _error.value = result.exceptionOrNull()?.message
        }
    }

    fun clearError() {
        _error.value = null
    }
}
