package com.p2p.domain.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Хеширование PIN для локальной блокировки приложения
 * PIN не хранится в открытом виде: соль + SHA-256, формат "saltB64:hashB64"
 */
object PinHasher {

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = digest(pin, salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        return try {
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expected = Base64.decode(parts[1], Base64.NO_WRAP)
            digest(pin, salt).contentEquals(expected)
        } catch (e: Exception) {
            false
        }
    }

    private fun digest(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pin.toByteArray(Charsets.UTF_8))
        return md.digest()
    }
}
