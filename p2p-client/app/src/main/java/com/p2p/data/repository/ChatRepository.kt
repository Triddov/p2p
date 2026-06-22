package com.p2p.data.repository

import android.util.Base64
import com.google.gson.Gson
import com.p2p.data.local.dao.*
import com.p2p.data.local.entities.*
import com.p2p.data.remote.ApiService
import com.p2p.data.remote.dto.*
import com.p2p.domain.crypto.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val apiService: ApiService,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val userDao: UserDao,
    private val cryptoManager: CryptoManager,
    private val webRTCRepository: WebRTCRepository,
    private val gson: Gson
) {

    // Сериализует выборку pending-сообщений: несколько триггеров (init, refresh,
    // периодический опрос) не должны расшифровывать одну сессию параллельно —
    // это повредило бы состояние Double Ratchet.
    private val pendingFetchMutex = Mutex()

    fun getAllChats(): Flow<List<Chat>> = chatDao.getAllChats()

    fun getMessagesForChat(chatId: String): Flow<List<Message>> =
        messageDao.getMessagesForChat(chatId)

    suspend fun getChat(chatId: String): Chat? = chatDao.getChat(chatId)

    suspend fun getChatByPeer(peerUserId: String): Chat? = chatDao.getChatByPeer(peerUserId)

    suspend fun createChat(peerUserId: String): Chat {
        val contact = contactDao.getContact(peerUserId)
            ?: throw IllegalArgumentException("Contact not found")

        val chatId = "chat-${UUID.randomUUID()}"
        val chat = Chat(
            id = chatId,
            peerUserId = peerUserId,
            peerUsername = contact.username,
            lastMessageText = null,
            lastMessageAt = null,
            unreadCount = 0
        )
        chatDao.insertChat(chat)
        return chat
    }

    /**
     * Отправляет зашифрованное сообщение собеседнику.
     *
     * Порядок:
     * 1. Если Signal-сессии нет — загружает prekey bundle и устанавливает сессию (X3DH).
     * 2. Шифрует через Double Ratchet (libsignal).
     * 3. Пробует P2P (WebRTC DataChannel), иначе — хранит на сервере.
     */
    suspend fun sendMessage(
        chatId: String,
        content: String,
        peerUserId: String
    ): Result<Message> = withContext(Dispatchers.IO) { runCatching {
        val myProfile = userDao.getLocalProfile()
            ?: error("Not authenticated")

        // установка сессии если её нет
        if (!cryptoManager.hasSessionWith(peerUserId)) {
            val bundle = apiService.getPrekeyBundle(peerUserId)
            cryptoManager.buildSession(peerUserId, bundle)
        }

        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // шифрование через Signal Protocol (Double Ratchet)
        val (ciphertextBytes, messageType) = cryptoManager.encrypt(peerUserId, content)
        val ciphertextBase64 = Base64.encodeToString(ciphertextBytes, Base64.NO_WRAP)

        // сохранеение локально сразу со статусом SENDING
        val message = Message(
            id = messageId,
            chatId = chatId,
            content = content,
            timestamp = timestamp,
            senderId = "me",
            status = MessageStatus.SENDING
        )
        messageDao.insertMessage(message)
        chatDao.updateLastMessage(chatId, content, timestamp)

        // пробуем P2P
        val sentP2P = if (webRTCRepository.isConnected(peerUserId)) {
            val p2pMessage = P2PMessage(
                type = "message",
                messageId = messageId,
                timestamp = timestamp,
                ciphertext = ciphertextBase64,
                messageType = messageType
            )
            webRTCRepository.sendMessage(peerUserId, gson.toJson(p2pMessage))
        } else {
            false
        }

        if (sentP2P) {
            messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
        } else {
            // Офлайн-путь: сохранение на сервере
            apiService.storeMessage(
                StoreMessageRequest(
                    recipientId = peerUserId,
                    senderId = myProfile.userId,
                    messageId = messageId,
                    timestamp = timestamp,
                    ciphertext = ciphertextBase64,
                    messageType = messageType
                )
            )
            messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
        }

        message
    } }

    /**
     * Получает pending-сообщения с сервера, расшифровывает и сохраняет локально.
     * После успешного сохранения подтверждает получение (ACK).
     */
    suspend fun fetchPendingMessages(): Result<List<Message>> = withContext(Dispatchers.IO) {
        pendingFetchMutex.withLock { runCatching {
            val response = apiService.getPendingMessages()
            val newMessages = mutableListOf<Message>()
            val ackIds = mutableListOf<String>()

            // Сервер отдаёт сообщения в порядке timestamp ASC — это обязательно
            // для корректного продвижения Double Ratchet (по порядку на отправителя).
            for (pendingMsg in response.messages) {
                runCatching {
                    // Идемпотентность по стабильному id: если сообщение уже обработано,
                    // НЕ расшифровываем повторно (ratchet — одноразовый, второй раз
                    // расшифровать нельзя). Просто переотправляем ACK — это делает
                    // повторную доставку с сервера безопасной: без потерь и дублей.
                    if (messageDao.getMessage(pendingMsg.id) != null) {
                        ackIds.add(pendingMsg.id)
                        return@runCatching
                    }

                    var chat = chatDao.getChatByPeer(pendingMsg.senderId)
                    if (chat == null) {
                        val contact = contactDao.getContact(pendingMsg.senderId)
                        chat = Chat(
                            id = "chat-${UUID.randomUUID()}",
                            peerUserId = pendingMsg.senderId,
                            peerUsername = contact?.username,
                            lastMessageText = null,
                            lastMessageAt = null,
                            unreadCount = 0
                        )
                        chatDao.insertChat(chat)
                    }

                    val ciphertextBytes = Base64.decode(pendingMsg.ciphertext, Base64.NO_WRAP)
                    val decrypted = cryptoManager.decrypt(
                        pendingMsg.senderId,
                        ciphertextBytes,
                        pendingMsg.messageType
                    )

                    val message = Message(
                        id = pendingMsg.id,
                        chatId = chat.id,
                        content = decrypted,
                        timestamp = pendingMsg.timestamp,
                        senderId = pendingMsg.senderId,
                        status = MessageStatus.DELIVERED
                    )
                    // Сохраняем сразу после расшифровки; побочные эффекты для чата —
                    // только для действительно нового сообщения (не для дубля).
                    messageDao.insertMessage(message)
                    chatDao.updateLastMessage(chat.id, decrypted, pendingMsg.timestamp)
                    chatDao.incrementUnreadCount(chat.id)

                    newMessages.add(message)
                    ackIds.add(pendingMsg.id)
                }.onFailure { e ->
                    // НЕ подтверждаем: сообщение остаётся на сервере и будет доставлено
                    // повторно позже — никогда не теряем доставляемое сообщение.
                    android.util.Log.e(
                        "ChatRepository",
                        "Failed to process pending message ${pendingMsg.id}: ${e.message}"
                    )
                }
            }

            if (ackIds.isNotEmpty()) {
                // Сбой ACK не фатален: всё уже сохранено локально, а повторная
                // доставка безопасно отсеётся проверкой getMessage(id) выше.
                runCatching { apiService.ackMessages(AckMessagesRequest(ackIds)) }
                    .onFailure {
                        android.util.Log.e(
                            "ChatRepository",
                            "ACK failed, will retry on next fetch: ${it.message}"
                        )
                    }
            }

            newMessages
        } }
    }

    /**
     * Обрабатывает входящее P2P-сообщение из WebRTC DataChannel.
     */
    suspend fun handleP2PMessage(peerUserId: String, messageJson: String): Result<Message> =
        withContext(Dispatchers.IO) { runCatching {
            val p2pMessage = gson.fromJson(messageJson, P2PMessage::class.java)

            if (p2pMessage.type != "message") {
                error("Unexpected P2P message type: ${p2pMessage.type}")
            }

            val chat = chatDao.getChatByPeer(peerUserId)
                ?: error("Chat not found for peer $peerUserId")

            val ciphertextBytes = Base64.decode(p2pMessage.ciphertext, Base64.NO_WRAP)
            val decrypted = cryptoManager.decrypt(
                peerUserId,
                ciphertextBytes,
                p2pMessage.messageType
            )

            val message = Message(
                id = p2pMessage.messageId,
                chatId = chat.id,
                content = decrypted,
                timestamp = p2pMessage.timestamp,
                senderId = peerUserId,
                status = MessageStatus.DELIVERED
            )
            messageDao.insertMessage(message)
            chatDao.updateLastMessage(chat.id, decrypted, p2pMessage.timestamp)
            chatDao.incrementUnreadCount(chat.id)

            sendDeliveryReceipt(peerUserId, p2pMessage.messageId)
            message
        } }

    suspend fun handleDeliveryReceipt(messageId: String) {
        messageDao.updateMessageStatus(messageId, MessageStatus.DELIVERED)
    }

    suspend fun markChatAsRead(chatId: String) {
        chatDao.clearUnreadCount(chatId)
    }

    private fun sendDeliveryReceipt(peerUserId: String, messageId: String) {
        val receipt = P2PMessage(
            type = "delivery_receipt",
            messageId = messageId,
            timestamp = System.currentTimeMillis(),
            ciphertext = "",
            messageType = CiphertextMessage.WHISPER_TYPE
        )
        webRTCRepository.sendMessage(peerUserId, gson.toJson(receipt))
    }
}

data class P2PMessage(
    val type: String,        // "message" | "delivery_receipt"
    val messageId: String,
    val timestamp: Long,
    val ciphertext: String,  // base64-encoded Signal ciphertext
    val messageType: Int     // CiphertextMessage.WHISPER_TYPE | PREKEY_TYPE
)
