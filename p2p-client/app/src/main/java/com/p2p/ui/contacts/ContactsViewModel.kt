package com.p2p.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.p2p.data.local.entities.VerificationMethod
import com.p2p.data.local.entities.VerifiedContact
import com.p2p.data.repository.ChatRepository
import com.p2p.data.repository.ContactRepository
import com.p2p.data.repository.UserSearchResult
import com.p2p.domain.crypto.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val chatRepository: ChatRepository,
    private val cryptoManager: CryptoManager,
    private val gson: Gson
) : ViewModel() {

    val contacts: StateFlow<List<VerifiedContact>> = contactRepository.getAllContacts()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<ContactsUiState>(ContactsUiState.Idle)
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    // Живой поиск: ввод -> debounce -> запрос с отменой предыдущего.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchState: StateFlow<SearchUiState> = _searchQuery
        .debounce(300)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            if (query.length < MIN_QUERY_LEN) {
                flowOf(SearchUiState.Idle)
            } else {
                flow {
                    emit(SearchUiState.Loading)
                    val result = contactRepository.searchUsers(query)
                    emit(
                        result.fold(
                            onSuccess = { list ->
                                if (list.isEmpty()) SearchUiState.Empty
                                else SearchUiState.Results(list)
                            },
                            onFailure = { SearchUiState.Error(it.message ?: "Search failed") }
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState.Idle)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /**
     * Открывает чат с найденным пользователем: если он ещё не контакт —
     * добавляет его по TOFU, затем создаёт/находит чат.
     */
    fun startChatWith(result: UserSearchResult) {
        viewModelScope.launch {
            _uiState.value = ContactsUiState.Loading
            try {
                if (contactRepository.getContact(result.userId) == null) {
                    contactRepository.addContactTofu(result)
                }
                val chat = chatRepository.getChatByPeer(result.userId)
                    ?: chatRepository.createChat(result.userId)
                _uiState.value = ContactsUiState.ChatCreated(chat.id, result.userId)
            } catch (e: Exception) {
                _uiState.value = ContactsUiState.Error(e.message ?: "Failed to open chat")
            }
        }
    }

    /**
     * Генерирует QR-код с моим публичным ключом
     */
    suspend fun generateMyQRCode(userId: String, username: String?): String =
        withContext(Dispatchers.IO) {
            val publicKey = cryptoManager.getIdentityPublicKey()
            val qrData = QRCodeData(
                version = 1,
                userId = userId,
                username = username,
                identityPublicKey = cryptoManager.toBase64(publicKey)
            )
            gson.toJson(qrData)
        }

    /**
     * Обрабатывает отсканированный QR-код
     */
    fun handleScannedQR(qrContent: String) {
        viewModelScope.launch {
            _uiState.value = ContactsUiState.Loading

            try {
                val qrData = gson.fromJson(qrContent, QRCodeData::class.java)
                val scannedKey = cryptoManager.fromBase64(qrData.identityPublicKey)

                // Проверяем с сервером
                contactRepository.verifyPublicKey(qrData.userId, scannedKey)
                    .onSuccess { matches ->
                        if (matches) {
                            // Ключи совпадают - сохранение как верифицированный
                            contactRepository.addVerifiedContact(
                                userId = qrData.userId,
                                username = qrData.username,
                                publicKey = scannedKey,
                                verificationMethod = VerificationMethod.QR_SCAN
                            )

                            _uiState.value = ContactsUiState.ContactVerified(qrData.username ?: qrData.userId)
                        } else {
                            _uiState.value = ContactsUiState.Error("Key mismatch! Possible MITM attack.")
                        }
                    }
                    .onFailure { error ->
                        _uiState.value = ContactsUiState.Error(error.message ?: "Verification failed")
                    }
            } catch (e: Exception) {
                _uiState.value = ContactsUiState.Error("Invalid QR code")
            }
        }
    }

    fun openChat(peerUserId: String) {
        viewModelScope.launch {
            _uiState.value = ContactsUiState.Loading
            try {
                val chat = chatRepository.getChatByPeer(peerUserId)
                    ?: chatRepository.createChat(peerUserId)
                _uiState.value = ContactsUiState.ChatCreated(chat.id, peerUserId)
            } catch (e: Exception) {
                _uiState.value = ContactsUiState.Error(e.message ?: "Failed to open chat")
            }
        }
    }

    fun deleteContact(userId: String) {
        viewModelScope.launch {
            contactRepository.deleteContact(userId)
        }
    }

    fun resetState() {
        _uiState.value = ContactsUiState.Idle
    }

    companion object {
        private const val MIN_QUERY_LEN = 3
    }
}

sealed class ContactsUiState {
    object Idle : ContactsUiState()
    object Loading : ContactsUiState()
    data class ContactVerified(val username: String) : ContactsUiState()
    data class ChatCreated(val chatId: String, val peerUserId: String) : ContactsUiState()
    data class Error(val message: String) : ContactsUiState()
}

sealed class SearchUiState {
    object Idle : SearchUiState()            // запрос пустой/короткий — показываем контакты
    object Loading : SearchUiState()
    object Empty : SearchUiState()           // ничего не найдено
    data class Results(val users: List<UserSearchResult>) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

data class QRCodeData(
    val version: Int,
    val userId: String,
    val username: String?,
    val identityPublicKey: String
)