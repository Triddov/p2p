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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.p2p.data.local.entities.VerifiedContact
import com.p2p.data.repository.UserSearchResult

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
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchState by viewModel.searchState.collectAsState()

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
        topBar = { TopAppBar(title = { Text("Contacts") }) },
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                placeholder = { Text("Search by username") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Пока запрос пустой/короткий — показываем контакты, иначе — результаты поиска.
            if (searchQuery.isBlank()) {
                ContactsList(contacts = contacts, onOpenChat = { viewModel.startChatWith(it) }, viewModel = viewModel)
            } else {
                SearchResults(state = searchState, onPick = { viewModel.startChatWith(it) })
            }
        }
    }
}

@Composable
private fun ContactsList(
    contacts: List<VerifiedContact>,
    onOpenChat: (UserSearchResult) -> Unit,
    viewModel: ContactsViewModel
) {
    if (contacts.isEmpty()) {
        CenteredHint(
            title = "No contacts yet",
            subtitle = "Find someone by username or scan a QR code"
        )
        return
    }
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

@Composable
private fun SearchResults(
    state: SearchUiState,
    onPick: (UserSearchResult) -> Unit
) {
    when (state) {
        is SearchUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        is SearchUiState.Empty -> CenteredHint(title = "No users found", subtitle = null)
        is SearchUiState.Error -> CenteredHint(title = state.message, subtitle = null)
        is SearchUiState.Results -> LazyColumn {
            items(state.users) { user ->
                SearchResultItem(user = user, onClick = { onPick(user) })
                Divider()
            }
        }
        is SearchUiState.Idle -> {} // запрос ещё короткий
    }
}

@Composable
private fun SearchResultItem(user: UserSearchResult, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(user.username ?: "Unknown", style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Text(
                text = if (user.isContact) "Already in contacts" else "Tap to start chat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                imageVector = if (user.isContact) Icons.Default.Check else Icons.Default.PersonAdd,
                contentDescription = null,
                tint = if (user.isContact)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun CenteredHint(title: String, subtitle: String?) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ContactListItem(
    contact: VerifiedContact,
    onClick: () -> Unit
) {
    val verified = contact.verificationMethod.isVerified

    ListItem(
        headlineContent = {
            Text(
                text = contact.username ?: "Unknown",
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (verified) Icons.Default.Verified else Icons.Default.GppMaybe,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (verified)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (verified) "Verified" else "Not verified",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
