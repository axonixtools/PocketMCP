package com.pocketmcp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val NeuralDarkColorScheme = darkColorScheme(
    primary = NeuralPrimary,
    onPrimary = NeuralOnPrimary,
    primaryContainer = NeuralPrimaryContainer,
    onPrimaryContainer = NeuralPrimary,
    secondary = NeuralSecondary,
    onSecondary = NeuralOnSecondary,
    secondaryContainer = NeuralSecondaryContainer,
    onSecondaryContainer = NeuralSecondary,
    tertiary = NeuralTertiary,
    onTertiary = NeuralOnTertiary,
    tertiaryContainer = NeuralTertiaryContainer,
    onTertiaryContainer = NeuralTertiary,
    error = NeuralError,
    onError = NeuralOnError,
    errorContainer = NeuralErrorContainer,
    onErrorContainer = NeuralError,
    background = NeuralBackground,
    onBackground = NeuralOnBackground,
    surface = NeuralSurface,
    onSurface = NeuralOnSurface,
    surfaceVariant = NeuralSurfaceVariant,
    onSurfaceVariant = NeuralOnSurfaceVariant,
    outline = NeuralOutline,
    outlineVariant = NeuralOutlineVariant,
    scrim = NeuralBackground
)

@Composable
fun PocketMCPTheme(
    darkTheme: Boolean = true, // Always dark for neural aesthetic
    dynamicColor: Boolean = false, // Disable dynamic color to preserve brand palette
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NeuralDarkColorScheme
        else -> NeuralDarkColorScheme // Always use dark scheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
