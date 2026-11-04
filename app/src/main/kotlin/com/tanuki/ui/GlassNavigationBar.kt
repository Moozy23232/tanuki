package com.tanuki.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt

/** 液态镜片只采样原生底图和导航自身；拖动预览与页面提交分离，避免途经页签时重建网页。 */
@Composable
internal fun GlassNavigationBar(
    selectedIndex: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 16.dp,
    icon: @Composable (Int, Color) -> Unit,
) {
    if (labels.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)
    val sources = remember(labels.size) { List(labels.size) { MutableInteractionSource() } }
    val pressed = sources.map { it.collectIsPressedAsState().value }.getOrNull(selectedIndex) == true
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var lastSelected by remember { mutableIntStateOf(selectedIndex.coerceIn(labels.indices)) }
    val selected = selectedIndex in labels.indices
    val position by animateFloatAsState(
        if (dragging) dragPosition else if (selected) selectedIndex.toFloat() else lastSelected.toFloat(),
        if (dragging) snap() else spring(dampingRatio = 0.8f, stiffness = 480f),
        label = "液态导航位置",
    )
    val pressure by animateFloatAsState(
        if (dragging || pressed) 1f else 0f,
        spring(dampingRatio = 0.8f, stiffness = 500f), label = "液态导航形变",
    )
    val visibility by animateFloatAsState(
        if (selected || dragging) 1f else 0f, label = "液态导航可见度",
    )
    LaunchedEffect(selectedIndex, labels.size, direction) {
        dragging = false
        if (selected) lastSelected = selectedIndex
    }
    val tabsBackdrop = rememberLayerBackdrop()
    // 网页和低版本只提供不透明原生底色，绝不将 AndroidView 放入背景录制层。
    val fallback = rememberCanvasBackdrop { drawRect(colors.surface) }
    val panelShape = RoundedCornerShape(36.dp)
    val lensShape = RoundedCornerShape(50)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val itemWidth = (maxWidth - 8.dp) / labels.size
        val itemPixels = with(density) { itemWidth.toPx() }
        val edgePixels = with(density) { 4.dp.toPx() }
        fun positionAt(x: Float): Float {
            val visual = (x - edgePixels) / itemPixels - 0.5f
            return (if (direction == LayoutDirection.Rtl) labels.lastIndex - visual else visual)
                .coerceIn(0f, labels.lastIndex.toFloat())
        }
        LaunchedEffect(itemPixels) { dragging = false }
        val gesture = Modifier.pointerInput(labels.size, selectedIndex, direction, itemPixels) {
            if (itemPixels <= 0f) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { point -> dragPosition = positionAt(point.x); dragging = true },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    if (dragging) dragPosition = positionAt(change.position.x)
                },
                onDragEnd = {
                    if (dragging) {
                        val target = dragPosition.roundToInt().coerceIn(labels.indices)
                        dragging = false
                        if (target != selectedIndex) select(target)
                    }
                },
                onDragCancel = { dragging = false },
            )
        }
        Box(gesture) {
            // 录制层仅含背板与真实标签，镜片在其同级绘制，避免递归捕获和重复无障碍节点。
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .layerBackdrop(tabsBackdrop)
                    .drawBackdrop(
                        backdrop = backdrop ?: fallback,
                        shape = { panelShape },
                        effects = { if (backdrop != null) blur(blurRadius.toPx()) },
                        highlight = { Highlight.Default.copy(alpha = if (dark) 0.35f else 0.6f) },
                        shadow = { Shadow(radius = 12.dp, alpha = 0.6f) },
                        onDrawSurface = {
                            if (backdrop != null) drawRect(colors.surface.copy(alpha = if (dark) 0.38f else 0.32f))
                        },
                    )
                    .border(0.75.dp, Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = if (dark) 0.16f else 0.65f),
                        colors.outlineVariant.copy(alpha = 0.25f),
                    )), panelShape)
                    .padding(4.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    val focused by sources[index].collectIsFocusedAsState()
                    Column(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp)
                            .selectable(
                                selected = selectedIndex == index,
                                interactionSource = sources[index], indication = null,
                                role = Role.Tab, onClick = { select(index) },
                            )
                            .then(if (focused) Modifier.border(1.5.dp, colors.onSurface, lensShape) else Modifier)
                            .padding(horizontal = 2.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                    ) {
                        Box(Modifier.clearAndSetSemantics {}) { icon(index, colors.onSurface) }
                        Text(label, color = colors.onSurface, style = MaterialTheme.typography.labelMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.matchParentSize().padding(4.dp).clearAndSetSemantics {}) {
                Box(
                    Modifier.align(AbsoluteAlignment.TopLeft).width(itemWidth).fillMaxHeight()
                        .drawBackdrop(
                            backdrop = tabsBackdrop,
                            shape = { lensShape },
                            effects = {
                                // API33+ 使用库提供的真实折射；旧系统保留同样的手势和高可读选中层。
                                lens((8f + 8f * pressure).dp.toPx(), (12f + 10f * pressure).dp.toPx())
                            },
                            highlight = { Highlight.Default.copy(width = 0.8.dp, alpha = 0.65f + pressure * 0.35f) },
                            shadow = { Shadow(radius = 6.dp, alpha = 0.65f) },
                            layerBlock = {
                                translationX = (if (direction == LayoutDirection.Rtl) labels.lastIndex - position else position) * itemPixels
                                alpha = visibility
                                scaleX = 1f + 0.055f * pressure
                                scaleY = 1f + 0.08f * pressure
                            },
                            onDrawSurface = {
                                drawRect((if (dark) Color.White else Color.Black).copy(alpha = 0.09f - 0.035f * pressure))
                            },
                        ),
                )
            }
        }
    }
}
