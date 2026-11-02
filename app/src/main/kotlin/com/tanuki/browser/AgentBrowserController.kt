package com.tanuki.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

// The transport capability and connection identity are never exposed to a web page.
class BrowserConnection(val port: Int, val token: String, val clientId: String = UUID.randomUUID().toString())
data class BrowserTabInfo(val id: String, val url: String, val title: String, val loading: Boolean)
data class BrowserState(
    val visible: Boolean = false, val pendingClient: String? = null, val authorizedClient: String? = null,
    val control: String = "AI 未授权", val url: String = "", val title: String = "新页面",
    val loading: Boolean = false, val canGoBack: Boolean = false, val canGoForward: Boolean = false,
    val error: String? = null, val closed: Boolean = false, val rendererFailed: Boolean = false,
    val operations: List<String> = emptyList(), val history: List<BrowserVisit> = emptyList(),
    val tabs: List<BrowserTabInfo> = emptyList(), val activeTab: String = "", val loadProgress: Int = 0,
    val zoomPercent: Int = 100, val findQuery: String = "", val findIndex: Int = 0, val findCount: Int = 0,
    val backgroundEnabled: Boolean = false, val downloadRequest: BrowserDownloadRequest? = null,
    val notice: String? = null, val pageMode: String = BrowserPageMode.WINDOWS.name, val takeoverRequested: Boolean = false,
    val overlayFrame: BrowserOverlayFrame? = null, val overlayCollapsed: Boolean = false
)
data class BrowserVisit(val url: String, val title: String, val time: Long = System.currentTimeMillis())

/** Visibility is presentation, never an authorization revocation or an implicit grant. */
internal class BrowserPermissionGate {
    data class Ticket(val generation: Long, val client: String)
    private var generation = 0L
    private var visible = false
    private var closed = false
    private var granted: String? = null
    private var pending: String? = null
    @Synchronized fun visibility(value: Boolean) { visible = value }
    @Synchronized fun request(client: String) {
        if (closed) throw RpcFailure("browser_unavailable")
        if (granted != null || (pending != null && pending != client)) throw RpcFailure("control_busy")
        pending = client
    }
    @Synchronized fun grant(client: String): Boolean {
        if (closed || !visible || pending != client) return false
        generation++; granted = client; pending = null; return true
    }
    @Synchronized fun grantFromNativeConfirmation(client: String): Boolean {
        if (closed || !visible) return false
        generation++; granted = client; pending = null; return true
    }
    @Synchronized fun grantProcessConnection(client: String): Boolean {
        if (closed) return false
        generation++; granted = client; pending = null; return true
    }
    @Synchronized fun revoke() { generation++; granted = null; pending = null }
    @Synchronized fun close() { closed = true; visible = false; revoke() }
    @Synchronized fun ticket(client: String): Ticket {
        if (closed || granted != client) throw RpcFailure("permission_required")
        return Ticket(generation, client)
    }
    @Synchronized fun valid(ticket: Ticket): Boolean = !closed && granted == ticket.client && generation == ticket.generation
    @Synchronized fun epoch(): Long = generation
    @Synchronized fun current(epoch: Long): Boolean = !closed && generation == epoch
}
internal class RpcFailure(val code: String) : Exception(code)

