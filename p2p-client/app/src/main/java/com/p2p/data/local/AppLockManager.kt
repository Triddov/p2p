package com.p2p.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped состояние блокировки приложения
 * При старте процесса считается заблокированным; разблокируется после ввода
 * PIN/биометрии и снова блокируется при сворачивании (MainActivity.onStop)
 */
@Singleton
class AppLockManager @Inject constructor() {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun unlock() { _locked.value = false }
    fun lock() { _locked.value = true }
}
