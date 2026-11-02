package com.tanuki.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

private const val MAX_REDIRECTS = 5
private const val MAX_ATTEMPTS = 3
private const val BUFFER_SIZE = 65536
private const val DISK_RESERVE = 64L * 1024 * 1024
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val SHA512_INTEGRITY = Regex("sha512-[A-Za-z0-9+/]{86}==")
private val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$")
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val ALLOWED_HOSTS = setOf(
    "nodejs.org", "cdimage.ubuntu.com", "registry.npmjs.org", "registry.npmmirror.com",
    "npmmirror.com", "cdn.npmmirror.com", "repo.huaweicloud.com", "mirrors.huaweicloud.com",
    "mirrors.tuna.tsinghua.edu.cn", "mirrors4.tuna.tsinghua.edu.cn", "mirrors6.tuna.tsinghua.edu.cn",
    "mirrors.ustc.edu.cn", "mirrors.nju.edu.cn", "mirror.nju.edu.cn", "mirror.lzu.edu.cn",
    "mirrors.bfsu.edu.cn", "mirrors.aliyun.com", "mirrors.cloud.tencent.com", "mirrors.sjtug.sjtu.edu.cn"
)

class DownloadHttpException(val status: Int, host: String) : IllegalStateException("下载失败 HTTP $status: $host")
class DownloadLimitException : IllegalStateException("下载内容超过安全大小限制")

/**
 * Downloads one already-chosen URL over HTTPS. Mirror selection and fallback stay with the caller:
 * this class only fetches the URL it is given, follows at most [MAX_REDIRECTS] redirects inside
 * [ALLOWED_HOSTS], resumes only on a matching `Content-Range`, and commits nothing before the
 * length and digest checks pass.
 */
