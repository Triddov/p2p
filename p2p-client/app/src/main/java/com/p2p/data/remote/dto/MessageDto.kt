package com.p2p.data.remote.dto

import com.google.gson.annotations.SerializedName

data class StoreMessageRequest(
    @SerializedName("recipient_id")
    val recipientId: String,
    @SerializedName("sender_id")
    val senderId: String,
    @SerializedName("message_id")
    val messageId: String,
    val timestamp: Long,
    val ciphertext: String,        // base64-encoded Signal message
    @SerializedName("message_type")
    val messageType: Int           // 2 = WHISPER, 3 = PREKEY
)

data class StoreMessageResponse(
    val status: String,
    @SerializedName("message_id")
    val messageId: String,
    @SerializedName("will_notify")
    val willNotify: Boolean
)

data class PendingMessagesResponse(
    val messages: List<PendingMessageDto>
)

data class PendingMessageDto(
    val id: String,
    @SerializedName("sender_id")
    val senderId: String,
    val ciphertext: String,        // base64-encoded Signal message
    @SerializedName("message_type")
    val messageType: Int,          // 2 = WHISPER, 3 = PREKEY
    val timestamp: Long
)

data class AckMessagesRequest(
    @SerializedName("message_ids")
    val messageIds: List<String>
)
