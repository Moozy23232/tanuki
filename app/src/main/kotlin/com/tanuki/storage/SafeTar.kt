package com.tanuki.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Extract only into an empty, application-owned staging directory. Links are deferred. */
object SafeTar {
    fun entryPath(root: Path, name: String): Path {
        require(!name.startsWith('/') && !name.contains('\u0000')) { "归档绝对路径" }
        val path = root.resolve(name).normalize()
        require(path.startsWith(root)) { "归档路径越界: $name" }
        return path
    }
    suspend fun extract(archive: File, destination: File, maxBytes: Long = 2L * 1024 * 1024 * 1024) {
        require(!destination.exists() || destination.list()?.isEmpty() == true) { "暂存目录必须为空" }
        destination.mkdirs()
        val root = destination.toPath().toAbsolutePath().normalize()
        data class Link(val path: Path, val target: String, val hard: Boolean)
        val links = mutableListOf<Link>(); val directories = mutableListOf<Pair<Path, Int>>()
        var bytes = 0L; var entries = 0
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val e = tar.nextTarEntry ?: break
                require(++entries <= 200000) { "归档文件数超限" }
                val path = entryPath(root, e.name)
                if (path == root) continue
                require(!Files.isSymbolicLink(path))
                when {
                    e.isDirectory -> { Files.createDirectories(path); directories += path to e.mode }
                    e.isSymbolicLink || e.isLink -> { links += Link(path, e.linkName, e.isLink) }
                    e.isFile -> {
                        require(e.size >= 0 && bytes + e.size <= maxBytes) { "解包大小超限" }
                        require(destination.usableSpace > e.size + 64L * 1024 * 1024) { "解包空间不足" }
                        Files.createDirectories(path.parent)
                        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "重复归档条目: ${e.name}" }
                        path.toFile().outputStream().use { out ->
                            val b = ByteArray(65536)
                            while (true) { currentCoroutineContext().ensureActive(); val n = tar.read(b); if (n < 0) break; out.write(b, 0, n); bytes += n; require(bytes <= maxBytes) }
                        }
                        android.system.Os.chmod(path.toString(), e.mode and 511)
                    }
                    e.isBlockDevice || e.isCharacterDevice || e.isFIFO -> Unit // supplied by /dev bind, never create host devices
                    else -> error("不支持的归档类型: ${e.name}")
                }
            }
        }
        // Hard links cannot traverse a symlink because all symlinks are still deferred.
        val fallbackHardLinks = mutableListOf<Pair<String, String>>()
        val hard = links.filter { it.hard }.toMutableList()
        while (hard.isNotEmpty()) {
            val before = hard.size
            val it = hard.iterator()
            while (it.hasNext()) {
                val l = it.next(); val target = entryPath(root, l.target.removePrefix("/"))
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    requireNoLinkParents(root, l.path); Files.createDirectories(l.path.parent)
                    try { Files.createLink(l.path, target) }
                    catch (_: java.nio.file.AccessDeniedException) {
                        // Android SELinux can forbid link(2). Bootstrap with a copy, then rebuild
                        // using PRoot's link2symlink semantics before declaring the rootfs ready.
                        Files.copy(target, l.path, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                        fallbackHardLinks += root.relativize(l.path).toString() to root.relativize(target).toString()
                    }
                    it.remove()
                }
            }
            require(hard.size < before) { "硬链接目标不存在" }
        }
        if (fallbackHardLinks.isNotEmpty()) {
            val json = org.json.JSONArray()
            fallbackHardLinks.forEach { (path, target) -> json.put(org.json.JSONObject().put("path", path).put("target", target)) }
            File(destination, ".hp-hardlinks.json").writeText(json.toString())
        }
        links.filter { !it.hard }.sortedByDescending { it.path.nameCount }.forEach { l ->
            currentCoroutineContext().ensureActive()
            requireNoLinkParents(root, l.path)
            val target = if (l.target.startsWith('/')) root.resolve(l.target.removePrefix("/")) else l.path.parent.resolve(l.target)
            val normalized = target.normalize()
            require(normalized.startsWith(root)) { "符号链接越界" }
            require(!Files.exists(l.path, LinkOption.NOFOLLOW_LINKS)) { "符号链接覆盖已有文件" }
            Files.createDirectories(l.path.parent)
            Files.createSymbolicLink(l.path, l.path.parent.relativize(normalized))
        }
        directories.sortedByDescending { it.first.nameCount }.forEach { (p, mode) -> android.system.Os.chmod(p.toString(), mode and 511) }
    }
    private fun requireNoLinkParents(root: Path, path: Path) {
        var p = path.parent
        while (p != null && p != root) { require(!Files.isSymbolicLink(p)) { "链接父目录不安全" }; p = p.parent }
    }
}
