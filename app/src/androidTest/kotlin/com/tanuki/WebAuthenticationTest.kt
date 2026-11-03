package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import com.tanuki.web.DshWebView
import com.tanuki.web.WebViewSupportState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Local HTTP fixture only: no DSH models, user profiles, or external network. */
class WebAuthenticationTest {
    private class Fixture(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }

    private class Server : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${socket.localPort}"
        val cookieName = "hp_test_${socket.localPort}"
        val token = AtomicReference("first-fixture-token")
        val acceptToken = AtomicBoolean(true)
        val exchanges = AtomicInteger()
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var page = "<!doctype html><title>Fixture authenticated</title><p>Ready</p>"
        private val worker = thread(isDaemon = true, name = "hp-auth-fixture") {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 2000
                        val input = client.getInputStream().bufferedReader()
                        val target = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        val headers = HashMap<String, String>()
                        for (i in 0..100) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (':' in line) headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        var extra = ""
                        val status = if (target.startsWith("/?token=")) {
                            exchanges.incrementAndGet()
                            if (acceptToken.get() && target.substringAfter("token=").substringBefore('&') == token.get()) {
                                extra = "Location: /\r\nSet-Cookie: $cookieName=${token.get()}; Path=/; HttpOnly; SameSite=Strict\r\n"
                                302
                            } else 401
                        } else if (headers["cookie"].orEmpty().split(';').any { it.trim() == "$cookieName=${token.get()}" }) 200 else 401
                        requests += "${if(target.startsWith("/?token=")) "bootstrap" else "page"}:$status"
                        val body = if (status == 200) page else "Authentication required"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status Fixture\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n$extra\r\n"
                        client.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    }
                } catch (_: java.io.IOException) { /* Closing the fixture or a browser socket is expected. */ }
            }
        }
        override fun close() { socket.close(); worker.join(2500) }
    }

    @Test fun externalLinksAndWindowOpenUseNewBrowserPagesWithoutLeavingDsh(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val directory = File(context.cacheDir, "dsh-links-${System.nanoTime()}")
        val manager = RuntimeManager(Fixture(context, directory))
        val pages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = Server()
        val public = BrowserFixtureServer()
        server.page = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><script>window.epoch=performance.timeOrigin;</script><a id=link href="${public.base}/ordinary?code=public-oauth" style="display:block;padding:24px">Open login</a><button id=popup onclick="window.open('${public.base}/popup?code=public-oauth','_blank')">Login popup</button></body></html>"""
        RuntimeManager::class.java.getDeclaredField("origin").apply { isAccessible = true }.set(manager, server.origin)
        RuntimeManager::class.java.getDeclaredField("authenticatedUrl").apply { isAccessible = true }.set(manager, "${server.origin}/?token=${server.token.get()}")
        manager.state.value = manager.state.value.copy(environment = EnvironmentState.READY, service = ServiceState.RUNNING)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var browser: DshWebView
        var created = false
        fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun js(source: String): String {
            val latch = CountDownLatch(1); var result = "null"
            ui { browser.view.evaluateJavascript(source) { result = it; latch.countDown() } }
            assertTrue(latch.await(5, TimeUnit.SECONDS)); return result
        }
        suspend fun await(message: String = "管理页连接", condition: () -> Boolean) {
            try { withTimeout(20000) { while (!condition()) delay(25) } }
            catch (_: TimeoutCancellationException) { fail("$message 超时；forwarded=${pages.size}; web=${manager.state.value.web}") }
        }
        try {
            ui {
                browser = DshWebView(activity, manager, { _, callback -> callback.onReceiveValue(null) }, { _, _ -> }, { file, _ -> file.delete() }, { pages.add(it) })
                created = true
                activity.setContentView(browser.view); browser.checkSupport(connectAfter = true)
            }
            await { manager.state.value.web == WebState.CONNECTED }
            val epoch = js("performance.timeOrigin")
            val position = org.json.JSONArray(js("JSON.stringify((function(){const r=document.querySelector('#link').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2];})())").let { org.json.JSONTokener(it).nextValue() as String })
            var x = 0f; var y = 0f
            ui {
                val location = IntArray(2); browser.view.getLocationOnScreen(location)
                @Suppress("DEPRECATION") val scale = browser.view.scale
                x = location[0] + position.getDouble(0).toFloat() * scale
                y = location[1] + position.getDouble(1).toFloat() * scale
            }
            val now = android.os.SystemClock.uptimeMillis()
            for ((action, time) in listOf(android.view.MotionEvent.ACTION_DOWN to now, android.view.MotionEvent.ACTION_UP to now + 80)) {
                val event = android.view.MotionEvent.obtain(now,time,action,x,y,0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
            }
            await("普通外链") { pages.size == 1 }
            assertEquals(public.base + "/ordinary?code=public-oauth", pages[0])
            js("document.querySelector('#popup').click();true")
            await("直接弹窗") { pages.size == 2 }
            assertEquals(public.base + "/popup?code=public-oauth", pages[1])
            js("setTimeout(()=>window.open('${public.base}/delayed?code=public-oauth','_blank'),20);true")
            await("异步外链弹窗") { pages.size == 3 }
            assertEquals(public.base + "/delayed?code=public-oauth", pages[2])
            js("window.open('${server.origin}/?token=not-for-browser','_blank');true")
            delay(300); assertEquals("Never export DSH bootstrap links", 3, pages.size)
            // 官方先预开空白弹窗、异步初始化后再赋值；13 秒超过旧弹窗存活上限。
            val official = "https://platform.deepseek.com/dsh/authorize?client_id=synthetic-native-test"
            js("window.oauthFixture=window.open('','_blank');setTimeout(()=>window.oauthFixture.location.assign('$official'),13000);true")
            await("预开空白官方授权弹窗") { pages.size == 4 }
            assertEquals(official, pages[3])
            // 官方主框架也可能在异步完成后跳转，不依赖已经失效的用户手势标志。
            js("setTimeout(()=>window.location.assign('$official'),20);true")
            await("主框架异步官方授权") { pages.size == 5 }
            assertEquals(official, pages[4])
            assertEquals(epoch, js("performance.timeOrigin"))
            assertEquals("No external content may load in the credential profile", 0, public.requests.get())
        } finally {
            ui { if (created) browser.destroy(); activity.finish() }
            manager.scope.cancel(); manager.scope.coroutineContext[Job]?.join()
            public.close(); server.close(); RuntimeManager.deleteTree(directory)
        }
    }

    @Test fun cookieRecoveryAndSameOriginTokenChangeAreBounded(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val directory = File(context.cacheDir, "web-auth-${System.nanoTime()}")
        val manager = RuntimeManager(Fixture(context, directory))
        val server = Server()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var browser: DshWebView? = null
        fun setTarget() {
            RuntimeManager::class.java.getDeclaredField("origin").apply { isAccessible = true }.set(manager, server.origin)
            RuntimeManager::class.java.getDeclaredField("authenticatedUrl").apply { isAccessible = true }
                .set(manager, "${server.origin}/?token=${server.token.get()}")
        }
        suspend fun await(message: String, predicate: () -> Boolean) {
            try { withTimeout(15000) { while (!predicate()) delay(25) } }
            catch (_: TimeoutCancellationException) { fail("$message; state=${manager.state.value.web}; exchanges=${server.exchanges.get()}; requests=${server.requests}") }
        }
        fun clearFixtureCookie() {
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.origin, "${server.cookieName}=; Path=/; Max-Age=0") { done.countDown() }
            }
            assertTrue("fixture cookie removal", done.await(5, TimeUnit.SECONDS))
        }
        try {
            setTarget()
            manager.state.value = manager.state.value.copy(environment = EnvironmentState.READY, service = ServiceState.RUNNING)
            instrumentation.runOnMainSync {
                browser = DshWebView(activity, manager, { _, callback -> callback.onReceiveValue(null) }, { _, _ -> }, { file, _ -> file.delete() })
                activity.setContentView(browser!!.view)
                browser!!.checkSupport(connectAfter = true)
            }
            await("initial authenticated page") { browser!!.support.value.state == WebViewSupportState.SUPPORTED && manager.state.value.web == WebState.CONNECTED }
            assertEquals(1, server.exchanges.get())

            clearFixtureCookie()
            instrumentation.runOnMainSync { browser!!.view.loadUrl(server.origin + "/") }
            await("401 must exchange the current launch URL once") { server.exchanges.get() == 2 && manager.state.value.web == WebState.CONNECTED }

            server.token.set("second-fixture-token")
            setTarget()
            instrumentation.runOnMainSync { browser!!.connect() }
            await("changed token at the same origin must reload") { server.exchanges.get() == 3 && manager.state.value.web == WebState.CONNECTED }

            server.acceptToken.set(false)
            server.token.set("rejected-fixture-token")
            setTarget()
            instrumentation.runOnMainSync { browser!!.connect() }
            await("invalid token must settle as expired") { server.exchanges.get() >= 5 && manager.state.value.web == WebState.AUTH_EXPIRED }
            delay(500)
            assertEquals("initial request plus exactly one retry", 5, server.exchanges.get())
            android.util.Log.i("TANUKI_PROBE", "WEB_AUTH cookie_recovery=true same_origin_token_change=true invalid_token_bounded=true")
        } finally {
            clearFixtureCookie()
            instrumentation.runOnMainSync { browser?.destroy(); activity.finish() }
            manager.scope.cancel(); manager.scope.coroutineContext[Job]?.join()
            server.close()
            RuntimeManager.deleteTree(directory)
        }
    }
}
