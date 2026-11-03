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

/** 石墨黑、温润灰白与烟灰建立中性层级，主操作不依赖彩色强调。 */
private val Accent = Color(0xFF2B2C28)
private val AccentLight = Color(0xFFE2E3DC)

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color(0xFFFDFDFB),
    primaryContainer = Color(0xFFE3E3DD), onPrimaryContainer = Color(0xFF232420),
    secondary = Color(0xFF60615B), onSecondary = Color(0xFFFCFCFA),
    secondaryContainer = Color(0xFFECECE6), onSecondaryContainer = Color(0xFF30312C),
    tertiary = Color(0xFF6A6B65), onTertiary = Color(0xFFFCFCFA),
    tertiaryContainer = Color(0xFFF0EFEA), onTertiaryContainer = Color(0xFF353631),
    background = Color(0xFFF6F6F2), onBackground = Color(0xFF1F201D),
    surface = Color(0xFFFCFCF9), onSurface = Color(0xFF1F201D),
    surfaceVariant = Color(0xFFE8E7E2), onSurfaceVariant = Color(0xFF5A5C56),
    surfaceDim = Color(0xFFD7D8D0), surfaceBright = Color(0xFFFCFCF9),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F1EC),
    surfaceContainer = Color(0xFFEBEBE5), surfaceContainerHigh = Color(0xFFE5E5DF),
    surfaceContainerHighest = Color(0xFFDFDFD8),
    outline = Color(0xFF75776F), outlineVariant = Color(0xFFCDCDC5),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF30312C), inverseOnSurface = Color(0xFFF1F1EC),
    inversePrimary = AccentLight, surfaceTint = Accent,
)

private val DarkScheme = darkColorScheme(
    primary = AccentLight, onPrimary = Color(0xFF282924),
    primaryContainer = Color(0xFF3C3E38), onPrimaryContainer = Color(0xFFECECE6),
    secondary = Color(0xFFC4C5BD), onSecondary = Color(0xFF2E302A),
    secondaryContainer = Color(0xFF454740), onSecondaryContainer = Color(0xFFE7E8E0),
    tertiary = Color(0xFFB7B9B0), onTertiary = Color(0xFF292B26),
    tertiaryContainer = Color(0xFF4F514A), onTertiaryContainer = Color(0xFFE9EAE2),
    background = Color(0xFF121310), onBackground = Color(0xFFE6E7E0),
    surface = Color(0xFF1A1B18), onSurface = Color(0xFFE6E7E0),
    surfaceVariant = Color(0xFF30322D), onSurfaceVariant = Color(0xFFC1C3BA),
    surfaceDim = Color(0xFF121310), surfaceBright = Color(0xFF3A3C35),
    surfaceContainerLowest = Color(0xFF0D0E0C), surfaceContainerLow = Color(0xFF1E1F1B),
    surfaceContainer = Color(0xFF23241F), surfaceContainerHigh = Color(0xFF2D2F29),
    surfaceContainerHighest = Color(0xFF383A33),
    outline = Color(0xFF8E9087), outlineVariant = Color(0xFF464841),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE6E7E0), inverseOnSurface = Color(0xFF30312C),
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
