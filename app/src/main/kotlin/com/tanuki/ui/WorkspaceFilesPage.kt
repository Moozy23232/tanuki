package com.tanuki.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tanuki.storage.FileLocation
import com.tanuki.storage.WorkspaceFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceFilesPage(
    manager: WorkspaceFileManager, onImport: () -> Unit, onExport: () -> Unit,
    modifier: Modifier = Modifier, runtimeRunning: Boolean = false, onStop: () -> Unit = {}
) {
    var location by rememberSaveable { mutableStateOf(FileLocation.WORKSPACE) }
    val files = remember(manager, location) { manager.inLocation(location) }
    var directory by rememberSaveable(location) { mutableStateOf("") }
    var entries by remember(files) { mutableStateOf(emptyList<WorkspaceFileManager.Entry>()) }
    var selected by remember(files) { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<WorkspaceFileManager.Preview?>(null) }
    var previewEntry by remember { mutableStateOf<WorkspaceFileManager.Entry?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    var actionEntries by remember { mutableStateOf(emptyList<WorkspaceFileManager.Entry>()) }
    var name by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var draftOriginal by remember { mutableStateOf("") }
    var draftRevision by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    var destinationPicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var locations by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    fun report(e: Exception) {
        error = e.message?.takeIf { text -> text.any { it in '\u4e00'..'\u9fff' } }
            ?: "操作失败，请检查文件是否仍存在、空间是否充足，然后重试。"
    }
    fun runTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; feedback = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally { busy = false }
        }
    }
    fun navigate(path: String) = runTask {
        val loaded = files.list(path)
        directory = path; entries = loaded; selected = emptySet(); query = ""
    }
    fun begin(kind: String, targets: List<WorkspaceFileManager.Entry> = emptyList()) {
        actionEntries = targets; name = targets.singleOrNull()?.name.orEmpty(); content = ""; draftOriginal = ""
        error = null; action = kind; menu = false
        destinationPicker = kind == "复制" || kind == "移动"
    }
    fun dismissEditor() {
        if (!busy) { if (content != draftOriginal && (action == "编辑文本" || action == "新建文本文件")) discard = true else action = null }
    }
    fun copyPath(path: String) { clipboard.setText(AnnotatedString(files.guestPath(path))); feedback = "已复制 Agent 可用的路径" }
    fun toggle(path: String) { selected = if (path in selected) selected - path else selected + path }
    LaunchedEffect(files, runtimeRunning) {
        error = null; selected = emptySet(); preview = null
        if (runtimeRunning) { entries = emptyList(); return@LaunchedEffect }
        busy = true
        try { entries = files.list(directory) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { report(e) }
        finally { busy = false }
    }
    BackHandler(enabled = action == null && preview == null && (selected.isNotEmpty() || searching || directory.isNotEmpty())) {
        when { selected.isNotEmpty() -> selected = emptySet(); searching -> { searching = false; query = "" }
            !busy -> navigate(directory.substringBeforeLast('/', "")) }
    }
    val shown = remember(entries, query, hidden, sort) {
        val ordering = when (sort) {
            1 -> compareByDescending<WorkspaceFileManager.Entry> { it.modified }
            2 -> compareByDescending { it.bytes }
            else -> compareBy { it.name.lowercase(Locale.ROOT) }
        }
        entries.filter { (hidden || !it.name.startsWith('.')) && it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<WorkspaceFileManager.Entry> { it.directory }.then(ordering).thenBy { it.name })
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { locations = true }, enabled = !busy) { Text(location.title); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(locations, { locations = false }) {
                        FileLocation.entries.forEach { area -> DropdownMenuItem(text = { Text(area.title + if (!area.writable) " · 只读" else "") },
                            onClick = { locations = false; directory = ""; query = ""; location = area }) }
                    }
                }
                IconButton(onClick = { searching = !searching; query = "" }, enabled = !busy && !runtimeRunning) { Icon(Icons.Default.Search, "搜索当前文件夹") }
                IconButton(onClick = { navigate(directory) }, enabled = !busy && !runtimeRunning) { TanukiIcon(TanukiGlyph.Refresh, "刷新文件") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Menu, "排序与显示") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("名称", "最近修改", "文件大小").forEachIndexed { index, label -> DropdownMenuItem(text = { Text((if (sort == index) "✓ " else "") + label) }, onClick = { sort = index; sortMenu = false }) }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(if (grid) "切换为列表" else "切换为网格") }, onClick = { grid = !grid; sortMenu = false })
                        DropdownMenuItem(text = { Text(if (hidden) "隐藏点号文件" else "显示隐藏文件") }, onClick = { hidden = !hidden; sortMenu = false })
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy && !runtimeRunning) { Icon(Icons.Default.MoreVert, "文件操作") }
                    DropdownMenu(menu, { menu = false }) {
                        if (location.writable) {
                            listOf("新建文件夹", "新建文本文件").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { begin(label) }) }
                            DropdownMenuItem(text = { Text("导入文件到工作区") }, onClick = { menu = false; onImport() })
                            DropdownMenuItem(text = { Text("导出整个工作区") }, onClick = { menu = false; onExport() })
                        }
                        DropdownMenuItem(text = { Text("复制当前目录路径") }, onClick = { menu = false; copyPath(directory) })
                    }
                }
            }
            if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true, label = { Text("搜索当前文件夹") }, trailingIcon = { IconButton(onClick = { query = ""; searching = false }) { Icon(Icons.Default.Close, "关闭搜索") } })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (directory.isNotEmpty()) IconButton(onClick = { navigate(directory.substringBeforeLast('/', "")) }, enabled = !busy && !runtimeRunning) { TanukiIcon(TanukiGlyph.Back, "返回上一级") }
                TextButton(onClick = { navigate("") }, enabled = !busy && !runtimeRunning) { Text(location.guestPath) }
                val parts = directory.split('/').filter { it.isNotEmpty() }
                parts.forEachIndexed { index, part ->
                    Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { navigate(parts.take(index + 1).joinToString("/")) }, enabled = !busy) { Text(part, maxLines = 1) }
                }
            }
            if (runtimeRunning) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DSH 正在使用文件。停止后可安全管理，项目内容会保留。")
                OutlinedButton(onClick = onStop) { Text("停止 DSH") }
            } else if (!location.writable) Text("只读位置 · 按 Agent 实际挂载路径显示，不修改运行环境", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (action == null) error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
            feedback?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (selected.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "取消选择") }
                    Text("已选 ${selected.size}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { selected = shown.map { it.path }.toSet() }) { Text("全选") }
                    if (location.writable) listOf("复制", "移动", "删除").forEach { label -> TextButton(onClick = { begin(label, entries.filter { it.path in selected }) }, enabled = !busy) { Text(label) } }
                }
            }
            val row: @Composable (WorkspaceFileManager.Entry, Boolean) -> Unit = { entry, tile ->
                var expanded by remember(entry.path) { mutableStateOf(false) }
                Surface(color = if (entry.path in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().combinedClickable(enabled = !busy,
                        onClick = { if (selected.isNotEmpty()) toggle(entry.path) else if (entry.directory) navigate(entry.path)
                            else runTask { preview = files.preview(entry.path); previewEntry = entry } },
                        onLongClick = { toggle(entry.path) }).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected.isNotEmpty()) Checkbox(checked = entry.path in selected, onCheckedChange = { toggle(entry.path) })
                            else TanukiIcon(if (entry.directory) TanukiGlyph.Folder else TanukiGlyph.File, modifier = Modifier.size(if (tile) 32.dp else 24.dp),
                                tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!tile) {
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { FileEntryLabel(entry, false) }
                            } else Spacer(Modifier.weight(1f))
                            Box {
                                IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "${entry.name}的操作") }
                                DropdownMenu(expanded, { expanded = false }) {
                                    DropdownMenuItem(text = { Text("复制 Agent 路径") }, onClick = { expanded = false; copyPath(entry.path) })
                                    DropdownMenuItem(text = { Text("选择") }, onClick = { expanded = false; toggle(entry.path) })
                                    if (location.writable) listOf("重命名", "复制", "移动", "删除").forEach { label -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; begin(label, listOf(entry)) }) }
                                }
                            }
                        }
                        if (tile) FileEntryLabel(entry, true)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty() && !busy && error == null && !runtimeRunning) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (query.isNotEmpty()) "没有匹配的文件" else if (entries.isNotEmpty()) "此处仅有隐藏文件" else "文件夹为空", style = MaterialTheme.typography.titleMedium)
                    Text(if (query.isNotEmpty()) "试试更短的文件名，或清除搜索。" else if (entries.isNotEmpty()) "在“排序与显示”中显示隐藏文件。" else if (location.writable) "从右上角菜单新建文件夹、文本文件或导入项目。" else "此位置暂无内容。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (grid) LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                    gridItems(shown, key = { it.path }) { entry -> row(entry, true) }
                } else LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.path }) { entry -> row(entry, false); HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                }
            }
        }
    }
    preview?.let { current ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(previewEntry?.name.orEmpty(), maxLines = 2) },
            text = { FilePreview(current, Modifier.fillMaxWidth().heightIn(max = 420.dp)) },
            dismissButton = { if (location.writable && current.text != null && previewEntry?.linked == false) TextButton(onClick = {
                begin("编辑文本", listOf(previewEntry!!)); content = current.text; draftOriginal = content; draftRevision = current.revision.orEmpty(); preview = null
            }) { Text("编辑") } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } })
    }
    if (destinationPicker) DirectoryPicker(files, action.orEmpty(), directory, actionEntries.map { it.path }, busy,
        onDismiss = { if (!busy) { destinationPicker = false; action = null } }, onChoose = { target ->
            runTask {
                val failed = mutableListOf<String>(); var completed = 0
                for (entry in actionEntries) {
                    try { if (action == "复制") files.copy(entry.path, target) else files.move(entry.path, target); completed++ }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed += entry.name }
                }
                destinationPicker = false; action = null; selected = emptySet(); entries = files.list(directory)
                if (failed.isEmpty()) feedback = "已完成 $completed 项"
                else error = "已完成 $completed 项；未处理：${failed.take(5).joinToString("、")}。检查是否同名、链接或空间不足；已有文件未覆盖。"
            }
        })
    else action?.let { kind ->
        AlertDialog(onDismissRequest = ::dismissEditor, title = { Text(if (kind == "编辑文本") "编辑 ${actionEntries.single().name}" else kind) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind == "删除") Text("删除 ${actionEntries.size} 项${if (actionEntries.any { it.directory }) "及其中的内容" else ""}？无法撤销；链接只删除条目本身。\n\n${actionEntries.take(6).joinToString("\n") { it.name }}")
                else {
                    if (kind != "编辑文本") OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (kind == "新建文本文件" || kind == "编辑文本") OutlinedTextField(content, { if (it.length <= WorkspaceFileManager.TEXT_LIMIT) content = it },
                        label = { Text("UTF-8 文本 · 最多 256 KiB") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        enabled = !busy, minLines = 5, maxLines = 14, modifier = Modifier.fillMaxWidth())
                    Text(if (kind == "编辑文本") "保存前会检查原文件是否被修改，避免覆盖他人的更改。" else "同名文件不会被覆盖。", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, dismissButton = { TextButton(onClick = ::dismissEditor, enabled = !busy) { Text("取消") } }, confirmButton = {
            TextButton(enabled = !busy && (kind == "删除" || kind == "编辑文本" || name.isNotBlank()), onClick = { runTask {
                when (kind) {
                    "新建文件夹" -> files.createFolder(directory, name)
                    "新建文本文件" -> files.createText(directory, name, content)
                    "编辑文本" -> files.saveText(actionEntries.single().path, content, draftRevision)
                    "重命名" -> files.rename(actionEntries.single().path, name)
                    "删除" -> {
                        var completed = 0
                        try { for (entry in actionEntries) { files.delete(entry.path); completed++ } }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            action = null; entries = files.list(directory); selected = emptySet()
                            throw IllegalStateException("已删除 $completed 项；后续删除未完成，请刷新检查。${e.message.orEmpty()}")
                        }
                    }
                }
                action = null; selected = emptySet(); entries = files.list(directory); feedback = if (kind == "编辑文本") "已保存" else "操作完成"
            } }) { Text(if (busy) "处理中…" else if (kind == "删除") "确认删除" else "保存") }
        })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的内容？") },
        text = { Text("原文件没有改变，当前草稿将丢失。") }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } },
        confirmButton = { TextButton(onClick = { discard = false; action = null }) { Text("放弃草稿") } })
}

