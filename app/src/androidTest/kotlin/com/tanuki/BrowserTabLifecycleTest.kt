package com.tanuki

import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.browser.AgentBrowserController
import com.tanuki.browser.BrowserLibrary
import com.tanuki.browser.SavedBrowserTab
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 已保存标签按需加载，已访问页面保留草稿，但同时只恢复当前页面的绘制。 */
class BrowserTabLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun js(view: WebView, source: String): Any? {
        val done = CountDownLatch(1); var result: Any? = null
        ui { view.evaluateJavascript(source) { result=JSONTokener(it).nextValue(); done.countDown() } }
        assertTrue(done.await(5,TimeUnit.SECONDS)); return result
    }
    private fun await(message: String, condition: () -> Boolean) {
        repeat(80) { if (condition()) return; SystemClock.sleep(100) }; assertTrue(message,condition())
    }
    private fun snapshot(browser: AgentBrowserController): JSONObject {
        val connection = browser.connection
        val body = JSONObject().put("clientId", connection.clientId).put("method", "snapshot")
            .put("params", JSONObject()).toString().toByteArray(Charsets.UTF_8)
        return Socket("127.0.0.1", connection.port).use { socket ->
            socket.soTimeout = 8000
            val output = socket.getOutputStream()
            output.write(("POST /rpc HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${connection.token}\r\n" +
                "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(body)
            val response = socket.getInputStream().bufferedReader().readText()
            JSONObject(response.substringAfter("\r\n\r\n"))
        }
    }
    @Test(timeout = 240_000) fun fifteenLoadedTabsKeepDraftsAndRestoreRemainingMetadataLazily() {
        val target = instrumentation.targetContext
        check(target.packageName.endsWith(".workspacecheck"))
        val directory = File(target.cacheDir, "many-tab-lifecycle-${System.nanoTime()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val library = BrowserLibrary(context)
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var browser: AgentBrowserController
        var created = false
        BrowserFixtureServer().use { server ->
            val firstId = UUID.randomUUID().toString()
            library.saveTabs(listOf(SavedBrowserTab(firstId, "${server.base}/?tab=0", "Fixture 0")), firstId)
            library.flush()
            try {
                lateinit var host: FrameLayout
                ui {
                    browser = AgentBrowserController(context, library); created = true
                    host = FrameLayout(activity).apply { visibility = View.INVISIBLE }
                    activity.setContentView(host); browser.attachBackgroundHost(host)
                    browser.setVisible(true)
                    // 使用合法连接只读普通页面，不额外授予 AI 操作权。
                    assertNull(browser.state.value.authorizedClient)
                }
                val ids = mutableListOf<String>()
                val views = mutableListOf<WebView>()
                val epochs = mutableListOf<Any?>()
                repeat(15) { index ->
                    lateinit var view: WebView
                    ui {
                        val id = if (index == 0) firstId else requireNotNull(browser.newTab("${server.base}/?tab=$index"))
                        ids += id; view = browser.webView; views += view
                        assertEquals(1, browser.resumedTabCount())
                        assertEquals(1, host.childCount)
                    }
                    await("fixture $index did not finish loading") {
                        js(view, "document.readyState==='complete' && !!document.querySelector('#draft') && location.search==='?tab=$index'") == true
                    }
                    js(view, "window.kept='document-$index';document.querySelector('#draft').value='draft-$index';document.querySelector('h1').textContent='Synthetic page $index';true")
                    epochs += js(view, "performance.timeOrigin")
                }
                ui { assertEquals(15, browser.state.value.tabs.size); assertEquals(15, browser.liveTabCount()) }
                ids.forEachIndexed { index, id ->
                    ui {
                        browser.selectTab(id)
                        assertSame("Switching must reuse document $index", views[index], browser.webView)
                        assertEquals(1, browser.resumedTabCount()); assertEquals(1, host.childCount)
                    }
                    assertEquals("document-$index", js(views[index], "window.kept"))
                    assertEquals("draft-$index", js(views[index], "document.querySelector('#draft').value"))
                    assertEquals(epochs[index], js(views[index], "performance.timeOrigin"))
                    val read = snapshot(browser)
                    assertTrue("Current page must remain readable without operation permission: $read", read.getBoolean("ok"))
                    assertTrue(read.getJSONObject("result").getString("text").contains("Synthetic page $index"))
                    ui { assertEquals(id, browser.state.value.activeTab); assertNull(browser.state.value.authorizedClient) }
                }
                val remainingIds = ids.take(13)
                val remainingUrls = remainingIds.indices.map { "${server.base}/?tab=$it" }
                ui {
                    ids.drop(13).forEach { browser.closeTab(it) }
                    assertEquals(13, browser.liveTabCount())
                    assertEquals(remainingIds, browser.state.value.tabs.map { it.id })
                    assertEquals(remainingIds.last(), browser.state.value.activeTab)
                    browser.library.flush(); browser.close()
                    browser = AgentBrowserController(context)
                    assertEquals(remainingIds, browser.state.value.tabs.map { it.id })
                    assertEquals(remainingUrls, browser.state.value.tabs.map { it.url })
                    assertEquals(remainingIds.last(), browser.state.value.activeTab)
                    assertEquals("Restoring metadata must not eagerly create renderers", 0, browser.liveTabCount())
                    browser.attachBackgroundHost(host)
                    assertEquals(0, browser.liveTabCount())
                    browser.setVisible(true)
                    assertEquals(1, browser.liveTabCount()); assertEquals(1, browser.resumedTabCount())
                }
                lateinit var restored: WebView
                ui { restored = browser.webView }
                await("selected restored fixture did not load") {
                    js(restored, "document.readyState==='complete' && !!document.querySelector('#draft') && location.search==='?tab=12'") == true
                }
                assertEquals("", js(restored, "document.querySelector('#draft').value"))
                assertTrue(snapshot(browser).getJSONObject("result").getString("text").contains("Browser laboratory"))
                ui {
                    browser.selectTab(remainingIds.first()); restored = browser.webView
                    assertEquals("Only selected restored pages need renderers", 2, browser.liveTabCount())
                    assertEquals(1, browser.resumedTabCount()); assertEquals(1, host.childCount)
                }
                await("first restored fixture did not load") {
                    js(restored, "document.readyState==='complete' && !!document.querySelector('#draft') && location.search==='?tab=0'") == true
                }
                assertTrue(snapshot(browser).getBoolean("ok"))
            } finally {
                ui { if (created) browser.close(); activity.finish() }
                if (!created) library.close()
                directory.deleteRecursively()
            }
        }
    }
    @Test fun restoredTabsAreLazyAndOnlySelectedPageStaysWarmWithoutLosingDraftOrAuthority() {
        val target = instrumentation.targetContext
        check(target.packageName.endsWith(".workspacecheck"))
        val directory = File(target.cacheDir,"tab-lifecycle-${System.nanoTime()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val library = BrowserLibrary(context)
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var browser: AgentBrowserController
        var created = false
        BrowserFixtureServer().use { server ->
            val ids = List(12) { UUID.randomUUID().toString() }
            library.saveTabs(ids.mapIndexed { i,id -> SavedBrowserTab(id,"${server.base}/?tab=$i","Fixture $i") },ids.first())
            library.flush()
            try {
                lateinit var host: FrameLayout
                ui {
                    browser=AgentBrowserController(context,library); created=true
                    assertEquals("Metadata restore must create no WebViews",0,browser.liveTabCount())
                    host=FrameLayout(activity).apply { visibility=View.INVISIBLE }
                    activity.setContentView(host); browser.attachBackgroundHost(host)
                    assertEquals(0,browser.liveTabCount())
                    browser.setVisible(true); browser.grantConnection(); browser.setBackgroundEnabled(true)
                    assertEquals(1,browser.liveTabCount()); assertEquals(1,browser.resumedTabCount())
                }
                lateinit var first: WebView
                ui { first=browser.webView }
                await("first page did not load") { js(first,"!!document.querySelector('#draft')")==true }
                js(first,"window.kept='same-document';document.querySelector('#draft').value='retained draft';document.body.style.minHeight='5000px';scrollTo(0,500);true")
                val epoch=js(first,"performance.timeOrigin")
                val scroll=js(first,"scrollY")
                for(id in ids.drop(1)) {
                    ui {
                        browser.selectTab(id)
                        assertEquals("Only one tab may be resumed",1,browser.resumedTabCount())
                        assertEquals("Only the current tab may be attached",1,host.childCount)
                        assertNull("Hidden page must leave the render hierarchy",first.parent)
                    }
                    lateinit var current: WebView; ui { current=browser.webView }
                    await("selected tab did not load") { js(current,"!!document.querySelector('#draft')")==true }
                }
                ui {
                    browser.selectTab(ids.first()); browser.setVisible(false)
                    assertEquals(12,browser.liveTabCount()); assertEquals(1,browser.resumedTabCount())
                    assertEquals(1,host.childCount)
                    assertNotNull("Leaving UI must not revoke AI",browser.state.value.authorizedClient)
                    assertSame(first,browser.webView)
                }
                assertEquals("same-document",js(first,"window.kept"))
                assertEquals("retained draft",js(first,"document.querySelector('#draft').value"))
                assertEquals(epoch,js(first,"performance.timeOrigin"))
                assertEquals("Switching may round a physical pixel, not reset scroll", (scroll as Number).toDouble(),
                    (js(first,"scrollY") as Number).toDouble(), 1.0)
                ui { browser.setBackgroundEnabled(false); assertEquals(0,browser.resumedTabCount()); assertEquals(0,host.childCount) }
            } finally {
                ui { if(created)browser.close(); activity.finish() }
                if(!created)library.close()
                directory.deleteRecursively()
            }
        }
    }
}