class Downloader internal constructor(
    private val connection: (URL) -> HttpURLConnection,
    private val progress: (Long, Long) -> Unit,
) {
    constructor(progress: (Long, Long) -> Unit) : this({ it.openConnection() as HttpURLConnection }, progress)

    suspend fun fetch(url: String, target: File, sha256: String, expectedBytes: Long = -1): File {
        require(SHA256_HEX.matches(sha256)) { "SHA-256 校验值格式无效" }
        return download(url, target, DigestCheck("SHA-256", "SHA-256", hexToBytes(sha256)), expectedBytes.declaredSize())
    }

    /** Strict npm SRI: exactly `sha512-<base64>` decoding to 64 bytes; weak or mixed algorithms are rejected. */
    suspend fun fetchIntegrity(url: String, target: File, integrity: String, expectedBytes: Long = -1,
                               maxBytes: Long = Long.MAX_VALUE): File =
        download(url, target, DigestCheck("SHA-512", "SHA-512", sha512Integrity(integrity)), expectedBytes.declaredSize(), maxBytes)

    private suspend fun download(url: String, target: File, check: DigestCheck, expected: Long?, maxBytes: Long = Long.MAX_VALUE): File {
        require(maxBytes > 0)
        if (expected != null && expected > maxBytes) throw DownloadLimitException()
        val parent = target.absoluteFile.parentFile ?: throw IllegalStateException("下载目标路径无效")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) throw IllegalStateException("无法创建下载目录")
        parse(url, "下载地址无效")
        val part = File(target.path + ".part")
        val validator = File(target.path + ".etag")
        val source = File(target.path + ".source")
        val sourceIdentity = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        denySymlinks(target, part, validator, source)
        if (target.exists() && !target.isFile) throw IllegalStateException("下载目标不是普通文件")
        if (part.exists() && !part.isFile) throw IllegalStateException("下载缓存不是普通文件")
        if (validator.exists() && !validator.isFile) throw IllegalStateException("下载校验缓存不是普通文件")
        if (source.exists() && !source.isFile) throw IllegalStateException("下载来源缓存不是普通文件")
        if (target.isFile && target.length() <= maxBytes && (expected == null || target.length() == expected) && check.verify(target)) return target
        var attempt = 0
        while (true) {
            attempt++
            currentCoroutineContext().ensureActive()
            denySymlinks(target, part, validator, source)
            // ETags belong to one URL. A matching validator on another mirror proves nothing.
            val sameSource = source.isFile && source.length() == 64L && source.readText() == sourceIdentity
            val resume = if (sameSource && part.length() <= maxBytes) resumePoint(part, validator) else ResumePoint(0L, null)
            if (resume.offset == 0L) { part.delete(); validator.delete() }
            source.writeText(sourceIdentity)
            val outcome = try {
                attemptOnce(url, part, validator, resume, expected, maxBytes)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (tooLarge: DownloadLimitException) {
                part.delete(); validator.delete(); throw tooLarge
            } catch (_: IOException) {
                // Keep the partial file plus ETag so the next attempt can resume.
                if (attempt >= MAX_ATTEMPTS) throw IllegalStateException("下载中断，请重试")
                continue
            }
            if (outcome is Outcome.Restart) {
                if (attempt >= MAX_ATTEMPTS) throw IllegalStateException("续传失败，请重试")
                continue
            }
            val written = (outcome as Outcome.Written).length
            denySymlinks(target, part, validator, source)
            if (part.length() != written) { part.delete(); validator.delete(); throw IllegalStateException("下载内容长度不一致") }
            if (expected != null && written != expected) { part.delete(); validator.delete(); throw IllegalStateException("下载内容长度与声明不一致") }
            if (!check.verify(part)) { part.delete(); validator.delete(); throw IllegalStateException("${check.label} 校验失败") }
            if (!part.renameTo(target)) throw IllegalStateException("无法提交下载")
            validator.delete(); source.delete()
            progress(written, written)
            return target
        }
    }

    private suspend fun attemptOnce(url: String, part: File, validator: File, resume: ResumePoint, expected: Long?, maxBytes: Long): Outcome {
        val conn = open(url, resume.offset, resume.validator)
        try {
            val code = conn.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE) { part.delete(); validator.delete(); return Outcome.Restart }
            val etag = strongEtag(conn.getHeaderField("ETag"))
            var total: Long
            val start: Long
            val resumed: Boolean
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    resumed = false; start = 0L
                    total = conn.contentLengthLong.takeIf { it >= 0 } ?: expected ?: -1L
                }
                HttpURLConnection.HTTP_PARTIAL -> {
                    val range = contentRange(conn.getHeaderField("Content-Range"))
                    if (resume.offset <= 0L || resume.validator == null || range == null ||
                        range.start != resume.offset || range.total == null || range.end + 1 != range.total) {
                        // A partial response we cannot attribute exactly is discarded, never trusted.
                        part.delete(); validator.delete(); return Outcome.Restart
                    }
                    // Some mirrors ignore If-Range, so compare the returned validator explicitly.
                    if (etag != null && etag != resume.validator) { part.delete(); validator.delete(); return Outcome.Restart }
                    resumed = true; start = range.start; total = range.total
                }
                else -> throw DownloadHttpException(code, conn.url.host)
            }
            if (total > maxBytes || start > maxBytes) throw DownloadLimitException()
            if (expected != null && total >= 0 && total != expected) {
                part.delete(); validator.delete(); throw IllegalStateException("下载大小与声明不一致")
            }
            if (total < 0 && expected != null) total = expected
            if (resumed && total >= 0 && start >= total) { part.delete(); validator.delete(); return Outcome.Restart }
            if (etag != null) validator.writeText(etag) else validator.delete()
            progress(start, total)
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) {
                    if (out.length() != start) throw IllegalStateException("续传文件长度不一致")
                } else out.setLength(0)
                out.seek(start)
                var done = start
                conn.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (read.toLong() > maxBytes - done) throw DownloadLimitException()
                        if (total >= 0 && done + read > total) throw IllegalStateException("下载内容超出声明长度")
                        if (part.parentFile!!.usableSpace <= DISK_RESERVE) throw IllegalStateException("空间不足，下载已停止；项目不会被删除")
                        out.write(buffer, 0, read)
                        done += read
                        progress(done, total)
                    }
                }
                out.fd.sync()
                if (total >= 0 && done != total) throw IOException("下载内容不完整")
                return Outcome.Written(done)
            }
        } finally { conn.disconnect() }
    }

    private fun open(raw: String, offset: Long, ifRange: String?): HttpURLConnection {
        var url = parse(raw, "下载地址无效")
        var hops = 0
        while (true) {
            val conn = connection(url)
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) {
                conn.setRequestProperty("Range", "bytes=$offset-")
                ifRange?.let { conn.setRequestProperty("If-Range", it) }
            }
            val code = try { conn.responseCode } catch (failure: IOException) { conn.disconnect(); throw failure }
            if (code !in REDIRECT_CODES) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) throw IllegalStateException("下载重定向缺少目标")
            if (hops >= MAX_REDIRECTS) throw IllegalStateException("下载重定向次数过多")
            hops++
            val next = try { URL(url, location.trim()) } catch (_: MalformedURLException) { throw IllegalStateException("下载重定向目标无效") }
            url = parse(next.toString(), "下载重定向目标无效")
        }
    }

    private fun parse(raw: String, invalid: String): URL {
        val url = try { URL(raw) } catch (_: MalformedURLException) { throw IllegalStateException(invalid) }
        if (url.protocol != "https") throw IllegalStateException("下载源必须使用 HTTPS")
        if (url.userInfo != null) throw IllegalStateException("下载源不得包含凭据")
        val host = url.host?.lowercase()
        if (host.isNullOrEmpty() || host !in ALLOWED_HOSTS) throw IllegalStateException("下载源不在允许列表：${host ?: "未知"}")
        if (url.port != -1 && url.port != 443) throw IllegalStateException("下载源端口不受支持：$host")
        return url
    }

    private fun resumePoint(part: File, validator: File): ResumePoint {
        if (!part.isFile || !validator.isFile) return ResumePoint(0L, null)
        val etag = readStrongEtag(validator) ?: return ResumePoint(0L, null)
        val length = part.length()
        return if (length > 0L) ResumePoint(length, etag) else ResumePoint(0L, null)
    }

    private class DigestCheck(val label: String, private val algorithm: String, private val expected: ByteArray) {
        fun verify(file: File): Boolean = MessageDigest.isEqual(digest(file, algorithm), expected)
    }

    private sealed class Outcome {
        data class Written(val length: Long) : Outcome()
        object Restart : Outcome()
    }

    private data class ResumePoint(val offset: Long, val validator: String?)

    companion object {
        fun hash(file: File): String = digest(file, "SHA-256").joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

private fun Long.declaredSize(): Long? = takeIf { it > 0 }

private fun digest(file: File, algorithm: String): ByteArray {
    val digest = MessageDigest.getInstance(algorithm)
    file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest()
}

private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
    ((Character.digit(hex[index * 2], 16) shl 4) or Character.digit(hex[index * 2 + 1], 16)).toByte()
}

