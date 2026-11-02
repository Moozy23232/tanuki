package com.tanuki.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI

/** Mirrors carry archives only; the official registry still supplies the version, lock and SRI. */
data class NpmSourceSelection(val version: String = RuntimeManager.DSH, val id: String = "official", val automatic: Boolean = true)
data class NpmArchiveSource(val id: String, val title: String, val registry: String) {
    fun urlFor(officialUrl: String): String {
        val uri = URI(officialUrl)
        require(uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith("/") && uri.rawPath.endsWith(".tgz") &&
            uri.path.split('/').none { it == "." || it == ".." }) { "npm 归档地址必须来自官方锁文件" }
        return registry + uri.rawPath
    }
}

class NpmSources(private val manager: RuntimeManager) {
    val speed = SourceSpeedTester(manager.scope)
    val state = MutableStateFlow(readChoice())
    private var epoch = 0L

    fun sources(version: String = state.value.version): List<RuntimeSource> {
        require(VERSION.matches(version)) { "DSH 版本格式无效" }
        val official = "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-$version.tgz"
        return ARCHIVES.map { RuntimeSource(it.id, it.title, it.urlFor(official), URI(it.registry).host) }
    }

    /** Entering the DSH step samples its selected release without changing a manual preference. */
    @Synchronized fun prepare(version: String) {
        if (manager.hasActiveOperation || manager.hasLiveChild) return
        useVersion(version)
        if (state.value.automatic && !speed.state.value.running && !fresh()) start()
    }

    @Synchronized fun test(version: String) {
        if (manager.hasActiveOperation || manager.hasLiveChild) return
        useVersion(version)
        if (!speed.state.value.running) start()
    }

    @Synchronized fun select(id: String) {
        check(!manager.hasActiveOperation && !manager.hasLiveChild) { "请等待当前操作完成并停止 DSH" }
        require(id == "auto" || ARCHIVES.any { it.id == id }) { "未知 DSH 下载源" }
        val selected = state.value.copy(automatic = id == "auto", id = if (id == "auto") state.value.id else id)
        persist(selected)
        epoch++
        state.value = selected
        if (selected.automatic) {
            speed.cancel()
            start()
        }
    }

    /** Returns this installation's stable order; a fallback does not overwrite a manual choice. */
    suspend fun orderedForInstall(version: String): List<NpmArchiveSource> {
        synchronized(this) { useVersion(version) }
        if (state.value.automatic && !fresh()) {
            manager.phase("测速 DSH 依赖下载源", EnvironmentState.CHECKING)
            val token = synchronized(this) {
                if (!speed.state.value.running) start()
                epoch
            }
            val sample = try { speed.state.first { !it.running } }
            catch (cancelled: CancellationException) { speed.cancel(); throw cancelled }
            synchronized(this) { adopt(token, version, sample) }
        }
        val preference = state.value
        val sample = speed.state.value.takeIf { fresh() }
        val best = sample?.bestSourceId?.takeIf { preference.automatic }
        val head = ARCHIVES.firstOrNull { it.id == (best ?: preference.id) } ?: ARCHIVES.first()
        val successful = sample?.results?.values.orEmpty().filter { it.status == SourceProbeStatus.SUCCESS }
            .sortedByDescending { it.bytesPerSecond }.mapNotNull { result -> ARCHIVES.firstOrNull { it.id == result.sourceId } }
        return (listOf(head) + successful + ARCHIVES).distinctBy { it.id }
    }

    @Synchronized private fun useVersion(version: String) {
        require(VERSION.matches(version)) { "DSH 版本格式无效" }
        if (state.value.version == version) return
        epoch++
        speed.clear()
        state.value = state.value.copy(version = version)
    }

    private fun fresh(): Boolean {
        val sample = speed.state.value
        val age = System.currentTimeMillis() - sample.measuredAtMillis
        return !sample.running && sample.measuredAtMillis > 0 && age in 0..FRESH_MS &&
            ARCHIVES.all { sample.results[it.id]?.status in setOf(SourceProbeStatus.SUCCESS, SourceProbeStatus.FAILED) }
    }

    @Synchronized private fun start() {
        val token = ++epoch
        val version = state.value.version
        speed.test(sources(version))
        if (!speed.state.value.running) return
        manager.scope.launch {
            try {
                val sample = speed.state.first { !it.running }
                synchronized(this@NpmSources) { adopt(token, version, sample) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { manager.event("DSH 下载源测速结果未保存：${Redact.text(error.message ?: "未知错误")}") }
        }
    }

    private fun adopt(token: Long, version: String, sample: SourceSpeedSnapshot) {
        val current = state.value
        if (token != epoch || current.version != version || !current.automatic || sample.measuredAtMillis <= 0) return
        val best = ARCHIVES.firstOrNull { it.id == sample.bestSourceId } ?: return
        val selected = current.copy(id = best.id)
        persist(selected)
        state.value = selected
    }

    private fun readChoice(): NpmSourceSelection {
        val saved = runCatching { JSONObject(manager.paths.read("npm-download-source.json") ?: "{}") }.getOrDefault(JSONObject())
        val id = saved.optString("id").takeIf { candidate -> ARCHIVES.any { it.id == candidate } } ?: "official"
        return NpmSourceSelection(id = id, automatic = saved.optString("mode") != "manual")
    }

    private fun persist(choice: NpmSourceSelection) {
        manager.paths.atomic("npm-download-source.json", JSONObject().put("schema", 1)
            .put("mode", if (choice.automatic) "auto" else "manual").put("id", choice.id).toString())
    }

    companion object {
        private const val FRESH_MS = 10L * 60 * 1000
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
        val ARCHIVES = listOf(
            NpmArchiveSource("official", "npm 官方", "https://registry.npmjs.org"),
            NpmArchiveSource("npmmirror", "npmmirror", "https://registry.npmmirror.com"),
            NpmArchiveSource("huawei", "华为云 npm", "https://repo.huaweicloud.com/repository/npm"),
            NpmArchiveSource("huawei-mirror", "华为云 npm 镜像", "https://mirrors.huaweicloud.com/repository/npm"),
            NpmArchiveSource("tencent", "腾讯云 npm", "https://mirrors.cloud.tencent.com/npm")
        )
    }
}
