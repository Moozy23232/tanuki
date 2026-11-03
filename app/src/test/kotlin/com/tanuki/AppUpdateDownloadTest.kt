package com.tanuki

import com.tanuki.download.Downloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdateDownloadTest {
    private val payload = "synthetic-apk-bytes".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val release = "https://github.com/leezzou/tanuki/releases/download/v0.1.1/fixture.apk"
    private class Response(url: URL, private val payload: ByteArray, private val location: String? = null) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = if (location == null) 200 else 302
        override fun getContentLengthLong() = payload.size.toLong()
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
        override fun getInputStream() = ByteArrayInputStream(payload)
    }
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("tanuki-app-update-test").toFile()
        try { block(File(directory, "update.apk")) } finally { directory.deleteRecursively() }
    }

    @Test fun updateDownloadHasSeparateHostsAndChecksLengthAndSha256() = fixture { file ->
        val events = mutableListOf<Pair<Long, Long>>()
        val downloader = Downloader.applicationUpdates({ Response(it, payload) }) { done, total -> events += done to total }
        assertEquals(file, downloader.fetch(release, file, sha, payload.size.toLong()))
        assertArrayEquals(payload, file.readBytes())
        assertEquals(payload.size.toLong() to payload.size.toLong(), events.last())
    }

    @Test fun defaultRuntimeDownloaderDoesNotGainGithubAccess() = fixture { file ->
        var opened = false
        try {
            Downloader({ opened = true; Response(it, payload) }) { _, _ -> }.fetch(release, file, sha, payload.size.toLong())
            fail("Runtime host boundary expanded")
        } catch (_: IllegalStateException) { }
        assertFalse(opened)
    }

    @Test fun updateRedirectCannotEscapeToArbitraryHostOrHttp() = fixture { file ->
        for (location in listOf("https://elsewhere.example/fixture.apk", "http://github.com/file", "https://registry.npmjs.org/file")) {
            try {
                Downloader.applicationUpdates({ Response(it, payload, location) }) { _, _ -> }
                    .fetch(release, file, sha, payload.size.toLong())
                fail("Unsafe redirect accepted")
            } catch (_: IllegalStateException) { }
            assertFalse(file.exists())
        }
    }

    @Test fun corruptUpdateIsNeverCommitted() = fixture { file ->
        try {
            Downloader.applicationUpdates({ Response(it, payload) }) { _, _ -> }
                .fetch(release, file, "0".repeat(64), payload.size.toLong())
            fail("Corrupt update accepted")
        } catch (_: IllegalStateException) { }
        assertFalse(file.exists())
    }
}
