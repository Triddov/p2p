package com.p2p.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.p2p.data.repository.AuthRepository
import com.p2p.ui.components.FullScreenLoader
import kotlinx.coroutines.delay

// если старт мгновенный, лоадер не мелькнёт на десятки мс
private const val MIN_SHOW_MS = 1000L

/**
 * Стартовый экран: показывает лоадер, пока резолвится состояние входа
 */
@Composable
fun StartupScreen(
    authRepository: AuthRepository,
    onResolved: (loggedIn: Boolean) -> Unit
) {
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        val profile = authRepository.getLocalProfile()
        val elapsed = System.currentTimeMillis() - start
        if (elapsed < MIN_SHOW_MS) delay(MIN_SHOW_MS - elapsed)
        onResolved(profile?.username != null)
    }
    FullScreenLoader()
}
