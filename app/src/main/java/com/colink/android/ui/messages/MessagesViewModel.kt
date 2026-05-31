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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MessagesUiState(
    val sending: Boolean = false,
    val message: String? = null,
)

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

    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    fun send(targetDeviceId: String?, text: String) {
        viewModelScope.launch {
            if (targetDeviceId == null) {
                _uiState.value = MessagesUiState(message = "Select a target device")
                return@launch
            }
            _uiState.value = MessagesUiState(sending = true)
            val result = connectionManager.sendText(targetDeviceId, text)
            _uiState.value = MessagesUiState(
                message = result.exceptionOrNull()?.message ?: "Message sent",
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
