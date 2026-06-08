package com.colink.android.ui.transfers

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.colink.android.R
import com.colink.android.domain.model.FileTransfer
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.domain.repository.FileTransferRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.transfer.buildFileOffer
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

data class TransfersUiState(
    val working: Boolean = false,
    val message: String? = null,
    val localDeviceId: String? = null,
)

@HiltViewModel
class TransfersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    fileTransferRepository: FileTransferRepository,
    private val deviceRepository: DeviceRepository,
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
        viewModelScope.launch(Dispatchers.IO) {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _uiState.update { it.copy(localDeviceId = identity?.deviceId) }
        }
    }

    fun accept(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(working = true, message = null) }
            val result = connectionManager.acceptFileOffer(sessionId)
            _uiState.update {
                it.copy(
                    working = false,
                    message = result.exceptionOrNull()?.message ?: context.getString(R.string.toast_transfer_accepted),
                )
            }
        }
    }

    fun reject(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(working = true, message = null) }
            val result = connectionManager.rejectFileOffer(sessionId)
            _uiState.update {
                it.copy(
                    working = false,
                    message = result.exceptionOrNull()?.message ?: context.getString(R.string.toast_transfer_rejected),
                )
            }
        }
    }

    fun send(contentResolver: ContentResolver, targetDeviceId: String?, uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (targetDeviceId == null || uri == null) {
                _uiState.update {
                    it.copy(working = false, message = context.getString(R.string.toast_select_device_and_file))
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
                    message = result.exceptionOrNull()?.message ?: context.getString(R.string.toast_file_offer_sent),
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
