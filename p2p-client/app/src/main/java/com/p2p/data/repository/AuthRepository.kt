package com.p2p.data.repository

import android.content.Context
import android.provider.Settings
import com.p2p.data.local.dao.UserDao
import com.p2p.data.local.entities.LocalProfile
import com.p2p.data.remote.ApiService
import com.p2p.data.remote.dto.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.p2p.domain.crypto.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val userDao: UserDao,
    private val cryptoManager: CryptoManager,
    @ApplicationContext private val context: Context
) {

    fun getLocalProfileFlow(): Flow<LocalProfile?> = userDao.getLocalProfileFlow()

    suspend fun getLocalProfile(): LocalProfile? = userDao.getLocalProfile()

    /**
     * Запрашивает код верификации на email
     */
    suspend fun requestVerificationCode(email: String): Result<RequestCodeResponse> {
        return try {
            val deviceId = getDeviceId()
            val response = apiService.requestCode(
                RequestCodeRequest(email = email, deviceId = deviceId)
            )
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Верифицирует код и регистрирует пользователя
     */
    suspend fun verifyCodeAndRegister(
        email: String,
        code: String
    ): Result<LocalProfile> {
        return try {
            // инициализцируем Signal Protocol ключи при первом запуске
            // withContext(IO): Signal DAO синхронные — нельзя вызывать на Main thread
            val keyRegistrationData = withContext(Dispatchers.IO) {
                if (!cryptoManager.isInitialized()) cryptoManager.initializeKeys() else null
            }

            val identityPublicKey = withContext(Dispatchers.IO) {
                cryptoManager.getIdentityPublicKey()
            }
            val identityPublicKeyBase64 = cryptoManager.toBase64(identityPublicKey)

            val response = apiService.verifyCode(
                VerifyCodeRequest(
                    email = email,
                    code = code,
                    deviceId = getDeviceId(),
                    identityPublicKey = identityPublicKeyBase64
                )
            )

            val profile = LocalProfile(
                userId = response.userId,
                email = response.email,
                username = null,
                identityPublicKey = identityPublicKey,
                accessToken = response.accessToken,
                refreshToken = response.refreshToken
            )
            userDao.insertLocalProfile(profile)

            // загрузка prekey bundle на сервер (только если ключи только что сгенерированы)
            if (keyRegistrationData != null) {
                apiService.registerPrekeys(keyRegistrationData)
            }

            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Устанавливает username для пользователя
     */
    suspend fun setUsername(username: String): Result<String> {
        return try {
            val response = apiService.setUsername(SetUsernameRequest(username))

            // обновление локального профиля
            val profile = userDao.getLocalProfile()
            if (profile != null) {
                userDao.insertLocalProfile(profile.copy(username = response.username))
            }

            Result.success(response.username)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Выход из аккаунта
     */
    suspend fun logout() {
        runCatching {
            val token = withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().token) }
            apiService.deleteFcmToken(FcmTokenRequest(token))
        }
        userDao.clearLocalProfile()
    }

    /** Получает текущий FCM-токен и регистрирует его на сервере (best-effort) */
    suspend fun registerFcmToken() {
        runCatching {
            val token = withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().token) }
            apiService.registerFcmToken(FcmTokenRequest(token))
        }
    }

    /** Регистрирует конкретный токен (из FirebaseMessagingService.onNewToken) */
    suspend fun registerFcmToken(token: String) {
        runCatching { apiService.registerFcmToken(FcmTokenRequest(token)) }
    }

    /**
     * Пополняет пул one-time prekeys на сервере, если он истощается
     * OTK расходуются по одному при каждой новой X3DH-сессии; без пополнения
     * после ~сотни первых контактов forward secrecy первого сообщения слабеет
     */
    suspend fun replenishOneTimePrekeysIfNeeded() {
        runCatching {
            if (!cryptoManager.isInitialized()) return
            val count = apiService.getOtkCount().count
            if (count >= OTK_LOW_THRESHOLD) return
            val newOtks = cryptoManager.generateMoreOneTimePrekeys(OTK_TARGET - count)
            apiService.addOneTimePrekeys(AddOneTimePrekeysRequest(newOtks))
        }
    }

    /**
     * Ротирует signed prekey, если он устарел (крипто-гигиена). Best-effort
     */
    suspend fun rotateSignedPrekeyIfNeeded() {
        runCatching {
            if (!cryptoManager.isInitialized()) return
            val upload = cryptoManager.rotateSignedPrekeyIfNeeded(SIGNED_PREKEY_MAX_AGE_MS) ?: return
            apiService.updateSignedPrekey(upload)
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    companion object {
        private const val OTK_LOW_THRESHOLD = 20
        private const val OTK_TARGET = 100
        private const val SIGNED_PREKEY_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}