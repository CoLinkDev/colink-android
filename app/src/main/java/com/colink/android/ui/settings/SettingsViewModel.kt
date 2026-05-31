package com.colink.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.repository.DeviceRepository
import com.colink.android.network.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val saving: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(serverUrl = ""),
        )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun save(settings: AppSettings) {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(saving = true)
            settingsDataStore.saveSettings(settings)
            deviceRepository.localDeviceIdentity()
                ?.takeIf { settings.deviceName.trim().isNotEmpty() && it.name != settings.deviceName.trim() }
                ?.let { deviceRepository.updateDeviceName(it.deviceId, settings.deviceName) }
            connectionManager.applySettings(settings)
            _uiState.value = SettingsUiState(message = "Settings saved")
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
