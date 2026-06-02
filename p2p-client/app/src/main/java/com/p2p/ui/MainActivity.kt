package com.p2p.ui

import android.os.Bundle
import androidx.navigation.compose.composable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.p2p.data.repository.AuthRepository
import com.p2p.ui.auth.AuthScreen
import com.p2p.ui.chat.ChatListScreen
import com.p2p.ui.chat.ChatScreen
import com.p2p.ui.contacts.ContactsScreen
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
            MaterialTheme {
                Surface {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "auth"
                    ) {
                        composable("auth") {
                            AuthScreen(
                                onAuthenticated = {
                                    navController.navigate("chat_list") {
                                        popUpTo("auth") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }

                            composable("chat_list") {
                            ChatListScreen(
                                onChatClick = { chatId, peerUserId ->
                                    navController.navigate("chat/$chatId/$peerUserId")
                                },
                                onContactsClick = {
                                    navController.navigate("contacts")
                                },
                                onProfileClick = {
                                    // TODO: add profile screen
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

                        composable("contacts") {
                            ContactsScreen(
                                onBack = { navController.popBackStack() },
                                onContactClick = { chatId, peerUserId ->
                                    navController.navigate("chat/$chatId/$peerUserId")
                                },
                                onScanQR = {
                                    navController.navigate("scan_qr")
                                },
                                onShowMyQR = {
                                    navController.navigate("my_qr")
                                }
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