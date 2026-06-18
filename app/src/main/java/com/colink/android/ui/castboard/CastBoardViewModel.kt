package com.colink.android.ui.castboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.Device
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.music.MusicSyncManager
import com.colink.android.network.music.MusicSyncState
import com.colink.android.network.sysinfo.SysInfoSyncManager
import com.colink.android.network.sysinfo.SysInfoSyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
private const val SOURCE_DEVICE_ID_ARG = "sourceDeviceId"

enum class CastBoardConnectionStatus {
    Idle,
    WaitingForDevice,
    Connected,
}

@HiltViewModel
class CastBoardViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
    private val musicSyncManager: MusicSyncManager,
    private val sysInfoSyncManager: SysInfoSyncManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var sourceDeviceId: String? = null
    private var heartbeatJob: Job? = null
    private val _selectedDeviceId = MutableStateFlow<String?>(null)

    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    private val _localDeviceId = MutableStateFlow<String?>(null)
    val localDeviceId: StateFlow<String?> = _localDeviceId.asStateFlow()

    val connectionStatus: StateFlow<CastBoardConnectionStatus> =
        combine(_selectedDeviceId, devices) { selectedDeviceId, devices ->
            when {
                selectedDeviceId == null -> CastBoardConnectionStatus.Idle
                devices.firstOrNull { it.deviceId == selectedDeviceId }?.let { it.online || it.lanAvailable } == true ->
                    CastBoardConnectionStatus.Connected
                else -> CastBoardConnectionStatus.WaitingForDevice
            }
        }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CastBoardConnectionStatus.Idle,
            )

    val musicState: StateFlow<MusicSyncState> =
        musicSyncManager.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MusicSyncState(),
        )

    val sysInfoState: StateFlow<SysInfoSyncState> =
        sysInfoSyncManager.state.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SysInfoSyncState(),
        )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _localDeviceId.value = identity?.deviceId
        }
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
        sysInfoSyncManager.beginSession(normalized)
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            connectionStatus.collectLatest { status ->
                if (status != CastBoardConnectionStatus.Connected) {
                    return@collectLatest
                }
                connectionManager.sendMusicAlive(normalized)
                connectionManager.sendSysInfoAlive(normalized)
                connectionManager.sendMusicRequest(normalized)
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    connectionManager.sendMusicAlive(normalized)
                    connectionManager.sendSysInfoAlive(normalized)
                    if (musicSyncManager.state.value.track == null) {
                        connectionManager.sendMusicRequest(normalized)
                    }
                }
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

    override fun onCleared() {
        heartbeatJob?.cancel()
        if (sourceDeviceId != null) {
            musicSyncManager.endSession()
            sysInfoSyncManager.endSession()
        }
        super.onCleared()
    }
}
