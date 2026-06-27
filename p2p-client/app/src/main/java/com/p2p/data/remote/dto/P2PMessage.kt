package com.p2p.data.remote.dto

import com.google.gson.annotations.SerializedName

enum class P2PMessageType {
    @SerializedName("message") MESSAGE,
    @SerializedName("delivery_receipt") DELIVERY_RECEIPT,
    @SerializedName("read_receipt") READ_RECEIPT
}

data class P2PMessage(
    val type: P2PMessageType,
    val messageId: String,
    val timestamp: Long,
    val ciphertext: String,  // base64-encoded Signal ciphertext
    val messageType: Int     // CiphertextMessage.WHISPER_TYPE | PREKEY_TYPE
)
