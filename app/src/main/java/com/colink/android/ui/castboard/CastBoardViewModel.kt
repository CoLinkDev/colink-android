package com.colink.android.ui.castboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.AppSettings
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
private const val SOURCE_DEVICE_ID_ARG = "sourceDeviceId"

@HiltViewModel
class CastBoardViewModel @Inject constructor(
    deviceRepository: DeviceRepository,
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
    private val musicSyncManager: MusicSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var sourceDeviceId: String? = null
    private var heartbeatJob: Job? = null
    private val _resolutionSavedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _selectedDeviceId = MutableStateFlow<String?>(null)

    val resolutionSavedEvents: SharedFlow<Unit> = _resolutionSavedEvents.asSharedFlow()

    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings(serverUrl = ""),
        )

    val musicState: StateFlow<MusicSyncState> =
        musicSyncManager.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MusicSyncState(),
        )

    init {
        savedStateHandle.get<String>(SOURCE_DEVICE_ID_ARG)?.let(::bindSourceDevice)
    }

    fun bindSourceDevice(deviceId: String?) {
        val normalized = deviceId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (normalized == sourceDeviceId) {
            return
        }
        heartbeatJob?.cancel()
        if (sourceDeviceId != null) {
            musicSyncManager.endSession()
        }
        sourceDeviceId = normalized
        _selectedDeviceId.value = normalized
        musicSyncManager.beginSession(normalized)
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendMusicAlive(normalized)
            connectionManager.sendMusicRequest(normalized)
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                connectionManager.sendMusicAlive(normalized)
            }
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

    fun saveCastBoardResolution(width: Int, height: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsDataStore.saveCastBoardResolution(width, height)
            _resolutionSavedEvents.emit(Unit)
        }
    }

    override fun onCleared() {
        heartbeatJob?.cancel()
        if (sourceDeviceId != null) {
            musicSyncManager.endSession()
        }
        super.onCleared()
    }
}
