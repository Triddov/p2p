package com.p2p.data.repository

import android.util.Base64
import com.google.gson.Gson
import com.p2p.data.local.dao.ChatDao
import com.p2p.data.local.dao.MessageDao
import com.p2p.data.local.dao.UserDao
import com.p2p.data.local.entities.Chat
import com.p2p.data.local.entities.Message
import com.p2p.data.local.entities.MessageStatus
import com.p2p.data.remote.ApiService
import com.p2p.data.remote.dto.AckMessagesRequest
import com.p2p.data.remote.dto.P2PMessage
import com.p2p.data.remote.dto.P2PMessageType
import com.p2p.data.remote.dto.StoreMessageRequest
import com.p2p.domain.crypto.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.message.CiphertextMessage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Доменная оркестрация обмена сообщениями: шифрование (Signal), выбор транспорта
 * (P2P/сервер), приём офлайн- и P2P-сообщений, квитанции доставки/прочтения.
 * Доступ к данным чатов/сообщений — через DAO; CRUD для UI живёт в ChatRepository.
 */
@Singleton
class MessagingService @Inject constructor(
    private val apiService: ApiService,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val cryptoManager: CryptoManager,
    private val webRTCRepository: WebRTCRepository,
    private val contactRepository: ContactRepository,
    private val gson: Gson
) {

    // Сериализует выборку pending-сообщений: несколько триггеров (init, refresh,
    // периодический опрос) не должны расшифровывать одну сессию параллельно —
    // это повредило бы состояние Double Ratchet.
    private val pendingFetchMutex = Mutex()

    /**
     * Отправляет зашифрованное сообщение собеседнику.
     * 1. Если Signal-сессии нет — загружает prekey bundle и устанавливает сессию (X3DH).
     * 2. Шифрует через Double Ratchet (libsignal)
     * 3. Пробует P2P (WebRTC DataChannel), иначе — хранит на сервере
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

        // сохранение локально сразу со статусом SENDING
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
                type = P2PMessageType.MESSAGE,
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
                    // НЕ расшифровываем повторно (ratchet — одноразовый). Просто
                    // переотправляем ACK — повторная доставка безопасна (без дублей/потерь).
                    if (messageDao.getMessage(pendingMsg.id) != null) {
                        ackIds.add(pendingMsg.id)
                        return@runCatching
                    }

                    var chat = chatDao.getChatByPeer(pendingMsg.senderId)
                    if (chat == null) {
                        // Незнакомый отправитель — добавляем по TOFU, чтобы был ник и статус доверия.
                        contactRepository.ensureTofuContact(pendingMsg.senderId)
                        val username = contactRepository.getContact(pendingMsg.senderId)?.username
                        chat = Chat(
                            id = "chat-${UUID.randomUUID()}",
                            peerUserId = pendingMsg.senderId,
                            peerUsername = username,
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
                    messageDao.insertMessage(message)
                    chatDao.updateLastMessage(chat.id, decrypted, pendingMsg.timestamp)
                    chatDao.incrementUnreadCount(chat.id)

                    newMessages.add(message)
                    ackIds.add(pendingMsg.id)
                }.onFailure { e ->
                    // НЕ подтверждаем: сообщение останется на сервере и придёт повторно.
                    android.util.Log.e(
                        "MessagingService",
                        "Failed to process pending message ${pendingMsg.id}: ${e.message}"
                    )
                }
            }

            if (ackIds.isNotEmpty()) {
                runCatching { apiService.ackMessages(AckMessagesRequest(ackIds)) }
                    .onFailure {
                        android.util.Log.e(
                            "MessagingService",
                            "ACK failed, will retry on next fetch: ${it.message}"
                        )
                    }
            }

            newMessages
        } }
    }

    /**
     * Обрабатывает входящее P2P-сообщение из WebRTC DataChannel.
     * Маршрутизация по типу: сообщение / квитанция доставки / квитанция прочтения.
     */
    suspend fun handleP2PMessage(peerUserId: String, messageJson: String): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching {
            val p2pMessage = gson.fromJson(messageJson, P2PMessage::class.java)
            when (p2pMessage.type) {
                P2PMessageType.MESSAGE -> handleIncomingMessage(peerUserId, p2pMessage)
                P2PMessageType.DELIVERY_RECEIPT ->
                    messageDao.updateMessageStatus(p2pMessage.messageId, MessageStatus.DELIVERED)
                P2PMessageType.READ_RECEIPT -> {
                    val chat = chatDao.getChatByPeer(peerUserId)
                    if (chat != null) messageDao.markOutgoingMessagesRead(chat.id)
                }
                null -> error("Unknown P2P message type")
            }
        } }

    private suspend fun handleIncomingMessage(peerUserId: String, p2pMessage: P2PMessage) {
        val chat = chatDao.getChatByPeer(peerUserId)
            ?: error("Chat not found for peer $peerUserId")

        // Идемпотентность: если сообщение уже есть (ретрансмит) — не расшифровываем
        // повторно, только переотправляем квитанцию доставки.
        if (messageDao.getMessage(p2pMessage.messageId) != null) {
            sendDeliveryReceipt(peerUserId, p2pMessage.messageId)
            return
        }

        val ciphertextBytes = Base64.decode(p2pMessage.ciphertext, Base64.NO_WRAP)
        val decrypted = cryptoManager.decrypt(peerUserId, ciphertextBytes, p2pMessage.messageType)

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
    }

    /** Сбрасывает счётчик непрочитанных и шлёт собеседнику квитанцию прочтения (best-effort). */
    suspend fun markChatAsRead(chatId: String) {
        chatDao.clearUnreadCount(chatId)
        chatDao.getChat(chatId)?.let { sendReadReceipt(it.peerUserId) }
    }

    private fun sendReadReceipt(peerUserId: String) {
        val receipt = P2PMessage(
            type = P2PMessageType.READ_RECEIPT,
            messageId = "",
            timestamp = System.currentTimeMillis(),
            ciphertext = "",
            messageType = CiphertextMessage.WHISPER_TYPE
        )
        webRTCRepository.sendMessage(peerUserId, gson.toJson(receipt))
    }

    private fun sendDeliveryReceipt(peerUserId: String, messageId: String) {
        val receipt = P2PMessage(
            type = P2PMessageType.DELIVERY_RECEIPT,
            messageId = messageId,
            timestamp = System.currentTimeMillis(),
            ciphertext = "",
            messageType = CiphertextMessage.WHISPER_TYPE
        )
        webRTCRepository.sendMessage(peerUserId, gson.toJson(receipt))
    }
}
