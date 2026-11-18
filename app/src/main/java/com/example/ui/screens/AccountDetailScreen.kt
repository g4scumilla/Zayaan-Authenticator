package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.crypto.TotpGenerator
import com.example.domain.model.TotpAccount
import com.example.domain.model.TotpAlgorithm
import com.example.ui.components.CountdownRing
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OtpDisplayStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    account: TotpAccount?,
    nowMillis: Long,
    groupCodeDigits: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onBack: () -> Unit,
    onCopyCode: (TotpAccount, String) -> Unit,
    onUpdateAccount: (TotpAccount) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onMoveAccount: (String, Boolean) -> Unit
) {
    if (account == null) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text("Account") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Account not found.")
            }
        }
        return
    }

    var issuer by remember(account.id) { mutableStateOf(account.issuer) }
    var accountName by remember(account.id) { mutableStateOf(account.accountName) }
    var algorithm by remember(account.id) { mutableStateOf(account.algorithm) }
    var digits by remember(account.id) { mutableIntStateOf(account.digits) }
    var period by remember(account.id) { mutableIntStateOf(account.periodSeconds) }
    var revealSecret by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val previewAccount = account.copy(
        issuer = issuer,
        accountName = accountName,
        algorithm = algorithm,
        digits = digits,
        periodSeconds = period
    )
    val codeState = remember(previewAccount, nowMillis / 1000L) {
        TotpGenerator.computeState(previewAccount, nowMillis)
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${account.displayIssuer()}?") },
            text = {
                Text(
                    "Deleting this authenticator removes it from this device and syncs the deletion across your other devices. Make sure you have disabled 2FA or saved backup codes for this service first."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteAccount(account.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("account_detail_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Account Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.testTag("delete_account_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete account",
                            tint = MaterialTheme.colorScheme.error
                        )
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = TotpGenerator.formatCode(codeState.code, groupCodeDigits),
                                style = OtpDisplayStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${algorithm.displayName} • $digits digits • ${period}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CountdownRing(
                                remainingSeconds = codeState.remainingSeconds,
                                progressFraction = codeState.progressFraction
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { onCopyCode(previewAccount, codeState.code) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text("Service / Issuer") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_issuer_input")
                )

                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text("Account Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("detail_account_input")
                )

                Text("Code Length", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(6, 8).forEach { d ->
                        FilterChip(selected = digits == d, onClick = { digits = d }, label = { Text("$d Digits") })
                    }
                }

                Text("Period", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(30, 60).forEach { p ->
                        FilterChip(selected = period == p, onClick = { period = p }, label = { Text("${p}s") })
                    }
                }

                Text("Algorithm", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TotpAlgorithm.entries.forEach { alg ->
                        FilterChip(selected = algorithm == alg, onClick = { algorithm = alg }, label = { Text(alg.displayName) })
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Secret Setup Key", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { revealSecret = !revealSecret }) {
                                Icon(
                                    imageVector = if (revealSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (revealSecret) "Hide secret" else "Reveal secret"
                                )
                            }
                        }
                        Text(
                            text = if (revealSecret) account.secretBase32 else "•••• •••• •••• ••••",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onMoveAccount(account.id, true) },
                        enabled = canMoveUp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Move Up")
                    }
                    OutlinedButton(
                        onClick = { onMoveAccount(account.id, false) },
                        enabled = canMoveDown,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Move Down")
                    }
                }

                Button(
                    onClick = { onUpdateAccount(previewAccount) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("detail_save_button")
                ) {
                    Text("Save Changes", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
