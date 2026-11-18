package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.core.crypto.InvalidRecoveryPasswordException
import com.example.core.crypto.VaultCrypto
import com.example.data.local.LocalVaultStorage
import com.example.data.remote.FirestoreVaultRemoteDataSource
import com.example.data.remote.VaultRemoteDataSource
import com.example.data.remote.VersionConflictException
import com.example.domain.model.CloudVaultDocument
import com.example.domain.model.DeletedAccountRecord
import com.example.domain.model.LocalVaultEnvelope
import com.example.domain.model.SyncStatus
import com.example.domain.model.TotpAccount
import com.example.domain.model.VaultContent
import com.example.domain.model.VaultGateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.UUID

interface ConnectivityChecker {
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker(context: Context) : ConnectivityChecker {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isOnline(): Boolean {
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun registerCallback(onAvailable: () -> Unit, onLost: () -> Unit): ConnectivityManager.NetworkCallback? {
        val manager = cm ?: return null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailable()
            override fun onLost(network: Network) = onLost()
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            callback
        } catch (_: Exception) {
            null
        }
    }

    fun unregisterCallback(callback: ConnectivityManager.NetworkCallback?) {
        if (callback != null) {
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }
}

class VaultRepository(
    private val userId: String,
    private val localStorage: LocalVaultStorage,
    private val remoteDataSource: VaultRemoteDataSource,
    private val connectivityChecker: ConnectivityChecker
) {
    constructor(context: Context, userId: String) : this(
        userId = userId,
        localStorage = LocalVaultStorage(context),
        remoteDataSource = FirestoreVaultRemoteDataSource(context),
        connectivityChecker = AndroidConnectivityChecker(context)
    )

    private val syncMutex = Mutex()

    private val _gateState = MutableStateFlow<VaultGateState>(VaultGateState.Loading)
    val gateState: StateFlow<VaultGateState> = _gateState.asStateFlow()

    private val _accounts = MutableStateFlow<List<TotpAccount>>(emptyList())
    val accounts: StateFlow<List<TotpAccount>> = _accounts.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.SYNCED)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastBackupTimestampMs = MutableStateFlow<Long?>(null)
    val lastBackupTimestampMs: StateFlow<Long?> = _lastBackupTimestampMs.asStateFlow()

    private val _backupVersion = MutableStateFlow(0L)
    val backupVersion: StateFlow<Long> = _backupVersion.asStateFlow()

    private val _lastSyncNotice = MutableStateFlow<String?>(null)
    val lastSyncNotice: StateFlow<String?> = _lastSyncNotice.asStateFlow()

    private var cachedEnvelope: LocalVaultEnvelope? = null

    suspend fun initialize(): Result<Unit> = syncMutex.withLock {
        val local = localStorage.load(userId)
        if (local != null) {
            cachedEnvelope = local
            _accounts.value = local.vaultContent.accounts.sortedBy { it.sortOrder }
            _lastBackupTimestampMs.value = local.lastBackupTimestampMs
            _backupVersion.value = local.lastSyncedVersion
            _gateState.value = VaultGateState.Ready

            if (!connectivityChecker.isOnline()) {
                _syncStatus.value = SyncStatus.OFFLINE
                return Result.success(Unit)
            }
            return syncLocked(allowSilentMerge = true)
        }

        // Fresh install / new device / signed out cache: check Firestore for existing encrypted backup
        if (!connectivityChecker.isOnline()) {
            _syncStatus.value = SyncStatus.OFFLINE
            _gateState.value = VaultGateState.NeedsRecoverySetup
            return Result.success(Unit)
        }

        val cloudResult = remoteDataSource.fetchCloudVault(userId)
        return cloudResult.fold(
            onSuccess = { remote ->
                if (remote != null) {
                    _lastBackupTimestampMs.value = remote.updatedAtMs
                    _backupVersion.value = remote.backupVersion
                    _syncStatus.value = SyncStatus.RECOVERY_REQUIRED
                    _gateState.value = VaultGateState.NeedsRecoveryUnlock(remote)
                } else {
                    _syncStatus.value = SyncStatus.NOT_CONFIGURED
                    _gateState.value = VaultGateState.NeedsRecoverySetup
                }
                Result.success(Unit)
            },
            onFailure = { error ->
                _syncStatus.value = SyncStatus.BACKUP_FAILED
                _gateState.value = VaultGateState.NeedsRecoverySetup
                Result.failure(error)
            }
        )
    }

    suspend fun createInitialVaultWithRecoveryPassword(recoveryPassword: String): Result<Unit> = syncMutex.withLock {
        if (recoveryPassword.length < VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Recovery password must be at least ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} characters.")
            )
        }

