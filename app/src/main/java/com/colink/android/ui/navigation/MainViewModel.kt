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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

data class MainUiState(
    val bootstrapping: Boolean = true,
    val authenticated: Boolean = false,
    val cloudStatus: CloudStatus = CloudStatus.Disconnected,
    val pairingRequest: LanPairingRequest? = null,
    val notificationsEnabled: Boolean = true,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectionManager: ConnectionManager,
    private val pairingCoordinator: LanPairingCoordinator,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {
    private val bootstrapping = MutableStateFlow(true)

    val uiState: StateFlow<MainUiState> =
        combine(
            bootstrapping,
            authRepository.session,
            connectionManager.cloudState,
            pairingCoordinator.pendingRequest,
            settingsDataStore.settings,
        ) { loading, session, cloud, pairingRequest, settings ->
            MainUiState(
                bootstrapping = loading,
                authenticated = session != null,
                cloudStatus = cloud.status,
                pairingRequest = pairingRequest,
                notificationsEnabled = settings.notifications,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            authRepository.bootstrap()
            bootstrapping.value = false
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
