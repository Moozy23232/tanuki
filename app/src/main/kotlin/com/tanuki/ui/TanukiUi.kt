package com.tanuki.ui

import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tanuki.R
import com.tanuki.runtime.*
import com.tanuki.storage.WorkspaceStorageState
import com.tanuki.update.Release
import com.tanuki.web.WebViewSupport
import com.tanuki.web.WebViewSupportState
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import java.util.Locale

/** Presentation only. The host owns permissions, confirmations, jobs and WebView lifetime. */
data class TanukiUiModel(
    val snapshot: Snapshot,
    val support: WebViewSupport,
    val versions: List<Release>,
    val recentLogs: List<String>,
    val storageUsage: String,
    val deviceInfo: String,
    val backgroundResult: String?,
    val legacyShim: Boolean,
    val blobExport: Boolean,
    val versionName: String,
    val freeBytes: Long,
    val components: ComponentInventory = ComponentInventory(),
    val ubuntuSources: List<RuntimeSource> = emptyList(),
    val nodeSources: List<RuntimeSource> = emptyList(),
    val ubuntuSpeed:SourceSpeedSnapshot=SourceSpeedSnapshot(),
    val nodeSpeed:SourceSpeedSnapshot=SourceSpeedSnapshot(),
    val nodeRequirement:String="",
    val workspaceStorage: WorkspaceStorageState = WorkspaceStorageState(),
    val tools: RequiredToolsStatus = RequiredToolsStatus(),
    val npmSelection: NpmSourceSelection = NpmSourceSelection(),
    val npmSources: List<RuntimeSource> = emptyList(),
    val npmSpeed: SourceSpeedSnapshot = SourceSpeedSnapshot(),
    val transfers: List<FileTransferProgress> = emptyList(),
    val liveLogs: kotlinx.coroutines.flow.StateFlow<List<String>>? = null,
    val appUpdate: com.tanuki.update.AppUpdateState = com.tanuki.update.AppUpdateState(),
    val appInstallAllowed: Boolean = false
)

data class TanukiUiCallbacks(
    val command: (String) -> Unit,
    val install: (Release) -> Unit,
    val checkUpdates: () -> Unit,
    val importFiles: () -> Unit,
    val measureStorage: () -> Unit,
    val toggleLegacy: (Boolean) -> Unit,
    val toggleBlob: (Boolean) -> Unit,
    val showNotices: () -> Unit,
    val updateWebView: () -> Unit,
    val webViewSettings: () -> Unit,
    val appSettings: () -> Unit,
    val notifications: () -> Unit,
    val connectDsh: () -> Unit,
    val selectSource: (RuntimeComponent, String) -> Unit = { _, _ -> },
    val reinstallDsh: (Release) -> Unit = { },
    val uninstallDsh: (Release?) -> Unit = { },
    val cacheDsh: (Release) -> Unit = { },
    val setFullscreen: (Boolean) -> Unit = { },
    val selectVersion:(RuntimeComponent,String)->Unit={_,_->},
    val testSources:(RuntimeComponent)->Unit={},
    val prepareSources:(RuntimeComponent)->Unit={},
    val cancelSourceTests:(RuntimeComponent)->Unit={},
    val refreshWorkspaceStorage: () -> Unit = {},
    val saveWorkspaceCapacity: suspend (Long?) -> Unit = {},
    val selectNpmSource: (String) -> Unit = {},
    val prepareNpmSources: (String) -> Unit = {},
    val testNpmSources: (String) -> Unit = {},
    val cancelNpmSourceTests: () -> Unit = {},
    val openBrowser: () -> Unit = {},
    val revokeBrowser: () -> Unit = {},
    val openBrowserOverlay: (() -> Unit)? = null,
    val checkAppUpdates: () -> Unit = {},
    val downloadAppUpdate: () -> Unit = {},
    val installAppUpdate: () -> Unit = {},
    val cancelAppUpdate: () -> Unit = {}
)

internal val Snapshot.tanukiBusy: Boolean get() = operationActive
internal val Snapshot.tanukiIdle: Boolean get() = !tanukiBusy &&
    service in setOf(ServiceState.STOPPED, ServiceState.FAILED, ServiceState.INTERRUPTED)
private enum class TanukiDestination(val title: String, val glyph: TanukiGlyph) {
    WORKSPACE("工作台", TanukiGlyph.Home), DSH("DSH", TanukiGlyph.Terminal),
    FILES("文件", TanukiGlyph.Folder), BROWSER("浏览器", TanukiGlyph.Browser), SETTINGS("设置", TanukiGlyph.Settings)
}
internal enum class TanukiGlyph { Home, Terminal, Layers, Settings, Folder, File, Browser, Back, Refresh, Stop, Chevron, Download, Expand, Contract }

