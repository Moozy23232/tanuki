package com.tanuki

import com.tanuki.update.AppUpdateFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 只用临时合成路径，允许 Android 系统目录别名，但不允许更新目录自身重定向。 */
class AppUpdateFilesTest {
    @Test fun systemCacheAliasUsesCanonicalTrustedRoot() {
        val root = Files.createTempDirectory("tanuki-update-alias-")
        try {
            val cache = Files.createDirectory(root.resolve("cache"))
            val alias = Files.createSymbolicLink(root.resolve("system-alias"), cache)
            val directory = AppUpdateFiles.directory(alias.toFile())
            assertEquals(File(cache.toFile().canonicalFile, "application-updates"), directory)
            assertEquals(directory.absoluteFile, directory.canonicalFile)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun ownUpdateDirectoryCannotRedirectOutsideCache() {
        val root = Files.createTempDirectory("tanuki-update-link-")
        try {
            val cache = Files.createDirectory(root.resolve("cache"))
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.createSymbolicLink(cache.resolve("application-updates"), outside)
            assertThrows(IllegalArgumentException::class.java) { AppUpdateFiles.directory(cache.toFile()) }
        } finally { root.toFile().deleteRecursively() }
    }
}
