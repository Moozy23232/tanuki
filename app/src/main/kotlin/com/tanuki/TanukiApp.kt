package com.tanuki

import android.app.Application
import com.tanuki.runtime.RuntimeManager

class TanukiApp : Application() {
    lateinit var runtime: RuntimeManager
        private set
    val updates by lazy { com.tanuki.update.UpdateManager(runtime) }
    val appUpdates by lazy { com.tanuki.update.ApplicationUpdateManager(this) }
    var browserController: com.tanuki.browser.AgentBrowserController? = null
        private set
    fun acquireBrowser(): com.tanuki.browser.AgentBrowserController {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        return browserController?.takeUnless { it.state.value.closed } ?: com.tanuki.browser.AgentBrowserController(
            this, com.tanuki.browser.BrowserLibrary(this, runtime.fileTransfers)) { setOfNotNull(runtime.origin) }.also { controller ->
            browserController = controller
            controller.onBackgroundNeeded = { startBrowserBackground() }
            controller.activateDefaultControl()
        }
    }
    fun showBrowserOverlay() {
        runCatching {
            startForegroundService(android.content.Intent(this, com.tanuki.browser.BrowserService::class.java)
                .setAction(com.tanuki.browser.BrowserService.SHOW_OVERLAY))
        }.onFailure { browserController?.notify("系统悬浮窗无法启动，请保持应用在前台后重试") }
    }
    fun hideBrowserOverlay() {
        if (browserController?.state?.value?.overlayFrame != null) runCatching {
            startService(android.content.Intent(this, com.tanuki.browser.BrowserService::class.java)
                .setAction(com.tanuki.browser.BrowserService.HIDE_OVERLAY))
        }
    }
    fun startBrowserBackground() {
        runCatching {
            startForegroundService(android.content.Intent(this, com.tanuki.browser.BrowserService::class.java))
        }.onFailure {
            browserController?.setBackgroundEnabled(false)
            android.widget.Toast.makeText(this, "后台浏览器无法启动，AI 授权已撤销；请保持应用在前台后重试", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate() {
        super.onCreate()
        // Must precede EVERY WebView, including DSH. Otherwise capturePicture can have
        // full document dimensions but blank pixels outside the human window's viewport.
        android.webkit.WebView.enableSlowWholeDocumentDraw()
        cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("tanuki-blob-") }?.forEach { it.delete() }
        runtime = RuntimeManager(this)
        // 安装器可能跨进程预读APK；恢复有期限的只读授权，不恢复任何安装动作。
        com.tanuki.update.ApkInstallAccess.restore(this)
    }
}
