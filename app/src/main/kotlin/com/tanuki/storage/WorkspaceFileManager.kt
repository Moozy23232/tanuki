package com.tanuki.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tanuki.runtime.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * Operations are serialized per manager and run on Dispatchers.IO.
 * The host must exclude guest/other-manager filesystem writers while using this API: Java path
 * checks and rename publication cannot protect against concurrent ancestor replacement.
 * Create/copy/move never overwrite. Text save replaces only a checked revision.
 * Recursive deletion can be partial on an underlying I/O failure.
 */
enum class FileLocation(val title: String, val guestPath: String, val writable: Boolean) {
    WORKSPACE("工作区", "/workspaces", true), HOME("用户目录", "/home/tanuki", false),
    UBUNTU("Ubuntu 文件", "/", false)
}

class WorkspaceFileManager(
    private val paths: Paths,
    private val mutation: Mutex = Mutex(),
    private val requireIdle: () -> Unit = {},
    private val storage: WorkspaceStorage = WorkspaceStorage(paths),
    val location: FileLocation = FileLocation.WORKSPACE
) {
    data class Entry(val path: String, val name: String, val directory: Boolean, val bytes: Long, val linked: Boolean, val modified: Long = 0)
    data class Preview(val text: String? = null, val image: Bitmap? = null, val revision: String? = null)
    fun inLocation(value: FileLocation) = WorkspaceFileManager(paths, mutation, requireIdle, storage, value)
    fun guestPath(relative: String) = (location.guestPath.trimEnd('/') + if (relative.isEmpty()) "" else "/$relative").ifEmpty { "/" }
    companion object { const val TEXT_LIMIT = 256 * 1024 }
    private val lock = Mutex()
    private val noFollow = LinkOption.NOFOLLOW_LINKS
    private val internalName = Regex("^\\.(?:proot\\.)?l2s\\..*")
    private fun reserved(name: String) = internalName.matches(name) || name.startsWith(".workspace-tmp-")
    private fun segment(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' } && !reserved(name)) { "名称无效或属于内部存储条目" }
    }
    // Mirrors Launcher.start bindings. UI paths are guest paths, never host-private absolute paths.
    // /dev, /proc, /sys and the credential-bearing browser-tools mount are intentionally not exposed.
    private val mounts get() = when (location) {
        FileLocation.WORKSPACE -> mapOf("" to paths.workspaces)
        FileLocation.HOME -> mapOf("" to paths.home)
        FileLocation.UBUNTU -> mapOf("" to paths.root, "workspaces" to paths.workspaces,
            "home/tanuki" to paths.home, "dsh-data" to paths.dshData, "opt/node" to paths.node,
            "opt/pnpm" to paths.pnpm, "opt/engines" to paths.engines, "tmp" to paths.guestTmp)
    }
    private fun root(): Path = paths.workspaces.toPath().toRealPath()
    private fun resolve(relative: String, entry: Boolean = false): Path {
        require(relative.isNotEmpty() || !entry) { "不能修改位置根目录" }
        if (relative.isNotEmpty()) relative.split('/').forEach(::segment)
        val binding = mounts.keys.filter { it.isEmpty() || relative == it || relative.startsWith("$it/") }.maxBy { it.length }
        val bound = mounts.getValue(binding).toPath()
        val logicalBase = paths.base.toPath().toAbsolutePath().normalize()
        val base = logicalBase.toRealPath()
        check(bound.normalize().startsWith(logicalBase)) { "目录超出允许范围" }
        var checked = base
        for (part in logicalBase.relativize(bound)) {
            checked = checked.resolve(part)
            check(Files.isDirectory(checked, noFollow) && !Files.isSymbolicLink(checked)) { "目录尚未安装、已移除或被重定向" }
        }
        val tail = if (binding.isEmpty()) relative else relative.removePrefix(binding).removePrefix("/")
        var result = checked
        val parts = tail.split('/')
        if (tail.isNotEmpty()) parts.forEachIndexed { index, part ->
            result = result.resolve(part)
            if (index < parts.lastIndex) check(Files.isDirectory(result, noFollow) && !Files.isSymbolicLink(result)) { "不能通过链接访问目录" }
        }
        return result
    }
    /** Relative paths use '/' and an empty string denotes the workspace root. */
    private fun directory(relative: String): Path = resolve(relative).also {
        check(Files.isDirectory(it, noFollow) && !Files.isSymbolicLink(it)) { "目录不存在或是符号链接" }
    }
    private fun destination(parent: String, name: String): Path {
        segment(name)
        return directory(parent).resolve(name).also { check(!Files.exists(it, noFollow)) { "同名条目已存在，未覆盖" } }
    }
    private fun payload(path: Path): Path {
        if (Files.isRegularFile(path, noFollow)) return path
        if (Files.isSymbolicLink(path)) {
            val target = L2sStore.payload(path, paths.base.toPath()) ?: throw IOException("不支持预览或复制普通符号链接")
            val store = paths.linkStore.toPath()
            val approvedStore = !Files.isSymbolicLink(store) && Files.isDirectory(store, noFollow) &&
                target.parent == store.toRealPath()
            check(target.startsWith(root()) || approvedStore ||
                (location != FileLocation.WORKSPACE && mounts.values.any { root ->
                    !Files.isSymbolicLink(root.toPath()) && root.isDirectory && target.startsWith(root.toPath().toRealPath())
                })) {
                "链接内容超出当前位置和允许的硬链接存储"
            }
            return target
        }
        throw IOException("不支持此文件类型，或文件已不存在")
    }
    private suspend fun <T> io(growing: Boolean = false, writing: Boolean = true, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutation.withLock {
            if (writing) check(location.writable) { "此位置只读；请在工作区编辑项目文件" }
            requireIdle()
            lock.withLock {
                if (growing) storage.requireCanStart()
                block()
            }
        }
    }

    suspend fun list(directory: String = ""): List<Entry> = io(writing = false) {
        val folder = directory(directory)
        val entries = linkedMapOf<String, Entry>()
        fun child(name: String) = if (directory.isEmpty()) name else "$directory/$name"
        Files.newDirectoryStream(folder).use { children ->
            for (path in children) {
                currentCoroutineContext().ensureActive()
                if (reserved(path.fileName.toString())) continue
                check(entries.size < 5000) { "目录超过 5000 项，请通过 DSH 处理或拆分目录" }
                val stat = Files.readAttributes(path, BasicFileAttributes::class.java, noFollow)
                val name = path.fileName.toString()
                entries[name] = Entry(child(name), name, stat.isDirectory,
                    if (stat.isSymbolicLink) runCatching { Files.size(payload(path)) }.getOrDefault(-1L) else stat.size(),
                    stat.isSymbolicLink, stat.lastModifiedTime().toMillis())
            }
        }
        // Mounted content wins over the image's empty mount-point directory.
        for ((mount, physical) in mounts) {
            val prefix = if (directory.isEmpty()) "" else "$directory/"
            if (mount.isNotEmpty() && mount.startsWith(prefix) && physical.isDirectory) {
                val name = mount.removePrefix(prefix).substringBefore('/')
                if (name.isNotEmpty()) entries[name] = Entry(child(name), name, true, 0, false)
            }
        }
        entries.values.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() }.thenBy { it.name })
    }
    suspend fun createFolder(parent: String, name: String) = io(growing = true) {
        val target = Files.createDirectory(destination(parent, name))
        try { storage.requireCanCommitImport() }
        catch (failure: Throwable) { Files.deleteIfExists(target); throw failure }
    }
    suspend fun createText(parent: String, name: String, text: String) = io(growing = true) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TEXT_LIMIT) { "文本不能超过 256 KiB" }
        val target = destination(parent, name)
        val stage = Files.createTempFile(target.parent, ".workspace-tmp-", ".part")
        try {
            writeStage(stage, bytes)
            currentCoroutineContext().ensureActive()
            storage.requireCanCommitImport()
            publish(stage, target)
        } finally { Files.deleteIfExists(stage) }
    }
    private fun writeStage(stage: Path, bytes: ByteArray) {
        java.nio.channels.FileChannel.open(stage, StandardOpenOption.WRITE, noFollow).use { channel ->
            val content = ByteBuffer.wrap(bytes)
            while (content.hasRemaining()) channel.write(content)
            channel.force(true)
        }
    }
    private fun publish(stage: Path, target: Path) {
        check(!Files.exists(target, noFollow)) { "同名条目已存在，未覆盖" }
        // Same-directory rename; deliberately omit REPLACE_EXISTING (ATOMIC_MOVE permits replacement).
        Files.move(stage, target)
    }
    suspend fun preview(relative: String): Preview = io(writing = false) {
        val file = payload(resolve(relative, true))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Files.newInputStream(file, noFollow).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val image = Files.newInputStream(file, noFollow).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("图片无法解码")
            Preview(image = image)
        } else {
            check(Files.size(file) <= TEXT_LIMIT) { "文本超过 256 KiB，无法预览" }
            val bytes = Files.newInputStream(file, noFollow).use { input ->
                val buffer = ByteArray(TEXT_LIMIT + 1)
                var count = 0
                while (count < buffer.size) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                check(count <= TEXT_LIMIT) { "文本超过 256 KiB，无法预览" }
                buffer.copyOf(count)
            }
            check(bytes.none { it == 0.toByte() }) { "此文件不是可预览的文本或图片" }
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: java.nio.charset.CharacterCodingException) { throw IOException("仅支持 UTF-8 文本预览") }
            Preview(text = text, revision = revision(bytes))
        }
    }
    private fun revision(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    /** Optimistic text save: an external change is never silently overwritten. Links are read-only. */
    suspend fun saveText(relative: String, text: String, expectedRevision: String) = io(growing = true) {
        val target = resolve(relative, true)
        check(Files.isRegularFile(target, noFollow) && !Files.isSymbolicLink(target)) { "链接或特殊文件仅可预览，请先复制为独立文件" }
        check(Files.size(target) <= TEXT_LIMIT) { "文件已变大，请重新打开" }
        val original = Files.newInputStream(target, noFollow).use { it.readBytes() }
        check(revision(original) == expectedRevision) { "文件已被修改，未覆盖；请复制当前草稿并重新打开文件" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= TEXT_LIMIT) { "文本不能超过 256 KiB" }
        val stage = Files.createTempFile(target.parent, ".workspace-tmp-", ".part")
        try {
            writeStage(stage, bytes)
            Files.setPosixFilePermissions(stage, Files.getPosixFilePermissions(target, noFollow))
            storage.requireCanCommitImport()
            currentCoroutineContext().ensureActive()
            // Save explicitly replaces this revision; unlike create/copy, this operation is an edit.
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(stage) }
    }
    suspend fun rename(relative: String, name: String) = move(relative, relative.substringBeforeLast('/', ""), name)
    suspend fun move(relative: String, parent: String, name: String = relative.substringAfterLast('/')) = io {
        val source = resolve(relative, true)
        val target = destination(parent, name)
        check(!target.startsWith(source)) { "不能移动到自身或子目录" }
        if (source.parent != target.parent) {
            check(!Files.isSymbolicLink(source)) { "链接仅支持原目录重命名；跨目录请复制后删除原条目" }
            inspectTree(source, rejectLinks = true)
        } else inspectTree(source, rejectLinks = Files.isDirectory(source, noFollow))
        publish(source, target)
    }
    suspend fun copy(relative: String, parent: String, name: String = relative.substringAfterLast('/')) = io(growing = true) {
        val source = resolve(relative, true)
        val target = destination(parent, name)
        check(!target.startsWith(source)) { "不能复制到自身或子目录" }
        inspectTree(source)
        val stage = Files.createTempDirectory(target.parent, ".workspace-tmp-")
        try {
            copyTree(source, stage.resolve("content"))
            currentCoroutineContext().ensureActive()
            storage.requireCanCommitImport()
            publish(stage.resolve("content"), target)
        } finally { removeTree(stage) }
    }
    suspend fun delete(relative: String) = io {
        val source = resolve(relative, true)
        inspectTree(source)
        currentCoroutineContext().ensureActive()
        try { removeTree(source) } // No link following: never remove backing through a published link.
        catch (error: IOException) { throw IOException("删除未完成，部分条目可能已删除；请刷新检查", error) }
    }
    private suspend fun inspectTree(source: Path, rejectLinks: Boolean = false) {
        val coroutine = currentCoroutineContext()
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            private fun inspect(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                coroutine.ensureActive()
                check(!reserved(path.fileName.toString())) { "目录包含内部硬链接存储或临时条目，不能操作" }
                check(!rejectLinks || !attrs.isSymbolicLink) { "目录包含链接，无法安全移动或重命名" }
                return FileVisitResult.CONTINUE
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = inspect(dir, attrs)
            override fun visitFile(file: Path, attrs: BasicFileAttributes) = inspect(file, attrs)
        })
    }
    private suspend fun copyTree(source: Path, target: Path, depth: Int = 0) {
        currentCoroutineContext().ensureActive()
        check(depth <= 128) { "目录层级过深" }
        if (Files.isDirectory(source, noFollow)) {
            Files.createDirectory(target)
            Files.newDirectoryStream(source).use { children -> for (child in children) copyTree(child, target.resolve(child.fileName), depth + 1) }
        } else {
            Files.newInputStream(payload(source), noFollow).use { input ->
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(paths.base.usableSpace - n >= WorkspaceCapacity.DISK_RESERVE) { "设备剩余空间不足，复制已取消" }
                        output.write(buffer, 0, n)
                    }
                }
            }
        }
    }
    private fun removeTree(source: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
