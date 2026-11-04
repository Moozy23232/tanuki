package com.tanuki

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.browser.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class BrowserLibraryTest {
    private fun fixture(block: (android.content.Context, BrowserLibrary) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "browser-library-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val library = BrowserLibrary(context)
        try { block(context, library) } finally { library.close(); directory.deleteRecursively() }
    }
    private fun await(block: () -> Boolean) {
        repeat(200) { if (block()) return; Thread.sleep(40) }; assertTrue("Browser task did not finish", block())
    }
    @Test fun nativeLibraryAndPagePreferencesPersistWithoutAnyPermission() = fixture { context, library ->
        library.visit("https://example.test/one", "One")
        library.bookmark("https://example.test/one", "Saved one", "Research")
        library.setSearchEngine(BrowserSearchEngine.DUCKDUCKGO)
        assertFalse(library.state.value.avoidBottomControls)
        library.setAvoidBottomControls(true)
        library.saveTabs(listOf(SavedBrowserTab("fixture", "https://example.test/one", "One", "MAC")), "fixture")
        library.flush()
        val reopened = BrowserLibrary(context)
        try {
            assertEquals("Research", reopened.state.value.bookmarks.single().folder)
            assertEquals("One", reopened.state.value.history.single().title)
            assertEquals("MAC", reopened.state.value.tabs.single().mode)
            assertEquals("DUCKDUCKGO", reopened.state.value.searchEngine)
            assertTrue(reopened.state.value.avoidBottomControls)
            reopened.clearHistory(); reopened.flush()
            assertTrue(reopened.state.value.history.isEmpty())
            assertEquals(1, reopened.state.value.bookmarks.size)
        } finally { reopened.close() }
    }
    @Test fun downloadProgressCompleteLengthCancelAndRemoval() = fixture { _, library ->
        BrowserFixtureServer().use { server ->
            val id = library.download(BrowserDownloadRequest(server.base + "/file", "fixture.txt", "text/plain", -1), { it.startsWith(server.base + "/") }, { null })
            await { library.state.value.downloads.first { it.id == id }.status == "已完成" }
            assertEquals(BrowserFixtureServer.FILE_BODY, library.file(id).readText())
            val row = library.state.value.downloads.first { it.id == id }
            assertEquals(row.total, row.received)
            await { library.transfers.state.value.isEmpty() }
            library.removeDownload(id); assertFalse(library.state.value.downloads.any { it.id == id })
            val slow = library.download(BrowserDownloadRequest(server.base + "/slow", "slow.txt", "text/plain", -1), { it.startsWith(server.base + "/") }, { null })
            await { library.state.value.downloads.first { it.id == slow }.received > 0 }
            assertTrue(library.transfers.state.value.isNotEmpty())
            library.cancel(slow)
            await { library.state.value.downloads.first { it.id == slow }.status == "已取消" && library.transfers.state.value.isEmpty() }
            assertThrows(IllegalStateException::class.java) { library.file(slow) }
        }
    }
    @Test fun shortBodiesAndForbiddenRedirectsNeverBecomeCompletedFiles() = fixture { _, library ->
        BrowserFixtureServer().use { server -> BrowserFixtureServer().use { private ->
            server.redirect = private.base + "/file"
            val allowed: (String) -> Boolean = { it.startsWith(server.base + "/") }
            for (route in listOf("/short", "/redirect")) {
                val id = library.download(BrowserDownloadRequest(server.base + route, "reject.txt", "text/plain", -1), allowed, { null })
                await { library.state.value.downloads.first { it.id == id }.status == "失败" }
                assertThrows(IllegalStateException::class.java) { library.file(id) }
            }
            assertEquals(0, private.requests.get())
        } }
    }
}

/** Local public page/download fixtures, no model or external network. */
internal class BrowserFixtureServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val base = "http://127.0.0.1:${server.localPort}"
    val requests = AtomicInteger()
    @Volatile var redirect = "$base/file"
    @Volatile var userAgent = ""
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task, "browser-fixture-client").apply { isDaemon = true } }
    private val thread = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            pool.execute { runCatching { socket.use { connection ->
                connection.soTimeout = 4000
                val reader = connection.getInputStream().bufferedReader()
                val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty().substringBefore('?')
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("User-Agent:", true)) userAgent = line.substringAfter(':').trim()
                }
                requests.incrementAndGet()
                val output = connection.getOutputStream()
                if (path == "/redirect") { output.write("HTTP/1.1 302 Found\r\nLocation: $redirect\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use }
                val binary = path in setOf("/file", "/short", "/slow")
                val data = if (binary) FILE_BODY.toByteArray() else when (path) { "/full" -> FULL_PAGE_HTML; "/dsh" -> DSH_HTML; else -> HTML }.toByteArray()
                val length = when (path) { "/short" -> data.size + 100; "/slow" -> 1_000_000; else -> data.size }
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: ${if (binary) "application/octet-stream" else "text/html; charset=utf-8"}\r\nContent-Length: $length\r\n" +
                    (if (binary) "Content-Disposition: attachment; filename=browser-fixture.txt\r\n" else "") + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                if (path == "/slow") repeat(1000) { output.write(ByteArray(1000) { 65 }); output.flush(); Thread.sleep(30) }
                else output.write(data)
            } } }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { server.close(); pool.shutdownNow(); thread.join(2000) }
    companion object {
        const val FILE_BODY = "Browser download fixture\nOnly public test content.\n"
        const val DSH_HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;width:100%;background:#f3f6fa;color:#172b4d;font:16px sans-serif}h1{font-size:24px}button{padding:12px}</style></head><body><h1>DSH 紧凑布局测试</h1><p>整个界面缩小，而不是只改字体。</p><button>操作按钮</button><script>window.marker='kept';</script></body></html>"""
        const val FULL_PAGE_HTML = """<!doctype html><html><head><title>Full-page fixture</title><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=yes"><style>html,body{margin:0}#page{position:relative;width:1440px;height:2400px;background:#f2f5fa;color:#172b4d;font:20px sans-serif}#end{position:absolute;right:0;bottom:0;width:80px;height:80px;background:#d81b60;color:white}</style></head><body><main id=page><h1>页面顶部内容</h1><p>即使窗口很小，AI 也必须读到整页。</p><p id=bottom style="position:absolute;bottom:100px">页面底部离屏内容</p><button id=far-action style="position:absolute;bottom:120px;right:0" onclick="this.dataset.clicked='yes'">页面底部操作</button><div id=end>END</div></main></body></html>"""
        const val HTML = """<!doctype html><html><head><title>Browser laboratory</title><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=yes"></head><body style="background:#dce9ff;color:#172b4d;font:18px sans-serif;padding:20px"><h1>Browser laboratory</h1><div style="height:60px;background:#ee7733">Public orange band</div><div style="height:60px;background:#22aa88">Public green band</div><p>needle alpha — needle beta — needle gamma</p><input aria-label="Draft fixture" id="draft" placeholder="Retained draft"><p><a href="/file" download="browser-fixture.txt">下载测试文件</a></p><p><a href="/second" target="_blank">Open second page</a></p><div style="height:800px">Long page for zoom and scrolling</div></body></html>"""
    }
}
