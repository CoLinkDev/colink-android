package com.colink.android.ui.messages

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.model.TextMessage
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.model.FileTransferDirection
import com.colink.android.domain.model.MessageDirection
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.domain.repository.MessageRepository
import com.colink.android.network.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TimelineItem {
    val id: String
    val timestamp: Long
    val direction: MessageDirection
    val route: String

    data class Message(val message: TextMessage) : TimelineItem {
        override val id: String get() = message.messageId
        override val timestamp: Long get() = message.createdAt
        override val direction: MessageDirection get() = message.direction
        override val route: String get() = message.route
    }

    data class Transfer(val transfer: FileTransfer) : TimelineItem {
        override val id: String get() = transfer.sessionId
        override val timestamp: Long get() = transfer.updatedAt
        override val direction: MessageDirection get() = if (transfer.direction == FileTransferDirection.Incoming) MessageDirection.Incoming else MessageDirection.Outgoing
        override val route: String get() = transfer.route
    }
}

data class MessagesUiState(
    val sending: Boolean = false,
    val message: String? = null,
    val localDeviceId: String? = null,
)

@HiltViewModel
class MessagesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    messageRepository: MessageRepository,
    fileTransferRepository: FileTransferRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val messages: StateFlow<List<TextMessage>> =
        messageRepository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transfers: StateFlow<List<FileTransfer>> =
        fileTransferRepository.transfers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val targetDevices: StateFlow<List<Device>> = combine(devices, _uiState) { devs, state ->
        devs.filter { it.deviceId != state.localDeviceId }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableTargetDevices: StateFlow<List<Device>> = targetDevices
        .map { list -> list.filter { it.online || it.lanAvailable } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedDevice: StateFlow<Device?> = combine(targetDevices, _selectedDeviceId) { devs, id ->
        devs.firstOrNull { it.deviceId == id }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val timelineItems: StateFlow<List<TimelineItem>> = combine(
        messages,
        transfers,
        _selectedDeviceId
    ) { msgs, trns, selectedId ->
        if (selectedId == null) return@combine emptyList()
        val msgItems = msgs.filter { it.deviceId == selectedId }.map { TimelineItem.Message(it) }
        val transferItems = trns.filter { it.deviceId == selectedId }.map { TimelineItem.Transfer(it) }
        (msgItems + transferItems).sortedByDescending { it.timestamp }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _uiState.update { it.copy(localDeviceId = identity?.deviceId) }
        }
    }

    fun send(targetDeviceId: String?, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (targetDeviceId == null) {
                _uiState.update {
                    it.copy(
                        sending = false,
                        message = context.getString(R.string.message_select_target_device),
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(sending = true, message = null) }
            val result = connectionManager.sendText(targetDeviceId, text)
            _uiState.update { state ->
                state.copy(
                    sending = false,
                    message = result.exceptionOrNull()?.message
                        ?: context.getString(R.string.message_sent),
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun selectDevice(deviceId: String?) {
        _selectedDeviceId.value = deviceId
    }
}
