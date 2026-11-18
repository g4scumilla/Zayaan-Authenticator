package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.core.crypto.VaultCrypto
import com.example.domain.model.AppSettings
import com.example.ui.components.BiometricAuthHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SecuritySettingsScreen(
    settings: AppSettings,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onSelectAutoLock: (Long) -> Unit,
    onToggleScreenProtection: (Boolean) -> Unit,
    onSelectClipboardClear: (Int) -> Unit,
    onBuildEncryptedExport: suspend (String) -> Result<String>,
    onBuildUnencryptedExport: () -> String,
    onImportEncryptedFile: (String, String) -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingEncryptedPayload by remember { mutableStateOf<String?>(null) }
    var pendingUnencryptedPayload by remember { mutableStateOf<String?>(null) }
    var pendingImportRawJson by remember { mutableStateOf<String?>(null) }

    var showEncryptedExportDialog by remember { mutableStateOf(false) }
    var showUnencryptedWarningDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }

    val createEncryptedDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val payload = pendingEncryptedPayload
        pendingEncryptedPayload = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray())
                }
            }.onSuccess {
                onNotify("Encrypted .zayanvault backup exported.")
            }.onFailure {
                onNotify("Failed to write export file.")
            }
        }
    }

    val createUnencryptedDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val payload = pendingUnencryptedPayload
        pendingUnencryptedPayload = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray())
                }
            }.onSuccess {
                onNotify("Unencrypted backup exported. Store it offline securely.")
            }.onFailure {
                onNotify("Failed to write export file.")
            }
        }
    }

    val openImportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                pendingImportRawJson = content
                showImportPasswordDialog = true
            } else {
                onNotify("Could not read selected backup file.")
            }
        }
    }

    if (showEncryptedExportDialog) {
        PassphraseDialog(
            title = "Export Encrypted Vault",
            description = "Choose a strong passphrase (min ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} chars) to encrypt this portable .zayanvault file with AES-256-GCM.",
            confirmLabel = "Export Encrypted",
            onDismiss = { showEncryptedExportDialog = false },
            onConfirm = { passphrase ->
                showEncryptedExportDialog = false
                scope.launch {
                    onBuildEncryptedExport(passphrase).fold(
                        onSuccess = { json ->
                            pendingEncryptedPayload = json
                            createEncryptedDocLauncher.launch("zayan-backup.zayanvault.json")
                        },
                        onFailure = { err ->
                            onNotify(err.message ?: "Export encryption failed.")
                        }
                    )
                }
            }
        )
    }

    if (showImportPasswordDialog) {
        PassphraseDialog(
            title = "Decrypt Imported Vault",
            description = "Enter the passphrase used when this .zayanvault backup file was exported.",
            confirmLabel = "Decrypt & Import",
            minLength = 1,
            onDismiss = {
                showImportPasswordDialog = false
                pendingImportRawJson = null
            },
            onConfirm = { passphrase ->
                val raw = pendingImportRawJson
                showImportPasswordDialog = false
                pendingImportRawJson = null
                if (raw != null) {
                    onImportEncryptedFile(raw, passphrase)
                }
            }
        )
    }

    if (showUnencryptedWarningDialog) {
        UnencryptedExportWarningDialog(
            onDismiss = { showUnencryptedWarningDialog = false },
            onConfirmUnencrypted = {
                showUnencryptedWarningDialog = false
                pendingUnencryptedPayload = onBuildUnencryptedExport()
                createUnencryptedDocLauncher.launch("zayan-unencrypted-secrets.json")
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("security_settings_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Security Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingSwitchRow(
                            title = "Biometric & Device Lock",
                            subtitle = "Require fingerprint, face, or device PIN to view 2FA codes",
                            checked = settings.biometricLockEnabled,
                            tag = "biometric_lock_switch",
                            onCheckedChange = { enable ->
                                if (!enable) {
                                    onToggleBiometric(false)
                                } else {
                                    val activity = context as? FragmentActivity
                                    if (activity != null && BiometricAuthHelper.canAuthenticate(activity)) {
                                        BiometricAuthHelper.authenticate(
                                            activity = activity,
                                            title = "Enable Biometric Lock",
                                            subtitle = "Confirm your biometric or device credential",
                                            onSuccess = { onToggleBiometric(true) },
                                            onError = { msg -> onNotify(msg) }
                                        )
                                    } else {
                                        onNotify("No biometric or screen lock configured on this device.")
                                    }
                                }
                            }
                        )

                        Text("Auto-Lock Timeout", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                0L to "Immediate",
                                60L to "1 min",
                                300L to "5 min",
                                -1L to "Never"
                            ).forEach { (sec, label) ->
                                FilterChip(
                                    selected = settings.autoLockTimeoutSeconds == sec,
                                    onClick = { onSelectAutoLock(sec) },
                                    enabled = settings.biometricLockEnabled,
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingSwitchRow(
                            title = "Screen Capture Protection",
                            subtitle = "Block screenshots and screen recording (FLAG_SECURE)",
                            checked = settings.screenCaptureProtection,
                            tag = "screen_capture_switch",
                            onCheckedChange = onToggleScreenProtection
                        )

                        Text("Secure Clipboard Auto-Clear", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                30 to "30s",
                                60 to "60s",
                                0 to "Off"
                            ).forEach { (sec, label) ->
                                FilterChip(
                                    selected = settings.clipboardClearSeconds == sec,
                                    onClick = { onSelectClipboardClear(sec) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Encrypted Export & Import", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Portable backups are encrypted with AES-256-GCM + PBKDF2-HMAC-SHA256 (600,000 rounds).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { showEncryptedExportDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("export_encrypted_button")
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Export Encrypted Vault (.zayanvault)")
                        }
                        OutlinedButton(
                            onClick = { openImportDocLauncher.launch(arrayOf("application/json", "*/*")) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("import_encrypted_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Import Encrypted Vault")
                        }
                        TextButton(
                            onClick = { showUnencryptedWarningDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export_unencrypted_button")
                        ) {
                            Text(
                                text = "Export Unencrypted JSON (Not Recommended)",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    description: String,
    confirmLabel: String,
    minLength: Int = VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.length >= minLength
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun UnencryptedExportWarningDialog(
    onDismiss: () -> Unit,
    onConfirmUnencrypted: () -> Unit
) {
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = { Text("Security Warning: Unencrypted Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "An unencrypted export writes ALL of your 2FA secret keys in plaintext JSON. Anyone who obtains this file can generate your login codes.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { acknowledged = !acknowledged }
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        text = "I understand the risk of exporting plaintext 2FA secrets.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmUnencrypted,
                enabled = acknowledged,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Export Plaintext")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
