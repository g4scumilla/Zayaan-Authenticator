package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.domain.model.AppThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealOnContainer,
    secondary = IndigoSecondary,
    onSecondary = IndigoOnSecondary,
    secondaryContainer = IndigoContainer,
    onSecondaryContainer = IndigoOnContainer,
    tertiary = StatusEmerald,
    background = ObsidianBg,
    onBackground = SlateTextPrimary,
    surface = ObsidianSurface,
    onSurface = SlateTextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = SlateTextSecondary,
    surfaceContainer = ObsidianCard,
    outline = ObsidianBorder,
    outlineVariant = ObsidianBorder.copy(alpha = 0.6f),
    error = StatusCoral,
    onError = Color(0xFF450A0A),
    errorContainer = StatusDangerContainer,
    onErrorContainer = Color(0xFFFECDD3)
)

private val LightColorScheme = lightColorScheme(
    primary = LightTealPrimary,
    onPrimary = LightTealOnPrimary,
    primaryContainer = LightTealContainer,
    onPrimaryContainer = LightTealOnContainer,
    secondary = LightIndigoSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightIndigoContainer,
    onSecondaryContainer = LightIndigoOnContainer,
    tertiary = StatusEmerald,
    background = DaylightBg,
    onBackground = LightTextPrimary,
    surface = DaylightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = DaylightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = DaylightSurface,
    outline = DaylightBorder,
    outlineVariant = DaylightBorder.copy(alpha = 0.7f),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val ZayanShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ZayanAuthenticatorTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZayanTypography,
        shapes = ZayanShapes,
        content = content
    )
}
