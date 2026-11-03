package com.tanuki.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ComponentSourceOption(val id: String, val title: String, val detail: String)

data class ComponentVersionOption(val id: String, val title: String, val detail: String)

data class ComponentProgressModel(
    val label: String,
    val source: String,
    val received: Long,
    val total: Long,
    val bytesPerSecond: Long,
    val reused: Boolean = false
)

data class RuntimeComponentCardModel(
    val id: String,
    val title: String,
    val version: String,
    val installed: Boolean,
    val sources: List<ComponentSourceOption>,
    val selectedSource: String,
    val actionsEnabled: Boolean,
    val canInstall: Boolean,
    val dependencyHint: String,
    val progress: ComponentProgressModel?,
    val versions: List<ComponentVersionOption> = emptyList(),
    val selectedVersion: String = "",
    val selectionNotice: String = "",
    val guided: Boolean = false
)

@Composable
fun RuntimeComponentCard(
    model: RuntimeComponentCardModel,
    onSource: (String) -> Unit,
    onInstall: () -> Unit,
    onReinstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    onVersion: (String) -> Unit = {},
    sourceControls: @Composable () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    var isExpanded by rememberSaveable(model.id) { mutableStateOf(false) }
    val p = model.progress

    // 下载出现时自动展开：活动进度不允许被隐藏。
    LaunchedEffect(model.progress) {
        if (model.progress != null) isExpanded = true
    }

    Column(Modifier.fillMaxWidth()) {
        // 扁平头部行：整行可点，尾部 48dp 展开目标；不包整卡 clickable，
        // 展开内容中的下拉与按钮动作不受干扰。
        val chevronRotation by animateFloatAsState(
            targetValue = if (isExpanded) 180f else 0f,
            label = "chevronRotation"
        )
        if (!model.guided) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = if (isExpanded) "收起" else "展开"
                ) {
                    if (p == null) isExpanded = !isExpanded else isExpanded = true
                }
                .semantics {
                    stateDescription = if (isExpanded) "已展开" else "已收起"
                }
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    model.version,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = cs.onSurfaceVariant
                )
            }
            // 状态点 + 文本（非胶囊 chip，低装饰，仅 Material 角色）。
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (model.installed) cs.primary else cs.outline)
            )
            Text(
                if (model.installed) "已安装" else "未安装",
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurfaceVariant
            )
            Box(
                Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(chevronRotation)
                )
            }
        }

        AnimatedVisibility(visible = isExpanded || model.guided) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (model.guided) 0.dp else 20.dp,
                        top = 0.dp,
                        end = if (model.guided) 0.dp else 20.dp,
                        bottom = if (model.guided) 0.dp else 20.dp,
                    )
            ) {
                if (!model.guided && model.dependencyHint.isNotBlank()) {
                    Text(
                        model.dependencyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                }

                if (model.progress == null) {
                    val versionSelectorEnabled = model.actionsEnabled
                    if (model.versions.isNotEmpty()) {
                        VersionSelector(
                            label = if (model.id == "ubuntu") "Ubuntu 镜像" else "Node.js 版本",
                            options = model.versions,
                            selectedVersion = model.selectedVersion,
                            enabled = versionSelectorEnabled,
                            notice = model.selectionNotice,
                            installedVersion = if (model.installed) model.version else "",
                            onSelect = onVersion
                        )
                    }
                    if(!model.guided)SourceSelector(
                        options = model.sources,
                        selectedSource = model.selectedSource,
                        enabled = model.actionsEnabled,
                        onSelect = onSource
                    )
                    sourceControls()
                }

                val progress = model.progress
                if (progress != null) {
                    ComponentDownloadProgress(progress)
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        enabled = true
                    ) { Text("取消下载") }
                } else {
                    val canAct = model.actionsEnabled && model.canInstall
                    val busy = !model.actionsEnabled
                    if (model.installed) {
                        val selectionMatchesInstalled =
                            !model.installed || model.selectedVersion.isBlank() || model.selectedVersion == model.version
                        val reinstallLabel = if (selectionMatchesInstalled) "重新安装" else "安装所选版本"
                        if (model.guided && selectionMatchesInstalled) {
                            Text("已安装 ${model.title} ${model.version}",style=MaterialTheme.typography.bodyMedium,
                                color=cs.onSurfaceVariant)
                        } else Button(
                            onClick = onReinstall,
                            enabled = canAct,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) { Text(reinstallLabel) }
                        if (!model.guided) TextButton(
                            onClick = onUninstall,
                            enabled = model.actionsEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("卸载") }
                    } else {
                        Button(
                            onClick = onInstall,
                            enabled = canAct,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        ) {
                            Text(if (busy) "进行中…" else if(model.guided)"安装 ${model.title}" else "安装")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorSurface(
    label: String,
    value: String,
    enabled: Boolean,
    onOpen: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { if (enabled) onOpen() },
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics { contentDescription = "$label：$value" }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VersionSelector(
    label: String,
    options: List<ComponentVersionOption>,
    selectedVersion: String,
    enabled: Boolean,
    notice: String,
    installedVersion: String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.id == selectedVersion }
    val currentLabel = current?.title ?: label
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            SelectorSurface(
                label = label,
                value = currentLabel,
                enabled = enabled,
                onOpen = { open = true }
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(selected = opt.id == selectedVersion, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        opt.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    if (opt.detail.isNotBlank()) {
                                        Text(
                                            opt.detail,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            onSelect(opt.id)
                            open = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
        val caption = buildString {
            if (installedVersion.isNotBlank()) append("当前已安装：$installedVersion。")
            if (notice.isNotBlank()) append(notice)
        }
        if (caption.isNotBlank()) {
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceSelector(
    options: List<ComponentSourceOption>,
    selectedSource: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.id == selectedSource }
    val label = current?.title ?: "选择下载源"
    Box {
        SelectorSurface(
            label = "下载源",
            value = label,
            enabled = enabled,
            onOpen = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = opt.id == selectedSource,
                                onClick = null,
                                enabled = enabled
                            )
                            Column {
                                Text(opt.title, style = MaterialTheme.typography.bodyMedium)
                                if (opt.detail.isNotBlank()) {
                                    Text(
                                        opt.detail,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        if (enabled) {
                            onSelect(opt.id)
                            open = false
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
    }
}

@Composable
fun ComponentDownloadProgress(p: ComponentProgressModel) {
    val known=p.total>0
    val percent=if(known)(p.received.toDouble()/p.total*100).toInt().coerceIn(0,100) else null
    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(p.label,style=MaterialTheme.typography.titleMedium)
            Text(if(p.reused)"使用已校验的本地缓存" else p.source,
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(known) {
                Text("$percent%",style=MaterialTheme.typography.headlineLarge,fontFamily=FontFamily.Monospace,
                    fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress={percent!!.toFloat()/100f},modifier=Modifier.fillMaxWidth().semantics {
                    contentDescription="下载进度 $percent%，已接收 ${byteSize(p.received)}，共 ${byteSize(p.total)}"
                })
                Text("${byteSize(p.received)} / ${byteSize(p.total)}",style=MaterialTheme.typography.bodyMedium)
            } else {
                LinearProgressIndicator(modifier=Modifier.fillMaxWidth().semantics {
                    contentDescription="下载进行中，总大小未知"
                })
                Text("总大小未知 · 已接收 ${byteSize(p.received)}",style=MaterialTheme.typography.bodyMedium)
            }
            if(p.bytesPerSecond>0) {
                val seconds=if(known)((p.total-p.received).coerceAtLeast(0)/p.bytesPerSecond) else 0
                Text("${byteSize(p.bytesPerSecond)}/s"+(if(seconds>0)" · 约剩余 ${etaText(seconds)}" else ""),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


private fun byteSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun etaText(seconds: Long): String = when {
    seconds >= 3600 -> "${seconds / 3600}时${(seconds % 3600) / 60}分"
    seconds >= 60 -> "${seconds / 60}分${seconds % 60}秒"
    else -> "${seconds}秒"
}
