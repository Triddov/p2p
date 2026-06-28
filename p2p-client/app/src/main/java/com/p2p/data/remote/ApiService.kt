package com.p2p.data.remote

import com.p2p.data.remote.dto.*
import retrofit2.http.*

interface ApiService {
    // Auth
    @POST("api/auth/request-code")
    suspend fun requestCode(@Body request: RequestCodeRequest): RequestCodeResponse

    @POST("api/auth/verify")
    suspend fun verifyCode(@Body request: VerifyCodeRequest): VerifyCodeResponse

    // User
    @POST("api/users/set-username")
    suspend fun setUsername(@Body request: SetUsernameRequest): SetUsernameResponse

    @PUT("api/users/discoverable")
    suspend fun setDiscoverable(@Body request: SetDiscoverableRequest)

    @PUT("api/users/fcm-token")
    suspend fun registerFcmToken(@Body request: FcmTokenRequest)

    @HTTP(method = "DELETE", path = "api/users/fcm-token", hasBody = true)
    suspend fun deleteFcmToken(@Body request: FcmTokenRequest)

    @GET("api/users/search")
    suspend fun searchUsers(@Query("q") query: String): SearchUsersResponse

    @GET("api/users/{userId}")
    suspend fun getUser(@Path("userId") userId: String): UserDto

    // Signal Protocol keys
    @PUT("api/keys/prekeys")
    suspend fun registerPrekeys(@Body request: RegisterPrekeysRequest)

    @POST("api/keys/otks")
    suspend fun addOneTimePrekeys(@Body request: AddOneTimePrekeysRequest)

    @PUT("api/keys/signed-prekey")
    suspend fun updateSignedPrekey(@Body request: UpdateSignedPrekeyRequest)

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