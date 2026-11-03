package com.tanuki.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SetupGuideStep(
    val id: String,
    val title: String,
    val caption: String,
    val complete: Boolean,
    val accessible: Boolean = true,
)

/** 只负责展示，完成状态与安装状态仍由调用方提供。 */
@Composable
fun SetupGuide(
    steps: List<SetupGuideStep>,
    selectedStep: Int,
    onStep: (Int) -> Unit,
    busy: Boolean,
    isReady: Boolean,
    onOpenDsh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (steps.isEmpty()) return
    val index = selectedStep.coerceIn(steps.indices)
    val current = steps[index]
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(tanukiHeroBrush()),
                    contentAlignment = Alignment.Center,
                ) {
                    TanukiIcon(TanukiGlyph.Terminal, tint = colors.primary, modifier = Modifier.size(23.dp))
                }
                Text("TANUKI · 本地开发空间", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            Text("让开发环境，\n随灵感就位。", style = MaterialTheme.typography.headlineLarge)
            Text(
                "准备本地环境，开始创造。",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stepWidth = maxOf(maxWidth / steps.size, (68 * fontScale).dp)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(vertical = 4.dp),
            ) {
                steps.forEachIndexed { position, step ->
                    val selected = position == index
                    val indicatorBase = maxOf(34.dp, (24 * fontScale).dp)
                    val indicatorSize by animateDpAsState(
                        targetValue = if (selected) indicatorBase + 4.dp else indicatorBase,
                        animationSpec = tween(220),
                        label = "stepIndicatorSize",
                    )
                    val indicatorColor by animateColorAsState(
                        targetValue = when {
                            selected -> colors.primary
                            step.complete -> colors.primaryContainer
                            else -> colors.surfaceContainerHigh
                        },
                        animationSpec = tween(220),
                        label = "stepIndicatorColor",
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                        animationSpec = tween(220),
                        label = "stepLabelColor",
                    )
                    Column(
                        Modifier.width(stepWidth).heightIn(min = 76.dp)
                            .selectable(selected, enabled = step.accessible, role = Role.RadioButton, onClick = { onStep(position) })
                            .clearAndSetSemantics {
                                contentDescription = "第 ${position + 1} 步，${step.title}"
                                role = Role.RadioButton
                                this.selected = selected
                                stateDescription = if (step.complete) "已完成" else if (step.accessible) "待完成" else "先完成前一步"
                                if (!step.accessible) disabled()
                                onClick { if (step.accessible) { onStep(position); true } else false }
                            }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().height(indicatorBase + 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.weight(1f).height(2.dp).background(
                                    if (position > 0 && steps[position - 1].complete) colors.primary.copy(alpha = 0.45f)
                                    else colors.outlineVariant.copy(alpha = 0.55f)
                                )
                            )
                            Box(Modifier.size(indicatorSize).clip(CircleShape).background(indicatorColor), contentAlignment = Alignment.Center) {
                                if (step.complete) GuideCheck(if (selected) colors.onPrimary else colors.onPrimaryContainer)
                                else Text(
                                    "${position + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                                )
                            }
                            Box(
                                Modifier.weight(1f).height(2.dp).background(
                                    if (step.complete) colors.primary.copy(alpha = 0.45f)
                                    else colors.outlineVariant.copy(alpha = 0.55f)
                                )
                            )
                        }
                        Text(
                            step.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = labelColor,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isReady) "运行环境已就绪" else if (current.id == "tools") "补齐基础工具" else "安装 ${current.title}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text("${index + 1} / ${steps.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Text(current.caption, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        content()
        if (isReady) Button(
            onClick = onOpenDsh,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("进入 DSH") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) TextButton(onClick = { onStep(index - 1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("上一步") }
            else Spacer(Modifier.width(1.dp))
            if (index < steps.lastIndex) TextButton(
                onClick = { onStep(index + 1) },
                enabled = steps[index + 1].accessible,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (current.complete) "下一步：${steps[index + 1].title}" else "查看下一步") }
        }
    }
}

@Composable
private fun GuideCheck(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path = Path().apply { moveTo(5f, 12.5f); lineTo(10f, 17f); lineTo(19f, 7f) }
            drawPath(path, color, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        }
    }
}
