package com.colink.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.CloudStatus
import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.lan.LanPairingCoordinator
import com.colink.android.data.local.datastore.SettingsDataStore
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
    private val connectionManager: ConnectionManager,
    private val pairingCoordinator: LanPairingCoordinator,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val _bootstrapping = MutableStateFlow(true)
    val bootstrapping: StateFlow<Boolean> = _bootstrapping.asStateFlow()

    val authenticated: StateFlow<Boolean> =
        authRepository.session
            .map { it != null }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val cloudStatus: StateFlow<CloudStatus> =
        connectionManager.cloudState
            .map { it.status }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudStatus.Disconnected)

    val pairingRequest: StateFlow<LanPairingRequest?> =
        pairingCoordinator.pendingRequest

    val notificationsEnabled: StateFlow<Boolean> =
        settingsDataStore.settings
            .map { it.notifications }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.bootstrap()
            _bootstrapping.value = false
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
}
