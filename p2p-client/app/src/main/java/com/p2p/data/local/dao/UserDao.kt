package com.p2p.data.local.dao

import androidx.room.*
import com.p2p.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM local_profile LIMIT 1")
    suspend fun getLocalProfile(): LocalProfile?

    @Query("SELECT * FROM local_profile LIMIT 1")
    fun getLocalProfileFlow(): Flow<LocalProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocalProfile(profile: LocalProfile)

    @Query("DELETE FROM local_profile")
    suspend fun clearLocalProfile()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM verified_contacts ORDER BY username ASC")
    fun getAllContacts(): Flow<List<VerifiedContact>>

    @Query("SELECT * FROM verified_contacts WHERE userId = :userId")
    suspend fun getContact(userId: String): VerifiedContact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: VerifiedContact)

    @Query("DELETE FROM verified_contacts WHERE userId = :userId")
    suspend fun deleteContact(userId: String)

    @Query("SELECT * FROM verified_contacts WHERE username LIKE :query || '%'")
    fun searchContacts(query: String): Flow<List<VerifiedContact>>
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastMessageAt DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChat(chatId: String): Chat?

    @Query("SELECT * FROM chats WHERE peerUserId = :peerUserId")
    suspend fun getChatByPeer(peerUserId: String): Chat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat)

    @Query("UPDATE chats SET lastMessageText = :text, lastMessageAt = :timestamp WHERE id = :chatId")
    suspend fun updateLastMessage(chatId: String, text: String, timestamp: Long)

    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE id = :chatId")
    suspend fun incrementUnreadCount(chatId: String)

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :chatId")
    suspend fun clearUnreadCount(chatId: String)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): Message?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: Message)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    // Все отправленные нами сообщения в чате помечаем прочитанными (по read-квитанции).
    // Значения статусов хранятся как имена enum (см. Converters).
    @Query("UPDATE messages SET status = 'READ' WHERE chatId = :chatId AND senderId = 'me' AND status IN ('SENT', 'DELIVERED')")
    suspend fun markOutgoingMessagesRead(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)
}

// Signal Protocol DAOs
// Все методы non-suspend: SignalProtocolStore - синхронный Java-интерфейс
// Вызываются только из фоновых потоков (внутри coroutine)

@Dao
interface SignalOwnIdentityDao {
    @Query("SELECT * FROM signal_own_identity WHERE id = 1")
    fun get(): SignalOwnIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(identity: SignalOwnIdentity)
}

@Dao
interface SignalTrustedIdentityDao {
    @Query("SELECT * FROM signal_trusted_identities WHERE address = :address")
    fun get(address: String): SignalTrustedIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(identity: SignalTrustedIdentity)
}

@Dao
interface SignalPreKeyDao {
    @Query("SELECT * FROM signal_prekeys WHERE preKeyId = :preKeyId")
    fun get(preKeyId: Int): SignalPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(preKey: SignalPreKeyEntity)

    @Query("DELETE FROM signal_prekeys WHERE preKeyId = :preKeyId")
    fun delete(preKeyId: Int)

    @Query("SELECT COUNT(*) FROM signal_prekeys")
    fun count(): Int
}

@Dao
interface SignalSignedPreKeyDao {
    @Query("SELECT * FROM signal_signed_prekeys WHERE signedPreKeyId = :id")
    fun get(id: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys")
    fun getAll(): List<SignalSignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(signedPreKey: SignalSignedPreKeyEntity)

    @Query("DELETE FROM signal_signed_prekeys WHERE signedPreKeyId = :id")
    fun delete(id: Int)

    @Query("SELECT COUNT(*) FROM signal_signed_prekeys WHERE signedPreKeyId = :id")
    fun exists(id: Int): Int
}

@Dao
interface SignalSessionDao {
    @Query("SELECT * FROM signal_sessions WHERE address = :address")
    fun get(address: String): SignalSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(session: SignalSessionEntity)

    @Query("DELETE FROM signal_sessions WHERE address = :address")
    fun delete(address: String)

    @Query("SELECT COUNT(*) FROM signal_sessions WHERE address = :address")
    fun exists(address: String): Int

    @Query("SELECT address FROM signal_sessions WHERE address LIKE :name || ':%'")
    fun getAddressesForName(name: String): List<String>

    @Query("DELETE FROM signal_sessions WHERE address LIKE :name || ':%'")
    fun deleteAllForName(name: String)
}