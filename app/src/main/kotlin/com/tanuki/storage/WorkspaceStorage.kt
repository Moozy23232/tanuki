package com.tanuki.storage

import android.util.AtomicFile
import com.tanuki.runtime.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.LinkOption

/** A policy for a shared directory, not a filesystem quota or a disk reservation. */
object WorkspaceCapacity {
    const val GIB = 1024L * 1024 * 1024
    const val MIN_LIMIT = 64L * 1024 * 1024
    const val DISK_RESERVE = 64L * 1024 * 1024
    const val MAX_IMPORT = 512L * 1024 * 1024

    fun parseGiB(text: String): Long {
        val normalized = text.trim().replace(',', '.')
        require(normalized.length in 1..32 && normalized.matches(Regex("[0-9]+(?:\\.[0-9]{1,6})?"))) { "请输入有效容量，最多保留六位小数" }
        val bytes = try {
            BigDecimal(normalized).multiply(BigDecimal.valueOf(GIB)).setScale(0, RoundingMode.CEILING).longValueExact()
        } catch (_: ArithmeticException) { throw IllegalArgumentException("容量数值过大") }
        require(bytes >= MIN_LIMIT) { "容量预算至少为 64 MiB（0.0625 GiB）" }
        return bytes
    }

    fun giBText(bytes: Long): String = BigDecimal.valueOf(bytes)
        .divide(BigDecimal.valueOf(GIB), 6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

    fun availableForWorkspace(used: Long, free: Long): Long {
        val remaining = (free - DISK_RESERVE).coerceAtLeast(0)
        return if (used > Long.MAX_VALUE - remaining) Long.MAX_VALUE else used + remaining
    }

    fun validateLimit(limit: Long, used: Long, free: Long) {
        require(limit >= MIN_LIMIT) { "容量预算至少为 64 MiB" }
        require(limit >= used) { "容量预算不能小于当前已用空间；已有文件不会被删除" }
        require(limit <= availableForWorkspace(used, free)) { "容量预算超过设备当前可供项目使用的空间" }
    }
}

data class WorkspaceStorageState(
    val limitBytes: Long? = null,
    val usedBytes: Long? = null,
    val freeBytes: Long = 0,
    val measuring: Boolean = false,
    val unreadableEntries: Int = 0,
    val measuredAtMillis: Long = 0,
    val error: String? = null,
    val configurationError: String? = null
) {
    val complete: Boolean get() = usedBytes != null && !measuring && error == null && unreadableEntries == 0
    val overBudget: Boolean get() = !measuring && usedBytes != null && limitBytes != null && usedBytes > limitBytes
    val lowDisk: Boolean get() = freeBytes <= WorkspaceCapacity.DISK_RESERVE
    val availableBytes: Long? get() {
        if (!complete) return null
        val physical = (freeBytes - WorkspaceCapacity.DISK_RESERVE).coerceAtLeast(0)
        return minOf(physical, limitBytes?.let { (it - usedBytes!!).coerceAtLeast(0) } ?: physical)
    }
}

class WorkspaceStorage(private val paths: Paths) {
    private val measurement = Mutex()
    private val settings = AtomicFile(File(paths.state, "workspace-capacity.json"))
    private val importJournal = AtomicFile(File(paths.state, "workspace-import.json"))
    @Volatile private var importInProgress = false
    private var incompleteLiveScans = 0
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()

    private fun load(): WorkspaceStorageState {
        return try {
            val json = try {
                settings.openRead().bufferedReader().use { JSONObject(it.readText()) }
            } catch (error: java.io.FileNotFoundException) {
                if (settings.baseFile.exists() || File(settings.baseFile.path + ".bak").exists()) throw error
                return WorkspaceStorageState(freeBytes = paths.base.usableSpace)
            }
            require(json.getInt("schema") == 1 && json.has("limitBytes"))
            val limit = if (json.isNull("limitBytes")) null else {
                val raw = json.get("limitBytes")
                require(raw is Long || raw is Int)
                (raw as Number).toLong().also { require(it >= WorkspaceCapacity.MIN_LIMIT) }
            }
            WorkspaceStorageState(limitBytes = limit, freeBytes = paths.base.usableSpace)
        } catch (_: Exception) {
            WorkspaceStorageState(freeBytes = paths.base.usableSpace,
                configurationError = "容量设置无法读取，请重新保存预算；现有项目已保留")
        }
    }

    private fun requireWorkspaceDirectory() {
        val root = paths.workspaces.toPath()
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "项目目录不存在或已被重定向" }
        check(paths.workspaces.canonicalFile.parentFile == paths.base.canonicalFile) { "项目目录超出应用存储范围" }
    }

    suspend fun recoverInterruptedImport(): Boolean = measurement.withLock {
        check(!importInProgress) { "项目导入正在进行" }
        recoverImportLocked()
    }

