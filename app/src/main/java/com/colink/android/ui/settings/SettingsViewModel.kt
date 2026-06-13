package com.colink.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.R
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.AppSettings
import com.colink.android.network.ConnectionManager
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
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(serverUrl = ""),
        )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateServerUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = settingsDataStore.currentSettings()
            if (current.serverUrl != url) {
                val updated = current.copy(serverUrl = url)
                settingsDataStore.saveSettings(updated)
                connectionManager.applySettings(updated)
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

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
