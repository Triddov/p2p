package com.p2p.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OneTimePrekeyDto(
    val id: Int,
    @SerializedName("public_key")
    val publicKey: String
)

data class RegisterPrekeysRequest(
    @SerializedName("registration_id")
    val registrationId: Int,
    @SerializedName("identity_key")
    val identityKey: String,
    @SerializedName("signed_prekey_id")
    val signedPrekeyId: Int,
    @SerializedName("signed_prekey")
    val signedPrekey: String,
    @SerializedName("signed_prekey_signature")
    val signedPrekeySig: String,
    @SerializedName("one_time_prekeys")
    val oneTimePrekeys: List<OneTimePrekeyDto>
)

data class AddOneTimePrekeysRequest(
    @SerializedName("one_time_prekeys")
    val oneTimePrekeys: List<OneTimePrekeyDto>
)

data class UpdateSignedPrekeyRequest(
    @SerializedName("signed_prekey_id")
    val signedPrekeyId: Int,
    @SerializedName("signed_prekey")
    val signedPrekey: String,
    @SerializedName("signed_prekey_signature")
    val signedPrekeySig: String
)

data class PrekeyBundleResponse(
    @SerializedName("registration_id")
    val registrationId: Int,
    @SerializedName("identity_key")
    val identityKey: String,
    @SerializedName("signed_prekey_id")
    val signedPrekeyId: Int,
    @SerializedName("signed_prekey")
    val signedPrekey: String,
    @SerializedName("signed_prekey_signature")
    val signedPrekeySig: String,
    @SerializedName("one_time_prekey_id")
    val oneTimePrekeyId: Int?,
    @SerializedName("one_time_prekey")
    val oneTimePrekey: String?
)

data class OtkCountResponse(
    val count: Int
)