@Composable
fun TanukiUi(model: TanukiUiModel, callbacks: TanukiUiCallbacks, browserView: WebView,
             filesPage: @Composable (Modifier) -> Unit = {},
             agentBrowserPage: @Composable (Modifier, Boolean, (Boolean) -> Unit) -> Unit = { _, _, _ -> }, browserRequest: Boolean = false,
             browserAuthorized: Boolean = false, browserOpenRequest: Int = 0) {
    var tab by rememberSaveable(key = "tanuki-navigation-5") { mutableIntStateOf(0) }
    val pageStates = rememberSaveableStateHolder()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var browserFullscreen by rememberSaveable { mutableStateOf(false) }
    val activeFullscreen = (fullscreen && tab == 1) || (browserFullscreen && tab == 3)
    var floatingBrowser by rememberSaveable { mutableStateOf(false) }
    fun navigate(index: Int) {
        if (index == 3) { callbacks.openBrowser(); floatingBrowser = false }
        tab = index
    }
    LaunchedEffect(browserOpenRequest) { if (browserOpenRequest > 0) navigate(3) }
    val s = model.snapshot
    LaunchedEffect(tab, s.service) {
        if (tab == 1 && s.service == ServiceState.RUNNING) callbacks.connectDsh()
    }
    LaunchedEffect(activeFullscreen) { callbacks.setFullscreen(activeFullscreen) }
    DisposableEffect(Unit) { onDispose { callbacks.setFullscreen(false) } }
    BackHandler(tab == 1) {
        if (activeFullscreen) {
            fullscreen = false
        } else if (s.service == ServiceState.RUNNING && model.support.state == WebViewSupportState.SUPPORTED &&
            s.web != WebState.RENDER_ERROR && browserView.canGoBack()) browserView.goBack() else tab = 0
    }
    var settingsReturn by rememberSaveable { mutableIntStateOf(0) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    fun openSettings() { settingsReturn = tab.takeUnless { it == 4 } ?: 0; settingsSection = null; tab = 4 }
    fun settingsBack() { if (settingsSection != null) settingsSection = null else tab = settingsReturn }
    BackHandler(tab != 0 && tab != 1 && tab != 4) {
        if (tab == 3 && activeFullscreen) browserFullscreen = false else tab = 0
    }
    BackHandler(tab == 4) { settingsBack() }
    val destinations = TanukiDestination.entries.take(4)
    val hazeState = remember { HazeState() }
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        // 只捕获原生滚动内容；网页保留原渲染链，旧系统使用高可读性的材质降级。
        val nativeGlass = !wide && (tab == 0 || tab == 4) && Build.VERSION.SDK_INT >= 32
        Row(Modifier.fillMaxSize()) {
            if (wide && !activeFullscreen) NavigationRail(containerColor = Color.Transparent,
                modifier = Modifier.fillMaxHeight().width(96.dp)) {
                Spacer(Modifier.height(20.dp))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        destinations.forEachIndexed { index, destination ->
                            NavigationRailItem(selected = tab == index, onClick = { navigate(index) },
                                icon = { TanukiIcon(destination.glyph, destination.title) }, label = { Text(destination.title) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ))
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            Scaffold(modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = if (activeFullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    Column {
                        if (tab != 1 && !activeFullscreen) TanukiHeader(tab, if (tab == 4) settingsSection ?: "设置" else TanukiDestination.entries[tab].title, callbacks,
                            s.service in setOf(ServiceState.STARTING, ServiceState.RUNNING),
                            settings = ::openSettings, back = ::settingsBack)
                        if (browserRequest && tab != 3) Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Row(Modifier.fillMaxWidth().then(if (tab == 1) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("网页操作待授权", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { navigate(3) }) { Text("查看请求") }
                            }
                        }
                        if (browserAuthorized && tab != 3) Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Row(Modifier.fillMaxWidth().then(if (tab == 1 && !browserRequest) Modifier.statusBarsPadding() else Modifier).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("AI 已获浏览器权限 · 后台有效", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = callbacks.revokeBrowser) { Text("撤销 AI") }
                            }
                        }
                        OperationBanner(s, model.transfers, onCancel = { callbacks.command("CANCEL") },
                            modifier = if (tab == 3 && activeFullscreen) Modifier.displayCutoutPadding().statusBarsPadding()
                                else if (tab == 1 && !browserRequest && !browserAuthorized) Modifier.statusBarsPadding() else Modifier)
                    }
                }, bottomBar = {
                    if (!wide && !activeFullscreen) Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        GlassNavigationBar(
                            selectedIndex = tab,
                            labels = destinations.map { it.title },
                            onSelect = ::navigate,
                            hazeState = if (nativeGlass) hazeState else null,
                        ) { index, tint ->
                            TanukiIcon(destinations[index].glyph, destinations[index].title, tint = tint)
                        }
                    }
                }) { padding ->
                val pageModifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                when (tab) {
                    1 -> DshPage(model, callbacks, browserView, pageModifier, home = { tab = 0 },
                        fullscreen = activeFullscreen, onFullscreen = { fullscreen = it }, settings = ::openSettings,
                        openBrowserWindow = {
                            if (callbacks.openBrowserOverlay != null) callbacks.openBrowserOverlay.invoke()
                            else { if (!floatingBrowser) callbacks.openBrowser(); floatingBrowser = !floatingBrowser }
                        })
                    2 -> pageStates.SaveableStateProvider("files") { filesPage(pageModifier) }
                    3 -> pageStates.SaveableStateProvider("browser") { agentBrowserPage(pageModifier, browserFullscreen, { browserFullscreen = it }) }
                    else -> key(tab, settingsSection.takeIf { tab == 4 }) {
                        // 原生内容可滚到玻璃后方，尾部仍预留导航高度，避免最后一项被遮挡。
                        val nativeModifier = if (nativeGlass) Modifier.fillMaxSize().padding(
                            start = padding.calculateStartPadding(layoutDirection),
                            top = padding.calculateTopPadding(),
                            end = padding.calculateEndPadding(layoutDirection),
                        ).consumeWindowInsets(padding).haze(
                            hazeState,
                            HazeDefaults.style(
                                tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.22f),
                                blurRadius = 18.dp,
                                noiseFactor = 0.035f,
                            ),
                        ) else pageModifier
                        val bottomSpace = 32.dp + if (nativeGlass) padding.calculateBottomPadding() else 0.dp
                        Box(nativeModifier.background(tanukiPageBrush())) {
                            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 760.dp).fillMaxSize().tanukiEnter().imePadding()
                                .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomSpace),
                                verticalArrangement = Arrangement.spacedBy(28.dp)) {
                                if (tab == 0) Overview(model, callbacks, openDsh = { tab = 1 })
                                else SettingsPage(model, callbacks, settingsSection) { settingsSection = it }
                            }
                        }
                    }
                }
            }
        }
        if (floatingBrowser && tab != 3) BrowserFloatingWindow(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            onClose = { floatingBrowser = false },
            onExpand = { floatingBrowser = false; tab = 3 },
            content = { floatingModifier -> agentBrowserPage(floatingModifier, false, { enabled ->
                if (enabled) { floatingBrowser = false; browserFullscreen = true; tab = 3 }
            }) })
    }
}

