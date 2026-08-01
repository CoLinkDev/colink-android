package com.colink.android.ui.devicecontrol

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.domain.model.Device
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.RemoteCameraSupport
import com.colink.android.network.SystemControlSupport
import com.colink.android.network.SystemControlUnsupportedException
import com.colink.android.network.message.PendingPowerAction
import com.colink.android.network.message.SystemControlAction
import com.colink.android.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DevicePowerControlUiState(
    val submitting: Boolean = false,
    val error: String? = null,
    val sentAction: SystemControlAction? = null,
    val pendingPower: PendingPowerAction? = null,
    val pendingPowerRemainingMs: Long? = null,
    val pendingPowerDeadlineElapsedRealtimeMs: Long? = null,
)

private const val PENDING_POWER_QUERY_INTERVAL_MILLIS = 5_000L
private const val PENDING_POWER_TICK_INTERVAL_MILLIS = 1_000L
private const val INITIAL_PENDING_POWER_QUERY_DELAY_MILLIS = 2_000L

@HiltViewModel
class DevicePowerControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    private val _selectedDeviceId = MutableStateFlow<String?>(null)
    private val _localDeviceId = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(DevicePowerControlUiState())
    private val pendingPowerQueryMutex = Mutex()
    private var pendingPowerPollingJob: Job? = null
    private var pendingPowerTickerJob: Job? = null

    val devices: StateFlow<List<Device>> =
        deviceRepository.devices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
    val selectedDeviceId: StateFlow<String?> = _selectedDeviceId.asStateFlow()
    val localDeviceId: StateFlow<String?> = _localDeviceId.asStateFlow()
    val uiState: StateFlow<DevicePowerControlUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val identity = deviceRepository.localDeviceIdentity()
                ?: deviceRepository.ensureLocalDeviceIdentity().getOrNull()
            _localDeviceId.value = identity?.deviceId
        }
    }

    fun selectDevice(deviceId: String) {
        pendingPowerPollingJob?.cancel()
        pendingPowerTickerJob?.cancel()
        _selectedDeviceId.value = deviceId
        _uiState.update { DevicePowerControlUiState() }
        pendingPowerPollingJob = viewModelScope.launch(Dispatchers.IO) {
            delay(INITIAL_PENDING_POWER_QUERY_DELAY_MILLIS)
            while (isActive) {
                refreshPendingPower(deviceId)
                delay(PENDING_POWER_QUERY_INTERVAL_MILLIS)
            }
        }
    }

    fun wakeOnLanSupport(deviceId: String): SystemControlSupport =
        connectionManager.wakeOnLanSupport(deviceId)

    fun terminalSupport(deviceId: String): SystemControlSupport =
        connectionManager.terminalSupport(deviceId)

    fun remoteCameraSupport(deviceId: String): RemoteCameraSupport =
        connectionManager.remoteCameraSupport(deviceId)

    fun systemControlSupport(deviceId: String?): SystemControlSupport =
        deviceId?.let(connectionManager::systemControlSupport) ?: SystemControlSupport.UNKNOWN

    fun delayedPowerControlSupport(deviceId: String?): SystemControlSupport =
        deviceId?.let(connectionManager::delayedPowerControlSupport)
            ?: SystemControlSupport.UNKNOWN

    fun send(action: SystemControlAction, delay: Int? = null) {
        val targetDeviceId = _selectedDeviceId.value ?: return
        if (_uiState.value.submitting) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(submitting = true, error = null, sentAction = null) }
            connectionManager.sendSystemControl(targetDeviceId, action, delay = delay)
                .onSuccess {
                    viewModelScope.launch(Dispatchers.Main) {
                        val labelRes = when (action) {
                            SystemControlAction.Sleep -> R.string.device_power_sleep
                            SystemControlAction.Shutdown -> R.string.device_power_shutdown
                            SystemControlAction.Lock -> R.string.device_power_lock
                            SystemControlAction.CancelPower -> R.string.device_power_cancel_scheduled
                            else -> error("Not a power control action")
                        }
                        val label = localizedContext().getString(labelRes)
                        Toast.makeText(
                            localizedContext(),
                            localizedContext().getString(R.string.device_power_command_sent, label),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    _uiState.update { state ->
                        state.copy(submitting = false, sentAction = action)
                    }
                    refreshPendingPower(targetDeviceId)
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            submitting = false,
                            error = when (error) {
                                is SystemControlUnsupportedException -> localizedContext().getString(
                                    R.string.device_control_unsupported,
                                )
                                else -> error.message?.takeIf { it.isNotBlank() }
                                    ?: localizedContext().getString(R.string.message_route_unavailable)
                            },
                        )
                    }
                }
        }
    }

    private suspend fun refreshPendingPower(deviceId: String) {
        pendingPowerQueryMutex.withLock {
            if (connectionManager.pendingPowerQuerySupport(deviceId) != SystemControlSupport.SUPPORTED) {
                if (_selectedDeviceId.value == deviceId) {
                    updatePendingPower(null)
                }
                return
            }
            connectionManager.querySystemControlState(deviceId)
                .onSuccess { result ->
                    if (_selectedDeviceId.value == deviceId) {
                        updatePendingPower(result.pendingPower)
                    }
                }
        }
    }

    private fun updatePendingPower(pendingPower: PendingPowerAction?) {
        pendingPowerTickerJob?.cancel()
        val deadline = pendingPower?.let {
            SystemClock.elapsedRealtime() + it.remainingMs
        }
        _uiState.update {
            it.copy(
                pendingPower = pendingPower,
                pendingPowerRemainingMs = pendingPower?.remainingMs,
                pendingPowerDeadlineElapsedRealtimeMs = deadline,
            )
        }
        if (deadline == null) {
            return
        }
        pendingPowerTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(PENDING_POWER_TICK_INTERVAL_MILLIS)
                val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                if (remainingMs == 0L) {
                    _uiState.update {
                        it.copy(
                            pendingPower = null,
                            pendingPowerRemainingMs = null,
                            pendingPowerDeadlineElapsedRealtimeMs = null,
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(pendingPowerRemainingMs = remainingMs) }
            }
        }
    }

    private fun localizedContext(): Context = LocaleHelper.localized(context)
}
