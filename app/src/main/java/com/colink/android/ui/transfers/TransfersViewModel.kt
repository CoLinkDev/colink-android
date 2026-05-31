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
import kotlinx.coroutines.launch

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

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun accept(sessionId: String) {
        viewModelScope.launch {
            _error.value = connectionManager.acceptFileOffer(sessionId).exceptionOrNull()?.message
        }
    }

    fun reject(sessionId: String) {
        viewModelScope.launch {
            _error.value = connectionManager.rejectFileOffer(sessionId).exceptionOrNull()?.message
        }
    }

    fun send(contentResolver: ContentResolver, targetDeviceId: String?, uri: Uri?) {
        viewModelScope.launch {
            if (targetDeviceId == null || uri == null) {
                _error.value = "select a device and file"
                return@launch
            }
            val offer = runCatching { buildFileOffer(contentResolver, uri) }.getOrElse {
                _error.value = it.message
                return@launch
            }
            _error.value = connectionManager
                .sendFileOffer(targetDeviceId, offer)
                .exceptionOrNull()
                ?.message
        }
    }

    fun clearError() {
        _error.value = null
    }
}