@Composable
private fun TanukiHeader(tab: Int, title: String, c: TanukiUiCallbacks, stoppable: Boolean,
                         settings: () -> Unit, back: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (tab == 4) IconButton(onClick = back) { TanukiIcon(TanukiGlyph.Back, "返回上一页") }
            if (tab == 0) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        TanukiIcon(TanukiGlyph.Terminal, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(if (tab == 0) stringResource(R.string.app_name) else title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (stoppable && tab == 0) StopButton(c, true)
            if (tab != 4) IconButton(onClick = settings) { TanukiIcon(TanukiGlyph.Settings, "设置") }
        }
    }
}

@Composable
private fun Overview(model: TanukiUiModel, c: TanukiUiCallbacks, openDsh: () -> Unit) = SetupOverview(model,c,openDsh)

@Composable
internal fun OperationCard(s: Snapshot, c: TanukiUiCallbacks) {
    // Active work is always visible in the fixed banner, not hidden below a long setup page.
    if (s.error.isNotBlank()) TanukiCard {
        Text("需要留意", style = MaterialTheme.typography.titleMedium)
        if (s.stage.isNotBlank()) Text(s.stage, style = MaterialTheme.typography.bodyMedium)
        TanukiDetails("查看错误信息") { SelectableCopy(s.error, error = true) }
    }
}

