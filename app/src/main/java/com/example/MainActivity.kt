package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.auth.GoogleAuthManager
import com.example.domain.model.VaultGateState
import com.example.ui.components.BiometricAuthHelper
import com.example.ui.components.BiometricLockOverlay
import com.example.ui.components.ScreenSecurityEffect
import com.example.ui.navigation.ZayanRoute
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.AccountDetailScreen
import com.example.ui.screens.AddAccountScreen
import com.example.ui.screens.AppSettingsScreen
import com.example.ui.screens.BackupSyncScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.GoogleSignInScreen
import com.example.ui.screens.ManualEntryScreen
import com.example.ui.screens.QrScannerScreen
import com.example.ui.screens.RecoveryPasswordSetupScreen
import com.example.ui.screens.RecoveryUnlockScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.WelcomeScreen
import com.example.ui.theme.ZayanAuthenticatorTheme
import com.example.ui.viewmodel.VaultViewModel
import com.example.ui.viewmodel.ZayanAppViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appViewModel: ZayanAppViewModel = viewModel(
                factory = ZayanAppViewModel.Factory(applicationContext)
            )
            val settings by appViewModel.settings.collectAsStateWithLifecycle()

            ZayanAuthenticatorTheme(themeMode = settings.themeMode) {
                ScreenSecurityEffect(enabled = settings.screenCaptureProtection)
                ZayanRootApp(appViewModel = appViewModel, activity = this)
            }
        }
    }
}

@Composable
private fun ZayanRootApp(
    appViewModel: ZayanAppViewModel,
    activity: FragmentActivity
) {
    val currentUser by GoogleAuthManager.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
    val splashFinished by appViewModel.splashFinished.collectAsStateWithLifecycle()
    val hasSeenWelcome by appViewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val isAppLocked by appViewModel.isAppLocked.collectAsStateWithLifecycle()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> appViewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> appViewModel.onAppForegrounded()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!splashFinished || hasSeenWelcome == null) {
        SplashScreen(onFinished = { appViewModel.markSplashDone() })
        return
    }

    val user = currentUser
    if (user == null) {
        if (hasSeenWelcome == false) {
            WelcomeScreen(onGetStarted = { appViewModel.completeWelcome() })
        } else {
            GoogleSignInScreen(onAuthSuccess = { /* AuthStateFlow transitions automatically */ })
        }
        return
    }

    // Authenticated session keyed strictly by user.uid
    key(user.uid) {
        AuthenticatedVaultHost(
            user = user,
            appViewModel = appViewModel,
            isAppLocked = isAppLocked,
            biometricEnabled = settings.biometricLockEnabled,
            activity = activity
        )
    }
}

