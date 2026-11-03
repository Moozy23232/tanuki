package com.tanuki.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

/** 原生页面的玻璃底栏；模糊背景由调用方的 Haze source 提供。 */
@Composable
internal fun GlassNavigationBar(
    selectedIndex: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    icon: @Composable (Int, Color) -> Unit,
) {
    if (labels.isEmpty()) return

    val colors = MaterialTheme.colorScheme
    val inheritedRippleTheme = LocalRippleTheme.current
    val rippleTheme = remember(inheritedRippleTheme) { GlassNavigationRippleTheme(inheritedRippleTheme) }
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(28.dp)
    val hasSelection = selectedIndex in labels.indices
    val initialPosition = if (hasSelection) selectedIndex.toFloat() else 0f
    val selectionPosition = remember { Animatable(initialPosition) }
    val selectionAlpha by animateFloatAsState(
        targetValue = if (hasSelection) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "玻璃导航选中淡入淡出",
    )
    val layoutDirection = LocalLayoutDirection.current

    LaunchedEffect(selectedIndex, labels.size) {
        if (selectedIndex in labels.indices) {
            selectionPosition.animateTo(
                targetValue = selectedIndex.toFloat(),
                animationSpec = tween(durationMillis = 280),
            )
        }
    }

    val glassColor = colors.surface.copy(
        alpha = if (hazeState == null) 0.96f else if (dark) 0.20f else 0.24f,
    )
    val selectionColor = if (dark) Color.White.copy(alpha = 0.12f)
        else colors.surfaceContainerHighest.copy(alpha = 0.42f)
    val hazeModifier = if (hazeState == null) Modifier else Modifier.hazeChild(hazeState, shape)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(hazeModifier)
            .clip(shape)
            .background(glassColor)
            .drawBehind {
                val edge = 4.dp.toPx()
                val itemWidth = (size.width - edge * 2) / labels.size
                val gap = 4.dp.toPx()
                val visualPosition = if (layoutDirection == LayoutDirection.Rtl) {
                    labels.lastIndex - selectionPosition.value
                } else {
                    selectionPosition.value
                }
                val indicatorHeight = size.height - edge * 2
                val topLeft = Offset(edge + visualPosition * itemWidth + gap / 2, edge)
                val indicatorSize = Size(itemWidth - gap, indicatorHeight)
                val corner = CornerRadius(indicatorHeight / 2)

                drawRoundRect(
                    color = selectionColor.copy(alpha = selectionColor.alpha * selectionAlpha),
                    topLeft = topLeft,
                    size = indicatorSize,
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = (if (dark) 0.10f else 0.42f) * selectionAlpha),
                    topLeft = topLeft,
                    size = indicatorSize,
                    cornerRadius = corner,
                    style = Stroke(width = 0.75.dp.toPx()),
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.16f else 0.68f),
                        colors.outlineVariant.copy(alpha = if (dark) 0.28f else 0.46f),
                    ),
                ),
                shape = shape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(4.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 220),
                    label = "玻璃导航内容颜色",
                )
                CompositionLocalProvider(LocalRippleTheme provides rippleTheme) {
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(index) },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        icon = { icon(index, contentColor) },
                        label = {
                            Text(label, color = contentColor, style = MaterialTheme.typography.labelMedium)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            unselectedIconColor = colors.onSurfaceVariant,
                            unselectedTextColor = colors.onSurfaceVariant,
                            indicatorColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

// 玻璃胶囊负责点击反馈，只关闭重复的触摸/悬停色块，保留键盘焦点状态层。
private class GlassNavigationRippleTheme(private val inherited: RippleTheme) : RippleTheme {
    @Composable
    override fun defaultColor(): Color = inherited.defaultColor()

    @Composable
    override fun rippleAlpha(): RippleAlpha = RippleAlpha(
        draggedAlpha = 0f,
        focusedAlpha = inherited.rippleAlpha().focusedAlpha,
        hoveredAlpha = 0f,
        pressedAlpha = 0f,
    )
}
