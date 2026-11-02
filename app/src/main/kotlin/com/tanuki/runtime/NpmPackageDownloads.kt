package com.tanuki.runtime

import com.tanuki.download.Downloader
import com.tanuki.download.DownloadHttpException
import com.tanuki.download.DownloadLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Plans from the lock, downloads verified archives, then installs solely from npm's cache. */
internal class NpmPackageDownloads(private val manager: RuntimeManager) {
    private data class Archive(val url: String, val integrity: String, val file: File)

    suspend fun install(version: String, expectedIntegrity: String, container: File, isolated: File): File =
        withContext(Dispatchers.IO) {
            try {
                require(VERSION.matches(version)) { "DSH 版本格式无效" }
                validateIntegrity(expectedIntegrity)
                val stage = File(container, version)
                val archives = manager.paths.npmArchives
                listOf(stage, archives, File(isolated, "home"), File(isolated, "dsh"), File(isolated, "workspace")).forEach {
                    check(it.isDirectory || it.mkdirs()) { "无法创建安装目录" }
                }
                File(isolated, "home/.tanuki-global-npmrc").writeText("")
                val guest = "/opt/engines/$version"
                suspend fun run(label: String, args: List<String>, progressKind: String? = null) {
                    val result = manager.launcher.run(
                        root = manager.paths.root,
                        args = listOf(
                            "/usr/bin/env",
                            "npm_config_userconfig=/dev/null",
                            "npm_config_globalconfig=/home/tanuki/.tanuki-global-npmrc",
                            "npm_config_registry=$REGISTRY",
                            "npm_config_cache=/home/tanuki/.npm",
                            "XDG_CONFIG_HOME=/home/tanuki/.config",
                        ) + args,
                        timeoutMs = 1200000,
                        home = File(isolated, "home"),
                        dshHome = File(isolated, "dsh"),
                        workspace = File(isolated, "workspace"),
                        engineHome = container,
                        onLine = { line ->
                            if (progressKind != null) installProgress(line, progressKind)?.let { (done, total) ->
                                val title = "写入已验证的 npm 缓存"
                                manager.state.update { it.copy(stage = "$title（$done/$total）", phasePercent = done * 100 / total) }
                            }
                        },
                    )
                    check(result.exitCode == 0) {
                        "$label：退出码 ${result.exitCode}；" +
                            Redact.text(result.stderr.ifBlank { result.stdout }).takeLast(1200)
                    }
                }
                val sourceOrder = manager.npmSources.orderedForInstall(version)
                val npmFlags = listOf("--engine-strict", "--no-audit", "--no-fund", "--loglevel=http", "--registry=$REGISTRY", "--prefix", guest)
                manager.phase("解析 DSH 依赖", EnvironmentState.INSTALLING)
                run("依赖解析失败", listOf("/opt/node/bin/npm", "install", "--package-lock-only", "--ignore-scripts", "--save-exact") + npmFlags + "@deepseek-ai/dsh@$version")
                val lock = File(stage, "package-lock.json")
                verifyTop(lock, version, expectedIntegrity)
                val lockHash = Downloader.hash(lock)

                manager.phase("检查依赖平台与完整性", EnvironmentState.VERIFYING)
                File(stage, ".dsh-plan.cjs").writeText(PLAN_SCRIPT)
                run("依赖检查失败", listOf("/opt/node/bin/node", "$guest/.dsh-plan.cjs", guest, version, expectedIntegrity))
                val planned = readJson(File(stage, ".dsh-plan.json")).getJSONArray("archives")
                check(planned.length() in 1..MAX_ARCHIVES) { "依赖下载清单不完整" }
                // Do not gate installation on official-only HEAD/Range requests. Some mobile
                // networks and mirrors omit lengths or reject probes despite serving valid GETs.
                val seen = HashSet<String>()
                val entries = (0 until planned.length()).map { index ->
                    val item = planned.getJSONObject(index)
                    val url = item.getString("url")
                    val integrity = item.getString("integrity")
                    validateUrl(url)
                    validateIntegrity(integrity)
                    check(seen.add(url)) { "依赖下载清单包含重复归档" }
                    // URL identity avoids two workers writing the same target, even for equal SRI.
                    val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    Archive(url, integrity, File(archives, "$key.tgz"))
                }
                check(manager.paths.base.usableSpace > 64L * 1024 * 1024) {
                    "空间不足，下载至少需要保留 64 MiB；当前程序和已校验缓存保留"
                }
                manager.phase("下载 DSH 依赖", EnvironmentState.DOWNLOADING)
                manager.event("依赖共 ${entries.size} 个；直接下载并逐包校验，总大小未知时显示已传输量，不等待大小预测")
                download(entries, sourceOrder)

                // Only successful fetchIntegrity results are admitted to this manifest. Unknown
                // HTTP lengths become exact here, after the bytes and SHA-512 have been checked.
                val downloaded = JSONArray()
                var total = 0L
                entries.forEach { entry ->
                    val bytes = entry.file.length()
                    check(entry.file.isFile && bytes in 1..MAX_BYTES && total <= MAX_BYTES - bytes) {
                        "已验证依赖归档大小无效或总量超过限制"
                    }
                    total += bytes
                    downloaded.put(JSONObject().put("url", entry.url).put("integrity", entry.integrity)
                        .put("bytes", bytes).put("archive", "/opt/download-cache/${entry.file.name}"))
                }
                manager.phase("检查 DSH 安装空间", EnvironmentState.CHECKING)
                // Retain verified downloads if extraction/npm cannot yet fit. The expansion
                // factor is a conservative estimate, not an assertion about all install scripts.
                check(manager.paths.base.usableSpace >= total * 6 + 64L * 1024 * 1024) {
                    "DSH 解压与安装空间不足，预计还需预留 ${(total * 6 + 64L * 1024 * 1024) / (1024 * 1024)} MiB；已校验依赖和当前程序保留"
                }
                File(stage, ".dsh-downloaded.json").writeText(JSONObject().put("archives", downloaded).toString())
                manager.phase("写入已验证的 npm 缓存", EnvironmentState.INSTALLING)
                File(stage, ".dsh-seed.cjs").writeText(SEED_SCRIPT)
                run("依赖缓存写入失败", listOf("/opt/node/bin/node", "$guest/.dsh-seed.cjs", "$guest/.dsh-plan.json", "$guest/.dsh-downloaded.json"), "seed")
                check(Downloader.hash(lock) == lockHash) { "依赖锁在下载期间发生变化" }
                manager.phase("离线安装 DSH 依赖", EnvironmentState.INSTALLING)
                run("依赖离线安装失败", listOf("/opt/node/bin/npm", "ci", "--offline") + npmFlags)
                check(Downloader.hash(lock) == lockHash) { "离线安装修改了依赖锁" }
                verifyTop(lock, version, expectedIntegrity)
                // npm may silently omit failing optional packages; verify the selected graph too.
                run("安装结果校验失败", listOf("/opt/node/bin/node", "$guest/.dsh-plan.cjs", guest, version, expectedIntegrity, "verify"))
                manager.phase("DSH 依赖安装完成", EnvironmentState.INSTALLING)
                stage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw IOException("DSH 依赖安装失败：" + Redact.text(error.message ?: "无法完成安装").take(1600))
            }
        }

