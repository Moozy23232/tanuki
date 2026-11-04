package com.tanuki

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsCompat
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
                browser.library.setAvoidBottomControls(library.avoidBottomControls)
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
        ui { browser.library.setAvoidBottomControls(false) }
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
        assertTrue("WebView must extend behind floating navigation", location[1] + web.height >= bottomTab.bottom)
        val origin = js(browser, "performance.timeOrigin")
        fun paint(color: String): Bitmap {
            js(browser, "document.documentElement.style.background='$color';document.body.style.background='$color'")
            instrumentation.waitForIdleSync(); SystemClock.sleep(400)
            return requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        }
        val red = paint("rgb(255,32,32)"); val blue = paint("rgb(32,32,255)")
        try {
            // 同时核验导航前方及背后的真实网页像素，不以DOM存在替代绘制结果。
            val x = bottomTab.centerX(); val y = bottomTab.top - (2 * context.resources.displayMetrics.density).toInt()
            assertTrue("Red web pixels paint at the safe viewport bottom", android.graphics.Color.red(red.getPixel(x, y)) - android.graphics.Color.red(blue.getPixel(x, y)) > 25)
            assertTrue("Blue web pixels paint at the safe viewport bottom", android.graphics.Color.blue(blue.getPixel(x, y)) - android.graphics.Color.blue(red.getPixel(x, y)) > 25)
            val behind = bottomTab.top + (2 * context.resources.displayMetrics.density).toInt()
            assertTrue("Red web pixels must also show behind navigation", android.graphics.Color.red(red.getPixel(x, behind)) - android.graphics.Color.red(blue.getPixel(x, behind)) > 25)
            assertTrue("Blue web pixels must also show behind navigation", android.graphics.Color.blue(blue.getPixel(x, behind)) - android.graphics.Color.blue(red.getPixel(x, behind)) > 25)
        } finally { red.recycle(); blue.recycle() }
        screenshot("browser-compact-overlay.png")
        click("页面总览"); find("新建页面"); click("返回浏览器")
        click("设置"); find("返回上一页"); click("返回上一页")
        assertSame(web, browser.webView); assertEquals(origin, js(browser, "performance.timeOrigin"))
        click("浏览器进入全屏"); find("浏览器退出全屏"); click("浏览器退出全屏"); find("网址输入框")
        assertSame(web, browser.webView); assertEquals(origin, js(browser, "performance.timeOrigin"))
    }

    @Test fun optionalBottomAvoidanceKeepsFixedControlsClickableWithoutReload() = fixture { _, browser, _ ->
        FixedBottomControlsFixture.Server().use { server ->
            ui { browser.library.setAvoidBottomControls(false) }
            openPage(browser, server.url)
            val view = browser.webView
            val origin = js(browser, "performance.timeOrigin")
            val floatingHeight = view.height
            fun toggleAvoidance() {
                click("浏览器菜单"); click("浏览器设置"); click("通用")
                click("避让网页底部控件"); click("返回浏览器"); click("返回浏览器")
                val painted = CountDownLatch(1)
                ui { view.postVisualStateCallback(1, object : android.webkit.WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { painted.countDown() }
                }) }
                assertTrue("Resized document must finish drawing", painted.await(10, TimeUnit.SECONDS))
            }
            toggleAvoidance()
            assertTrue(browser.library.state.value.avoidBottomControls)
            await { view.height < floatingHeight }
            assertSame(view, browser.webView); assertEquals(origin, js(browser, "performance.timeOrigin"))
            ui { browser.takeControl() }
            instrumentation.waitForIdleSync(); SystemClock.sleep(300)
            fun browserSelected(): Boolean = nodes().filter { it.text?.toString() == "浏览器" || it.contentDescription?.toString() == "浏览器" }
                .any { candidate ->
                    var node: AccessibilityNodeInfo? = candidate
                    var selected = false
                    while (node != null) { selected = selected || node.isSelected; node = node.parent }
                    selected
                }
            assertTrue("Browser navigation must be selected before the touch", browserSelected())
            screenshot("browser-fixed-bottom-before.png")
            for ((id, event) in listOf("fixed-send" to "send", "fixed-more" to "more", "fixed-input" to "focus")) {
                val geometry = js(browser, "(()=>{const r=document.getElementById('$id').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,w:innerWidth,h:innerHeight}})()") as JSONObject
                val location = IntArray(2)
                var width = 0; var height = 0
                ui { browser.webView.getLocationOnScreen(location); width = browser.webView.width; height = browser.webView.height }
                val x = location[0] + geometry.getDouble("x").toFloat() * width / geometry.getDouble("w").toFloat()
                val y = location[1] + geometry.getDouble("y").toFloat() * height / geometry.getDouble("h").toFloat()
                // 只从 DOM 读取坐标；事件必须经过屏幕触摸，不能用无障碍或 JS 点击绕过导航。
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { instrumentation.sendPointerSync(touch) } finally { touch.recycle() }
                    SystemClock.sleep(60)
                }
                instrumentation.waitForIdleSync(); SystemClock.sleep(250)
                val count = (js(browser, "window.__fixedBottomEvents.$event") as Number).toInt()
                val selected = browserSelected()
                assertTrue("Fixed-bottom $id must receive its center touch ($x,$y), event=$count, browserSelected=$selected", count == 1 && selected)
            }
            toggleAvoidance()
            assertFalse(browser.library.state.value.avoidBottomControls)
            await { view.height == floatingHeight }
            assertSame(view, browser.webView); assertEquals(origin, js(browser, "performance.timeOrigin"))
            assertEquals(1, (js(browser, "window.__fixedBottomEvents.send") as Number).toInt())
        }
    }

    @Test fun revokedAiKeepsDshBrowserEntryAndUserPageVisible() = fixture { _, browser, server ->
        openPage(browser, server.base + "/")
        if (browser.state.value.authorizedClient == null) click("交回 AI")
        val view = browser.webView
        val epoch = js(browser, "performance.timeOrigin")
        js(browser, "document.querySelector('#draft').value='human draft';true")
        click("撤销 AI 操作权限")
        click("DSH"); find("AI 可查看 · 你在操作"); click("查看浏览器")
        find("Browser laboratory")
        assertSame(view, browser.webView)
        assertEquals(epoch, js(browser, "performance.timeOrigin"))
        assertEquals("human draft", js(browser, "document.querySelector('#draft').value"))
        val page = Rect()
        ui { assertTrue(view.isShown); assertTrue(view.getGlobalVisibleRect(page)) }
        assertTrue(page.width() > 0 && page.height() > 0)
        // 核对真实网页色块，避免仅有可访问性节点而画面空白仍被计为通过。
        val pixels = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var orange = 0
            for (y in page.top.coerceAtLeast(0) until page.bottom.coerceAtMost(pixels.height) step 4)
                for (x in page.left.coerceAtLeast(0) until page.right.coerceAtMost(pixels.width) step 4)
                    if ((pixels.getPixel(x, y) and 0xffffff) == 0xee7733) orange++
            assertTrue("撤权后网页应继续绘制", orange > 5)
        } finally { pixels.recycle() }
        assertNull(browser.state.value.authorizedClient)
        assertFalse(browser.library.state.value.aiControlEnabled)
        assertTrue(rpc(browser, "snapshot").getJSONObject("result").getString("text").contains("Browser laboratory"))
        assertTrue(rpc(browser, "screenshot").getJSONObject("result").getInt("width") > 0)
        assertEquals("permission_required", rpc(browser, "back").getJSONObject("error").getString("code"))
        input(server.base + "/second"); click("前往")
        await { browser.state.value.url.endsWith("/second") && !browser.state.value.loading }
        find("Browser laboratory")
        assertNull(browser.state.value.authorizedClient)
        assertFalse(browser.library.state.value.aiControlEnabled)
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
        touchPage(); find("收回 AI 浏览器操作权限？"); assertTrue(rpc(browser, "snapshot").getBoolean("ok")); assertFalse(rpc(browser, "back").getBoolean("ok"))
        screenshot("browser-touch-takeover.png"); click("继续交给 AI")
        assertTrue(rpc(browser, "snapshot").getBoolean("ok"))
        touchPage(); click("临时接管"); assertTrue(browser.library.state.value.aiControlEnabled)
        assertNotNull(browser.state.value.idleReturnSeconds)
        click("交回 AI"); click("撤销 AI 操作权限"); assertFalse(browser.library.state.value.aiControlEnabled)
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
        assertTrue(rpc(browser, "snapshot").getBoolean("ok")); assertFalse(rpc(browser, "back").getBoolean("ok"))
    }
    @Test fun tabOverviewCanCreateBeyondTwelveWithAnAccessibleBadge() = fixture { _, browser, server ->
        click("浏览器")
        val count = maxOf(13, browser.state.value.tabs.size + 1)
        ui { while (browser.state.value.tabs.size < count) assertNotNull(browser.newTab()) }
        click("页面总览"); find("$count 个页面")
        assertTrue(find("新建页面").isEnabled)
        click("新建页面")
        await { browser.state.value.tabs.size == count + 1 }
        assertEquals("${count + 1} 个页面", androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(find("页面总览")).stateDescription?.toString())
        screenshot("browser-tab-badge.png")
        click("页面总览"); find("${count + 1} 个页面"); click("返回浏览器")
        input(server.base + "/"); click("前往")
        await { !browser.state.value.loading && browser.state.value.title == "Browser laboratory" }
        if (browser.state.value.authorizedClient != null) click("撤销 AI 操作权限")
        js(browser, "window.open('/second','_blank');true")
        SystemClock.sleep(250)
        assertEquals("Automatic popups stay blocked", count + 1, browser.state.value.tabs.size)
        val link = Rect().also(find("Open second page")::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, link.exactCenterX(), link.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            SystemClock.sleep(60)
        }
        await { browser.state.value.tabs.size == count + 2 && browser.state.value.url.endsWith("/second") }
    }
    @Test fun multiPageOverviewBookmarksAndHistoryUseNativeControls() = fixture { _, browser, server ->
        openPage(browser, server.base + "/")
        if (browser.state.value.authorizedClient != null) click("撤销 AI 操作权限")
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
    @Test fun fullscreenRoundTripsRestoreChromePositionWithoutReload() = fixture { _, browser, server ->
        openPage(browser, server.base + "/")
        ui { browser.takeControl() }
        val view = browser.webView
        val origin = js(browser, "performance.timeOrigin")
        js(browser, "document.querySelector('#draft').value='round-trip draft';true")
        fun geometry(): List<Int> {
            val address = Rect().also(find("网址输入框")::getBoundsInScreen)
            val page = Rect()
            ui { view.getGlobalVisibleRect(page) }
            return listOf(address.top, page.top, page.height())
        }
        val before = geometry()
        repeat(3) { cycle ->
            click("浏览器进入全屏"); find("浏览器退出全屏")
            await { view.height > before[2] }
            if (cycle == 1) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            else click("浏览器退出全屏")
            // 先等原生栏恢复，避免通用查找器在过渡帧误滚动网页寻找不存在的控件。
            await { nodes().any { it.contentDescription?.toString() == "网址输入框" && it.isVisibleToUser } }
            find("网址输入框")
            try { await { geometry() == before } }
            catch (error: AssertionError) { throw AssertionError("Fullscreen cycle $cycle shifted chrome: before=$before after=${geometry()}", error) }
            assertSame(view, browser.webView)
            assertEquals(origin, js(browser, "performance.timeOrigin"))
            assertEquals("round-trip draft", js(browser, "document.querySelector('#draft').value"))
        }
        screenshot("browser-fullscreen-roundtrip.png")
    }
    @Test fun newBrowserPageDefaultsToWindowsAndFullscreenKeepsTheDocument() = fixture { activity, browser, server ->
        assertEquals("WINDOWS", browser.state.value.pageMode)
        openPage(browser, server.base + "/")
        await { server.userAgent.contains("Windows NT") && !browser.state.value.loading }
        assertTrue(js(browser, "navigator.userAgent").toString().contains("Windows NT"))
        val view = browser.webView
        val epoch = js(browser, "performance.timeOrigin")
        js(browser, "document.querySelector('#draft').value='fullscreen draft';true")
        fun barVisibility(): List<Boolean> {
            var result = emptyList<Boolean>()
            ui {
                val insets = WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(activity.window.decorView.rootWindowInsets), activity.window.decorView)
                result = listOf(WindowInsetsCompat.Type.statusBars(), WindowInsetsCompat.Type.navigationBars(),
                    WindowInsetsCompat.Type.captionBar(), WindowInsetsCompat.Type.systemBars()).map(insets::isVisible)
            }
            return result
        }
        val initialBars = barVisibility()
        assertTrue("Status/navigation bars must initially be visible", initialBars.take(2).all { it })
        var beforeHeight = 0
        ui { beforeHeight = view.height }
        click("浏览器进入全屏")
        find("浏览器退出全屏")
        await { var height = 0; ui { height = view.height }; height > beforeHeight }
        assertSame(view, browser.webView)
        assertEquals(epoch, js(browser, "performance.timeOrigin"))
        assertEquals("fullscreen draft", js(browser, "document.querySelector('#draft').value"))
        ui { assertEquals(android.view.View.LAYER_TYPE_HARDWARE, view.layerType) }
        // 手机没有captionBar，不能把组合systemBars的false当作状态栏/导航栏都已隐藏。
        await { barVisibility().take(2).none { it } }
        screenshot("browser-immersive-windows.png")
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        await { nodes().any { it.contentDescription?.toString() == "浏览器进入全屏" && it.isVisibleToUser } }
        find("浏览器进入全屏")
        ui { assertEquals(android.view.View.LAYER_TYPE_NONE, view.layerType) }
        try {
            await { barVisibility() == initialBars }
        } catch (error: AssertionError) {
            throw AssertionError("System bar visibility [status,navigation,caption,combined]: initial=$initialBars restored=${barVisibility()}", error)
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
        if (browser.state.value.authorizedClient != null) click("撤销 AI 操作权限")
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
