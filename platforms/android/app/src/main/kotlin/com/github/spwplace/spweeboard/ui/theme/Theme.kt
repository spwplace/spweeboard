package com.github.spwplace.spweeboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFBBC3FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1E2578),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF353D90),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFDEE0FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFC5C4DD),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF2E2F42),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF454559),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE2E0F9),
    tertiary = androidx.compose.ui.graphics.Color(0xFFE8B9D4),
    onTertiary = androidx.compose.ui.graphics.Color(0xFF46263B),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF5F3C52),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFD8EE),
    background = androidx.compose.ui.graphics.Color(0xFF1B1B1F),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE4E1E6),
    surface = androidx.compose.ui.graphics.Color(0xFF1B1B1F),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE4E1E6),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF46464F),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFC6C5D0),
)

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4D55A9),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFDEE0FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF030865),
    secondary = androidx.compose.ui.graphics.Color(0xFF5C5D72),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE2E0F9),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF191A2C),
    tertiary = androidx.compose.ui.graphics.Color(0xFF78536A),
    onTertiary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFFFD8EE),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF2E1125),
    background = androidx.compose.ui.graphics.Color(0xFFFEFBFF),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1B1B1F),
    surface = androidx.compose.ui.graphics.Color(0xFFFEFBFF),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1B1B1F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE4E1EC),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF46464F),
)

@Composable
fun SpweeboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
