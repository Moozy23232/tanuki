package com.tanuki.storage

import android.system.Os
import com.tanuki.runtime.Paths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** PRoot hardlinks are two symlinks and one payload, not ordinary user symlinks. */
object L2sStore {
    private val intermediateName = Regex("^\\.(?:proot\\.)?l2s\\..+[0-9]{4}$")
    private val storedName = Regex("^\\.(?:proot\\.)?l2s\\..+[0-9]{4}(?:\\.[0-9]{4,})?$")

    /** A stable app-private inode store; component replacement must never rename or remove it. */
    fun prepare(paths: Paths) {
        val directory = paths.linkStore.toPath()
        check(!Files.isSymbolicLink(directory)) { "硬链接存储目录被重定向，已停止启动" }
        Files.createDirectories(directory)
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "硬链接存储路径不是目录" }
        check(Os.stat(directory.toString()).st_dev == Os.stat(paths.base.absolutePath).st_dev) {
            "硬链接存储与运行环境必须位于同一文件系统"
        }
        Os.chmod(directory.toString(), 448) // 0700; owned by the ordinary Android application UID.
    }

    /**
     * Return a verified regular payload for a PRoot link, or null for an ordinary file/symlink.
     * Both legacy in-tree chains and the persistent store are supported. A broken recognized
     * chain is an error: callers must not silently omit a project file or invent empty contents.
     */
    fun payload(path: Path, allowedRoot: Path): Path? {
        if (!Files.isSymbolicLink(path)) return null
        val first = Files.readSymbolicLink(path)
        if (!intermediateName.matches(first.fileName?.toString().orEmpty())) return null
        fun target(from: Path, link: Path) = (if (link.isAbsolute) link else from.parent.resolve(link)).normalize().toAbsolutePath()
        val root = allowedRoot.toRealPath()
        fun confined(candidate: Path): Path {
            val parent = candidate.parent.toRealPath()
            if (!parent.startsWith(root)) throw IOException("硬链接目标超出应用文件区：${path.fileName}")
            return parent.resolve(candidate.fileName)
        }
        try {
            val intermediate = confined(target(path, first))
            if (!Files.isSymbolicLink(intermediate)) throw IOException("硬链接索引缺失：${path.fileName}")
            val last = confined(target(intermediate, Files.readSymbolicLink(intermediate)))
            if (last.parent != intermediate.parent ||
                !last.fileName.toString().matches(Regex(Regex.escape(intermediate.fileName.toString()) + "\\.[0-9]{4,}"))) {
                throw IOException("硬链接索引无效：${path.fileName}")
            }
            if (!Files.isRegularFile(last, LinkOption.NOFOLLOW_LINKS)) throw IOException("硬链接内容缺失：${path.fileName}")
            return last
        } catch (missing: NoSuchFileException) {
            throw IOException("硬链接内容缺失：${path.fileName}", missing)
        }
    }

    /**
     * Call only with the runtime mutation lock held and all its guest processes stopped.
     * Scan *all* app files so candidate installs, retained data and old snapshots retain their
     * references. An incomplete scan deletes nothing. No user file contents are read.
     */
    suspend fun collectUnreferenced(paths: Paths): Int {
        if (!Files.exists(paths.linkStore.toPath(), LinkOption.NOFOLLOW_LINKS)) return 0
        val store = paths.linkStore.toPath().toRealPath()
        if (!Files.isDirectory(store, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(paths.linkStore.toPath())) return 0
        val coroutine = currentCoroutineContext()
        val keep = HashSet<Path>()
        var entries = 0
        val started = System.nanoTime()
        fun checked() {
            coroutine.ensureActive()
            if (++entries > 250000 || System.nanoTime() - started > 10_000_000_000L) throw IOException("硬链接引用统计未完成")
        }
        fun retain(initial: Path) {
            var next = initial
            repeat(4) {
                if (!storedName.matches(next.fileName?.toString().orEmpty())) return
                val parent = try { next.parent.toRealPath() } catch (_: NoSuchFileException) { return }
                next = parent.resolve(next.fileName)
                if (next.parent != store || !keep.add(next)) return
                if (!Files.isSymbolicLink(next)) return
                val target = Files.readSymbolicLink(next)
                next = (if (target.isAbsolute) target else next.parent.resolve(target)).normalize()
            }
        }
        try {
            Files.walkFileTree(paths.base.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checked()
                    return if (dir.toRealPath() == store) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checked()
                    if (attrs.isSymbolicLink) {
                        val target = Files.readSymbolicLink(file)
                        retain(if (target.isAbsolute) target else file.parent.resolve(target))
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: IOException) { return 0 }
        var removed = 0
        Files.newDirectoryStream(store).use { children ->
            for (file in children) {
                coroutine.ensureActive()
                if (storedName.matches(file.fileName.toString()) && file !in keep &&
                    !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(file)) removed++
            }
        }
        return removed
    }
}