    private suspend fun download(entries: List<Archive>, sources: List<NpmArchiveSource>) = coroutineScope {
        val progress = NpmDownloadProgress(entries.size, MAX_BYTES)
        val usedSources = arrayOfNulls<String>(entries.size)
        val failedSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val failures = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val missingPackageReported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val mutex = Any()
        var networkUsed = false
        var sampleTime = System.nanoTime()
        var sampleBytes = 0L
        var speed = 0L
        fun publish(index: Int, bytes: Long, total: Long = -1, network: Boolean = false,
                    complete: Boolean = false) = synchronized(mutex) {
            val observed = when {
                index < 0 -> progress.snapshot()
                complete -> progress.complete(index, bytes)
                else -> progress.update(index, bytes, total)
            }
            networkUsed = networkUsed || network
            val received = observed.received
            val now = System.nanoTime()
            val elapsed = now - sampleTime
            if (received < sampleBytes) {
                sampleTime = now
                sampleBytes = received
                speed = 0
            } else if (elapsed >= 250_000_000L) {
                speed = ((received - sampleBytes).toDouble() * 1_000_000_000 / elapsed).toLong()
                sampleTime = now
                sampleBytes = received
            }
            val labels = usedSources.filterNotNull().distinct()
            val label = "DSH 依赖 · " + if (labels.isEmpty()) sources.first().title
                else labels.take(2).joinToString("、") + if (labels.size > 2) " 等 ${labels.size} 个来源" else ""
            manager.state.update {
                it.copy(stage = "下载 DSH 依赖（已校验 ${observed.verified}/${observed.archives}）",
                    bytes = received, total = observed.total, phasePercent = null,
                    download = RuntimeDownloadProgress(component = "dsh", source = label, received = received,
                        total = observed.total, bytesPerSecond = speed,
                        reused = observed.verified == observed.archives && !networkUsed))
            }
        }
        publish(-1, 0L)
        val next = AtomicInteger()
        repeat(minOf(4, entries.size)) {
            launch(Dispatchers.IO) {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= entries.size) break
                    val entry = entries[index]
                    var completed = false
                    var lastError: Exception? = null
                    for (source in sources) {
                        if (source.id in failedSources && source.id != "official") continue
                        try {
                            synchronized(mutex) { usedSources[index] = source.title }
                            publish(index, 0)
                            var transferred = false
                            Downloader { received, length ->
                                transferred = true
                                publish(index, received, length, network = true)
                            }.fetchIntegrity(source.urlFor(entry.url), entry.file, entry.integrity, maxBytes = MAX_BYTES)
                            if (!transferred) synchronized(mutex) { usedSources[index] = "已校验缓存" }
                            completed = true
                            break
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (tooLarge: DownloadLimitException) { throw tooLarge }
                        catch (error: Exception) {
                            lastError = error
                            if (manager.paths.base.usableSpace <= 64L * 1024 * 1024) throw error
                            val packageMissing = error is DownloadHttpException && error.status in setOf(400,403,404,410)
                            if(packageMissing) {
                                if(missingPackageReported.add(source.id))manager.event("${source.title} 有依赖尚未同步，缺失包改用其他源")
                            } else if(failures.computeIfAbsent(source.id) { AtomicInteger() }.incrementAndGet()>=2 && failedSources.add(source.id)) {
                                manager.event("DSH 下载源 ${source.title} 连续失败，本次改用其他源：" +
                                    Redact.text(error.message ?: "下载失败").take(160))
                            }
                        }
                    }
                    if (!completed) throw lastError ?: IOException("没有可用的 DSH 依赖下载源")
                    // A verified cache hit does not invoke Downloader's callback.
                    publish(index, entry.file.length(), complete = true)
                }
            }
        }
    }

    private fun verifyTop(lock: File, version: String, integrity: String) {
        val packages = readJson(lock).getJSONObject("packages")
        val top = packages.getJSONObject("node_modules/@deepseek-ai/dsh")
        check(top.getString("version") == version && top.getString("integrity") == integrity) { "DSH 版本或完整性与选定发布不一致" }
        validateUrl(top.getString("resolved"))
        val dependencies = packages.getJSONObject("").getJSONObject("dependencies")
        check(dependencies.length() == 1 && dependencies.getString("@deepseek-ai/dsh") == version) { "根依赖与选定 DSH 版本不一致" }
    }

    private fun readJson(file: File): JSONObject {
        check(file.isFile && file.length() in 1..MAX_JSON_BYTES) { "依赖清单缺失或大小无效" }
        return JSONObject(file.readText())
    }

    private fun validateUrl(raw: String) {
        val uri = URI(raw)
        check(uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith("/") && uri.rawPath.endsWith(".tgz")) { "依赖归档必须来自 npm 官方 HTTPS 地址" }
    }

    private fun validateIntegrity(value: String) {
        check(SRI.matches(value)) { "依赖缺少有效的 SHA-512 完整性校验" }
        val decoded = Base64.getDecoder().decode(value.removePrefix("sha512-"))
        check(decoded.size == 64 && "sha512-" + Base64.getEncoder().encodeToString(decoded) == value) { "SHA-512 完整性校验格式无效" }
    }

    companion object {
        private val PROGRESS_LINE = Regex("DSH_PROGRESS (measure|seed) ([0-9]{1,4}) ([0-9]{1,4})")
        internal fun installProgress(line: String, expectedKind: String): Pair<Int, Int>? {
            val match = PROGRESS_LINE.matchEntire(line) ?: return null
            if (match.groupValues[1] != expectedKind) return null
            val done = match.groupValues[2].toInt()
            val total = match.groupValues[3].toInt()
            return if (total in 1..MAX_ARCHIVES && done in 0..total) done to total else null
        }
        const val REGISTRY = "https://registry.npmjs.org"
        const val MAX_ARCHIVES = 4000
        const val MAX_BYTES = 1_500L * 1024 * 1024
        const val MAX_JSON_BYTES = 16L * 1024 * 1024
        val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
        val SRI = Regex("sha512-[A-Za-z0-9+/]{86}==")

        val PLAN_SCRIPT = """
            'use strict';
            const fs = require('node:fs');
            const path = require('node:path');
            const base = '/opt/node/lib/node_modules/npm';
            const Arborist = require(base + '/node_modules/@npmcli/arborist');
            const optionalSet = require(base + '/node_modules/@npmcli/arborist/lib/optional-set.js');
            const {checkPlatform, checkEngine} = require(base + '/node_modules/npm-install-checks');
            const npmVersion = require(base + '/package.json').version;
            const [stage, version, expected, mode] = process.argv.slice(2);
            function assert(ok, message) {
                if (!ok) throw Object.assign(new Error(message), {publicMessage: message});
            }
            function official(raw) {
                const u = new URL(raw);
                assert(u.protocol === 'https:' && u.hostname === 'registry.npmjs.org' && !u.port &&
                    !u.username && !u.password && !u.search && !u.hash && u.pathname.endsWith('.tgz') &&
                    u.href === raw, '依赖归档不是规范的 npm 官方地址');
            }
            function integrity(raw) {
                assert(typeof raw === 'string' && /^sha512-[A-Za-z0-9+/]{86}==${'$'}/.test(raw) &&
                    Buffer.from(raw.slice(7), 'base64').length === 64 &&
                    'sha512-' + Buffer.from(raw.slice(7), 'base64').toString('base64') === raw,
                    '依赖缺少有效的 SHA-512 校验');
            }
            async function main() {
                assert(process.platform === 'linux' && process.arch === 'arm64' &&
                    process.report.getReport().header.glibcVersionRuntime, '安装环境必须是 linux arm64 glibc');
                const lock = JSON.parse(fs.readFileSync(path.join(stage, 'package-lock.json'), 'utf8'));
                const top = lock.packages?.['node_modules/@deepseek-ai/dsh'];
                assert(top?.version === version && top?.integrity === expected, 'DSH 发布完整性不一致');
                integrity(expected);
                const tree = await new Arborist({path: stage, registry: 'https://registry.npmjs.org',
                    force: false, engineStrict: true}).loadVirtual();
                const rootEdge = tree.edgesOut.get('@deepseek-ai/dsh');
                assert(rootEdge?.valid && rootEdge.to?.version === version, 'DSH 根依赖无效');
                const reachable = new Set([tree]);
                for (const node of reachable) {
                    for (const edge of node.edgesOut.values()) {
                        if (edge.to) reachable.add(edge.to);
                    }
                }
                assert(reachable.size <= 12000, '依赖图过大');
                const excluded = new Set();
                function exclude(node) {
                    assert(node.optional, '必需依赖不兼容或缺失');
                    for (const member of optionalSet(node)) {
                        assert(member !== tree && member.optional, '无法安全剔除可选依赖');
                        excluded.add(member);
                    }
                }
                for (const node of reachable) {
                    if (node === tree) continue;
                    assert(!node.isLink, '依赖锁包含不支持的本地链接');
                    try {
                        checkPlatform(node.package, false, {os: 'linux', cpu: 'arm64', libc: 'glibc'});
                        checkEngine(node.package, npmVersion, process.version, false);
                    } catch (error) {
                        assert(error.code === 'EBADPLATFORM' || error.code === 'EBADENGINE', '依赖环境检查失败');
                        exclude(node);
                    }
                }
                // Missing required children invalidate their entire optional ancestor branch.
                for (const node of reachable) {
                    for (const edge of node.edgesOut.values()) {
                        if (!edge.valid && !edge.optional) exclude(node);
                    }
                }
                const selected = new Set([tree]);
                for (const node of selected) {
                    for (const edge of node.edgesOut.values()) {
                        if (!edge.valid || !edge.to || excluded.has(edge.to)) {
                            assert(edge.optional, '必需依赖无法从锁文件解析');
                            continue;
                        }
                        selected.add(edge.to);
                    }
                }
                const archives = new Map();
                const packages = [];
                for (const node of selected) {
                    if (node === tree) continue;
                    const location = node.location;
                    assert(location && !path.isAbsolute(location) && !location.split('/').includes('..'), '依赖路径无效');
                    if (node.inBundle) continue;
                    const entry = lock.packages[location];
                    assert(entry && entry.version === node.version && !entry.link, '依赖锁内容不一致');
                    official(entry.resolved);
                    integrity(entry.integrity);
                    const previous = archives.get(entry.resolved);
                    assert(!previous || previous.integrity === entry.integrity, '同一归档存在不同完整性校验');
                    archives.set(entry.resolved, {url: entry.resolved, integrity: entry.integrity});
                    packages.push({location, version: entry.version});
                    if (mode === 'verify') {
                        const installed = JSON.parse(fs.readFileSync(path.join(stage, location, 'package.json'), 'utf8'));
                        assert(installed.version === entry.version, '已安装依赖版本与锁不一致');
                    }
                }
                assert(archives.size > 0 && archives.size <= 4000, '依赖归档数量无效');
                if (mode !== 'verify') fs.writeFileSync(path.join(stage, '.dsh-plan.json'), JSON.stringify({archives: [...archives.values()], packages}));
            }
            main().catch(error => {
                console.error(error.publicMessage || '依赖图、平台、版本或完整性校验失败');
                process.exitCode = 1;
            });
        """.trimIndent()

        val SEED_SCRIPT = """
            'use strict';
            const fs = require('node:fs');
            const {pipeline} = require('node:stream/promises');
            const cacache = require('/opt/node/lib/node_modules/npm/node_modules/cacache');
            const [planPath, downloadedPath] = process.argv.slice(2);
            function assert(ok) { if (!ok) throw new Error('归档清单校验失败'); }
            async function main() {
                const plan = JSON.parse(fs.readFileSync(planPath, 'utf8')).archives;
                const downloaded = JSON.parse(fs.readFileSync(downloadedPath, 'utf8')).archives;
                assert(Array.isArray(plan) && Array.isArray(downloaded) && plan.length === downloaded.length);
                console.log('DSH_PROGRESS seed 0 ' + plan.length);
                for (let index = 0; index < plan.length; index++) {
                    const wanted = plan[index];
                    const item = downloaded[index];
                    assert(item.url === wanted.url && item.integrity === wanted.integrity &&
                        /^\/opt\/download-cache\/[a-f0-9]{64}\.tgz${'$'}/.test(item.archive));
                    const stat = fs.lstatSync(item.archive);
                    assert(stat.isFile() && stat.size === item.bytes);
                    await pipeline(fs.createReadStream(item.archive),
                        cacache.put.stream('/home/tanuki/.npm/_cacache', 'dsh-archive:' + item.url,
                            {integrity: item.integrity, size: item.bytes}));
                    console.log('DSH_PROGRESS seed ' + (index + 1) + ' ' + plan.length);
                }
            }
            main().catch(() => { console.error('已下载归档的完整性校验或 npm 缓存写入失败'); process.exitCode = 1; });
        """.trimIndent()
    }
}
