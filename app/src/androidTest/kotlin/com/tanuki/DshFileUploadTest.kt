package com.tanuki

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.RuntimeManager
import com.tanuki.runtime.ServiceState
import com.tanuki.web.DshWebView
import com.tanuki.web.DshFileChooser
import com.tanuki.web.OriginPolicy
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 只使用临时管理页和自建文本，不读取真实 DSH 会话或设备文档。 */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class DshFileUploadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue(message, condition())
    }

    private class UploadServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${socket.localPort}"
        val bodies = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        @Volatile var page = "<!doctype html><title>Upload fixture</title>"
        private val worker = thread(isDaemon = true, name = "dsh-file-fixture") {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n") && head.length < 16384) {
                        val value = input.read(); if (value < 0) break
                        head.append(value.toChar())
                    }
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    check(length in 0..65536)
                    val bytes = ByteArray(length); var offset = 0
                    while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                    val upload = head.startsWith("POST /upload ")
                    if (upload) bodies += bytes
                    val response = (if (upload) "OK" else page).toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: ${if (upload) "text/plain" else "text/html"}; charset=utf-8\r\nContent-Length: ${response.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                        write(response)
                    }
                }
            } catch (_: Exception) { /* 夹具关闭或网页取消请求时停止当前连接。 */ }
        }
        override fun close() { socket.close(); worker.join(5500) }
    }

    private inner class Fixture(private val systemPicker: Boolean = false) : AutoCloseable {
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "dsh-upload-${System.nanoTime()}")
        val manager = RuntimeManager(object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        })
        val server = UploadServer()
        val selected = AtomicReference<ValueCallback<Array<Uri>>?>()
        val intent = AtomicReference<Intent?>()
        val createdUris = mutableListOf<Uri>()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var browser: DshWebView
        var disposed = false
        init {
            check(context.packageName.endsWith(".workspacecheck"))
            RuntimeManager::class.java.getDeclaredField("origin").apply { isAccessible = true }.set(manager, server.origin)
            RuntimeManager::class.java.getDeclaredField("authenticatedUrl").apply { isAccessible = true }.set(manager, server.origin)
            manager.state.value = manager.state.value.copy(service = ServiceState.RUNNING)
            ui {
                (activity.application as TanukiApp).hideBrowserOverlay()
                browser = DshWebView(activity, manager, { request, callback ->
                    intent.set(request); selected.set(callback)
                    if (systemPicker) MainActivity::class.java.getDeclaredMethod("chooseDshFiles", Intent::class.java, ValueCallback::class.java)
                        .apply { isAccessible = true }.invoke(activity, request, callback)
                }, { _, _ -> }, { _, _ -> })
                activity.setContentView(browser.view)
                // 真实网络导航同时提供可信原生URL与文档origin，不能用about:blank历史记录绕过来源检查。
                server.page = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><input id="file" type="file" accept="text/plain" multiple style="margin:40px;width:250px;height:80px"><script>
                    window.uploadResult='waiting';
                    window.fixtureClicks=0;document.querySelector('#file').addEventListener('click',()=>window.fixtureClicks++);
                    document.querySelector('#file').onchange=async function(){try{
                      window.fileTexts=await Promise.all(Array.from(this.files,f=>f.text()));
                      const workerSource="onmessage=e=>{const x=new XMLHttpRequest();x.open('POST',e.data.url);x.withCredentials=true;x.setRequestHeader('Content-Type','application/octet-stream');x.onload=()=>postMessage({status:x.status,body:x.responseText});x.onerror=()=>postMessage({error:true});x.send(e.data.file);}";
                      const workerUrl=URL.createObjectURL(new Blob([workerSource],{type:'application/javascript'}));
                      try{for(const file of this.files){await new Promise((resolve,reject)=>{
                        const worker=new Worker(workerUrl,{name:'dsh-file-upload'});
                        worker.onmessage=e=>{worker.terminate();e.data.status===200?resolve(e.data.body):reject(Error('upload'));};
                        worker.onerror=()=>{worker.terminate();reject(Error('worker'));};
                        worker.postMessage({file,url:location.origin+'/upload'});
                      });}window.uploadResult='OK';}finally{URL.revokeObjectURL(workerUrl);}
                    }catch(e){window.uploadResult='error:'+e.name;}};
                </script>"""
                browser.view.loadUrl(server.origin + "/")
            }
            await("合成上传页加载") { js("document.readyState==='complete' && !!document.querySelector('#file')") == "true" }
            ui { assertTrue("原生管理页URL必须同源：${browser.view.url}", OriginPolicy.sameOrigin(browser.view.url.orEmpty(), server.origin)) }
            await("管理WebView可见并取得窗口焦点") {
                var ready = false
                ui { ready = browser.view.isShown && browser.view.hasWindowFocus() && browser.view.width > 0 && browser.view.height > 0 }
                ready
            }
            val drawn = CountDownLatch(1)
            ui { browser.view.postVisualStateCallback(1, object : android.webkit.WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { drawn.countDown() }
            }) }
            assertTrue("合成上传页完成首帧绘制", drawn.await(5, TimeUnit.SECONDS))
        }
        fun js(source: String): String {
            val latch = CountDownLatch(1); var result = "null"
            ui { browser.view.evaluateJavascript(source) { result = it; latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS)); return result
        }
        fun document(name: String, text: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/TanukiUploadTests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            createdUris += uri
            context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        }
        fun clickFile() {
            selected.set(null)
            val bounds = JSONArray(JSONTokener(js("JSON.stringify((()=>{const r=document.querySelector('#file').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})())")).nextValue() as String)
            var x = 0f; var y = 0f
            val viewportWidth = js("window.innerWidth").toFloat()
            ui {
                val location = IntArray(2); browser.view.getLocationOnScreen(location)
                val scale = browser.view.width / viewportWidth
                x = location[0] + bounds.getDouble(0).toFloat() * scale
                y = location[1] + bounds.getDouble(1).toFloat() * scale
            }
            val now = SystemClock.uptimeMillis()
            for ((action, at) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 60)) {
                val event = MotionEvent.obtain(now, at, action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            try { await("真实文件输入框触发选择") { selected.get() != null } }
            catch (error: AssertionError) {
                var native = ""
                ui {
                    val bounds = android.graphics.Rect(); browser.view.getGlobalVisibleRect(bounds)
                    native = "url=${browser.view.url}; origin=${server.origin}; shown=${browser.view.isShown}; focus=${browser.view.hasWindowFocus()}; size=${browser.view.width}x${browser.view.height}; rect=$bounds"
                }
                throw AssertionError("$native; tap=$x,$y; page=${js("JSON.stringify({origin:location.origin,width:innerWidth,clicks:window.fixtureClicks,result:window.uploadResult})")}; active=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}", error)
            }
        }
        override fun close() {
            ui { if (!disposed) browser.destroy(); activity.finish() }
            manager.scope.cancel(); server.close()
            createdUris.forEach { context.contentResolver.delete(it, null, null) }
            directory.deleteRecursively()
        }
    }

    @Test fun selectedContentFilesReachDomAndWorkerUpload() {
        Fixture().use { f ->
            val first = "Tanuki 合成上传 A\n"; val second = "Synthetic upload B\n"
            val uris = arrayOf(f.document("upload-a-${System.nanoTime()}.txt", first), f.document("upload-b-${System.nanoTime()}.txt", second))
            f.clickFile()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, f.intent.get()!!.action)
            assertTrue(f.intent.get()!!.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            ui { f.selected.getAndSet(null)!!.onReceiveValue(uris) }
            try { await("DOM读取并上传合成文件") { f.js("window.uploadResult") == "\"OK\"" } }
            catch (error: AssertionError) { throw AssertionError("合成上传状态=${f.js("window.uploadResult")}; DOM=${f.js("JSON.stringify(window.fileTexts)")}; POST=${f.server.bodies.size}", error) }
            val texts = JSONArray(JSONTokener(f.js("JSON.stringify(window.fileTexts)")).nextValue() as String)
            assertEquals(first, texts.getString(0)); assertEquals(second, texts.getString(1))
            assertEquals(listOf(first, second), f.server.bodies.map { it.toString(Charsets.UTF_8) })
            assertEquals("text/plain", f.intent.get()!!.type)
            ui { assertFalse(f.browser.view.settings.allowFileAccess) }
        }
    }

    private fun params() = object : WebChromeClient.FileChooserParams() {
        override fun getMode() = MODE_OPEN
        override fun getAcceptTypes() = arrayOf("text/plain")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT)
    }

    @Test fun clipDataOnlyResultDeliversAllFilesThroughMainActivity() {
        Fixture().use { f ->
            val contents = listOf("ClipData 附件甲\n", "ClipData attachment B\n")
            val uris = contents.mapIndexed { index, text -> f.document("clip-$index-${System.nanoTime()}.txt", text) }
            val clips = ClipData.newRawUri("合成附件", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
            val result = Intent().apply { clipData = clips }
            // 记录旧适配器是否会丢弃多选结果，不把未来内核修好该API误判为产品失败。
            val legacyCount = WebChromeClient.FileChooserParams.parseResult(android.app.Activity.RESULT_OK, result)?.size ?: 0
            android.util.Log.i("TANUKI_PROBE", "UPLOAD_CLIPDATA legacy_count=$legacyCount selected_count=2")
            assertArrayEquals(uris.toTypedArray(), DshFileChooser.result(android.app.Activity.RESULT_OK, result))
            assertNull(DshFileChooser.result(android.app.Activity.RESULT_CANCELED, result))
            assertArrayEquals(uris.toTypedArray(), DshFileChooser.result(android.app.Activity.RESULT_OK, Intent(result).setData(uris[0])))
            f.clickFile()
            ui {
                MainActivity::class.java.getDeclaredField("filesCallback").apply { isAccessible = true }.set(f.activity, f.selected.get())
                MainActivity::class.java.getDeclaredMethod("finishDshFiles", Int::class.javaPrimitiveType, Intent::class.java)
                    .apply { isAccessible = true }.invoke(f.activity, android.app.Activity.RESULT_OK, result)
            }
            await("ClipData结果经MainActivity上传全部附件") { f.server.bodies.size == 2 }
            assertEquals(contents, f.server.bodies.map { it.toString(Charsets.UTF_8) })
            val texts = JSONArray(JSONTokener(f.js("JSON.stringify(window.fileTexts)")).nextValue() as String)
            assertEquals(contents, (0 until texts.length()).map { texts.getString(it) })
        }
    }

    @Test fun systemDocumentPickerReturnsChosenBytesThroughMainActivity() {
        Fixture(systemPicker = true).use { f ->
            val name = "tanuki-upload-${System.nanoTime()}.txt"
            val content = "Synthetic SAF upload\n"
            f.document(name, content)
            f.js("document.querySelector('#file').multiple=false;true")
            f.clickFile()
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.text?.toString() == name) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            try {
                await("系统文件选择器显示合成文档") { find(instrumentation.uiAutomation.rootInActiveWindow) != null }
                var row = find(instrumentation.uiAutomation.rootInActiveWindow)!!
                while (!row.isClickable && row.parent != null) row = row.parent
                assertTrue(row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await("系统选择结果回到管理页并完成上传") { f.server.bodies.isNotEmpty() }
                assertEquals(content, f.server.bodies.single().toString(Charsets.UTF_8))
            } finally {
                if (f.server.bodies.isEmpty()) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }

    @Test fun cancelledAndNavigatedRequestsCannotDeliverStaleFiles() {
        Fixture().use { f ->
            var calls = 0; var result: Array<Uri>? = null
            fun request() = ui {
                assertTrue(f.browser.view.webChromeClient!!.onShowFileChooser(f.browser.view, ValueCallback { calls++; result = it }, params()))
            }
            request(); val first = f.selected.get()!!
            ui { first.onReceiveValue(null); first.onReceiveValue(arrayOf(Uri.parse("content://media/external/file/1"))) }
            assertEquals(1, calls); assertNull(result)
            request(); val stale = f.selected.get()!!
            ui { f.browser.view.loadUrl(f.server.origin + "/next") }
            await("导航取消选择") { calls == 2 }
            ui { stale.onReceiveValue(arrayOf(Uri.parse("content://media/external/file/1"))) }
            assertEquals(2, calls); assertNull(result)
            request(); ui { f.browser.destroy(); f.disposed = true }
            assertEquals(3, calls); assertNull(result)
        }
    }

    @Test fun foreignPagesAndPrivateFileUrisAreRejected() {
        Fixture().use { f ->
            for (uri in listOf(Uri.parse("file:///data/data/${f.context.packageName}/files/private"), Uri.parse("content://${f.context.packageName}.updates/application_updates/private.apk"))) {
                var called = false; var result: Array<Uri>? = null
                ui {
                    f.browser.view.webChromeClient!!.onShowFileChooser(f.browser.view, ValueCallback { called = true; result = it }, params())
                    f.selected.get()!!.onReceiveValue(arrayOf(uri))
                }
                assertTrue(called); assertNull(result)
            }
            ui { f.browser.view.loadDataWithBaseURL("https://untrusted.example/", "<title>Foreign</title>", "text/html", "UTF-8", "https://untrusted.example/") }
            await("外部页面切换") { f.js("location.origin") == "\"https://untrusted.example\"" }
            f.selected.set(null); var called = false
            ui { f.browser.view.webChromeClient!!.onShowFileChooser(f.browser.view, ValueCallback { called = true; assertNull(it) }, params()) }
            assertTrue(called); assertNull(f.selected.get())
        }
    }
}
