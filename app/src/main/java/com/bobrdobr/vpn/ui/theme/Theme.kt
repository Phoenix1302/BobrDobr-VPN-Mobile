package com.bobrdobr.vpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = NeonEmerald,
    onPrimary = BackgroundMain,
    primaryContainer = BackgroundCardActive,
    onPrimaryContainer = NeonEmeraldGlow,

    secondary = NeonCyan,
    onSecondary = BackgroundMain,
    secondaryContainer = BackgroundCard,
    onSecondaryContainer = NeonCyanGlow,

    tertiary = NeonPurple,
    onTertiary = BackgroundMain,
    tertiaryContainer = BackgroundCard,
    onTertiaryContainer = NeonPurpleGlow,

    background = BackgroundMain,
    onBackground = TextPrimary,

    surface = BackgroundSurface,
    onSurface = TextPrimary,
    surfaceVariant = BackgroundCard,
    onSurfaceVariant = TextSecondary,

    outline = CardBorder,
    outlineVariant = CardBorderActive,

    error = NeonRed,
    onError = BackgroundMain,
    errorContainer = BackgroundCardActive,
    onErrorContainer = NeonRedGlow
)

@Composable
fun BobrDobrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // BobrDobr VPN is cyber-themed (always dark cyberpunk aesthetic)
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = BackgroundMain.toArgb()
            window.navigationBarColor = BackgroundMain.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
