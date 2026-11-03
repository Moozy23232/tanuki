package com.tanuki.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 鸢尾蓝建立识别度，雾紫与暖杏只用于轻量渐变和空间分层。 */
private val Accent = Color(0xFF4D55B6)
private val AccentLight = Color(0xFFC4C6FF)

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E5FF), onPrimaryContainer = Color(0xFF242765),
    secondary = Color(0xFF5D5F72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E7F2), onSecondaryContainer = Color(0xFF303044),
    tertiary = Color(0xFF76536B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8E3EF), onTertiaryContainer = Color(0xFF47253B),
    background = Color(0xFFF8F8FC), onBackground = Color(0xFF1B1B24),
    surface = Color(0xFFFDFCFF), onSurface = Color(0xFF1B1B24),
    surfaceVariant = Color(0xFFE9E8F0), onSurfaceVariant = Color(0xFF5A5967),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F0F7),
    surfaceContainer = Color(0xFFECEBF3), surfaceContainerHigh = Color(0xFFE6E5EE),
    surfaceContainerHighest = Color(0xFFE0DFE9),
    outline = Color(0xFF777683), outlineVariant = Color(0xFFCFCDDA),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF30303B), inverseOnSurface = Color(0xFFF2F0F8),
    inversePrimary = AccentLight, surfaceTint = Accent,
)

private val DarkScheme = darkColorScheme(
    primary = AccentLight, onPrimary = Color(0xFF292D76),
    primaryContainer = Color(0xFF3C427E), onPrimaryContainer = Color(0xFFE4E5FF),
    secondary = Color(0xFFC7C5D8), onSecondary = Color(0xFF303043),
    secondaryContainer = Color(0xFF444455), onSecondaryContainer = Color(0xFFE5E2EF),
    tertiary = Color(0xFFE5B9D2), onTertiary = Color(0xFF43273A),
    tertiaryContainer = Color(0xFF5D3C50), onTertiaryContainer = Color(0xFFF8E3EF),
    background = Color(0xFF121218), onBackground = Color(0xFFE6E3ED),
    surface = Color(0xFF1A1A22), onSurface = Color(0xFFE6E3ED),
    surfaceVariant = Color(0xFF2D2C38), onSurfaceVariant = Color(0xFFC5C2D0),
    surfaceContainerLowest = Color(0xFF0D0D12), surfaceContainerLow = Color(0xFF1D1D26),
    surfaceContainer = Color(0xFF22222C), surfaceContainerHigh = Color(0xFF2C2B37),
    surfaceContainerHighest = Color(0xFF373642),
    outline = Color(0xFF8F8C9C), outlineVariant = Color(0xFF464552),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE6E3ED), inverseOnSurface = Color(0xFF30303B),
    inversePrimary = Accent, surfaceTint = AccentLight,
)

private val TanukiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private fun tanukiType(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.em)

private val TanukiTypography = Typography(
    headlineLarge = tanukiType(34, 43, FontWeight.SemiBold, -0.02),
    headlineMedium = tanukiType(29, 38, FontWeight.SemiBold, -0.015),
    headlineSmall = tanukiType(25, 33, FontWeight.SemiBold, -0.01),
    titleLarge = tanukiType(22, 30, FontWeight.SemiBold),
    titleMedium = tanukiType(16, 24, FontWeight.SemiBold),
    titleSmall = tanukiType(14, 20, FontWeight.SemiBold),
    bodyLarge = tanukiType(16, 25, FontWeight.Normal),
    bodyMedium = tanukiType(14, 22, FontWeight.Normal),
    bodySmall = tanukiType(12, 18, FontWeight.Normal),
    labelLarge = tanukiType(14, 20, FontWeight.Medium),
    labelMedium = tanukiType(12, 18, FontWeight.Medium),
    labelSmall = tanukiType(11, 16, FontWeight.Medium),
)

object TanukiSpacing {
    val xs = 4.dp; val sm = 8.dp; val md = 12.dp; val lg = 16.dp; val xl = 24.dp; val xxl = 32.dp
}

internal fun tanukiColorScheme(dark: Boolean) = if (dark) DarkScheme else LightScheme

@Composable
fun TanukiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = tanukiColorScheme(isSystemInDarkTheme()),
        typography = TanukiTypography,
        shapes = TanukiShapes,
        content = content,
    )
}
