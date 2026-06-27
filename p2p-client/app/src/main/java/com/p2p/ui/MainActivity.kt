package com.p2p.ui

import android.os.Bundle
import androidx.navigation.compose.composable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            P2PMessengerTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "auth"
                    ) {
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