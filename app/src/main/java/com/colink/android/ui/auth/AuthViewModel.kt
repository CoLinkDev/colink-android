package com.colink.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.colink.android.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(identifier: String, password: String) {
        submit { authRepository.login(identifier, password) }
    }

    fun register(email: String, username: String, password: String) {
        submit { authRepository.register(email, username, password) }
    }

    private fun submit(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _uiState.value = AuthUiState(loading = true)
            val result = block()
            _uiState.value = AuthUiState(error = result.exceptionOrNull()?.message)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
