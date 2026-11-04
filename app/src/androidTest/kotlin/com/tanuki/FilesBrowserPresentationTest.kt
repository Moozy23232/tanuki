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
                click("撤销 AI 权限"); assertNull(controller.state.value.authorizedClient)
                assertFalse(rpc("snapshot").getBoolean("ok"))
                assertTrue(rpc("request_control").getBoolean("ok")); click("交回 AI")
                click("文件"); await { !controller.state.value.visible }
                assertEquals(client, controller.state.value.authorizedClient)
                assertTrue(rpc("snapshot").getJSONObject("result").getString("text").contains("Browser fixture"))
                click("浏览器"); find("Browser fixture")
                assertEquals(page.url, controller.state.value.url)
                assertEquals(client, controller.state.value.authorizedClient)
                click("撤销 AI 权限"); assertNull(controller.state.value.authorizedClient)
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
                assertRenderedText("网址输入框"); assertRenderedText("前往")
                screenshot("browser-address-empty.png")
                tap("网址输入框")
                assertTrue("Touch must reach the address field, not an overlapping WebView", nodes().any { it.isEditable && it.isFocused })
                input(page.url); screenshot("browser-address-keyboard.png")
                tap("前往")
                await { controller.state.value.url == page.url && !controller.state.value.loading }
                find("Browser fixture")
                assertRenderedText("网址输入框"); assertRenderedText("前往")
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
        try {
            ui {
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
            assertRenderedText("网址输入框"); assertRenderedText("前往")
            screenshot("browser-address-dark-font200.png")
            tap("网址输入框")
            assertTrue(nodes().any { it.isEditable && it.isFocused })
        } finally { ui { activity.finish() } }
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
