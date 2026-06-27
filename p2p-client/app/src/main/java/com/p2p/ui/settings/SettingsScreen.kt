package com.p2p.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Appearance") },
                supportingContent = { Text("Dark theme") },
                leadingContent = { Icon(Icons.Default.DarkMode, contentDescription = null) }
            )
            Divider()
            ListItem(
                headlineContent = { Text("About") },
                supportingContent = { Text("Point & Point · v1.0") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
            )
            Divider()
        }
    }
}