@Composable
internal fun WebViewCard(support: WebViewSupport, idle: Boolean, c: TanukiUiCallbacks) {
    val needsUpdate = support.state == WebViewSupportState.UPDATE_REQUIRED
    TanukiCard {
        Text(when (support.state) {
            WebViewSupportState.UPDATE_REQUIRED -> "升级 WebView，打开 DSH"
            WebViewSupportState.FAILED -> "WebView 检测未完成"
            WebViewSupportState.CHECKING -> "正在检查网页支持"
            else -> "先检测 WebView 能力"
        }, style = MaterialTheme.typography.titleMedium)
        if (needsUpdate) {
            Text("Chromium ${support.chromium ?: support.version} → 123+", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Caption(if (support.missing.isNotEmpty()) "实测缺失：${support.missing.joinToString("、")}" else "${cssResult("subgrid", support.subgrid)} · ${cssResult("light-dark()", support.lightDark)}")
            Caption("先保存任务并停止 DSH；更新内核可能关闭其他使用它的应用。")
            Button(onClick = c.updateWebView, enabled = idle, shape = MaterialTheme.shapes.medium) { Text("官方更新入口") }
            if (!idle) Caption("停止服务并等待当前操作完成后可更新。")
        } else {
            Caption(if (support.state == WebViewSupportState.FAILED) support.error.ifBlank { "请重新检测渲染能力。" } else "需要 Chromium 123+，并实测支持 subgrid 与 light-dark()。")
            TextButton(onClick = { c.command("WEBVIEW_CHECK") }, enabled = support.state != WebViewSupportState.CHECKING) { Text("检测 WebView") }
        }
    }
}

private val DSH_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+.*")

@Composable
internal fun EnvironmentSection(model: TanukiUiModel, c: TanukiUiCallbacks, guideStep:Int?=null) {
    val s = model.snapshot
    val comps = model.components
    val guided=guideStep!=null
    val automatic=ComponentSourceOption("auto","自动择优","先测速再使用最快可用源，下载失败后自动换源")
    var showVersions by rememberSaveable { mutableStateOf(false) }
    var selectedVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = model.versions.firstOrNull { it.version == selectedVersion }
        ?: model.versions.firstOrNull { it.version == s.dsh }
        ?: model.versions.firstOrNull { it.version == RuntimeManager.DSH }
        ?: model.versions.firstOrNull { it.version == s.candidate } ?: model.versions.firstOrNull()
    val actionsEnabled = s.tanukiIdle && s.update != UpdateState.RECOVERY_REQUIRED
    val download = s.download
    val ubuntuProgress = download?.takeIf { it.component.equals("ubuntu", ignoreCase = true) }
        ?.let { ComponentProgressModel(s.stage, it.source, it.received, it.total, it.bytesPerSecond,it.reused) }
    val nodeProgress = download?.takeIf { it.component.equals("node", ignoreCase = true) }
        ?.let { ComponentProgressModel(s.stage, it.source, it.received, it.total, it.bytesPerSecond,it.reused) }

    val currentVersion = s.dsh.takeIf { it.matches(DSH_VERSION) }
    val currentRelease = model.versions.firstOrNull { it.version == currentVersion }
    val dshSwitching = s.update in setOf(UpdateState.STAGING, UpdateState.TESTING, UpdateState.BACKUP, UpdateState.SWITCHING)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
    if(!guided)Text("运行组件",Modifier.padding(start=16.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(
        shape=MaterialTheme.shapes.extraLarge,
        color=if(guided)Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
    Column {
    if(guideStep==null || guideStep==0)RuntimeComponentCard(
        model = RuntimeComponentCardModel(
            id = "ubuntu", title = "Ubuntu Base", version = if(comps.ubuntuInstalled)comps.ubuntuVersion else "未安装",
            installed = comps.ubuntuInstalled, sources = listOf(automatic)+model.ubuntuSources.map { ComponentSourceOption(it.id, it.title, it.detail) },
            selectedSource = if(comps.ubuntuAutomatic)"auto" else comps.ubuntuSource, actionsEnabled = actionsEnabled, canInstall = true,
            dependencyHint = "官方 ARM64 精简基础镜像，不含桌面；Node、DSH、项目和配置独立保留",
            progress = ubuntuProgress,
            versions=RuntimeCatalog.ubuntu.map { ComponentVersionOption(it.version,"Ubuntu Base ${it.version} LTS","ARM64 · ${it.size/(1024*1024)} MB · 精简基础镜像") },
            selectedVersion=comps.selectedUbuntuVersion,guided=guided),
        onSource = { id -> c.selectSource(RuntimeComponent.UBUNTU, id) },
        onInstall = { c.command("UBUNTU_INSTALL") },
        onReinstall = { c.command("UBUNTU_REINSTALL") },
        onUninstall = { c.command("UBUNTU_UNINSTALL") },
        onCancel = { c.command("CANCEL") },
        onVersion={ c.selectVersion(RuntimeComponent.UBUNTU,it) },
        sourceControls={ SourceSpeedControls("Ubuntu",model.ubuntuSources.map { ComponentSourceOption(it.id,it.title,it.detail) },comps.ubuntuSource,
            model.ubuntuSpeed,actionsEnabled,!s.tanukiBusy && !model.nodeSpeed.running,
            {c.testSources(RuntimeComponent.UBUNTU)},{c.cancelSourceTests(RuntimeComponent.UBUNTU)},{c.selectSource(RuntimeComponent.UBUNTU,it)},automatic=comps.ubuntuAutomatic,
            probeKey=comps.selectedUbuntuVersion,onPrepare={c.prepareSources(RuntimeComponent.UBUNTU)}) })
    if(!guided)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant)
    if(guideStep==null || guideStep==1)RuntimeComponentCard(
        model = RuntimeComponentCardModel(
            id = "node", title = "Node.js", version = if(comps.nodeInstalled)comps.nodeVersion else "未安装",
            installed = comps.nodeInstalled, sources = listOf(automatic)+model.nodeSources.map { ComponentSourceOption(it.id, it.title, it.detail) },
            selectedSource = if(comps.nodeAutomatic)"auto" else comps.nodeSource, actionsEnabled = actionsEnabled, canInstall = comps.ubuntuInstalled,
            dependencyHint = if (!comps.ubuntuInstalled) "请先安装 Ubuntu 才能安装 Node；已安装 Node 可独立卸载" else "",
            progress = nodeProgress,
            versions=RuntimeCatalog.node.map { ComponentVersionOption(it.version,"v${it.version}${if(it.lts) " · LTS" else " · 非 LTS"}","ARM64 · ${it.size/(1024*1024)} MB") },
            selectedVersion=comps.selectedNodeVersion,guided=guided,
            selectionNotice=if(model.nodeRequirement.isBlank())"安装前验证 Node.js、pnpm 和已安装 DSH 的兼容性。" else "当前 DSH 要求 Node.js ${model.nodeRequirement}；不兼容的版本将保留当前安装。"),
        onSource = { id -> c.selectSource(RuntimeComponent.NODE, id) },
        onInstall = { c.command("NODE_INSTALL") },
        onReinstall = { c.command("NODE_REINSTALL") },
        onUninstall = { c.command("NODE_UNINSTALL") },
        onCancel = { c.command("CANCEL") },
        onVersion={ c.selectVersion(RuntimeComponent.NODE,it) },
        sourceControls={ SourceSpeedControls("Node.js",model.nodeSources.map { ComponentSourceOption(it.id,it.title,it.detail) },comps.nodeSource,
            model.nodeSpeed,actionsEnabled,!s.tanukiBusy && !model.ubuntuSpeed.running,
            {c.testSources(RuntimeComponent.NODE)},{c.cancelSourceTests(RuntimeComponent.NODE)},{c.selectSource(RuntimeComponent.NODE,it)},automatic=comps.nodeAutomatic,
            probeKey=comps.selectedNodeVersion,onPrepare={c.prepareSources(RuntimeComponent.NODE)}) })

    if(!guided)HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant)
    if(guideStep==null || guideStep==3)ComponentDisclosure("DSH",if(comps.dshInstalled)s.dsh else "官方开发引擎",comps.dshInstalled,dshSwitching,guided=guided) {
        val dshProgress=download?.takeIf { it.component.equals("dsh",ignoreCase=true) }
        if(dshProgress!=null) {
            ComponentDownloadProgress(ComponentProgressModel(s.stage,dshProgress.source,dshProgress.received,dshProgress.total,dshProgress.bytesPerSecond,dshProgress.reused))
        } else if (dshSwitching) {
            Text(s.stage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Caption("解析、校验与安装无法准确估算总量；上方显示耗时和当前进展。")
        }
        SourceSpeedControls("DSH 依赖",model.npmSources.map { ComponentSourceOption(it.id,it.title,it.detail) },model.npmSelection.id,
            model.npmSpeed,actionsEnabled,!s.tanukiBusy && !model.ubuntuSpeed.running && !model.nodeSpeed.running,
            {c.testNpmSources(selected?.version ?: RuntimeManager.DSH)},c.cancelNpmSourceTests,c.selectNpmSource,
            automatic=model.npmSelection.automatic,probeKey=selected?.version ?: RuntimeManager.DSH,
            onPrepare={c.prepareNpmSources(selected?.version ?: RuntimeManager.DSH)})
        Caption("依赖归档自动选源，失败时换源；版本与校验信息仍来自 npm 官方。")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick={if(model.versions.isEmpty())c.checkUpdates() else showVersions=true},enabled=!s.tanukiBusy,
                modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=MaterialTheme.shapes.medium) {
                Text(if(model.versions.isEmpty())"获取官方版本" else "选择版本安装")
            }
            TextButton(onClick=c.checkUpdates,enabled=!s.tanukiBusy,modifier=Modifier.heightIn(min=48.dp)) { Text("刷新") }
        }
        if(comps.dshInstalled && !guided) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton(onClick={currentRelease?.let(c.reinstallDsh)},
                    enabled=currentRelease!=null && s.tanukiIdle && comps.ubuntuInstalled && comps.nodeInstalled,
                    modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=MaterialTheme.shapes.medium) { Text("重新安装") }
                TextButton(onClick={c.uninstallDsh(null)},enabled=s.tanukiIdle,modifier=Modifier.heightIn(min=48.dp),
                    colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("卸载") }
            }
            if(currentRelease==null)Caption("重新安装前请先获取官方版本列表。")
        }
        if(!guided)TanukiDetails("更新与恢复") {
            Fact("更新状态",s.update.label)
            if(s.candidate.isNotBlank())Fact("官方候选",s.candidate)
            Caption("选择官方发布后确认安装；切换数据版本前会执行检查并保留匹配快照。")
            if(s.update==UpdateState.RECOVERY_REQUIRED) {
                ActionRow("检查并保留当前版本","不自动回滚项目",enabled=s.tanukiIdle) { c.command("RESUME_UPDATE") }
            }
            ActionRow("恢复匹配快照","恢复程序、配置和会话；项目不回滚",enabled=s.tanukiIdle) { c.command("RESTORE") }
        }
    }
    }
    }
    }

    if (showVersions) AlertDialog(onDismissRequest = { showVersions = false },
        title = { Text("选择 DSH 版本") },
        text = {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f,fill=false).heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(model.versions, key = { it.version }) { release ->
                    Row(Modifier.fillMaxWidth().selectable(selected = selected?.version == release.version, role = Role.RadioButton,
                        onClick = { selectedVersion = release.version }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected?.version == release.version, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(release.version, style = MaterialTheme.typography.titleMedium)
                                if (release.version == currentVersion) StatusPill("当前", true)
                                if (release.version in comps.installedDshVersions) StatusPill("已安装", false)
                            }
                            Caption(if (release.verified) "已通过本机启动检查（历史记录）" else "尚未通过本机启动检查")
                        }
                    }
                }
                item {
                    selected?.let { release ->
                        TanukiDetails("所选版本详情 · ${release.version}") {
                            Caption("Node.js 要求：${release.node}")
                            Caption(release.notes)
                            SelectableCopy("来源：${release.source}\n完整性：${release.integrity}")
                            Caption("安装备用只保存程序，不切换当前数据。较旧版本需先恢复匹配的数据快照才能使用。")
                            Caption("验证记录不代表所有模型与数据迁移均兼容。")
                        }
                    }
                }
            }
            OutlinedButton(enabled=selected!=null && s.tanukiIdle && comps.ubuntuInstalled && comps.nodeInstalled,
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={selected?.let(c.cacheDsh);showVersions=false}) { Text("安装为备用程序") }
            if(selected!=null && selected.version in comps.installedDshVersions) {
                TextButton(enabled=s.tanukiIdle,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
                    onClick={c.uninstallDsh(selected);showVersions=false}) { Text("卸载所选版本") }
            }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = selected != null && s.tanukiIdle && comps.ubuntuInstalled && comps.nodeInstalled &&
                    s.update != UpdateState.RECOVERY_REQUIRED,
                    onClick = { selected?.let(c.install); showVersions = false }) { Text("安装并使用") }
            }
        },
        dismissButton = { TextButton(onClick = { showVersions = false }) { Text("取消") } })
    if(!guided)TanukiCard {
        TanukiDetails("来源与完整性") {
            val ubuntu=RuntimeCatalog.find(RuntimeComponent.UBUNTU,comps.selectedUbuntuVersion)!!
            val node=RuntimeCatalog.find(RuntimeComponent.NODE,comps.selectedNodeVersion)!!
            Caption("Ubuntu、Node.js 与 DSH 分层安装；Ubuntu 镜像和 Node.js 版本按官方 SHA-256 校验，所有下载源必须提供相同文件。")
            SelectableCopy("Ubuntu ${ubuntu.version} SHA-256：${ubuntu.sha256}\nNode.js ${node.version} SHA-256：${node.sha256}")
            Caption("Ubuntu 附加工具由签名 apt 软件源补齐；PRoot 不提供恶意代码隔离。")
            OutlinedButton(onClick={c.command("BASE")},enabled=actionsEnabled,modifier=Modifier.heightIn(min=48.dp)) { Text("验证 Ubuntu 基础启动") }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        TanukiDetails("运行诊断") {
        Fact("DSH 进程", s.service.label)
        Fact("本机 Web 健康", if (s.webHealthy) "就绪" else "未就绪")
        Fact("网页认证", s.web.label)
        Fact("健康检查", s.health.label)
        Fact("DSH 沙箱探测", s.confinement.label)
        Caption("模型上下文会发送至你选择的 API 服务商。工具执行在 DSH 中逐次确认；停止后的任务不会自动重放。")
        ActionRow("重启 DSH", "可能中断当前任务", enabled = s.environment == EnvironmentState.READY && !s.tanukiBusy && s.update != UpdateState.RECOVERY_REQUIRED) { c.command("RESTART") }
        ActionRow("固定健康检查", enabled = s.tanukiIdle && s.environment == EnvironmentState.READY) { c.command("HEALTH") }
        ActionRow("一分钟后台自检", "专用目录计数 60 次，不调用模型 API", enabled = !s.tanukiBusy && s.environment == EnvironmentState.READY) { c.command("BACKGROUND") }
        Text(s.stage, style = MaterialTheme.typography.bodySmall)
        SelectableCopy(model.backgroundResult ?: "暂无完整的后台自检结果")
        TanukiDetails("设备信息") { SelectableCopy(model.deviceInfo) }
        TanukiDetails("最近事件") { SelectableCopy(s.events.takeLast(12).joinToString("\n").ifBlank { "暂无事件" }) }
        TanukiDetails("脱敏日志") {
            val logs = model.liveLogs?.collectAsState()?.value ?: model.recentLogs
            SelectableCopy(logs.takeLast(80).joinToString("\n").ifBlank { "暂无日志" })
        }
        if (s.error.isNotBlank()) SelectableCopy(s.error, error = true)
        }
    }
}

