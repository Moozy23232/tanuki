package com.tanuki.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

object Redact {
    fun text(value: String): String = value
        .replace(Regex("(?i)([?&](?:token|auth|key|code)=)[^\\s&#]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"), "$1[REDACTED]")
        .replace(Regex("(?im)^(set-cookie:).*"), "$1 [REDACTED]")
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[REDACTED]")
        .replace(Regex("(?i)((?:api[_-]?key|password|secret|token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"'}]+"), "$1[REDACTED]")
}
class EventLog(private val paths: Paths, scope: CoroutineScope) {
    val recent = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val activityAtMillis = kotlinx.coroutines.flow.MutableStateFlow(0L)
    // A bounded diagnostic ring must not block input behind disk I/O or grow with guest output.
    private val diskQueue = kotlinx.coroutines.channels.Channel<String>(512, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private var writeFailed = false
    init {
        scope.launch(Dispatchers.IO) {
            for (first in diskQueue) {
                val batch = StringBuilder(first)
                repeat(63) { diskQueue.tryReceive().getOrNull()?.let { batch.append(it) } }
                val file = File(paths.logs, "runtime.log")
                try {
                    if (file.length() > 1024 * 1024) {
                        val previous = File(paths.logs, "runtime.previous.log")
                        previous.delete()
                        if (!file.renameTo(previous)) throw java.io.IOException("log rotation failed")
                    }
                    file.appendText(batch.toString())
                    writeFailed = false
                } catch (_: java.io.IOException) {
                    if (!writeFailed) android.util.Log.w("TANUKI_RUNTIME", "Private log storage unavailable")
                    writeFailed = true
                }
            }
        }
    }
    @Synchronized fun append(line: String) {
        val safe = Redact.text(line)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - activityAtMillis.value >= 500) activityAtMillis.value = now
        recent.value = (recent.value + safe.take(400)).takeLast(16)
        diskQueue.trySend("${java.time.Instant.now()} ${safe.take(12000)}\n")
        if (line.startsWith("app start ") || line.startsWith("stderr TANUKI_IDENTITY ") || line.startsWith("exit=")) android.util.Log.i("TANUKI_RUNTIME", safe)
    }
    fun tail():String = runCatching {
        val file=File(paths.logs,"runtime.log")
        if(!file.exists())return@runCatching "暂无日志"
        java.io.RandomAccessFile(file,"r").use { input ->
            val size=minOf(input.length(),24000).toInt();input.seek(input.length()-size)
            val bytes=ByteArray(size);input.readFully(bytes);bytes.toString(Charsets.UTF_8)
        }
    }.getOrDefault("日志暂时无法读取")
}
data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)
class Child(val process: java.lang.Process, private val log: EventLog, val label: String) {
    @Volatile var stopping: Boolean = false
        private set
    private val pid = AtomicInteger(0)
    @Volatile private var startTime: String? = null
    internal val identified: Boolean get() = pid.get() > 1 && startTime != null
    private fun birth(id: Int): String? = runCatching {
        File("/proc/$id/stat").readText().substringAfterLast(") ").split(' ')[19]
    }.getOrNull()
    @Synchronized fun observe(line: String) {
        if (!line.startsWith("TANUKI_IDENTITY ") || pid.get()!=0) return
        val fields=line.substringAfter(' ').split(' ').mapNotNull { field ->
            field.indexOf('=').takeIf { it>0 }?.let { field.take(it) to field.substring(it+1) }
        }.toMap()
        val id=fields["pid"]?.toIntOrNull() ?: return
        if(id<=1 || fields["ppid"]?.toIntOrNull()!=AndroidProcess.myPid() ||
            fields["uid"]?.toIntOrNull()!=AndroidProcess.myUid() || fields["pgid"]?.toIntOrNull()!=id)return
        startTime=birth(id) ?: return
        pid.set(id)
    }
    suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
        stopping = true
        val id = pid.get()
        // A live Java Process plus matching Linux start time protects against PID reuse.
        val owned = process.isAlive && id > 1 && startTime != null && birth(id) == startTime
        var signalError:android.system.ErrnoException?=null
        fun signal(target:Int,value:Int) {
            try { android.system.Os.kill(target,value) }
            catch(error:android.system.ErrnoException) {
                if(error.errno!=android.system.OsConstants.ESRCH)signalError=error
            }
        }
        fun stat(candidate:Int):List<String>?=runCatching {
            File("/proc/$candidate/stat").readText().substringAfterLast(") ").split(' ')
        }.getOrNull()
        fun parent(candidate:Int):Int?=stat(candidate)?.getOrNull(1)?.toIntOrNull()
        // Retain member birth times so escalation remains safe if the leader exits first.
        val members=buildMap<Int,String> {
            if(owned) {
                startTime?.let { put(id,it) }
                File("/proc").list()?.forEach { name ->
                    name.toIntOrNull()?.let { member ->
                        stat(member)?.takeIf { it.getOrNull(2)?.toIntOrNull()==id }?.getOrNull(19)?.let { put(member,it) }
                    }
                }
            }
        }
        fun groupAlive():Boolean=members.any { (member,born) ->
            stat(member)?.let { it.getOrNull(19)==born && it.getOrNull(2)?.toIntOrNull()==id && it.firstOrNull()!="Z" }==true
        }
        if (owned) {
            // Some Android kernels omit task/children; scan visible processes as fallback.
            val guest = runCatching { File("/proc/$id/task/$id/children").readText().trim().split(' ').firstOrNull()?.toIntOrNull() }.getOrNull()
                ?: members.keys.firstOrNull { it!=id && parent(it)==id }
            if (guest != null && guest > 1 && parent(guest)==id) signal(guest,15)
            withTimeoutOrNull(3000) { while (process.isAlive) delay(50) }
            if (groupAlive()) signal(-id,15)
        } else if (process.isAlive) process.destroy()
        withTimeoutOrNull(1000) { while (process.isAlive || groupAlive()) delay(50) }
        if(groupAlive())signal(-id,9)
        if (process.isAlive) {
            if (id > 1 && startTime != null && birth(id) == startTime) signal(id,9)
            else process.destroyForcibly()
        }
        withTimeoutOrNull(1000) { while(process.isAlive || groupAlive())delay(25) }
        check(!process.isAlive && !groupAlive()) { "运行进程未能停止，请重试停止后再操作组件" }
        signalError?.let { throw it }
        log.append("$label stopped; exit=${process.exitValue()}")
    }
}
class Launcher(private val context: Context, private val paths: Paths, private val log: EventLog) {
    private companion object {
        // Linux PDEATHSIG follows the creating thread. Coroutine workers may retire
        // while a guest is still running, so all native children share a lasting owner.
        val processOwner=Executors.newSingleThreadExecutor { task ->
            Thread(task,"tanuki-process-owner").apply { isDaemon=true }
        }
        fun spawn(builder:ProcessBuilder):java.lang.Process {
            val pending=processOwner.submit<java.lang.Process> { builder.start() }
            var interrupted=false
            try {
                // Complete ownership handoff before returning, including on interrupt.
                while(true) {
                    try { return pending.get() }
                    catch(_:InterruptedException) { interrupted=true }
                    catch(error:ExecutionException) { throw error.cause ?: error }
                }
            } finally { if(interrupted)Thread.currentThread().interrupt() }
        }
    }
    fun start(root: File, args: List<String>, home: File = paths.home, dshHome: File = paths.dshData, workspace: File = paths.workspaces, engineHome: File = paths.engines, nodeHome: File? = null): Child {
        val lib = context.applicationInfo.nativeLibraryDir
        val node = nodeHome ?: if (File(paths.node, "bin/node").isFile || !File(root, "opt/node/bin/node").isFile) paths.node else File(root, "opt/node")
        val pnpm = if (paths.pnpm.exists() || !File(root, "opt/pnpm").exists()) paths.pnpm else File(root, "opt/pnpm")
        check(!java.nio.file.Files.isSymbolicLink(paths.downloads.toPath()) &&
            !java.nio.file.Files.isSymbolicLink(paths.npmArchives.toPath())) { "下载缓存目录被重定向" }
        listOf(paths.npmArchives, File(root,"opt/download-cache"), node, pnpm, paths.nodeCache, home, dshHome, workspace, File(root, "opt/node"), File(root, "opt/pnpm"), File(root, "opt/node-cache"), File(root, "opt/engines"), File(root, "workspaces"), File(root, "home/tanuki"), File(root, "dsh-data")).forEach { it.mkdirs() }
        com.tanuki.storage.L2sStore.prepare(paths)
        for (directory in listOf(paths.guestTmp, paths.prootTmp, paths.sharedMemory)) {
            check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) { "运行临时目录被重定向，已停止启动" }
            java.nio.file.Files.createDirectories(directory.toPath())
            check(java.nio.file.Files.isDirectory(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "运行临时路径不是目录" }
            android.system.Os.chmod(directory.absolutePath, 448)
        }
        val browserTools = com.tanuki.browser.BrowserIntegration.directory(paths)
        val browserBinding = if (home == paths.home && dshHome == paths.dshData && workspace == paths.workspaces &&
            browserTools.isDirectory && !java.nio.file.Files.isSymbolicLink(browserTools.toPath())) {
            File(root, "opt/tanuki").mkdirs()
            listOf("-b", "${browserTools.absolutePath}:/opt/tanuki")
        } else emptyList()
        val command = listOf("$lib/libsupervisor.so", "$lib/libproot.so", "--link2symlink", "-0", "-r", root.absolutePath) + browserBinding + listOf(
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${paths.guestTmp.absolutePath}:/tmp",
            // link2symlink writes host-absolute targets; the same guest path must resolve to this store.
            "-b", "${paths.linkStore.absolutePath}:${paths.linkStore.absolutePath}",
            "-b", "${paths.sharedMemory.absolutePath}:/dev/shm", "-b", "${home.absolutePath}:/home/tanuki",
            "-b", "${dshHome.absolutePath}:/dsh-data", "-b", "${workspace.absolutePath}:/workspaces",
            "-b", "${paths.npmArchives.absolutePath}:/opt/download-cache",
            "-b", "${engineHome.absolutePath}:/opt/engines", "-b", "${node.absolutePath}:/opt/node", "-b", "${pnpm.absolutePath}:/opt/pnpm", "-b", "${paths.nodeCache.absolutePath}:/opt/node-cache", "-w", "/workspaces", "/usr/bin/env", "-i",
            "HOME=/home/tanuki", "DSH_HOME=/dsh-data", "PATH=/opt/pnpm/${RuntimeManager.PNPM}/bin:/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp", "LANG=C.UTF-8", "TERM=xterm-256color", "DEBIAN_FRONTEND=noninteractive",
            "DPKG_DEB_THREADS_MAX=1", "XZ_DEFAULTS=-T1", "ZSTD_NBTHREADS=1", "UV_THREADPOOL_SIZE=4", "NODE_COMPILE_CACHE=/opt/node-cache",
            "npm_config_fetch_timeout=60000", "npm_config_fetch_retries=2", "npm_config_fetch_retry_mintimeout=2000", "npm_config_fetch_retry_maxtimeout=10000") + args
        val pb = ProcessBuilder(command).directory(paths.base)
        pb.environment().apply {
            clear(); put("PATH", "/system/bin"); put("PROOT_LOADER", "$lib/libproot-loader.so")
            // Keep loader scratch separate from the guest /tmp and component swap directories.
            put("PROOT_TMP_DIR", paths.prootTmp.absolutePath)
            put("PROOT_L2S_DIR", paths.linkStore.absolutePath)
        }
        log.append("launch $args root=${root.absolutePath} hostUid=${AndroidProcess.myUid()} appPid=${AndroidProcess.myPid()} native=$lib")
        return Child(spawn(pb), log, args.firstOrNull() ?: "child")
    }
    suspend fun collect(child: Child, onLine: (String) -> Unit = {}): CommandResult = coroutineScope {
        fun reader(stream: java.io.InputStream, channel: String) = async(Dispatchers.IO) {
            val text = StringBuilder()
            val line=java.io.ByteArrayOutputStream();var clipped=false
            fun emit() {
                val decoded=line.toString("UTF-8")
                val raw=(if(clipped || decoded.length>12000)"[单行输出已截断] " else "")+decoded.take(12000)
                line.reset();clipped=false
                if(channel=="stderr")child.observe(raw)
                onLine(raw)
                val safe=Redact.text(raw);log.append("$channel $safe")
                text.appendLine(safe.take(12000))
                // Preserve the final apt/dpkg failure instead of only its preamble.
                if(text.length>131072)text.delete(0,text.length-131072)
            }
            try {
                stream.use { input ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val ready=input.available()
                        if(ready==0){if(!child.process.isAlive)break;delay(100);continue}
                        // Read only bytes already present. A descendant retaining a pipe
                        // cannot block cancellation or the Java process reaper indefinitely.
                        val count=input.read(buffer,0,minOf(buffer.size,ready));if(count<0)break
                        for(i in 0 until count) {
                            val c=buffer[i].toInt() and 255
                            if(c==10 || c==13){if(line.size()>0 || clipped)emit()}
                            else if(line.size()<49152)line.write(c) else clipped=true
                        }
                    }
                    if(line.size()>0 || clipped)emit()
                }
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                if (!child.stopping) {
                    withTimeoutOrNull(1000) { while (child.process.isAlive) delay(25) }
                    if (child.process.isAlive) throw e
                }
            }
            text.toString()
        }
        val out = reader(child.process.inputStream, "stdout")
        val err = reader(child.process.errorStream, "stderr")
        try {
            while (child.process.isAlive) delay(50)
            val code = child.process.exitValue()
            CommandResult(code, out.await(), err.await()).also { log.append("exit=$code") }
        } finally { if (child.process.isAlive) child.stop() }
    }
    suspend fun run(root: File, args: List<String>, timeoutMs: Long = 120000, home: File = paths.home, dshHome: File = paths.dshData, workspace: File = paths.workspaces, engineHome: File = paths.engines, nodeHome: File? = null, onLine: (String) -> Unit = {}): CommandResult {
        val child = start(root, args, home, dshHome, workspace, engineHome, nodeHome)
        return try { withTimeout(timeoutMs) { collect(child,onLine) } } finally { if (child.process.isAlive) child.stop() }
    }
}
