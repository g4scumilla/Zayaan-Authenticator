package com.example.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.core.crypto.VaultCrypto
import com.example.domain.model.SyncStatus
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusCoral
import com.example.ui.theme.StatusEmerald
import kotlin.math.abs

@Composable
fun ScreenSecurityEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled, context) {
        val activity = context as? Activity
        if (activity != null) {
            if (enabled) {
                activity.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose { }
    }
}

@Composable
fun IssuerAvatar(
    issuer: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val palette = listOf(
        Color(0xFF0EA5E9),
        Color(0xFF10B981),
        Color(0xFF6366F1),
        Color(0xFFF59E0B),
        Color(0xFFEC4899),
        Color(0xFF14B8A6)
    )
    val label = issuer.ifBlank { "Z" }
    val bgColor = palette[abs(label.lowercase().hashCode()) % palette.size]
    val initials = label.trim()
        .split(" ", "-", "_")
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "ZA" }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor.copy(alpha = 0.18f))
            .border(1.dp, bgColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = bgColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CountdownRing(
    remainingSeconds: Int,
    progressFraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp
) {
    val targetColor = when {
        remainingSeconds <= 5 -> StatusCoral
        remainingSeconds <= 10 -> StatusAmber
        else -> MaterialTheme.colorScheme.primary
    }
    val ringColor by animateColorAsState(targetValue = targetColor, label = "ringColor")
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 3.5.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokePx)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progressFraction.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Text(
            text = "${remainingSeconds}s",
            style = MaterialTheme.typography.labelSmall,
            color = ringColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SyncStatusPill(
    status: SyncStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, tint, bg) = when (status) {
        SyncStatus.SYNCED -> Triple(Icons.Default.CloudDone, StatusEmerald, StatusEmerald.copy(alpha = 0.14f))
        SyncStatus.SYNCING -> Triple(Icons.Default.CloudSync, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
        SyncStatus.OFFLINE -> Triple(Icons.Default.CloudOff, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        SyncStatus.BACKUP_FAILED -> Triple(Icons.Default.ErrorOutline, StatusCoral, StatusCoral.copy(alpha = 0.16f))
        SyncStatus.RECOVERY_REQUIRED,
        SyncStatus.NOT_CONFIGURED -> Triple(Icons.Default.WarningAmber, StatusAmber, StatusAmber.copy(alpha = 0.16f))
    }

    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("sync_status_pill"),
        shape = CircleShape,
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = status.label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun PasswordStrengthMeter(
    password: String,
    modifier: Modifier = Modifier
) {
    val score = VaultCrypto.evaluatePasswordStrength(password)
    val fraction = (score / 5f).coerceIn(0.05f, 1f)
    val (label, color) = when {
        password.length < VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH ->
            "Minimum ${VaultCrypto.MIN_RECOVERY_PASSWORD_LENGTH} characters required" to StatusCoral
        score <= 2 -> "Fair — consider a longer passphrase" to StatusAmber
        score == 3 -> "Good — strong recovery passphrase" to MaterialTheme.colorScheme.primary
        else -> "Excellent — high-entropy recovery passphrase" to StatusEmerald
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { if (password.isEmpty()) 0f else fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (password.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else color
        )
    }
}

object BiometricAuthHelper {
    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Zayan Authenticator",
        subtitle: String = "Verify your identity to access your 2FA codes",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(promptInfo)
    }
}

@Composable
fun BiometricLockOverlay(
    onUnlockClick: () -> Unit,
    errorMessage: String?
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("biometric_lock_overlay"),
        color = MaterialTheme.colorScheme.background
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
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Vault Locked",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Zayan Authenticator Locked",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Authenticate with biometrics or device credentials to view your verification codes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onUnlockClick,
                modifier = Modifier
                    .height(52.dp)
                    .testTag("biometric_unlock_button")
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Unlock Vault")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Local vault protected by Android Keystore",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
