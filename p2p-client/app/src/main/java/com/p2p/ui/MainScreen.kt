package com.p2p.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.p2p.ui.chat.ChatListScreen
import com.p2p.ui.contacts.ContactsScreen
import com.p2p.ui.profile.ProfileScreen
import com.p2p.ui.settings.SettingsScreen

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    object Chats : Tab("chats", "Chats", Icons.Default.Forum)
    object Contacts : Tab("contacts", "Contacts", Icons.Default.Contacts)
    object Profile : Tab("profile", "Profile", Icons.Default.Person)
    object Settings : Tab("settings", "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Chats, Tab.Contacts, Tab.Profile, Tab.Settings)

// Material "fade through": уходящий контент гаснет, приходящий проявляется
// с лёгким увеличением. Рекомендуемый переход для вкладок (между ними нет
// пространственной связи, поэтому не слайд).
private fun fadeThroughEnter(): EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 90)) +
        scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 220, delayMillis = 90))

private fun fadeThroughExit(): ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = 90))

/**
 * Корневой экран с нижним баром из 4 вкладок (Chats / Contacts / Profile / Settings).
 * Detail-экраны (чат, QR) лежат во внешнем графе и открываются через колбэки —
 * там бар уже не показывается.
 */
@Composable
fun MainScreen(
    onOpenChat: (chatId: String, peerUserId: String) -> Unit,
    onScanQR: () -> Unit,
    onShowMyQR: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val tabNav = rememberNavController()
    val backStackEntry by tabNav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                tabNav.navigate(tab.route) {
                                    // Сохраняем состояние каждой вкладки и не плодим копии.
                                    popUpTo(tabNav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = tabNav,
            startDestination = Tab.Chats.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeThroughEnter() },
            exitTransition = { fadeThroughExit() },
            popEnterTransition = { fadeThroughEnter() },
            popExitTransition = { fadeThroughExit() },
        ) {
            composable(Tab.Chats.route) {
                ChatListScreen(
                    onChatClick = onOpenChat,
                    onNewChat = {
                        tabNav.navigate(Tab.Contacts.route) {
                            popUpTo(tabNav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Tab.Contacts.route) {
                ContactsScreen(
                    onContactClick = onOpenChat,
                    onScanQR = onScanQR,
                    onShowMyQR = onShowMyQR
                )
            }

            composable(Tab.Profile.route) {
                ProfileScreen(
                    onShowMyQR = onShowMyQR,
                    onLoggedOut = onLoggedOut
                )
            }

            composable(Tab.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
