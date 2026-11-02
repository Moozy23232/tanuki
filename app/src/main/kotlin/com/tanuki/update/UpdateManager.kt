package com.tanuki.update

import com.tanuki.runtime.*
import com.tanuki.storage.DataSnapshots
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val integrity: String, val source: String, val node: String, val notes: String, val verified: Boolean)
class UpdateManager(private val manager: RuntimeManager) {
    val releases = MutableStateFlow<List<Release>>(emptyList())
    private var job: Job? = null
    val hasActiveOperation: Boolean get() = job?.isActive == true
    fun cancel() { job?.cancel() }
    private fun keepForeground():Boolean = try {
        manager.context.startForegroundService(android.content.Intent(manager.context,RuntimeService::class.java).setAction("KEEPALIVE"));true
    }catch(t:Throwable){manager.fail(t);manager.state.update { it.copy(update=UpdateState.FAILED) };false}
    suspend fun awaitIdle() { job?.join() }
    fun check() {
        if (job?.isActive == true) return
        job = manager.scope.launch {
            manager.state.update { it.copy(update = UpdateState.CHECKING, stage = "获取官方 DSH 版本列表", error = "", download = null, bytes = 0, total = -1, phasePercent = null) }
            try {
                val connection = URL("https://registry.npmjs.org/@deepseek-ai%2Fdsh").openConnection() as HttpURLConnection
                connection.connectTimeout = 20000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
                val metadata = try {
                    check(connection.responseCode == 200) { "官方版本检查 HTTP ${connection.responseCode}" }
                    val bytes = connection.inputStream.use { it.readBytesLimited(8 * 1024 * 1024) }
                    JSONObject(bytes.toString(Charsets.UTF_8))
                } finally { connection.disconnect() }
                val versions = metadata.getJSONObject("versions")
                val list = versions.keys().asSequence().filter { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")) }.toList().sortedWith { a,b -> VersionOrder.compare(b,a) }.take(40).map { version ->
                    val item = versions.getJSONObject(version); val dist = item.getJSONObject("dist")
                    val source = dist.getString("tarball")
                    require(URL(source).protocol == "https" && URL(source).host == "registry.npmjs.org")
                    Release(version, dist.getString("integrity"), source, item.optJSONObject("engines")?.optString("node", "未声明") ?: "未声明",
                        "官方 npm 发行版；详细变更请查阅官方仓库。${if ('-' in version) "预发布版本。" else ""}", File(manager.paths.engines, "$version/.web-verified").exists())
                }
                releases.value = list
                val candidate = metadata.getJSONObject("dist-tags").getString("latest")
                manager.paths.atomic("releases.json", metadata.toString())
                manager.state.update { it.copy(candidate = candidate, stage = "官方版本列表已更新", update = if (candidate == it.dsh) UpdateState.CURRENT else UpdateState.AVAILABLE) }
                manager.event("官方 npm 版本检查完成：$candidate")
            } catch (cancelled: CancellationException) {
                manager.state.update { it.copy(update = UpdateState.UNCHECKED, stage = "版本检查已取消，可重新获取") }
                throw cancelled
            } catch (t: Throwable) { manager.fail(t); manager.state.update { it.copy(update = UpdateState.CHECK_FAILED, stage = "官方版本获取失败，可重试") } }
        }
    }
    private fun cachedRelease(version: String): Release {
        val metadata=JSONObject(manager.paths.read("releases.json") ?: error("请先检查官方版本列表"))
        val item=metadata.getJSONObject("versions").getJSONObject(version)
        val dist=item.getJSONObject("dist")
        val source=dist.getString("tarball")
        require(URL(source).protocol=="https" && URL(source).host=="registry.npmjs.org")
        return Release(version,dist.getString("integrity"),source,item.optJSONObject("engines")?.optString("node") ?: "未声明","官方 npm 发行版",false)
    }
    fun install(release: Release, reinstall: Boolean = false) {
        if (job?.isActive == true || !keepForeground()) return
        job = manager.scope.launch {
            try {
                check(manager.state.value.service !in setOf(ServiceState.RUNNING, ServiceState.STARTING, ServiceState.STOPPING) && !manager.hasLiveChild) { "请确认停止服务后再安装，可能中断任务" }
                check(manager.state.value.update != UpdateState.RECOVERY_REQUIRED) { "请先处理上次更新恢复" }
                manager.mutation.withLock {
                    manager.components.refresh(false)
                    check(manager.components.baseReady()) { "请先安装 Ubuntu 和 Node.js" }
                    manager.components.checkNodeRange(release.node)
                    val old=manager.paths.metadata()
                    val hasMetadata=File(manager.paths.state,"installed.json").exists()
                    check(!hasMetadata || old.optBoolean("ready") && old.has("ubuntu") && old.has("node") && old.has("dsh")) { "安装元数据不完整，已保留会话；请恢复匹配元数据" }
                    val previous=old.optString("dsh")
                    if(hasMetadata) {
                        check(VersionOrder.compare(release.version,previous)>=0) { "现有数据由 DSH $previous 使用过；安装旧版前需恢复该版本的匹配数据快照" }
                    } else check(manager.paths.dshData.listFiles().orEmpty().isEmpty()) { "现有会话缺少版本信息，已保留数据；请恢复匹配元数据" }
                    check(!reinstall || previous==release.version) { "重新安装仅用于当前版本；其他版本请使用版本安装入口" }
                    manager.paths.atomic("update-transaction", JSONObject().put("candidate", release.version).put("phase", "staging").toString())
                    manager.state.update { it.copy(update = UpdateState.STAGING,stage="准备 DSH ${release.version}",error="",download=null,bytes=0,total=-1) }
                    manager.ensurePnpm()
                    // Keep the exact old program available to the data snapshot's restore path.
                    if(hasMetadata && previous!=release.version && !manager.components.state.value.dshInstalled) {
                        manager.state.update { it.copy(stage="补齐快照回退所需的 DSH $previous") }
                        manager.installEngine(previous,cachedRelease(previous).integrity)
                    }
                    manager.installEngine(release.version, release.integrity, replaceExisting=reinstall) { candidate ->
                        manager.state.update { it.copy(update = UpdateState.TESTING,stage="检查 DSH ${release.version} 候选启动") }
                        test(release.version,candidate)
                    }
                    val snapshot=if(hasMetadata) {
                        manager.state.update { it.copy(update = UpdateState.BACKUP,stage="保存版本匹配的私有数据快照") }
                        DataSnapshots.create(manager.paths)
                    } else null
                    val journal=JSONObject().put("candidate",release.version).put("phase","switching")
                    snapshot?.let { journal.put("snapshot",it.name) }
                    manager.paths.atomic("update-transaction",journal.toString())
                    manager.state.update { it.copy(update = UpdateState.SWITCHING, stage = "切换到 DSH ${release.version}", phasePercent = null, download = null, bytes = 0, total = -1) }
                    val metadata=old.put("ready",true).put("ubuntu",manager.components.installedUbuntuVersion()).put("node",manager.components.installedNodeVersion()).put("dsh",release.version)
                    manager.paths.atomic("installed.json",metadata.toString())
                    manager.components.refresh()
                }
                manager.start()
                withTimeout(200000) { while (manager.state.value.service !in setOf(ServiceState.RUNNING, ServiceState.FAILED, ServiceState.INTERRUPTED)) delay(250) }
                check(manager.state.value.service == ServiceState.RUNNING) { "新版本使用正式数据启动失败；请恢复匹配快照" }
                File(manager.paths.state, "update-transaction").delete()
                manager.state.update { it.copy(update = UpdateState.SUCCESS,stage="DSH ${release.version} 安装与本机启动检查完成") }
                manager.event("DSH 安装与本机 Web 健康检查通过；官方网页需重新认证。模型和数据迁移兼容性仍需人工验收")
            } catch (t: Throwable) {
                manager.fail(t)
                manager.components.refresh()
                val journal = manager.paths.read("update-transaction")
                val switched = journal?.let { runCatching { JSONObject(it).optString("phase") == "switching" }.getOrDefault(true) } ?: false
                manager.state.update { it.copy(update = if (switched) UpdateState.RECOVERY_REQUIRED else UpdateState.FAILED) }
                if (!switched) File(manager.paths.state, "update-transaction").delete()
            }
        }
    }
    /** Install a program without opening current userdata; also makes old snapshot engines available. */
    fun stage(release: Release) {
        if(job?.isActive==true || !keepForeground())return
        val previousUpdate=manager.state.value.update
        job=manager.scope.launch {
            try {
                check(manager.state.value.service !in setOf(ServiceState.RUNNING,ServiceState.STARTING,ServiceState.STOPPING) && !manager.hasLiveChild) { "请先停止 DSH" }
                manager.mutation.withLock {
                    manager.components.refresh(false)
                    check(manager.components.baseReady()) { "请先安装 Ubuntu 和 Node.js" }
                    manager.components.checkNodeRange(release.node)
                    manager.state.update { it.copy(update=UpdateState.STAGING,stage="安装 DSH ${release.version} 备用程序",error="",download=null,bytes=0,total=-1) }
                    manager.ensurePnpm()
                    manager.installEngine(release.version,release.integrity)
                    manager.state.update { it.copy(update=UpdateState.TESTING,stage="检查备用 DSH ${release.version}") }
                    test(release.version)
                    manager.components.refresh()
                }
                manager.state.update { it.copy(update=if(previousUpdate==UpdateState.RECOVERY_REQUIRED)previousUpdate else UpdateState.AVAILABLE,stage="DSH ${release.version} 备用程序已安装，未切换数据版本") }
                manager.event("备用 DSH ${release.version} 安装与隔离启动检查完成；正式数据未切换")
            } catch(t:Throwable) {
                manager.fail(t);manager.components.refresh()
                manager.state.update { it.copy(update=if(previousUpdate==UpdateState.RECOVERY_REQUIRED)previousUpdate else UpdateState.FAILED) }
            }
        }
    }
    private suspend fun test(version: String,engineHome:File=File(manager.paths.engines,version)) {
        val isolated = File(manager.paths.runtime, "candidate-test")
        RuntimeManager.deleteTree(isolated)
        val child = manager.launcher.start(manager.paths.root,
            listOf("/opt/node/bin/node", "/opt/engines/node_modules/@deepseek-ai/dsh/lib/bin.js", "web", "--no-open", "--host", "127.0.0.1", "--port", "0"),
            File(isolated, "home"), File(isolated, "dsh"), File(isolated, "workspace"), engineHome)
        coroutineScope {
            val url = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val collector = async { manager.launcher.collect(child) { line -> if (line.contains("dsh web:")) url.set(Regex("http://127\\.0\\.0\\.1:\\d+/[^\\s\\u001B]*").find(line)?.value) } }
            try {
                withTimeout(180000) {
                    while (url.get() == null) { check(child.process.isAlive) { "候选版本就绪前退出" }; delay(200) }
                    val request = URL(url.get()).openConnection() as HttpURLConnection
                    request.instanceFollowRedirects = false; request.connectTimeout = 5000; request.readTimeout = 5000
                    try { check(request.responseCode in 200..399) { "候选 Web 认证入口未就绪" } } finally { request.disconnect() }
                }
            } finally {
                withContext(NonCancellable) {
                    try { child.stop() } finally {
                        val ended=withTimeoutOrNull(5000) { collector.join();true } ?: false
                        collector.cancelAndJoin()
                        check(ended) { "候选 DSH 输出未能结束，当前版本与数据未切换" }
                    }
                }
            }
        }
        RuntimeManager.deleteTree(isolated)
        manager.probeSandbox(version,engineHome)
    }
    fun resumeInterrupted() {
        if(job?.isActive==true || !keepForeground())return
        job=manager.scope.launch {
            try {
                val journal=JSONObject(manager.paths.read("update-transaction") ?: error("没有中断的更新事务"))
                check(journal.optString("phase")=="switching") { "此事务不在切换阶段，可重新选择版本" }
                check(journal.getString("candidate")==manager.paths.metadata().getString("dsh")) { "当前版本尚未切换，请恢复匹配快照或重试安装" }
                check(JSONObject(manager.paths.read("restore-transaction") ?: "{}").optString("phase")!="prepared") { "请先完成中断的数据恢复" }
                manager.state.update { it.copy(update=UpdateState.TESTING) }
                manager.start()
                withTimeout(200000){while(manager.state.value.service !in setOf(ServiceState.RUNNING,ServiceState.FAILED,ServiceState.INTERRUPTED))delay(200)}
                check(manager.state.value.service==ServiceState.RUNNING && manager.state.value.webHealthy) { "当前版本未通过 Web 启动检查，恢复入口已保留" }
                File(manager.paths.state,"update-transaction").delete()
                manager.state.update { it.copy(update=UpdateState.SUCCESS) }
                manager.event("中断更新已通过本机启动检查，保留当前版本与数据；未回滚项目")
            }catch(t:Throwable){manager.fail(t);manager.state.update { it.copy(update=UpdateState.RECOVERY_REQUIRED) }}
        }
    }
    fun restore() {
        if (job?.isActive == true || !keepForeground()) return
        job = manager.scope.launch {
            manager.state.update { it.copy(update=UpdateState.RESTORING) }
            try {
                check(manager.state.value.service !in setOf(ServiceState.RUNNING, ServiceState.STARTING)) { "请先停止服务" }
                manager.mutation.withLock {
                    DataSnapshots.restore(manager.paths)
                    manager.state.update { it.copy(dsh = manager.paths.metadata().getString("dsh"), update = UpdateState.RESTORED_PENDING_CHECK) }
                    manager.event("已恢复匹配程序和数据快照，项目目录未回滚；请启动服务验收")
                }
            } catch (t: Throwable) { manager.fail(t); manager.state.update { it.copy(update = UpdateState.RECOVERY_REQUIRED) } }
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= limit) { "版本元数据超过大小限制" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
