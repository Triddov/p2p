package com.p2p.data.repository

import com.p2p.data.local.dao.ContactDao
import com.p2p.data.local.entities.VerificationMethod
import com.p2p.data.local.entities.VerifiedContact
import com.p2p.data.remote.ApiService
import com.p2p.domain.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val apiService: ApiService,
    private val contactDao: ContactDao,
    private val cryptoManager: CryptoManager
) {

    fun getAllContacts(): Flow<List<VerifiedContact>> = contactDao.getAllContacts()

    suspend fun getContact(userId: String): VerifiedContact? = contactDao.getContact(userId)

    /**
     * Ищет пользователей по префиксу username на сервере (живой поиск).
     * Возвращает список с пометкой, кто уже в контактах.
     */
    suspend fun searchUsers(prefix: String): Result<List<UserSearchResult>> {
        return try {
            val response = apiService.searchUsers(prefix)
            val results = response.users.map { user ->
                UserSearchResult(
                    userId = user.id,
                    username = user.username,
                    identityPublicKey = cryptoManager.fromBase64(user.identityPublicKey),
                    isContact = contactDao.getContact(user.id) != null
                )
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Добавляет контакт по модели TOFU (trust on first use): identity-ключ берётся
     * с сервера, контакт помечается как НЕ верифицированный. Верификация
     * (QR / safety number) — опциональный апгрейд позже.
     */
    suspend fun addContactTofu(result: UserSearchResult) {
        val contact = VerifiedContact(
            userId = result.userId,
            username = result.username,
            identityPublicKey = result.identityPublicKey,
            verifiedAt = 0L,
            verificationMethod = VerificationMethod.TOFU
        )
        contactDao.insertContact(contact)
    }

    /**
     * Добавляет контакт после сканирования QR
     */
    suspend fun addVerifiedContact(
        userId: String,
        username: String?,
        publicKey: ByteArray,
        verificationMethod: VerificationMethod
    ) {
        val contact = VerifiedContact(
            userId = userId,
            username = username,
            identityPublicKey = publicKey,
            verifiedAt = System.currentTimeMillis(),
            verificationMethod = verificationMethod
        )

        contactDao.insertContact(contact)
    }

    /**
     * Проверяет, совпадает ли ключ из QR с ключом с сервера
     */
    suspend fun verifyPublicKey(userId: String, scannedKey: ByteArray): Result<Boolean> {
        return try {
            val user = apiService.getUser(userId)
            val serverKey = cryptoManager.fromBase64(user.identityPublicKey)

            val matches = scannedKey.contentEquals(serverKey)
            Result.success(matches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Детект смены identity-ключа собеседника
     * Сравнивает текущий ключ с сервера с запиненным в контакте. Если изменился:
     * пере-пинивает новый ключ, СБРАСЫВАЕТ доверие в "tofu" и обнуляет старую
     * сессию (чтобы построилась новая). Возвращает true, если ключ сменился.
     *
     * Это штатное поведение при переустановке у собеседника — но также возможный
     * признак MITM, поэтому в UI показывается предупреждение и предлагается
     * повторная верификация.
     */
    suspend fun detectKeyChange(userId: String): Boolean {
        val contact = contactDao.getContact(userId) ?: return false

        val serverUser = try {
            apiService.getUser(userId)
        } catch (e: Exception) {
            return false // нет связи — не трогаем состояние
        }
        val serverKey = cryptoManager.fromBase64(serverUser.identityPublicKey)

        if (serverKey.contentEquals(contact.identityPublicKey)) return false

        contactDao.insertContact(
            contact.copy(
                identityPublicKey = serverKey,
                verificationMethod = VerificationMethod.TOFU,
                verifiedAt = 0L
            )
        )
        cryptoManager.resetSessionForNewKey(userId, serverKey)
        return true
    }

    suspend fun deleteContact(userId: String) {
        contactDao.deleteContact(userId)
    }
}

/**
 * Результат поиска пользователя (ещё не контакт). isContact = уже в списке контактов.
 */
data class UserSearchResult(
    val userId: String,
    val username: String?,
    val identityPublicKey: ByteArray,
    val isContact: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserSearchResult) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}