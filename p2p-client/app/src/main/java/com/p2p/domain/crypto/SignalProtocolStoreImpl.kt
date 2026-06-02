package com.p2p.domain.crypto

import com.p2p.data.local.dao.*
import com.p2p.data.local.entities.*
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed реализация SignalProtocolStore.
 *
 * Все методы синхронные (интерфейс libsignal не поддерживает suspend).
 * Вызываются только из фоновых потоков, поэтому это безопасно
 */
@Singleton
class SignalProtocolStoreImpl @Inject constructor(
    private val ownIdentityDao: SignalOwnIdentityDao,
    private val trustedIdentityDao: SignalTrustedIdentityDao,
    private val preKeyDao: SignalPreKeyDao,
    private val signedPreKeyDao: SignalSignedPreKeyDao,
    private val sessionDao: SignalSessionDao
) : SignalProtocolStore {

    // IdentityKeyStore

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val stored = ownIdentityDao.get()
            ?: error("Signal identity key not initialized. Call CryptoManager.initializeKeys() first.")
        return IdentityKeyPair(stored.identityKeyPairBytes)
    }

    override fun getLocalRegistrationId(): Int {
        return ownIdentityDao.get()?.registrationId
            ?: error("Signal identity key not initialized.")
    }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val key = address.toKey()
        val existing = trustedIdentityDao.get(key)
        trustedIdentityDao.save(SignalTrustedIdentity(key, identityKey.serialize()))
        return if (existing != null &&
            !existing.identityKeyBytes.contentEquals(identityKey.serialize())
        ) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val key = address.toKey()
        val stored = trustedIdentityDao.get(key) ?: return true // TOFU
        return stored.identityKeyBytes.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val stored = trustedIdentityDao.get(address.toKey()) ?: return null
        return IdentityKey(stored.identityKeyBytes, 0)
    }

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        return preKeyDao.get(preKeyId)?.let { PreKeyRecord(it.serializedRecord) }
            ?: throw InvalidKeyIdException("PreKey $preKeyId not found")
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        preKeyDao.save(SignalPreKeyEntity(preKeyId, record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return preKeyDao.get(preKeyId) != null
    }

    override fun removePreKey(preKeyId: Int) {
        preKeyDao.delete(preKeyId)
    }

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        return signedPreKeyDao.get(signedPreKeyId)?.let { SignedPreKeyRecord(it.serializedRecord) }
            ?: throw InvalidKeyIdException("SignedPreKey $signedPreKeyId not found")
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> {
        return signedPreKeyDao.getAll()
            .map { SignedPreKeyRecord(it.serializedRecord) }
            .toMutableList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signedPreKeyDao.save(SignalSignedPreKeyEntity(signedPreKeyId, record.serialize()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return signedPreKeyDao.exists(signedPreKeyId) > 0
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeyDao.delete(signedPreKeyId)
    }

    // SessionStore

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val stored = sessionDao.get(address.toKey()) ?: return SessionRecord()
        return SessionRecord(stored.serializedRecord)
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        return addresses.map { address ->
            val stored = sessionDao.get(address.toKey())
                ?: throw NoSessionException("No session for ${address.toKey()}")
            SessionRecord(stored.serializedRecord)
        }
    }

    override fun getSubDeviceSessions(name: String): MutableList<Int> {
        return sessionDao.getAddressesForName(name)
            .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
            .toMutableList()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessionDao.save(SignalSessionEntity(address.toKey(), record.serialize()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return sessionDao.exists(address.toKey()) > 0
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        sessionDao.delete(address.toKey())
    }

    override fun deleteAllSessions(name: String) {
        sessionDao.deleteAllForName(name)
    }

    // SenderKeyStore (in-memory, P2P не использует групповые ключи)

    private val senderKeyCache = ConcurrentHashMap<String, SenderKeyRecord>()

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        senderKeyCache["${sender.toKey()}:$distributionId"] = record
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord? {
        return senderKeyCache["${sender.toKey()}:$distributionId"]
    }

    // KyberPreKeyStore (заглушка, PQXDH не используется в этой версии)

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        throw InvalidKeyIdException("KyberPreKey $kyberPreKeyId not found")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = emptyList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = Unit

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = false

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) = Unit

    // Internal helpers

    fun saveOwnIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int) {
        ownIdentityDao.save(
            SignalOwnIdentity(
                identityKeyPairBytes = identityKeyPair.serialize(),
                registrationId = registrationId
            )
        )
    }

    fun isInitialized(): Boolean = ownIdentityDao.get() != null

    private fun SignalProtocolAddress.toKey(): String = "$name:$deviceId"
}
