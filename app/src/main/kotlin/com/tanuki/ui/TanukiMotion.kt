package com.tanuki.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private val TanukiEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 原生内容的一次性入场；不包含也不能包住任何 WebView。 */
internal fun Modifier.tanukiEnter(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 420, easing = TanukiEnterEasing))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}

/** 顶部仅保留低透明度烟灰层次，向背景色收敛以维持长页面阅读对比度。 */
@Composable
internal fun tanukiPageBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return Brush.verticalGradient(
        listOf(
            colors.surfaceContainerHigh.copy(alpha = 0.30f),
            colors.surfaceContainerLow.copy(alpha = 0.12f),
            colors.background,
        )
    )
}

/** 首屏使用低反差灰阶静态渐变，不循环、不随时间变化。 */
@Composable
internal fun tanukiHeroBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return Brush.linearGradient(
        listOf(
            colors.surfaceContainerLow,
            colors.surfaceContainerHigh,
        )
    )
}
