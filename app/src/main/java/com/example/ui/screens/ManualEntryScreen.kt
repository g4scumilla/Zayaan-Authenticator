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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.example.core.crypto.Base32
import com.example.core.crypto.TotpGenerator
import com.example.domain.model.TotpAlgorithm
import com.example.ui.theme.OtpCompactStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(
    prefillIssuer: String,
    nowMillis: Long,
    errorMessage: String?,
    onBack: () -> Unit,
    onSaveAccount: (
        issuer: String,
        accountName: String,
        secret: String,
        algorithm: TotpAlgorithm,
        digits: Int,
        periodSeconds: Int
    ) -> Unit
) {
    var issuer by remember { mutableStateOf(prefillIssuer) }
    var accountName by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(TotpAlgorithm.SHA1) }
    var digits by remember { mutableIntStateOf(6) }
    var period by remember { mutableIntStateOf(30) }

    val normalizedSecret = remember(secretKey) { Base32.normalize(secretKey) }
    val isSecretValid = remember(normalizedSecret) { Base32.isValid(normalizedSecret) }
    val previewCode = remember(normalizedSecret, algorithm, digits, period, nowMillis / 1000L) {
        if (isSecretValid) {
            runCatching {
                val code = TotpGenerator.generateCodeForTime(
                    secretBase32 = normalizedSecret,
                    timeSeconds = nowMillis / 1000L,
                    periodSeconds = period,
                    digits = digits,
                    algorithm = algorithm
                )
                TotpGenerator.formatCode(code, true)
            }.getOrNull()
        } else null
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("manual_entry_screen"),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Enter Setup Key") },
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text("Service / Issuer") },
                    placeholder = { Text("e.g., GitHub, Google, AWS") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_issuer_input")
                )

                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text("Account Name / Email") },
                    placeholder = { Text("e.g., alice@example.com") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_account_input")
                )

                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = { Text("Secret Key (Base32)") },
                    placeholder = { Text("JBSWY3DPEHPK3PXP") },
                    singleLine = true,
                    isError = secretKey.isNotEmpty() && !isSecretValid,
                    supportingText = if (secretKey.isNotEmpty() && !isSecretValid) {
                        { Text("Enter valid Base32 characters (A–Z, 2–7)") }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_secret_input")
                )

                Text("Code Length", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(6, 8).forEach { count ->
                        FilterChip(
                            selected = digits == count,
                            onClick = { digits = count },
                            label = { Text("$count Digits") }
                        )
                    }
                }

                Text("Time Period", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(30, 60).forEach { sec ->
                        FilterChip(
                            selected = period == sec,
                            onClick = { period = sec },
                            label = { Text("${sec}s") }
                        )
                    }
                }

                Text("Hash Algorithm", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TotpAlgorithm.entries.forEach { alg ->
                        FilterChip(
                            selected = algorithm == alg,
                            onClick = { algorithm = alg },
                            label = { Text(alg.displayName) }
                        )
                    }
                }

                if (previewCode != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Code Preview",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = previewCode,
                                style = OtpCompactStyle,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        onSaveAccount(issuer, accountName, normalizedSecret, algorithm, digits, period)
                    },
                    enabled = isSecretValid && (issuer.isNotBlank() || accountName.isNotBlank()),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("manual_save_button")
                ) {
                    Text("Save Account", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
