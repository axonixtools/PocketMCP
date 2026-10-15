package com.pocketmcp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tokens that don't map onto the Material 3 [androidx.compose.material3.ColorScheme]:
 * hairline separators, grouped-list fills, code surfaces, and semantic accents.
 */
@Immutable
data class AppColors(
    val success: Color,
    val warning: Color,
    val separator: Color,
    val fieldFill: Color,
    val card: Color,
    val elevatedCard: Color,
    val codeBackground: Color,
    val segmentSelected: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color
)

private val LightAppColors = AppColors(
    success = GreenLight,
    warning = OrangeLight,
    separator = LightSeparator,
    fieldFill = LightFieldFill,
    card = LightCard,
    elevatedCard = LightCard,
    codeBackground = LightCodeBackground,
    segmentSelected = Color(0xFFFFFFFF),
    secondaryLabel = LightSecondaryLabel,
    tertiaryLabel = LightTertiaryLabel
)

private val DarkAppColors = AppColors(
    success = GreenDark,
    warning = OrangeDark,
    separator = DarkSeparator,
    fieldFill = DarkFieldFill,
    card = DarkCard,
    elevatedCard = DarkElevatedCard,
    codeBackground = DarkCodeBackground,
    segmentSelected = Color(0xFF636366),
    secondaryLabel = DarkSecondaryLabel,
    tertiaryLabel = DarkTertiaryLabel
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

private val LightColorScheme = lightColorScheme(
    primary = BlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0x1F007AFF),
    onPrimaryContainer = BlueLight,
    secondary = GrayLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF3C3C43),
    tertiary = GrayLight,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightLabel,
    surface = LightBackground,
    onSurface = LightLabel,
    surfaceVariant = LightCard,
    onSurfaceVariant = LightSecondaryLabel,
    outline = Color(0xFFC6C6C8),
    outlineVariant = LightSeparator,
    error = RedLight,
    onError = Color.White,
    errorContainer = Color(0x1FFF3B30),
    onErrorContainer = RedLight,
    scrim = Color(0x66000000)
)

private val DarkColorScheme = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0x1F0A84FF),
    onPrimaryContainer = BlueDark,
    secondary = GrayDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A3A3C),
    onSecondaryContainer = Color(0xFFEBEBF5),
    tertiary = GrayDark,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = DarkLabel,
    surface = DarkBackground,
    onSurface = DarkLabel,
    surfaceVariant = DarkCard,
    onSurfaceVariant = DarkSecondaryLabel,
    outline = Color(0xFF545458),
    outlineVariant = DarkSeparator,
    error = RedDark,
    onError = Color.White,
    errorContainer = Color(0x1FFF453A),
    onErrorContainer = RedDark,
    scrim = Color(0x66000000)
)

/** Accessor for the app's extended design tokens. */
object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

/**
 * PocketMCP theme — follows the system light/dark setting.
 * The palette is intentionally fixed (no dynamic color) so the app has a
 * consistent identity across devices.
 */
@Composable
fun PocketMCPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val appColors = if (darkTheme) DarkAppColors else LightAppColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Keep the transparent bars clean — no gray system scrim on either theme.
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
