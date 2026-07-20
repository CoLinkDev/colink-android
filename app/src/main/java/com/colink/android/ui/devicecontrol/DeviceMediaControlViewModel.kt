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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DeviceMediaControlUiState(
    val submitting: Boolean = false,
    val querying: Boolean = false,
    val volume: Int = DEFAULT_VOLUME,
    val playback: String? = null,
    val error: String? = null,
)

private const val DEFAULT_VOLUME = 50
private const val SYSTEM_STATE_QUERY_INTERVAL_MILLIS = 5_000L

@HiltViewModel
class DeviceMediaControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(DeviceMediaControlUiState())
    private var statePollingJob: Job? = null

    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()
    val uiState: StateFlow<DeviceMediaControlUiState> = _uiState.asStateFlow()

    fun selectDevice(deviceId: String) {
        statePollingJob?.cancel()
        _selectedDeviceId.value = deviceId
        _uiState.update {
            it.copy(
                submitting = false,
                querying = connectionManager.systemControlQuerySupport(deviceId) != SystemControlSupport.TOO_OLD,
                volume = DEFAULT_VOLUME,
                playback = null,
                error = null,
            )
        }
    }

    fun startSystemStatePolling() {
        val deviceId = _selectedDeviceId.value ?: return
        statePollingJob?.cancel()
        statePollingJob = viewModelScope.launch(Dispatchers.IO) {
            var initialQuery = true
            while (isActive && _selectedDeviceId.value == deviceId) {
                refreshSystemState(deviceId, initialQuery)
                initialQuery = false
                delay(SYSTEM_STATE_QUERY_INTERVAL_MILLIS)
            }
        }
    }

    fun stopSystemStatePolling() {
        statePollingJob?.cancel()
        statePollingJob = null
        _uiState.update { it.copy(querying = false) }
    }

    fun mediaControlSupport(deviceId: String?): SystemControlSupport =
        deviceId?.let(connectionManager::mediaControlSupport) ?: SystemControlSupport.UNKNOWN

    fun send(action: SystemControlAction, volume: Int? = null) {
        val targetDeviceId = _selectedDeviceId.value ?: return
        if (_uiState.value.submitting || _uiState.value.querying) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(submitting = true, error = null) }
            connectionManager.sendSystemControl(targetDeviceId, action, volume)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            playback = when (action) {
                                SystemControlAction.Play -> "playing"
                                SystemControlAction.Pause -> "paused"
                                else -> it.playback
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            error = when (error) {
                                is SystemControlUnsupportedException -> localizedContext().getString(
                                    R.string.device_control_unsupported,
                                )

                                else -> error.message?.takeIf(String::isNotBlank)
                                    ?: localizedContext().getString(R.string.message_route_unavailable)
                            },
                        )
                    }
                }
        }
    }

    fun updateVolume(volume: Int) {
        _uiState.update { it.copy(volume = volume.coerceIn(0, 100)) }
    }

    private suspend fun refreshSystemState(deviceId: String, initialQuery: Boolean) {
        if (connectionManager.systemControlQuerySupport(deviceId) == SystemControlSupport.TOO_OLD) {
            if (initialQuery && _selectedDeviceId.value == deviceId) {
                _uiState.update { it.copy(querying = false) }
            }
            return
        }
        if (initialQuery) {
            _uiState.update { it.copy(querying = true, error = null) }
        }
        connectionManager.querySystemControlState(deviceId)
            .onSuccess { state ->
                if (_selectedDeviceId.value == deviceId) {
                    _uiState.update {
                        it.copy(
                            querying = if (initialQuery) false else it.querying,
                            volume = state.volume ?: it.volume,
                            playback = state.playback,
                        )
                    }
                }
            }
            .onFailure {
                if (_selectedDeviceId.value == deviceId) {
                    _uiState.update {
                        it.copy(
                            querying = if (initialQuery) false else it.querying,
                            playback = null,
                            error = if (initialQuery) null else it.error,
                        )
                    }
                }
            }
    }

    private fun localizedContext(): Context = LocaleHelper.localized(context)
}
