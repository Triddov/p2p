package com.p2p.data.remote.dto

import com.google.gson.annotations.SerializedName

data class SearchUsersResponse(
    val users: List<UserDto>
)

data class UserDto(
    val id: String,
    val username: String?,
    @SerializedName("identity_public_key")
    val identityPublicKey: String,
    @SerializedName("last_seen")
    val lastSeen: String?,
    val online: Boolean = false
)