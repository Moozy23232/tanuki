package com.tanuki.runtime

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Base64

/** 缓存绑定实际 Node/npm 与解析策略；不把其他环境的安装计划当作当前结果。 */
internal data class NpmLockIdentity(val version: String, val integrity: String, val nodeVersion: String, val npmVersion: String) {
    init {
        require(VERSION.matches(version) && VERSION.matches(nodeVersion) && VERSION.matches(npmVersion)) { "依赖锁版本身份无效" }
        require(SRI.matches(integrity) && Base64.getDecoder().decode(integrity.removePrefix("sha512-")).size == 64 &&
            "sha512-" + Base64.getEncoder().encodeToString(Base64.getDecoder().decode(integrity.removePrefix("sha512-"))) == integrity) {
            "依赖锁必须绑定规范的 SHA-512 校验"
        }
    }
    val key: String = npmLockSha256(listOf("tanuki-npm-lock-v1", "https://registry.npmjs.org", "linux-arm64-glibc",
        version, integrity, nodeVersion, npmVersion).joinToString("\n").toByteArray(Charsets.UTF_8))

    companion object {
        private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
        private val SRI = Regex("sha512-[A-Za-z0-9+/]{86}==")
    }
}

internal fun npmLockSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** 只保存经官方解析及依赖图检查的公开锁；单文件提交避免混用两次写入的内容。 */
internal class NpmLockCache(private val base: File) {
    private val directory = File(base, "downloads/npm-locks")
    private fun file(identity: NpmLockIdentity) = File(directory, "${identity.key}.lock")

    private fun owned(file: File) {
        val root = base.absoluteFile.toPath().normalize()
        var cursor = file.absoluteFile.toPath().normalize()
        check(cursor.startsWith(root)) { "依赖锁缓存路径越界" }
        while (true) {
            check(!Files.isSymbolicLink(cursor)) { "依赖锁缓存路径被重定向，已保留原文件" }
            if (cursor == root) break
            cursor = cursor.parent ?: error("依赖锁缓存路径越界")
        }
    }

    fun load(identity: NpmLockIdentity): ByteArray? {
        val target = file(identity)
        owned(target)
        if (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) || target.length() !in 1..MAX_LOCK_BYTES + 1024L) return null
        return try {
            DataInputStream(FileInputStream(target)).use { input ->
                require(input.readUTF() == MAGIC && input.readUTF() == identity.key)
                val length = input.readInt()
                require(length in 1..MAX_LOCK_BYTES)
                val bytes = ByteArray(length).also(input::readFully)
                require(input.readUTF() == npmLockSha256(bytes) && input.read() == -1)
                bytes
            }
        } catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    fun store(identity: NpmLockIdentity, bytes: ByteArray) {
        require(bytes.size in 1..MAX_LOCK_BYTES) { "依赖锁缓存大小无效" }
        val target = file(identity)
        owned(target)
        Files.createDirectories(directory.toPath())
        owned(target)
        val temporary = Files.createTempFile(directory.toPath(), "lock-", ".pending")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                val record = DataOutputStream(output)
                record.writeUTF(MAGIC)
                record.writeUTF(identity.key)
                record.writeInt(bytes.size)
                record.write(bytes)
                record.writeUTF(npmLockSha256(bytes))
                record.flush()
                output.fd.sync()
            }
            owned(target)
            try { Files.move(temporary, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target.toPath(), REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        const val MAX_LOCK_BYTES = 16 * 1024 * 1024
        private const val MAGIC = "Tanuki official npm dependency lock v1"
    }
}

internal enum class NpmLockOrigin { SAVED, INSTALLED, BUNDLED, OFFICIAL }
internal data class NpmLockCandidate(val bytes: ByteArray, val origin: NpmLockOrigin)

/** 下载开始前提交已验证的锁，后续失败或重建候选目录都不会丢失解析成果。 */
internal class NpmDependencyLocks(private val cache: NpmLockCache) {
    suspend fun prepare(identity: NpmLockIdentity, reuse: suspend () -> NpmLockCandidate?,
                        resolveOfficial: suspend () -> ByteArray, verify: suspend (ByteArray) -> Unit,
                        onOrigin: (NpmLockOrigin) -> Unit = {}, onCacheError: (IOException) -> Unit = {}): NpmLockOrigin {
        val candidate = cache.load(identity)?.let { NpmLockCandidate(it, NpmLockOrigin.SAVED) } ?: reuse()
        val origin = candidate?.origin ?: NpmLockOrigin.OFFICIAL
        onOrigin(origin)
        val bytes = candidate?.bytes ?: resolveOfficial()
        require(bytes.size in 1..NpmLockCache.MAX_LOCK_BYTES) { "依赖锁大小无效" }
        // 命中缓存也重新检查根依赖、平台、引擎和完整依赖图，不能绕过安装校验。
        verify(bytes)
        if (origin != NpmLockOrigin.SAVED) {
            try { cache.store(identity, bytes) }
            catch (error: IOException) { onCacheError(error) }
        }
        return origin
    }
}

/** 预置锁来自官方 HTTPS registry，完整内容由 APK 中的摘要绑定，不向镜像索取校验值。 */
internal object BundledDshLock {
    const val VERSION = "0.2.1-rc.5"
    const val INTEGRITY = "sha512-WkyJZv4zl9sCAxSzRctGm58m0rilGqwR6pniBiLOz5CPDfHfDuAUicg0AAazXI7oj/Cph6/cjNTncA/rpLTXpw=="
    const val SHA256 = "f1167bea5a45cbd179edb630ef58f2b7aa8c9512b1aaab8268fbd180bc7c6bcb"
    const val ASSET = "dsh-locks/$VERSION.json"

    fun load(version: String, integrity: String, read: () -> ByteArray): ByteArray? {
        if (version != VERSION || integrity != INTEGRITY) return null
        val bytes = read()
        check(bytes.size in 1..NpmLockCache.MAX_LOCK_BYTES && npmLockSha256(bytes) == SHA256) { "APK 预置 DSH 依赖锁校验失败" }
        return bytes
    }
}
