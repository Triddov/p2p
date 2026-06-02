package com.p2p.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.data.local.entities.Chat
import com.p2p.data.local.entities.Message
import com.p2p.data.repository.AuthRepository
import com.p2p.data.repository.ChatRepository
import com.p2p.data.repository.WebRTCRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val webRTCRepository: WebRTCRepository,
    private val authRepository: AuthRepository,
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

    init {
        // пометка чата как прочитанного
        viewModelScope.launch {
            chatRepository.markChatAsRead(chatId)
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
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return

        viewModelScope.launch {
            _uiState.value = ChatUiState.Sending

            chatRepository.sendMessage(chatId, content, peerUserId)
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
        chatRepository.handleP2PMessage(peerUserId, messageJson)
            .onSuccess {
                // Сообщение обработано
            }
            .onFailure { error ->
                println("Failed to handle P2P message: ${error.message}")
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