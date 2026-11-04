package com.tanuki.browser

import android.content.Context
import android.util.AtomicFile
import android.webkit.URLUtil
import com.tanuki.runtime.FileTransfers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 仅供原生层使用，不通过浏览器 RPC 或 JavaScript 接口暴露。 */
data class BrowserBookmark(val id: String, val url: String, val title: String, val folder: String = "收藏夹")
data class BrowserDownload(val id: String, val name: String, val url: String, val mime: String,
    val status: String, val received: Long = 0, val total: Long = -1, val time: Long = System.currentTimeMillis(),
    val error: String? = null, val image: Boolean = false)
data class SavedBrowserTab(val id: String, val url: String, val title: String, val mode: String = BrowserPageMode.WINDOWS.name)
data class BrowserLibraryState(val history: List<BrowserVisit> = emptyList(), val bookmarks: List<BrowserBookmark> = emptyList(),
    val downloads: List<BrowserDownload> = emptyList(), val tabs: List<SavedBrowserTab> = emptyList(), val selectedTab: String? = null,
    val searchEngine: String = "BING", val aiControlEnabled: Boolean = true, val avoidBottomControls: Boolean = false)
data class BrowserDownloadRequest(val url: String, val name: String, val mime: String, val size: Long)

class BrowserLibrary(context: Context, val transfers: FileTransfers = FileTransfers { android.os.SystemClock.elapsedRealtime() }) {
    private val root = File(context.filesDir, "browser-data")
    private val directory = File(root, "downloads")
    private val metadata = File(root, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val slots = Semaphore(2)
    private val diskLock = Any()
    private var saveJob: Job? = null
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<BrowserLibraryState> = mutable.asStateFlow()
    private fun safeDirectory() {
        check(!Files.isSymbolicLink(root.toPath()) && !Files.isSymbolicLink(directory.toPath())) { "浏览器目录被重定向" }
        check(root.isDirectory || root.mkdirs()); check(directory.isDirectory || directory.mkdirs())
    }
    private fun load(): BrowserLibraryState = runCatching {
        safeDirectory()
        check(!Files.isSymbolicLink(metadata.toPath()))
        if (!metadata.exists() || metadata.length() > 16 * 1024 * 1024) return@runCatching BrowserLibraryState()
        val json = JSONObject(AtomicFile(metadata).openRead().bufferedReader().use { it.readText() })
        fun rows(key: String, max: Int = Int.MAX_VALUE): List<JSONObject> = json.optJSONArray(key)?.let { a ->
            (0 until minOf(max, a.length())).mapNotNull { a.optJSONObject(it) }
        }.orEmpty()
        BrowserLibraryState(
            history = rows("history", 500).map { BrowserVisit(it.getString("url"), it.optString("title"), it.optLong("time")) },
            bookmarks = rows("bookmarks", 500).map { BrowserBookmark(it.getString("id"), it.getString("url"), it.optString("title"), it.optString("folder", "收藏夹")) },
            downloads = rows("downloads", 200).map { row ->
                val status = row.optString("status")
                BrowserDownload(row.getString("id"), row.getString("name"), row.optString("url"), row.optString("mime"),
                    if (status in setOf("等待中", "下载中")) "已中断" else status, row.optLong("received"), row.optLong("total", -1),
                    row.optLong("time"), if (status in setOf("等待中", "下载中")) "上次运行中断，请重新下载" else row.optString("error").ifBlank { null }, row.optBoolean("image"))
            },
            tabs = rows("tabs").map { SavedBrowserTab(it.getString("id"), it.optString("url"), it.optString("title"), BrowserPageMode.from(it.optString("mode")).name) },
            selectedTab = json.optString("selectedTab").ifBlank { null }, searchEngine = BrowserSearchEngine.from(json.optString("searchEngine")).name,
            aiControlEnabled = json.optBoolean("aiControlEnabled", true), avoidBottomControls = json.optBoolean("avoidBottomControls", false))
    }.getOrElse { BrowserLibraryState() }
    @Synchronized private fun update(persist: Boolean = true, block: (BrowserLibraryState) -> BrowserLibraryState) {
        val previous = mutable.value
        val next = block(previous)
        if (next == previous) return
        mutable.value = next
        if (persist) {
            saveJob?.cancel()
            saveJob = scope.launch { delay(150); persist() }
        }
    }
    private fun persist() = synchronized(diskLock) {
        runCatching {
            safeDirectory()
            check(listOf(metadata, File(metadata.path + ".new"), File(metadata.path + ".bak")).none { Files.isSymbolicLink(it.toPath()) })
            val s = state.value
            val json = JSONObject().put("history", JSONArray(s.history.map { JSONObject().put("url", it.url).put("title", it.title).put("time", it.time) }))
                .put("bookmarks", JSONArray(s.bookmarks.map { JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("folder", it.folder) }))
                .put("downloads", JSONArray(s.downloads.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("mime", it.mime)
                    .put("status", it.status).put("received", it.received).put("total", it.total).put("time", it.time).put("error", it.error).put("image", it.image) }))
                .put("tabs", JSONArray(s.tabs.map { JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("mode", it.mode) }))
                .put("selectedTab", s.selectedTab).put("searchEngine", s.searchEngine).put("aiControlEnabled", s.aiControlEnabled)
                .put("avoidBottomControls", s.avoidBottomControls)
            val atomic = AtomicFile(metadata); val stream = atomic.startWrite()
            try { android.system.Os.fchmod(stream.fd, 384); stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) }
            catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
    }
    fun visit(url: String, title: String) = update { it.copy(history = (listOf(BrowserVisit(url, title.take(200))) + it.history.filterNot { v -> v.url == url }).take(500)) }
    fun clearHistory() = update { it.copy(history = emptyList()) }
    fun saveTabs(tabs: List<SavedBrowserTab>, selected: String) = update { it.copy(tabs = tabs, selectedTab = selected) }
    fun bookmark(url: String, title: String, folder: String) = update {
        require(url.length <= 8192 && URI(url).scheme?.lowercase() in setOf("http", "https"))
        val previous = it.bookmarks.firstOrNull { b -> b.url == url }
        val next = BrowserBookmark(previous?.id ?: UUID.randomUUID().toString(), url, title.trim().take(200).ifBlank { url }, folder.trim().take(80).ifBlank { "收藏夹" })
        it.copy(bookmarks = (listOf(next) + it.bookmarks.filterNot { b -> b.url == url }).take(500))
    }
    fun setSearchEngine(engine: BrowserSearchEngine) = update { it.copy(searchEngine = engine.name) }
    fun setAvoidBottomControls(enabled: Boolean) = update { it.copy(avoidBottomControls = enabled) }
    fun setAiControlEnabled(enabled: Boolean) { update { it.copy(aiControlEnabled = enabled) }; flush() }
    fun removeBookmark(id: String) = update { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == id }) }
    private fun change(id: String, persist: Boolean = true, block: (BrowserDownload) -> BrowserDownload) = update(persist) {
        it.copy(downloads = it.downloads.map { row -> if (row.id == id) block(row) else row })
    }
    private fun append(record: BrowserDownload) = update {
        check(it.downloads.size < 200) { "下载记录已满，请先删除不需要的下载" }
        it.copy(downloads = listOf(record) + it.downloads)
    }
    fun download(request: BrowserDownloadRequest, allowed: (String) -> Boolean, cookie: (String) -> String?): String {
        check(allowed(request.url)) { "不允许下载此地址" }
        check(request.size <= MAX_BYTES) { "单文件下载上限为 256 MiB" }
        val id = UUID.randomUUID().toString()
        val name = request.name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(140).ifBlank { "download" }
        append(BrowserDownload(id, name, request.url, request.mime.take(100).ifBlank { "application/octet-stream" }, "等待中", total = request.size))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val partial = File(directory, "$id.part")
            try {
                slots.withPermit {
                    safeDirectory(); check(!Files.isSymbolicLink(partial.toPath()))
                    transfers.track("浏览器下载：$name", request.size) { transfer ->
                        var address = request.url
                        var count = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(allowed(address)) { "下载地址不允许访问" }
                            val connection = URL(address).openConnection() as HttpURLConnection
                            connections[id] = connection
                            connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 15000
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            cookie(address)?.let { connection.setRequestProperty("Cookie", it) }
                            try {
                                val status = connection.responseCode
                                if (status in setOf(301, 302, 303, 307, 308)) {
                                    check(++count <= 5) { "下载重定向过多" }
                                    val next = URL(URL(address), connection.getHeaderField("Location") ?: error("无效的下载跳转")).toString()
                                    check(!(address.startsWith("https:", true) && !next.startsWith("https:", true))) { "已阻止不安全的下载跳转" }
                                    check(allowed(next)) { "已阻止下载跳转" }; address = next; continue
                                }
                                check(status == 200) { "下载失败 HTTP $status" }
                                val total = if (connection.contentEncoding.isNullOrBlank() || connection.contentEncoding == "identity") connection.contentLengthLong else -1
                                check(total <= MAX_BYTES) { "单文件下载上限为 256 MiB" }
                                check(directory.usableSpace > maxOf(0, total) + 32 * 1024 * 1024) { "可用空间不足" }
                                change(id) { it.copy(status = "下载中", total = total) }
                                var received = 0L; var notified = 0L
                                partial.outputStream().use { output ->
                                    connection.inputStream.use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val size = input.read(buffer); if (size < 0) break
                                            received += size
                                            check(received <= MAX_BYTES) { "下载超过 256 MiB，已停止" }
                                            output.write(buffer, 0, size)
                                            val now = android.os.SystemClock.elapsedRealtime()
                                            if (now - notified >= 100) {
                                                transfers.update(transfer, received, total)
                                                change(id, false) { it.copy(received = received, total = total) }; notified = now
                                            }
                                        }
                                    }
                                    output.fd.sync()
                                }
                                currentCoroutineContext().ensureActive()
                                check(total < 0 || received == total) { "下载内容不完整" }
                                check(partial.renameTo(File(directory, id))) { "无法保存下载" }
                                transfers.update(transfer, received, total)
                                change(id) { it.copy(status = "已完成", received = received, total = total, error = null) }
                                break
                            } finally { connections.remove(id, connection); connection.disconnect() }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                change(id) { it.copy(status = "已取消", error = null) }; throw cancelled
            } catch (error: Exception) {
                val active = currentCoroutineContext().isActive
                change(id) { it.copy(status = if (active) "失败" else "已取消", error = if (active) "下载未完成，请检查网络、空间或重新下载" else null) }
            } finally { partial.delete(); jobs.remove(id) }
        }
        jobs[id] = job; job.start(); return id
    }
    fun saveScreenshot(bytes: ByteArray): String {
        val id = UUID.randomUUID().toString()
        append(BrowserDownload(id, "网页截图-${System.currentTimeMillis()}.png", "", "image/png", "等待中", total = bytes.size.toLong(), image = true))
        scope.launch {
            try {
                transfers.track("保存网页截图", bytes.size.toLong()) { transfer ->
                    safeDirectory(); File(directory, id).outputStream().use { it.write(bytes); it.fd.sync() }
                    transfers.update(transfer, bytes.size.toLong(), bytes.size.toLong())
                    change(id) { it.copy(status = "已完成", received = bytes.size.toLong()) }
                }
            } catch (_: Exception) { change(id) { it.copy(status = "失败", error = "无法保存截图，请检查可用空间") } }
        }
        return id
    }
    fun export(context: Context, id: String, destination: android.net.Uri, notice: (String) -> Unit) {
        scope.launch {
            val message = try {
                val source = file(id)
                transfers.track("导出浏览器文件", source.length()) { transfer ->
                    context.contentResolver.openOutputStream(destination, "w")!!.use { output ->
                        source.inputStream().use { input -> transfers.copy(input, output, transfer, source.length()) }
                    }
                }
                "文件已导出"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "导出失败，应用内原文件仍保留" }
            withContext(Dispatchers.Main) { notice(message) }
        }
    }
    fun cancel(id: String) {
        jobs[id]?.cancel(); connections[id]?.disconnect()
        change(id) { if (it.status in setOf("等待中", "下载中")) it.copy(status = "已取消", error = null) else it }
    }
    fun cancelAll() { jobs.keys.toList().forEach(::cancel) }
    fun file(id: String): File {
        safeDirectory()
        check(id.matches(Regex("[a-f0-9-]{36}")))
        check(state.value.downloads.any { it.id == id && it.status == "已完成" }) { "文件尚未下载完成" }
        return File(directory, id).also { check(it.isFile && !Files.isSymbolicLink(it.toPath())) { "下载文件不存在" } }
    }
    fun removeDownload(id: String) {
        check(jobs[id] == null) { "请先取消下载并等待取消完成" }
        check(id.matches(Regex("[a-f0-9-]{36}")))
        safeDirectory(); File(directory, id).delete(); File(directory, "$id.part").delete()
        update { it.copy(downloads = it.downloads.filterNot { d -> d.id == id }) }
    }
    fun flush() { persist() }
    fun close() { cancelAll(); persist(); scope.cancel() }
    companion object { const val MAX_BYTES = 256L * 1024 * 1024 }
}
