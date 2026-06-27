package com.p2p.data.repository

import com.p2p.data.local.dao.ChatDao
import com.p2p.data.local.dao.ContactDao
import com.p2p.data.local.dao.MessageDao
import com.p2p.data.local.entities.Chat
import com.p2p.data.local.entities.Message
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Доступ к данным чатов и сообщений (для UI). Доменная логика обмена
 * (шифрование, транспорт, квитанции, приём) — в [MessagingService].
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao
) {

    fun getAllChats(): Flow<List<Chat>> = chatDao.getAllChats()

    fun getMessagesForChat(chatId: String): Flow<List<Message>> =
        messageDao.getMessagesForChat(chatId)

    suspend fun getChat(chatId: String): Chat? = chatDao.getChat(chatId)

    suspend fun getChatByPeer(peerUserId: String): Chat? = chatDao.getChatByPeer(peerUserId)

    suspend fun createChat(peerUserId: String): Chat {
        val contact = contactDao.getContact(peerUserId)
            ?: throw IllegalArgumentException("Contact not found")

        val chat = Chat(
            id = "chat-${UUID.randomUUID()}",
            peerUserId = peerUserId,
            peerUsername = contact.username,
            lastMessageText = null,
            lastMessageAt = null,
            unreadCount = 0
        )
        chatDao.insertChat(chat)
        return chat
    }

    /** Удаляет чат вместе со всеми его сообщениями (контакт остаётся). */
    suspend fun deleteChat(chatId: String) {
        messageDao.deleteMessagesForChat(chatId)
        chatDao.deleteChat(chatId)
    }

    /** Удаляет чат с указанным собеседником, если он есть. */
    suspend fun deleteChatByPeer(peerUserId: String) {
        chatDao.getChatByPeer(peerUserId)?.let { deleteChat(it.id) }
    }
}