/** Process-owned browser. Native policy grants this process's connection; RPC never grants itself. */
@SuppressLint("SetJavaScriptEnabled")
class AgentBrowserController(private val context: Context, val library: BrowserLibrary = BrowserLibrary(context),
    private val privateOrigins: () -> Set<String> = { emptySet() }) {
    private val main = Handler(Looper.getMainLooper())
    private val gate = BrowserPermissionGate()
    private val mutableState = MutableStateFlow(BrowserState(history = library.state.value.history))
    val state: StateFlow<BrowserState> = mutableState.asStateFlow()
    private val stopped = AtomicBoolean(false)
    private var rendererGone = false
    private val operationBusy = AtomicBoolean(false)
    private val permit = java.util.concurrent.atomic.AtomicReference<ActionPermit?>(null)
    private val gateName = "hpGate" + UUID.randomUUID().toString().replace("-", "")
    private var requestGeneration = 0L
    private var defaultPolicyApplied = false
    private var takeoverClient: String? = null
    private data class ActionPermit(val nonce: String, val ticket: BrowserPermissionGate.Ticket, val document: String, val expires: Long)
    private inner class ActionBridge {
        @JavascriptInterface fun consume(nonce: String): Boolean {
            val next = permit.get() ?: return false
            if (nonce != next.nonce || !permit.compareAndSet(next, null)) return false
            return !stopped.get() && gate.valid(next.ticket) && documentId == next.document &&
                android.os.SystemClock.elapsedRealtime() < next.expires
        }
    }
    @Volatile private var documentId = UUID.randomUUID().toString()
    private var nodeKey = "__hp_${UUID.randomUUID().toString().replace("-", "")}"
    private inner class Tab(val id: String, var url: String = "", var title: String = "新页面") {
        var rendered: WebView? = null
        val view: WebView get() = rendered ?: createView(this)
        var resumed: Boolean? = null
        var preview: Bitmap? = null
        var loading = false; var progress = 0; var error: String? = null; var gone = false
        var zoom = 100; var scale = 0f; var baseScale = 0f; var mode = BrowserPageMode.WINDOWS
        var query = ""; var match = 0; var matches = 0; var needsRestore = false
        var manualZoom = false; var fittingWidth = false; var fittedViewWidth = 0
    }
    private val tabs = mutableListOf<Tab>()
    private var selected = ""
    private val current: Tab get() = tabs.first { it.id == selected }
    val webView: WebView get() = current.view
    private var backgroundHost: ViewGroup? = null
    private var activityVisible = false
    var onBackgroundNeeded: () -> Unit = {}
    private val server: ServerSocket
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { task -> Thread(task, "browser-rpc").apply { isDaemon = true } })
    private val deadline = java.util.concurrent.ScheduledThreadPoolExecutor(1, { task ->
        Thread(task, "browser-deadline").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true }
    private val sockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    val connection: BrowserConnection

    init {
        uiThread()
        check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { "独立浏览器需要支持 MULTI_PROFILE 的 WebView" }
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        connection = BrowserConnection(server.localPort, android.util.Base64.encodeToString(secret,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING))
        val saved = library.state.value
        for (entry in saved.tabs.take(12)) {
            val tab = createTab(entry.id.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: UUID.randomUUID().toString())
            tab.url = entry.url.takeIf { it.isEmpty() || allowed(it) }.orEmpty(); tab.title = entry.title.take(200)
            tab.needsRestore = tab.url.isNotEmpty(); tab.mode = BrowserPageMode.from(entry.mode)
        }
        if (tabs.isEmpty()) createTab()
        selected = saved.selectedTab?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        publishTabs()
        Thread({
            while (!stopped.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                val expiry = try { deadline.schedule({ runCatching { socket.close() } }, 12, TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.RejectedExecutionException) { sockets.remove(socket); runCatching { socket.close() }; break }
                try { workers.execute {
                    try { socket.use { serve(it) } } finally { expiry.cancel(false); sockets.remove(socket) }
                } } catch (_: java.util.concurrent.RejectedExecutionException) { expiry.cancel(false); sockets.remove(socket); runCatching { socket.close() } }
            }
        }, "browser-listener").apply { isDaemon = true; start() }
    }
    private fun createTab(id: String = UUID.randomUUID().toString()): Tab {
        if (tabs.size >= 12) throw RpcFailure("tab_limit")
        return Tab(id).also { tabs += it }
    }
    /** Restored tabs are metadata until selected; never build twelve renderers at startup. */
    private fun createView(tab: Tab): WebView {
        val view = WebView(context)
        tab.rendered = view
        WebViewCompat.setProfile(view, "tanuki-browser-v1")
        WebViewCompat.getProfile(view).cookieManager.setAcceptThirdPartyCookies(view, false)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; blockNetworkLoads = false
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true; setGeolocationEnabled(false)
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            userAgentString = tab.mode.userAgent(WebSettings.getDefaultUserAgent(context))
        }
        for (name in listOf("searchBoxJavaBridge_", "accessibility", "accessibilityTraversal")) view.removeJavascriptInterface(name)
        view.addJavascriptInterface(ActionBridge(), gateName)
        view.setDownloadListener { url, _, disposition, mime, size ->
            if (allowed(url)) update { copy(downloadRequest = BrowserDownloadRequest(url, attachmentName(disposition) ?: URLUtil.guessFileName(url, disposition, mime), mime.orEmpty(), size)) }
            else update { copy(notice = "仅支持 HTTP/HTTPS 文件下载；此类链接请使用站点的普通下载入口") }
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN && !tab.fittingWidth) tab.manualZoom = true
            if (state.value.takeoverRequested) true
            else if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN && state.value.authorizedClient != null) {
                requestTakeover(); true // Do not forward the tap to links, forms or a running agent action.
            } else false
        }
        view.setFindListener { active, count, done ->
            if (done) { tab.match = if (count == 0) 0 else active + 1; tab.matches = count; publishTabs() }
        }
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left; val oldWidth = oldRight - oldLeft
            if (width > 0 && width != oldWidth && !tab.manualZoom) fitContentWidth(tab)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title.orEmpty().take(200); publishTabs(); saveTabs() }
            override fun onProgressChanged(view: WebView, progress: Int) { tab.progress = progress; publishTabs() }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
            override fun onCreateWindow(view: WebView?, dialog: Boolean, gesture: Boolean, result: android.os.Message?): Boolean {
                if (!gesture || result == null || tabs.size >= 12) return false
                val next = createTab(); selectTab(next.id)
                (result.obj as? WebView.WebViewTransport)?.webView = next.view
                result.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.rendered === window }?.let { closeTab(it.id) } }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                if (tab.baseScale == 0f || tab.fittingWidth) tab.baseScale = newScale
                tab.fittingWidth = false
                tab.scale = newScale
                tab.zoom = if (tab.baseScale > 0) (100 * newScale / tab.baseScale).roundToInt() else 100
                publishTabs()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = blocked(request.url.toString())
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blocked(url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = if (allowed(request.url.toString())) null else deniedResource()
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (tab.id == selected) invalidateDocument()
                if (url != null && !allowed(url)) { view.stopLoading(); tab.error = "已阻止此地址"; tab.loading = false; publishTabs(); return }
                tab.url = url.orEmpty().take(8192); tab.loading = true; tab.error = null; tab.query = ""; tab.match = 0; tab.matches = 0
                tab.preview = null
                tab.baseScale = 0f; tab.zoom = 100; tab.manualZoom = false; tab.fittingWidth = false; tab.fittedViewWidth = 0
                publishTabs()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (stopped.get() || tab !in tabs) return
                tab.loading = false; tab.progress = 100
                if (url != null && allowed(url)) { tab.url = url; library.visit(url, view.title.orEmpty()); update { copy(history = library.state.value.history) } }
                publishTabs(); saveTabs(); fitContentWidth(tab)
                view.postDelayed({ fitContentWidth(tab) }, 150)
            }
            private fun fail(message: String) { tab.error = message; tab.loading = false; publishTabs() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("页面加载失败，请检查地址后重试")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) fail("页面返回错误（${response.statusCode}）")
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); fail("证书验证失败，已停止加载") }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                gate.revoke(); permit.set(null); requestGeneration++; invalidateDocument()
                tab.gone = true; tab.loading = false; tab.error = "此页面的渲染进程已停止，请关闭该页后新建页面"
                (view.parent as? ViewGroup)?.removeView(view); view.destroy()
                update { copy(authorizedClient = null, pendingClient = null, control = "授权已撤销") }; publishTabs(); return true
            }
        }
        view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ensureSize(view)
        return view
    }
    private fun fitContentWidth(tab: Tab) {
        if (stopped.get() || tab !in tabs || tab.gone || tab.manualZoom || tab.loading || tab.view.width <= 0) return
        val view = tab.view
        // CSS width is independent of the overlay's native pixel size. Read the document's
        // full extent, not just the clipped viewport. Manual/AI zoom remains sticky on resize.
        view.evaluateJavascript("""(function(){
            const width=Math.max(1,Math.ceil(Math.max(document.documentElement.scrollWidth,document.body?.scrollWidth||0)));
            // Chromium otherwise clamps zoom-out to 25%, which still crops a wide page
            // inside a narrow overlay. Preserve the site's layout width and initial scale.
            let meta=document.querySelector('meta[name="viewport"]'),relaxed=false;
            if(meta){
                const old=meta.content||'';
                const next=old.replace(/(?:^|,)\s*(?:minimum-scale|maximum-scale|user-scalable)\s*=[^,]*/gi,'')+
                    ',minimum-scale=0.01,maximum-scale=10,user-scalable=yes';
                if(next!==old){meta.content=next;relaxed=true;}
            }
            return {width:width,relaxed:relaxed};
        })()""") { raw ->
            if (stopped.get() || tab !in tabs || tab.gone || tab.manualZoom || tab.loading || tab.view !== view || view.width <= 0) return@evaluateJavascript
            val result = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
            val contentWidth = result.optDouble("width").takeIf { it.isFinite() && it > 1 } ?: return@evaluateJavascript
            if (result.optBoolean("relaxed")) {
                // Viewport constraints apply on the next renderer layout, not during JS execution.
                view.postDelayed({ fitContentWidth(tab) }, 100)
                return@evaluateJavascript
            }
            val desired = view.width.toFloat() / contentWidth.toFloat()
            @Suppress("DEPRECATION") val previous = view.scale
            if (!desired.isFinite() || desired <= 0 || previous <= 0 || tab.fittedViewWidth == view.width && kotlin.math.abs(previous - desired) < .01f) return@evaluateJavascript
            tab.fittingWidth = true; tab.fittedViewWidth = view.width; tab.baseScale = desired; tab.zoom = 100
            val oldScrollX = view.scrollX; val oldScrollY = view.scrollY
            view.scrollTo(0, oldScrollY)
            view.zoomBy(desired / previous)
            view.scrollTo(oldScrollX, oldScrollY)
            view.post { if (!tab.manualZoom) { tab.fittingWidth = false; publishTabs() } }
        }
    }
    /** Restore the default full-width reading mode without reloading the document. */
    fun fitWidth() = manual { current.manualZoom = false; current.fittedViewWidth = 0; fitContentWidth(current) }
    private fun ensureSize(view: WebView) {
        if (view.width > 0 && view.height > 0) return
        val size = context.resources.displayMetrics
        val width = size.widthPixels.coerceAtLeast(320); val height = (size.heightPixels - (220 * size.density).toInt()).coerceAtLeast(320)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    fun attachBackgroundHost(host: ViewGroup) {
        uiThread(); backgroundHost = host
        applyTabLifecycle()
    }
    fun detachBackgroundHost(host: ViewGroup) {
        uiThread(); if (backgroundHost !== host) return
        backgroundHost = null
        tabs.forEach { tab -> tab.rendered?.let { if (it.parent === host) host.removeView(it) } }
    }
    fun park(view: WebView, expectedParent: ViewGroup? = null) {
        uiThread()
        // An old Activity/Compose holder may dispose AFTER the overlay reparented this view.
        if (expectedParent != null && view.parent !== expectedParent) return
        view.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (view.parent as? ViewGroup)?.removeView(view)
        if (!stopped.get() && current.rendered === view && !current.gone && (state.value.visible || state.value.backgroundEnabled)) {
            backgroundHost?.addView(view, ViewGroup.LayoutParams(-1, -1)); ensureSize(view)
        }
    }
    private fun publishTabs() {
        if (tabs.none { it.id == selected }) return
        val tab = current; rendererGone = tab.gone
        update { copy(url = tab.url, title = tab.title, loading = tab.loading, loadProgress = tab.progress, error = tab.error,
            rendererFailed = tab.gone, canGoBack = !tab.gone && tab.rendered?.canGoBack() == true, canGoForward = !tab.gone && tab.rendered?.canGoForward() == true,
            activeTab = tab.id, tabs = this@AgentBrowserController.tabs.map { BrowserTabInfo(it.id, it.url, it.title, it.loading) },
            zoomPercent = tab.zoom, findQuery = tab.query, findIndex = tab.match, findCount = tab.matches, pageMode = tab.mode.name) }
    }
    private fun saveTabs() { if (tabs.isNotEmpty()) library.saveTabs(tabs.map { SavedBrowserTab(it.id, it.url, it.title, it.mode.name) }, selected) }
    private fun restoreTab() {
        val tab = current
        if (tab.needsRestore && !tab.gone) { tab.needsRestore = false; if (allowed(tab.url)) tab.view.loadUrl(tab.url) }
    }
    fun newTab(url: String? = null): String? {
        uiThread(); if (stopped.get()) return null
        return try {
            if (!url.isNullOrBlank() && !allowed(normalize(url))) throw RpcFailure("navigation_blocked")
            val tab = createTab(); selectTab(tab.id); if (!url.isNullOrBlank()) load(normalize(url)); saveTabs(); tab.id
        } catch (_: RpcFailure) { update { copy(notice = if (tabs.size >= 12) "最多打开 12 个页面，请先关闭不需要的页面" else "无法打开此地址") }; null }
    }
    fun selectTab(id: String) {
        uiThread(); if (stopped.get() || tabs.none { it.id == id }) return
        if (selected != id) { permit.set(null); invalidateDocument(); selected = id }
        applyTabLifecycle(); publishTabs(); restoreTab(); saveTabs()
    }
    fun closeTab(id: String) {
        uiThread(); if (stopped.get()) return
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (selected == id) { permit.set(null); invalidateDocument() }
        tab.rendered?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            if (!tab.gone) { view.stopLoading(); view.destroy() }
        }
        tab.rendered = null
        tabs.remove(tab)
        if (tabs.isEmpty()) createTab()
        if (selected == id) selected = tabs.last().id
        applyTabLifecycle(); publishTabs(); restoreTab(); saveTabs()
    }
    fun setVisible(visible: Boolean) {
        uiThread(); if (stopped.get()) return
        activityVisible = visible; refreshVisibility()
    }
    internal fun setOverlayFrame(frame: BrowserOverlayFrame?, collapsed: Boolean = false) {
        uiThread(); if (stopped.get()) return
        update { copy(overlayFrame = frame, overlayCollapsed = frame != null && collapsed) }
        refreshVisibility()
    }
    private fun refreshVisibility() {
        val visible = activityVisible || state.value.overlayFrame != null
        gate.visibility(visible)
        if (state.value.visible != visible) update { copy(visible = visible) }
        applyTabLifecycle()
        if (visible) restoreTab()
    }
    /** Keep one active document warm for background RPC, not every hidden tab drawing. */
    private fun applyTabLifecycle() {
        if (stopped.get()) return
        val warm = state.value.visible || state.value.backgroundEnabled
        tabs.forEach { tab ->
            if (tab.gone) return@forEach
            if (warm && tab.id == selected) {
                val view = tab.view
                if (view.parent == null) backgroundHost?.addView(view, ViewGroup.LayoutParams(-1, -1))
                ensureSize(view)
                if (tab.resumed != true) { view.onResume(); tab.resumed = true }
            } else tab.rendered?.let { view ->
                if (tab.resumed != false) { view.onPause(); tab.resumed = false }
                if (view.parent === backgroundHost) backgroundHost?.removeView(view)
            }
        }
    }
    fun setBackgroundEnabled(enabled: Boolean) {
        uiThread(); if (stopped.get()) return
        update { copy(backgroundEnabled = enabled) }
        if (!enabled) revoke()
        applyTabLifecycle()
        if (enabled) restoreTab()
    }
    internal fun liveTabCount(): Int = tabs.count { it.rendered != null && !it.gone }
    internal fun resumedTabCount(): Int = tabs.count { it.rendered != null && !it.gone && it.resumed == true }
    /** Current user-selected default, applied once; an explicit revoke is sticky for this process. */
    fun activateDefaultControl() {
        uiThread(); if (defaultPolicyApplied || stopped.get()) return
        defaultPolicyApplied = true
        if (library.state.value.aiControlEnabled && !rendererGone && gate.grantProcessConnection(connection.clientId)) granted(connection.clientId)
    }
    private fun requestTakeover() {
        uiThread(); val client = state.value.authorizedClient ?: return
        gate.revoke(); permit.set(null); invalidateDocument(); requestGeneration++
        takeoverClient = client
        update { copy(authorizedClient = null, pendingClient = null, takeoverRequested = true, control = "AI 已暂停，等待接管选择") }
    }
    fun resolveTakeover(takeBack: Boolean) {
        uiThread(); val client = takeoverClient ?: return
        takeoverClient = null
        update { copy(takeoverRequested = false) }
        if (takeBack) takeControl()
        else if (!rendererGone && gate.grantProcessConnection(client)) granted(client)
    }
    fun grant(clientId: String) {
        uiThread(); if (rendererGone || !gate.grant(clientId)) return
        granted(clientId)
    }
    fun grantConnection() {
        uiThread(); if (rendererGone || !gate.grantFromNativeConfirmation(connection.clientId)) return
        library.setAiControlEnabled(true)
        granted(connection.clientId)
    }
    fun takeControl() { uiThread(); revoke(); library.setAiControlEnabled(false) }
    private fun granted(clientId: String) {
        requestGeneration++
        update { copy(authorizedClient = clientId, pendingClient = null, takeoverRequested = false, control = "AI 可操作浏览器") }; log("当前连接获得浏览器权限")
        onBackgroundNeeded()
    }
    fun revoke() {
        uiThread(); gate.revoke(); requestGeneration++; permit.set(null); invalidateDocument(); takeoverClient = null
        update { copy(authorizedClient = null, pendingClient = null, takeoverRequested = false, control = "你在操作浏览器") }; log("用户收回浏览器权限")
    }
    private fun normalize(url: String): String {
        val value = url.trim()
        return if (value.contains("://") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) value else "https://$value"
    }
    fun navigate(url: String) = manual { load(normalize(url)) }
    fun navigateInput(input: String) = manual { load(browserAddress(input, BrowserSearchEngine.from(library.state.value.searchEngine))) }
    fun pageMode(mode: BrowserPageMode) = manual {
        current.mode = mode; webView.settings.userAgentString = mode.userAgent(WebSettings.getDefaultUserAgent(context))
        current.baseScale = 0f; current.zoom = 100; saveTabs()
        if (current.url.isNotBlank() && allowed(current.url)) { invalidateDocument(); webView.reload() }
    }
    fun saveState(bundle: android.os.Bundle) { uiThread(); if (!stopped.get() && !rendererGone) webView.saveState(bundle); saveTabs() }
    fun restoreState(bundle: android.os.Bundle) {
        uiThread(); if (stopped.get() || rendererGone || current.url.isNotEmpty()) return
        webView.restoreState(bundle)
        if (webView.url?.let(::allowed) == true) { current.url = webView.url.orEmpty(); current.title = webView.title.orEmpty(); publishTabs() }
        else webView.stopLoading()
    }
    fun clearHistory() { uiThread(); tabs.filterNot { it.gone }.forEach { it.rendered?.clearHistory() }; library.clearHistory(); update { copy(history = emptyList()) }; refresh() }
    fun back() = manual { if (webView.canGoBack()) { invalidateDocument(); webView.goBack() } }
    fun forward() = manual { if (webView.canGoForward()) { invalidateDocument(); webView.goForward() } }
    fun reload() = manual { if (allowed(current.url)) { invalidateDocument(); if (webView.url == null) load(current.url) else webView.reload() } }
    fun find(query: String) = manual { current.query = query.take(200); current.match = 0; current.matches = 0; webView.findAllAsync(current.query) }
    fun findNext(forward: Boolean) = manual { webView.findNext(forward) }
    fun clearFind() = manual { current.query = ""; current.match = 0; current.matches = 0; webView.clearMatches() }
    fun zoom(percent: Int) = manual {
        val target = percent.coerceIn(50, 300)
        current.manualZoom = true
        @Suppress("DEPRECATION") val actualScale = webView.scale
        if (current.baseScale <= 0f) current.baseScale = actualScale
        val oldScrollX = webView.scrollX; val oldScrollY = webView.scrollY
        webView.scrollTo(0, oldScrollY)
        if (actualScale > 0f) webView.zoomBy(current.baseScale * target / 100f / actualScale)
        webView.scrollTo(oldScrollX, oldScrollY)
    }
    fun notify(message: String) { uiThread(); update { copy(notice = message) } }
    fun thumbnail(id: String): Bitmap? {
        uiThread()
        val tab = tabs.firstOrNull { it.id == id && !it.gone && it.url.isNotEmpty() } ?: return null
        tab.preview?.let { return it }
        val view = tab.rendered ?: return null
        return runCatching {
            ensureSize(view)
            val image = Bitmap.createBitmap(240, 144, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image); canvas.scale(240f / view.width, 240f / view.width); view.draw(canvas)
            tab.preview = image; image
        }.getOrNull()
    }
    fun dismissNotice() { uiThread(); update { copy(notice = null) } }
    fun dismissDownload() { uiThread(); update { copy(downloadRequest = null) } }
    fun acceptDownload(request: BrowserDownloadRequest? = state.value.downloadRequest) {
        uiThread(); if (request == null) return
        dismissDownload()
        try {
            val cookies = WebViewCompat.getProfile(webView).cookieManager
            library.download(request, ::allowed) { cookies.getCookie(it) }; onBackgroundNeeded()
            update { copy(notice = "下载已开始，可在「下载与截图」中查看进度") }
        } catch (_: Exception) { update { copy(notice = "无法开始下载，请检查大小、下载记录数量和地址（单文件最多 256 MiB）") } }
    }
    fun capturePage() = manual {
        val image = screenshot()
        library.saveScreenshot(android.util.Base64.decode(image.getString("base64"), android.util.Base64.DEFAULT))
        update { copy(notice = "完整网页截图已保存到「下载与截图」；不受小窗范围影响") }
    }
    fun close() {
        uiThread(); if (!stopped.compareAndSet(false, true)) return
        gate.close(); requestGeneration++; permit.set(null); main.removeCallbacksAndMessages(null); invalidateDocument(); server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
        workers.shutdownNow(); deadline.shutdownNow(); saveTabs()
        tabs.forEach { tab -> tab.rendered?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            if (!tab.gone) { view.stopLoading(); view.destroy() }
        }; tab.rendered = null }
        backgroundHost = null; library.close()
        update { copy(closed = true, visible = false, authorizedClient = null, pendingClient = null, backgroundEnabled = false, overlayFrame = null, overlayCollapsed = false, control = "浏览器已关闭") }
    }
    private fun manual(action: () -> Unit) {
        uiThread(); if (stopped.get() || rendererGone) return
        try { action(); refresh() } catch (_: Exception) { failPage("操作未完成，请检查网页或重新打开") }
    }
    private fun uiThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Browser UI requires main thread" } }
    private fun update(block: BrowserState.() -> BrowserState) { mutableState.value = mutableState.value.block() }
    private fun log(message: String) { update { copy(operations = (operations + message).takeLast(20)) } }
    private fun failPage(message: String) { if (tabs.isNotEmpty()) { current.error = message; current.loading = false; publishTabs() } }
    private fun invalidateDocument() { permit.set(null); documentId = UUID.randomUUID().toString(); nodeKey = "__hp_${UUID.randomUUID().toString().replace("-", "")}" }
    private fun refresh() { if (!stopped.get()) publishTabs() }
    private fun origin(url: String): String? = runCatching {
        val uri = URI(url); val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if (uri.rawUserInfo != null || uri.port !in -1..65535) return null
        "$scheme://$host:${if (uri.port == -1) if (scheme == "https") 443 else 80 else uri.port}"
    }.getOrNull()
    private fun allowed(url: String): Boolean = runCatching {
        if (url.length > 8192) return false
        val candidate = origin(url) ?: return false
        val port = URI(url).port.let { if (it == -1) if (URI(url).scheme.equals("https", true)) 443 else 80 else it }
        if (port == connection.port) return false
        privateOrigins().none { origin(it) == candidate || runCatching { URI(it).port == port }.getOrDefault(false) }
    }.getOrDefault(false)
    private fun blocked(url: String): Boolean { if (allowed(url)) return false; failPage("已阻止此地址"); return true }
    private fun deniedResource() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun load(url: String) {
        if (!allowed(url)) throw RpcFailure("navigation_blocked")
        current.needsRestore = false; current.url = url; current.loading = true; current.error = null
        invalidateDocument(); webView.loadUrl(url); publishTabs(); saveTabs()
    }

    private data class Reply(val body: JSONObject, val epoch: Long, val ticket: BrowserPermissionGate.Ticket? = null)
    private fun error(code: String) = JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", code))
    private fun ok(result: Any) = JSONObject().put("ok", true).put("result", result)
    private fun serve(socket: Socket) {
        var http = 200
        var reply: Reply? = null
        val body = try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            var headerBytes = 0
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val b = input.read(); if (b == -1 || ++headerBytes > 8192) throw RpcFailure("invalid_http")
                    if (b == 10) break
                    bytes.write(b)
                }
                val text = bytes.toString("US-ASCII")
                if (!text.endsWith("\r")) throw RpcFailure("invalid_http")
                return text.dropLast(1)
            }
            val requestLine = line()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val row = line(); if (row.isEmpty()) break
                val colon = row.indexOf(':'); if (colon <= 0) throw RpcFailure("invalid_http")
                val key = row.substring(0, colon).lowercase()
                if (!key.matches(Regex("[a-z0-9-]+")) || headers.put(key, row.substring(colon + 1).trim()) != null) throw RpcFailure("invalid_http")
            }
            if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw RpcFailure("invalid_http")
            val length = headers["content-length"]?.toIntOrNull() ?: throw RpcFailure("invalid_http")
            if (length !in 1..65536) { http = 413; throw RpcFailure("body_too_large") }
            val data = ByteArray(length)
            var read = 0
            while (read < length) { val n = input.read(data, read, length - read); if (n < 0) throw RpcFailure("invalid_http"); read += n }
            // Drain the bounded body before rejecting, so closing the socket does not reset a JSON error reply.
            if (headers.containsKey("origin")) { http = 403; throw RpcFailure("origin_forbidden") }
            val auth = headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(auth, "Bearer ${connection.token}".toByteArray(Charsets.UTF_8))) {
                http = 401; throw RpcFailure("unauthorized")
            }
            if (requestLine != "POST /rpc HTTP/1.1" && requestLine != "POST /rpc HTTP/1.0") {
                http = 404; throw RpcFailure("endpoint_not_found")
            }
            val payload = String(data, Charsets.UTF_8)
            var depth = 0; var quoted = false; var escaped = false
            for (char in payload) {
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { if (++depth > 32) throw RpcFailure("invalid_request") }
                    '}', ']' -> depth--
                }
            }
            val tokener = JSONTokener(payload)
            val json = tokener.nextValue() as? JSONObject ?: throw RpcFailure("invalid_request")
            if (tokener.nextClean() != '\u0000') throw RpcFailure("invalid_request")
            reply = dispatch(json)
            reply!!.body
        } catch (e: RpcFailure) { error(e.code) }
          catch (_: Exception) { error("request_failed") }
        try {
            val candidate = reply
            val valid = candidate == null || (gate.current(candidate.epoch) && (candidate.ticket == null || gate.valid(candidate.ticket)))
            val lease = if (valid) candidate else null
            val result = if (valid) body else error("permission_revoked")
            val bytes = result.toString().toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 $http ${if (http == 200) "OK" else "Rejected"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            var offset = 0
            while (offset < bytes.size) {
                if (lease != null && (!gate.current(lease.epoch) || (lease.ticket != null && !gate.valid(lease.ticket)))) break
                val count = minOf(8192, bytes.size - offset); output.write(bytes, offset, count); offset += count
            }
        } catch (_: Exception) { /* Closed/revoked connections carry no diagnostic secrets. */ }
    }

    private fun dispatch(request: JSONObject): Reply {
        val client = request.opt("clientId") as? String ?: throw RpcFailure("invalid_client")
        if (!client.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) throw RpcFailure("invalid_client")
        val normalized = UUID.fromString(client).toString()
        val method = request.opt("method") as? String ?: throw RpcFailure("invalid_method")
        val params = if (request.has("params")) request.opt("params") as? JSONObject ?: throw RpcFailure("invalid_params") else JSONObject()
        if (method !in setOf("status", "request_control", "navigate", "snapshot", "click", "type", "scroll", "screenshot", "back", "forward", "reload", "tabs", "new_tab", "select_tab", "close_tab", "find", "zoom")) throw RpcFailure("unknown_method")
        val epoch = gate.epoch()
        val ticket = if (method == "status" || method == "request_control") null else gate.ticket(normalized)
        if (ticket != null && !operationBusy.compareAndSet(false, true)) throw RpcFailure("operation_busy")
        val result = CompletableFuture<JSONObject>()
        val active = AtomicBoolean(true)
        fun valid() = active.get() && gate.current(epoch) && (ticket == null || gate.valid(ticket))
        val task = Runnable {
            if (!valid()) { result.complete(error("permission_revoked")); return@Runnable }
            try {
                if (method == "status") {
                    // Auth-only status deliberately excludes URL, title, document and other clients' IDs.
                    val s = state.value
                    result.complete(ok(JSONObject().put("visible", s.visible).put("closed", s.closed)
                        .put("authorized", s.authorizedClient == normalized).put("pending", s.pendingClient == normalized).put("background", s.backgroundEnabled)))
                } else if (method == "request_control") {
                    if (rendererGone) throw RpcFailure("browser_unavailable")
                    if (state.value.authorizedClient == normalized) {
                        result.complete(ok(JSONObject().put("pending", false).put("authorized", true)))
                        return@Runnable
                    }
                    if (state.value.takeoverRequested) throw RpcFailure("permission_required")
                    gate.request(normalized)
                    if (state.value.pendingClient == null) {
                        val requestId = ++requestGeneration
                        main.postDelayed({ if (requestGeneration == requestId && state.value.pendingClient == normalized) revoke() }, 120000)
                    }
                    update { copy(pendingClient = normalized, control = "Agent 请求控制") }; log("连接请求授权")
                    result.complete(ok(JSONObject().put("pending", true)))
                } else {
                    if (rendererGone) throw RpcFailure("browser_unavailable")
                    log("代理操作：$method")
                    execute(method, params, ticket!!) { value ->
                        result.complete(when {
                            !valid() -> error("permission_revoked")
                            value is JSONObject && value.has("error") -> error(value.getString("error"))
                            else -> ok(value)
                        })
                    }
                }
            } catch (e: RpcFailure) { result.complete(error(e.code)) }
              catch (_: Exception) { result.complete(error("operation_failed")) }
        }
        main.post(task)
        return try { Reply(result.get(5, TimeUnit.SECONDS), epoch, ticket) }
        catch (_: Exception) { throw RpcFailure("operation_timeout") }
        finally {
            active.set(false); main.removeCallbacks(task)
            if (ticket != null) { permit.set(null); operationBusy.set(false) }
        }
    }

    private fun execute(method: String, params: JSONObject, ticket: BrowserPermissionGate.Ticket, done: (Any) -> Unit) {
        fun text(name: String, limit: Int): String {
            val value = params.opt(name) as? String ?: throw RpcFailure("invalid_params")
            if (value.toByteArray(Charsets.UTF_8).size > limit) throw RpcFailure("invalid_params")
            return value
        }
        val result = JSONObject().put("accepted", true)
        if (params.has("tabId") && method !in setOf("select_tab", "close_tab", "new_tab")) {
            val id = text("tabId", 64)
            if (tabs.none { it.id == id }) throw RpcFailure("tab_not_found")
            selectTab(id)
        }
        when (method) {
            "tabs" -> done(JSONObject().put("activeTab", selected).put("tabs", org.json.JSONArray(tabs.map { JSONObject().put("id", it.id).put("url", it.url).put("title", it.title) })))
            "new_tab" -> {
                val url = if (params.has("url")) text("url", 8192) else null
                if (url != null && !allowed(url)) throw RpcFailure("navigation_blocked")
                val id = newTab(url) ?: throw RpcFailure("tab_limit"); done(result.put("tabId", id))
            }
            "select_tab", "close_tab" -> {
                val id = text("tabId", 64); if (tabs.none { it.id == id }) throw RpcFailure("tab_not_found")
                if (method == "select_tab") selectTab(id) else closeTab(id); done(result.put("tabId", selected))
            }
            "find" -> { find(text("query", 200)); done(result) }
            "zoom" -> {
                if (params.optBoolean("fit", false)) fitWidth()
                else {
                    val percent = params.opt("percent") as? Number ?: throw RpcFailure("invalid_params")
                    if (!percent.toDouble().isFinite() || percent.toDouble() !in 50.0..300.0) throw RpcFailure("invalid_params")
                    zoom(percent.toInt())
                }
                done(result.put("zoomPercent", current.zoom).put("fitWidth", !current.manualZoom))
            }
            "navigate" -> { load(text("url", 8192)); done(result) }
            "back" -> { if (webView.canGoBack()) webView.goBack(); done(result) }
            "forward" -> { if (webView.canGoForward()) webView.goForward(); done(result) }
            "reload" -> { if (!allowed(webView.url.orEmpty())) throw RpcFailure("navigation_blocked"); webView.reload(); done(result) }
            "screenshot" -> done(screenshot())
            "scroll" -> {
                val dx = params.opt("dx") as? Number ?: throw RpcFailure("invalid_params")
                val dy = params.opt("dy") as? Number ?: throw RpcFailure("invalid_params")
                if (!dx.toDouble().isFinite() || !dy.toDouble().isFinite() || kotlin.math.abs(dx.toDouble()) > 10000 || kotlin.math.abs(dy.toDouble()) > 10000) throw RpcFailure("invalid_params")
                webView.scrollBy(dx.toInt(), dy.toInt()); done(result)
            }
            "snapshot" -> evaluate(snapshotScript(params.optInt("offset", 0).coerceIn(0, 1_000_000),
                params.optInt("elementOffset", 0).coerceIn(0, 50_000)), ticket, done)
            "click", "type" -> {
                if (text("documentId", 64) != documentId) throw RpcFailure("stale_document")
                val id = text("elementId", 80)
                val value = if (method == "type") text("text", 16384) else ""
                val action = if (method == "click") "e.click();" else """
                    if (!(e instanceof HTMLInputElement || e instanceof HTMLTextAreaElement) || e.readOnly ||
                        (e instanceof HTMLInputElement && !['text','search','email','url','tel','password','number'].includes(e.type))) return {error:'not_editable'};
                    const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                    Object.getOwnPropertyDescriptor(proto,'value').set.call(e,${JSONObject.quote(value)});
                    e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
                """.trimIndent()
                evaluate("""(function(){const s=window[${JSONObject.quote(nodeKey)}];
                    const e=s && s.nodes[${JSONObject.quote(id)}];
                    if(!e || !e.isConnected || e.ownerDocument!==document) return {error:'stale_element'};
                    if(e.disabled || e.type==='file') return {error:'element_unavailable'};
                    e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});
                    const r=e.getBoundingClientRect();
                    if(r.width<=0 || r.height<=0 || r.bottom<=0 || r.right<=0 || r.top>=innerHeight || r.left>=innerWidth) return {error:'element_unavailable'};
                    $action return {accepted:true};})()""", ticket, done)
            }
        }
        refresh()
    }
    private fun evaluate(script: String, ticket: BrowserPermissionGate.Ticket, done: (Any) -> Unit) {
        val document = documentId
        val nonce = UUID.randomUUID().toString()
        permit.set(ActionPermit(nonce, ticket, document, android.os.SystemClock.elapsedRealtime() + 4500))
        val guarded = "(function(){if(!window[${JSONObject.quote(gateName)}].consume(${JSONObject.quote(nonce)}))return {error:'permission_revoked'};return $script;})()"
        webView.evaluateJavascript(guarded) { raw ->
            if (document != documentId) { done(JSONObject().put("error", "stale_document")); return@evaluateJavascript }
            try {
                if (raw.length > 160000) throw RpcFailure("result_too_large")
                done(JSONObject(raw))
            } catch (_: Exception) { done(JSONObject().put("error", "page_unavailable")) }
        }
    }
    private fun snapshotScript(offset: Int, elementOffset: Int): String = """(function(){
        const from=$offset,elementFrom=$elementOffset,nodes=Object.create(null),elements=[];let text='',visits=0,position=0,elementCount=0;
        // No viewport intersection test: off-screen content remains part of the AI document.
        const readable=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.visibility!=='collapse'&&s.display!=='none'&&s.opacity!=='0'&&!e.closest('[hidden],[aria-hidden="true"]')};
        const walk=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);
        while(walk.nextNode()&&++visits<=50000){const n=walk.currentNode;
            if(n.nodeType===3){const p=n.parentElement;if(!p||p.closest('script,style,noscript,input,textarea,select,[contenteditable]')||!readable(p))continue;
                const raw=(n.textContent||'')+' ',start=position,end=start+raw.length;position=end;
                if(end>from&&text.length<20000){const local=Math.max(0,from-start);text+=raw.slice(local,local+20000-text.length);}continue;}
            if(!readable(n)||!n.matches('a[href],button,input,textarea,select,[role="button"],[role="link"]')||n.type==='file'||n.type==='hidden')continue;
            if(elementCount++<elementFrom||elements.length>=100)continue;
            const r=n.getBoundingClientRect(),id=${JSONObject.quote(UUID.randomUUID().toString())}+'-'+elements.length;nodes[id]=n;
            elements.push({elementId:id,tag:n.tagName.toLowerCase(),type:n.type||'',label:(n.getAttribute('aria-label')||n.getAttribute('placeholder')||(n.matches('input,textarea,select')?'':n.innerText)||'').slice(0,200),disabled:!!n.disabled,
                bounds:{x:Math.round(r.left+scrollX),y:Math.round(r.top+scrollY),width:Math.round(r.width),height:Math.round(r.height)},inViewport:r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth});
        }
        window[${JSONObject.quote(nodeKey)}]={nodes:nodes};
        const root=document.documentElement,body=document.body;
        return {tabId:${JSONObject.quote(selected)},documentId:${JSONObject.quote(documentId)},scope:'full_document',text:text.slice(0,20000),elements:elements,
            textOffset:from,nextTextOffset:position>from+text.length?from+text.length:null,
            elementOffset:elementFrom,nextElementOffset:elementCount>elementFrom+elements.length?elementFrom+elements.length:null,
            scanTruncated:visits>50000,truncated:position>from+text.length||elementCount>elementFrom+elements.length||visits>50000,
            viewport:{width:innerWidth,height:innerHeight,scrollX:scrollX,scrollY:scrollY},
            document:{width:Math.max(root.scrollWidth,body?.scrollWidth||0),height:Math.max(root.scrollHeight,body?.scrollHeight||0)},zoomPercent:${current.zoom}};})()""".trimIndent()
    private fun screenshot(): JSONObject {
        @Suppress("DEPRECATION") val picture = webView.capturePicture()
        val width = picture.width; val height = picture.height
        if (width <= 0 || height <= 0) throw RpcFailure("page_unavailable")
        // capturePicture holds the full laid-out document, unlike WebView.draw(Canvas),
        // which is clipped to the human viewport. Never resize or reload the live WebView.
        val scale = minOf(1.0, 2048.0 / max(width, height))
        val bitmap = Bitmap.createBitmap(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.scale(scale.toFloat(), scale.toFloat()); picture.draw(canvas)
            val bytes = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw RpcFailure("capture_failed")
            if ((bytes.size().toLong() + 2) / 3 * 4 > 2 * 1024 * 1024) throw RpcFailure("screenshot_too_large")
            return JSONObject().put("mimeType", "image/png").put("width", bitmap.width).put("height", bitmap.height)
                .put("scope", "full_document").put("sourceWidth", width).put("sourceHeight", height)
                .put("viewportWidth", webView.width).put("viewportHeight", webView.height).put("zoomPercent", current.zoom)
                .put("base64", android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP))
        } finally { bitmap.recycle() }
    }
}
