package com.tanuki.runtime

import com.tanuki.download.Downloader
import com.tanuki.storage.SafeTar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/** User-selected mode: AUTO re-measures and adopts the fastest source; MANUAL is never overwritten. */
private const val AUTO = "auto"
private const val MANUAL = "manual"
/** A completed speed sample older than this is re-measured before an automatic download. */
private const val SAMPLE_FRESH_MS = 10L * 60 * 1000
/** Never start another mirror attempt when the device is already at the download reserve. */
private const val DOWNLOAD_DISK_RESERVE = 64L * 1024 * 1024

/** All mutations run through RuntimeManager's one operation/mutex; userdata is never a target. */
enum class RuntimeComponent { UBUNTU, NODE }
data class RuntimeSource(val id: String, val title: String, val url: String, val detail: String, val expectedBytes: Long = -1)
data class ComponentInventory(val ubuntuInstalled: Boolean = false, val nodeInstalled: Boolean = false,
    val dshInstalled: Boolean = false, val ubuntuSource: String = "official", val nodeSource: String = "official",
    val installedDshVersions: Set<String> = emptySet(), val ubuntuVersion:String="未安装",val nodeVersion:String="未安装",
    val selectedUbuntuVersion:String=RuntimeManager.UBUNTU,val selectedNodeVersion:String=RuntimeManager.NODE,
    val ubuntuAutomatic:Boolean=true,val nodeAutomatic:Boolean=true)

