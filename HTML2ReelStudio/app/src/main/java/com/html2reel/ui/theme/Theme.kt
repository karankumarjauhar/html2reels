package com.html2reel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val StudioBg = Color(0xFF090B12)
val StudioSurface = Color(0xFF121624)
val StudioSurfaceElevated = Color(0xFF191F33)
val StudioBorder = Color(0xFF27304A)
val StudioIndigo = Color(0xFF6366F1)
val StudioCyan = Color(0xFF22D3EE)
val StudioCoral = Color(0xFFF43F5E)
val StudioAmber = Color(0xFFFB923C)
val StudioTextPrimary = Color(0xFFF8FAFC)
val StudioTextMuted = Color(0xFF94A3B8)

private val DarkStudioColors = darkColorScheme(
    primary = StudioIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF252954),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = StudioCyan,
    onSecondary = Color(0xFF041C24),
    tertiary = StudioCoral,
    onTertiary = Color.White,
    background = StudioBg,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = StudioTextMuted,
    outline = StudioBorder,
    error = Color(0xFFEF4444),
    onError = Color.White
)

private val StudioTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = StudioTextPrimary
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = StudioTextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = StudioTextPrimary
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        color = StudioTextMuted
    )
)

@Composable
fun HTML2ReelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkStudioColors,
        typography = StudioTypography,
        content = content
    )
}
