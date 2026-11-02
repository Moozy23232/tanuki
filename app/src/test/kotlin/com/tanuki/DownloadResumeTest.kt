package com.tanuki

import com.tanuki.download.Downloader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class DownloadResumeTest {
    private val url = "https://nodejs.org/dist/fixture.tar.gz"
    private val payload = "verified archive contents".toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private class Response(url: URL, private val payload: ByteArray, private val etag: String) : HttpURLConnection(url) {
        private val offset: Int get() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = if (offset > 0) HTTP_PARTIAL else HTTP_OK
        override fun getContentLengthLong() = (payload.size - offset).toLong()
        override fun getHeaderField(name: String): String? = when (name) {
            "ETag" -> etag
            "Content-Range" -> if (offset > 0) "bytes $offset-${payload.lastIndex}/${payload.size}" else null
            else -> null
        }
        override fun getInputStream(): java.io.InputStream = payload.copyOfRange(offset, payload.size).inputStream()
    }

    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("hp-download-resume").toFile()
        try { block(File(directory, "archive.tgz")) } finally { directory.deleteRecursively() }
    }

    @Test fun differentSourceOrLegacyPartialStartsFromZeroEvenWithMatchingEtag() = fixture { target ->
        for (source in listOf("https://repo.huaweicloud.com/nodejs/fixture.tar.gz", null)) {
            target.delete()
            File(target.path + ".part").writeText("BAD")
            File(target.path + ".etag").writeText("\"same-etag\"")
            val record = File(target.path + ".source")
            if (source == null) record.delete() else record.writeText(digest(source.toByteArray()))
            lateinit var response: Response
            val downloader = Downloader({ Response(it, payload, "\"same-etag\"").also { response = it } }) { _, _ -> }
            runBlocking { downloader.fetch(url, target, digest(payload), payload.size.toLong()) }
            assertNull("another URL's partial bytes must never be ranged", response.getRequestProperty("Range"))
            assertArrayEquals(payload, target.readBytes())
            assertFalse(record.exists())
        }
    }

    @Test fun matchingUrlAndValidatorResumeTheExistingBytes() = fixture { target ->
        File(target.path + ".part").writeBytes(payload.copyOf(7))
        File(target.path + ".etag").writeText("\"stable\"")
        File(target.path + ".source").writeText(digest(url.toByteArray()))
        lateinit var response: Response
        val downloader = Downloader({ Response(it, payload, "\"stable\"").also { response = it } }) { _, _ -> }
        runBlocking { downloader.fetch(url, target, digest(payload), payload.size.toLong()) }
        assertEquals("bytes=7-", response.getRequestProperty("Range"))
        assertEquals("\"stable\"", response.getRequestProperty("If-Range"))
        assertArrayEquals(payload, target.readBytes())
    }

    @Test fun changedValidatorDiscardsPartialBeforeRetry() = fixture { target ->
        File(target.path + ".part").writeText("BAD")
        File(target.path + ".etag").writeText("\"old\"")
        File(target.path + ".source").writeText(digest(url.toByteArray()))
        val responses = mutableListOf<Response>()
        val downloader = Downloader({ Response(it, payload, "\"new\"").also(responses::add) }) { _, _ -> }
        runBlocking { downloader.fetch(url, target, digest(payload), payload.size.toLong()) }
        assertEquals(2, responses.size)
        assertEquals("bytes=3-", responses[0].getRequestProperty("Range"))
        assertNull(responses[1].getRequestProperty("Range"))
        assertArrayEquals(payload, target.readBytes())
    }
}
