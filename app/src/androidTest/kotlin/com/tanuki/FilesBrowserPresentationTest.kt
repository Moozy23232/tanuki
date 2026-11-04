package com.tanuki

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.browser.AgentBrowserController
import com.tanuki.storage.WorkspaceFileManager
import com.tanuki.ui.TanukiTheme
import com.tanuki.ui.AgentBrowserPage
import com.tanuki.ui.WorkspaceFilesPage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID

/** Actual MainActivity navigation + native controls, isolated synthetic files only. No inference. */
class FilesBrowserPresentationTest {
    @get:org.junit.Rule val visibleAccessibility = VisibleAccessibilityRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as TanukiApp).runtime
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun launch(): MainActivity {
        check(context.packageName.endsWith(".workspacecheck")) { "Use the isolated verification applicationId" }
        check(!runtime.hasLiveChild)
        return instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != context.packageName) return emptyList()
        val result = arrayListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && result.size < 1000) {
            val node = queue.removeFirst()
            if (!node.refresh()) continue
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return result
    }
    private fun matches(node: AccessibilityNodeInfo, label: String) = node.text?.toString() == label || node.contentDescription?.toString() == label
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(40) {
            val current = nodes()
            current.lastOrNull { matches(it, label) && it.isVisibleToUser }?.let { return it }
            current.firstOrNull { matches(it, label) }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            current.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        screenshot("files-browser-failure.png")
        File(context.cacheDir, "files-browser-failure-tree.txt").writeText(nodes().joinToString("\n") { "${it.text} / ${it.contentDescription} ${it.className} ${it.isClickable}" })
        error("Visible control not found: $label")
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = find(label)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Cannot click $label" }
        SystemClock.sleep(180)
    }
    private fun tap(label: String) {
        val node = find(label)
        val bounds = Rect().also(node::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        SystemClock.sleep(180)
    }
    /** A semantics node can exist while a WebView paints over it. Assert actual text pixels too. */
    private fun assertRenderedText(label: String) {
        val bounds = Rect().also(find(label)::getBoundsInScreen)
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("$label must be inside the screen", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= image.width && bounds.bottom <= image.height)
            val colors = hashSetOf<Int>()
            for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) colors += image.getPixel(x, y)
            assertTrue("$label exists in accessibility but is not visibly painted (${colors.size} colors)", colors.size > 8)
        } finally { image.recycle() }
    }
    /** 前往已改成箭头；核验实际图形和对比，不能套用文字抗锯齿的颜色数量。 */
    private fun assertRenderedGoIcon(expectedEnabled: Boolean, expectedAddress: String = "") {
        // Icon子节点不合并按钮的disabled语义；启用状态取最近的按钮祖先，像素仍取图标边界。
        var settledNode: AccessibilityNodeInfo? = null
        var actualAddress: String? = null
        var actualEnabled: Boolean? = null
        var ancestry = arrayListOf<String>()
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val fresh = nodes()
            val node = fresh.lastOrNull { matches(it, "前往") && it.isVisibleToUser }
            val address = fresh.lastOrNull { matches(it, "网址输入框") && it.isVisibleToUser }
            val addressBounds = address?.let { Rect().also(it::getBoundsInScreen) }
            val field = fresh.firstOrNull { candidate ->
                candidate.isEditable && candidate.isVisibleToUser && addressBounds?.let { area ->
                    val r = Rect().also(candidate::getBoundsInScreen)
                    area.contains(r.centerX(), r.centerY())
                } == true
            }
            actualAddress = field?.let { it.text?.toString().orEmpty() }
            var current: AccessibilityNodeInfo? = node
            var button: AccessibilityNodeInfo? = null
            ancestry = arrayListOf()
            while (current != null && ancestry.size < 10) {
                val candidate = current
                if (!candidate.refresh()) break
                ancestry += "${candidate.className}:enabled=${candidate.isEnabled},clickable=${candidate.isClickable}"
                if (candidate.isClickable || candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } ||
                    !candidate.isEnabled || candidate.className?.toString() in setOf("android.widget.Button", "android.widget.ImageButton")) {
                    button = candidate; break
                }
                current = candidate.parent
            }
            actualEnabled = button?.isEnabled
            // 输入动作和Compose语义更新异步；轮询新树，必须同时观察到原URL和按钮状态，不能只多等固定时长。
            if (node != null && actualAddress == expectedAddress && actualEnabled == expectedEnabled) {
                settledNode = node; break
            }
            SystemClock.sleep(50)
        }
        if (settledNode == null) {
            screenshot("browser-go-icon-state-failure.png")
            fail("前往状态未收敛：expectedAddress=$expectedAddress, actualAddress=$actualAddress, expectedEnabled=$expectedEnabled, actualEnabled=$actualEnabled, ancestors=$ancestry")
        }
        assertEquals("地址输入必须保留合成URL", expectedAddress, actualAddress)
        assertEquals("空地址禁用前往，输入地址后启用", expectedEnabled, actualEnabled)
        val bounds = Rect().also(checkNotNull(settledNode)::getBoundsInScreen)
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertTrue("前往完整位于屏幕内", bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.top >= 0 && bounds.right <= image.width && bounds.bottom <= image.height)
            // 无障碍可能报告整个48dp按钮；只检查居中的20dp实际图标区域。
            val size = (20 * context.resources.displayMetrics.density).toInt()
            val icon = Rect(bounds.centerX() - size / 2, bounds.centerY() - size / 2,
                bounds.centerX() - size / 2 + size, bounds.centerY() - size / 2 + size)
            assertTrue("前往的20dp图标不能被裁切", bounds.contains(icon))
            fun pixel(x: Float, y: Float) = image.getPixel(icon.left + (x * size).toInt(), icon.top + (y * size).toInt())
            val background = pixel(0.08f, 0.08f)
            fun contrast(x: Float, y: Float): Int {
                val color = pixel(x, y)
                return maxOf(kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.red(background)),
                    kotlin.math.abs(android.graphics.Color.green(color) - android.graphics.Color.green(background)),
                    kotlin.math.abs(android.graphics.Color.blue(color) - android.graphics.Color.blue(background)))
            }
            // 探针位于箭杆、箭尖和上下斜边的内部；纯色覆盖或只剩语义节点都不能通过。
            for ((x, y) in listOf(0.30f to 0.50f, 0.55f to 0.50f, 0.78f to 0.50f, 0.60f to 0.31f, 0.60f to 0.69f)) {
                assertTrue("前往箭头在($x,$y)必须实际绘制且可辨，contrast=${contrast(x, y)}", contrast(x, y) >= 24)
            }
            assertTrue("箭头左上、左下必须保留背景，不能把整块前景当作图标",
                contrast(0.25f, 0.25f) < 24 && contrast(0.25f, 0.75f) < 24)
        } catch (error: AssertionError) {
            File(context.cacheDir, "browser-go-icon-failure.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            throw error
        } finally { image.recycle() }
    }
    private fun input(value: String) {
        val field = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        SystemClock.sleep(120)
    }
    private fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; SystemClock.sleep(50) }
        assertTrue("State did not settle", condition())
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350) // Accessibility semantics can precede the rendered Compose frame.
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun fixture(): File {
        check(context.packageName.endsWith(".workspacecheck"))
        return File(runtime.paths.workspaces, "files-ui-${UUID.randomUUID().toString().take(8)}").apply {
            check(mkdirs()); File(this, "inbox").mkdir()
            File(this, "hello.txt").writeText("original text")
            File(this, ".hidden.txt").writeText("hidden fixture")
        }
    }
    @Test fun fileEditingCopyPickerAndSelectionWorkThroughActualActivity() {
        val folder = fixture()
        val activity = launch()
        try {
            click("文件"); click(folder.name); find("hello.txt")
            screenshot("files-list-light.png")
            click("hello.txt"); click("编辑"); input("edited on Android")
            click("保存"); await { File(folder, "hello.txt").readText() == "edited on Android" }
            click("hello.txt的操作"); click("复制")
            screenshot("files-copy-picker.png")
            click("inbox"); click("复制到这里")
            await { File(folder, "inbox/hello.txt").exists() }
            assertEquals("edited on Android", File(folder, "inbox/hello.txt").readText())
            click("hello.txt的操作"); click("选择"); find("已选 1")
            screenshot("files-selection.png")
            click("取消选择"); click("排序与显示"); click("显示隐藏文件"); find(".hidden.txt")
            click("排序与显示"); click("切换为网格"); find("hello.txt")
            screenshot("files-grid.png")
            click("工作区"); find("Ubuntu 文件 · 只读"); screenshot("files-locations.png")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            click("设置"); find("返回上一页"); click("返回上一页"); find("hello.txt")
            assertTrue("Original source survives copying", File(folder, "hello.txt").exists())
        } finally { ui { activity.finish() }; folder.deleteRecursively() }
    }
    @Test fun canonicalGrantAndTabExitKeepControlUntilExplicitTakeback() {
        val activity = launch()
        val controller = MainActivity::class.java.getDeclaredField("agentBrowser").apply { isAccessible = true }
            .get(activity) as AgentBrowserController
        val originalTab = controller.state.value.activeTab
        var fixtureTab: String? = null
        ui { fixtureTab = controller.newTab() }
        val client = controller.connection.clientId
        val originalPolicy = controller.library.state.value.aiControlEnabled
        ui { controller.revoke() }
        fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
            val body = JSONObject().put("clientId", client).put("method", method).put("params", params).toString().toByteArray()
            return Socket("127.0.0.1", controller.connection.port).use { socket ->
                socket.soTimeout = 8000
                socket.getOutputStream().write(("POST /rpc HTTP/1.1\r\nAuthorization: Bearer ${controller.connection.token}\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                socket.getOutputStream().write(body)
                JSONObject(socket.getInputStream().bufferedReader().readText().substringAfter("\r\n\r\n"))
            }
        }
        try {
            AgentBrowserControlTest.LocalPage().use { page ->
                assertTrue(rpc("request_control").getBoolean("ok"))
                assertNull(controller.state.value.authorizedClient)
                find("网页操作待授权"); screenshot("browser-request-banner.png")
                click("查看请求"); assertNull(controller.state.value.authorizedClient)
                find("交回 AI"); screenshot("browser-request.png")
                click("交回 AI"); await { controller.state.value.authorizedClient == client }
                assertTrue(rpc("navigate", JSONObject().put("url", page.url)).getBoolean("ok"))
                await { controller.state.value.url == page.url && !controller.state.value.loading }
                find("Browser fixture"); screenshot("browser-controlled-page.png")
                click("撤销 AI 操作权限"); assertNull(controller.state.value.authorizedClient)
                assertTrue(rpc("snapshot").getBoolean("ok")); assertFalse(rpc("back").getBoolean("ok"))
                assertTrue(rpc("request_control").getBoolean("ok")); click("交回 AI")
                click("文件"); await { !controller.state.value.visible }
                assertEquals(client, controller.state.value.authorizedClient)
                assertTrue(rpc("snapshot").getJSONObject("result").getString("text").contains("Browser fixture"))
                click("浏览器"); find("Browser fixture")
                assertEquals(page.url, controller.state.value.url)
                assertEquals(client, controller.state.value.authorizedClient)
                click("撤销 AI 操作权限"); assertNull(controller.state.value.authorizedClient)
                click("浏览器菜单"); click("历史记录"); find("Browser fixture"); screenshot("browser-history.png"); click("返回浏览器")
                click("设置"); find("返回上一页"); click("返回上一页"); find("Browser fixture")
            }
        } finally { ui { controller.revoke(); controller.library.setAiControlEnabled(originalPolicy); fixtureTab?.let(controller::closeTab); controller.selectTab(originalTab); activity.finish() } }
    }
    @Test fun addressBarIsActuallyPaintedAndRealTouchCanNavigateBeforeAndAfterPageLoad() {
        val activity = launch()
        val controller = MainActivity::class.java.getDeclaredField("agentBrowser").apply { isAccessible = true }
            .get(activity) as AgentBrowserController
        val originalTab = controller.state.value.activeTab
        var fixtureTab: String? = null
        try {
            AgentBrowserControlTest.LocalPage().use { page ->
                click("浏览器")
                ui { controller.revoke(); fixtureTab = controller.newTab() }
                assertRenderedText("网址输入框"); assertRenderedGoIcon(expectedEnabled = false)
                screenshot("browser-address-empty.png")
                tap("网址输入框")
                assertTrue("Touch must reach the address field, not an overlapping WebView", nodes().any { it.isEditable && it.isFocused })
                input(page.url); assertRenderedGoIcon(expectedEnabled = true, expectedAddress = page.url); screenshot("browser-address-keyboard.png")
                tap("前往")
                await { controller.state.value.url == page.url && !controller.state.value.loading }
                find("Browser fixture")
                assertRenderedText("网址输入框"); assertRenderedGoIcon(expectedEnabled = true, expectedAddress = page.url)
                screenshot("browser-address-loaded.png")
                tap("网址输入框")
                try {
                    await {
                        nodes().firstOrNull { it.isEditable && it.isFocused }?.let {
                            it.textSelectionStart == 0 && it.textSelectionEnd == page.url.length
                        } == true
                    }
                } catch (error: AssertionError) {
                    screenshot("browser-address-selection-failure.png")
                    val field = nodes().firstOrNull { it.isEditable && it.isFocused }
                    throw AssertionError("Address selection ${field?.textSelectionStart}..${field?.textSelectionEnd}, length=${field?.text?.length}, expected=${page.url.length}", error)
                }
                // 已聚焦后的再次点击只定位光标，不把每次点击都强制改成全选。
                SystemClock.sleep(500); tap("网址输入框")
                await { nodes().firstOrNull { it.isEditable && it.isFocused }?.let { it.textSelectionStart == it.textSelectionEnd } == true }
                input(page.url + "?second=1"); tap("前往")
                await { controller.state.value.url.endsWith("?second=1") && !controller.state.value.loading }
                assertNull(controller.state.value.authorizedClient)
                assertRenderedText("网址输入框")
            }
        } finally { ui { fixtureTab?.let(controller::closeTab); controller.selectTab(originalTab); activity.finish() } }
    }
    @Test fun browserAddressRemainsVisibleAndTouchableInDarkThemeAtTwoHundredPercent() {
        val activity = launch()
        val controller = MainActivity::class.java.getDeclaredField("agentBrowser").apply { isAccessible = true }
            .get(activity) as AgentBrowserController
        val originalTab = controller.state.value.activeTab
        var fixtureTab: String? = null
        try {
            ui {
                fixtureTab = checkNotNull(controller.newTab())
                val config = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                }
                val density = activity.resources.displayMetrics.density
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density, 2f)) {
                        TanukiTheme { AgentBrowserPage(controller, Modifier.widthIn(max = 320.dp).fillMaxSize().safeDrawingPadding(), onSettings = {}) }
                    }
                }
            }
            assertRenderedText("网址输入框"); assertRenderedGoIcon(expectedEnabled = false)
            screenshot("browser-address-dark-font200.png")
            tap("网址输入框")
            assertTrue(nodes().any { it.isEditable && it.isFocused })
        } finally { ui { fixtureTab?.let(controller::closeTab); controller.selectTab(originalTab); activity.finish() } }
    }
    @Test fun fileControlsRemainReachableInDarkThemeAtTwoHundredPercent() {
        val folder = fixture()
        val activity = launch()
        try {
            ui {
                val config = Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                }
                val density = activity.resources.displayMetrics.density
                activity.setContent {
                    CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density, 2f)) {
                        TanukiTheme { Surface(Modifier.fillMaxSize()) {
                            WorkspaceFilesPage(WorkspaceFileManager(runtime.paths), {}, {}, Modifier.fillMaxSize())
                        } }
                    }
                }
            }
            click(folder.name); find("hello.txt")
            screenshot("files-dark-font200.png")
            click("文件操作"); find("新建文件夹"); screenshot("files-menu-dark-font200.png")
            val node = find("新建文件夹")
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue(bounds.left >= 0 && bounds.right <= context.resources.displayMetrics.widthPixels)
        } finally { ui { activity.finish() }; folder.deleteRecursively() }
    }
}
