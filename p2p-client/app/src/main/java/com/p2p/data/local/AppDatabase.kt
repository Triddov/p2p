package com.p2p.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.p2p.data.local.dao.*
import com.p2p.data.local.entities.*

@Database(
    entities = [
        LocalProfile::class,
        VerifiedContact::class,
        Chat::class,
        Message::class,
        SignalOwnIdentity::class,
        SignalTrustedIdentity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalSessionEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun signalOwnIdentityDao(): SignalOwnIdentityDao
    abstract fun signalTrustedIdentityDao(): SignalTrustedIdentityDao
    abstract fun signalPreKeyDao(): SignalPreKeyDao
    abstract fun signalSignedPreKeyDao(): SignalSignedPreKeyDao
    abstract fun signalSessionDao(): SignalSessionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE local_profile ADD COLUMN refreshToken TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // удаление старой таблицы криптосессий
                database.execSQL("DROP TABLE IF EXISTS sessions")

                // Signal Protocol: собственная identity
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS signal_own_identity (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        identityKeyPairBytes BLOB NOT NULL,
                        registrationId INTEGER NOT NULL
                    )"""
                )
                // Trusted identities контактов (TOFU)
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS signal_trusted_identities (
                        address TEXT PRIMARY KEY NOT NULL,
                        identityKeyBytes BLOB NOT NULL
                    )"""
                )
                // Наши one-time prekeys
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS signal_prekeys (
                        preKeyId INTEGER PRIMARY KEY NOT NULL,
                        serializedRecord BLOB NOT NULL
                    )"""
                )
                // Наш signed prekey
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS signal_signed_prekeys (
                        signedPreKeyId INTEGER PRIMARY KEY NOT NULL,
                        serializedRecord BLOB NOT NULL
                    )"""
                )
                // Double Ratchet сессии
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS signal_sessions (
                        address TEXT PRIMARY KEY NOT NULL,
                        serializedRecord BLOB NOT NULL
                    )"""
                )
            }
        }
    }
}

class Converters {
    @androidx.room.TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @androidx.room.TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @androidx.room.TypeConverter
    fun fromVerificationMethod(method: VerificationMethod): String = method.storageValue

    @androidx.room.TypeConverter
    fun toVerificationMethod(value: String): VerificationMethod =
        VerificationMethod.fromStorage(value)
}
