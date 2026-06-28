package com.p2p.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Локальные настройки приложения (DataStore): тема и видимость в поиске.
 * discoverable хранится локально как зеркало; на сервер пушится отдельно.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val discoverableKey = booleanPreferencesKey("discoverable")
    private val appLockKey = booleanPreferencesKey("app_lock_enabled")
    private val pinHashKey = stringPreferencesKey("pin_hash")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val discoverable: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[discoverableKey] ?: true
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setDiscoverable(value: Boolean) {
        context.settingsDataStore.edit { it[discoverableKey] = value }
    }

    val appLockEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[appLockKey] ?: false
    }

    val pinHash: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[pinHashKey]
    }

    val notificationsEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[notificationsKey] ?: true
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[notificationsKey] = value }
    }

    suspend fun setAppLock(enabled: Boolean, pinHash: String?) {
        context.settingsDataStore.edit { prefs ->
            prefs[appLockKey] = enabled
            if (enabled && pinHash != null) {
                prefs[pinHashKey] = pinHash
            } else if (!enabled) {
                prefs.remove(pinHashKey)
            }
        }
    }
}
