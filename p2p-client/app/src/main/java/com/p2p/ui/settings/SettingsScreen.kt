package com.p2p.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val appLockEnabled by viewModel.appLockEnabled.collectAsState()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val context = LocalContext.current

    var showPinSetup by remember { mutableStateOf(false) }

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
                supportingContent = { Text("Show notifications for new messages") },
                leadingContent = { Icon(Icons.Default.NotificationsNone, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                            checkedTrackColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }
            )

            Divider()

            SectionHeader("Privacy")
            ListItem(
                headlineContent = { Text("Show me in search") },
                supportingContent = { Text("Allow others to find you by username") },
                leadingContent = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = discoverable,
                        onCheckedChange = { viewModel.setDiscoverable(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                            checkedTrackColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }
            )

            Divider()

            SectionHeader("Security")
            ListItem(
                headlineContent = { Text("App lock") },
                supportingContent = { Text("Require PIN or biometrics to open the app") },
                leadingContent = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = appLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) showPinSetup = true else viewModel.disableAppLock()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                            checkedTrackColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
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

    if (showPinSetup) {
        PinSetupDialog(
            onDismiss = { showPinSetup = false },
            onConfirm = { pin ->
                viewModel.enableAppLock(pin)
                showPinSetup = false
            }
        )
    }
}

@Composable
private fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length == 4 && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column {
                Text(
                    "Choose a 4-digit PIN to lock the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                PinField(value = pin, onChange = { pin = it }, label = "PIN")
                Spacer(Modifier.height(8.dp))
                PinField(value = confirm, onChange = { confirm = it }, label = "Confirm PIN")
                if (confirm.length == 4 && pin != confirm) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "PINs don't match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text("Enable") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) onChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.tertiary,
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
        RadioButton(
            selected = mode == selected,
            onClick = { onSelect(mode) },
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.tertiary)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
