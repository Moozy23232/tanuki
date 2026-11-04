package com.tanuki

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tanuki.browser.*
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native controls + real WebViews. Public loopback fixture, no model or real project. */
class BrowserWorkspacePresentationTest {
    @get:org.junit.Rule val visibleAccessibility = VisibleAccessibilityRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as TanukiApp
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(block: () -> Boolean) { repeat(200) { if (block()) return; SystemClock.sleep(50) }; assertTrue("Browser state did not settle", block()) }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        val result = arrayListOf<AccessibilityNodeInfo>(); val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && result.size < 1200) {
            val node = queue.removeFirst(); if (!node.refresh()) continue
            result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return result
    }
    private fun find(label: String): AccessibilityNodeInfo {
        var backwards = false
        var revealed = false
        repeat(50) { attempt ->
            val all = nodes()
            val matches = all.filter { it.text?.toString() == label || it.contentDescription?.toString() == label }
            matches.lastOrNull { it.isVisibleToUser }?.let { return it }
            // ponytail-lite: 沿用现有UI回归的显露/双向查找，返回网页后目标可能在当前滚动位置上方。
            if (!revealed && matches.lastOrNull()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true) {
                revealed = true
            } else {
                val action = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                val scroller = all.firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == action } }
                if (scroller?.performAction(action) != true || attempt == 24) backwards = !backwards
            }
            SystemClock.sleep(100)
        }
        File(context.cacheDir, "browser-workspace-failure-tree.txt").writeText(nodes().joinToString("\n") { "${it.text} / ${it.contentDescription}" })
        screenshot("browser-workspace-failure.png")
        error("Missing control: $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = find(label)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $label" }
        SystemClock.sleep(200)
    }
    private fun input(value: String, last: Boolean = false, field: String? = null) {
        fun webContent(node: AccessibilityNodeInfo): Boolean {
            var parent: AccessibilityNodeInfo? = node
            repeat(30) { if (parent?.className?.toString()?.contains("WebView") == true) return true; parent = parent?.parent }
            return false
        }
        val fields = nodes().filter { it.isEditable && it.isVisibleToUser && !webContent(it) }
        if (fields.isEmpty()) { screenshot("browser-input-missing.png"); error("No native text field is visible") }
        val node = if (field != null) {
            val bounds = Rect().also(find(field)::getBoundsInScreen)
            fields.first { candidate -> val r = Rect().also(candidate::getBoundsInScreen); bounds.contains(r.centerX(), r.centerY()) }
        } else if (last) fields.last() else fields.first()
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        SystemClock.sleep(120)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun js(browser: AgentBrowserController, script: String): Any? {
        var result: Any? = null; val latch = CountDownLatch(1)
        ui { browser.webView.evaluateJavascript(script) { result = JSONTokener(it).nextValue(); latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun rpc(browser: AgentBrowserController, method: String): JSONObject {
        val c = browser.connection
        val body = JSONObject().put("id", 1).put("clientId", c.clientId).put("method", method).put("params", JSONObject()).toString().toByteArray()
        Socket("127.0.0.1", c.port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().apply { write("POST /rpc HTTP/1.1\r\nHost: 127.0.0.1:${c.port}\r\nAuthorization: Bearer ${c.token}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
            return JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
        }
    }
    private fun fixture(block: (MainActivity, AgentBrowserController, BrowserFixtureServer) -> Unit) {
        check(context.packageName.endsWith(".workspacecheck")); check(!app.runtime.hasLiveChild)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val browser = requireNotNull(app.browserController)
        // Clean only our prior failed public fixtures, never arbitrary user pages.
        ui { browser.state.value.tabs.filter { it.title in setOf("Browser laboratory", "Browser fixture") && it.url.startsWith("http://127.0.0.1:") }.forEach { browser.closeTab(it.id) } }
        val before = browser.state.value; val library = browser.library.state.value
        ui { check(browser.newTab() != null) }
        try { BrowserFixtureServer().use { block(activity, browser, it) } }
        catch (error: Throwable) {
            screenshot("browser-workspace-failure.png")
            File(context.cacheDir, "browser-workspace-failure-tree.txt").writeText(nodes().joinToString("\n") { "${it.text} / ${it.contentDescription}" })
            throw error
        } finally {
            ui {
                browser.revoke()
                browser.state.value.tabs.filter { tab -> before.tabs.none { it.id == tab.id } }.forEach { browser.closeTab(it.id) }
                browser.selectTab(before.activeTab)
                browser.library.setSearchEngine(BrowserSearchEngine.from(library.searchEngine))
                browser.library.setAiControlEnabled(library.aiControlEnabled)
                if (before.authorizedClient == browser.connection.clientId) {
                    browser.setVisible(true); browser.grantConnection(); browser.setVisible(false)
                }
                browser.library.state.value.bookmarks.filter { b -> library.bookmarks.none { it.id == b.id } }.forEach { browser.library.removeBookmark(it.id) }
                browser.library.state.value.downloads.filter { d -> library.downloads.none { it.id == d.id } }.forEach { browser.library.cancel(it.id) }
            }
            SystemClock.sleep(250)
            browser.library.state.value.downloads.filter { d -> library.downloads.none { it.id == d.id } }.forEach { runCatching { browser.library.removeDownload(it.id) } }
            if (!before.backgroundEnabled) context.stopService(Intent(context, BrowserService::class.java))
            ui { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().forEach { it.finish() }; if (!activity.isDestroyed) activity.finish() }
        }
    }
    private fun openPage(browser: AgentBrowserController, url: String) {
        click("浏览器"); input(url); click("前往")
        await { !browser.state.value.loading && browser.state.value.title == "Browser laboratory" }
        find("Browser laboratory")
    }
    @Test fun compactChromeSharesTitleRowAndWebPixelsShowBehindNavigation() = fixture { _, browser, server ->
        openPage(browser, server.base + "/")
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        val address = bounds(find("网址输入框"))
        val title = nodes().filter { it.text?.toString() == "浏览器" && it.isVisibleToUser }.minBy { bounds(it).top }
        assertTrue("Address must share the browser title row", bounds(title).centerY() in address.top..address.bottom)
        assertTrue("Address follows the title", bounds(title).right <= address.left)
        val back = bounds(find("后退")); val tabs = bounds(find("页面总览"))
        assertTrue("Navigation tools sit below the address", back.top >= address.bottom && tabs.top >= address.bottom)
        val web = browser.webView
        val location = IntArray(2)
        ui { web.getLocationOnScreen(location) }
        assertTrue("Tools stay above the page", back.bottom <= location[1] && tabs.bottom <= location[1])
        var nav = find("DSH")
        while (!nav.isClickable) nav = requireNotNull(nav.parent)
        val bottomTab = bounds(nav)
        assertTrue("WebView extends behind floating navigation", location[1] + web.height >= bottomTab.bottom)
        val origin = js(browser, "performance.timeOrigin")
        fun paint(color: String): Bitmap {
            js(browser, "document.documentElement.style.background='$color';document.body.style.background='$color'")
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            return requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        val red = paint("rgb(255,32,32)"); val blue = paint("rgb(32,32,255)")
        try {
            // 取未选页签的上内边距；只有真实网页透过背板时，此处才随网页颜色改变。
            val x = bottomTab.centerX(); val y = bottomTab.top + (2 * context.resources.displayMetrics.density).toInt()
            assertTrue("Red web pixels show through the navigation", android.graphics.Color.red(red.getPixel(x, y)) - android.graphics.Color.red(blue.getPixel(x, y)) > 25)
            assertTrue("Blue web pixels show through the navigation", android.graphics.Color.blue(blue.getPixel(x, y)) - android.graphics.Color.blue(red.getPixel(x, y)) > 25)
        } finally { red.recycle(); blue.recycle() }
        screenshot("browser-compact-overlay.png")
        click("页面总览"); find("新建页面"); click("返回浏览器")
        click("设置"); find("返回上一页"); click("返回上一页")
        assertSame(web, browser.webView); assertEquals(origin, js(browser, "performance.timeOrigin"))
        click("浏览器进入全屏"); find("浏览器退出全屏"); click("浏览器退出全屏"); find("网址输入框")
        assertSame(web, browser.webView); assertEquals(origin, js(browser, "performance.timeOrigin"))
    }

    @Test fun defaultConnectionAndTouchTakeoverSurviveBackgroundAndRecreation() = fixture { activity, browser, server ->
        openPage(browser, server.base + "/")
        if (browser.state.value.authorizedClient == null) click("交回 AI")
        assertTrue(rpc(browser, "request_control").getJSONObject("result").getBoolean("authorized"))
        await { browser.state.value.backgroundEnabled && browser.state.value.authorizedClient == browser.connection.clientId }
        val target = Rect().also(find("Browser laboratory")::getBoundsInScreen)
        fun touchPage() {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.exactCenterX(), target.exactCenterY(), 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
        }
        touchPage(); find("收回 AI 浏览器权限？"); assertFalse(rpc(browser, "snapshot").getBoolean("ok"))
        screenshot("browser-touch-takeover.png"); click("继续交给 AI")
        assertTrue(rpc(browser, "snapshot").getBoolean("ok"))
        touchPage(); click("临时接管"); assertTrue(browser.library.state.value.aiControlEnabled)
        assertNotNull(browser.state.value.idleReturnSeconds)
        click("交回 AI"); click("撤销 AI 权限"); assertFalse(browser.library.state.value.aiControlEnabled)
        click("文件"); click("浏览器"); assertNull(browser.state.value.authorizedClient)
        click("交回 AI"); assertTrue(browser.library.state.value.aiControlEnabled)
        val view = browser.webView
        val time = js(browser, "performance.timeOrigin")
        js(browser, "document.querySelector('#draft').value='keep draft';window.__probeTick=0;setInterval(()=>window.__probeTick++,200);true")
        click("DSH"); assertTrue(rpc(browser, "snapshot").getJSONObject("result").getString("text").contains("Browser laboratory"))
        // Real cross-app dragging/resizing is covered by BrowserOverlayPresentationTest.
        click("浏览器"); find("Browser laboratory"); assertSame(view, browser.webView)
        assertEquals("keep draft", js(browser, "document.querySelector('#draft').value"))
        assertEquals(time, js(browser, "performance.timeOrigin"))
        ui { activity.moveTaskToBack(true) }
        await { !browser.state.value.visible }
        SystemClock.sleep(1200)
        assertTrue(rpc(browser, "snapshot").getJSONObject("result").getString("text").contains("Browser laboratory"))
        assertEquals(browser.connection.clientId, browser.state.value.authorizedClient)
        assertTrue((js(browser, "window.__probeTick") as Number).toInt() > 0)
        instrumentation.uiAutomation.executeShellCommand("am start -n ${context.packageName}/com.tanuki.MainActivity").close()
        var resumed: MainActivity? = null
        await { ui { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }; resumed != null }
        val old = resumed!!
        ui { old.recreate() }
        await { ui { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }; resumed != null && resumed !== old }
        assertSame(browser, app.browserController); assertSame(view, browser.webView)
        assertTrue(rpc(browser, "snapshot").getJSONObject("result").getString("text").contains("Browser laboratory"))
        assertEquals("keep draft", js(browser, "document.querySelector('#draft').value"))
        assertEquals(time, js(browser, "performance.timeOrigin"))
        context.startService(Intent(context, BrowserService::class.java).setAction("REVOKE"))
        await { browser.state.value.authorizedClient == null }
        assertFalse(rpc(browser, "snapshot").getBoolean("ok"))
    }
    @Test fun multiPageOverviewBookmarksAndHistoryUseNativeControls() = fixture { _, browser, server ->
        openPage(browser, server.base + "/")
        if (browser.state.value.authorizedClient != null) click("撤销 AI 权限")
        val first = browser.state.value.activeTab; val view = browser.webView
        js(browser, "document.querySelector('#draft').value='tab one';true")
        click("页面总览"); click("新建页面"); await { browser.state.value.activeTab != first }; val second = browser.state.value.activeTab
        input(server.base + "/second"); click("前往"); await { browser.state.value.url.endsWith("/second") && !browser.state.value.loading }
        click("页面总览"); find("关闭此页"); screenshot("browser-pages-overview.png")
        click(server.base + "/"); await { browser.state.value.activeTab == first }
        assertSame(view, browser.webView); assertEquals("tab one", js(browser, "document.querySelector('#draft').value"))
        click("浏览器菜单"); click("收藏当前页"); input("Native saved fixture"); input("Research", last = true); click("保存收藏")
        assertTrue(browser.library.state.value.bookmarks.any { it.title == "Native saved fixture" && it.folder == "Research" })
        click("浏览器菜单"); click("收藏夹"); find("Research"); find("Native saved fixture"); screenshot("browser-bookmarks.png"); click("返回浏览器")
        click("浏览器菜单"); click("历史记录"); find("Browser laboratory"); screenshot("browser-persistent-history.png"); click("返回浏览器")
        ui { browser.selectTab(second) }; await { browser.state.value.activeTab == second }
        click("页面总览"); click("关闭此页"); click("返回浏览器")
        assertTrue(browser.state.value.tabs.isNotEmpty()); assertNull(browser.state.value.authorizedClient)
    }
    @Test fun newBrowserPageDefaultsToWindowsAndFullscreenKeepsTheDocument() = fixture { activity, browser, server ->
        assertEquals("WINDOWS", browser.state.value.pageMode)
        openPage(browser, server.base + "/")
        await { server.userAgent.contains("Windows NT") && !browser.state.value.loading }
        assertTrue(js(browser, "navigator.userAgent").toString().contains("Windows NT"))
        val view = browser.webView
        val epoch = js(browser, "performance.timeOrigin")
        js(browser, "document.querySelector('#draft').value='fullscreen draft';true")
        var beforeHeight = 0
        ui { beforeHeight = view.height }
        click("浏览器进入全屏")
        find("浏览器退出全屏")
        await { var height = 0; ui { height = view.height }; height > beforeHeight }
        assertSame(view, browser.webView)
        assertEquals(epoch, js(browser, "performance.timeOrigin"))
        assertEquals("fullscreen draft", js(browser, "document.querySelector('#draft').value"))
        ui { assertEquals(android.view.View.LAYER_TYPE_HARDWARE, view.layerType) }
        await {
            var hidden = false
            ui { hidden = activity.window.decorView.rootWindowInsets?.let {
                !androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, activity.window.decorView)
                    .isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } == true }
            hidden
        }
        screenshot("browser-immersive-windows.png")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        find("浏览器进入全屏")
        ui { assertEquals(android.view.View.LAYER_TYPE_NONE, view.layerType) }
        await {
            var visible = false
            ui { visible = activity.window.decorView.rootWindowInsets?.let {
                androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, activity.window.decorView)
                    .isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } == true }
            visible
        }
        assertEquals(epoch, js(browser, "performance.timeOrigin"))
        assertEquals("fullscreen draft", js(browser, "document.querySelector('#draft').value"))
        click("浏览器进入全屏"); click("浏览器退出全屏")
        find("浏览器进入全屏")
        assertEquals(epoch, js(browser, "performance.timeOrigin"))
    }
    @Test fun emptyBrowserCanExitFullscreenWithoutFocusingAMissingAddressField() = fixture { _, browser, _ ->
        click("浏览器")
        assertTrue(browser.state.value.url.isEmpty())
        click("浏览器进入全屏")
        find("浏览器退出全屏")
        click("退出全屏后输入网址")
        find("网址输入框")
        find("浏览器进入全屏")
    }
    @Test fun displayModesSearchFindZoomCaptureAndDownloadAreUsable() = fixture { _, browser, server ->
        openPage(browser, server.base + "/")
        if (browser.state.value.authorizedClient != null) click("撤销 AI 权限")
        click("浏览器菜单"); screenshot("browser-menu-grouped.png"); click("浏览器设置"); screenshot("browser-settings-groups.png")
        click("通用"); click("搜索引擎"); click("DuckDuckGo"); click("完成")
        assertEquals("DUCKDUCKGO", browser.library.state.value.searchEngine)
        click("返回浏览器"); click("返回浏览器")
        click("浏览器菜单"); click("工具箱"); screenshot("browser-toolbox.png"); click("页面显示模式"); click("Windows PC"); click("应用并重新加载")
        await { browser.state.value.pageMode == "WINDOWS" && server.userAgent.contains("Windows NT") && !browser.state.value.loading }
        assertTrue(js(browser, "navigator.userAgent").toString().contains("Windows NT"))
        click("浏览器菜单"); click("工具箱"); click("页面显示模式"); click("Mac"); screenshot("browser-display-modes.png"); click("应用并重新加载")
        await { browser.state.value.pageMode == "MAC" && server.userAgent.contains("Macintosh") && !browser.state.value.loading }
        click("浏览器菜单"); click("工具箱"); click("页面显示模式"); click("移动端"); click("应用并重新加载")
        await { browser.state.value.pageMode == "MOBILE" && server.userAgent.contains("Android") && !browser.state.value.loading }
        val mobileTab = browser.state.value.activeTab
        ui { assertNotNull(browser.newTab()) }
        assertEquals("WINDOWS", browser.state.value.pageMode)
        ui { browser.selectTab(mobileTab) }
        assertEquals("MOBILE", browser.state.value.pageMode)
        click("浏览器菜单"); click("工具箱"); click("页内查找"); input("needle", field = "页内查找输入框"); await { browser.state.value.findCount == 3 }
        click("下一个匹配"); assertTrue(browser.state.value.findIndex > 0); screenshot("browser-find.png"); click("关闭查找")
        click("浏览器菜单"); click("工具箱"); click("网页缩放"); click("放大网页"); await { browser.state.value.zoomPercent > 100 }; screenshot("browser-zoom.png"); click("恢复 100%"); click("完成")
        click("浏览器菜单"); click("工具箱"); click("截取网页画面")
        await { browser.library.state.value.downloads.any { it.image && it.status == "已完成" } }
        find("打开"); find("导出"); screenshot("browser-capture-library.png"); click("返回浏览器")
        val link = Rect().also(find("下载测试文件")::getBoundsInScreen)
        val touchTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(touchTime, SystemClock.uptimeMillis(), action, link.exactCenterX(), link.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            SystemClock.sleep(60)
        }
        find("下载此文件？"); assertTrue(browser.library.state.value.downloads.none { !it.image && it.url.startsWith(server.base) })
        click("确认下载")
        await { browser.library.state.value.downloads.any { it.url == server.base + "/file" && it.status == "已完成" } }
        val record = browser.library.state.value.downloads.first { it.url == server.base + "/file" }
        assertEquals(BrowserFixtureServer.FILE_BODY, browser.library.file(record.id).readText())
        find("browser-fixture.txt"); screenshot("browser-downloads.png"); click("返回浏览器")
        assertNull(browser.state.value.authorizedClient)
    }
}
