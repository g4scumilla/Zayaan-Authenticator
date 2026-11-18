package com.example.ui.viewmodel

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.SettingsRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ZayanAppViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = AppSettings()
        )

    private val _splashFinished = MutableStateFlow(false)
    val splashFinished: StateFlow<Boolean> = _splashFinished.asStateFlow()

    private val _hasSeenWelcome = MutableStateFlow<Boolean?>(null)
    val hasSeenWelcome: StateFlow<Boolean?> = _hasSeenWelcome.asStateFlow()

    private val _isAppLocked = MutableStateFlow(false)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    private var lastBackgroundedElapsedMs: Long? = null

    init {
        viewModelScope.launch {
            val seen = settingsRepository.hasSeenWelcome.first()
            _hasSeenWelcome.value = seen
            val currentSettings = settingsRepository.settings.first()
            if (currentSettings.biometricLockEnabled) {
                _isAppLocked.value = true
            }
        }
    }

    fun markSplashDone() {
        _splashFinished.value = true
    }

    fun completeWelcome() {
        _hasSeenWelcome.value = true
        viewModelScope.launch {
            settingsRepository.setHasSeenWelcome(true)
        }
    }

    fun onAppBackgrounded() {
        if (settings.value.biometricLockEnabled && settings.value.autoLockTimeoutSeconds >= 0L) {
            lastBackgroundedElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun onAppForegrounded() {
        val current = settings.value
        if (!current.biometricLockEnabled) {
            _isAppLocked.value = false
            return
        }
        val timeoutSec = current.autoLockTimeoutSeconds
        if (timeoutSec < 0L) return
        val bgAt = lastBackgroundedElapsedMs ?: return
        val elapsedSec = (SystemClock.elapsedRealtime() - bgAt) / 1000L
        if (elapsedSec >= timeoutSec) {
            _isAppLocked.value = true
        }
        lastBackgroundedElapsedMs = null
    }

    fun unlockApp() {
        _isAppLocked.value = false
        lastBackgroundedElapsedMs = null
    }

    fun lockNow() {
        if (settings.value.biometricLockEnabled) {
            _isAppLocked.value = true
        }
    }

    fun setThemeMode(mode: AppThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }

    fun setBiometricLockEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBiometricLockEnabled(enabled)
        if (!enabled) _isAppLocked.value = false
    }

    fun setAutoLockTimeoutSeconds(seconds: Long) = viewModelScope.launch {
        settingsRepository.setAutoLockTimeoutSeconds(seconds)
    }

    fun setScreenCaptureProtection(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setScreenCaptureProtection(enabled)
    }

    fun setGroupCodeDigits(grouped: Boolean) = viewModelScope.launch {
        settingsRepository.setGroupCodeDigits(grouped)
    }

    fun setShowNextCode(show: Boolean) = viewModelScope.launch {
        settingsRepository.setShowNextCode(show)
    }

    fun setHapticFeedback(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHapticFeedback(enabled)
    }

    fun setClipboardClearSeconds(seconds: Int) = viewModelScope.launch {
        settingsRepository.setClipboardClearSeconds(seconds)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ZayanAppViewModel(SettingsRepository(context.applicationContext)) as T
        }
    }
}
