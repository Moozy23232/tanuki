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

/** Shared Material roles: quiet neutral surfaces, blue for actions and selection. */
private val Accent = Color(0xFF3456CF)
private val AccentLight = Color(0xFFB6C4FF)

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EDFF), onPrimaryContainer = Color(0xFF243C88),
    secondary = Color(0xFF545F7A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E8F4), onSecondaryContainer = Color(0xFF303B53),
    tertiary = Color(0xFF62587C), onTertiary = Color.White,
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF1B2030),
    surface = Color.White, onSurface = Color(0xFF1B2030),
    surfaceVariant = Color(0xFFEEF0F6), onSurfaceVariant = Color(0xFF555E70),
    outline = Color(0xFF747D90), outlineVariant = Color(0xFFDDE1EC),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF2E3340), inverseOnSurface = Color(0xFFF0F1F7),
    inversePrimary = AccentLight, surfaceTint = Accent,
)

private val DarkScheme = darkColorScheme(
    primary = AccentLight, onPrimary = Color(0xFF172B64),
    primaryContainer = Color(0xFF253866), onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFBCC6E0), onSecondary = Color(0xFF273148),
    secondaryContainer = Color(0xFF3C465E), onSecondaryContainer = Color(0xFFE0E6F7),
    tertiary = Color(0xFFCEC0EA), onTertiary = Color(0xFF352A4C),
    background = Color(0xFF12141A), onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF1C1F28), onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF292D39), onSurfaceVariant = Color(0xFFB9BFCE),
    outline = Color(0xFF9098AC), outlineVariant = Color(0xFF3A404F),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE8EAF2), inverseOnSurface = Color(0xFF2E3340),
    inversePrimary = Accent, surfaceTint = Accent,
)

private val TanukiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun tanukiType(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.em)

private val TanukiTypography = Typography(
    headlineLarge = tanukiType(28, 36, FontWeight.SemiBold, -0.01),
    headlineMedium = tanukiType(24, 32, FontWeight.SemiBold, -0.01),
    headlineSmall = tanukiType(22, 28, FontWeight.SemiBold, -0.01),
    titleLarge = tanukiType(20, 28, FontWeight.SemiBold),
    titleMedium = tanukiType(16, 24, FontWeight.SemiBold),
    titleSmall = tanukiType(14, 20, FontWeight.SemiBold),
    bodyLarge = tanukiType(16, 24, FontWeight.Normal),
    bodyMedium = tanukiType(14, 20, FontWeight.Normal),
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
