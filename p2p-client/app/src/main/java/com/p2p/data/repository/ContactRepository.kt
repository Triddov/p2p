package com.p2p.data.repository

import com.p2p.data.local.dao.ContactDao
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
     * Ищет пользователя по username на сервере
     */
    suspend fun searchUser(username: String): Result<VerifiedContact?> {
        return try {
            val response = apiService.searchUser(username)

            if (!response.found || response.user == null) {
                return Result.success(null)
            }

            val user = response.user
            val contact = VerifiedContact(
                userId = user.id,
                username = user.username,
                identityPublicKey = cryptoManager.fromBase64(user.identityPublicKey),
                verifiedAt = 0, // Не верифицирован через QR
                verificationMethod = "server_search"
            )

            Result.success(contact)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Добавляет контакт после сканирования QR
     */
    suspend fun addVerifiedContact(
        userId: String,
        username: String?,
        publicKey: ByteArray,
        verificationMethod: String
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

    suspend fun deleteContact(userId: String) {
        contactDao.deleteContact(userId)
    }
}