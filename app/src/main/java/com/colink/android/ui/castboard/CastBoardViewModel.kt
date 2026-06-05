package com.colink.android.ui.castboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.Device
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.music.MusicSyncManager
import com.colink.android.network.music.MusicSyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
private const val SOURCE_DEVICE_ID_ARG = "sourceDeviceId"

@HiltViewModel
class CastBoardViewModel @Inject constructor(
    deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val musicSyncManager: MusicSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val sourceDeviceId: String? =
        savedStateHandle.get<String>(SOURCE_DEVICE_ID_ARG)?.trim()?.takeIf { it.isNotBlank() }
    private val heartbeatJob: Job?
    private val _selectedDeviceId = MutableStateFlow(sourceDeviceId)

    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val musicState: StateFlow<MusicSyncState> =
        musicSyncManager.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MusicSyncState(),
        )

    init {
        val sourceId = sourceDeviceId
        heartbeatJob = if (sourceId != null) {
            musicSyncManager.beginSession(sourceId)
            viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    connectionManager.sendMusicAlive(sourceId)
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                }
            }
        } else {
            null
        }
    }

    fun selectDevice(deviceId: String) {
        if (sourceDeviceId != null) {
            return
        }
        _selectedDeviceId.value = deviceId
    }

    fun selectedDevice(): Device? =
        devices.value.firstOrNull { it.deviceId == selectedDeviceId.value }

    fun isSourceAvailable(): Boolean {
        val currentSourceId = sourceDeviceId ?: return true
        val device = devices.value.firstOrNull { it.deviceId == currentSourceId } ?: return false
        return device.online || device.lanAvailable
    }

    override fun onCleared() {
        heartbeatJob?.cancel()
        if (sourceDeviceId != null) {
            musicSyncManager.endSession()
        }
        super.onCleared()
    }
}
