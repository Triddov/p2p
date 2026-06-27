package com.p2p.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.p2p.data.local.ThemeMode

private const val SOURCE_URL = "https://github.com/Triddov"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val discoverable by viewModel.discoverable.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("Appearance")
            ThemeOptionRow("System default", ThemeMode.SYSTEM, themeMode) { viewModel.setThemeMode(it) }
            ThemeOptionRow("Light", ThemeMode.LIGHT, themeMode) { viewModel.setThemeMode(it) }
            ThemeOptionRow("Dark", ThemeMode.DARK, themeMode) { viewModel.setThemeMode(it) }

            Divider()

            SectionHeader("Notifications")
            ListItem(
                headlineContent = { Text("Push notifications") },
                supportingContent = { Text("Coming soon") },
                leadingContent = { Icon(Icons.Default.NotificationsNone, contentDescription = null) },
                trailingContent = {
                    Switch(checked = false, enabled = false, onCheckedChange = {})
                }
            )

            Divider()

            SectionHeader("Privacy")
            ListItem(
                headlineContent = { Text("Show me in search") },
                supportingContent = { Text("Allow others to find you by username") },
                leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingContent = {
                    Switch(checked = discoverable, onCheckedChange = { viewModel.setDiscoverable(it) })
                }
            )

            Divider()

            SectionHeader("About")
            ListItem(
                headlineContent = { Text("Point & Point") },
                supportingContent = {
                    Text("Secure peer-to-peer messenger with end-to-end encryption · v1.0")
                },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
            )
            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text("github.com/Triddov") },
                leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                modifier = Modifier.clickable { openUrl(context, SOURCE_URL) }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun ThemeOptionRow(
    label: String,
    mode: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = mode == selected, onClick = { onSelect(mode) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = mode == selected, onClick = { onSelect(mode) })
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