@Composable
private fun SettingsPage(model: TanukiUiModel, c: TanukiUiCallbacks, section: String?, select: (String) -> Unit) {
    val s = model.snapshot
    if (section == null) {
        val categories = listOf(
            "应用更新" to "Tanuki ${model.versionName} · 检查、下载与覆盖安装",
            "存储与空间" to "工作区容量、可用空间与目录占用",
            "项目与备份" to "文件导入、项目导出与私有快照",
            "组件维护与诊断" to "Ubuntu、Node.js、DSH 的版本与维护",
            "运行与后台" to "通知权限、电池设置与后台运行",
            "网页与兼容性" to "WebView 内核更新与能力检测",
            "高级与关于" to "兼容选项、缓存与许可来源")
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("工作空间偏好", style = MaterialTheme.typography.headlineMedium)
            Caption("存储、项目、运行与网页能力，按主题集中管理。")
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column { categories.forEachIndexed { index, (title, detail) ->
                ActionRow(title, detail, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) { select(title) }
                if (index != categories.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            } }
        }
        return
    }
    if (section == "应用更新") {
        ApplicationUpdateCard(model.appUpdate, model.versionName, model.appInstallAllowed,
            c.checkAppUpdates, c.downloadAppUpdate, c.installAppUpdate, c.cancelAppUpdate)
        return
    }
    if (section == "组件维护与诊断") { EnvironmentSection(model, c); return }
    if (section == "存储与空间") TanukiCard {
        WorkspaceStorageControls(model.workspaceStorage, s.tanukiIdle,
            onRefresh = c.refreshWorkspaceStorage, onSave = c.saveWorkspaceCapacity)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TanukiDetails("其他目录占用") {
            Text(model.storageUsage, style = MaterialTheme.typography.bodyMedium)
            ActionRow("统计分配空间", "按实际磁盘分配量统计各目录", onClick = c.measureStorage)
            ActionRow("整理 Ubuntu 安装缓存", "保留工具、项目与版权文件", enabled = s.tanukiIdle && s.environment == EnvironmentState.READY) { c.command("SLIM") }
        }
    }
    if (section == "项目与备份") SettingsGroup("项目与备份") {
        ActionRow("导入文件", "保存为新项目", enabled = s.tanukiIdle, onClick = c.importFiles)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow("导出项目 ZIP", "不含 DSH 会话与密钥；默认排除常见凭据文件", enabled = s.tanukiIdle) { c.command("EXPORT") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TanukiDetails("私有快照") {
            ActionRow("创建私有快照", "包含配置、凭据、扩展与会话", enabled = s.tanukiIdle) { c.command("BACKUP") }
            ActionRow("恢复匹配快照", "会覆盖快照后的配置与会话，项目不回滚", enabled = s.tanukiIdle) { c.command("RESTORE") }
            Caption("私有快照只保存在应用内，卸载即丢失。导出 ZIP 前请检查源文件中的敏感内容。")
        }
    }
    if (section == "运行与后台") SettingsGroup("运行与后台") {
        ActionRow("通知权限", "显示运行状态与停止入口", onClick = c.notifications)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow("电池与应用设置", "管理后台与锁屏行为", onClick = c.appSettings)
        TanukiDetails("后台运行说明") {
            Caption("服务由你主动启动；唤醒锁在运行期间自动续期，停止后释放。后台和息屏运行仍受系统限制。")
        }
    }
    if (section == "网页与兼容性") SettingsGroup("网页与兼容性") {
        ActionRow("官方更新入口", "当前 WebView ${model.support.version}", enabled = s.tanukiIdle, onClick = c.updateWebView)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow("系统 WebView 设置", enabled = s.tanukiIdle, onClick = c.webViewSettings)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow("重新检测能力", "会重新加载网页，请先保存草稿", enabled = model.support.state != WebViewSupportState.CHECKING) { c.command("WEBVIEW_CHECK") }
        TanukiDetails("检测结果与兼容性说明") {
            Text(model.support.provider, style = MaterialTheme.typography.bodySmall)
            Caption("要求 Chromium 123+；${cssResult("subgrid", model.support.subgrid)} · ${cssResult("light-dark()", model.support.lightDark)}")
            SelectableCopy(model.support.description)
            Caption("JavaScript 兼容层不能升级系统内核或补齐 CSS 渲染能力。")
        }
    }
    if (section == "高级与关于") SettingsGroup("高级与关于") {
        TanukiDetails("高级选项") {
            ToggleRow("旧 WebView 标准 API 兼容层", model.legacyShim, c.toggleLegacy)
            Caption("仅适用于当前固定 DSH 与旧 WebView；不补齐 CSS。修改后请明确重新连接。")
            ToggleRow("官方页面 Blob 导出", model.blobExport, c.toggleBlob)
            Caption("仅接收当前官方页面的一次性传输，需系统文件选择器确认，上限 8 MiB；重新连接后生效。")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow("许可与组件来源", "${model.versionName} · ARM64 · GPL-3.0-or-later", onClick = c.showNotices)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow("清除下载缓存", "保留运行环境和项目", enabled = s.tanukiIdle) { c.command("CACHE") }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), content = content)
        }
    }
}

