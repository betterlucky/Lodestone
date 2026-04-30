package com.daveharris.healthmonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CalmBlueLightColors = lightColorScheme(
    primary = Color(0xFF2157B5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2A61),
    secondary = Color(0xFF4B78A8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEBFA),
    onSecondaryContainer = Color(0xFF12304C),
    tertiary = Color(0xFF5C7EC3),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E8FF),
    onTertiaryContainer = Color(0xFF182B58),
    error = Color(0xFFAF2F4A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9DF),
    onErrorContainer = Color(0xFF490C19),
    background = Color(0xFFF4F8FF),
    onBackground = Color(0xFF102033),
    surface = Color(0xFFFAFCFF),
    onSurface = Color(0xFF14202E),
    surfaceVariant = Color(0xFFE6EEF8),
    onSurfaceVariant = Color(0xFF4A5B70),
    outline = Color(0xFF8C9AAF)
)

private val CalmBlueDarkColors = darkColorScheme(
    primary = Color(0xFFABC7FF),
    onPrimary = Color(0xFF002D6F),
    primaryContainer = Color(0xFF0F479D),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFB2CBE8),
    onSecondary = Color(0xFF1A334B),
    secondaryContainer = Color(0xFF314A63),
    onSecondaryContainer = Color(0xFFDCEBFA),
    tertiary = Color(0xFFC0C8FF),
    onTertiary = Color(0xFF283A6E),
    tertiaryContainer = Color(0xFF3F5285),
    onTertiaryContainer = Color(0xFFE0E8FF),
    error = Color(0xFFFFB2C0),
    onError = Color(0xFF670F24),
    errorContainer = Color(0xFF8B233B),
    onErrorContainer = Color(0xFFFFD9DF),
    background = Color(0xFF0B1522),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF101A28),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF233242),
    onSurfaceVariant = Color(0xFFB9C7D8),
    outline = Color(0xFF8392A6)
)

@Composable
fun HealthMonitorTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CalmBlueDarkColors else CalmBlueLightColors,
        content = content
    )
}
