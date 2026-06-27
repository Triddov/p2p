package com.p2p.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.p2p.data.local.entities.VerifiedContact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onContactClick: (chatId: String, peerUserId: String) -> Unit,
    onScanQR: () -> Unit,
    onShowMyQR: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel()
) {
    val contacts by viewModel.contacts.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var showSearchDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is ContactsUiState.ContactVerified -> viewModel.resetState()
            is ContactsUiState.ChatCreated -> {
                onContactClick(state.chatId, state.peerUserId)
                viewModel.resetState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Default.Search, "Search user")
                    }
                }
            )
        },
        floatingActionButton = {
            Column {
                FloatingActionButton(
                    onClick = onScanQR,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, "Scan QR")
                }

                FloatingActionButton(onClick = onShowMyQR) {
                    Icon(Icons.Default.QrCode, "My QR")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when {
                contacts.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No contacts yet",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scan a QR code to add contact",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn {
                        items(contacts) { contact ->
                            ContactListItem(
                                contact = contact,
                                onClick = { viewModel.openChat(contact.userId) }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }

    // Search dialog
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = {
                showSearchDialog = false
                viewModel.resetState()
            },
            title = { Text("Search User") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Username") },
                        placeholder = { Text("alice_crypto") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    when (val state = uiState) {
                        is ContactsUiState.Loading -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        }

                        is ContactsUiState.UserFound -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("User found: ${state.contact.username}")
                            Text(
                                text = "Note: Exchange QR codes to verify identity",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        is ContactsUiState.Error -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        else -> {}
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (searchQuery.text.isNotBlank()) {
                            viewModel.searchUser(searchQuery.text)
                        }
                    },
                    enabled = searchQuery.text.isNotBlank()
                ) {
                    Text("Search")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSearchDialog = false
                    viewModel.resetState()
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ContactListItem(
    contact: VerifiedContact,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = contact.username ?: "Unknown",
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (contact.verificationMethod == "qr_scan") {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "Verified",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = if (contact.verificationMethod == "qr_scan")
                        "Verified via QR"
                    else
                        "Not verified",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}