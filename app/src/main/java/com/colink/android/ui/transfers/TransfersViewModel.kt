package com.colink.android.ui.transfers

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.transfer.buildFileOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransfersUiState(
    val working: Boolean = false,
    val message: String? = null,
    val localDeviceId: String? = null,
)

@HiltViewModel
class TransfersViewModel @Inject constructor(
    fileTransferRepository: FileTransferRepository,
    deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    val transfers: StateFlow<List<FileTransfer>> =
        fileTransferRepository.transfers.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )
    val devices = deviceRepository.devices.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _uiState = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _uiState.update { it.copy(localDeviceId = identity?.deviceId) }
        }
    }

    fun accept(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(working = true, message = null) }
            val result = connectionManager.acceptFileOffer(sessionId)
            _uiState.update {
                it.copy(
                    working = false,
                    message = result.exceptionOrNull()?.message ?: "Transfer accepted",
                )
            }
        }
    }

    fun reject(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(working = true, message = null) }
            val result = connectionManager.rejectFileOffer(sessionId)
            _uiState.update {
                it.copy(
                    working = false,
                    message = result.exceptionOrNull()?.message ?: "Transfer rejected",
                )
            }
        }
    }

    fun send(contentResolver: ContentResolver, targetDeviceId: String?, uri: Uri?) {
        viewModelScope.launch {
            if (targetDeviceId == null || uri == null) {
                _uiState.update {
                    it.copy(working = false, message = "Select a device and file")
                }
                return@launch
            }
            _uiState.update { it.copy(working = true, message = null) }
            val offer = runCatching { buildFileOffer(contentResolver, uri) }.getOrElse {
                _uiState.update { state ->
                    state.copy(working = false, message = it.message)
                }
                return@launch
            }
            val result = connectionManager
                .sendFileOffer(targetDeviceId, offer)
            _uiState.update {
                it.copy(
                    working = false,
                    message = result.exceptionOrNull()?.message ?: "File offer sent",
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