private fun sha512Integrity(integrity: String): ByteArray {
    val value = integrity.trim()
    require(SHA512_INTEGRITY.matches(value)) { "仅接受 sha512-<base64> 完整性校验值" }
    val decoded = try { Base64.getDecoder().decode(value.removePrefix("sha512-")) } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("sha512 完整性校验值无效")
    }
    require(decoded.size == 64) { "sha512 完整性校验值长度无效" }
    return decoded
}

private data class ContentRange(val start: Long, val end: Long, val total: Long?)

private fun contentRange(raw: String?): ContentRange? {
    val match = CONTENT_RANGE.find(raw?.trim() ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val totalText = match.groupValues[3]
    val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
    if (start < 0L || end < start) return null
    if (total != null && total <= end) return null
    return ContentRange(start, end, total)
}

private fun strongEtag(raw: String?): String? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty() || value.length > 512) return null
    if (value.startsWith("W/", ignoreCase = true)) return null
    if (value.any { it < ' ' || it == '\u007f' }) return null
    return value
}

private fun readStrongEtag(file: File): String? = try {
    if (file.length() > 1024L) null else strongEtag(file.readText())
} catch (_: IOException) { null }

private fun denySymlinks(vararg files: File) {
    for (file in files) if (Files.isSymbolicLink(file.toPath())) throw IllegalStateException("下载路径被重定向，已停止：${file.name}")
}
