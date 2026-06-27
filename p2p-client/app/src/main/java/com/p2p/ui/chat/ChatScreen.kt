package com.p2p.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.p2p.data.local.entities.Message
import com.p2p.data.local.entities.MessageStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val chat by viewModel.chat.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isP2PConnected by viewModel.isP2PConnected.collectAsState()
    val isPeerVerified by viewModel.isPeerVerified.collectAsState()
    val keyChanged by viewModel.keyChanged.collectAsState()
    val peerPresence by viewModel.peerPresence.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChatUiState.MessageSent) {
            viewModel.resetState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chat?.peerUsername ?: "Chat")
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isPeerVerified)
                                    Icons.Default.Verified
                                else
                                    Icons.Default.GppMaybe,
                                contentDescription = if (isPeerVerified) "Verified" else "Not verified",
                                modifier = Modifier.size(16.dp),
                                tint = if (isPeerVerified)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.tertiary
                            )
                        }
                        val presenceLabel = when {
                            peerPresence?.online == true -> "online"
                            peerPresence?.lastSeen != null -> formatLastSeen(peerPresence!!.lastSeen!!)
                            else -> null
                        }
                        Text(
                            text = if (presenceLabel != null)
                                "$presenceLabel · ${if (isP2PConnected) "P2P" else "via server"}"
                            else
                                if (isP2PConnected) "P2P Connected" else "Via Server",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isP2PConnected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Menu")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (keyChanged) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Security key changed",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "The contact may have reinstalled the app — or it could be an attack. Re-verify by scanning their QR in Contacts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        TextButton(onClick = { viewModel.dismissKeyChangeWarning() }) { Text("Dismiss") }
                    }
                }
            }

            // Messages list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(message)
                }
            }

            // Input field
            Surface(
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val text = messageText.text
                            if (text.isNotBlank()) {
                                viewModel.sendMessage(text)
                                // Очищаем поле сразу: сообщение уже сохранено локально,
                                // его статус (отправка/доставлено/ошибка) виден на «пузыре»
                                messageText = TextFieldValue("")
                            }
                        },
                        enabled = messageText.text.isNotBlank() && uiState !is ChatUiState.Sending
                    ) {
                        if (uiState is ChatUiState.Sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Send, "Send")
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete chat") },
            text = { Text("Delete this conversation? Messages will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteChat()
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatLastSeen(iso: String): String {
    return try {
        val instant = java.time.Instant.parse(iso)
        val mins = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes()
        when {
            mins < 1L -> "last seen just now"
            mins < 60L -> "last seen ${mins}m ago"
            mins < 1440L -> "last seen ${mins / 60}h ago"
            else -> "last seen ${mins / 1440}d ago"
        }
    } catch (e: Exception) {
        "last seen recently"
    }
}

@Composable
fun MessageBubble(message: Message) {
    val isMe = message.senderId == "me"
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isMe)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.content,
                    color = if (isMe)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = dateFormat.format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMe)
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )

                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = when (message.status) {
                                MessageStatus.SENDING -> Icons.Default.Schedule
                                MessageStatus.SENT -> Icons.Default.Done
                                MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                MessageStatus.READ -> Icons.Default.DoneAll
                                MessageStatus.FAILED -> Icons.Default.Error
                            },
                            contentDescription = message.status.name,
                            modifier = Modifier.size(16.dp),
                            // Прочитано — выделяем акцентом; остальные статусы приглушены.
                            tint = if (message.status == MessageStatus.READ)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}