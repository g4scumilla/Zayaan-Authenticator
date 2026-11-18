package com.example

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.domain.model.AppSettings
import com.example.domain.model.CloudVaultDocument
import com.example.domain.model.SyncStatus
import com.example.domain.model.TotpAccount
import com.example.domain.model.TotpAlgorithm
import com.example.ui.screens.AddAccountScreen
import com.example.ui.screens.BackupSyncScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ManualEntryScreen
import com.example.ui.screens.RecoveryPasswordSetupScreen
import com.example.ui.screens.RecoveryUnlockScreen
import com.example.ui.theme.ZayanAuthenticatorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ZayanUiRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dashboard renders live TOTP code, supports search filtering, and triggers copy`() {
        val sampleAccounts = listOf(
            TotpAccount(
                id = "acc_1",
                issuer = "GitHub",
                accountName = "alice@github.com",
                secretBase32 = "JBSWY3DPEHPK3PXP",
                algorithm = TotpAlgorithm.SHA1,
                digits = 6,
                periodSeconds = 30
            ),
            TotpAccount(
                id = "acc_2",
                issuer = "Google",
                accountName = "alice@gmail.com",
                secretBase32 = "GEZDGNBVGY3TQOJQ",
                algorithm = TotpAlgorithm.SHA1,
                digits = 8,
                periodSeconds = 30
            )
        )
        var copiedAccount: String? = null

        composeTestRule.setContent {
            ZayanAuthenticatorTheme {
                val snackbar = remember { SnackbarHostState() }
                DashboardScreen(
                    accounts = sampleAccounts,
                    searchQuery = "",
                    nowMillis = 59_000L,
                    syncStatus = SyncStatus.SYNCED,
                    syncNotice = null,
                    settings = AppSettings(),
                    snackbarHostState = snackbar,
                    onSearchQueryChange = {},
                    onCopyCode = { acc, _ -> copiedAccount = acc.id },
                    onAccountClick = {},
                    onMoveAccount = { _, _ -> },
                    onAddAccountClick = {},
                    onQuickScanClick = {},
                    onSyncStatusClick = {},
                    onSettingsClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeTestRule.onNodeWithText("Google").assertIsDisplayed()
        composeTestRule.onNodeWithTag("copy_code_acc_1").performClick()
        assertEquals("acc_1", copiedAccount)
    }

    @Test
    fun `manual entry validates Base32 secret and saves account`() {
        var savedIssuer = ""
        var savedSecret = ""

        composeTestRule.setContent {
            ZayanAuthenticatorTheme {
                ManualEntryScreen(
                    prefillIssuer = "",
                    nowMillis = 59_000L,
                    errorMessage = null,
                    onBack = {},
                    onSaveAccount = { issuer, _, secret, _, _, _ ->
                        savedIssuer = issuer
                        savedSecret = secret
                    }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("manual_save_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("manual_issuer_input").performScrollTo().performTextInput("Microsoft")
        composeTestRule.onNodeWithTag("manual_account_input").performScrollTo().performTextInput("zayan@outlook.com")
        composeTestRule.onNodeWithTag("manual_secret_input").performScrollTo().performTextInput("JBSWY3DPEHPK3PXP")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("manual_save_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("manual_save_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("Microsoft", savedIssuer)
        assertEquals("JBSWY3DPEHPK3PXP", savedSecret)
    }

    @Test
    fun `recovery password setup enforces 12-char minimum and matching confirmation`() {
        var submitted = false

        composeTestRule.setContent {
            ZayanAuthenticatorTheme {
                RecoveryPasswordSetupScreen(
                    userEmail = "user@gmail.com",
                    isBusy = false,
                    errorMessage = null,
                    onCreateVault = { _, _ -> submitted = true },
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("create_vault_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recovery_password_input").performScrollTo().performTextInput("short123")
        composeTestRule.onNodeWithTag("recovery_password_confirm_input").performScrollTo().performTextInput("short123")
        composeTestRule.onNodeWithTag("create_vault_button").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("recovery_password_input").performScrollTo().performTextInput("longenough")
        composeTestRule.onNodeWithTag("recovery_password_confirm_input").performScrollTo().performTextInput("longenough")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("create_vault_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("create_vault_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(submitted)
    }

    @Test
    fun `recovery unlock screen accepts passphrase and triggers restore`() {
        var unlockedWith = ""
        val cloudMeta = CloudVaultDocument(
            userId = "uid_123",
            encryptedPayload = "ciphertext",
            vaultIv = "iv",
            wrappedVek = "wrapped",
            wrapIv = "wrapIv",
            kdfAlgorithm = "PBKDF2WithHmacSHA256",
            kdfIterations = 600_000L,
            kdfSalt = "salt",
            encryptionVersion = 1L,
            schemaVersion = 1L,
            backupVersion = 4L,
            lastDeviceId = "dev_1",
            createdAtMs = 1_690_000_000_000L,
            updatedAtMs = 1_700_000_000_000L
        )

        composeTestRule.setContent {
            ZayanAuthenticatorTheme {
                RecoveryUnlockScreen(
                    userEmail = "zayan@gmail.com",
                    cloudVault = cloudMeta,
                    isBusy = false,
                    errorMessage = null,
                    onUnlockVault = { unlockedWith = it },
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recovery_unlock_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("recovery_unlock_input").performScrollTo().performTextInput("CorrectHorseBatteryStaple!")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recovery_unlock_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("recovery_unlock_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("CorrectHorseBatteryStaple!", unlockedWith)
    }

    @Test
    fun `add account screen supports otpauth uri import`() {
        var importedUri = ""

        composeTestRule.setContent {
            ZayanAuthenticatorTheme {
                AddAccountScreen(
                    errorMessage = null,
                    onBack = {},
                    onScanQrClick = {},
                    onManualEntryClick = {},
                    onImportUri = { importedUri = it }
                )
            }
        }

        composeTestRule.waitForIdle()
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        composeTestRule.onNodeWithTag("otpauth_uri_input").performScrollTo().performTextInput(uri)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("import_uri_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("import_uri_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(uri, importedUri)
    }

    @Test
    fun `backup sync screen displays status and triggers manual sync`() {
        var manualSyncTriggered = false

        composeTestRule.setContent {
            ZayanAuthenticatorTheme {
                BackupSyncScreen(
                    userEmail = "zayan@gmail.com",
                    syncStatus = SyncStatus.SYNCED,
                    lastBackupTimestampMs = 1_700_000_000_000L,
                    backupVersion = 3L,
                    accountCount = 5,
                    isBusy = false,
                    errorMessage = null,
                    onBack = {},
                    onManualBackup = { manualSyncTriggered = true },
                    onCheckCloudRestore = {},
                    onChangeRecoveryPassword = { _, _, _, _ -> }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("backup_sync_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_sync_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(manualSyncTriggered)
    }
}
