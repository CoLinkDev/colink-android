package com.colink.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.CloudStatus
import com.colink.android.domain.model.AppUpdate
import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.domain.repository.UpdateRepository
import com.colink.android.data.local.datastore.SettingsDataStore
import com.colink.android.network.ConnectionManager
import com.colink.android.network.lan.LanPairingCoordinator
import com.colink.android.util.CoLinkLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsDataStore: SettingsDataStore,
    private val connectionManager: ConnectionManager,
    private val pairingCoordinator: LanPairingCoordinator,
    private val updateRepository: UpdateRepository,
) : ViewModel() {
    private val _bootstrapping = MutableStateFlow(true)
    val bootstrapping: StateFlow<Boolean> = _bootstrapping.asStateFlow()
    private val _availableUpdate = MutableStateFlow<AppUpdate?>(null)
    val availableUpdate: StateFlow<AppUpdate?> = _availableUpdate.asStateFlow()

    val authenticated: StateFlow<Boolean> =
        authRepository.session
            .map { it != null }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val accountName: StateFlow<String> =
        authRepository.session
            .map { session -> session?.username?.ifBlank { session.email.orEmpty() }.orEmpty() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val accountEmail: StateFlow<String> =
        authRepository.session
            .map { it?.email.orEmpty() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val serverUrl: StateFlow<String> =
        settingsDataStore.settings
            .map { it.serverUrl }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val cloudStatus: StateFlow<CloudStatus> =
        connectionManager.cloudState
            .map { it.status }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudStatus.Disconnected)

    val pairingRequest: StateFlow<LanPairingRequest?> =
        pairingCoordinator.pendingRequest

    init {
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.bootstrap()
            _bootstrapping.value = false
            launch { authRepository.refreshProfile() }
            checkForUpdates()
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.logout()
            connectionManager.stopCloud()
        }
    }

    fun respondPairing(requestId: String, accepted: Boolean) {
        pairingCoordinator.respond(requestId, accepted)
    }

    fun clearPairing(requestId: String) {
        pairingCoordinator.clear(requestId)
    }

    fun cancelPairing(request: LanPairingRequest) {
        pairingCoordinator.cancel(request.requestId)
        connectionManager.cancelLanPairing(request.deviceId)
    }

    fun dismissUpdate() {
        _availableUpdate.value = null
    }

    private suspend fun checkForUpdates() {
        updateRepository.checkForUpdate()
            .onSuccess { update -> _availableUpdate.value = update }
            .onFailure { error -> CoLinkLog.w("Update", "update check failed", error) }
    }
}
