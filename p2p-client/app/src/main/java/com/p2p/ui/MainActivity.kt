package com.p2p.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.compose.composable
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import com.p2p.data.local.AppLockManager
import com.p2p.data.local.SettingsRepository
import com.p2p.data.local.ThemeMode
import com.p2p.ui.lock.LockScreen
import com.p2p.ui.theme.P2PMessengerTheme
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.p2p.data.repository.AuthRepository
import com.p2p.ui.auth.AuthScreen
import com.p2p.ui.chat.ChatScreen
import com.p2p.ui.qr.MyQRScreen
import com.p2p.ui.qr.QRScannerScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var appLockManager: AppLockManager

    override fun onStop() {
        super.onStop()
        appLockManager.lock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Разрешение на уведомления (Android 13+) - для пушей
            val notifPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val appLockEnabled by settingsRepository.appLockEnabled.collectAsState(initial = false)
            val locked by appLockManager.locked.collectAsState()

            P2PMessengerTheme(darkTheme = darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (appLockEnabled && locked) {
                        LockScreen(onUnlocked = { appLockManager.unlock() })
                        return@Surface
                    }
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "startup"
                    ) {
                        composable("startup") {
                            StartupScreen(
                                authRepository = authRepository,
                                onResolved = { loggedIn ->
                                    val dest = if (loggedIn) "main" else "auth"
                                    navController.navigate(dest) {
                                        popUpTo("startup") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable("auth") {
                            AuthScreen(
                                onAuthenticated = {
                                    navController.navigate("main") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable("main") {
                            MainScreen(
                                onOpenChat = { chatId, peerUserId ->
                                    navController.navigate("chat/$chatId/$peerUserId")
                                },
                                onScanQR = {
                                    navController.navigate("scan_qr")
                                },
                                onShowMyQR = {
                                    navController.navigate("my_qr")
                                },
                                onLoggedOut = {
                                    navController.navigate("auth") {
                                        popUpTo("main") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                        composable(
                            route = "chat/{chatId}/{peerUserId}",
                            arguments = listOf(
                                navArgument("chatId") { type = NavType.StringType },
                                navArgument("peerUserId") { type = NavType.StringType }
                            )
                        ) {
                            ChatScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("my_qr") {
                            MyQRScreen(
                                onBack = { navController.popBackStack() },
                                authRepository = authRepository
                            )
                        }

                        composable("scan_qr") {
                            QRScannerScreen(
                                onBack = { navController.popBackStack() },
                                onQRScanned = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}