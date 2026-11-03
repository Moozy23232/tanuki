package com.tanuki.update

import java.io.File
import java.nio.file.Files

/** 接受 Android 自身的缓存根别名，只拒绝应用更新子目录重定向。 */
object AppUpdateFiles {
    fun directory(cache: File): File {
        val root = cache.canonicalFile
        val updates = File(root, "application-updates")
        require(!Files.isSymbolicLink(updates.toPath()) && updates.canonicalFile.parentFile == root) {
            "更新缓存目录不能是链接"
        }
        return updates
    }
}
