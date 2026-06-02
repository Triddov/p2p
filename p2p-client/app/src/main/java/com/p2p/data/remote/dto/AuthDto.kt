package com.p2p.data.remote.dto

import com.google.gson.annotations.SerializedName

data class RequestCodeRequest(
    val email: String,
    @SerializedName("device_id")
    val deviceId: String
)

data class RequestCodeResponse(
    val status: String,
    @SerializedName("retry_after")
    val retryAfter: Int?
)

data class VerifyCodeRequest(
    val email: String,
    val code: String,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("identity_public_key")
    val identityPublicKey: String
)

data class VerifyCodeResponse(
    @SerializedName("user_id")
    val userId: String,
    val email: String,
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String,
    @SerializedName("identity_public_key")
    val identityPublicKey: String
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

data class RefreshTokenResponse(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String
)

data class SetUsernameRequest(
    val username: String
)

data class SetUsernameResponse(
    val username: String
)