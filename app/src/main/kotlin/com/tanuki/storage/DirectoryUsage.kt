package com.tanuki.storage

import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

object DirectoryUsage {
    private data class Inode(val device: Long, val number: Long)
    data class Usage(
        val allocatedBytes: Long,
        val unreadableEntries: Int,
        val complete: Boolean = true,
        val incompleteReason: String? = null
    )

    suspend fun measure(directory: File, maxEntries: Int = 250000, maxMillis: Long = 15000, stopAfterBytes: Long? = null, linkedFilesRoot: File = directory): Usage {
        require(maxEntries > 0 && maxMillis in 1..60000 && (stopAfterBytes == null || stopAfterBytes >= 0))
        if (!directory.exists()) return Usage(0, 0)
        val coroutine = currentCoroutineContext()
        val started = System.nanoTime()
        val inodes = HashSet<Inode>()
        var bytes = 0L
        var unreadable = 0
        var entries = 0
        var incomplete: String? = null
        fun withinBudget(): Boolean {
            coroutine.ensureActive()
            if (entries >= maxEntries) incomplete = "目录条目超过单次统计上限"
            if (System.nanoTime() - started >= maxMillis * 1000000L) incomplete = "目录统计超时，请稍后重试"
            return incomplete == null
        }
        fun count(path: Path): FileVisitResult {
            if (!withinBudget()) return FileVisitResult.TERMINATE
            entries++
            try {
                fun add(stat: android.system.StructStat) {
                    if (inodes.add(Inode(stat.st_dev, stat.st_ino))) {
                        bytes = Math.addExact(bytes, Math.multiplyExact(stat.st_blocks, 512L))
                    }
                }
                add(Os.lstat(path.toString()))
                L2sStore.payload(path, linkedFilesRoot.toPath())?.let { add(Os.lstat(it.toString())) }
                if (stopAfterBytes != null && bytes > stopAfterBytes) {
                    incomplete = "项目空间已超出预算，统计提前结束"
                    return FileVisitResult.TERMINATE
                }
            } catch (error: ArithmeticException) {
                incomplete = "目录大小超出可统计范围"
                return FileVisitResult.TERMINATE
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT) unreadable++
            } catch (_: Exception) {
                unreadable++
            }
            return FileVisitResult.CONTINUE
        }
        Files.walkFileTree(directory.toPath(), emptySet<FileVisitOption>(), 128, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = count(dir)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isDirectory) {
                    incomplete = "目录深度超过单次统计上限"
                    return FileVisitResult.TERMINATE
                }
                return count(file)
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                if (!withinBudget()) return FileVisitResult.TERMINATE
                entries++
                if (exc !is NoSuchFileException) unreadable++
                return FileVisitResult.CONTINUE
            }
        })
        return Usage(bytes, unreadable, incomplete == null && unreadable == 0, incomplete)
    }
}
