package com.p2p.data.remote

import com.google.gson.Gson
import com.p2p.data.local.dao.UserDao
import com.p2p.data.remote.dto.RefreshTokenRequest
import com.p2p.data.remote.dto.RefreshTokenResponse
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TokenAuthenticator @Inject constructor(
    private val userDao: UserDao,
    private val gson: Gson,
    @Named("baseUrl") private val baseUrl: String
) : Authenticator {

    // Отдельный клиент без authenticator — чтобы избежать рекурсии
    private val authClient = OkHttpClient()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Не пытаемся рефрешить, если сам /auth/refresh вернул 401
        if (response.request.url.encodedPath.contains("/auth/refresh")) return null
        // Ограничение количества попыток (цепочка priorResponse)
        if (response.responseCount() >= 3) return null

        val profile = runBlocking { userDao.getLocalProfile() } ?: return null

        // Первая попытка: добавление сохранённого access token, если он не был отправлен
        if (response.request.header("Authorization") == null && profile.accessToken.isNotBlank()) {
            return response.request.newBuilder()
                .header("Authorization", "Bearer ${profile.accessToken}")
                .build()
        }

        // Вторая попытка: refresh
        if (profile.refreshToken.isBlank()) return null

        return try {
            val body = gson.toJson(RefreshTokenRequest(profile.refreshToken))
                .toRequestBody("application/json".toMediaType())

            val refreshRequest = Request.Builder()
                .url("${baseUrl}api/auth/refresh")
                .post(body)
                .build()

            val refreshResponse = authClient.newCall(refreshRequest).execute()
            if (!refreshResponse.isSuccessful) return null

            val tokens = gson.fromJson(
                refreshResponse.body?.string(),
                RefreshTokenResponse::class.java
            ) ?: return null

            runBlocking {
                userDao.insertLocalProfile(
                    profile.copy(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken
                    )
                )
            }

            response.request.newBuilder()
                .header("Authorization", "Bearer ${tokens.accessToken}")
                .build()
        } catch (e: Exception) {
            null
        }
    }

    private fun Response.responseCount(): Int {
        var count = 1
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
