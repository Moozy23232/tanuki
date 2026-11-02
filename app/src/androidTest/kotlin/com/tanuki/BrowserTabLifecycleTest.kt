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
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Twelve saved tabs must not become twelve actively drawing WebViews. */
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
