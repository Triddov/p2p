package com.p2p.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.p2p.data.local.entities.Chat
import com.p2p.data.repository.AuthRepository
import com.p2p.data.repository.ChatRepository
import com.p2p.data.repository.ContactRepository
import com.p2p.data.repository.WebRTCRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val contactRepository: ContactRepository,
    private val webRTCRepository: WebRTCRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = chatRepository.getAllChats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<ChatListUiState>(ChatListUiState.Idle)
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    init {
        // подключение к signaling серверу
        connectToSignaling()

        // первичная загрузка pending-сообщений (с индикатором)
        fetchPendingMessages()

        // периодический фоновый опрос, пока экран жив (без индикатора).
        // Нужен для гарантированной доставки офлайн-сообщений, пока нет push (FCM):
        // выборка идемпотентна, повторы безопасны (без потерь и дублей).
        startPendingMessagesPolling()

        viewModelScope.launch {
            authRepository.replenishOneTimePrekeysIfNeeded()
            authRepository.rotateSignedPrekeyIfNeeded()
        }
    }

    private fun startPendingMessagesPolling() {
        viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                chatRepository.fetchPendingMessages()
            }
        }
    }

    private fun connectToSignaling() {
        viewModelScope.launch {
            val profile = authRepository.getLocalProfile() ?: return@launch

            webRTCRepository.connectSignaling(profile.userId, profile.accessToken)
        }
    }

    fun fetchPendingMessages() {
        viewModelScope.launch {
            _uiState.value = ChatListUiState.Loading

            chatRepository.fetchPendingMessages()
                .onSuccess { messages ->
                    _uiState.value = if (messages.isEmpty()) {
                        ChatListUiState.Idle
                    } else {
                        ChatListUiState.NewMessages(messages.size)
                    }
                }
                .onFailure { error ->
                    _uiState.value = ChatListUiState.Error(error.message ?: "Failed to fetch messages")
                }
        }
    }

    fun createChatWithContact(peerUserId: String) {
        viewModelScope.launch {
            _uiState.value = ChatListUiState.Loading

            try {
                val existingChat = chatRepository.getChatByPeer(peerUserId)

                val chat = existingChat ?: chatRepository.createChat(peerUserId)

                _uiState.value = ChatListUiState.ChatCreated(chat.id, peerUserId)
            } catch (e: Exception) {
                _uiState.value = ChatListUiState.Error(e.message ?: "Failed to create chat")
            }
        }
    }

    fun resetState() {
        _uiState.value = ChatListUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        webRTCRepository.disconnectSignaling()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 15_000L
    }
}

sealed class ChatListUiState {
    object Idle : ChatListUiState()
    object Loading : ChatListUiState()
    data class NewMessages(val count: Int) : ChatListUiState()
    data class ChatCreated(val chatId: String, val peerUserId: String) : ChatListUiState()
    data class Error(val message: String) : ChatListUiState()
}