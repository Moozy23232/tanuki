package com.tanuki.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectArchive {
    /** The caller holds the runtime mutation lock and has stopped all guest writers. */
    suspend fun write(workspaces: File, allowedRoot: File, output: OutputStream) {
        val root = workspaces.toPath()
        val excluded = setOf(".env", ".npmrc", ".netrc", "credentials", "id_rsa", "id_ed25519")
        val coroutine = currentCoroutineContext()
        ZipOutputStream(output.buffered()).use { zip ->
            Files.walk(root).use { entries ->
                entries.forEach { path ->
                    coroutine.ensureActive()
                    val relative = root.relativize(path).toString()
                    if (relative.isNotEmpty() && relative.split('/').none {
                            it in excluded || it.startsWith(".env.") || it.startsWith(".import-")
                        }) {
                        val payload = L2sStore.payload(path, allowedRoot.toPath())
                        val file = payload ?: path.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        if (file != null) {
                            zip.putNextEntry(ZipEntry(relative))
                            Files.copy(file, zip)
                            zip.closeEntry()
                        } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            zip.putNextEntry(ZipEntry("$relative/"))
                            zip.closeEntry()
                        } else {
                            throw IOException("项目包含无法导出的符号链接或特殊文件：$relative；未生成完整备份")
                        }
                    }
                }
            }
        }
    }
}
