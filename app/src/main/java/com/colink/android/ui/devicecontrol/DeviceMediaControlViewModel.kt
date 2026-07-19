package com.colink.android.ui.devicecontrol

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.network.ConnectionManager
import com.colink.android.network.SystemControlSupport
import com.colink.android.network.SystemControlUnsupportedException
import com.colink.android.network.message.SystemControlAction
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceMediaControlUiState(
    val submitting: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DeviceMediaControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(DeviceMediaControlUiState())

    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()
    val uiState: StateFlow<DeviceMediaControlUiState> = _uiState.asStateFlow()

    fun selectDevice(deviceId: String) {
        _selectedDeviceId.value = deviceId
        _uiState.update { it.copy(error = null) }
    }

    fun mediaControlSupport(deviceId: String?): SystemControlSupport =
        deviceId?.let(connectionManager::mediaControlSupport) ?: SystemControlSupport.UNKNOWN

    fun send(action: SystemControlAction, volume: Int? = null) {
        val targetDeviceId = _selectedDeviceId.value ?: return
        if (_uiState.value.submitting) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(submitting = true, error = null) }
            connectionManager.sendSystemControl(targetDeviceId, action, volume)
                .onSuccess {
                    _uiState.update { it.copy(submitting = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            error = when (error) {
                                is SystemControlUnsupportedException -> localizedContext().getString(
                                    R.string.device_media_unsupported,
                                )

                                else -> error.message?.takeIf(String::isNotBlank)
                                    ?: localizedContext().getString(R.string.message_route_unavailable)
                            },
                        )
                    }
                }
        }
    }

    private fun localizedContext(): Context = LocaleHelper.localized(context)
}