@Composable
private fun DshPage(model: TanukiUiModel, c: TanukiUiCallbacks, browser: WebView, modifier: Modifier,
                    home: () -> Unit, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, settings: () -> Unit,
                    openBrowserWindow: () -> Unit) {
    val s = model.snapshot
    val density = LocalDensity.current
    var headerPixels by remember { mutableIntStateOf(with(density) { 60.dp.roundToPx() }) }
    val topInset = if (fullscreen) 0.dp else with(density) { headerPixels.toDp() }
    val showBrowser = s.service == ServiceState.RUNNING && model.support.state == WebViewSupportState.SUPPORTED && s.web != WebState.RENDER_ERROR
    val originalLayer = remember(browser) { browser.layerType }
    DisposableEffect(browser, fullscreen) {
        // On some WebView/GPU combinations the direct draw path goes blank when system
        // bars are hidden. A dedicated HARDWARE layer keeps immersive pixels composited;
        // release it on exit rather than retaining a full-screen texture in normal mode.
        browser.setLayerType(if (fullscreen) android.view.View.LAYER_TYPE_HARDWARE else originalLayer, null)
        onDispose { browser.setLayerType(originalLayer, null) }
    }
    Box(modifier.fillMaxSize().imePadding()) {
        // One stable document and bounded native host in both window modes: no reload.
        if (s.web != WebState.RENDER_ERROR) key(browser) {
            AndroidView(factory = { context ->
                android.widget.FrameLayout(context).apply {
                    clipChildren = true; clipToPadding = true
                    (browser.parent as? ViewGroup)?.removeView(browser)
                    addView(browser, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }, modifier = Modifier.fillMaxSize().padding(top = topInset).clipToBounds(),
                update = {
                    browser.visibility = if (showBrowser) android.view.View.VISIBLE else android.view.View.INVISIBLE
                })
        }
        if (!showBrowser) Column(Modifier.fillMaxSize().padding(top = topInset).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TanukiCard {
                TanukiIcon(TanukiGlyph.Terminal, tint = MaterialTheme.colorScheme.primary)
                Text(when {
                    s.service != ServiceState.RUNNING -> "工作空间尚未启动"
                    s.web == WebState.RENDER_ERROR -> "网页渲染已中断"
                    else -> "先准备兼容的网页内核"
                }, style = MaterialTheme.typography.titleLarge)
                Fact("DSH 进程", s.service.label)
                Fact("本机 Web 服务", if (s.webHealthy) "就绪" else "未就绪")
                Caption("网页状态：${s.web.label}")
                if (s.service != ServiceState.RUNNING) Button(onClick = { c.command(if (s.environment == EnvironmentState.READY) "START" else "INIT") },
                    enabled = !s.tanukiBusy && s.update != UpdateState.RECOVERY_REQUIRED) { Text(if (s.environment == EnvironmentState.READY) "启动 DSH" else "下载并准备环境") }
                if (s.web == WebState.RENDER_ERROR) OutlinedButton(onClick = { c.command("RECONNECT") }, enabled = s.service == ServiceState.RUNNING) { Text("恢复网页连接") }
                if (s.error.isNotBlank()) TanukiDetails("查看失败信息") { SelectableCopy(s.error, error = true) }
            }
            OperationCard(s, c)
            if (model.support.state != WebViewSupportState.SUPPORTED) WebViewCard(model.support, s.tanukiIdle, c)
        }
        if (!fullscreen) Surface(color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).onSizeChanged { headerPixels = it.height }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = home) { TanukiIcon(TanukiGlyph.Back, "返回概览") }
                Column(Modifier.weight(1f)) {
                    Text("DSH", style = MaterialTheme.typography.titleMedium)
                    Text(s.web.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = openBrowserWindow) { TanukiIcon(TanukiGlyph.Browser, "浏览器小窗") }
                IconButton(onClick = { onFullscreen(true) }) { TanukiIcon(TanukiGlyph.Expand, "进入全屏") }
                IconButton(onClick = { c.command("RECONNECT") }, enabled = s.service == ServiceState.RUNNING && model.support.state == WebViewSupportState.SUPPORTED) {
                    TanukiIcon(TanukiGlyph.Refresh, "重新连接 DSH 网页")
                }
                IconButton(onClick = settings) { TanukiIcon(TanukiGlyph.Settings, "设置") }
            }
        }
        if (fullscreen) Surface(
            shape = RoundedCornerShape(25.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopEnd).zIndex(1f).statusBarsPadding().padding(end = 12.dp, top = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = openBrowserWindow, modifier = Modifier.size(48.dp)) { TanukiIcon(TanukiGlyph.Browser, "浏览器小窗") }
                IconButton(onClick = { onFullscreen(false) }, modifier = Modifier.size(48.dp)) {
                    TanukiIcon(TanukiGlyph.Contract, "退出全屏")
                }
            }
        }
    }
}

