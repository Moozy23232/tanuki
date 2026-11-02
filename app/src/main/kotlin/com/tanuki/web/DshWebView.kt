package com.tanuki.web

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.tanuki.runtime.RuntimeManager
import com.tanuki.runtime.WebState
import java.io.ByteArrayInputStream

class DshWebView(private val activity: ComponentActivity, private val manager: RuntimeManager,
                 private val chooser: (Intent, ValueCallback<Array<Uri>>) -> Unit,
                 private val download: (String, String) -> Unit,
                 private val blobDownload: (java.io.File, String) -> Unit,
                 private val openPage: (String) -> Unit = {}) {
    private var attachedOrigin: String? = null
    private var attachedAuthUrl: String? = null
    private var retriedAuthUrl: String? = null
    private var authRetryPending = false
    private var mainFrameAuthFailure = false
    private lateinit var authObserver: Job
    private val compatibility = LegacyWebCompatibility(activity)
    private val compactViewport = DshViewport { url -> OriginPolicy.sameOrigin(url, manager.origin) }
    private var destroyed = false
    val needsRecreation:Boolean get()=destroyed
    val support=kotlinx.coroutines.flow.MutableStateFlow(WebViewSupport())
    private var probeId=0
    private var probeUrl:String?=null
    private var connectAfterProbe=false
    private var clearProbeHistory=false
    private var pendingRestore:Bundle?=null
    private lateinit var blobs: BlobDownloads
    private val popups = mutableSetOf<WebView>()
    @SuppressLint("SetJavaScriptEnabled")
    val view = WebView(activity).apply {
        // WebView treats a WRAP_CONTENT height as an unbounded HTML viewport. Compose's
        // holder can still give it visible bounds while html/body percentage heights stay 0.
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        // No JavaScript/native command interface. Project previews open outside this credential store.
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        compactViewport.install(this)
        // The official page follows prefers-color-scheme; do not apply a second forced-dark pass.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }
        val background = android.util.TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.colorBackground, background, true)) setBackgroundColor(background.data)
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Only this origin-isolated DSH document can request a new tab. Popup holders
        // never load external content using DSH's cookies; they forward a URL and die.
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        WebView.setWebContentsDebuggingEnabled(com.tanuki.BuildConfig.DEBUG)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (OriginPolicy.sameOrigin(url, manager.origin)) return false
                if (request.isForMainFrame && request.hasGesture() && OriginPolicy.sameOrigin(view.url.orEmpty(), manager.origin)) routePage(url)
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (OriginPolicy.sameOrigin(url, manager.origin)) return null
                if (!request.isForMainFrame && url.startsWith("blob:") && OriginPolicy.sameOrigin(url.removePrefix("blob:"), manager.origin)) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked origin", emptyMap(), ByteArrayInputStream("来源不受信任".toByteArray()))
            }
            override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) {
                authTrace("pageStarted bootstrap=${url==attachedAuthUrl}")
                if(!destroyed && url!=probeUrl && OriginPolicy.sameOrigin(url,manager.origin)) {
                    // A bootstrap 302 can report only the final URL, with the token removed.
                    authRetryPending=false
                    manager.web(WebState.CONNECTING)
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                authTrace("pageFinished bootstrap=${url==attachedAuthUrl}")
                if(destroyed || authRetryPending)return
                if(url==probeUrl && support.value.state==WebViewSupportState.CHECKING){finishProbe();return}
                if(mainFrameAuthFailure) {
                    mainFrameAuthFailure=false
                    // Chromium can deliver HTTP errors before pageStarted. Let the failed
                    // navigation finish before replacing it with the one allowed retry.
                    if(retryAuthentication())return
                }
                // HTTP page completion is not evidence of an authenticated WebSocket.
                if (support.value.state==WebViewSupportState.SUPPORTED && OriginPolicy.sameOrigin(url, manager.origin)) {
                    compactViewport.apply(view)
                    if(clearProbeHistory){view.clearHistory();clearProbeHistory=false}
                    manager.web(WebState.AUTHENTICATING)
                    manager.verifyBrowserCookie(CookieManager.getInstance().getCookie(url))
                }
                // flush() blocks on persistent I/O; never stall the DSH renderer/UI thread.
                activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { CookieManager.getInstance().flush() }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if(request.isForMainFrame)authTrace("mainFrameHttp=${error.statusCode}")
                if(!destroyed && request.isForMainFrame && support.value.state==WebViewSupportState.SUPPORTED && OriginPolicy.sameOrigin(request.url.toString(),manager.origin) && error.statusCode in setOf(401,403)) {
                    mainFrameAuthFailure=true
                    manager.web(WebState.AUTHENTICATING)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { manager.web(WebState.DISCONNECTED); manager.event("网页连接失败：${error.description}") }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                destroyed=true;++probeId;connectAfterProbe=false
                support.value=support.value.copy(state=WebViewSupportState.FAILED,error="网页渲染进程已退出，请重新连接")
                manager.web(WebState.RENDER_ERROR); manager.event("WebView 渲染进程已退出，请返回应用重新连接；DSH 进程状态单独显示")
                authObserver.cancel()
                compatibility.dispose(); blobs.dispose()
                (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(opener: WebView, isDialog: Boolean, isUserGesture: Boolean, message: android.os.Message): Boolean {
                if (destroyed || !OriginPolicy.sameOrigin(opener.url.orEmpty(), manager.origin) || popups.size >= 4) return false
                val transport = message.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(activity)
                popups += popup
                var handled = false
                fun dispose() { if (popups.remove(popup)) popup.destroy() }
                fun accept(url: String) {
                    if (handled || url == "about:blank") return
                    handled = true
                    routePage(url)
                    popup.post { dispose() }
                }
                popup.settings.apply { javaScriptEnabled = false; allowFileAccess = false; allowContentAccess = false }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.isForMainFrame) accept(request.url.toString())
                        return true
                    }
                    @Deprecated("Compatibility callback")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean { accept(url); return true }
                    override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) { accept(url) }
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        WebResourceResponse("text/plain", "utf-8", 403, "Forwarded to browser", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                transport.webView = popup; message.sendToTarget()
                popup.postDelayed({ dispose() }, 10_000)
                return true
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                chooser(intent, callback); return true
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        setDownloadListener { url, _, disposition, mime, _ ->
            if (url.startsWith("blob:")) blobs.request(url, URLUtil.guessFileName(url, disposition, mime))
            else if (OriginPolicy.sameOrigin(url, manager.origin)) download(url, URLUtil.guessFileName(url, disposition, mime))
            else Toast.makeText(activity, "此下载来源尚不支持；请从项目导出入口导出文件", Toast.LENGTH_LONG).show()
        }
    }
    init {
        blobs = BlobDownloads(activity, view, blobDownload, manager.fileTransfers) { message -> manager.event(message); Toast.makeText(activity,message,Toast.LENGTH_LONG).show() }
        authObserver = activity.lifecycleScope.launch {
            manager.state.map { it.web }.distinctUntilChanged().collect { state ->
                if(state==WebState.AUTH_EXPIRED)retryAuthentication()
            }
        }
    }
    private fun authTrace(event:String) {
        if(com.tanuki.BuildConfig.DEBUG)android.util.Log.d("TANUKI_AUTH",
            "$event pending=$authRetryPending retryUsed=${retriedAuthUrl!=null && retriedAuthUrl==manager.authenticatedUrl} web=${manager.state.value.web}")
    }
    /** One retry per in-memory launch URL; a rejected token must never produce a reload loop. */
    private fun retryAuthentication(): Boolean {
        authTrace("retryRequested")
        if(destroyed || support.value.state!=WebViewSupportState.SUPPORTED ||
            manager.state.value.service!=com.tanuki.runtime.ServiceState.RUNNING)return false
        val url=manager.authenticatedUrl ?: return false
        val origin=manager.origin ?: return false
        if(!OriginPolicy.sameOrigin(url,origin) || retriedAuthUrl==url)return false
        retriedAuthUrl=url;attachedAuthUrl=url;authRetryPending=true
        manager.web(WebState.AUTHENTICATING)
        view.post {
            if(!destroyed && support.value.state==WebViewSupportState.SUPPORTED &&
                manager.state.value.service==com.tanuki.runtime.ServiceState.RUNNING &&
                manager.origin==origin && manager.authenticatedUrl==url) {
                authTrace("retryNavigation samePage=${view.url==url}")
                view.loadUrl(url)
            } else authRetryPending=false
        }
        return true
    }
    fun checkSupport(connectAfter:Boolean=false) {
        connectAfterProbe=connectAfterProbe || connectAfter
        if(destroyed || support.value.state==WebViewSupportState.CHECKING)return
        val info=WebView.getCurrentWebViewPackage()
        support.value=WebViewSupport(WebViewSupportState.CHECKING,info?.packageName ?: "未知",info?.versionName ?: "未知")
        val id=++probeId
        attachedOrigin=null;attachedAuthUrl=null;retriedAuthUrl=null;authRetryPending=false;mainFrameAuthFailure=false
        probeUrl="https://compat.tanuki.invalid/check-$id"
        manager.web(WebState.DISCONNECTED)
        view.loadDataWithBaseURL(probeUrl,"<!doctype html><meta charset=utf-8><title>WebView 能力检测</title>","text/html","UTF-8",null)
        view.postDelayed({if(!destroyed && probeId==id && support.value.state==WebViewSupportState.CHECKING){support.value=support.value.copy(state=WebViewSupportState.FAILED,error="渲染能力检测超时");manager.web(WebState.INCOMPATIBLE)}},10000)
    }
    private fun finishProbe() {
        val id=probeId
        view.evaluateJavascript(WebViewSupport.PROBE_SCRIPT) { json ->
            if(destroyed || id!=probeId || support.value.state!=WebViewSupportState.CHECKING)return@evaluateJavascript
            val before=support.value
            support.value=runCatching { WebViewSupport.measured(before.provider,before.version,json) }.getOrElse { before.copy(state=WebViewSupportState.FAILED,error="无法读取 CSS 能力") }
            if(support.value.state==WebViewSupportState.SUPPORTED) {
                clearProbeHistory=true
                if(connectAfterProbe){connectAfterProbe=false;connect()}
            }else{connectAfterProbe=false;manager.web(WebState.INCOMPATIBLE);manager.event(support.value.description)}
        }
    }
    fun connect(force: Boolean = false) {
        if(destroyed)return
        if(force || support.value.state==WebViewSupportState.UNCHECKED){checkSupport(connectAfter=true);return}
        if(support.value.state!=WebViewSupportState.SUPPORTED){if(support.value.state==WebViewSupportState.CHECKING)connectAfterProbe=true;else manager.web(WebState.INCOMPATIBLE);return}
        val url = manager.authenticatedUrl ?: run {
            manager.web(WebState.DISCONNECTED)
            manager.event("DSH 网页地址尚未就绪，请先启动服务")
            return
        }
        if (attachedOrigin != manager.origin || attachedAuthUrl != url || force || view.url == null) {
            try {
                compatibility.install(view, manager.origin ?: return, manager.state.value.dsh)
                blobs.bind(manager.origin ?: return)
                attachedOrigin = manager.origin;attachedAuthUrl=url;retriedAuthUrl=null;authRetryPending=false;mainFrameAuthFailure=false
                manager.web(WebState.AUTHENTICATING)
                val saved=pendingRestore;pendingRestore=null
                if(saved!=null && saved.getString("managedOrigin")==manager.origin && view.restoreState(saved)!=null && OriginPolicy.sameOrigin(view.url ?: "",manager.origin)) {
                    clearProbeHistory=false
                    view.post { compactViewport.apply(view) }
                    return
                }
                view.loadUrl(url)
            } catch (t: Throwable) { manager.fail(t); manager.web(WebState.RENDER_ERROR) }
        }
    }
    fun save(bundle: Bundle) { if (!destroyed && support.value.state==WebViewSupportState.SUPPORTED && OriginPolicy.sameOrigin(view.url ?: "",manager.origin)){bundle.putString("managedOrigin",attachedOrigin);view.saveState(bundle)} }
    fun restore(bundle: Bundle) {
        // Runtime discovery may finish after Activity restoration; connect() checks the actual origin.
        val current=manager.origin
        if(current==null || bundle.getString("managedOrigin")==current)pendingRestore=Bundle(bundle)
    }
    private fun routePage(url: String) {
        if (destroyed) return
        if (OriginPolicy.browserPage(url, manager.origin)) openPage(url)
        else Toast.makeText(activity, "无法在浏览器中打开此链接；DSH 管理页面与文件链接保持隔离", Toast.LENGTH_LONG).show()
    }
    fun destroy() {
        ++probeId; authObserver.cancel(); compatibility.dispose(); blobs.dispose()
        popups.toList().forEach { it.destroy() }; popups.clear()
        if (!destroyed) view.destroy(); destroyed = true
    }
}