@Composable
private fun FileEntryLabel(entry: WorkspaceFileManager.Entry, tile: Boolean) {
    Text(entry.name, maxLines = if (tile) 2 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
    Text((if (entry.directory) "文件夹" else (if (entry.linked) "链接 · " else "") + fileSize(entry.bytes)) +
        if (!tile && entry.modified > 0) " · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.modified)) else "",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DirectoryPicker(files: WorkspaceFileManager, operation: String, initial: String, sources: List<String>, busy: Boolean,
                            onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    var current by remember { mutableStateOf(initial) }
    var folders by remember { mutableStateOf(emptyList<WorkspaceFileManager.Entry>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        loading = true; error = null
        try { folders = files.list(current).filter { it.directory && sources.none { path -> it.path == path || it.path.startsWith("$path/") } } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { folders = emptyList(); error = "无法打开此目录，请返回上一级" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${operation}到文件夹") }, text = {
        Column(Modifier.heightIn(max = 400.dp)) {
            Text(files.guestPath(current), style = MaterialTheme.typography.bodySmall)
            if (current.isNotEmpty()) TextButton(onClick = { current = current.substringBeforeLast('/', "") }, enabled = !loading && !busy) { Text("返回上一级") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(folders, key = { it.path }) { folder -> TextButton(onClick = { current = folder.path }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(12.dp)) {
                    TanukiIcon(TanukiGlyph.Folder); Spacer(Modifier.width(12.dp)); Text(folder.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TanukiIcon(TanukiGlyph.Chevron)
                } }
                if (!loading && folders.isEmpty() && error == null) item { Text("没有可进入的子文件夹", Modifier.padding(vertical = 16.dp)) }
            }
            Text("选择当前位置；同名条目会跳过，不会覆盖。", style = MaterialTheme.typography.bodySmall)
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }, confirmButton = {
        TextButton(onClick = { onChoose(current) }, enabled = !loading && !busy && error == null && sources.none { current == it || current.startsWith("$it/") }) { Text(if (busy) "处理中…" else "${operation}到这里") }
    })
}

@Composable
private fun FilePreview(preview: WorkspaceFileManager.Preview, modifier: Modifier) {
    if (preview.image != null) Image(preview.image.asImageBitmap(), "文件图片预览", modifier, contentScale = ContentScale.Fit)
    else SelectionContainer(modifier.verticalScroll(rememberScrollState())) { Text(preview.text.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
}
private fun fileSize(bytes: Long): String = when {
    bytes < 0 -> "无法读取大小"; bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KiB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
}
