package com.tanuki

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.browser.AgentBrowserController
import com.tanuki.browser.BrowserPermissionGate
import com.tanuki.browser.RpcFailure
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Loopback-only fixtures. No model, bridge, credentials or external service is needed. */
class AgentBrowserControlTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var controller: AgentBrowserController
    private val client = UUID.randomUUID().toString()
    private lateinit var scratch: java.io.File
    @Before fun create() = ui {
        val context = instrumentation.targetContext
        scratch = java.io.File(context.cacheDir, "browser-control-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = scratch }
        controller = AgentBrowserController(isolated) { setOf("http://127.0.0.1:34875") }
        controller.setVisible(true)
    }
    @After fun destroy() { ui { controller.close() }; scratch.deleteRecursively() }
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun rpc(method: String, who: String = client, token: String = controller.connection.token,
                    origin: String? = null, path: String = "/rpc", verb: String = "POST",
                    params: JSONObject = JSONObject()): JSONObject {
        val body = JSONObject().put("clientId", who).put("method", method).put("params", params).toString().toByteArray()
        return Socket("127.0.0.1", controller.connection.port).use { socket ->
            socket.soTimeout = 8000
            val headers = "$verb $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                (origin?.let { "Origin: $it\r\n" } ?: "") + "Content-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().write(headers.toByteArray()); socket.getOutputStream().write(body)
            val response = socket.getInputStream().bufferedReader().readText()
            JSONObject(response.substringAfter("\r\n\r\n"))
        }
    }
    private fun code(response: JSONObject) = response.getJSONObject("error").getString("code")
    private fun grant() {
        assertTrue(rpc("request_control").getJSONObject("result").getBoolean("pending"))
        ui { controller.grant(client) }
        assertEquals(client, controller.state.value.authorizedClient)
    }
    @Test fun moreThanTwelveTabsCanBeCreatedByRpcAndRestored() {
        grant()
        repeat(14) { assertTrue("New tab must not have a fixed count limit", rpc("new_tab").getBoolean("ok")) }
        ui {
            val ids = controller.state.value.tabs.map { it.id }
            val selected = controller.state.value.activeTab
            val context = controller.webView.context
            assertEquals(15, ids.size)
            controller.close()
            controller = AgentBrowserController(context) { setOf("http://127.0.0.1:34875") }
            assertEquals(ids, controller.state.value.tabs.map { it.id })
            assertEquals(selected, controller.state.value.activeTab)
        }
    }
    @Test fun defaultNativePolicyIsCanonicalTouchPausesAndTakebackPersists() {
        ui { controller.setVisible(false); controller.activateDefaultControl() }
        val canonical = controller.connection.clientId
        assertEquals(canonical, controller.state.value.authorizedClient)
        assertEquals(1, controller.state.value.tabs.size)
        assertEquals(1, rpc("tabs", who = canonical).getJSONObject("result").getJSONArray("tabs").length())
        assertTrue(rpc("request_control", who = canonical).getJSONObject("result").getBoolean("authorized"))
        assertEquals("permission_required", code(rpc("tabs")))
        fun touch() = ui {
            controller.setVisible(true)
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            try { controller.webView.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(); assertTrue(controller.state.value.takeoverRequested)
        assertEquals("permission_required", code(rpc("tabs", who = canonical)))
        ui { controller.resolveTakeover(false) }
        assertEquals(canonical, controller.state.value.authorizedClient)
        touch(); ui { controller.resolveTakeover(true); controller.activateDefaultControl() }
        assertNull(controller.state.value.authorizedClient)
        assertTrue(controller.library.state.value.aiControlEnabled)
        assertNotNull(controller.state.value.idleReturnSeconds)
        ui { controller.takeControl() }
        assertNull(controller.state.value.idleReturnSeconds)
        assertFalse(controller.library.state.value.aiControlEnabled)
        ui {
            controller.close()
            val isolated = object : android.content.ContextWrapper(instrumentation.targetContext) { override fun getFilesDir() = scratch }
            controller = AgentBrowserController(isolated) { emptySet() }
            controller.activateDefaultControl(); controller.setVisible(true)
        }
        assertNull(controller.state.value.authorizedClient)
        ui { controller.grantConnection() }
        assertEquals(controller.connection.clientId, controller.state.value.authorizedClient)
        assertTrue(controller.library.state.value.aiControlEnabled)
    }
    @Test fun idleThirtySecondsReturnsTemporaryControlButNeverExplicitRevoke() {
        ui {
            controller.activateDefaultControl(); controller.setVisible(true)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, 1f, 1f, 0)
                try { controller.webView.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            controller.resolveTakeover(true); controller.setVisible(false)
        }
        assertNull(controller.state.value.authorizedClient)
        assertNotNull(controller.state.value.idleReturnSeconds)
        val deadline = android.os.SystemClock.elapsedRealtime() + 36_000
        while (controller.state.value.authorizedClient == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertEquals(controller.connection.clientId, controller.state.value.authorizedClient)
        ui { controller.takeControl() }
        // 真正等待超过 30 秒，确认显式撤销不会被默认恢复策略覆盖。
        Thread.sleep(31_000)
        assertNull(controller.state.value.authorizedClient)
        assertNull(controller.state.value.idleReturnSeconds)
        assertFalse(controller.library.state.value.aiControlEnabled)
    }
    @Test fun authenticationOriginAndEndpointAreStrict() {
        assertEquals("unauthorized", code(rpc("status", token = "incorrect")))
        assertEquals("origin_forbidden", code(rpc("status", origin = "null")))
        assertEquals("origin_forbidden", code(rpc("status", origin = "https://example.test")))
        assertEquals("endpoint_not_found", code(rpc("status", verb = "GET")))
        assertEquals("endpoint_not_found", code(rpc("status", path = "/rpc?x=1")))
        assertEquals("invalid_client", code(rpc("status", who = "not-a-uuid")))
        val status = rpc("status").getJSONObject("result")
        assertFalse(status.getBoolean("authorized")); assertFalse(status.has("url")); assertFalse(status.has("title"))
    }
    @Test fun requestsNeverGrantAndWrongClientCannotUseAnotherLease() {
        assertEquals("permission_required", code(rpc("snapshot")))
        assertTrue(rpc("request_control").getBoolean("ok"))
        assertNull(controller.state.value.authorizedClient)
        assertEquals("permission_required", code(rpc("screenshot")))
        ui { controller.grant(UUID.randomUUID().toString()) }
        assertNull(controller.state.value.authorizedClient)
        ui { controller.grant(client) }
        assertEquals("permission_required", code(rpc("snapshot", who = UUID.randomUUID().toString())))
        assertEquals("control_busy", code(rpc("request_control", who = UUID.randomUUID().toString())))
    }
    @Test fun visibilityAndTouchNeitherGrantNorRevokeExplicitPermission() {
        ui { controller.setVisible(false); controller.setVisible(true) }
        assertEquals("permission_required", code(rpc("back")))
        ui {
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            try { controller.webView.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        assertNull(controller.state.value.authorizedClient)
        grant()
        ui { controller.setVisible(false) }
        assertTrue(rpc("status").getJSONObject("result").getBoolean("authorized"))
        assertTrue(rpc("tabs").getBoolean("ok"))
        ui { controller.setVisible(true) }
        assertEquals(client, controller.state.value.authorizedClient)
        ui { controller.revoke() }
        assertEquals("permission_required", code(rpc("tabs")))
        assertTrue(rpc("request_control").getBoolean("ok"))
        ui { controller.setVisible(false); controller.grant(client) }
        assertNull(controller.state.value.authorizedClient)
        ui { controller.setVisible(true); controller.grant(client) }
        assertTrue(rpc("status").getJSONObject("result").getBoolean("authorized"))
    }
    @Test fun proactiveNativeGrantIsForKnownConnectionAndCannotBeInvokedByRpc() {
        assertEquals("unknown_method", code(rpc("grant")))
        ui { controller.grantConnection() }
        assertEquals(controller.connection.clientId, controller.state.value.authorizedClient)
        assertTrue(rpc("tabs", who = controller.connection.clientId).getBoolean("ok"))
        assertEquals("permission_required", code(rpc("tabs")))
        ui { controller.setVisible(false) }
        assertTrue(rpc("tabs", who = controller.connection.clientId).getBoolean("ok"))
        ui { controller.revoke() }
        assertEquals("permission_required", code(rpc("tabs", who = controller.connection.clientId)))
    }
    @Test fun queuedMainThreadWorkAndLateResponseTicketsDieOnRevoke() {
        val gate = BrowserPermissionGate()
        gate.visibility(true); gate.request(client); assertTrue(gate.grant(client))
        val ticket = gate.ticket(client)
        val epoch = gate.epoch()
        var executed = false
        val completed = CountDownLatch(1)
        ui {
            Handler(Looper.getMainLooper()).post {
                if (gate.valid(ticket)) executed = true
                completed.countDown()
            }
            gate.revoke()
            gate.request(client); assertTrue(gate.grant(client))
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertFalse(executed); assertFalse(gate.valid(ticket)); assertFalse(gate.current(epoch))
        assertTrue(gate.valid(gate.ticket(client)))
        gate.close()
        try { gate.ticket(client); fail("Closed gate granted a ticket") } catch (_: RpcFailure) { }
    }
    @Test fun realDomTypingClickScreenshotAndCookieIsolation() {
        check(instrumentation.targetContext.packageName.endsWith(".workspacecheck"))
        val context = instrumentation.targetContext
        val intent = android.content.Intent(context, MainActivity::class.java)
        androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            LocalPage().use { page ->
                scenario.onActivity { activity -> activity.setContentView(controller.webView); controller.setVisible(true) }
                ui {
                    android.webkit.CookieManager.getInstance().setCookie(page.url, "hp_management_fixture=private; Path=/")
                    androidx.webkit.WebViewCompat.getProfile(controller.webView).cookieManager.setCookie(page.url, "hp_browser_fixture=visible; Path=/")
                }
                grant()
                assertTrue(rpc("navigate", params = JSONObject().put("url", page.url)).getBoolean("ok"))
                await { controller.state.value.url == page.url && !controller.state.value.loading }
                var snapshot = rpc("snapshot").getJSONObject("result")
                assertTrue(snapshot.getString("text").contains("Browser fixture"))
                assertFalse(page.cookie.orEmpty().contains("hp_management_fixture"))
                assertTrue(page.cookie.orEmpty().contains("hp_browser_fixture"))
                val elements = snapshot.getJSONArray("elements")
                fun element(label: String): JSONObject = (0 until elements.length()).map { elements.getJSONObject(it) }
                    .first { it.getString("label") == label }
                fun params(label: String) = JSONObject().put("documentId", snapshot.getString("documentId"))
                    .put("elementId", element(label).getString("elementId"))
                assertTrue(rpc("type", params = params("Fixture input").put("text", "hello fixture")).getBoolean("ok"))
                assertTrue(rpc("click", params = params("Submit fixture")).getBoolean("ok"))
                await { page.submitted == "hello fixture" }
                val image = rpc("screenshot").getJSONObject("result")
                assertEquals("image/png", image.getString("mimeType"))
                assertTrue(image.getInt("width") in 1..1024)
                val bytes = android.util.Base64.decode(image.getString("base64"), android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(bitmap)
                val sampled = hashSetOf<Int>()
                for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 30))
                    for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 30)) sampled += bitmap.getPixel(x, y)
                assertTrue("Screenshot must contain rendered page, not a blank bitmap", sampled.size > 3)
                bitmap.recycle()
                val oldDocument = snapshot.getString("documentId")
                ui { controller.reload() }
                assertTrue(rpc("status").getJSONObject("result").getBoolean("authorized"))
                assertEquals("stale_document", code(rpc("click", params = JSONObject().put("documentId", oldDocument).put("elementId", "old"))))
                ui {
                    android.webkit.CookieManager.getInstance().setCookie(page.url, "hp_management_fixture=; Max-Age=0; Path=/")
                    androidx.webkit.WebViewCompat.getProfile(controller.webView).cookieManager.setCookie(page.url, "hp_browser_fixture=; Max-Age=0; Path=/")
                }
            }
        }
    }
    @Test fun fullDocumentTextAndOffscreenControlsCanBeReadInBoundedPages() {
        val longText = "row ".repeat(13_000)
        LocalPage().use { page ->
            page.htmlOverride = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><p>$longText</p>" +
                (0 until 150).joinToString("") { "<button>Button $it</button>" } +
                "<input type=password value='PUBLIC_PASSWORD_SENTINEL'><textarea>PUBLIC_DRAFT_SENTINEL</textarea>"
            val intent = android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            androidx.test.core.app.ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { it.setContentView(controller.webView) }
                grant()
                assertTrue(rpc("navigate", params=JSONObject().put("url",page.url)).getBoolean("ok"))
                await { !controller.state.value.loading && controller.state.value.url==page.url }
                val text = StringBuilder()
                var offset=0
                for(index in 0 until 4) {
                    val result=rpc("snapshot",params=JSONObject().put("offset",offset)).getJSONObject("result")
                    assertEquals("full_document",result.getString("scope"))
                    text.append(result.getString("text"))
                    assertFalse(result.toString().contains("PUBLIC_PASSWORD_SENTINEL"))
                    assertFalse(result.toString().contains("PUBLIC_DRAFT_SENTINEL"))
                    if(result.isNull("nextTextOffset")) break
                    val next=result.getInt("nextTextOffset"); assertTrue(next>offset); offset=next
                }
                assertEquals("Paging must not add/drop characters inside text nodes",longText + " " +
                    (0 until 150).joinToString("") { "Button $it " },text.toString())
                val first=rpc("snapshot").getJSONObject("result")
                assertEquals(100,first.getJSONArray("elements").length())
                val second=rpc("snapshot",params=JSONObject().put("elementOffset",first.getInt("nextElementOffset"))).getJSONObject("result")
                val controls=second.getJSONArray("elements")
                assertTrue((0 until controls.length()).any { controls.getJSONObject(it).optString("label")=="Button 149" })
                assertTrue(second.isNull("nextElementOffset"))
                assertFalse(second.getBoolean("scanTruncated"))
            }
        }
    }
    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.uptimeMillis() + 10000
        while (!condition() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(50)
        assertTrue("Fixture did not become ready", condition())
    }
    internal class LocalPage : AutoCloseable {
        private val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/fixture"
        @Volatile var cookie: String? = null
        @Volatile var submitted: String? = null
        @Volatile var htmlOverride: String? = null
        private val thread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                runCatching { socket.use {
                    it.soTimeout = 3000
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Cookie:", true)) cookie = line.substringAfter(':').trim()
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    if (request.startsWith("POST /submit")) {
                        val data = CharArray(length); var count = 0
                        while (count < length) { val n = reader.read(data, count, length - count); if (n < 0) break; count += n }
                        submitted = String(data, 0, count)
                    }
                    val html = htmlOverride ?: """<!doctype html><title>Browser fixture</title><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{background:#dce9ff;color:#152543;padding:20px}input,button{display:block;font-size:18px;margin:12px}</style><h1>Browser fixture</h1><input aria-label="Fixture input" id="input"><button onclick="fetch('/submit',{method:'POST',body:document.getElementById('input').value}).then(()=>document.getElementById('result').textContent='Submitted')">Submit fixture</button><div id="result"></div>"""
                    val body = html.toByteArray()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().write(body)
                } }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); thread.join(4000) }
    }
    @Test fun forbiddenUrlsAndStaleDocumentsFailWithoutNavigation() {
        grant()
        for (url in listOf("file:///etc/passwd", "content://fixture", "javascript:alert(1)",
                "http://127.0.0.1:${controller.connection.port}/rpc", "http://localhost:34875/",
                "http://127.0.0.1:34875/", "http://example.test:34875/")) {
            assertEquals("navigation_blocked", code(rpc("navigate", params = JSONObject().put("url", url))))
        }
        assertEquals("stale_document", code(rpc("click", params = JSONObject()
            .put("documentId", "old").put("elementId", "old"))))
    }
}