        // Guard: check if a cloud vault already exists so we never overwrite an existing backup!
        if (connectivityChecker.isOnline()) {
            val remoteCheck = remoteDataSource.fetchCloudVault(userId).getOrNull()
            if (remoteCheck != null) {
                _syncStatus.value = SyncStatus.RECOVERY_REQUIRED
                _gateState.value = VaultGateState.NeedsRecoveryUnlock(remoteCheck)
                return Result.failure(
                    IllegalStateException("An encrypted cloud backup already exists for this Google account. Please recover it instead.")
                )
            }
        }

        val vekBytes = VaultCrypto.generateVaultKey()
        val vekBase64 = Base64.getEncoder().encodeToString(vekBytes)
        val wrapped = VaultCrypto.wrapVek(vekBytes, recoveryPassword, userId)
        val deviceId = UUID.randomUUID().toString()
        val emptyContent = VaultContent(schemaVersion = 1, accounts = emptyList(), deletedAccounts = emptyList())

        val initialVersion = 1L
        val encrypted = VaultCrypto.encryptVault(emptyContent, vekBytes, userId, initialVersion)

        val envelope = LocalVaultEnvelope(
            userId = userId,
            deviceId = deviceId,
            vekBase64 = vekBase64,
            vaultContent = emptyContent,
            lastSyncedVersion = 0L,
            pendingSync = true,
            lastBackupTimestampMs = null,
            kdfSaltBase64 = wrapped.kdfSaltBase64,
            kdfIterations = wrapped.kdfIterations,
            wrappedVekBase64 = wrapped.wrappedVekBase64,
            wrapIvBase64 = wrapped.wrapIvBase64
        )
        localStorage.save(envelope)
        cachedEnvelope = envelope
        _accounts.value = emptyList()

        if (!connectivityChecker.isOnline()) {
            _syncStatus.value = SyncStatus.OFFLINE
            _gateState.value = VaultGateState.Ready
            return Result.success(Unit)
        }

        _syncStatus.value = SyncStatus.SYNCING
        val doc = CloudVaultDocument(
            userId = userId,
            encryptedPayload = encrypted.encryptedPayloadBase64,
            vaultIv = encrypted.vaultIvBase64,
            wrappedVek = wrapped.wrappedVekBase64,
            wrapIv = wrapped.wrapIvBase64,
            kdfAlgorithm = VaultCrypto.KDF_ALGORITHM,
            kdfIterations = wrapped.kdfIterations.toLong(),
            kdfSalt = wrapped.kdfSaltBase64,
            encryptionVersion = VaultCrypto.ENCRYPTION_VERSION.toLong(),
            schemaVersion = 1L,
            backupVersion = initialVersion,
            lastDeviceId = deviceId,
            createdAtMs = null,
            updatedAtMs = null
        )

