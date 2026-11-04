package com.tanuki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.tanuki.runtime.*
import com.tanuki.storage.DataSnapshots
import com.tanuki.storage.DirectoryUsage
import com.tanuki.storage.WorkspaceFileManager
import com.tanuki.browser.AgentBrowserController
import com.tanuki.browser.BrowserIntegration
import com.tanuki.ui.AgentBrowserPage
import com.tanuki.ui.WorkspaceFilesPage
import com.tanuki.ui.TanukiTheme
import com.tanuki.ui.TanukiUi
import com.tanuki.ui.TanukiUiCallbacks
import com.tanuki.ui.TanukiUiModel
import com.tanuki.update.Release
import com.tanuki.web.DshWebView
import com.tanuki.web.DshFileChooser
import com.tanuki.web.OriginPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    private val manager get() = (application as TanukiApp).runtime
    private val updates get() = (application as TanukiApp).updates
    private val appUpdates get() = (application as TanukiApp).appUpdates
    private lateinit var browser: DshWebView
    private val dshWindowMode by lazy { com.tanuki.ui.DshWindowMode(window) }
    private var agentBrowser: AgentBrowserController? = null
    private var browserBackgroundHost: android.widget.FrameLayout? = null
    private var browserUnavailable by mutableStateOf<String?>(null)
    private var overlayPermissionPrompt by mutableStateOf(false)
    private var browserOpenRequest by mutableIntStateOf(0)
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.provider.Settings.canDrawOverlays(this)) (application as TanukiApp).showBrowserOverlay()
        else {
            agentBrowser?.notify("尚未允许悬浮窗；完整浏览器仍可正常使用")
            android.widget.Toast.makeText(this, "尚未允许悬浮窗；完整浏览器仍可正常使用", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private val workspaceFiles by lazy {
        WorkspaceFileManager(manager.paths, manager.mutation, {
            check(!manager.hasLiveChild && manager.state.value.service in setOf(ServiceState.STOPPED, ServiceState.FAILED, ServiceState.INTERRUPTED)) {
                "请先停止 DSH，再管理文件；避免与 Agent 同时修改文件。"
            }
        }, manager.workspaceStorage)
    }
    private var filesCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: String? = null
    private var pendingBlob: File? = null
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        finishDshFiles(result.resultCode, result.data)
    }
    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch(Dispatchers.IO) {
            try { manager.fileTransfers.track("导入项目文件") { importProjectFiles(uris) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { manager.fail(error) }
        }
    }
    private val exportProject = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { destination -> lifecycleScope.launch(Dispatchers.IO) {
            try { manager.fileTransfers.track("导出项目 ZIP") { exportProjects(destination) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { manager.fail(error) }
        } }
    }
    private val downloadFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val url = pendingDownload; val blob = pendingBlob; pendingDownload = null; pendingBlob = null
        if (uri == null) blob?.delete()
        else if (blob != null) lifecycleScope.launch(Dispatchers.IO) {
            try {
                manager.fileTransfers.track("保存网页 Blob 文件", blob.length()) { id ->
                    contentResolver.openOutputStream(uri, "w")!!.use { out ->
                        blob.inputStream().use { manager.fileTransfers.copy(it, out, id, blob.length()) }
                    }
                }
                manager.event("官方 Blob 文件导出完成")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (t: Exception) { manager.fail(t) } finally { blob.delete() }
        } else if (url != null) {
            val cookie = CookieManager.getInstance().getCookie(url)
            lifecycleScope.launch(Dispatchers.IO) {
                try { saveDownload(url, uri, cookie) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { manager.fail(error) }
            }
        } else {
            manager.fail(IllegalStateException("下载因页面或 Activity 重建而中断，请重新保存文件"))
            android.widget.Toast.makeText(this, "下载已中断，请重新保存文件", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        manager.event(if (granted) "通知权限已授予" else "通知权限未授予；可在系统设置开启")
    }

    fun action(name: String, version: String? = null) {
        if ((name in setOf("INIT", "START", "BASE", "HEALTH", "BACKGROUND", "SLIM") || name.startsWith("UBUNTU_") || name.startsWith("NODE_") || name=="DSH_UNINSTALL") && android.os.Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, RuntimeService::class.java).setAction(name).apply { version?.let { putExtra("version",it) } })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏占位始终交给Compose，避免全屏往返后窗口与页面重复避让。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        consumeBrowserIntent(intent)
        browser = DshWebView(this, manager,
            chooser = ::chooseDshFiles,
            download = { url, name ->
                if (pendingDownload == null && pendingBlob == null) { pendingDownload = url; downloadFile.launch(name) }
                else manager.event("请先完成当前文件保存")
            },
            blobDownload = { file, name ->
                if (pendingDownload == null && pendingBlob == null) { pendingBlob = file; downloadFile.launch(name) }
                else { file.delete(); manager.event("请先完成当前文件保存") }
            }, openPage = ::openDshPage)
        savedInstanceState?.getBundle("web")?.let(browser::restore)
        browser.checkSupport()
        runCatching {
            (application as TanukiApp).acquireBrowser()
        }.onSuccess { controller ->
            agentBrowser = controller
            savedInstanceState?.getBundle("agentBrowser")?.let(controller::restoreState)
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { BrowserIntegration.publish(this@MainActivity, manager.paths, controller.connection) }
                    .onFailure { withContext(Dispatchers.Main) { browserUnavailable = "Agent 连接准备失败，普通浏览仍可用；请检查同名 Skill 或重新打开应用。" } }
            }
        }.onFailure { browserUnavailable = "受控浏览器需要支持独立资料的新版 WebView，请从设置更新内核。" }
        setContent {
            TanukiTheme {
                val s by manager.state.collectAsState()
                val versions by updates.releases.collectAsState()
                val components by manager.components.state.collectAsState()
                val workspaceStorage by manager.workspaceStorage.state.collectAsState()
                val ubuntuSpeed by manager.components.sourceTests.getValue(RuntimeComponent.UBUNTU).state.collectAsState()
                val nodeSpeed by manager.components.sourceTests.getValue(RuntimeComponent.NODE).state.collectAsState()
                val npmSelection by manager.npmSources.state.collectAsState()
                val npmSpeed by manager.npmSources.speed.state.collectAsState()
                val npmSources=remember(npmSelection.version) { manager.npmSources.sources(npmSelection.version) }
                val tools by manager.requiredTools.collectAsState()
                val transfers by manager.fileTransfers.state.collectAsState()
                val appUpdate by appUpdates.state.collectAsState()
                val nodeRequirement=remember(s.dsh,components.dshInstalled) { manager.components.dshNodeRequirement(s.dsh).orEmpty() }
                val ubuntuSources=remember(components.selectedUbuntuVersion) { manager.components.sources(RuntimeComponent.UBUNTU) }
                val nodeSources=remember(components.selectedNodeVersion) { manager.components.sources(RuntimeComponent.NODE) }
                val webSupport by browser.support.collectAsState()
                var legacyShim by remember { mutableStateOf(getSharedPreferences("web", MODE_PRIVATE).getBoolean("legacyPromise", true)) }
                var blobExport by remember { mutableStateOf(getSharedPreferences("web", MODE_PRIVATE).getBoolean("blobExport", true)) }
                var storageUsage by remember { mutableStateOf("点击统计各目录的实际占用") }
                var notices by remember { mutableStateOf<String?>(null) }
                var confirmation by remember { mutableStateOf<String?>(null) }
                var selected by remember { mutableStateOf<Release?>(null) }
                val deviceInfo = remember { manager.deviceInfo() }
                val backgroundResult by produceState<String?>(null, s.stage) {
                    value = withContext(Dispatchers.IO) { manager.paths.read("background-diagnostics.json") }
                }
                val freeBytes by produceState(0L) {
                    while (true) {
                        value = withContext(Dispatchers.IO) { manager.paths.base.usableSpace }
                        delay(5_000)
                    }
                }
                val dark = isSystemInDarkTheme()
                val colors = MaterialTheme.colorScheme
                // Pointer/scroll/progress updates in the browser must not recompose DSH.
                val browserAttention = remember(agentBrowser) {
                    agentBrowser?.state?.map { state ->
                        (state.pendingClient != null || state.downloadRequest != null) to (state.authorizedClient != null)
                    }?.distinctUntilChanged()
                }
                val browserFlags = browserAttention?.collectAsState(initial = false to false)?.value ?: (false to false)
                LaunchedEffect(dark, colors.background, colors.surface) {
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.surface.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                TanukiUi(
                    model = TanukiUiModel(s, webSupport, versions, emptyList(), storageUsage, deviceInfo,
                        backgroundResult, legacyShim, blobExport, BuildConfig.VERSION_NAME, freeBytes,liveLogs=manager.log.recent,
                        components=components,ubuntuSources=ubuntuSources,nodeSources=nodeSources,
                        ubuntuSpeed=ubuntuSpeed,nodeSpeed=nodeSpeed,nodeRequirement=nodeRequirement,workspaceStorage=workspaceStorage,
                        tools=tools,npmSelection=npmSelection,npmSources=npmSources,npmSpeed=npmSpeed,transfers=transfers,
                        appUpdate=appUpdate,appInstallAllowed=appUpdates.installAllowed()),
                    callbacks = TanukiUiCallbacks(
                        command = { command ->
                            when (command) {
                                "START", "HEALTH", "BACKGROUND" -> action(command)
                                "CANCEL" -> { manager.cancel(); updates.cancel() }
                                "UBUNTU_INSTALL", "UBUNTU_REINSTALL", "UBUNTU_UNINSTALL", "NODE_INSTALL", "NODE_REINSTALL", "NODE_UNINSTALL",
                                "INIT", "BASE", "STOP", "RESTART", "SLIM", "WEBVIEW_CHECK", "RECONNECT", "EXPORT", "BACKUP", "RESTORE", "RESUME_UPDATE", "CACHE" -> confirmation = command
                            }
                        },
                        install = { release -> selected = release; confirmation = "UPDATE" },
                        checkUpdates = updates::check,
                        importFiles = { importFiles.launch(arrayOf("*/*")) },
                        measureStorage = {
                            storageUsage = "正在统计实际分配空间…"
                            lifecycleScope.launch {
                                storageUsage = withContext(Dispatchers.IO) {
                                    runCatching { measureDirectories() }.getOrElse { "统计失败：${Redact.text(it.message ?: "未知错误")}" }
                                }
                            }
                        },
                        toggleLegacy = { enabled ->
                            legacyShim = enabled
                            getSharedPreferences("web", MODE_PRIVATE).edit().putBoolean("legacyPromise", enabled).apply()
                        },
                        toggleBlob = { enabled ->
                            blobExport = enabled
                            getSharedPreferences("web", MODE_PRIVATE).edit().putBoolean("blobExport", enabled).apply()
                        },
                        showNotices = {
                            lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { assets.open("THIRD_PARTY_NOTICES").bufferedReader().use { it.readText() } } }
                                    .onSuccess { notices = it }.onFailure(manager::fail)
                            }
                        },
                        updateWebView = ::openWebViewUpdater,
                        webViewSettings = ::openWebViewSettings,
                        appSettings = ::openAppSettings,
                        notifications = {
                            if (android.os.Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else openAppSettings()
                        },
                        connectDsh = { browser.connect() },
                        selectSource = { component,id -> runCatching { manager.components.selectSource(component,id) }.onFailure(manager::fail) },
                        selectVersion={ component,version->runCatching { manager.components.selectVersion(component,version) }.onFailure(manager::fail) },
                        testSources=manager.components::testSources,
                        prepareSources=manager.components::prepareSources,
                        cancelSourceTests={manager.components.sourceTests.getValue(it).cancel()},
                        selectNpmSource={id->runCatching { manager.npmSources.select(id) }.onFailure(manager::fail)},
                        prepareNpmSources=manager.npmSources::prepare,
                        testNpmSources=manager.npmSources::test,
                        cancelNpmSourceTests=manager.npmSources.speed::cancel,
                        refreshWorkspaceStorage=manager::refreshWorkspaceStorage,
                        saveWorkspaceCapacity={limit -> withContext(Dispatchers.IO) { manager.configureWorkspaceCapacity(limit) }},
                        reinstallDsh = { release -> selected=release;confirmation="DSH_REINSTALL" },
                        uninstallDsh = { release -> selected=release;confirmation="DSH_UNINSTALL" },
                        cacheDsh = { release -> selected=release;confirmation="DSH_STAGE" },
                        setFullscreen = dshWindowMode::setFullscreen,
                        openBrowser = {
                            (application as TanukiApp).hideBrowserOverlay()
                            (application as TanukiApp).startBrowserBackground()
                        },
                        revokeBrowser = { agentBrowser?.takeControl() },
                        openBrowserOverlay = ::requestBrowserOverlay,
                        checkAppUpdates = appUpdates::check,
                        downloadAppUpdate = appUpdates::download,
                        installAppUpdate = ::confirmApplicationUpdate,
                        cancelAppUpdate = appUpdates::cancel
                    ),
                    browserView = browser.view,
                    browserOpenRequest = browserOpenRequest,
                    browserRequest = browserFlags.first,
                    browserAuthorized = browserFlags.second,
                    browserAvailable = agentBrowser != null,
                    filesPage = { modifier, bottomInset ->
                        WorkspaceFilesPage(workspaceFiles,
                            onImport = { importFiles.launch(arrayOf("*/*")) },
                            onExport = { confirmation = "EXPORT" }, modifier = modifier, bottomInset = bottomInset,
                            runtimeRunning = s.service in setOf(ServiceState.STARTING, ServiceState.RUNNING, ServiceState.STOPPING),
                            onStop = { confirmation = "STOP" })
                    },
                    agentBrowserPage = { modifier, browserFullscreen, onBrowserFullscreen, onOpenSettings, bottomInset ->
                        agentBrowser?.let { AgentBrowserPage(it, modifier, browserUnavailable,
                            onShowOverlay = ::requestBrowserOverlay,
                            onHideOverlay = { (application as TanukiApp).hideBrowserOverlay() }, openRequest = browserOpenRequest,
                            fullscreen = browserFullscreen, onFullscreen = onBrowserFullscreen,
                            onSettings = onOpenSettings, bottomInset = bottomInset) }
                            ?: Column(modifier) {
                                Text(browserUnavailable ?: "浏览器暂不可用")
                                if (onOpenSettings != null) TextButton(onClick = onOpenSettings) { Text("设置") }
                            }
                    }
                )
                if (overlayPermissionPrompt) AlertDialog(onDismissRequest = { overlayPermissionPrompt = false },
                    title = { Text("允许浏览器悬浮窗") },
                    text = { Text("需要在系统设置允许「显示在其他应用上层」。授权后，浏览器可停留在其他应用上方；拖动顶部移动，拖动右下角调整大小。只显示本应用的网页，不读取其他应用内容。可以随时关闭窗口或撤销系统授权。") },
                    confirmButton = { TextButton(onClick = {
                        overlayPermissionPrompt = false
                        runCatching { overlayPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                            .onFailure { agentBrowser?.notify("无法打开系统悬浮窗设置，请从应用管理的特殊访问权限进入") }
                    }) { Text("前往系统设置") } },
                    dismissButton = { TextButton(onClick = { overlayPermissionPrompt = false }) { Text("暂不开启") } })
                notices?.let { content ->
                    AlertDialog(onDismissRequest = { notices = null }, title = { Text("许可与组件来源") },
                        text = { Text(content, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                        confirmButton = { TextButton(onClick = { notices = null }) { Text("关闭") } })
                }
                confirmation?.let { command ->
                    AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(confirmationTitle(command)) },
                        text = { Text(confirmationMessage(command, selected)) },
                        confirmButton = {
                            TextButton(onClick = {
                                when (command) {
                                    "INIT", "BASE", "STOP", "SLIM", "UBUNTU_INSTALL", "UBUNTU_REINSTALL", "UBUNTU_UNINSTALL", "NODE_INSTALL", "NODE_REINSTALL", "NODE_UNINSTALL" -> action(command)
                                    "DSH_UNINSTALL" -> action(command,selected?.version)
                                    "DSH_REINSTALL" -> selected?.let { updates.install(it,true) }
                                    "DSH_STAGE" -> selected?.let(updates::stage)
                                    "RESTART" -> lifecycleScope.launch {
                                        try { manager.stop(); action("START") }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Throwable) { manager.fail(error) }
                                    }
                                    "RECONNECT" -> if (browser.needsRecreation || s.web == WebState.RENDER_ERROR) recreate() else browser.connect(true)
                                    "WEBVIEW_CHECK" -> if (browser.needsRecreation) recreate() else browser.checkSupport(true)
                                    "UPDATE" -> selected?.let(updates::install)
                                    "RESTORE" -> updates.restore()
                                    "RESUME_UPDATE" -> updates.resumeInterrupted()
                                    "EXPORT" -> exportProject.launch("${getString(R.string.app_name)}-projects.zip")
                                    "BACKUP" -> lifecycleScope.launch(Dispatchers.IO) {
                                        try {
                                            manager.fileTransfers.track("创建应用私有数据快照") {
                                                manager.mutation.withLock {
                                                    check(!manager.hasLiveChild && manager.state.value.service in setOf(ServiceState.STOPPED, ServiceState.FAILED, ServiceState.INTERRUPTED)) { "请先停止 DSH" }
                                                    DataSnapshots.create(manager.paths); manager.event("应用私有数据快照完成")
                                                }
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) { manager.fail(error) }
                                    }
                                    "CACHE" -> lifecycleScope.launch(Dispatchers.IO) {
                                        try {
                                            manager.fileTransfers.track("清理下载缓存") {
                                                manager.mutation.withLock {
                                                    RuntimeManager.deleteTree(manager.paths.downloads)
                                                    manager.paths.downloads.mkdirs(); manager.event("下载缓存已清除")
                                                }
                                            }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (error: Exception) { manager.fail(error) }
                                    }
                                }
                                confirmation = null
                            }) { Text(if (command in setOf("INIT", "BASE")) "确认来源并下载" else "确认") }
                        },
                        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
                }
            }
        }
        // The active background document retains a measured viewport for RPC, not a second
        // full-screen drawing pass behind every native/DSH frame. Inactive tabs are detached.
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val background = android.widget.FrameLayout(this).apply {
            visibility = android.view.View.INVISIBLE
            clipChildren = true; clipToPadding = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        content.addView(background, 0, android.view.ViewGroup.LayoutParams(-1, -1))
        browserBackgroundHost = background; agentBrowser?.attachBackgroundHost(background)
    }

    private fun confirmationTitle(command: String): String = when (command) {
        "INIT", "BASE" -> "准备精简运行环境"
        "STOP" -> "停止服务"
        "RESTART" -> "重启服务"
        "UPDATE" -> "安装所选 DSH 版本"
        "DSH_UNINSTALL" -> "卸载 DSH 程序"
        "DSH_REINSTALL" -> "重新安装当前 DSH"
        "DSH_STAGE" -> "安装 DSH 备用程序"
        "UBUNTU_INSTALL" -> "安装精简 Ubuntu"
        "UBUNTU_REINSTALL" -> "重新安装 Ubuntu"
        "UBUNTU_UNINSTALL" -> "卸载 Ubuntu 镜像"
        "NODE_INSTALL" -> "安装 Node.js"
        "NODE_REINSTALL" -> "重新安装 Node.js"
        "NODE_UNINSTALL" -> "卸载 Node.js"
        "RESTORE" -> "恢复数据快照"
        "RESUME_UPDATE" -> "检查当前版本"
        "SLIM" -> "整理 Ubuntu 安装缓存"
        "WEBVIEW_CHECK" -> "重新检测 WebView"
        "RECONNECT" -> "重新加载官方网页"
        "EXPORT" -> "导出项目"
        "BACKUP" -> "备份私有数据"
        "CACHE" -> "清除下载缓存"
        else -> "确认操作"
    }

    private fun confirmationMessage(command: String, release: Release?): String = when (command) {
        "INIT", "BASE" -> "下载所选 Ubuntu Base ${manager.components.selected(RuntimeComponent.UBUNTU).version} ARM64 精简镜像，不含桌面。${if(command=="INIT") "随后安装所选 Node.js ${manager.components.selected(RuntimeComponent.NODE).version} 与官方 DSH ${RuntimeManager.DSH}，仅添加必要工具。" else "本次只验证 App 内 Linux 启动。"}\n\nUbuntu 下载源：${manager.components.sourceFor(RuntimeComponent.UBUNTU).title}；Node.js 下载源：${manager.components.sourceFor(RuntimeComponent.NODE).title}。每个版本使用对应官方 SHA-256 校验。建议预留 4 GB 空间；模型上下文会发送至你选择的服务商。"
        "UBUNTU_INSTALL", "UBUNTU_REINSTALL" -> "下载 Ubuntu Base ${manager.components.selected(RuntimeComponent.UBUNTU).version} ARM64，来源：${manager.components.sourceFor(RuntimeComponent.UBUNTU).title}。验证该镜像对应的官方 SHA-256 后安装基础工具。${if(command=="UBUNTU_REINSTALL") "Ubuntu 内自行安装的软件与系统修改会被替换。" else ""}\n\n独立 Node.js、DSH 引擎、配置、会话与项目保留；失败时恢复原 Ubuntu。请先停止 DSH。"
        "UBUNTU_UNINSTALL" -> "移除 Ubuntu 镜像及其中安装的软件。Node.js 将迁移并保留在独立目录；DSH 程序、配置、会话、快照和项目保留。重新安装 Ubuntu 后才能再次运行 DSH。请先停止服务。"
        "NODE_INSTALL", "NODE_REINSTALL" -> "下载 Node.js ${manager.components.selected(RuntimeComponent.NODE).version} ARM64，来源：${manager.components.sourceFor(RuntimeComponent.NODE).title}。验证该版本压缩包、pnpm 和 DSH 的兼容性后再替换；失败时保留当前 Node。Ubuntu、DSH 程序、配置、会话与项目保留。请先停止服务。"
        "NODE_UNINSTALL" -> "只移除 Node.js 与 pnpm 工具层，保留 Ubuntu、DSH 程序、配置、会话与项目。重新安装 Node.js 后才能再次运行 DSH。请先停止服务。"
        "DSH_UNINSTALL" -> "卸载 DSH ${release?.version ?: manager.paths.metadata().optString("dsh","当前版本")} 的引擎程序，保留配置、会话、插件、Skill、项目和其他已安装版本。关联快照恢复前需要先重装匹配的引擎。请先停止服务。"
        "DSH_STAGE" -> "下载并安装官方 DSH ${release?.version ?: "所选版本"} 程序，使用隔离目录检查启动；不打开正式会话，不更改当前版本或数据。较旧版本可先安装为备用，再恢复匹配的数据快照。请先停止服务。"
        "DSH_REINSTALL" -> "移除并重新安装当前 DSH ${release?.version ?: ""} 的官方程序与依赖。配置、会话、插件和项目保留；若下载失败，引擎会保持待安装，可重试。请先停止服务。"
        "EXPORT" -> "导出工作区文件，默认排除常见凭据文件。请先停止服务以获得一致结果，仍需检查源文件中的敏感内容。"
        "CACHE" -> "只删除下载压缩包和未完成下载，不删除环境或项目。"
        "UPDATE" -> "安装官方 DSH ${release?.version ?: "所选版本"}，先进行候选启动检查、备份，再切换。请先停止服务。检查不使用正式模型凭据，正式服务保留官方调度设置。"
        "BACKUP" -> "请先停止服务。快照包含私有凭据与会话，仅保存在 App 内；卸载会丢失，不能作为跨设备备份。"
        "RESTORE" -> "请先停止服务。恢复会覆盖快照之后的会话与配置；项目代码不回滚。"
        "RESUME_UPDATE" -> "检查当前已切换版本并保留现有数据与官方调度设置。失败时保留恢复入口，不自动回滚项目。"
        "SLIM" -> "启用后续安装的精简策略并清理 apt 下载缓存，保留已安装工具、版权文件、会话和项目。请先停止 DSH。"
        "WEBVIEW_CHECK" -> "重新检测会重新加载网页，可能丢失尚未保存的草稿；本机 DSH 任务继续由服务管理。"
        "RECONNECT" -> "重新连接会加载网页，可能丢失尚未保存的草稿；本机 DSH 任务继续由服务管理。"
        else -> "可能中断正在运行的任务。无法可靠判断所有 Agent 子任务状态，停止后不会自动重放操作。"
    }

    private suspend fun measureDirectories(): String {
        val lines=mutableListOf<String>()
        val directories=listOf(
            "Linux 环境" to manager.paths.runtime, "DSH 程序" to manager.paths.engines,
            "配置、会话和扩展" to manager.paths.data, "工作区" to manager.paths.workspaces,
            "下载缓存" to manager.paths.downloads, "私有备份" to manager.paths.backups, "日志" to manager.paths.logs
        )
        for((name,path) in directories) {
            val use=DirectoryUsage.measure(path, linkedFilesRoot=manager.paths.base)
            lines+="$name：${use.allocatedBytes / (1024 * 1024)} MiB${when { use.incompleteReason != null -> "（${use.incompleteReason}）"; use.unreadableEntries > 0 -> "（有 ${use.unreadableEntries} 项无法读取）"; else -> "" }}"
        }
        return lines.joinToString("\n")
    }

    private fun ensureWebViewUpdateIdle(): Boolean {
        val busy = manager.hasActiveOperation || updates.hasActiveOperation || manager.state.value.service !in setOf(ServiceState.STOPPED, ServiceState.FAILED, ServiceState.INTERRUPTED)
        if (busy) android.widget.Toast.makeText(this, "请先完成操作并停止 DSH，再更新 WebView", android.widget.Toast.LENGTH_LONG).show()
        return !busy
    }
    private fun openWebViewUpdater() {
        if (!ensureWebViewUpdateIdle()) return
        val provider = android.webkit.WebView.getCurrentWebViewPackage()?.packageName ?: "com.google.android.webview"
        if (!provider.startsWith("com.google.android.webview")) { openWebViewSettings(); return }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.webview"))) }
            .recoverCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview"))) }
            .onFailure { android.widget.Toast.makeText(this, "未找到可用商店，请使用设备官方渠道更新系统 WebView", android.widget.Toast.LENGTH_LONG).show() }
    }
    private fun openWebViewSettings() {
        if (!ensureWebViewUpdateIdle()) return
        runCatching { startActivity(Intent("android.settings.WEBVIEW_SETTINGS")) }
            .recoverCatching {
                val provider = android.webkit.WebView.getCurrentWebViewPackage()?.packageName ?: "com.google.android.webview"
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$provider")))
            }.onFailure { manager.event("无法打开系统 WebView 设置，请从系统应用管理进入") }
    }
    private fun openDshPage(url: String) {
        if (!OriginPolicy.browserPage(url, manager.origin)) return
        val controller = agentBrowser
        val runtime = manager
        val opened = if (controller == null) false else if (com.tanuki.web.DshOAuth.isAuthorization(url)) {
            val origin = runtime.origin; val identity = runtime.authenticatedUrl
            origin != null && identity != null && controller.openDshAuthorization(url, origin, identity) {
                runtime.origin to runtime.authenticatedUrl
            }
        } else controller.newTab(url) != null
        if (!opened) {
            android.widget.Toast.makeText(this, controller?.state?.value?.notice ?: browserUnavailable ?: "浏览器暂不可用，请先检查 WebView", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (android.provider.Settings.canDrawOverlays(this)) (application as TanukiApp).showBrowserOverlay()
        else {
            // Keep a usable login tab even if the user declines the OS overlay permission.
            browserOpenRequest++
            overlayPermissionPrompt = true
        }
    }
    private fun requestBrowserOverlay() {
        if (agentBrowser == null) {
            android.widget.Toast.makeText(this, browserUnavailable ?: "浏览器暂不可用，请先检查 WebView", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (android.provider.Settings.canDrawOverlays(this)) (application as TanukiApp).showBrowserOverlay()
        else overlayPermissionPrompt = true
    }
    private fun consumeBrowserIntent(value: Intent?) {
        if (value?.getBooleanExtra(com.tanuki.browser.BrowserService.OPEN_BROWSER, false) == true) {
            value.removeExtra(com.tanuki.browser.BrowserService.OPEN_BROWSER)
            browserOpenRequest++
            (application as TanukiApp).hideBrowserOverlay()
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeBrowserIntent(intent) }
    private fun confirmApplicationUpdate() {
        if (!appUpdates.installAllowed()) {
            android.widget.Toast.makeText(this, "请先停止 DSH，并等待环境与文件任务结束", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(this).setTitle("允许安装 Tanuki 更新")
                .setMessage("需要在系统设置中允许此应用安装更新。授权后请返回应用，再次点击安装；不会自动安装或清除数据。")
                .setNegativeButton("取消", null).setPositiveButton("前往设置") { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { appUpdates.installerFailed() }
                }.show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("覆盖更新 Tanuki？")
            .setMessage("已核对同包名、同签名和更高版本。Ubuntu、Node.js、DSH 与项目会保留；请勿卸载旧应用。下一步仍需在 Android 安装器中确认。")
            .setNegativeButton("取消", null).setPositiveButton("继续安装") { _, _ ->
                appUpdates.prepareInstall { installer ->
                    if (!isFinishing && !isDestroyed) runCatching {
                        check(appUpdates.installAllowed())
                        startActivity(installer)
                    }.onFailure { appUpdates.installerFailed() }
                }
            }.show()
    }
    private fun openAppSettings() { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = Bundle(); browser.save(b); outState.putBundle("web", b)
        agentBrowser?.let { controller -> val state = Bundle(); controller.saveState(state); outState.putBundle("agentBrowser", state) }
    }
    override fun onPause() { agentBrowser?.setVisible(false); super.onPause() }
    override fun onDestroy() {
        val callback = filesCallback
        filesCallback = null
        callback?.onReceiveValue(null)
        browserBackgroundHost?.let { agentBrowser?.detachBackgroundHost(it) }
        browserBackgroundHost = null
        // The application and foreground browser service own tabs/RPC, not this Activity.
        browser.destroy()
        super.onDestroy()
    }

    private fun chooseDshFiles(intent: Intent, callback: ValueCallback<Array<Uri>>) {
        // 上一个系统选择器尚未返回时，拒绝新请求，避免旧结果串到新网页。
        if (filesCallback != null) { callback.onReceiveValue(null); return }
        filesCallback = callback
        try { chooser.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { cancelDshFiles() }
        catch (_: SecurityException) { cancelDshFiles() }
    }

    private fun finishDshFiles(resultCode: Int, data: Intent?) {
        val callback = filesCallback
        filesCallback = null
        val uris = runCatching { DshFileChooser.result(resultCode, data) }.getOrNull()
        if (resultCode == RESULT_OK && (callback == null || uris == null)) {
            android.widget.Toast.makeText(this, "未能取得附件，页面可能已重建，请重新选择文件", android.widget.Toast.LENGTH_LONG).show()
        }
        callback?.onReceiveValue(uris)
    }

    private fun cancelDshFiles() {
        val callback = filesCallback
        filesCallback = null
        callback?.onReceiveValue(null)
        android.widget.Toast.makeText(this, "无法打开系统文件选择器，请稍后重试", android.widget.Toast.LENGTH_LONG).show()
    }

    private suspend fun importProjectFiles(uris: List<Uri>) = manager.mutation.withLock {
        check(!manager.hasLiveChild && manager.state.value.service in setOf(ServiceState.STOPPED, ServiceState.FAILED, ServiceState.INTERRUPTED)) { "请先停止服务再导入文件" }
        val names = uris.mapIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "file-$index"
        }
        val destination = manager.workspaceStorage.importFiles(names) { index ->
            contentResolver.openInputStream(uris[index]) ?: error("无法打开所选文件")
        }
        manager.event("已导入新项目 ${destination.name}")
    }
    private suspend fun exportProjects(uri: Uri) = manager.mutation.withLock {
        check(!manager.hasLiveChild && manager.state.value.service in setOf(ServiceState.STOPPED, ServiceState.FAILED, ServiceState.INTERRUPTED)) { "请先停止服务再导出，以避免写入中断" }
        contentResolver.openOutputStream(uri, "w")!!.use { output ->
            com.tanuki.storage.ProjectArchive.write(manager.paths.workspaces, manager.paths.base, output)
        }
        manager.event("项目导出完成，不含 DSH 私有数据")
    }
    private suspend fun saveDownload(url: String, uri: Uri, cookie: String?) = manager.fileTransfers.track("下载网页文件") { id ->
        require(OriginPolicy.sameOrigin(url, manager.origin)) { "下载服务来源已变更" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept-Encoding", "identity")
        cookie?.let { c.setRequestProperty("Cookie", it) }
        try {
            check(c.responseCode == 200) { "下载失败 HTTP ${c.responseCode}" }
            val total = if (c.contentEncoding.isNullOrBlank() || c.contentEncoding == "identity") c.contentLengthLong else -1L
            manager.fileTransfers.update(id, 0, total)
            contentResolver.openOutputStream(uri, "w")!!.use { out ->
                c.inputStream.use { manager.fileTransfers.copy(it, out, id, total) }
            }
            manager.event("官方文件下载完成")
        } finally { c.disconnect() }
    }
}
