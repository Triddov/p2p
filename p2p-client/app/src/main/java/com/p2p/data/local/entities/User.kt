package com.p2p.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_profile")
data class LocalProfile(
    @PrimaryKey
    val userId: String,
    val email: String,
    val username: String?,
    val identityPublicKey: ByteArray,
    val accessToken: String,
    val refreshToken: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LocalProfile
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()
}

@Entity(tableName = "verified_contacts")
data class VerifiedContact(
    @PrimaryKey
    val userId: String,
    val username: String?,
    val identityPublicKey: ByteArray,
    val verifiedAt: Long,
    val verificationMethod: VerificationMethod,
    val lastSeen: Long? = null
) {
    // Полное value-равенство: важно для Compose — иначе при смене verificationMethod
    // (тот же userId) UI считал бы контакт неизменившимся и не обновлял статус.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifiedContact) return false
        return userId == other.userId &&
            username == other.username &&
            identityPublicKey.contentEquals(other.identityPublicKey) &&
            verifiedAt == other.verifiedAt &&
            verificationMethod == other.verificationMethod &&
            lastSeen == other.lastSeen
    }

    override fun hashCode(): Int {
        var result = userId.hashCode()
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + identityPublicKey.contentHashCode()
        result = 31 * result + verifiedAt.hashCode()
        result = 31 * result + verificationMethod.hashCode()
        result = 31 * result + (lastSeen?.hashCode() ?: 0)
        return result
    }
}

@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey
    val id: String,
    val peerUserId: String,
    val peerUsername: String?,
    val lastMessageText: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int = 0
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val id: String,
    val chatId: String,
    val content: String,
    val timestamp: Long,
    val senderId: String, // "me" or peer user ID
    val status: MessageStatus,
    val isEncrypted: Boolean = true
)

enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

/**
 * Уровень доверия к контакту.
 * storageValue — стабильное строковое представление в БД (не зависит от имени константы).
 */
enum class VerificationMethod(val storageValue: String) {
    TOFU("tofu"),          // trust on first use, не верифицирован
    QR_SCAN("qr_scan");    // верифицирован сканированием QR

    val isVerified: Boolean get() = this == QR_SCAN

    companion object {
        fun fromStorage(value: String): VerificationMethod =
            entries.firstOrNull { it.storageValue == value } ?: TOFU
    }
}

// Signal Protocol storage entities

// Собственная identity key pair + registration ID (единственная запись, id = 1)
@Entity(tableName = "signal_own_identity")
data class SignalOwnIdentity(
    @PrimaryKey val id: Int = 1,
    val identityKeyPairBytes: ByteArray,
    val registrationId: Int
) {
    override fun equals(other: Any?) = other is SignalOwnIdentity && id == other.id
    override fun hashCode() = id
}

// Trusted identity keys контактов (TOFU)
@Entity(tableName = "signal_trusted_identities")
data class SignalTrustedIdentity(
    @PrimaryKey val address: String, // "userId:deviceId"
    val identityKeyBytes: ByteArray
) {
    override fun equals(other: Any?) = other is SignalTrustedIdentity && address == other.address
    override fun hashCode() = address.hashCode()
}

// Наши собственные one-time prekeys (хранятся до потребления сервером)
@Entity(tableName = "signal_prekeys")
data class SignalPreKeyEntity(
    @PrimaryKey val preKeyId: Int,
    val serializedRecord: ByteArray
) {
    override fun equals(other: Any?) = other is SignalPreKeyEntity && preKeyId == other.preKeyId
    override fun hashCode() = preKeyId
}

// Наш активный signed prekey
@Entity(tableName = "signal_signed_prekeys")
data class SignalSignedPreKeyEntity(
    @PrimaryKey val signedPreKeyId: Int,
    val serializedRecord: ByteArray
) {
    override fun equals(other: Any?) = other is SignalSignedPreKeyEntity && signedPreKeyId == other.signedPreKeyId
    override fun hashCode() = signedPreKeyId
}

// Double Ratchet состояние сессии с каждым собеседником
@Entity(tableName = "signal_sessions")
data class SignalSessionEntity(
    @PrimaryKey val address: String, // "userId:deviceId"
    val serializedRecord: ByteArray
) {
    override fun equals(other: Any?) = other is SignalSessionEntity && address == other.address
    override fun hashCode() = address.hashCode()
}
