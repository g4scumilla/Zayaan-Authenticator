package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppSettings
import com.example.domain.model.AppThemeMode
import com.example.domain.model.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    userEmail: String?,
    userDisplayName: String?,
    syncStatus: SyncStatus,
    hasUnsyncedChanges: Boolean,
    settings: AppSettings,
    onBack: () -> Unit,
    onThemeChange: (AppThemeMode) -> Unit,
    onGroupDigitsChange: (Boolean) -> Unit,
    onShowNextCodeChange: (Boolean) -> Unit,
    onHapticFeedbackChange: (Boolean) -> Unit,
    onOpenBackupSync: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignOutConfirmed: () -> Unit
) {
    var showSignOutConfirm by remember { mutableStateOf(false) }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text("Sign Out of Zayan Authenticator?") },
            text = {
                Text(
                    if (hasUnsyncedChanges) {
                        "Warning: You have local changes that have not synced to Firestore yet. Signing out will clear local cached data on this device. Sync before signing out to avoid losing recent additions."
                    } else {
                        "Your encrypted backup remains safely stored in Cloud Firestore. You can sign back in with Google and your recovery password anytime."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutConfirm = false
                        onSignOutConfirmed()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("app_settings_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = userDisplayName?.takeIf { it.isNotBlank() } ?: "Google Account",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = userEmail ?: "Authenticated",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Backup Status: ${syncStatus.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                SettingsNavRow(
                    icon = Icons.Default.CloudSync,
                    title = "Backup & Synchronization",
                    subtitle = "Cloud sync status, recovery status, manual backup",
                    tag = "nav_backup_sync",
                    onClick = onOpenBackupSync
                )

                SettingsNavRow(
                    icon = Icons.Default.Security,
                    title = "Security & Exports",
                    subtitle = "Biometric lock, auto-lock, screen capture, encrypted backup files",
                    tag = "nav_security_settings",
                    onClick = onOpenSecuritySettings
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Appearance", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onThemeChange(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        SettingSwitchRow(
                            title = "Group Code Digits",
                            subtitle = "Display 6-digit codes as 123 456 for easier reading",
                            checked = settings.groupCodeDigits,
                            tag = "group_digits_switch",
                            onCheckedChange = onGroupDigitsChange
                        )

                        SettingSwitchRow(
                            title = "Show Next Code Preview",
                            subtitle = "Preview the upcoming code below the active OTP",
                            checked = settings.showNextCode,
                            tag = "next_code_switch",
                            onCheckedChange = onShowNextCodeChange
                        )

                        SettingSwitchRow(
                            title = "Haptic Feedback",
                            subtitle = "Subtle vibration when copying codes or scanning QR codes",
                            checked = settings.hapticFeedback,
                            tag = "haptic_switch",
                            onCheckedChange = onHapticFeedbackChange
                        )
                    }
                }

                SettingsNavRow(
                    icon = Icons.Default.Info,
                    title = "About & Privacy Policy",
                    subtitle = "Encryption design, RFC compliance, and version details",
                    tag = "nav_about",
                    onClick = onOpenAbout
                )

                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showSignOutConfirm = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("Sign Out")
                }
            }
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