@Composable
private fun AuthenticatedVaultHost(
    user: FirebaseUser,
    appViewModel: ZayanAppViewModel,
    isAppLocked: Boolean,
    biometricEnabled: Boolean,
    activity: FragmentActivity
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val vaultViewModel: VaultViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                VaultViewModel.create(context.applicationContext, user.uid)
            }
        }
    )

    val gateState by vaultViewModel.gateState.collectAsStateWithLifecycle()
    val accounts by vaultViewModel.accounts.collectAsStateWithLifecycle()
    val filteredAccounts by vaultViewModel.filteredAccounts.collectAsStateWithLifecycle()
    val searchQuery by vaultViewModel.searchQuery.collectAsStateWithLifecycle()
    val nowMillis by vaultViewModel.nowMillis.collectAsStateWithLifecycle()
    val syncStatus by vaultViewModel.syncStatus.collectAsStateWithLifecycle()
    val lastBackupTimestampMs by vaultViewModel.lastBackupTimestampMs.collectAsStateWithLifecycle()
    val backupVersion by vaultViewModel.backupVersion.collectAsStateWithLifecycle()
    val syncNotice by vaultViewModel.syncNotice.collectAsStateWithLifecycle()
    val isBusy by vaultViewModel.isBusy.collectAsStateWithLifecycle()
    val actionError by vaultViewModel.actionError.collectAsStateWithLifecycle()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vaultViewModel) {
        vaultViewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var biometricError by remember { mutableStateOf<String?>(null) }

    val performSignOut: () -> Unit = {
        GoogleAuthManager.signOut(
            credentialManager = credentialManager,
            scope = scope,
            onBeforeAuthSignOut = {
                vaultViewModel.clearLocalSessionOnSignOut()
                appViewModel.unlockApp()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val gate = gateState) {
            VaultGateState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            VaultGateState.NeedsRecoverySetup -> {
                RecoveryPasswordSetupScreen(
                    userEmail = user.email,
                    isBusy = isBusy,
                    errorMessage = actionError,
                    onCreateVault = { pwd, confirm ->
                        vaultViewModel.setupRecoveryPassword(pwd, confirm) {}
                    },
                    onSignOut = performSignOut
                )
            }
            is VaultGateState.NeedsRecoveryUnlock -> {
                RecoveryUnlockScreen(
                    userEmail = user.email,
                    cloudVault = gate.cloudVault,
                    isBusy = isBusy,
                    errorMessage = actionError,
                    onUnlockVault = { pwd ->
                        vaultViewModel.unlockWithRecoveryPassword(pwd) {}
                    },
                    onSignOut = performSignOut
                )
            }
            VaultGateState.Ready -> {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = ZayanRoute.Dashboard
                ) {
                    composable<ZayanRoute.Dashboard> {
                        DashboardScreen(
                            accounts = filteredAccounts,
                            searchQuery = searchQuery,
                            nowMillis = nowMillis,
                            syncStatus = syncStatus,
                            syncNotice = syncNotice,
                            settings = settings,
                            snackbarHostState = snackbarHostState,
                            onSearchQueryChange = vaultViewModel::updateSearchQuery,
                            onCopyCode = { acc, code ->
                                vaultViewModel.copyOtpToClipboard(
                                    context = context,
                                    code = code,
                                    issuer = acc.displayIssuer(),
                                    clearAfterSeconds = settings.clipboardClearSeconds
                                )
                            },
                            onAccountClick = { acc ->
                                navController.navigate(ZayanRoute.AccountDetail(acc.id))
                            },
                            onMoveAccount = vaultViewModel::moveAccount,
                            onAddAccountClick = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.AddAccount)
                            },
                            onQuickScanClick = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.QrScanner)
                            },
                            onSyncStatusClick = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.BackupSync)
                            },
                            onSettingsClick = {
                                navController.navigate(ZayanRoute.AppSettings)
                            }
                        )
                    }

                    composable<ZayanRoute.AddAccount> {
                        AddAccountScreen(
                            errorMessage = actionError,
                            onBack = { navController.popBackStack() },
                            onScanQrClick = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.QrScanner)
                            },
                            onManualEntryClick = { prefill ->
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.ManualEntry(prefill))
                            },
                            onImportUri = { uri ->
                                vaultViewModel.addFromOtpAuthUri(uri) {
                                    navController.popBackStack(ZayanRoute.Dashboard, inclusive = false)
                                }
                            }
                        )
                    }

                    composable<ZayanRoute.QrScanner> {
                        QrScannerScreen(
                            scanError = actionError,
                            onBack = { navController.popBackStack() },
                            onManualEntryFallback = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.ManualEntry("")) {
                                    popUpTo(ZayanRoute.QrScanner) { inclusive = true }
                                }
                            },
                            onQrCodeDetected = { raw ->
                                vaultViewModel.addFromOtpAuthUri(raw) {
                                    navController.popBackStack(ZayanRoute.Dashboard, inclusive = false)
                                }.isSuccess
                            }
                        )
                    }

                    composable<ZayanRoute.ManualEntry> { entry ->
                        val route = entry.toRoute<ZayanRoute.ManualEntry>()
                        ManualEntryScreen(
                            prefillIssuer = route.prefillIssuer,
                            nowMillis = nowMillis,
                            errorMessage = actionError,
                            onBack = { navController.popBackStack() },
                            onSaveAccount = { issuer, name, secret, alg, digits, period ->
                                vaultViewModel.addManualAccount(
                                    issuer = issuer,
                                    accountName = name,
                                    secret = secret,
                                    algorithm = alg,
                                    digits = digits,
                                    periodSeconds = period
                                ) {
                                    navController.popBackStack(ZayanRoute.Dashboard, inclusive = false)
                                }
                            }
                        )
                    }

                    composable<ZayanRoute.AccountDetail> { entry ->
                        val route = entry.toRoute<ZayanRoute.AccountDetail>()
                        val index = accounts.indexOfFirst { it.id == route.accountId }
                        val account = accounts.getOrNull(index)
                        AccountDetailScreen(
                            account = account,
                            nowMillis = nowMillis,
                            groupCodeDigits = settings.groupCodeDigits,
                            canMoveUp = index > 0,
                            canMoveDown = index in 0 until accounts.lastIndex,
                            onBack = { navController.popBackStack() },
                            onCopyCode = { acc, code ->
                                vaultViewModel.copyOtpToClipboard(
                                    context = context,
                                    code = code,
                                    issuer = acc.displayIssuer(),
                                    clearAfterSeconds = settings.clipboardClearSeconds
                                )
                            },
                            onUpdateAccount = { updated ->
                                vaultViewModel.updateAccount(updated) {
                                    navController.popBackStack()
                                }
                            },
                            onDeleteAccount = { id ->
                                vaultViewModel.deleteAccount(id) {
                                    navController.popBackStack()
                                }
                            },
                            onMoveAccount = vaultViewModel::moveAccount
                        )
                    }

                    composable<ZayanRoute.BackupSync> {
                        BackupSyncScreen(
                            userEmail = user.email,
                            syncStatus = syncStatus,
                            lastBackupTimestampMs = lastBackupTimestampMs,
                            backupVersion = backupVersion,
                            accountCount = accounts.size,
                            isBusy = isBusy,
                            errorMessage = actionError,
                            onBack = { navController.popBackStack() },
                            onManualBackup = vaultViewModel::triggerManualSync,
                            onCheckCloudRestore = vaultViewModel::checkCloudRestore,
                            onChangeRecoveryPassword = { cur, newPwd, confirm, done ->
                                vaultViewModel.changeRecoveryPassword(cur, newPwd, confirm, done)
                            }
                        )
                    }

                    composable<ZayanRoute.AppSettings> {
                        AppSettingsScreen(
                            userEmail = user.email,
                            userDisplayName = user.displayName,
                            syncStatus = syncStatus,
                            hasUnsyncedChanges = vaultViewModel.hasUnsyncedChanges(),
                            settings = settings,
                            onBack = { navController.popBackStack() },
                            onThemeChange = appViewModel::setThemeMode,
                            onGroupDigitsChange = appViewModel::setGroupCodeDigits,
                            onShowNextCodeChange = appViewModel::setShowNextCode,
                            onHapticFeedbackChange = appViewModel::setHapticFeedback,
                            onOpenBackupSync = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.BackupSync)
                            },
                            onOpenSecuritySettings = {
                                vaultViewModel.clearError()
                                navController.navigate(ZayanRoute.SecuritySettings)
                            },
                            onOpenAbout = {
                                navController.navigate(ZayanRoute.About)
                            },
                            onSignOutConfirmed = performSignOut
                        )
                    }

                    composable<ZayanRoute.SecuritySettings> {
                        SecuritySettingsScreen(
                            settings = settings,
                            snackbarHostState = snackbarHostState,
                            onBack = { navController.popBackStack() },
                            onToggleBiometric = appViewModel::setBiometricLockEnabled,
                            onSelectAutoLock = appViewModel::setAutoLockTimeoutSeconds,
                            onToggleScreenProtection = appViewModel::setScreenCaptureProtection,
                            onSelectClipboardClear = appViewModel::setClipboardClearSeconds,
                            onBuildEncryptedExport = vaultViewModel::buildEncryptedExportJson,
                            onBuildUnencryptedExport = vaultViewModel::buildUnencryptedExportJson,
                            onImportEncryptedFile = vaultViewModel::importEncryptedExport,
                            onNotify = { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }

                    composable<ZayanRoute.About> {
                        AboutScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }

        if (isAppLocked && biometricEnabled) {
            LaunchedEffect(Unit) {
                BiometricAuthHelper.authenticate(
                    activity = activity,
                    onSuccess = {
                        biometricError = null
                        appViewModel.unlockApp()
                    },
                    onError = { msg -> biometricError = msg }
                )
            }
            BiometricLockOverlay(
                onUnlockClick = {
                    BiometricAuthHelper.authenticate(
                        activity = activity,
                        onSuccess = {
                            biometricError = null
                            appViewModel.unlockApp()
                        },
                        onError = { msg -> biometricError = msg }
                    )
                },
                errorMessage = biometricError
            )
        }
    }
}