@Composable
private fun ComponentDisclosure(title:String,version:String,installed:Boolean,forceExpanded:Boolean=false,guided:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    LaunchedEffect(forceExpanded) { if(forceExpanded)expanded=true }
    val rotation by animateFloatAsState(targetValue=if(expanded)90f else 0f,label="componentChevron")
    Column(Modifier.fillMaxWidth()) {
        if(!guided)Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClickLabel=if(expanded)"收起$title" else "展开$title") { expanded=forceExpanded || !expanded }
            .semantics { stateDescription=if(expanded)"已展开" else "已收起" }.padding(horizontal=20.dp,vertical=16.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(version,style=MaterialTheme.typography.labelMedium,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(if(installed)"已安装" else "未安装",installed)
            Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                TanukiIcon(TanukiGlyph.Chevron,modifier=Modifier.size(20.dp).rotate(rotation),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible=expanded || guided) {
            Column(Modifier.padding(start=if(guided)0.dp else 20.dp,end=if(guided)0.dp else 20.dp,bottom=if(guided)0.dp else 20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        }
    }
}

@Composable
private fun TanukiCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape=MaterialTheme.shapes.extraLarge,
        color=MaterialTheme.colorScheme.surfaceContainerLow,
        modifier=Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)
    }
}

@Composable
private fun Caption(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Fact(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun StatusPill(text: String, active: Boolean) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,RoundedCornerShape(50)))
        Text(text,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StopButton(c: TanukiUiCallbacks, enabled: Boolean) {
    TextButton(onClick = { c.command("STOP") }, enabled=enabled, modifier=Modifier.heightIn(min=48.dp),contentPadding = PaddingValues(horizontal = 12.dp)) {
        TanukiIcon(TanukiGlyph.Stop, "停止 DSH 服务", modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp)); Text("停止")
    }
}

