package com.tanuki.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/** Presentation only: the host supplies actual completion and active installation state. */
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stepWidth = maxOf((maxWidth - 24.dp) / steps.size, (64 * fontScale).dp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { position, step ->
                    val selected = position == index
                    Column(Modifier.width(stepWidth).heightIn(min = 64.dp)
                        .background(if (selected) colors.primaryContainer else colors.surface, MaterialTheme.shapes.medium)
                        .selectable(selected, enabled = step.accessible, role = Role.RadioButton, onClick = { onStep(position) })
                        .clearAndSetSemantics {
                            contentDescription = "第 ${position + 1} 步，${step.title}"
                            role = Role.RadioButton
                            this.selected = selected
                            stateDescription = if (step.complete) "已完成" else if (step.accessible) "待完成" else "先完成前一步"
                            if (!step.accessible) disabled()
                            onClick { if (step.accessible) { onStep(position); true } else false }
                        }.padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (step.complete) GuideCheck(if (selected) colors.onPrimaryContainer else colors.primary)
                        else Text("${position + 1}", style = MaterialTheme.typography.labelMedium,
                            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                        Text(step.title, style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (isReady) "运行环境已就绪" else if (current.id == "tools") "补齐基础工具" else "安装 ${current.title}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() })
                Text("${index + 1} / ${steps.size}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Text(current.caption, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        content()
        if (isReady) Button(onClick = onOpenDsh, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium) {
            Text("进入 DSH")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (index > 0) TextButton(onClick = { onStep(index - 1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("上一步") }
            else Spacer(Modifier.width(1.dp))
            if (index < steps.lastIndex) TextButton(onClick = { onStep(index + 1) }, enabled = steps[index + 1].accessible,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (current.complete) "下一步：${steps[index + 1].title}" else "查看下一步")
            }
        }
    }
}

@Composable
private fun GuideCheck(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(18.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val path = Path().apply { moveTo(5f, 12.5f); lineTo(10f, 17f); lineTo(19f, 7f) }
            drawPath(path, color, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        }
    }
}
