package com.colink.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.repository.UpdateRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.util.LocaleHelper
import com.colink.android.util.normalizeServerUrl
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

data class SettingsUiState(
    val saving: Boolean = false,
    val checkingUpdate: Boolean = false,
    val availableUpdate: AppUpdate? = null,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
    private val updateRepository: UpdateRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(serverUrl = ""),
        )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun saveServerUrl(url: String) {
        val normalizedUrl = normalizeServerUrl(url)
        if (normalizedUrl == null) {
            val localizedContext = LocaleHelper.localized(context)
            _uiState.update {
                it.copy(message = localizedContext.getString(R.string.err_server_url_invalid))
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsDataStore.currentSettings()
            if (current.serverUrl != normalizedUrl) {
                val updated = current.copy(serverUrl = normalizedUrl)
                settingsDataStore.saveSettings(updated)
                connectionManager.applySettings(updated)
            }
            val localizedContext = LocaleHelper.localized(context)
            _uiState.update {
                it.copy(message = localizedContext.getString(R.string.settings_saved))
            }
        }
    }

    fun updateLanguage(lang: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsDataStore.currentSettings()
            if (current.language != lang) {
                val updated = current.copy(language = lang)
                settingsDataStore.saveSettings(updated)
                connectionManager.applySettings(updated)
            }
        }
    }

    fun updateClipboardSync(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsDataStore.currentSettings()
            if (current.enableClipboardSync != enabled) {
                val updated = current.copy(enableClipboardSync = enabled)
                settingsDataStore.saveSettings(updated)
                connectionManager.applySettings(updated)
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun checkForUpdate() {
        if (_uiState.value.checkingUpdate) {
            return
        }
        val localizedContext = LocaleHelper.localized(context)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(checkingUpdate = true, message = null) }
            updateRepository.checkForUpdate()
                .onSuccess { update ->
                    _uiState.update {
                        it.copy(
                            checkingUpdate = false,
                            availableUpdate = update,
                            message = if (update == null) localizedContext.getString(R.string.update_up_to_date) else null,
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            checkingUpdate = false,
                            message = localizedContext.getString(R.string.update_check_failed),
                        )
                    }
                }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(availableUpdate = null) }
    }
}
