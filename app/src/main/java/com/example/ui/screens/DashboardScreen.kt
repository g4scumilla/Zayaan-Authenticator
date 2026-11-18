package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.crypto.TotpGenerator
import com.example.domain.model.AppSettings
import com.example.domain.model.SyncStatus
import com.example.domain.model.TotpAccount
import com.example.ui.components.CountdownRing
import com.example.ui.components.IssuerAvatar
import com.example.ui.components.SyncStatusPill
import com.example.ui.theme.OtpDisplayStyle
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCoral

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    accounts: List<TotpAccount>,
    searchQuery: String,
    nowMillis: Long,
    syncStatus: SyncStatus,
    syncNotice: String?,
    settings: AppSettings,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String) -> Unit,
    onCopyCode: (TotpAccount, String) -> Unit,
    onAccountClick: (TotpAccount) -> Unit,
    onMoveAccount: (String, Boolean) -> Unit,
    onAddAccountClick: () -> Unit,
    onQuickScanClick: () -> Unit,
    onSyncStatusClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var reorderMode by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_art),
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column {
                            Text(
                                text = "Zayan",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Authenticator",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    SyncStatusPill(
                        status = syncStatus,
                        onClick = onSyncStatusClick
                    )
                    if (accounts.size > 1) {
                        IconButton(
                            onClick = { reorderMode = !reorderMode },
                            modifier = Modifier.testTag("toggle_reorder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Reorder,
                                contentDescription = if (reorderMode) "Finish reordering" else "Reorder accounts",
                                tint = if (reorderMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.testTag("dashboard_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddAccountClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_account_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add account")
            }
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
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search services or accounts") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("dashboard_search_field")
                )

                AnimatedVisibility(visible = !syncNotice.isNullOrBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = syncNotice.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (accounts.isEmpty()) {
                    EmptyVaultState(
                        hasSearchQuery = searchQuery.isNotBlank(),
                        onScanQr = onQuickScanClick,
                        onAddAccount = onAddAccountClick
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(accounts, key = { _, acc -> acc.id }) { index, account ->
                            val codeState = remember(account.id, account.secretBase32, account.digits, account.periodSeconds, account.algorithm, nowMillis / 1000L) {
                                TotpGenerator.computeState(account, nowMillis)
                            }
                            TotpAccountCard(
                                account = account,
                                formattedCode = TotpGenerator.formatCode(codeState.code, settings.groupCodeDigits),
                                rawCode = codeState.code,
                                nextFormattedCode = if (settings.showNextCode) {
                                    TotpGenerator.formatCode(codeState.nextCode, settings.groupCodeDigits)
                                } else null,
                                remainingSeconds = codeState.remainingSeconds,
                                progressFraction = codeState.progressFraction,
                                reorderMode = reorderMode && searchQuery.isBlank(),
                                canMoveUp = index > 0,
                                canMoveDown = index < accounts.lastIndex,
                                onCopy = {
                                    if (settings.hapticFeedback) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    onCopyCode(account, codeState.code)
                                },
                                onClick = { onAccountClick(account) },
                                onMoveUp = { onMoveAccount(account.id, true) },
                                onMoveDown = { onMoveAccount(account.id, false) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotpAccountCard(
    account: TotpAccount,
    formattedCode: String,
    rawCode: String,
    nextFormattedCode: String?,
    remainingSeconds: Int,
    progressFraction: Float,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCopy: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val codeColor = when {
        remainingSeconds <= 5 -> StatusCoral
        remainingSeconds <= 10 -> StatusAmber
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .testTag("account_card_${account.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IssuerAvatar(issuer = account.displayIssuer())
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayIssuer(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = account.accountName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (reorderMode) {
                    Row {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                        }
                    }
                } else {
                    CountdownRing(
                        remainingSeconds = remainingSeconds,
                        progressFraction = progressFraction
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = formattedCode,
                        style = OtpDisplayStyle,
                        color = codeColor,
                        modifier = Modifier.testTag("otp_code_${account.id}")
                    )
                    if (nextFormattedCode != null) {
                        Text(
                            text = "Next: $nextFormattedCode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onCopy,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                        .testTag("copy_code_${account.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy code $rawCode for ${account.displayIssuer()}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = when {
                    remainingSeconds <= 5 -> StatusCoral
                    remainingSeconds <= 10 -> StatusAmber
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyVaultState(
    hasSearchQuery: Boolean,
    onScanQr: () -> Unit,
    onAddAccount: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = if (hasSearchQuery) "No matching accounts" else "Your encrypted vault is empty",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (hasSearchQuery) {
                "Try searching for another issuer or email address."
            } else {
                "Scan a 2FA QR code or enter a setup key from Google, Microsoft, GitHub, Amazon, or any TOTP service."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasSearchQuery) {
            Spacer(modifier = Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(
                    onClick = onScanQr,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan QR")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = onAddAccount,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("More Options")
                }
            }
        }
    }
}
