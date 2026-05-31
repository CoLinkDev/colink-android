package com.colink.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.model.CloudConnectionState
import com.colink.android.domain.model.LanPairingRequest
import com.colink.android.domain.repository.AuthRepository
import com.colink.android.network.ConnectionManager
import com.colink.android.network.lan.LanPairingCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

data class MainUiState(
    val bootstrapping: Boolean = true,
    val authenticated: Boolean = false,
    val cloud: CloudConnectionState = CloudConnectionState(),
    val pairingRequest: LanPairingRequest? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectionManager: ConnectionManager,
    private val pairingCoordinator: LanPairingCoordinator,
) : ViewModel() {
    private val bootstrapping = MutableStateFlow(true)

    val uiState: StateFlow<MainUiState> =
        combine(
            bootstrapping,
            authRepository.session,
            connectionManager.cloudState,
            pairingCoordinator.pendingRequest,
        ) { loading, session, cloud, pairingRequest ->
            MainUiState(
                bootstrapping = loading,
                authenticated = session != null,
                cloud = cloud,
                pairingRequest = pairingRequest,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            authRepository.bootstrap()
            bootstrapping.value = false
        }
    }

    fun logout() {
        viewModelScope.launch {
            connectionManager.stop()
            authRepository.logout()
        }
    }

    fun respondPairing(requestId: String, accepted: Boolean) {
        pairingCoordinator.respond(requestId, accepted)
    }
}
