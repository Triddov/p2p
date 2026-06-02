package com.p2p.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.data.local.entities.LocalProfile
import com.p2p.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Initial)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val localProfile: StateFlow<LocalProfile?> = authRepository.getLocalProfileFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun requestCode(email: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            authRepository.requestVerificationCode(email)
                .onSuccess {
                    _uiState.value = AuthUiState.CodeSent(email)
                }
                .onFailure { error ->
                    _uiState.value = AuthUiState.Error(error.message ?: "Unknown error")
                }
        }
    }

    fun verifyCode(email: String, code: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            authRepository.verifyCodeAndRegister(email, code)
                .onSuccess { profile ->
                    _uiState.value = AuthUiState.Authenticated(profile)
                }
                .onFailure { error ->
                    _uiState.value = AuthUiState.Error(error.message ?: "Invalid code")
                }
        }
    }

    fun setUsername(username: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            authRepository.setUsername(username)
                .onSuccess {
                    _uiState.value = AuthUiState.UsernameSet
                }
                .onFailure { error ->
                    _uiState.value = AuthUiState.Error(error.message ?: "Failed to set username")
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.value = AuthUiState.Initial
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState.Initial
    }
}

sealed class AuthUiState {
    object Initial : AuthUiState()
    object Loading : AuthUiState()
    data class CodeSent(val email: String) : AuthUiState()
    data class Authenticated(val profile: LocalProfile) : AuthUiState()
    object UsernameSet : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}