    private fun recoverImportLocked(): Boolean {
        if (!importJournal.baseFile.exists() && !File(importJournal.baseFile.path + ".bak").exists()) return false
        requireWorkspaceDirectory()
        val record = try { importJournal.openRead().bufferedReader().use { JSONObject(it.readText()) } }
        catch (error: Exception) { throw IllegalStateException("未完成导入记录无法读取；现有项目已保留", error) }
        val name = record.optString("stage")
        val token = runCatching { java.util.UUID.fromString(name.removePrefix(".import-")) }.getOrNull()
        check(record.optInt("schema") == 1 && token != null && name == ".import-$token") { "未完成导入记录无效；现有项目已保留" }
        // Only a journaled UUID stage belongs to this transaction. Never sweep by prefix.
        com.tanuki.runtime.RuntimeManager.deleteTree(File(paths.workspaces, name))
        importJournal.delete()
        return true
    }

    suspend fun refresh(): WorkspaceStorageState = measurement.withLock { measureLocked() }

    private suspend fun measureLocked(stopAfterBytes: Long? = null): WorkspaceStorageState {
        mutableState.update { it.copy(measuring = true, error = null) }
        try {
            requireWorkspaceDirectory()
            val sample = DirectoryUsage.measure(paths.workspaces, stopAfterBytes = stopAfterBytes, linkedFilesRoot = paths.base)
            val problem = when {
                sample.incompleteReason != null -> sample.incompleteReason
                sample.unreadableEntries > 0 -> "部分项目文件无法统计，当前用量不完整"
                !sample.complete -> "项目空间统计未完成"
                else -> null
            }
            mutableState.update { it.copy(usedBytes = sample.allocatedBytes, freeBytes = paths.base.usableSpace,
                unreadableEntries = sample.unreadableEntries, measuredAtMillis = System.currentTimeMillis(), error = problem) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val message = if (error is IllegalStateException) error.message else "无法统计项目空间，请检查目录后重试"
            mutableState.update { it.copy(usedBytes = null, measuredAtMillis = 0, unreadableEntries = 0,
                freeBytes = paths.base.usableSpace, error = message ?: "无法统计项目空间") }
        } finally { mutableState.update { it.copy(measuring = false) } }
        return mutableState.value
    }

    /** The caller serializes this with startup/import and requires the runtime to be stopped. */
    suspend fun configure(limitBytes: Long?) = measurement.withLock {
        check(!importInProgress) { "请等待当前项目导入完成" }
        recoverImportLocked()
        if (limitBytes != null) {
            val sample = measureLocked()
            require(sample.complete) { "${sample.error ?: "请先完成项目空间统计"}；也可先选“跟随可用空间”解除自定预算" }
            WorkspaceCapacity.validateLimit(limitBytes, sample.usedBytes!!, sample.freeBytes)
        }
        val json = JSONObject().put("schema", 1).put("limitBytes", limitBytes ?: JSONObject.NULL)
        val output = settings.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); settings.finishWrite(output) }
        catch (error: Throwable) { settings.failWrite(output); throw error }
        incompleteLiveScans = 0
        mutableState.update { it.copy(limitBytes = limitBytes, configurationError = null, freeBytes = paths.base.usableSpace) }
    }

    private fun requireConfiguration() {
        check(mutableState.value.configurationError == null) { mutableState.value.configurationError!! }
    }

    /** Fresh measurements are authoritative only at this app-controlled admission point. */
    suspend fun requireCanStart() {
        requireConfiguration()
        recoverInterruptedImport()
        requireWorkspaceDirectory()
        val current = if (mutableState.value.limitBytes != null) refresh() else {
            mutableState.update { it.copy(freeBytes = paths.base.usableSpace) }
            mutableState.value
        }
        check(!current.lowDisk) { "设备剩余空间不足 64 MiB，请先清理下载缓存或设备存储" }
        if (current.limitBytes != null) {
            check(!current.overBudget) { "项目空间已超出容量预算，请在设置中调高预算或改为跟随可用空间" }
            check(current.complete) { "${current.error ?: "项目空间统计不完整"}；可先改为“跟随可用空间”再启动" }
        }
    }

    private suspend fun beginImport(fileCount: Int): ImportAllowance = measurement.withLock {
        requireConfiguration()
        require(fileCount > 0)
        check(!importInProgress) { "已有项目导入正在进行" }
        recoverImportLocked()
        val sample = measureLocked()
        check(sample.complete) { "${sample.error ?: "项目空间统计不完整"}；请刷新，或先改为“跟随可用空间”再重试" }
        val allowance = ImportAllowance(sample.limitBytes?.let { (it - sample.usedBytes!!).coerceAtLeast(0) }, fileCount)
        allowance.beforeWrite(0)
        importInProgress = true
        allowance
    }

    inner class ImportAllowance internal constructor(private val remaining: Long?, fileCount: Int) {
        // A conservative estimate while streaming; final allocation is checked before rename.
        private val blockSize = runCatching { android.system.Os.statvfs(paths.base.absolutePath).f_frsize.coerceIn(4096, 65536) }.getOrDefault(4096L)
        private val overhead = (fileCount.toLong() + 4L) * blockSize
        private var payload = 0L
        fun beforeWrite(count: Int) {
            require(count >= 0)
            val next = Math.addExact(payload, count.toLong())
            check(next <= WorkspaceCapacity.MAX_IMPORT) { "单次导入超过 512 MiB，请分批导入" }
            val estimate = Math.addExact(next, overhead)
            check(remaining == null || estimate <= remaining) { "导入将超过项目容量预算，已保留原有项目" }
            check(paths.base.usableSpace - WorkspaceCapacity.DISK_RESERVE >= count.toLong() + overhead) { "设备剩余空间不足，已保留原有项目" }
            payload = next
        }
    }

    /** Stage is already inside workspaces: never add payload bytes to this measurement again. */
    suspend fun requireCanCommitImport() {
        requireConfiguration()
        val sample = refresh()
        check(sample.complete) { sample.error ?: "无法确认导入后的空间占用" }
        check(sample.limitBytes == null || sample.usedBytes!! <= sample.limitBytes) { "导入后实际占用超过容量预算，已保留原有项目" }
        check(!sample.lowDisk) { "导入后设备剩余空间不足，已保留原有项目" }
    }

    /** Caller holds RuntimeManager.mutation and has confirmed that DSH is stopped. */
    suspend fun importFiles(names: List<String>, openSource: (Int) -> InputStream): File = withContext(Dispatchers.IO) {
        val token = java.util.UUID.randomUUID().toString()
        val stage = File(paths.workspaces, ".import-$token")
        val allowance = beginImport(names.size)
        var recorded = false
        var stageOwned = false
        var committed = false
        var originalFailure: Throwable? = null
        try {
            check(!Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) { "导入临时目录已存在" }
            val record = JSONObject().put("schema", 1).put("stage", stage.name)
            val output = importJournal.startWrite()
            try { output.write(record.toString().toByteArray(Charsets.UTF_8)); importJournal.finishWrite(output) }
            catch (error: Throwable) { importJournal.failWrite(output); throw error }
            recorded = true
            Files.createDirectory(stage.toPath())
            stageOwned = true
            names.forEachIndexed { index, display ->
                currentCoroutineContext().ensureActive()
                val name = File(display).name
                require(name !in setOf("", ".", "..") && !name.contains('\u0000') && !File(stage, name).exists()) { "导入文件名重复或不安全" }
                allowance.beforeWrite(0)
                openSource(index).use { input -> File(stage, name).outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        allowance.beforeWrite(count)
                        output.write(buffer, 0, count)
                    }
                } }
            }
            requireCanCommitImport()
            val destination = File(paths.workspaces, "import-$token")
            check(!destination.exists() && stage.renameTo(destination)) { "无法提交项目导入，原有项目已保留" }
            committed = true
            destination
        } catch (error: Throwable) { originalFailure = error; throw error }
        finally {
            withContext(NonCancellable) {
                var cleanupFailed = false
                try { if (stageOwned) com.tanuki.runtime.RuntimeManager.deleteTree(stage) }
                catch (cleanup: Throwable) {
                    cleanupFailed = true
                    val failure = originalFailure
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                } finally {
                    if (recorded && !cleanupFailed) importJournal.delete()
                    importInProgress = false
                    if (!committed || cleanupFailed) refresh()
                    else mutableState.update { it.copy(freeBytes = paths.base.usableSpace) }
                    if (cleanupFailed) mutableState.update { it.copy(error = "未完成的导入目录清理失败，下次操作前将重试") }
                }
            }
        }
    }

    /** Called by a watcher tied to one runtime; it does not itself terminate anything. */
    suspend fun runtimeViolation(): String? = measurement.withLock {
        val sample = if (mutableState.value.limitBytes == null) {
            mutableState.update { it.copy(freeBytes = paths.base.usableSpace) }
            mutableState.value
        } else measureLocked(stopAfterBytes = mutableState.value.limitBytes)
        when {
            sample.configurationError != null -> sample.configurationError
            sample.lowDisk -> "设备剩余空间不足 64 MiB"
            sample.overBudget -> "项目空间已超出容量预算"
            sample.limitBytes != null && !sample.complete -> {
                incompleteLiveScans++
                if (incompleteLiveScans >= 2) "项目空间连续统计失败，无法确认容量预算；可改为跟随可用空间后重试" else null
            }
            else -> { incompleteLiveScans = 0; null }
        }
    }
}
