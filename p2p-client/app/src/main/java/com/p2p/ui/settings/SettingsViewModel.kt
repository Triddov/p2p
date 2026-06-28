package com.p2p.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.data.local.SettingsRepository
import com.p2p.data.local.ThemeMode
import com.p2p.data.remote.ApiService
import com.p2p.data.remote.dto.SetDiscoverableRequest
import com.p2p.domain.crypto.PinHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val apiService: ApiService
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val discoverable: StateFlow<Boolean> = settingsRepository.discoverable
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val appLockEnabled: StateFlow<Boolean> = settingsRepository.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val notificationsEnabled: StateFlow<Boolean> = settingsRepository.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDiscoverable(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDiscoverable(value)
            val ok = runCatching { apiService.setDiscoverable(SetDiscoverableRequest(value)) }.isSuccess
            if (!ok) settingsRepository.setDiscoverable(!value)
        }
    }

    fun setNotificationsEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(value) }
    }

    fun enableAppLock(pin: String) {
        viewModelScope.launch { settingsRepository.setAppLock(true, PinHasher.hash(pin)) }
    }

    fun disableAppLock() {
        viewModelScope.launch { settingsRepository.setAppLock(false, null) }
    }
}
