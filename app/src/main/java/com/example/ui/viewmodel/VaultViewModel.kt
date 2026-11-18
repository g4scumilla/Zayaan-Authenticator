package com.example.ui.viewmodel

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PersistableBundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.crypto.Base32
import com.example.core.crypto.TotpCodeState
import com.example.core.crypto.TotpGenerator
import com.example.core.crypto.VaultCrypto
import com.example.data.local.LocalVaultStorage
import com.example.data.remote.VaultRemoteDataSource
import com.example.data.sync.AndroidConnectivityChecker
import com.example.data.sync.VaultRepository
import com.example.domain.model.OtpAuthParseResult
import com.example.domain.model.OtpAuthUriParser
import com.example.domain.model.SyncStatus
import com.example.domain.model.TotpAccount
import com.example.domain.model.TotpAlgorithm
import com.example.domain.model.VaultContent
import com.example.domain.model.VaultGateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class VaultViewModel(
    val currentUserId: String,
    private val repository: VaultRepository,
    private val connectivityObserver: AndroidConnectivityChecker? = null
) : ViewModel() {

    val gateState: StateFlow<VaultGateState> = repository.gateState
    val accounts: StateFlow<List<TotpAccount>> = repository.accounts
    val syncStatus: StateFlow<SyncStatus> = repository.syncStatus
    val lastBackupTimestampMs: StateFlow<Long?> = repository.lastBackupTimestampMs
    val backupVersion: StateFlow<Long> = repository.backupVersion
    val syncNotice: StateFlow<String?> = repository.lastSyncNotice

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _nowMillis = MutableStateFlow(System.currentTimeMillis())
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val filteredAccounts: StateFlow<List<TotpAccount>> = combine(accounts, searchQuery) { list, query ->
        val q = query.trim().lowercase()
        if (q.isEmpty()) list
        else list.filter {
            it.issuer.lowercase().contains(q) || it.accountName.lowercase().contains(q)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var clipboardClearJob: Job? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.initialize()
            }
        }
        startOtpTicker()
        networkCallback = connectivityObserver?.registerCallback(
            onAvailable = {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.onConnectivityRestored()
                }
            },
            onLost = {
                repository.onNetworkLost()
            }
        )
    }

    private fun startOtpTicker() {
        viewModelScope.launch {
            while (isActive) {
                _nowMillis.value = System.currentTimeMillis()
                val millisIntoSecond = _nowMillis.value % 1000L
                val sleepMs = (1000L - millisIntoSecond).coerceIn(100L, 1000L)
                delay(sleepMs)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearError() {
        _actionError.value = null
    }

    fun computeCodeState(account: TotpAccount, currentMillis: Long): TotpCodeState =
        TotpGenerator.computeState(account, currentMillis)

    fun setupRecoveryPassword(
        password: String,
        confirmPassword: String,
        onSuccess: () -> Unit
    ) {
        if (password.length < VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH) {
            _actionError.value = "Recovery password must be at least ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} characters."
            return
        }
        if (password != confirmPassword) {
            _actionError.value = "Recovery passwords do not match."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _actionError.value = null
            val result = withContext(Dispatchers.Default) {
                repository.createInitialVaultWithRecoveryPassword(password)
            }
            _isBusy.value = false
            result.fold(
                onSuccess = {
                    _snackbarMessages.tryEmit("Zero-knowledge vault created & backed up.")
                    onSuccess()
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Failed to initialize encrypted backup."
                }
            )
        }
    }

    fun unlockWithRecoveryPassword(
        password: String,
        onSuccess: () -> Unit
    ) {
        if (password.isEmpty()) {
            _actionError.value = "Please enter your recovery password."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _actionError.value = null
            val gate = gateState.value
            val cloudOverride = (gate as? VaultGateState.NeedsRecoveryUnlock)?.cloudVault
            val result = withContext(Dispatchers.Default) {
                repository.unlockAndRestoreWithRecoveryPassword(password, cloudOverride)
            }
            _isBusy.value = false
            result.fold(
                onSuccess = {
                    _snackbarMessages.tryEmit("Vault decrypted & restored successfully.")
                    onSuccess()
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Incorrect recovery password."
                }
            )
        }
    }

    fun changeRecoveryPassword(
        currentPassword: String,
        newPassword: String,
        confirmPassword: String,
        onSuccess: () -> Unit
    ) {
        if (newPassword.length < VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH) {
            _actionError.value = "New recovery password must be at least ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} characters."
            return
        }
        if (newPassword != confirmPassword) {
            _actionError.value = "New recovery passwords do not match."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _actionError.value = null
            val result = withContext(Dispatchers.Default) {
                repository.changeRecoveryPassword(currentPassword, newPassword)
            }
            _isBusy.value = false
            result.fold(
                onSuccess = {
                    _snackbarMessages.tryEmit("Recovery password updated & vault re-encrypted.")
                    onSuccess()
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Failed to update recovery password."
                }
            )
        }
    }

    fun addManualAccount(
        issuer: String,
        accountName: String,
        secret: String,
        algorithm: TotpAlgorithm,
        digits: Int,
        periodSeconds: Int,
        onSuccess: () -> Unit
    ) {
        val cleanSecret = Base32.normalize(secret)
        if (issuer.isBlank() && accountName.isBlank()) {
            _actionError.value = "Please enter a service name or account name."
            return
        }
        if (!Base32.isValid(cleanSecret)) {
            _actionError.value = "Invalid Base32 secret key. Check characters (A-Z, 2-7)."
            return
        }
        val account = TotpAccount(
            id = UUID.randomUUID().toString(),
            issuer = issuer.trim().ifBlank { "Authenticator" },
            accountName = accountName.trim().ifBlank { issuer.trim() },
            secretBase32 = cleanSecret,
            algorithm = algorithm,
            digits = digits,
            periodSeconds = periodSeconds
        )
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { repository.addAccount(account) }
            res.fold(
                onSuccess = {
                    _snackbarMessages.tryEmit("${account.displayIssuer()} added.")
                    onSuccess()
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Could not save account."
                }
            )
        }
    }

    fun addFromOtpAuthUri(rawUri: String, onSuccess: () -> Unit): Result<TotpAccount> {
        val parsed = OtpAuthUriParser.parse(rawUri, nextSortOrder = accounts.value.size)
        return when (parsed) {
            is OtpAuthParseResult.Error -> {
                _actionError.value = parsed.message
                Result.failure(IllegalArgumentException(parsed.message))
            }
            is OtpAuthParseResult.Success -> {
                viewModelScope.launch {
                    val res = withContext(Dispatchers.IO) { repository.addAccount(parsed.account) }
                    res.fold(
                        onSuccess = {
                            _snackbarMessages.tryEmit("${parsed.account.displayIssuer()} imported.")
                            onSuccess()
                        },
                        onFailure = { err ->
                            _actionError.value = err.message ?: "Failed to import account."
                        }
                    )
                }
                Result.success(parsed.account)
            }
        }
    }

    fun updateAccount(account: TotpAccount, onSuccess: () -> Unit) {
        if (!Base32.isValid(account.secretBase32)) {
            _actionError.value = "Invalid Base32 secret key."
            return
        }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { repository.updateAccount(account) }
            res.fold(
                onSuccess = {
                    _snackbarMessages.tryEmit("Account updated.")
                    onSuccess()
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Failed to update account."
                }
            )
        }
    }

    fun deleteAccount(accountId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { repository.deleteAccount(accountId) }
            res.fold(
                onSuccess = {
                    _snackbarMessages.tryEmit("Account deleted.")
                    onSuccess()
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Failed to delete account."
                }
            )
        }
    }

    fun moveAccount(accountId: String, moveUp: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.moveAccount(accountId, moveUp)
        }
    }

    fun triggerManualSync() {
        viewModelScope.launch {
            _isBusy.value = true
            val res = withContext(Dispatchers.IO) { repository.syncNow() }
            _isBusy.value = false
            res.fold(
                onSuccess = { _snackbarMessages.tryEmit("Cloud backup is up to date.") },
                onFailure = { err -> _snackbarMessages.tryEmit(err.message ?: "Sync failed.") }
            )
        }
    }

    fun checkCloudRestore() {
        viewModelScope.launch {
            _isBusy.value = true
            val res = withContext(Dispatchers.IO) { repository.initialize() }
            _isBusy.value = false
            res.fold(
                onSuccess = { _snackbarMessages.tryEmit("Checked cloud vault state.") },
                onFailure = { err -> _snackbarMessages.tryEmit(err.message ?: "Failed to check cloud vault.") }
            )
        }
    }

    fun copyOtpToClipboard(context: Context, code: String, issuer: String, clearAfterSeconds: Int) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("Zayan OTP", code)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        val clearHint = if (clearAfterSeconds > 0) " (auto-clears in ${clearAfterSeconds}s)" else ""
        _snackbarMessages.tryEmit("Copied $issuer code$clearHint")

        clipboardClearJob?.cancel()
        if (clearAfterSeconds > 0) {
            clipboardClearJob = viewModelScope.launch {
                delay(clearAfterSeconds * 1000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    suspend fun buildEncryptedExportJson(passphrase: String): Result<String> = withContext(Dispatchers.Default) {
        if (passphrase.length < VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH) {
            return@withContext Result.failure(
                IllegalArgumentException("Export passphrase must be at least ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} characters.")
            )
        }
        runCatching {
            VaultCrypto.encryptExportEnvelope(repository.getCurrentVaultContent(), passphrase)
        }
    }

    fun buildUnencryptedExportJson(): String {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        return json.encodeToString(repository.getCurrentVaultContent())
    }

    fun importEncryptedExport(rawJson: String, passphrase: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val content = VaultCrypto.decryptExportEnvelope(rawJson, passphrase)
                    repository.importAccounts(content.accounts).getOrThrow()
                }
            }
            _isBusy.value = false
            result.fold(
                onSuccess = { count ->
                    _snackbarMessages.tryEmit("Imported $count new account(s) from encrypted backup.")
                },
                onFailure = { err ->
                    _actionError.value = err.message ?: "Failed to decrypt backup file."
                }
            )
        }
    }

    fun hasUnsyncedChanges(): Boolean = repository.hasUnsyncedChanges()

    fun clearLocalSessionOnSignOut() {
        repository.clearLocalSessionOnSignOut()
    }

    override fun onCleared() {
        super.onCleared()
        connectivityObserver?.unregisterCallback(networkCallback)
        clipboardClearJob?.cancel()
    }

    companion object {
        fun create(context: Context, userId: String): VaultViewModel {
            val appCtx = context.applicationContext
            val checker = AndroidConnectivityChecker(appCtx)
            return VaultViewModel(
                currentUserId = userId,
                repository = VaultRepository(appCtx, userId),
                connectivityObserver = checker
            )
        }

        fun createWithDependencies(
            userId: String,
            localStorage: LocalVaultStorage,
            remoteDataSource: VaultRemoteDataSource,
            isOnline: () -> Boolean
        ): VaultViewModel {
            val repo = VaultRepository(
                userId = userId,
                localStorage = localStorage,
                remoteDataSource = remoteDataSource,
                connectivityChecker = object : com.example.data.sync.ConnectivityChecker {
                    override fun isOnline(): Boolean = isOnline()
                }
            )
            return VaultViewModel(userId, repo, null)
        }
    }
}
