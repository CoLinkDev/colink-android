package com.colink.android.ui.castboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import com.colink.android.domain.model.Device
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.message.SystemControlAction
import com.colink.android.network.music.MusicSyncManager
import com.colink.android.network.music.MusicSyncState
import com.colink.android.network.sysinfo.SysInfoSyncManager
import com.colink.android.network.sysinfo.SysInfoSyncState
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val screenWaker: CastBoardScreenWaker,
    private val pluginManager: CastBoardPluginManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var sourceDeviceId: String? = null
    private var connectionStatusJob: Job? = null
    private val _selectedDeviceId = MutableStateFlow<String?>(null)

    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()

    val peerProtocolVersions = connectionManager.peerProtocolVersions

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
        connectionStatusJob?.cancel()
        if (sourceDeviceId != null) {
            musicSyncManager.endSession()
            sysInfoSyncManager.endSession()
        }
        sourceDeviceId = normalized
        _selectedDeviceId.value = normalized
        CoLinkLog.i("CastBoard", "source selected device=${CoLinkLog.shortId(normalized)}")
        connectionManager.requestPeerProtocolVersions(normalized)
        musicSyncManager.beginSession(normalized)
        sysInfoSyncManager.beginSession(normalized)
        connectionStatusJob = viewModelScope.launch(Dispatchers.IO) {
            var wasWaitingForDevice = false
            connectionStatus.collectLatest { status ->
                CoLinkLog.i(
                    "CastBoard",
                    "source status device=${CoLinkLog.shortId(normalized)} status=$status",
                )
                if (status == CastBoardConnectionStatus.WaitingForDevice) {
                    wasWaitingForDevice = true
                    return@collectLatest
                }
                if (status != CastBoardConnectionStatus.Connected) {
                    return@collectLatest
                }
                if (wasWaitingForDevice) {
                    screenWaker.wakeForReconnect()
                    wasWaitingForDevice = false
                }
            }
        }
    }

    fun onFrontendMusicAlive() {
        val targetDeviceId = sourceDeviceId ?: return
        if (connectionStatus.value != CastBoardConnectionStatus.Connected) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendMusicAlive(targetDeviceId)
            if (musicSyncManager.state.value.track == null) {
                connectionManager.sendMusicRequest(targetDeviceId)
            }
        }
    }

    fun onFrontendSysInfoAlive() {
        val targetDeviceId = sourceDeviceId ?: return
        if (connectionStatus.value != CastBoardConnectionStatus.Connected) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendSysInfoAlive(targetDeviceId)
        }
    }

    fun sendMediaControl(action: String) {
        val systemAction = when (action) {
            "play" -> SystemControlAction.Play
            "pause" -> SystemControlAction.Pause
            "next" -> SystemControlAction.Next
            "previous" -> SystemControlAction.Previous
            else -> {
                CoLinkLog.w("CastBoard", "ignored unsupported media control action=$action")
                return
            }
        }
        val targetDeviceId = sourceDeviceId ?: return
        if (connectionStatus.value != CastBoardConnectionStatus.Connected) {
            CoLinkLog.w("CastBoard", "ignored media control while source is disconnected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            connectionManager.sendSystemControl(targetDeviceId, systemAction)
                .onFailure { error ->
                    CoLinkLog.w("CastBoard", "media control failed: ${error.message}")
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

    fun enabledPlugins(): List<CastBoardPluginItem> = pluginManager.enabledPlugins()

    fun pluginsDirectory(): File = pluginManager.pluginsDirectory

    override fun onCleared() {
        connectionStatusJob?.cancel()
        if (sourceDeviceId != null) {
            CoLinkLog.i("CastBoard", "source session ended")
            musicSyncManager.endSession()
            sysInfoSyncManager.endSession()
        }
        super.onCleared()
    }
}
