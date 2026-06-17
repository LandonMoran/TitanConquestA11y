package com.titanconquest.a11y.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// High-contrast palette designed for low-vision users.
// All text/background combinations meet WCAG AA (4.5:1) minimum,
// most meet AAA (7:1).

private val DarkColorScheme = darkColorScheme(
    primary          = Color(0xFFFFD54F),  // Amber — high visibility on dark
    onPrimary        = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF3E3000),
    onPrimaryContainer = Color(0xFFFFE57F),
    secondary        = Color(0xFF80CBC4),
    onSecondary      = Color(0xFF00201E),
    background       = Color(0xFF121212),
    onBackground     = Color(0xFFEEEEEE),
    surface          = Color(0xFF1E1E1E),
    onSurface        = Color(0xFFEEEEEE),
    error            = Color(0xFFFF8A80),
    onError          = Color(0xFF410002)
)

private val LightColorScheme = lightColorScheme(
    primary          = Color(0xFF5D4037),  // Deep brown — earthy Titan feel
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD54F),
    onPrimaryContainer = Color(0xFF1A0D00),
    secondary        = Color(0xFF00695C),
    onSecondary      = Color(0xFFFFFFFF),
    background       = Color(0xFFFAFAFA),
    onBackground     = Color(0xFF1A1A1A),
    surface          = Color(0xFFFFFFFF),
    onSurface        = Color(0xFF1A1A1A),
    error            = Color(0xFFB00020),
    onError          = Color(0xFFFFFFFF)
)

@Composable
fun TitanConquestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Android 12+) disabled — we control contrast intentionally
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),  // Uses system font scaling automatically
        content = content
    )
}
