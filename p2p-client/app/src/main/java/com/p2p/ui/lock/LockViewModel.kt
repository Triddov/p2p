package com.p2p.ui.lock

import androidx.lifecycle.ViewModel
import com.p2p.data.local.SettingsRepository
import com.p2p.domain.crypto.PinHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class LockViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    suspend fun verifyPin(pin: String): Boolean {
        val stored = settingsRepository.pinHash.first() ?: return false
        return PinHasher.verify(pin, stored)
    }
}
