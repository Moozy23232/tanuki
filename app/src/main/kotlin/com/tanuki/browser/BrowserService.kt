package com.tanuki.browser

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import com.tanuki.MainActivity
import com.tanuki.TanukiApp
import com.tanuki.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** User-started browser lifetime. The real overlay is optional and never restarts itself. */
class BrowserService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controller get() = (application as TanukiApp).browserController
    private var overlay: BrowserOverlayWindow? = null
    private var stopping = false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("browser", "浏览器后台运行", NotificationManager.IMPORTANCE_LOW))
        startForeground(2, notification("浏览器后台运行 · AI 可查看，未获操作权"))
        controller?.let { browser ->
            browser.setBackgroundEnabled(true)
            scope.launch {
                combine(browser.state, browser.library.state) { state, library ->
                    val active = library.downloads.firstOrNull { it.status in setOf("等待中", "下载中") }
                    val control = when { state.takeoverRequested -> "AI 已暂停，等待接管选择"; state.authorizedClient != null -> "AI 已获操作权，可后台操作"; else -> "AI 可查看，未获操作权" }
                    Triple("${if (state.overlayFrame != null) "悬浮窗 · " else ""}${state.tabs.size} 个页面 · $control", active, state.overlayFrame != null)
                }.distinctUntilChanged().collect { (text, active, hasOverlay) ->
                    // Moving/resizing a window must not flood NotificationManager every pointer frame.
                    if (!stopping) getSystemService(NotificationManager::class.java).notify(2, notification(text, active, hasOverlay))
                }
            }
        }
    }
    private fun notification(text: String, download: BrowserDownload? = null, hasOverlay: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 21, fullBrowserIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String, id: Int) = PendingIntent.getService(this, id, Intent(this, BrowserService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "browser").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("${getString(R.string.app_name)} 浏览器").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text + (download?.let { "\n${it.name} · ${it.status}" } ?: "")))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "撤销 AI 操作授权", action("REVOKE", 22)).build())
            .apply { if (hasOverlay) addAction(Notification.Action.Builder(null, "关闭悬浮窗", action(HIDE_OVERLAY, 24)).build()) }
            .addAction(Notification.Action.Builder(null, "停止后台浏览器", action("STOP", 23)).build())
            .apply { if (download != null) setProgress(100, if (download.total > 0) (100.0 * download.received / download.total).toInt().coerceIn(0, 100) else 0, download.total <= 0) }.build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (controller == null || controller?.state?.value?.closed == true) {
            stopping = true; scope.cancel()
            hideOverlay(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            SHOW_OVERLAY -> showOverlay()
            HIDE_OVERLAY -> hideOverlay()
            "REVOKE" -> controller?.takeControl()
            "STOP" -> {
                stopping = true; scope.cancel()
                hideOverlay(); controller?.takeControl(); controller?.library?.cancelAll()
                controller?.setBackgroundEnabled(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun showOverlay() {
        overlay?.let { it.revealPage(); return }
        val browser = controller ?: return
        if (!Settings.canDrawOverlays(this)) {
            browser.notify("需要先允许「显示在其他应用上层」，才能打开系统悬浮窗")
            return
        }
        runCatching {
            BrowserOverlayWindow(this, browser, ::hideOverlay) {
                // Start while our user-touched window is visible; do not rely on background launch privileges.
                runCatching { startActivity(fullBrowserIntent()) }.onSuccess { hideOverlay() }
                    .onFailure { Toast.makeText(this, "请从通知或桌面打开完整浏览器", Toast.LENGTH_LONG).show() }
            }.also { window -> overlay = window; check(window.show()) }
        }.onFailure {
            hideOverlay(); browser.notify("系统未能显示悬浮窗，请检查悬浮窗权限并重试")
            Toast.makeText(this, "悬浮窗未打开，请检查系统权限", Toast.LENGTH_LONG).show()
        }
    }
    private fun hideOverlay() { val previous = overlay; overlay = null; previous?.close() }
    private fun fullBrowserIntent() = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(OPEN_BROWSER, true)
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); overlay?.configurationChanged() }
    override fun onDestroy() {
        stopping = true; scope.cancel(); hideOverlay(); controller?.setBackgroundEnabled(false); controller?.library?.cancelAll(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val SHOW_OVERLAY = "SHOW_OVERLAY"
        const val HIDE_OVERLAY = "HIDE_OVERLAY"
        const val OPEN_BROWSER = "openBrowser"
    }
}
