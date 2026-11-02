package com.tanuki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tanuki.runtime.SourceProbeStatus
import com.tanuki.runtime.SourceSpeedSnapshot

@Composable
fun SourceSpeedControls(
    componentTitle: String,
    sources: List<ComponentSourceOption>,
    selectedSource: String,
    snapshot: SourceSpeedSnapshot,
    canSelect: Boolean,
    canTest: Boolean,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onSource: (String) -> Unit,
    automatic: Boolean = true,
    probeKey: String = "",
    onPrepare: () -> Unit = {}
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var now by remember(snapshot.measuredAtMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.measuredAtMillis) {
        if(snapshot.measuredAtMillis>0)while(true) {
            kotlinx.coroutines.delay(30_000);now=System.currentTimeMillis()
        }
    }
    val fresh=snapshot.measuredAtMillis>0 && now-snapshot.measuredAtMillis in 0 until 10*60*1000L
    var prepared by remember(componentTitle, probeKey, automatic) { mutableStateOf(false) }
    LaunchedEffect(componentTitle, probeKey, automatic, canTest) {
        // A probe toggles canTest itself. Cancellation/completion must not start it again.
        if(automatic && canTest && !prepared) { prepared = true; onPrepare() }
    }
    val resolved = sources.firstOrNull { it.id == selectedSource }
    val sample = snapshot.results[selectedSource]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            automatic && snapshot.running -> "正在自动测速 · ${snapshot.results.values.count { it.status != SourceProbeStatus.TESTING }} / ${sources.size}"
            automatic && snapshot.measuredAtMillis>0 && !fresh -> "上次测速已过期，安装前会重新测速"
            automatic && snapshot.bestSourceId != null && resolved != null -> "已自动选择：${resolved.title}"
            automatic && snapshot.measuredAtMillis > 0 -> "测速暂不可用，下载时逐个尝试"
            automatic -> "自动测速并选择最快可用源"
            else -> "手动优先：${resolved?.title ?: selectedSource}"
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if(snapshot.running) {
            LinearProgressIndicator(modifier=Modifier.fillMaxWidth().semantics { contentDescription="$componentTitle 下载源测速进行中" })
        }
        if(!snapshot.running && sample?.status == SourceProbeStatus.SUCCESS) {
            Text("${if(fresh)"本次采样" else "上次采样"} ${mbps(sample.bytesPerSecond)} MB/s · 连接 ${sample.latencyMs} ms",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = {
            dialogOpen = true
            if(!snapshot.running && snapshot.results.isEmpty() && canTest)onTest()
        }, enabled = sources.isNotEmpty(),modifier=Modifier.heightIn(min=48.dp)) {
            Text("查看测速与下载源")
        }
    }

    if (dialogOpen) {
        val completed = snapshot.results.values.filter { it.status != SourceProbeStatus.TESTING }
        val best = if(snapshot.running || !fresh)null else snapshot.bestSourceId
        val hasResults = snapshot.results.isNotEmpty()

        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("$componentTitle · 下载源测速") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if(hasResults && !snapshot.running && !fresh)Text("以下为上次采样结果，已超过 10 分钟。安装时会重新测速。",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!automatic)TextButton(onClick={onSource("auto");dialogOpen=false},enabled=canSelect,
                        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("恢复自动择优") }
                    if (snapshot.running) {
                        // A finished-source count is not a time estimate for the in-flight source.
                        LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                        Text("正在测速 · ${completed.size} / ${sources.size} 个源", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        sources.sortedBy { if(it.id==best)0 else 1 }.forEach { option ->
                            SourceSpeedRow(
                                option = option,
                                selected = option.id == selectedSource,
                                isBest = option.id == best,
                                result = snapshot.results[option.id],
                                canSelect = canSelect && !snapshot.running,
                                running = snapshot.running,
                                onSource = { id -> onSource(id); dialogOpen=false }
                            )
                        }
                    }
                    if (hasResults && !snapshot.running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onTest, enabled = canTest) { Text("重新测速") }
                            TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                        }
                    }
                    Text("每个源最多采样 256 KiB，比较首字节延迟和传输耗时。结果随当前网络变化。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(automatic)"自动模式会使用本次最快可用源。点击“使用”可改为手动优先。" else "优先使用你选择的源，下载失败后尝试其他可用源。",
style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                if (!hasResults && !snapshot.running) {
                    Button(onClick = onTest, enabled = canTest) { Text("开始测速") }
                }
            },
            dismissButton = {
                if (snapshot.running) {
                    TextButton(onClick = onCancel) { Text("取消") }
                } else if (!hasResults) {
                    TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                }
            }
        )
    }
}

@Composable
private fun SourceSpeedRow(
    option: ComponentSourceOption,
    selected: Boolean,
    isBest: Boolean,
    result: com.tanuki.runtime.SourceProbeResult?,
    canSelect: Boolean,
    running: Boolean,
    onSource: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) cs.primaryContainer else cs.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp),
            modifier=Modifier.padding(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(option.title,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,color=cs.onSurface)
                if(selected || isBest)Text(
                    when { selected && isBest -> "当前下载源 · 本次最快"; selected -> "当前下载源"; else -> "本次最快" },
                    style=MaterialTheme.typography.labelMedium,color=cs.onSurface)
                SourceSpeedDetail(result,running)
            }
            if(canSelect)OutlinedButton(onClick={onSource(option.id)},modifier=Modifier.heightIn(min=48.dp)
                .semantics { contentDescription="使用${option.title}" }) { Text(if(selected)"选定" else "使用") }
        }
    }
}

@Composable
private fun SourceSpeedDetail(result: com.tanuki.runtime.SourceProbeResult?,running:Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when (result?.status) {
        SourceProbeStatus.SUCCESS -> Text(
            "连接 ${result.latencyMs} ms · ${mbps(result.bytesPerSecond)} MB/s",
            style = MaterialTheme.typography.bodySmall, color = color
        )
        SourceProbeStatus.FAILED -> Text(
            "连接失败" + if (result.error.isNotBlank()) "：${result.error}" else "",
            style = MaterialTheme.typography.bodySmall, color = color
        )
        SourceProbeStatus.TESTING -> Text("测试中…",style=MaterialTheme.typography.bodySmall,color=color)
        null -> Text(if(running)"等待测试" else "未测试",style=MaterialTheme.typography.bodySmall,color=color)
    }
}

private fun mbps(bytesPerSecond: Long): String {
    val m = bytesPerSecond / (1024.0 * 1024.0)
    return if (m >= 10) "%.0f".format(m) else "%.1f".format(m)
}
