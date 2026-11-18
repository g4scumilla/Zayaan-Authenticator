package com.example.domain.model

import kotlinx.serialization.Serializable

enum class SyncStatus(val label: String) {
    SYNCED("Synced"),
    SYNCING("Syncing"),
    OFFLINE("Offline"),
    BACKUP_FAILED("Backup failed"),
    RECOVERY_REQUIRED("Recovery required"),
    NOT_CONFIGURED("Setup required")
}

enum class AppThemeMode(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System")
}

@Serializable
data class LocalVaultEnvelope(
    val userId: String,
    val deviceId: String,
    val vekBase64: String,
    val vaultContent: VaultContent,
    val lastSyncedVersion: Long = 0L,
    val pendingSync: Boolean = false,
    val lastBackupTimestampMs: Long? = null,
    val kdfSaltBase64: String = "",
    val kdfIterations: Int = 600_000,
    val wrappedVekBase64: String = "",
    val wrapIvBase64: String = ""
)

data class CloudVaultDocument(
    val userId: String,
    val encryptedPayload: String,
    val vaultIv: String,
    val wrappedVek: String,
    val wrapIv: String,
    val kdfAlgorithm: String,
    val kdfIterations: Long,
    val kdfSalt: String,
    val encryptionVersion: Long,
    val schemaVersion: Long,
    val backupVersion: Long,
    val lastDeviceId: String,
    val createdAtMs: Long?,
    val updatedAtMs: Long?
)

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val biometricLockEnabled: Boolean = false,
    val autoLockTimeoutSeconds: Long = 60L, // 0 = immediate, -1 = never
    val screenCaptureProtection: Boolean = true,
    val groupCodeDigits: Boolean = true,
    val showNextCode: Boolean = false,
    val hapticFeedback: Boolean = true,
    val clipboardClearSeconds: Int = 30
)

sealed interface VaultGateState {
    data object Loading : VaultGateState
    data object NeedsRecoverySetup : VaultGateState
    data class NeedsRecoveryUnlock(val cloudVault: CloudVaultDocument) : VaultGateState
    data object Ready : VaultGateState
}
