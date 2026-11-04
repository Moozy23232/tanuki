package com.tanuki.ui

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.tanuki.browser.*

private fun browserFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
}

@Composable
fun AgentBrowserPage(controller: AgentBrowserController, modifier: Modifier = Modifier, connectionError: String? = null,
    onShowOverlay: (() -> Unit)? = null, onHideOverlay: () -> Unit = {}, openRequest: Int = 0,
    fullscreen: Boolean = false, onFullscreen: (Boolean) -> Unit = {},
    onSettings: (() -> Unit)? = null, bottomInset: Dp = 0.dp) {
    val state by controller.state.collectAsState()
    val interactionModifier = modifier.pointerInput(controller) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed || it.previousPressed }) controller.noteHumanInteraction()
            }
        }
    }
    val library by controller.library.state.collectAsState()
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.url)) }
    val addressInteractions = remember { MutableInteractionSource() }
    val addressFocused by addressInteractions.collectIsFocusedAsState()
    LaunchedEffect(addressFocused) {
        if (addressFocused) address = address.copy(selection = TextRange(0, address.text.length))
    }
    var menu by remember { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var showFind by rememberSaveable { mutableStateOf(false) }
    var findText by rememberSaveable(state.activeTab) { mutableStateOf("") }
    var libraryQuery by remember { mutableStateOf("") }
    var bookmarkTitle by remember { mutableStateOf("") }
    var bookmarkFolder by remember { mutableStateOf("收藏夹") }
    var selectedMode by remember { mutableStateOf(BrowserPageMode.WINDOWS) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val addressFocus = remember { FocusRequester() }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = exporting; exporting = null
        if (uri != null && id != null) controller.library.export(context.applicationContext, id, uri) { controller.notify(it) }
    }
    fun open() { if (address.text.isNotBlank()) { focus.clearFocus(); keyboard?.hide(); controller.navigateInput(address.text) } }
    fun showPage(name: String) { menu = null; libraryQuery = ""; screen = name }
    fun back() { screen = if (screen in setOf("通用", "权限与后台", "数据管理", "关于浏览器")) "浏览器设置" else null }
    fun openFile(record: BrowserDownload) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.browserfiles", controller.library.file(record.id))
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "打开下载文件"))
        }.onFailure { controller.notify("没有可打开此文件的应用，或文件已移除；可选择导出") }
    }
    LaunchedEffect(state.url, state.activeTab) { address = TextFieldValue(state.url); findText = state.findQuery }
    LaunchedEffect(openRequest) { if (openRequest > 0) { screen = null; menu = null; dialog = null } }
    val owner = remember(context) {
        var current = context
        while (current is android.content.ContextWrapper && current !is LifecycleOwner) current = current.baseContext
        current as? LifecycleOwner
    }
    DisposableEffect(controller, owner) {
        controller.setVisible(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.setVisible(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> controller.setVisible(false)
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); controller.setVisible(false) }
    }
    BackHandler(enabled = fullscreen || screen != null || showFind || (state.canGoBack && !state.closed)) {
        when { fullscreen -> onFullscreen(false); screen != null -> back(); showFind -> { showFind = false; controller.clearFind() }; else -> controller.back() }
    }
    if (screen == null) Surface(interactionModifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (!fullscreen) Row(Modifier.fillMaxWidth().zIndex(1f).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onSettings != null) Text("浏览器", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                // 标签放在外层，避免覆盖编辑节点的文本选择语义。
                Box(Modifier.weight(1f).semantics { contentDescription = "网址输入框" }) {
                    BasicTextField(address, { controller.noteHumanInteraction(); address = it.copy(text = it.text.take(8192)) },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(addressFocus),
                        interactionSource = addressInteractions,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true, enabled = !state.closed && !state.rendererFailed,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { open() }),
                        decorationBox = { field ->
                            Box {
                                // 视觉背板更薄，触摸和大字体高度仍由内容决定。
                                Box(Modifier.matchParentSize().padding(vertical = 4.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraLarge))
                                Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                        if (address.text.isEmpty()) Text("搜索或输入网址", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        field()
                                    }
                                    IconButton(onClick = ::open, enabled = address.text.isNotBlank() && !state.closed && !state.rendererFailed) {
                                        Icon(Icons.Default.ArrowForward, "前往", Modifier.size(20.dp))
                                    }
                                }
                            }
                        })
                }
                if (onSettings != null) IconButton(onClick = onSettings) { TanukiIcon(TanukiGlyph.Settings, "设置") }
            }
            if (!fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = controller::back, enabled = state.canGoBack) { Icon(Icons.Default.ArrowBack, "后退") }
                IconButton(onClick = controller::forward, enabled = state.canGoForward) { Icon(Icons.Default.ArrowForward, "前进") }
                IconButton(onClick = controller::reload, enabled = state.url.isNotEmpty()) { Icon(Icons.Default.Refresh, "刷新网页") }
                Box(Modifier.size(48.dp)) {
                    IconButton(onClick = { showPage("页面总览") }, modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "页面总览"; stateDescription = "${state.tabs.size} 个页面"
                    }) { Icon(Icons.Default.Tab, null) }
                    // 角标放在按钮裁切外，放大字体时仍保留完整数量语义。
                    Badge(Modifier.align(Alignment.TopEnd).clearAndSetSemantics {},
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(if (state.tabs.size > 99) "99+" else state.tabs.size.toString(), maxLines = 1)
                    }
                }
                IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); onFullscreen(true) }) { TanukiIcon(TanukiGlyph.Expand, "浏览器进入全屏") }
                IconButton(onClick = { menu = "main" }) { Icon(Icons.Default.Menu, "浏览器菜单") }
            }
            if (!fullscreen) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.authorizedClient != null) Icons.Default.SmartToy else Icons.Default.Person, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (state.privatePage) "登录授权页 · AI 不可查看或操作" else if (state.takeoverRequested) "AI 可查看，操作已暂停" else if (state.authorizedClient != null) "AI 可操作 · 点网页可临时接管"
                        else state.idleReturnSeconds?.let { "你在操作 · ${it}秒未操作后交回 AI" } ?: "AI 可查看 · 操作权已收回",
                        Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { if (state.authorizedClient != null) controller.takeControl() else controller.grantConnection() }, enabled = !state.closed && !state.rendererFailed && !state.privatePage) {
                        Text(if (state.authorizedClient != null) "撤销 AI 操作权限" else "交回 AI", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (showFind && !fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(findText, { controller.noteHumanInteraction(); findText = it.take(200); controller.find(findText) }, Modifier.weight(1f).semantics { contentDescription = "页内查找输入框" }, singleLine = true,
                    shape = MaterialTheme.shapes.large, label = { Text("页内查找") }, supportingText = { Text("${state.findIndex} / ${state.findCount}") })
                IconButton(onClick = { controller.findNext(false) }, enabled = state.findCount > 0) { Icon(Icons.Default.KeyboardArrowUp, "上一个匹配") }
                IconButton(onClick = { controller.findNext(true) }, enabled = state.findCount > 0) { Icon(Icons.Default.KeyboardArrowDown, "下一个匹配") }
                IconButton(onClick = { showFind = false; controller.clearFind() }) { Icon(Icons.Default.Close, "关闭查找") }
            }
            connectionError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.notice?.let { message -> Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = controller::dismissNotice) { Icon(Icons.Default.Close, "关闭提示") }
            } }
            if (state.loading) LinearProgressIndicator(progress = state.loadProgress / 100f, modifier = Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (state.overlayFrame != null) Column(Modifier.fillMaxSize().padding(bottom = bottomInset).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    TanukiIcon(TanukiGlyph.Browser, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("网页正在悬浮窗中显示", style = MaterialTheme.typography.titleMedium)
                    Text("可显示在其他应用上方，拖动左下角或右下角调整大小。", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = onHideOverlay) { Text("收回到完整浏览器") }
                }
                else if (!state.closed && !state.rendererFailed && state.url.isNotEmpty()) key(state.activeTab) {
                    val view = controller.webView
                    val originalLayer = remember(view) { view.layerType }
                    DisposableEffect(view, fullscreen) {
                        // 部分 WebView/GPU 组合在隐藏系统栏后停止绘制。
                        // 保留当前文档，退出全屏时释放其专用硬件纹理。
                        view.setLayerType(if (fullscreen) android.view.View.LAYER_TYPE_HARDWARE else originalLayer, null)
                        onDispose { view.setLayerType(originalLayer, null) }
                    }
                    // 默认网页透显；需要操作网页固定底栏时，由用户显式开启视口避让。
                    AndroidView(factory = { viewContext ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        FrameLayout(viewContext).apply { clipChildren = true; clipToPadding = true; addView(view, FrameLayout.LayoutParams(-1, -1)) }
                    }, onRelease = { host -> controller.park(view, host) }, modifier = Modifier.fillMaxSize()
                        .padding(bottom = if (library.avoidBottomControls) bottomInset else 0.dp).clipToBounds())
                }
                if (state.overlayFrame == null && state.url.isEmpty() && !state.rendererFailed) Column(Modifier.fillMaxSize().padding(bottom = bottomInset).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Public, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("开始浏览", style = MaterialTheme.typography.headlineSmall)
                    if (fullscreen) FilledTonalButton(onClick = { onFullscreen(false) }) { Text("退出全屏后输入网址") }
                    else FilledTonalButton(onClick = { addressFocus.requestFocus(); keyboard?.show() }) { Text("搜索或输入网址") }
                    Text("${BrowserSearchEngine.from(library.searchEngine).title} · 收藏、历史和下载在顶部菜单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (fullscreen) Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(25.dp),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopEnd).zIndex(1f).displayCutoutPadding().statusBarsPadding().padding(end = 12.dp, top = 8.dp)) {
            IconButton(onClick = { onFullscreen(false) }, modifier = Modifier.size(48.dp)) {
                TanukiIcon(TanukiGlyph.Contract, "浏览器退出全屏")
            }
        }
        }
    } else BrowserNativePage(screen!!, ::back, interactionModifier, actions = {
        if (onSettings != null) IconButton(onClick = onSettings) { TanukiIcon(TanukiGlyph.Settings, "设置") }
        if (fullscreen) IconButton(onClick = { onFullscreen(false) }) { TanukiIcon(TanukiGlyph.Contract, "浏览器退出全屏") }
        if (screen == "页面总览") IconButton(onClick = { controller.newTab(); screen = null }) { Icon(Icons.Default.Add, "新建页面") }
        if (screen == "历史记录" && library.history.isNotEmpty()) IconButton(onClick = { dialog = "清空历史记录" }) { Icon(Icons.Default.DeleteOutline, "清空记录") }
    }) {
        when (screen) {
            "页面总览" -> {
                Text("${state.tabs.size} 个页面", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                LazyVerticalGrid(columns = GridCells.Adaptive(144.dp), modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = bottomInset + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.tabs, key = { it.id }) { tab -> Surface(shape = MaterialTheme.shapes.large,
                        color = if (tab.id == state.activeTab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column {
                            Column(Modifier.fillMaxWidth().clickable { controller.selectTab(tab.id); screen = null }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val preview = remember(tab.id, tab.url) { controller.thumbnail(tab.id) }
                                if (preview != null) Image(preview.asImageBitmap(), "页面预览", Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Crop)
                                Text(tab.title.ifBlank { "新页面" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(tab.url.ifBlank { "尚未打开网址" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { controller.closeTab(tab.id) }, modifier = Modifier.align(Alignment.End)) { Text("关闭此页") }
                        }
                    } }
                }
            }
            "浏览器设置" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomInset)) {
                BrowserPreference("通用", "搜索引擎与页面显示", Icons.Default.Tune) { screen = "通用" }
                BrowserPreference("权限与后台", "AI 接管、收回权限和后台运行", Icons.Default.Security) { screen = "权限与后台" }
                BrowserPreference("数据管理", "历史、收藏与下载文件", Icons.Default.FolderOpen) { screen = "数据管理" }
                BrowserPreference("关于浏览器", "内核与数据隔离说明", Icons.Default.Info) { screen = "关于浏览器" }
            }
            "通用" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomInset)) {
                BrowserPreference("搜索引擎", BrowserSearchEngine.from(library.searchEngine).title, Icons.Default.Search) { dialog = "搜索引擎" }
                BrowserPreference("当前页面显示模式", BrowserPageMode.from(state.pageMode).title, Icons.Default.Devices) { selectedMode = BrowserPageMode.from(state.pageMode); dialog = "页面显示模式" }
                ListItem(modifier = Modifier.padding(horizontal = 4.dp).toggleable(library.avoidBottomControls,
                    role = Role.Switch, onValueChange = controller.library::setAvoidBottomControls),
                    headlineContent = { Text("避让网页底部控件") },
                    supportingContent = { Text("开启后预留底部空间，避免网页自己的固定输入框或按钮被导航遮挡") },
                    leadingContent = { Icon(Icons.Default.KeyboardArrowUp, null) },
                    trailingContent = { Switch(library.avoidBottomControls, onCheckedChange = null) })
            }
            "数据管理" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomInset)) {
                BrowserPreference("历史记录", "${library.history.size} 条记录", Icons.Default.History) { showPage("历史记录") }
                BrowserPreference("收藏夹", "${library.bookmarks.size} 个收藏 · 按文件夹分类", Icons.Default.Bookmarks) { showPage("收藏夹") }
                BrowserPreference("下载与截图", "${library.downloads.size} 个文件 · 查看、导出或删除", Icons.Default.Download) { showPage("下载与截图") }
                BrowserPreference("清空历史记录", "不会删除收藏或下载", Icons.Default.DeleteOutline) { dialog = "清空历史记录" }
            }
            "权限与后台" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomInset).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (state.authorizedClient != null) "AI 可以操作浏览器" else "浏览器由你控制", style = MaterialTheme.typography.titleMedium)
                Text("当前工具连接可查看普通网页，默认允许操作；登录授权临时页始终不向 AI 开放。触碰网页先暂停 AI；临时接管后，30 秒没有点击、滑动或输入会交回 AI。切页与后台不会另外撤销权限。")
                FilledTonalButton(onClick = { if (state.authorizedClient != null) controller.takeControl() else controller.grantConnection() }) { Text(if (state.authorizedClient != null) "撤销 AI 操作权限" else "交回 AI") }
                Text("显式撤销会记住你的选择，不因空闲、切页或重启自动交回。提交、付款、发布等业务操作仍需另外确认。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(if (state.backgroundEnabled) "后台运行已开启" else "后台运行已停止", style = MaterialTheme.typography.titleSmall)
                Text("后台通知可收回 AI 操作权或停止浏览器及下载，不会停止 DSH。Android 仍可能限制后台网络或回收进程。", style = MaterialTheme.typography.bodySmall)
                if (onShowOverlay != null) {
                    HorizontalDivider()
                    Text("系统悬浮窗", style = MaterialTheme.typography.titleSmall)
                    Text("显示在其他应用上方；拖动顶部移动，拖动左下或右下角改变大小。首次使用需要系统悬浮窗授权。AI 始终读取完整文档，不受小窗裁切；敏感系统页面可能隐藏悬浮窗。", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = if (state.overlayFrame == null) onShowOverlay else onHideOverlay) { Text(if (state.overlayFrame == null) "打开系统悬浮窗" else "关闭系统悬浮窗") }
                }
                Text(state.operations.joinToString("\n").ifBlank { "暂无操作记录" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "关于浏览器" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = bottomInset).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("独立 Android WebView", style = MaterialTheme.typography.titleMedium)
                Text("浏览器与 DSH 管理页隔离 Cookie 和网站存储。参考 Via 的交互组织，不是 Via 内核，也不会读取 Via 的数据。")
                Text("页面显示模式修改 User-Agent；切换为 PC 或 Mac 不会更换浏览器内核。文件上传、相机、麦克风和定位默认不开放。")
                Text("连接权限不等于经过认证的单个模型或会话身份。只有持有本机连接能力的工具可以请求操作。", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                OutlinedTextField(libraryQuery, { controller.noteHumanInteraction(); libraryQuery = it.take(200) }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("筛选$screen") }, singleLine = true)
                LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = bottomInset + 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (screen) {
                        "历史记录" -> {
                            val entries = library.history.filter { (it.title + it.url).contains(libraryQuery, true) }
                            if (entries.isEmpty()) item { Text("还没有匹配的历史记录。") }
                            items(entries, key = { it.url }) { entry -> TextButton(onClick = { controller.navigate(entry.url); screen = null }) {
                                Column(Modifier.fillMaxWidth()) { Text(entry.title.ifBlank { entry.url }, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(entry.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                            } }
                        }
                        "收藏夹" -> {
                            val groups = library.bookmarks.filter { (it.title + it.url + it.folder).contains(libraryQuery, true) }.groupBy { it.folder }
                            if (groups.isEmpty()) item { Text("还没有匹配的收藏。打开网页后，选择菜单里的「收藏当前页」。") }
                            groups.forEach { (folder, bookmarks) ->
                                item(key = "folder:$folder") { Text(folder, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                                items(bookmarks, key = { it.id }) { entry -> Column {
                                    TextButton(onClick = { controller.navigate(entry.url); screen = null }) { Text(entry.title, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    Row { TextButton(onClick = { controller.newTab(entry.url); screen = null }) { Text("新页面打开") }; TextButton(onClick = { controller.library.removeBookmark(entry.id) }) { Text("移除收藏") } }
                                } }
                            }
                        }
                        else -> {
                            val entries = library.downloads.filter { (it.name + it.status).contains(libraryQuery, true) }
                            if (entries.isEmpty()) item { Text("下载和网页截图保存在这里。卸载应用前请导出需要保留的内容。") }
                            items(entries, key = { it.id }) { record -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(record.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text("${record.status} · ${browserFileSize(record.received)}" + if (record.total > 0) " / ${browserFileSize(record.total)}" else "", style = MaterialTheme.typography.bodySmall)
                                if (record.status in setOf("等待中", "下载中")) {
                                    if (record.total > 0) LinearProgressIndicator(progress = (record.received.toDouble() / record.total).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                record.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    if (record.status == "已完成") { TextButton(onClick = { openFile(record) }) { Text("打开") }; TextButton(onClick = { exporting = record.id; saveFile.launch(record.name) }) { Text("导出") } }
                                    if (record.status in setOf("等待中", "下载中")) TextButton(onClick = { controller.library.cancel(record.id) }) { Text("取消下载") }
                                    else {
                                        if (!record.image && record.status != "已完成") TextButton(onClick = { controller.acceptDownload(BrowserDownloadRequest(record.url, record.name, record.mime, -1)) }) { Text("重新下载") }
                                        TextButton(onClick = { dialog = "删除下载:${record.id}" }) { Text("删除") }
                                    }
                                }
                                HorizontalDivider()
                            } }
                        }
                    }
                }
            }
        }
    }
    menu?.let { page -> BrowserMenuSheet(page == "tools", state.url.isNotEmpty() && !state.loading, state.authorizedClient != null,
        onBack = { menu = "main" }, onDismiss = { menu = null }, onChoose = { name ->
            when (name) {
                "工具箱" -> menu = "tools"
                "页内查找" -> { menu = null; showFind = true }
                "网页缩放" -> { menu = null; dialog = name }
                "页面显示模式" -> { menu = null; selectedMode = BrowserPageMode.from(state.pageMode); dialog = name }
                "截取网页画面" -> { controller.capturePage(); showPage("下载与截图") }
                "收藏当前页" -> { menu = null; bookmarkTitle = state.title; bookmarkFolder = library.bookmarks.firstOrNull { it.url == state.url }?.folder ?: "收藏夹"; dialog = name }
                else -> showPage(name)
            }
        }) }
    if (state.takeoverRequested && state.overlayFrame == null) AlertDialog(onDismissRequest = { controller.resolveTakeover(false) }, title = { Text("收回 AI 浏览器操作权限？") },
        text = { Column {
            Text("AI 操作已暂停，这次点击尚未执行。临时接管期间 AI 可查看网页，不能点击、输入或导航；30 秒未操作会自动交回操作权。")
            TextButton(onClick = controller::takeControl) { Text("撤销 AI 操作权限") }
        } },
        confirmButton = { TextButton(onClick = { controller.resolveTakeover(true) }) { Text("临时接管") } },
        dismissButton = { TextButton(onClick = { controller.resolveTakeover(false) }) { Text("继续交给 AI") } })
    state.downloadRequest?.takeIf { state.overlayFrame == null }?.let { request -> AlertDialog(onDismissRequest = controller::dismissDownload,
        title = { Text("下载此文件？") }, text = { Text("${request.name}\n${request.url.take(160)}\n\n保存到应用私有下载目录，可查看、导出或删除。单文件最多 256 MiB。") },
        confirmButton = { TextButton(onClick = { controller.acceptDownload(request); showPage("下载与截图") }) { Text("确认下载") } },
        dismissButton = { TextButton(onClick = controller::dismissDownload) { Text("取消") } }) }
    dialog?.let { title -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (title.startsWith("删除下载:")) "删除下载文件？" else title) }, text = {
        when (title) {
            "收藏当前页" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(bookmarkTitle, { controller.noteHumanInteraction(); bookmarkTitle = it.take(200) }, label = { Text("收藏名称") })
                OutlinedTextField(bookmarkFolder, { controller.noteHumanInteraction(); bookmarkFolder = it.take(80) }, label = { Text("收藏文件夹") }, supportingText = { Text("输入新名称即可建立收藏分类") })
            }
            "搜索引擎" -> Column { BrowserSearchEngine.entries.forEach { engine -> Row(Modifier.fillMaxWidth().clickable { controller.library.setSearchEngine(engine) }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(library.searchEngine == engine.name, onClick = { controller.library.setSearchEngine(engine) }); Text(engine.title)
            } }; Text("搜索词只在你点「前往」后发送给所选引擎。", style = MaterialTheme.typography.bodySmall) }
            "页面显示模式" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                BrowserPageMode.entries.forEach { mode -> Row(Modifier.fillMaxWidth().clickable { selectedMode = mode }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedMode == mode, onClick = { selectedMode = mode }); Text(mode.title)
                } }
                Text("修改当前页面的 User-Agent 并重新加载。未提交的输入可能丢失；网站也可能继续显示响应式布局。", style = MaterialTheme.typography.bodySmall)
            }
            "网页缩放" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.zoomPercent}% · 也可双指缩放网页")
                Row { IconButton(onClick = { controller.zoom(state.zoomPercent - 25) }) { Icon(Icons.Default.Remove, "缩小网页") }; TextButton(onClick = { controller.zoom(100) }) { Text("恢复 100%") }; IconButton(onClick = { controller.zoom(state.zoomPercent + 25) }) { Icon(Icons.Default.Add, "放大网页") } }
                TextButton(onClick = controller::fitWidth) { Text("适合宽度") }
                Text("缩放范围可能受网站限制，以网页实际显示为准。", style = MaterialTheme.typography.bodySmall)
            }
            "清空历史记录" -> Text("删除历史记录与页面前进/后退历史，不删除收藏、下载或网站登录状态。")
            else -> Text("同时删除应用内文件与下载记录。已导出到其他位置的副本不受影响。")
        }
    }, confirmButton = { TextButton(onClick = {
        when {
            title == "收藏当前页" -> controller.library.bookmark(state.url, bookmarkTitle, bookmarkFolder)
            title == "页面显示模式" -> controller.pageMode(selectedMode)
            title == "清空历史记录" -> controller.clearHistory()
            title.startsWith("删除下载:") -> runCatching { controller.library.removeDownload(title.substringAfter(':')) }.onFailure { controller.notify("请先取消下载，再删除文件") }
        }
        dialog = null
    }) { Text(when (title) { "收藏当前页" -> "保存收藏"; "页面显示模式" -> "应用并重新加载"; "清空历史记录" -> "确认清空"; else -> if (title.startsWith("删除下载:")) "确认删除" else "完成" }) } },
        dismissButton = { if (title !in setOf("搜索引擎", "网页缩放")) TextButton(onClick = { dialog = null }) { Text("取消") } }) }
}
