package com.colink.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.domain.model.AppSettings
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.network.ConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val authenticated: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(serverUrl = ""),
        )

    fun login(serverUrl: String, identifier: String, password: String) {
        submit(serverUrl) { authRepository.login(identifier, password) }
    }

    fun register(serverUrl: String, email: String, username: String, password: String) {
        submit(serverUrl) { authRepository.register(email, username, password) }
    }

    private fun submit(serverUrl: String, block: suspend () -> Result<Unit>) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AuthUiState(loading = true)
            settingsDataStore.saveServerUrl(serverUrl)
            val result = block()
            _uiState.value = AuthUiState(
                error = result.exceptionOrNull()?.message,
                authenticated = result.isSuccess,
            )
            if (result.isSuccess) {
                connectionManager.startCloud()
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun consumeAuthenticated() {
        _uiState.update { it.copy(authenticated = false) }
    }
}