@Composable
private fun ActionRow(title: String, detail: String? = null, enabled: Boolean = true,
                      contentPadding: PaddingValues = PaddingValues(vertical = 12.dp), onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
            if (detail != null) Caption(detail)
        }
        TanukiIcon(TanukiGlyph.Chevron, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.45f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TanukiDetails(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue=if(expanded)90f else 0f,label="detailsChevron")
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,onClickLabel=if(expanded)"收起" else "展开") { expanded=!expanded }
            .semantics { stateDescription=if(expanded)"已展开" else "已收起" },verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TanukiIcon(TanukiGlyph.Chevron,modifier=Modifier.size(20.dp).rotate(rotation),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(visible=expanded) {
            Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun SelectableCopy(text: String, error: Boolean = false) {
    SelectionContainer {
        Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun cssResult(name: String, supported: Boolean?): String = "$name ${when (supported) { true -> "支持"; false -> "缺失"; null -> "待检测" }}"
private fun byteSize(bytes: Long): String = when {
    bytes < 0 -> "未知"
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Small original line symbols; no icon font or additional dependency. */
@Composable
internal fun TanukiIcon(glyph: TanukiGlyph, description: String? = null, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    Canvas(if (description == null) modifier else modifier.semantics { contentDescription = description }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
            val path = Path()
            when (glyph) {
                TanukiGlyph.Home -> {
                    path.moveTo(3f, 10f); path.lineTo(12f, 3f); path.lineTo(21f, 10f)
                    path.moveTo(5f, 9f); path.lineTo(5f, 21f); path.lineTo(10f, 21f); path.lineTo(10f, 15f)
                    path.lineTo(14f, 15f); path.lineTo(14f, 21f); path.lineTo(19f, 21f); path.lineTo(19f, 9f)
                }
                TanukiGlyph.Terminal -> {
                    drawRoundRect(tint, Offset(2f, 4f), Size(20f, 16f), CornerRadius(3f), style = stroke)
                    path.moveTo(6f, 9f); path.lineTo(9f, 12f); path.lineTo(6f, 15f)
                    path.moveTo(12f, 15f); path.lineTo(17f, 15f)
                }
                TanukiGlyph.Folder -> {
                    path.moveTo(3f, 7f); path.lineTo(3f, 5f); path.lineTo(10f, 5f); path.lineTo(12f, 8f)
                    path.lineTo(21f, 8f); path.lineTo(21f, 20f); path.lineTo(3f, 20f); path.close()
                }
                TanukiGlyph.File -> {
                    path.moveTo(5f, 3f); path.lineTo(14f, 3f); path.lineTo(20f, 9f); path.lineTo(20f, 21f)
                    path.lineTo(5f, 21f); path.close(); path.moveTo(14f, 3f); path.lineTo(14f, 9f); path.lineTo(20f, 9f)
                    path.moveTo(9f, 13f); path.lineTo(16f, 13f); path.moveTo(9f, 17f); path.lineTo(16f, 17f)
                }
                TanukiGlyph.Browser -> {
                    drawCircle(tint, 9f, Offset(12f, 12f), style = stroke)
                    drawOval(tint, Offset(8f, 3f), Size(8f, 18f), style = stroke)
                    path.moveTo(3f, 12f); path.lineTo(21f, 12f)
                }
                TanukiGlyph.Layers -> {
                    path.moveTo(3f, 7f); path.lineTo(12f, 3f); path.lineTo(21f, 7f); path.lineTo(12f, 11f); path.close()
                    path.moveTo(3f, 12f); path.lineTo(12f, 16f); path.lineTo(21f, 12f)
                    path.moveTo(3f, 17f); path.lineTo(12f, 21f); path.lineTo(21f, 17f)
                }
                TanukiGlyph.Settings -> {
                    for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                        drawLine(tint, Offset(3f, y), Offset(x - 3f, y), 1.8f, StrokeCap.Round)
                        drawLine(tint, Offset(x + 3f, y), Offset(21f, y), 1.8f, StrokeCap.Round)
                        drawCircle(tint, 2.5f, Offset(x, y), style = stroke)
                    }
                }
                TanukiGlyph.Back -> { path.moveTo(13f, 5f); path.lineTo(6f, 12f); path.lineTo(13f, 19f); path.moveTo(6f, 12f); path.lineTo(21f, 12f) }
                TanukiGlyph.Refresh -> {
                    drawArc(tint, 35f, 285f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke)
                    path.moveTo(20f, 3f); path.lineTo(20f, 9f); path.lineTo(14f, 9f)
                }
                TanukiGlyph.Stop -> drawRoundRect(tint, Offset(5f, 5f), Size(14f, 14f), CornerRadius(3f), style = stroke)
                TanukiGlyph.Chevron -> { path.moveTo(9f, 6f); path.lineTo(15f, 12f); path.lineTo(9f, 18f) }
                TanukiGlyph.Download -> {
                    path.moveTo(12f, 3f); path.lineTo(12f, 15f); path.moveTo(7f, 10f); path.lineTo(12f, 15f); path.lineTo(17f, 10f)
                    path.moveTo(4f, 16f); path.lineTo(4f, 21f); path.lineTo(20f, 21f); path.lineTo(20f, 16f)
                }
                TanukiGlyph.Expand -> {
                    path.moveTo(9f, 3f); path.lineTo(3f, 3f); path.lineTo(3f, 9f)
                    path.moveTo(15f, 3f); path.lineTo(21f, 3f); path.lineTo(21f, 9f)
                    path.moveTo(3f, 15f); path.lineTo(3f, 21f); path.lineTo(9f, 21f)
                    path.moveTo(21f, 15f); path.lineTo(21f, 21f); path.lineTo(15f, 21f)
                }
                TanukiGlyph.Contract -> {
                    path.moveTo(10f, 10f); path.lineTo(4f, 4f); path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                    path.moveTo(14f, 10f); path.lineTo(20f, 4f); path.moveTo(20f, 9f); path.lineTo(20f, 4f); path.lineTo(15f, 4f)
                    path.moveTo(10f, 14f); path.lineTo(4f, 20f); path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                    path.moveTo(14f, 14f); path.lineTo(20f, 20f); path.moveTo(15f, 20f); path.lineTo(20f, 20f); path.lineTo(20f, 15f)
                }
            }
            drawPath(path, tint, style = stroke)
        }
    }
}
