package com.tanuki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class BrowserMenuItem(val title: String, val icon: ImageVector, val enabled: Boolean = true)

/** Thumb-reachable, two-level actions. Preferences are not mixed into a long overflow menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserMenuSheet(tools: Boolean, loaded: Boolean, authorized: Boolean,
    onChoose: (String) -> Unit, onBack: () -> Unit, onDismiss: () -> Unit) {
    val entries = if (tools) listOf(
        BrowserMenuItem("页内查找", Icons.Default.Search, loaded),
        BrowserMenuItem("网页缩放", Icons.Default.ZoomIn, loaded),
        BrowserMenuItem("截取网页画面", Icons.Default.Screenshot, loaded),
        BrowserMenuItem("页面显示模式", Icons.Default.Devices))
    else listOf(
        BrowserMenuItem("收藏夹", Icons.Default.Bookmarks),
        BrowserMenuItem("历史记录", Icons.Default.History),
        BrowserMenuItem("下载与截图", Icons.Default.Download),
        BrowserMenuItem("收藏当前页", Icons.Default.BookmarkAdd, loaded),
        BrowserMenuItem("工具箱", Icons.Default.Construction),
        BrowserMenuItem("浏览器设置", Icons.Default.Settings))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tools) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回浏览器菜单") }
            Text(if (tools) "工具箱" else "浏览器", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭浏览器菜单") }
        }
        val columns = if (LocalDensity.current.fontScale > 1.4f) 2 else 3
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries, key = { it.title }) { item ->
                Column(Modifier.fillMaxWidth().clickable(enabled = item.enabled) { onChoose(item.title) }
                    .heightIn(min = 88.dp).padding(horizontal = 8.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    Icon(item.icon, null, Modifier.size(24.dp), tint = color)
                    Text(item.title, color = color, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (authorized) Icons.Default.SmartToy else Icons.Default.Person, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (authorized) "AI 可操作 · 点网页可收回" else "你在操作 · AI 已暂停", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onChoose("权限与后台") }) { Text("管理") }
        }
    }
}

@Composable
internal fun BrowserNativePage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回浏览器") }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                actions()
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
internal fun BrowserPreference(title: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
