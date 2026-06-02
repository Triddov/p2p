package com.p2p.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.p2p.data.local.entities.VerifiedContact
import com.p2p.data.repository.ChatRepository
import com.p2p.data.repository.ContactRepository
import com.p2p.domain.crypto.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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

    fun searchUser(username: String) {
        viewModelScope.launch {
            _uiState.value = ContactsUiState.Loading

            contactRepository.searchUser(username)
                .onSuccess { contact ->
                    if (contact != null) {
                        _uiState.value = ContactsUiState.UserFound(contact)
                    } else {
                        _uiState.value = ContactsUiState.Error("User not found")
                    }
                }
                .onFailure { error ->
                    _uiState.value = ContactsUiState.Error(error.message ?: "Search failed")
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
                                verificationMethod = "qr_scan"
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

    /**
     * todo Вычисляет fingerprint для голосовой верификации
     */
//    fun calculateFingerprint(userId: String): String? {
//        viewModelScope.launch {
//            val contact = contactRepository.getContact(userId)
//            if (contact != null) {
//                return@launch cryptoManager.calculateFingerprint(contact.identityPublicKey)
//            }
//        }
//        return null
//    }

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
}

sealed class ContactsUiState {
    object Idle : ContactsUiState()
    object Loading : ContactsUiState()
    data class UserFound(val contact: VerifiedContact) : ContactsUiState()
    data class ContactVerified(val username: String) : ContactsUiState()
    data class ChatCreated(val chatId: String, val peerUserId: String) : ContactsUiState()
    data class Error(val message: String) : ContactsUiState()
}

data class QRCodeData(
    val version: Int,
    val userId: String,
    val username: String?,
    val identityPublicKey: String
)