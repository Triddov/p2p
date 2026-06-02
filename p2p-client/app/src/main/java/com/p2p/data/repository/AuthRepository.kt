package com.p2p.data.repository

import android.content.Context
import android.provider.Settings
import com.p2p.data.local.dao.UserDao
import com.p2p.data.local.entities.LocalProfile
import com.p2p.data.remote.ApiService
import com.p2p.data.remote.dto.*
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
     * Обновляет пару токенов через refresh token.
     * Возвращает новый access token или ошибку.
     */
    suspend fun refreshTokens(): Result<String> {
        return try {
            val profile = userDao.getLocalProfile()
                ?: return Result.failure(IllegalStateException("Not authenticated"))

            if (profile.refreshToken.isBlank()) {
                return Result.failure(IllegalStateException("No refresh token"))
            }

            val response = apiService.refreshToken(RefreshTokenRequest(profile.refreshToken))

            userDao.insertLocalProfile(
                profile.copy(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken
                )
            )

            Result.success(response.accessToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Выход из аккаунта
     */
    suspend fun logout() {
        userDao.clearLocalProfile()
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }
}