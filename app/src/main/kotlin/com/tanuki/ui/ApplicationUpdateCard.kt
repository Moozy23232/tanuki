package com.tanuki.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tanuki.update.AppUpdatePhase
import com.tanuki.update.AppUpdateState
import kotlinx.coroutines.delay
import java.util.Locale

/** 单列操作兼容小屏和大字体，状态、真实字节进度与安装确认始终分开。 */
@Composable
internal fun ApplicationUpdateCard(state: AppUpdateState, currentVersion: String, installAllowed: Boolean,
    onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.busy, state.startedAt) {
        while (state.busy) { now = SystemClock.elapsedRealtime(); delay(1_000) }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Tanuki 应用更新", style = MaterialTheme.typography.titleLarge)
            Text("当前版本 $currentVersion", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.message, style = MaterialTheme.typography.bodyMedium,
                color = if (state.phase == AppUpdatePhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            OutlinedButton(onClick = onCheck, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("检查更新")
            }
            state.candidate?.let { candidate ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("可用版本 ${candidate.versionName}", style = MaterialTheme.typography.titleMedium)
                Text("${candidate.packageName} · ${megabytes(candidate.bytes)} MiB", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.CANCELLED, AppUpdatePhase.FAILED)) {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下载更新") }
                }
                if (state.phase == AppUpdatePhase.READY) {
                    Button(onClick = onInstall, enabled = installAllowed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("安装更新") }
                    if (!installAllowed) Text("请先停止 DSH，并等待环境和文件任务结束；下载文件会保留。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.busy) {
                if (state.phase == AppUpdatePhase.DOWNLOADING && state.total > 0) {
                    LinearProgressIndicator(progress = (state.received.toDouble() / state.total).toFloat().coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "应用更新下载进度" })
                    Text("${megabytes(state.received)} / ${megabytes(state.total)} MiB", style = MaterialTheme.typography.labelMedium)
                } else LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "应用更新正在处理" })
                Text("已用 ${((now - state.startedAt).coerceAtLeast(0) / 1_000)} 秒", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消更新任务") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("仅接收本项目同包名、同签名且版本更高的 APK；下载后校验大小和 SHA-256，安装前再次核对。Android 安装器会继续校验签名并等待你的确认。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("覆盖更新保留已安装的 Ubuntu、Node.js、DSH 和项目。不要卸载旧应用，也不要清除数据；正式签名与开发签名不能相互覆盖。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024))