        return remoteDataSource.createInitialVault(userId, doc).fold(
            onSuccess = {
                val now = System.currentTimeMillis()
                val synced = envelope.copy(
                    lastSyncedVersion = initialVersion,
                    pendingSync = false,
                    lastBackupTimestampMs = now
                )
                localStorage.save(synced)
                cachedEnvelope = synced
                _lastBackupTimestampMs.value = now
                _backupVersion.value = initialVersion
                _syncStatus.value = SyncStatus.SYNCED
                _gateState.value = VaultGateState.Ready
                Result.success(Unit)
            },
            onFailure = { error ->
                _syncStatus.value = SyncStatus.BACKUP_FAILED
                _gateState.value = VaultGateState.Ready
                Result.failure(error)
            }
        )
    }

    suspend fun unlockAndRestoreWithRecoveryPassword(
        recoveryPassword: String,
        cloudDocOverride: CloudVaultDocument? = null
    ): Result<Unit> = syncMutex.withLock {
        val cloudDoc = cloudDocOverride ?: remoteDataSource.fetchCloudVault(userId).getOrElse {
            return Result.failure(it)
        } ?: return Result.failure(IllegalStateException("No encrypted cloud backup found for this account."))

        return try {
            val vekBytes = VaultCrypto.unwrapVek(
                wrappedVekBase64 = cloudDoc.wrappedVek,
                wrapIvBase64 = cloudDoc.wrapIv,
                kdfSaltBase64 = cloudDoc.kdfSalt,
                kdfAlgorithm = cloudDoc.kdfAlgorithm,
                kdfIterations = cloudDoc.kdfIterations.toInt(),
                encryptionVersion = cloudDoc.encryptionVersion.toInt(),
                recoveryPassword = recoveryPassword,
                uid = userId
            )
            val remoteContent = VaultCrypto.decryptVault(
                encryptedPayloadBase64 = cloudDoc.encryptedPayload,
                vaultIvBase64 = cloudDoc.vaultIv,
                vekBytes = vekBytes,
                uid = userId,
                backupVersion = cloudDoc.backupVersion
            )

            val existingLocal = cachedEnvelope ?: localStorage.load(userId)
            val mergedContent = if (existingLocal != null) {
                VaultMergeEngine.merge(existingLocal.vaultContent, remoteContent).merged
            } else {
                remoteContent
            }

            val deviceId = existingLocal?.deviceId ?: UUID.randomUUID().toString()
            val now = cloudDoc.updatedAtMs ?: System.currentTimeMillis()
            val newEnvelope = LocalVaultEnvelope(
                userId = userId,
                deviceId = deviceId,
                vekBase64 = Base64.getEncoder().encodeToString(vekBytes),
                vaultContent = mergedContent,
                lastSyncedVersion = cloudDoc.backupVersion,
                pendingSync = existingLocal != null && mergedContent != remoteContent,
                lastBackupTimestampMs = now,
                kdfSaltBase64 = cloudDoc.kdfSalt,
                kdfIterations = cloudDoc.kdfIterations.toInt(),
                wrappedVekBase64 = cloudDoc.wrappedVek,
                wrapIvBase64 = cloudDoc.wrapIv
            )
            localStorage.save(newEnvelope)
            cachedEnvelope = newEnvelope
            _accounts.value = mergedContent.accounts.sortedBy { it.sortOrder }
            _lastBackupTimestampMs.value = now
            _backupVersion.value = cloudDoc.backupVersion
            _gateState.value = VaultGateState.Ready

            if (newEnvelope.pendingSync && connectivityChecker.isOnline()) {
                syncLocked(allowSilentMerge = true)
            } else {
                _syncStatus.value = if (connectivityChecker.isOnline()) SyncStatus.SYNCED else SyncStatus.OFFLINE
                Result.success(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidRecoveryPasswordException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addAccount(account: TotpAccount): Result<Unit> = mutateVault { content ->
        val nextOrder = (content.accounts.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val now = System.currentTimeMillis()
        val normalized = account.copy(
            sortOrder = nextOrder,
            createdAtMs = now,
            updatedAtMs = now
        )
        content.copy(
            accounts = content.accounts + normalized,
            deletedAccounts = content.deletedAccounts.filterNot { it.id == normalized.id }
        )
    }

    suspend fun updateAccount(updated: TotpAccount): Result<Unit> = mutateVault { content ->
        val now = System.currentTimeMillis()
        val list = content.accounts.map { existing ->
            if (existing.id == updated.id) updated.copy(updatedAtMs = now) else existing
        }
        content.copy(accounts = list)
    }

    suspend fun deleteAccount(accountId: String): Result<Unit> = mutateVault { content ->
        val now = System.currentTimeMillis()
        val remaining = content.accounts
            .filterNot { it.id == accountId }
            .sortedBy { it.sortOrder }
            .mapIndexed { idx, acc -> acc.copy(sortOrder = idx) }
        val tombstones = content.deletedAccounts.filterNot { it.id == accountId } +
            DeletedAccountRecord(id = accountId, deletedAtMs = now)
        content.copy(accounts = remaining, deletedAccounts = tombstones)
    }

    suspend fun moveAccount(accountId: String, moveUp: Boolean): Result<Unit> = mutateVault { content ->
        val sorted = content.accounts.sortedBy { it.sortOrder }.toMutableList()
        val index = sorted.indexOfFirst { it.id == accountId }
        if (index == -1) return@mutateVault content
        val targetIndex = if (moveUp) index - 1 else index + 1
        if (targetIndex !in sorted.indices) return@mutateVault content

        val now = System.currentTimeMillis()
        val item = sorted.removeAt(index)
        sorted.add(targetIndex, item)
        val reindexed = sorted.mapIndexed { idx, acc ->
            if (acc.sortOrder != idx) acc.copy(sortOrder = idx, updatedAtMs = now) else acc
        }
        content.copy(accounts = reindexed)
    }

    suspend fun importAccounts(accountsToImport: List<TotpAccount>): Result<Int> {
        var addedCount = 0
        val result = mutateVault { content ->
            val existingFingerprints = content.accounts.map {
                "${it.issuer.lowercase()}|${it.accountName.lowercase()}|${it.secretBase32}"
            }.toSet()
            var nextOrder = (content.accounts.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val now = System.currentTimeMillis()
            val novel = accountsToImport.filter {
                val fp = "${it.issuer.lowercase()}|${it.accountName.lowercase()}|${it.secretBase32}"
                fp !in existingFingerprints
            }.map {
                addedCount++
                it.copy(
                    id = UUID.randomUUID().toString(),
                    sortOrder = nextOrder++,
                    createdAtMs = now,
                    updatedAtMs = now
                )
            }
            content.copy(accounts = content.accounts + novel)
        }
        return result.map { addedCount }
    }

    suspend fun changeRecoveryPassword(
        currentRecoveryPassword: String,
        newRecoveryPassword: String
    ): Result<Unit> = syncMutex.withLock {
        if (newRecoveryPassword.length < VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH) {
            return Result.failure(
                IllegalArgumentException("New recovery password must be at least ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} characters.")
            )
        }
        val envelope = cachedEnvelope ?: localStorage.load(userId)
            ?: return Result.failure(IllegalStateException("Vault is not initialized."))

        // Verify current recovery password against local or cloud wrapped VEK before changing
        try {
            VaultCrypto.unwrapVek(
                wrappedVekBase64 = envelope.wrappedVekBase64,
                wrapIvBase64 = envelope.wrapIvBase64,
                kdfSaltBase64 = envelope.kdfSaltBase64,
                kdfAlgorithm = VaultCrypto.KDF_ALGORITHM,
                kdfIterations = envelope.kdfIterations,
                encryptionVersion = VaultCrypto.ENCRYPTION_VERSION,
                recoveryPassword = currentRecoveryPassword,
                uid = userId
            )
        } catch (e: InvalidRecoveryPasswordException) {
            return Result.failure(e)
        }

        // Rotate VEK as well for full forward cryptographic hygiene
        val newVek = VaultCrypto.generateVaultKey()
        val newWrapped = VaultCrypto.wrapVek(newVek, newRecoveryPassword, userId)
        val updatedEnvelope = envelope.copy(
            vekBase64 = Base64.getEncoder().encodeToString(newVek),
            kdfSaltBase64 = newWrapped.kdfSaltBase64,
            kdfIterations = newWrapped.kdfIterations,
            wrappedVekBase64 = newWrapped.wrappedVekBase64,
            wrapIvBase64 = newWrapped.wrapIvBase64,
            pendingSync = true
        )
        localStorage.save(updatedEnvelope)
        cachedEnvelope = updatedEnvelope

        if (!connectivityChecker.isOnline()) {
            _syncStatus.value = SyncStatus.OFFLINE
            return Result.success(Unit)
        }
        return syncLocked(allowSilentMerge = true, forceIncludeWrappedKey = true)
    }

    suspend fun syncNow(): Result<Unit> = syncMutex.withLock {
        if (!connectivityChecker.isOnline()) {
            _syncStatus.value = SyncStatus.OFFLINE
            return Result.failure(IllegalStateException("Device is offline. Changes are saved locally and encrypted."))
        }
        syncLocked(allowSilentMerge = true)
    }

    suspend fun onConnectivityRestored() {
        syncMutex.withLock {
            val env = cachedEnvelope ?: localStorage.load(userId)
            if (env != null && connectivityChecker.isOnline()) {
                syncLocked(allowSilentMerge = true)
            }
        }
    }

    fun onNetworkLost() {
        if (_gateState.value == VaultGateState.Ready) {
            _syncStatus.value = SyncStatus.OFFLINE
        }
    }

    fun getCurrentVaultContent(): VaultContent {
        return (cachedEnvelope ?: localStorage.load(userId))?.vaultContent ?: VaultContent()
    }

    fun hasUnsyncedChanges(): Boolean {
        return (cachedEnvelope ?: localStorage.load(userId))?.pendingSync == true
    }

    fun clearLocalSessionOnSignOut() {
        cachedEnvelope = null
        localStorage.clear(userId)
        _accounts.value = emptyList()
    }

    private suspend fun mutateVault(transform: (VaultContent) -> VaultContent): Result<Unit> = syncMutex.withLock {
        val current = cachedEnvelope ?: localStorage.load(userId)
            ?: return Result.failure(IllegalStateException("Vault is locked or not initialized."))

        val updatedContent = transform(current.vaultContent)
        val updatedEnvelope = current.copy(
            vaultContent = updatedContent,
            pendingSync = true
        )
        localStorage.save(updatedEnvelope)
        cachedEnvelope = updatedEnvelope
        _accounts.value = updatedContent.accounts.sortedBy { it.sortOrder }

        if (!connectivityChecker.isOnline()) {
            _syncStatus.value = SyncStatus.OFFLINE
            return Result.success(Unit)
        }
        return syncLocked(allowSilentMerge = true)
    }

    private suspend fun syncLocked(
        allowSilentMerge: Boolean,
        forceIncludeWrappedKey: Boolean = false
    ): Result<Unit> {
        var envelope = cachedEnvelope ?: localStorage.load(userId)
            ?: return Result.failure(IllegalStateException("No local vault to sync."))

        _syncStatus.value = SyncStatus.SYNCING
        _lastSyncNotice.value = null

        val remoteFetch = remoteDataSource.fetchCloudVault(userId)
        val remote = remoteFetch.getOrElse { error ->
            _syncStatus.value = if (connectivityChecker.isOnline()) SyncStatus.BACKUP_FAILED else SyncStatus.OFFLINE
            return Result.failure(error)
        }

        // Case 1: Cloud vault doesn't exist yet -> create initial cloud backup
        if (remote == null) {
            val targetVersion = maxOf(1L, envelope.lastSyncedVersion + 1L)
            val vekBytes = Base64.getDecoder().decode(envelope.vekBase64)
            val encrypted = VaultCrypto.encryptVault(envelope.vaultContent, vekBytes, userId, targetVersion)
            val doc = CloudVaultDocument(
                userId = userId,
                encryptedPayload = encrypted.encryptedPayloadBase64,
                vaultIv = encrypted.vaultIvBase64,
                wrappedVek = envelope.wrappedVekBase64,
                wrapIv = envelope.wrapIvBase64,
                kdfAlgorithm = VaultCrypto.KDF_ALGORITHM,
                kdfIterations = envelope.kdfIterations.toLong(),
                kdfSalt = envelope.kdfSaltBase64,
                encryptionVersion = VaultCrypto.ENCRYPTION_VERSION.toLong(),
                schemaVersion = 1L,
                backupVersion = targetVersion,
                lastDeviceId = envelope.deviceId,
                createdAtMs = null,
                updatedAtMs = null
            )
            return remoteDataSource.createInitialVault(userId, doc).fold(
                onSuccess = {
                    val now = System.currentTimeMillis()
                    val saved = envelope.copy(
                        lastSyncedVersion = targetVersion,
                        pendingSync = false,
                        lastBackupTimestampMs = now
                    )
                    localStorage.save(saved)
                    cachedEnvelope = saved
                    _backupVersion.value = targetVersion
                    _lastBackupTimestampMs.value = now
                    _syncStatus.value = SyncStatus.SYNCED
                    Result.success(Unit)
                },
                onFailure = { err ->
                    _syncStatus.value = SyncStatus.BACKUP_FAILED
                    Result.failure(err)
                }
            )
        }

        // Case 2: Remote is already at the exact same version and we have no pending local edits
        if (remote.backupVersion == envelope.lastSyncedVersion && !envelope.pendingSync) {
            val now = remote.updatedAtMs ?: envelope.lastBackupTimestampMs
            _lastBackupTimestampMs.value = now
            _backupVersion.value = remote.backupVersion
            _syncStatus.value = SyncStatus.SYNCED
            return Result.success(Unit)
        }

        // Case 3: Remote has a newer version OR local has pending changes -> decrypt remote & merge safely
        val localVek = Base64.getDecoder().decode(envelope.vekBase64)
        var mergedContent = envelope.vaultContent
        var conflictNotice: String? = null

        if (remote.backupVersion > envelope.lastSyncedVersion) {
            if (!allowSilentMerge) {
                _syncStatus.value = SyncStatus.RECOVERY_REQUIRED
                return Result.failure(VersionConflictException(remote))
            }
            val remoteContent = try {
                VaultCrypto.decryptVault(
                    encryptedPayloadBase64 = remote.encryptedPayload,
                    vaultIvBase64 = remote.vaultIv,
                    vekBytes = localVek,
                    uid = userId,
                    backupVersion = remote.backupVersion
                )
            } catch (e: InvalidRecoveryPasswordException) {
                // Another device rotated the recovery password / VEK; never overwrite remote!
                _syncStatus.value = SyncStatus.RECOVERY_REQUIRED
                _gateState.value = VaultGateState.NeedsRecoveryUnlock(remote)
                return Result.failure(e)
            }

            val mergeResult = VaultMergeEngine.merge(envelope.vaultContent, remoteContent)
            mergedContent = mergeResult.merged
            if (mergeResult.conflictsResolvedCount > 0) {
                conflictNotice = "Merged ${mergeResult.conflictsResolvedCount} multi-device change(s) safely."
            }

            // If remote was ahead and local had NO pending edits (or merged == remoteContent),
            // just accept remote state locally without an unnecessary write!
            if (!envelope.pendingSync || mergedContent == remoteContent) {
                val now = remote.updatedAtMs ?: System.currentTimeMillis()
                val updated = envelope.copy(
                    vaultContent = mergedContent,
                    lastSyncedVersion = remote.backupVersion,
                    pendingSync = false,
                    lastBackupTimestampMs = now
                )
                localStorage.save(updated)
                cachedEnvelope = updated
                _accounts.value = mergedContent.accounts.sortedBy { it.sortOrder }
                _lastBackupTimestampMs.value = now
                _backupVersion.value = remote.backupVersion
                _lastSyncNotice.value = conflictNotice
                _syncStatus.value = SyncStatus.SYNCED
                return Result.success(Unit)
            }
        }

        // Upload merged/pending local changes with optimistic concurrency check (retry once on race)
        var expectedVersion = remote.backupVersion
        repeat(2) { attempt ->
            val nextVersion = expectedVersion + 1L
            val encrypted = VaultCrypto.encryptVault(mergedContent, localVek, userId, nextVersion)
            val includeWrapped = forceIncludeWrappedKey ||
                envelope.wrappedVekBase64 != remote.wrappedVek ||
                envelope.kdfSaltBase64 != remote.kdfSalt

            val newDoc = CloudVaultDocument(
                userId = userId,
                encryptedPayload = encrypted.encryptedPayloadBase64,
                vaultIv = encrypted.vaultIvBase64,
                wrappedVek = envelope.wrappedVekBase64,
                wrapIv = envelope.wrapIvBase64,
                kdfAlgorithm = VaultCrypto.KDF_ALGORITHM,
                kdfIterations = envelope.kdfIterations.toLong(),
                kdfSalt = envelope.kdfSaltBase64,
                encryptionVersion = VaultCrypto.ENCRYPTION_VERSION.toLong(),
                schemaVersion = 1L,
                backupVersion = nextVersion,
                lastDeviceId = envelope.deviceId,
                createdAtMs = remote.createdAtMs,
                updatedAtMs = null
            )

            val txResult = remoteDataSource.updateVaultOptimistically(
                userId = userId,
                expectedRemoteVersion = expectedVersion,
                doc = newDoc,
                includeWrappedKey = includeWrapped
            )

            if (txResult.isSuccess) {
                val now = System.currentTimeMillis()
                val saved = envelope.copy(
                    vaultContent = mergedContent,
                    lastSyncedVersion = nextVersion,
                    pendingSync = false,
                    lastBackupTimestampMs = now
                )
                localStorage.save(saved)
                cachedEnvelope = saved
                _accounts.value = mergedContent.accounts.sortedBy { it.sortOrder }
                _lastBackupTimestampMs.value = now
                _backupVersion.value = nextVersion
                _lastSyncNotice.value = conflictNotice
                _syncStatus.value = SyncStatus.SYNCED
                return Result.success(Unit)
            }

            val err = txResult.exceptionOrNull()
            if (err is VersionConflictException && attempt == 0) {
                val latestRemote = err.remoteDocument
                expectedVersion = latestRemote.backupVersion
                val latestContent = try {
                    VaultCrypto.decryptVault(
                        latestRemote.encryptedPayload,
                        latestRemote.vaultIv,
                        localVek,
                        userId,
                        latestRemote.backupVersion
                    )
                } catch (decryptErr: InvalidRecoveryPasswordException) {
                    _syncStatus.value = SyncStatus.RECOVERY_REQUIRED
                    _gateState.value = VaultGateState.NeedsRecoveryUnlock(latestRemote)
                    return Result.failure(decryptErr)
                }
                val remerge = VaultMergeEngine.merge(mergedContent, latestContent)
                mergedContent = remerge.merged
                conflictNotice = "Merged concurrent multi-device changes safely."
            } else {
                _syncStatus.value = if (connectivityChecker.isOnline()) SyncStatus.BACKUP_FAILED else SyncStatus.OFFLINE
                return Result.failure(err ?: IllegalStateException("Backup sync failed."))
            }
        }

        _syncStatus.value = SyncStatus.BACKUP_FAILED
        return Result.failure(IllegalStateException("Concurrent modification prevented sync; please retry."))
    }
}
