package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.zayanSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "zayan_settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.zayanSettingsStore)

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val AUTO_LOCK_SECONDS = longPreferencesKey("auto_lock_seconds")
        val SCREEN_CAPTURE_PROTECTION = booleanPreferencesKey("screen_capture_protection")
        val GROUP_CODE_DIGITS = booleanPreferencesKey("group_code_digits")
        val SHOW_NEXT_CODE = booleanPreferencesKey("show_next_code")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val CLIPBOARD_CLEAR_SECONDS = intPreferencesKey("clipboard_clear_seconds")
        val HAS_SEEN_WELCOME = booleanPreferencesKey("has_seen_welcome")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val themeRaw = prefs[Keys.THEME_MODE] ?: AppThemeMode.DARK.name
        val theme = AppThemeMode.entries.firstOrNull { it.name == themeRaw } ?: AppThemeMode.DARK
        AppSettings(
            themeMode = theme,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
            autoLockTimeoutSeconds = prefs[Keys.AUTO_LOCK_SECONDS] ?: 60L,
            screenCaptureProtection = prefs[Keys.SCREEN_CAPTURE_PROTECTION] ?: true,
            groupCodeDigits = prefs[Keys.GROUP_CODE_DIGITS] ?: true,
            showNextCode = prefs[Keys.SHOW_NEXT_CODE] ?: false,
            hapticFeedback = prefs[Keys.HAPTIC_FEEDBACK] ?: true,
            clipboardClearSeconds = prefs[Keys.CLIPBOARD_CLEAR_SECONDS] ?: 30
        )
    }

    val hasSeenWelcome: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.HAS_SEEN_WELCOME] ?: false
    }

    suspend fun setHasSeenWelcome(seen: Boolean) {
        dataStore.edit { it[Keys.HAS_SEEN_WELCOME] = seen }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setAutoLockTimeoutSeconds(seconds: Long) {
        dataStore.edit { it[Keys.AUTO_LOCK_SECONDS] = seconds }
    }

    suspend fun setScreenCaptureProtection(enabled: Boolean) {
        dataStore.edit { it[Keys.SCREEN_CAPTURE_PROTECTION] = enabled }
    }

    suspend fun setGroupCodeDigits(grouped: Boolean) {
        dataStore.edit { it[Keys.GROUP_CODE_DIGITS] = grouped }
    }

    suspend fun setShowNextCode(show: Boolean) {
        dataStore.edit { it[Keys.SHOW_NEXT_CODE] = show }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        dataStore.edit { it[Keys.HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setClipboardClearSeconds(seconds: Int) {
        dataStore.edit { it[Keys.CLIPBOARD_CLEAR_SECONDS] = seconds }
    }
}
