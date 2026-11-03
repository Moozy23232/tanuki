package com.tanuki.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanuki.runtime.*
import kotlinx.coroutines.delay
import java.util.Locale

private data class ProgressItem(val id: String, val title: String, val fraction: Float?, val detail: String,
    val timing: OperationTiming)

/** Outside every page's scroll area, including the DSH and browser tabs. */
@Composable
internal fun OperationBanner(snapshot: Snapshot, transfers: List<FileTransferProgress>, onCancel: () -> Unit,
    modifier: Modifier = Modifier) {
    val items = buildList {
        if (snapshot.operationActive) {
            val download = snapshot.download
            add(ProgressItem("runtime", snapshot.stage.ifBlank { "正在准备操作" }, snapshot.measuredFraction(), when {
                snapshot.phasePercent != null -> "当前安装阶段 · 不代表整个安装已完成"
                download != null -> transferDetail(download.received, download.total) +
                    if (download.reused) " · 已校验缓存" else " · ${download.source}"
                snapshot.bytes > 0 -> transferDetail(snapshot.bytes, snapshot.total)
                else -> "正在执行，完成后会自动更新"
            }, snapshot.timing))
        }
        transfers.forEach { add(ProgressItem("file-${it.id}", it.label,
            if (it.total > 0) (it.received.toDouble() / it.total).toFloat().coerceIn(0f, 1f) else null,
            transferDetail(it.received, it.total), it.timing)) }
    }
    if (items.isEmpty()) return
    var details by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(items.first().title, Modifier.weight(1f).padding(top = 10.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (items.size > 1) "进度 · ${items.size}" else "查看进度")
                }
            }
            ProgressBody(items.first(), compact = true)
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("当前操作进度") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.forEach { item ->
                key(item.id) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    ProgressBody(item, compact = false)
                }
            }
            Text("有总量时显示当前阶段的真实进度；解析、解压或等待服务响应时显示持续动效，不估造整体百分比。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.operationActive && snapshot.service !in setOf(ServiceState.STARTING, ServiceState.STOPPING) &&
                snapshot.update !in setOf(UpdateState.SWITCHING, UpdateState.RESTORING)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消当前操作") }
                Text("取消会等待当前请求或清理结束；不要反复点击安装。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("收起") } })
}

@Composable
private fun ProgressBody(item: ProgressItem, compact: Boolean) {
    val appearedAt = remember(item.id) { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(item.id) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    val start = item.timing.startedAtMillis.takeIf { it > 0 } ?: appearedAt
    val last = item.timing.lastActivityAtMillis.takeIf { it > 0 } ?: start
    val elapsed = ((now - start).coerceAtLeast(0) / 1000)
    val quiet = ((now - last).coerceAtLeast(0) / 1000)
    val percent = item.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    val progressModifier = Modifier.fillMaxWidth().semantics {
        contentDescription = "操作进度"
        stateDescription = if (percent == null) "进行中，无法估算百分比" else "当前阶段 $percent%"
    }
    val colors = MaterialTheme.colorScheme
    if (item.fraction != null) LinearProgressIndicator(
        progress = { item.fraction },
        modifier = progressModifier,
        color = colors.primary,
        trackColor = colors.primaryContainer,
    )
    else LinearProgressIndicator(
        modifier = progressModifier,
        color = colors.primary,
        trackColor = colors.primaryContainer,
    )
    Text("已用 ${duration(elapsed)} · " + when (percent) {
        null -> "进度暂不可估算"
        100 -> "当前阶段 100%，继续处理"
        else -> "当前阶段 $percent%"
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(item.detail, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 2 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis)
    if (quiet >= 60) Text(if (compact) "${duration(quiet)}没有新进展，点“查看进度”了解状态"
        else "已 ${duration(quiet)}未收到新进展。网络、解压或安装脚本可能仍在等待；请检查网络，必要时在任务入口取消后重试。计时和动效不代表后台一定正常。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
}

private fun duration(seconds: Long): String = if (seconds >= 3600) "${seconds / 3600}时${seconds % 3600 / 60}分${seconds % 60}秒"
    else if (seconds >= 60) "${seconds / 60}分${seconds % 60}秒" else "${seconds}秒"
private fun bytes(value: Long): String = when {
    value >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / 1048576.0)
    value >= 1024 -> String.format(Locale.ROOT, "%.1f KB", value / 1024.0)
    else -> "$value B"
}
private fun transferDetail(received: Long, total: Long): String = if (total > 0) "${bytes(received)} / ${bytes(total)}"
    else if (received > 0) "已传输 ${bytes(received)} · 总大小未知" else "正在连接或准备文件 · 总大小未知"
