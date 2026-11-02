package com.tanuki

import com.tanuki.download.Downloader
import com.tanuki.download.DownloadLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

class NpmUnknownLengthDownloadTest {
    private val url = "https://registry.npmmirror.com/fixture/-/fixture-1.0.0.tgz"
    private val payload = ByteArray(70_000) { (it % 251).toByte() }
    private fun sri(bytes: ByteArray) = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))
    private class Response(url: URL, private val bytes: ByteArray, private val declared: Long) : HttpURLConnection(url) {
        var bodyOpened = false
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            check(requestMethod == "GET" && getRequestProperty("Range") == null) { "HEAD/size probes are unavailable on this fixture" }
            return HTTP_OK
        }
        override fun getContentLengthLong() = declared
        override fun getHeaderField(name: String): String? = null
        override fun getInputStream(): java.io.InputStream { bodyOpened = true; return bytes.inputStream() }
    }
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("hp-npm-unknown-length").toFile()
        try { block(File(directory, "archive.tgz")) } finally { directory.deleteRecursively() }
    }
    @Test fun chunkedMirrorBodySucceedsWithoutHeadRangeOrContentLength() = fixture { target ->
        val observed = mutableListOf<Pair<Long, Long>>()
        var requests = 0
        val downloader = Downloader({ Response(it, payload, -1).also { requests++ } }) { bytes, total -> observed.add(bytes to total) }
        runBlocking { downloader.fetchIntegrity(url, target, sri(payload), maxBytes = 100_000) }
        assertEquals(1, requests)
        assertArrayEquals(payload, target.readBytes())
        assertTrue(observed.any { it.first > 0 && it.second == -1L })
        assertEquals(payload.size.toLong() to payload.size.toLong(), observed.last())
    }
    @Test fun verifiedCacheWithoutSizeDoesNotUseNetwork() = fixture { target ->
        target.writeBytes(payload)
        val downloader = Downloader({ error("verified cache must not contact server") }) { _, _ -> error("unexpected network progress") }
        runBlocking { downloader.fetchIntegrity(url, target, sri(payload), maxBytes = 100_000) }
        assertArrayEquals(payload, target.readBytes())
    }
    @Test fun incorrectSha512StillFailsWithUnknownLengthAndPreservesOldTarget() = fixture { target ->
        target.writeText("old target")
        val downloader = Downloader({ Response(it, payload, -1) }) { _, _ -> }
        try { runBlocking { downloader.fetchIntegrity(url, target, sri("different".toByteArray()), maxBytes = 100_000) }; fail("invalid SRI accepted") }
        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("SHA-512")) }
        assertEquals("old target", target.readText())
        assertFalse(File(target.path + ".part").exists())
    }
    @Test fun oversizedHeaderFailsBeforeOpeningBody() = fixture { target ->
        lateinit var response: Response
        val downloader = Downloader({ Response(it, payload, Long.MAX_VALUE).also { response = it } }) { _, _ -> }
        try { runBlocking { downloader.fetchIntegrity(url, target, sri(payload), maxBytes = 100_000) }; fail("size limit missing") }
        catch (_: DownloadLimitException) { }
        assertFalse(response.bodyOpened)
        assertFalse(target.exists())
    }
    @Test fun oversizedChunkedStreamStopsWithoutCommittingOrKeepingItsPartial() = fixture { target ->
        val observed = mutableListOf<Long>()
        val downloader = Downloader({ Response(it, payload, -1) }) { bytes, _ -> observed.add(bytes) }
        try { runBlocking { downloader.fetchIntegrity(url, target, sri(payload), maxBytes = 68_000) }; fail("stream size limit missing") }
        catch (_: DownloadLimitException) { }
        assertTrue(observed.all { it <= 68_000 })
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }
    @Test fun knownTruncatedResponseStillFailsInsteadOfSkippingLengthValidation() = fixture { target ->
        val downloader = Downloader({ Response(it, payload, payload.size.toLong() + 1) }) { _, _ -> }
        try { runBlocking { downloader.fetchIntegrity(url, target, sri(payload), maxBytes = 100_000) }; fail("truncation accepted") }
        catch (_: IllegalStateException) { }
        assertFalse(target.exists())
    }
    @Test fun cancellationIsNotConvertedIntoAnInstallFallback() = fixture { target ->
        val downloader = Downloader({ Response(it, payload, -1) }) { bytes, _ -> if (bytes > 0) throw CancellationException("test cancellation") }
        try { runBlocking { downloader.fetchIntegrity(url, target, sri(payload), maxBytes = 100_000) }; fail("cancel swallowed") }
        catch (_: CancellationException) { }
        assertFalse(target.exists())
    }
}
