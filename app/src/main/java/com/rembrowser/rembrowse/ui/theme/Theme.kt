package com.rembrowser.rembrowse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RemBackground = Color(0xFF0B1120)
val RemSurface = Color(0xFF111827)
val RemSurfaceElevated = Color(0xFF1E293B)
val RemBorder = Color(0xFF334155)
val RemAccentCyan = Color(0xFF38BDF8)
val RemAccentIndigo = Color(0xFF818CF8)
val RemAccentEmerald = Color(0xFF34D399)
val RemAccentAmber = Color(0xFFF59E0B)
val RemTextPrimary = Color(0xFFF8FAFC)
val RemTextSecondary = Color(0xFF94A3B8)
val RemDanger = Color(0xFFF87171)

private val RemBrowseDarkColorScheme = darkColorScheme(
    primary = RemAccentCyan,
    onPrimary = Color(0xFF082F49),
    secondary = RemAccentIndigo,
    onSecondary = Color(0xFF1E1B4B),
    tertiary = RemAccentAmber,
    onTertiary = Color(0xFF451A03),
    background = RemBackground,
    onBackground = RemTextPrimary,
    surface = RemSurface,
    onSurface = RemTextPrimary,
    surfaceVariant = RemSurfaceElevated,
    onSurfaceVariant = RemTextSecondary,
    outline = RemBorder,
    error = RemDanger,
    onError = Color(0xFF450A0A)
)

@Composable
fun RemBrowseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemBrowseDarkColorScheme,
        content = content
    )
}