class RuntimeComponents(private val manager: RuntimeManager) {
    private val paths get() = manager.paths
    val state = MutableStateFlow(ComponentInventory())
    val sourceTests=RuntimeComponent.entries.associateWith { SourceSpeedTester(manager.scope) }
    private val epochs=RuntimeComponent.entries.associateWith { AtomicLong() }
    private fun versionPreferences()=runCatching { JSONObject(paths.read("component-versions.json") ?: "{}") }.getOrDefault(JSONObject())
    fun selected(component:RuntimeComponent):RuntimeArtifact = RuntimeCatalog.find(component,versionPreferences().optString(component.name)) ?: RuntimeCatalog.entries(component).first()
    fun selectVersion(component:RuntimeComponent,version:String) {
        check(!manager.hasActiveOperation && !manager.hasLiveChild) { "请等待当前操作完成并停止 DSH" }
        require(RuntimeCatalog.find(component,version)!=null) { "未知组件版本" }
        paths.atomic("component-versions.json",versionPreferences().put(component.name,version).toString())
        // A probe started for the previous version must not pick a source for the new one.
        bumpEpoch(component)
        sourceTests.getValue(component).clear()
        refresh(false)
    }
    /** Entering an installer step starts a sample automatically; a fresh result is reused. */
    fun prepareSources(component:RuntimeComponent) {
        if(choice(component).mode!=AUTO || sourceTests.getValue(component).state.value.running)return
        if(freshSample(component,sources(component))!=null)return
        startTest(component,adoptBest=true)
    }
    fun testSources(component:RuntimeComponent) {
        if(sourceTests.values.any { it.state.value.running })return
        startTest(component,adoptBest=true)
    }
    /** Starts one probe run; automatic mode adopts the completed sample's fastest source. */
    private fun startTest(component:RuntimeComponent,adoptBest:Boolean) {
        val tester=sourceTests.getValue(component)
        val token=bumpEpoch(component)
        val artifact=selected(component)
        val options=sources(component,artifact)
        tester.test(options)
        if(!tester.state.value.running)return
        if(!adoptBest || choice(component).mode!=AUTO)return
        manager.scope.launch {
            val snapshot=try { tester.state.first { !it.running } }
                catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Throwable) { manager.event("下载源测速结果未保存：${Redact.text(error.message ?: "未知错误")}");return@launch }
            // Automatic mode adopts this run's fastest source; the persisted user choice is not read.
            adoptBest(component,token,artifact,snapshot)
        }
    }
    /** Persists the measured best source only while version, probe epoch and automatic mode are unchanged. */
    private fun adoptBest(component:RuntimeComponent,token:Long,artifact:RuntimeArtifact,snapshot:SourceSpeedSnapshot) {
        if(epochs.getValue(component).get()!=token)return
        if(choice(component).mode!=AUTO)return
        if(!sameArtifact(artifact,selected(component)))return
        if(snapshot.measuredAtMillis<=0L)return
        val best=snapshot.bestSourceId?.let { id -> sources(component,artifact).firstOrNull { it.id==id } } ?: return
        persist(component,SourceChoice(AUTO,best.id))
        refresh(false)
        manager.event("自动测速完成，已选择下载源：${best.title}")
    }
    private fun bumpEpoch(component:RuntimeComponent):Long=epochs.getValue(component).incrementAndGet()
    private fun sameArtifact(expected:RuntimeArtifact,actual:RuntimeArtifact)=expected.version==actual.version &&
        expected.sha256==actual.sha256 && expected.filename==actual.filename
    fun installedUbuntuVersion():String?=RuntimeCatalog.ubuntuFromMarker(File(paths.root,".base-verified").takeIf { it.isFile }?.readText())?.version
    fun installedNodeVersion():String? {
        val root=if(File(paths.node,"bin/node").isFile)paths.node else File(paths.root,"opt/node")
        if(!File(root,"bin/node").isFile)return null
        val marker=File(root,".component-version")
        val version=if(marker.isFile)marker.readText().trim() else paths.metadata().optString("node",RuntimeManager.NODE)
        return version.takeIf { it.matches(VERSION) }
    }
    fun sources(component: RuntimeComponent): List<RuntimeSource> = sources(component,selected(component))
    /** Eight public mirrors per component; every entry serves the same official archive and digest. */
    private fun sources(component: RuntimeComponent, artifact: RuntimeArtifact): List<RuntimeSource> {
        val ubuntu = "ubuntu-base/releases/${artifact.series}/release/${artifact.filename}"
        val node = "v${artifact.version}/${artifact.filename}"
        val bytes=artifact.size
        return when(component) {
            RuntimeComponent.UBUNTU -> listOf(
                RuntimeSource("official", "Ubuntu 官方", "https://cdimage.ubuntu.com/$ubuntu", "cdimage.ubuntu.com",bytes),
                RuntimeSource("huawei", "华为云", "https://repo.huaweicloud.com/ubuntu-cdimage/$ubuntu", "repo.huaweicloud.com",bytes),
                RuntimeSource("huawei-mirror", "华为云镜像", "https://mirrors.huaweicloud.com/ubuntu-cdimage/$ubuntu", "mirrors.huaweicloud.com",bytes),
                RuntimeSource("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/$ubuntu", "mirrors.tuna.tsinghua.edu.cn",bytes),
                RuntimeSource("ustc", "中科大 USTC", "https://mirrors.ustc.edu.cn/ubuntu-cdimage/$ubuntu", "mirrors.ustc.edu.cn",bytes),
                RuntimeSource("aliyun", "阿里云", "https://mirrors.aliyun.com/ubuntu-cdimage/$ubuntu", "mirrors.aliyun.com",bytes),
                RuntimeSource("nju", "南京大学 NJU", "https://mirror.nju.edu.cn/ubuntu-cdimage/$ubuntu", "mirror.nju.edu.cn",bytes),
                RuntimeSource("bfsu", "北外 BFSU", "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/$ubuntu", "mirrors.bfsu.edu.cn",bytes))
            RuntimeComponent.NODE -> listOf(
                RuntimeSource("official", "Node.js 官方", "https://nodejs.org/dist/$node", "nodejs.org",bytes),
                RuntimeSource("npmmirror", "npmmirror", "https://cdn.npmmirror.com/binaries/node/$node", "cdn.npmmirror.com",bytes),
                RuntimeSource("tuna", "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/$node", "mirrors.tuna.tsinghua.edu.cn",bytes),
                RuntimeSource("huawei", "华为云", "https://repo.huaweicloud.com/nodejs/$node", "repo.huaweicloud.com",bytes),
                RuntimeSource("huawei-mirror", "华为云镜像", "https://mirrors.huaweicloud.com/nodejs/$node", "mirrors.huaweicloud.com",bytes),
                RuntimeSource("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/nodejs-release/$node", "mirrors.cloud.tencent.com",bytes),
                RuntimeSource("aliyun", "阿里云", "https://mirrors.aliyun.com/nodejs-release/$node", "mirrors.aliyun.com",bytes),
                RuntimeSource("nju", "南京大学 NJU", "https://mirror.nju.edu.cn/nodejs-release/$node", "mirror.nju.edu.cn",bytes))
        }
    }
    private data class SourceChoice(val mode:String,val id:String)
    private fun preferences() = runCatching { JSONObject(paths.read("download-sources.json") ?: "{}") }.getOrDefault(JSONObject())
    /** Legacy files store a bare source id; without a stored mode the component is automatic. */
    private fun choice(component:RuntimeComponent):SourceChoice {
        val stored=preferences().opt(component.name)
        val id=when(stored) { is JSONObject->stored.optString("id"); is String->stored; else->"" }
        val mode=if(stored is JSONObject && stored.optString("mode")==MANUAL)MANUAL else AUTO
        return SourceChoice(mode,id)
    }
    @Synchronized private fun persist(component:RuntimeComponent,choice:SourceChoice) {
        paths.atomic("download-sources.json",preferences().put(component.name,
            JSONObject().put("mode",choice.mode).put("id",choice.id)).toString())
    }
    /** The actual source used next: the fresh measured best in auto mode, else the chosen/official one. */
    fun sourceFor(component: RuntimeComponent): RuntimeSource {
        val options=sources(component)
        val chosen=choice(component)
        if(chosen.mode==AUTO) freshSample(component,options)?.bestSourceId?.let { best ->
            options.firstOrNull { it.id==best }?.let { return it }
        }
        return options.firstOrNull { it.id==chosen.id } ?: options.first()
    }
    fun selectSource(component: RuntimeComponent, id: String) {
        check(!manager.hasActiveOperation && !manager.hasLiveChild) { "请等待当前操作完成并停止 DSH" }
        val options=sources(component)
        if(id==AUTO) {
            persist(component,SourceChoice(AUTO,choice(component).id))
            bumpEpoch(component)
            sourceTests.getValue(component).cancel()
            refresh(false)
            manager.event("已启用自动选择下载源：安装前先测速，再选择最快的可用源")
            startTest(component,adoptBest=true)
            return
        }
        require(options.any { it.id==id }) { "未知下载源" }
        persist(component,SourceChoice(MANUAL,id))
        // A running probe must not overwrite a manual choice; its epoch is invalidated.
        bumpEpoch(component)
        refresh(false)
        manager.event("已固定下载源：${options.first { it.id==id }.title}；自动测速不会再更改")
    }
    /** A completed sample for every current source, measured recently enough to trust. */
    private fun freshSample(component:RuntimeComponent,options:List<RuntimeSource>):SourceSpeedSnapshot? {
        val snapshot=sourceTests.getValue(component).state.value
        if(snapshot.running || snapshot.measuredAtMillis<=0L)return null
        if(System.currentTimeMillis()-snapshot.measuredAtMillis>SAMPLE_FRESH_MS)return null
        if(options.any { option -> snapshot.results[option.id]?.status.let { status -> status==null || status==SourceProbeStatus.TESTING } })return null
        return snapshot
    }
    /**
     * Resolves the artifact once and returns its SHA-256 verified archive. Automatic mode measures
     * first (reusing a <=10 minute sample); the chosen source is tried first in manual mode. Only
     * transport/digest failures fall through to the next mirror - cancellation and a full disk stop.
     */
    suspend fun downloadArtifact(component: RuntimeComponent): File = downloadArtifact(component, selected(component))
    private suspend fun downloadArtifact(component: RuntimeComponent, artifact: RuntimeArtifact): File {
        val cached=File(paths.downloads,artifact.cacheName)
        if(Files.exists(cached.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(cached.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "下载缓存路径被重定向，已保留原文件" }
        }
        if(cached.isFile && cached.length()==artifact.size && Downloader.hash(cached)==artifact.sha256) {
            manager.state.update { it.copy(bytes=artifact.size,total=artifact.size,phasePercent=null,
                download=RuntimeDownloadProgress(component.name.lowercase(),"已校验缓存",artifact.size,artifact.size,0,true)) }
            manager.event("复用已校验的 ${artifact.cacheName}")
            return cached
        }
        val options=sources(component,artifact)
        val mode=choice(component).mode
        val sample=if(mode==AUTO) freshSample(component,options) ?: measure(component,artifact,options)
            else freshSample(component,options)
        val ordered=orderedSources(component,options,mode,sample)
        var last:Throwable?=null
        for((index,source) in ordered.withIndex()) {
            try {
                manager.event("下载 ${artifact.cacheName}：${source.title}")
                val file=manager.download(source.url,artifact.cacheName,artifact.sha256,component.name.lowercase(),source.title,expectedSize=source.expectedBytes)
                persist(component,SourceChoice(choice(component).mode,source.id))
                refresh(false)
                manager.event("已从 ${source.title} 下载 ${artifact.cacheName} 并通过 SHA-256 校验")
                return file
            } catch(cancelled:CancellationException) {
                throw cancelled
            } catch(error:Throwable) {
                last=error
                if(paths.base.usableSpace<=DOWNLOAD_DISK_RESERVE) throw error
                if(index==ordered.lastIndex) break
                manager.event("下载源 ${source.title} 失败，改用下一个源：${Redact.text(error.message ?: "未知错误")}")
            }
        }
        throw last ?: IllegalStateException("没有可用的下载源")
    }
    /** Reuses a running probe or runs one, then adopts its best source for automatic mode. */
    private suspend fun measure(component:RuntimeComponent,artifact:RuntimeArtifact,options:List<RuntimeSource>):SourceSpeedSnapshot? {
        val tester=sourceTests.getValue(component)
        val token=bumpEpoch(component)
        if(!tester.state.value.running)tester.test(options)
        if(!tester.state.value.running)return freshSample(component,options)
        val snapshot=try { tester.state.first { !it.running } }
            catch(cancelled:CancellationException) { tester.cancel();throw cancelled }
        adoptBest(component,token,artifact,snapshot)
        return freshSample(component,options) ?: snapshot
    }
    private fun orderedSources(component:RuntimeComponent,options:List<RuntimeSource>,mode:String,sample:SourceSpeedSnapshot?):List<RuntimeSource> {
        val resolved=options.firstOrNull { it.id==choice(component).id } ?: options.first()
        val best=sample?.bestSourceId?.let { id -> options.firstOrNull { it.id==id } }
        val successful=sample?.results?.values.orEmpty()
            .filter { it.status==SourceProbeStatus.SUCCESS && it.bytesPerSecond>0 }
            .sortedWith(compareByDescending<SourceProbeResult> { it.bytesPerSecond }.thenBy { it.latencyMs })
            .mapNotNull { result -> options.firstOrNull { it.id==result.sourceId } }
        val head=if(mode==AUTO) listOfNotNull(best ?: resolved) else listOf(resolved)
        return (head+successful+options).distinctBy { it.id }
    }
    fun baseReady(): Boolean = state.value.ubuntuInstalled && state.value.nodeInstalled && installedUbuntuVersion()!=null && installedNodeVersion()!=null
    fun refresh(updateEnvironment: Boolean = true) {
        val meta=paths.metadata()
        val version=meta.optString("dsh")
        val engine=if(version.matches(VERSION)) File(paths.engines,version) else null
        val inventory=ComponentInventory(
            File(paths.root,".base-verified").isFile,
            File(paths.node,"bin/node").isFile || File(paths.root,"opt/node/bin/node").isFile,
            engine!=null && File(engine,".installed").isFile && File(engine,"node_modules/@deepseek-ai/dsh/lib/bin.js").isFile,
            sourceFor(RuntimeComponent.UBUNTU).id,sourceFor(RuntimeComponent.NODE).id,
            paths.engines.listFiles().orEmpty().filter { it.name.matches(VERSION) && File(it,".installed").isFile && File(it,"node_modules/@deepseek-ai/dsh/lib/bin.js").isFile }.map { it.name }.toSet(),installedUbuntuVersion() ?: "未知镜像",installedNodeVersion() ?: "未安装",
            selected(RuntimeComponent.UBUNTU).version,selected(RuntimeComponent.NODE).version,
            choice(RuntimeComponent.UBUNTU).mode==AUTO,choice(RuntimeComponent.NODE).mode==AUTO)
        if(meta.optBoolean("ready") && inventory.ubuntuInstalled && inventory.nodeInstalled && installedUbuntuVersion()!=null && installedNodeVersion()!=null &&
            (meta.optString("ubuntu")!=inventory.ubuntuVersion || meta.optString("node")!=inventory.nodeVersion)) {
            paths.atomic("installed.json",meta.put("ubuntu",inventory.ubuntuVersion).put("node",inventory.nodeVersion).toString())
        }
        state.value=inventory
        if(updateEnvironment) manager.state.update { it.copy(
            environment=if(inventory.ubuntuInstalled && inventory.nodeInstalled && installedUbuntuVersion()!=null && installedNodeVersion()!=null && inventory.dshInstalled && meta.optBoolean("ready")) EnvironmentState.READY else EnvironmentState.ABSENT,
            ubuntu=if(inventory.ubuntuInstalled) inventory.ubuntuVersion else "未安装",
            node=if(inventory.nodeInstalled) inventory.nodeVersion else "未安装",
            dsh=meta.optString("dsh","未安装")) }
    }
    /** Reject redirected component roots before rename or recursive deletion. */
    private fun owned(file: File) {
        var cursor=file.absoluteFile
        val base=paths.base.absoluteFile
        check(cursor.toPath().normalize().startsWith(base.toPath())) { "组件路径越界" }
        while(cursor!=base) {
            check(!Files.isSymbolicLink(cursor.toPath())) { "组件目录被重定向，已保留文件：${cursor.name}" }
            cursor=cursor.parentFile ?: error("组件路径越界")
        }
    }
    private fun remove(file: File) { owned(file); RuntimeManager.deleteTree(file) }
    fun migrateLegacyLayers() {
        check(!manager.hasLiveChild) { "请先停止 DSH 再迁移组件" }
        for((legacy,destination) in listOf(File(paths.root,"opt/node") to paths.node,File(paths.root,"opt/pnpm") to paths.pnpm)) {
            owned(legacy);owned(destination)
            if(!legacy.exists())continue
            if(destination.isDirectory && destination.listFiles()?.isEmpty()==true)check(destination.delete())
            if(!destination.exists()) {
                check(legacy.renameTo(destination)) { "无法迁移 ${destination.name}，原目录已保留" }
                manager.event("${destination.name} 已迁移至独立组件目录")
            }
        }
        refresh(false)
    }
    /** A journal interrupted before commit restores the old component; committed cleanup is resumable. */
    fun recover() {
        val raw=paths.read("component-transaction") ?: return
        val journal=JSONObject(raw)
        val name=journal.getString("component")
        require(name in setOf("ubuntu","node","dsh"))
        if(journal.optString("action")=="uninstall") {
            val targets=when(name) {
                "ubuntu"->listOf(paths.root,paths.stage)
                "node"->listOf(paths.node,paths.pnpm,paths.nodeCache,File(paths.root,"opt/node"),File(paths.root,"opt/pnpm"))
                else->{val version=journal.getString("version");require(version.matches(VERSION));listOf(File(paths.engines,version))}
            }
            targets.forEach(::remove)
        } else {
            val (destination,previous)=swapTargets(name,journal.optString("version").takeIf { it.isNotEmpty() })
            owned(destination);owned(previous)
            if(journal.optString("phase")=="committed") remove(previous)
            else if(previous.exists()) {
                remove(destination)
                check(previous.renameTo(destination)) { "组件恢复未完成，请保留数据后重试" }
            } else if(!journal.optBoolean("hadPrevious")) remove(destination)
            remove(when(name) {
                "ubuntu" -> paths.stage
                "node" -> File(paths.runtime,"node.install")
                else -> File(paths.runtime,"engine-install")
            })
        }
        File(paths.state,"component-transaction").delete()
    }
    private fun swapTargets(name:String,version:String?):Pair<File,File> = when(name) {
        "ubuntu" -> paths.root to File(paths.runtime,"ubuntu.previous")
        "node" -> paths.node to File(paths.runtime,"node.previous")
        "dsh" -> {
            require(version!=null && version.matches(VERSION)) { "DSH 替换事务缺少有效版本" }
            File(paths.engines,version) to File(paths.runtime,"dsh-$version.previous")
        }
        else -> error("未知组件替换事务")
    }
    internal suspend fun replaceEngine(version:String,block:suspend ()->Unit) = replace("dsh",version,block)
    private suspend fun replace(name: String, version:String?=null, block: suspend ()->Unit) {
        val (destination,previous)=swapTargets(name,version)
        owned(destination);owned(previous)
        check(!previous.exists()) { "存在待恢复的组件，请重新打开应用完成恢复" }
        val journal=JSONObject().put("component",name).put("phase","switching").put("hadPrevious",destination.exists())
        if(version!=null)journal.put("version",version)
        paths.atomic("component-transaction",journal.toString())
        try {
            if(destination.exists())check(destination.renameTo(previous)) { "无法保留旧组件，未进行替换" }
            block()
            withContext(NonCancellable) {
                paths.atomic("component-transaction",journal.put("phase","committed").toString())
                remove(previous)
                File(paths.state,"component-transaction").delete()
            }
        } catch(t:Throwable) {
            withContext(NonCancellable) { recover() }
            throw t
        }
    }
    suspend fun checkNodeRange(range:String?,nodeHome:File?=null,targetVersion:String?=null,label:String="DSH") {
        if(range.isNullOrBlank() || range=="未声明")return
        val script="const{createRequire}=require('node:module');const req=createRequire('/opt/node/lib/node_modules/npm/package.json');const semver=req('semver');const actual=process.argv[2]||process.versions.node;if(!semver.satisfies(actual,process.argv[1])){console.error('Node.js '+actual+' does not satisfy '+process.argv[1]);process.exit(42)}"
        val result=manager.launcher.run(paths.root,listOf("/opt/node/bin/node","-e",script,range,targetVersion ?: ""),nodeHome=nodeHome)
        check(result.exitCode==0) { "$label 要求 Node.js $range；所选版本不兼容，当前组件保留。${result.stderr.takeLast(800)}" }
    }
    fun dshNodeRequirement(version:String=paths.metadata().optString("dsh")):String? {
        if(!version.matches(VERSION))return null
        val pkg=File(paths.engines,"$version/node_modules/@deepseek-ai/dsh/package.json")
        return if(pkg.isFile)runCatching { JSONObject(pkg.readText()).optJSONObject("engines")?.optString("node") }.getOrNull() else null
    }
    suspend fun checkDshNodeCompatibility(version:String,nodeHome:File?=null,targetVersion:String?=null) {
        require(version.matches(VERSION))
        checkNodeRange(dshNodeRequirement(version),nodeHome,targetVersion,"DSH $version")
    }
    private suspend fun probeInstalledDsh(nodeHome:File?=null) {
        val version=paths.metadata().optString("dsh")
        if(!version.matches(VERSION) || !File(paths.engines,"$version/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)return
        val isolated=File(paths.runtime,"component-probe")
        remove(isolated)
        try {
            checkDshNodeCompatibility(version,nodeHome)
            val result=manager.launcher.run(paths.root,listOf("/opt/node/bin/node","/opt/engines/$version/node_modules/@deepseek-ai/dsh/lib/bin.js","--version"),120000,
                File(isolated,"home"),File(isolated,"dsh"),File(isolated,"workspace"),nodeHome=nodeHome)
            check(result.exitCode==0) { "DSH 与候选组件启动检查失败，当前组件保留：${result.stderr.takeLast(1500)}" }
        } finally { withContext(NonCancellable) { remove(isolated) } }
    }
    suspend fun ensureNode(force: Boolean = false) {
        migrateLegacyLayers()
        if(!force && File(paths.node,"bin/node").isFile)return
        val artifact=selected(RuntimeComponent.NODE)
        check(paths.base.usableSpace >= artifact.size*6 + DOWNLOAD_DISK_RESERVE) {
            "Node.js 下载和解压空间不足，请先清理存储；当前组件保留"
        }
        val currentDsh=paths.metadata().optString("dsh")
        if(File(paths.node,"bin/node").isFile && currentDsh.matches(VERSION))checkDshNodeCompatibility(currentDsh,targetVersion=artifact.version)
        manager.phase("下载 Node.js ${artifact.version}",EnvironmentState.DOWNLOADING)
        val archive=downloadArtifact(RuntimeComponent.NODE,artifact)
        manager.phase("校验与解压 Node.js",EnvironmentState.INSTALLING)
        val staging=File(paths.runtime,"node.install")
        remove(staging)
        try {
            SafeTar.extract(archive,staging)
            val candidate=File(staging,"node-v${artifact.version}-linux-arm64")
            manager.ensureNodeLibraries()
            manager.phase("验证 Node.js ${artifact.version}",EnvironmentState.VERIFYING)
            val result=manager.launcher.run(paths.root,listOf("/opt/node/bin/node","--version"),nodeHome=candidate)
            check(result.exitCode==0 && result.stdout.trim()=="v${artifact.version}") { "Node.js 候选启动检查失败：${result.stderr}" }
            if(currentDsh.matches(VERSION))checkDshNodeCompatibility(currentDsh,candidate)
            manager.ensurePnpm(candidate)
            manager.ensureUbuntuTools()
            probeInstalledDsh(candidate)
            File(candidate,".component-version").writeText(artifact.version)
            replace("node") { check(candidate.renameTo(paths.node)) { "无法提交 Node.js 组件" } }
            val meta=paths.metadata()
            if(meta.optBoolean("ready"))paths.atomic("installed.json",meta.put("node",artifact.version).toString())
        } finally { withContext(NonCancellable) { remove(staging) } }
    }
    fun install(component: RuntimeComponent, force: Boolean = false) = manager.launchExclusive("component-${component.name.lowercase()}") {
        recover()
        migrateLegacyLayers()
        try {
            when(component) {
                RuntimeComponent.NODE -> {
                    check(installedUbuntuVersion()!=null) { "请先安装已知版本的 Ubuntu，再安装 Node.js" }
                    ensureNode(force);manager.ensurePnpm()
                }
                RuntimeComponent.UBUNTU -> {
                    if(force || !File(paths.root,".base-verified").isFile) {
                        val artifact=selected(component)
                        manager.phase("下载 Ubuntu ${artifact.version}",EnvironmentState.DOWNLOADING)
                        // ensureBase downloads through downloadArtifact once, inside the replace transaction.
                        replace("ubuntu") { manager.ensureBase();manager.ensureUbuntuTools();probeInstalledDsh() }
                    } else manager.ensureUbuntuTools()
                    val meta=paths.metadata()
                    if(meta.optBoolean("ready"))paths.atomic("installed.json",meta.put("ubuntu",installedUbuntuVersion()).toString())
                }
            }
            refresh()
            manager.state.update { it.copy(stage="${if(component==RuntimeComponent.UBUNTU)"Ubuntu" else "Node.js"} 安装完成",error="",bytes=0,total=-1,download=null) }
        } finally { refresh();manager.state.update { it.copy(download=null) } }
    }
    fun uninstall(component: RuntimeComponent) = manager.launchExclusive("component-uninstall-${component.name.lowercase()}") {
        recover();migrateLegacyLayers()
        val targets=if(component==RuntimeComponent.UBUNTU)listOf(paths.root,paths.stage) else listOf(paths.node,paths.pnpm,paths.nodeCache,File(paths.root,"opt/node"),File(paths.root,"opt/pnpm"))
        targets.forEach(::owned)
        manager.phase("正在卸载 ${if(component==RuntimeComponent.UBUNTU)"Ubuntu" else "Node.js"}",EnvironmentState.INSTALLING)
        val journal=JSONObject().put("component",if(component==RuntimeComponent.UBUNTU)"ubuntu" else "node").put("action","uninstall")
        paths.atomic("component-transaction",journal.toString())
        withContext(NonCancellable) { recover();refresh() }
        com.tanuki.storage.L2sStore.collectUnreferenced(paths)
        manager.state.update { it.copy(stage="组件已卸载，项目、会话与其他组件保留",error="",download=null) }
    }
    fun uninstallDsh(version: String? = null) = manager.launchExclusive("component-uninstall-dsh") {
        val selected=version ?: paths.metadata().optString("dsh")
        require(selected.matches(VERSION)) { "未选择有效 DSH 版本" }
        val target=File(paths.engines,selected);owned(target)
        manager.phase("卸载 DSH $selected 程序",EnvironmentState.INSTALLING)
        paths.atomic("component-transaction",JSONObject().put("component","dsh").put("action","uninstall").put("version",selected).toString())
        withContext(NonCancellable) { recover();refresh() }
        com.tanuki.storage.L2sStore.collectUnreferenced(paths)
        manager.state.update { it.copy(stage="DSH $selected 已卸载；配置、会话、扩展与项目保留",error="") }
    }
    companion object { private val VERSION=Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?") }
}
