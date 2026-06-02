package com.p2p.data.remote

import com.p2p.data.remote.dto.*
import retrofit2.http.*

interface ApiService {
    // Auth
    @POST("api/auth/request-code")
    suspend fun requestCode(@Body request: RequestCodeRequest): RequestCodeResponse

    @POST("api/auth/verify")
    suspend fun verifyCode(@Body request: VerifyCodeRequest): VerifyCodeResponse

    @POST("api/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): RefreshTokenResponse

    // User
    @POST("api/users/set-username")
    suspend fun setUsername(@Body request: SetUsernameRequest): SetUsernameResponse

    @GET("api/users/search")
    suspend fun searchUser(@Query("q") username: String): SearchUserResponse

    @GET("api/users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserDto

    // Signal Protocol keys
    @PUT("api/keys/prekeys")
    suspend fun registerPrekeys(@Body request: RegisterPrekeysRequest)

    @GET("api/keys/{userId}")
    suspend fun getPrekeyBundle(@Path("userId") userId: String): PrekeyBundleResponse

    @GET("api/keys/count")
    suspend fun getOtkCount(): OtkCountResponse

    // Messages
    @POST("api/messages/store")
    suspend fun storeMessage(@Body request: StoreMessageRequest): StoreMessageResponse

    @GET("api/messages/pending")
    suspend fun getPendingMessages(): PendingMessagesResponse

    @POST("api/messages/ack")
    suspend fun ackMessages(@Body request: AckMessagesRequest)
}