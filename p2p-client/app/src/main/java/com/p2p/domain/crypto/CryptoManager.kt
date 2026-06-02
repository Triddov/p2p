package com.p2p.domain.crypto

import android.util.Base64
import com.p2p.data.remote.dto.OneTimePrekeyDto
import com.p2p.data.remote.dto.PrekeyBundleResponse
import com.p2p.data.remote.dto.RegisterPrekeysRequest
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoManager @Inject constructor(
    private val store: SignalProtocolStoreImpl
) {
    companion object {
        private const val DEVICE_ID = 1
        private const val OTK_BATCH_SIZE = 100
        private const val SIGNED_PREKEY_ID = 1
    }

    fun isInitialized(): Boolean = store.isInitialized()

    /**
     * Генерирует все ключи Signal Protocol:
     *  - Identity key pair (Ed25519 / X25519)
     *  - Signed prekey
     *  - 100 one-time prekeys
     *
     * Сохраняет в SignalProtocolStore (Room).
     * Возвращает данные для загрузки на сервер.
     */
    fun initializeKeys(): RegisterPrekeysRequest {
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = SecureRandom().nextInt(16380) + 1

        store.saveOwnIdentity(identityKeyPair, registrationId)

        // Signed prekey
        val signedKeyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(
            identityKeyPair.privateKey,
            signedKeyPair.publicKey.serialize()
        )
        val signedPreKey = SignedPreKeyRecord(
            SIGNED_PREKEY_ID,
            System.currentTimeMillis(),
            signedKeyPair,
            signature
        )
        store.storeSignedPreKey(SIGNED_PREKEY_ID, signedPreKey)

        // One-time prekeys
        val preKeys = (1..OTK_BATCH_SIZE).map { id ->
            PreKeyRecord(id, Curve.generateKeyPair())
        }
        preKeys.forEach { store.storePreKey(it.id, it) }

        return RegisterPrekeysRequest(
            registrationId = registrationId,
            identityKey = toBase64(identityKeyPair.publicKey.serialize()),
            signedPrekeyId = SIGNED_PREKEY_ID,
            signedPrekey = toBase64(signedKeyPair.publicKey.serialize()),
            signedPrekeySig = toBase64(signature),
            oneTimePrekeys = preKeys.map { pk ->
                OneTimePrekeyDto(pk.id, toBase64(pk.keyPair.publicKey.serialize()))
            }
        )
    }

    /**
     * Устанавливает X3DH сессию из prekey bundle собеседника.
     * Вызывается один раз перед первым сообщением.
     */
    fun buildSession(recipientUserId: String, bundle: PrekeyBundleResponse) {
        val address = SignalProtocolAddress(recipientUserId, DEVICE_ID)

        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            DEVICE_ID,
            bundle.oneTimePrekeyId ?: 0,
            if (bundle.oneTimePrekey != null)
                Curve.decodePoint(fromBase64(bundle.oneTimePrekey), 0)
            else
                null,
            bundle.signedPrekeyId,
            Curve.decodePoint(fromBase64(bundle.signedPrekey), 0),
            fromBase64(bundle.signedPrekeySig),
            IdentityKey(fromBase64(bundle.identityKey), 0)
        )

        SessionBuilder(store, address).process(preKeyBundle)
    }

    /**
     * Шифрует plaintext для собеседника.
     * Возвращает (serialized ciphertext, message_type):
     *   type = 3 (PREKEY_TYPE) для первого сообщения,
     *   type = 2 (WHISPER_TYPE) для последующих.
     */
    fun encrypt(recipientUserId: String, plaintext: String): Pair<ByteArray, Int> {
        val address = SignalProtocolAddress(recipientUserId, DEVICE_ID)
        val encrypted: CiphertextMessage =
            SessionCipher(store, address).encrypt(plaintext.toByteArray(Charsets.UTF_8))
        return encrypted.serialize() to encrypted.type
    }

    /**
     * Расшифровывает сообщение от собеседника.
     * Double Ratchet обновляется автоматически после каждого вызова.
     */
    fun decrypt(senderUserId: String, ciphertext: ByteArray, messageType: Int): String {
        val address = SignalProtocolAddress(senderUserId, DEVICE_ID)
        val cipher = SessionCipher(store, address)

        val plaintext = when (messageType) {
            CiphertextMessage.PREKEY_TYPE   -> cipher.decrypt(PreKeySignalMessage(ciphertext))
            CiphertextMessage.WHISPER_TYPE  -> cipher.decrypt(SignalMessage(ciphertext))
            else -> throw IllegalArgumentException("Unknown Signal message type: $messageType")
        }

        return String(plaintext, Charsets.UTF_8)
    }

    fun hasSessionWith(userId: String): Boolean =
        store.containsSession(SignalProtocolAddress(userId, DEVICE_ID))

    fun getIdentityPublicKey(): ByteArray =
        store.getIdentityKeyPair().publicKey.serialize()

    /**
     * Визуальный fingerprint публичного ключа для голосовой верификации.
     * Формат: "ABCD EFGH IJKL MNOP" (SHA-256, первые 6 байт, base32-подобная кодировка)
     */
    // TODO:
    fun calculateFingerprint(publicKeyBytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val encoded = Base64.encodeToString(hash.copyOf(6), Base64.NO_PADDING or Base64.NO_WRAP)
            .replace('+', 'A').replace('/', 'B').take(20)
        return encoded.chunked(4).joinToString(" ")
    }

    fun toBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromBase64(s: String): ByteArray =
        Base64.decode(s, Base64.NO_WRAP)
}
