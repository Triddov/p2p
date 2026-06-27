package com.p2p.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.data.local.entities.Chat
import com.p2p.data.local.entities.Message
import com.p2p.data.repository.AuthRepository
import com.p2p.data.repository.ChatRepository
import com.p2p.data.repository.ContactRepository
import com.p2p.data.repository.MessagingService
import com.p2p.data.repository.PeerPresence
import com.p2p.data.repository.WebRTCRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val messagingService: MessagingService,
    private val webRTCRepository: WebRTCRepository,
    private val authRepository: AuthRepository,
    private val contactRepository: ContactRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val chatId: String = savedStateHandle.get<String>("chatId")
        ?: throw IllegalArgumentException("chatId required")

    private val peerUserId: String = savedStateHandle.get<String>("peerUserId")
        ?: throw IllegalArgumentException("peerUserId required")

    val chat: StateFlow<Chat?> = flow {
        emit(chatRepository.getChat(chatId))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<Message>> = chatRepository.getMessagesForChat(chatId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val isP2PConnected: StateFlow<Boolean> = webRTCRepository.connectionStateFlow
        .filter { it.first == peerUserId }
        .map { it.second }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isPeerVerified: StateFlow<Boolean> = contactRepository.getAllContacts()
        .map { contacts -> contacts.firstOrNull { it.userId == peerUserId } }
        .map { it?.verificationMethod?.isVerified == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Предупреждение о смене identity-ключа собеседника
    private val _keyChanged = MutableStateFlow(false)
    val keyChanged: StateFlow<Boolean> = _keyChanged.asStateFlow()

    // Presence собеседника (снимок при открытии чата)
    private val _peerPresence = MutableStateFlow<PeerPresence?>(null)
    val peerPresence: StateFlow<PeerPresence?> = _peerPresence.asStateFlow()

    init {
        // пометка чата как прочитанного
        viewModelScope.launch {
            messagingService.markChatAsRead(chatId)
        }

        // прослушивание входящих P2P сообщений
        viewModelScope.launch {
            webRTCRepository.messageFlow
                .filter { it.first == peerUserId }
                .collect { (_, messageJson) ->
                    handleIncomingP2PMessage(messageJson)
                }
        }

        // установление P2P соединения
        initiateP2PConnection()

        // детект смены ключа собеседника (возможный MITM / переустановка)
        viewModelScope.launch {
            if (contactRepository.detectKeyChange(peerUserId)) {
                _keyChanged.value = true
            }
        }

        // presence собеседника (снимок при открытии)
        viewModelScope.launch {
            _peerPresence.value = contactRepository.getPresence(peerUserId)
        }
    }

    fun dismissKeyChangeWarning() {
        _keyChanged.value = false
    }

    /** Удаляет текущий чат вместе с сообщениями (контакт остаётся). */
    fun deleteChat() {
        viewModelScope.launch {
            chatRepository.deleteChat(chatId)
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        viewModelScope.launch {
            _uiState.value = ChatUiState.Sending

            messagingService.sendMessage(chatId, content, peerUserId)
                .onSuccess {
                    _uiState.value = ChatUiState.MessageSent
                }
                .onFailure { error ->
                    _uiState.value = ChatUiState.Error(error.message ?: "Failed to send")
                }
        }
    }

    private fun initiateP2PConnection() {
        viewModelScope.launch {
            val myProfile = authRepository.getLocalProfile() ?: return@launch

            if (!webRTCRepository.isConnected(peerUserId)) {
                webRTCRepository.initiateConnection(myProfile.userId, peerUserId)
            }
        }
    }

    private suspend fun handleIncomingP2PMessage(messageJson: String) {
        messagingService.handleP2PMessage(peerUserId, messageJson)
            .onSuccess {
                // Сообщение обработано
            }
            .onFailure { error ->
                android.util.Log.e("ChatViewModel", "Failed to handle P2P message: ${error.message}")
            }
    }

    fun resetState() {
        _uiState.value = ChatUiState.Idle
    }
}

sealed class ChatUiState {
    object Idle : ChatUiState()
    object Sending : ChatUiState()
    object MessageSent : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}