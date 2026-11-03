package com.tanuki

import com.tanuki.runtime.BundledDshLock
import com.tanuki.runtime.NpmDependencyLocks
import com.tanuki.runtime.NpmLockCache
import com.tanuki.runtime.NpmLockCandidate
import com.tanuki.runtime.NpmLockIdentity
import com.tanuki.runtime.NpmLockOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

class NpmDependencyLocksTest {
    private val integrity = "sha512-" + Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
    private val identity get() = NpmLockIdentity("1.2.3", integrity, "24.22.2", "11.19.1")
    private val lock = "public official dependency lock".toByteArray()
    private fun fixture(block: (File) -> Unit) {
        val base = Files.createTempDirectory("tanuki-npm-lock").toFile()
        try { block(base) } finally { base.deleteRecursively() }
    }
    private fun entry(base: File, id: NpmLockIdentity = identity) = File(base, "downloads/npm-locks/${id.key}.lock")

    @Test fun verifiedLockSurvivesCacheAndResolverRecreation() = fixture { base ->
        NpmLockCache(base).store(identity, lock)
        assertArrayEquals(lock, NpmLockCache(base).load(identity))
    }

    @Test fun releaseIntegrityNodeAndNpmAreAllPartOfIdentity() = fixture { base ->
        val cache = NpmLockCache(base)
        cache.store(identity, lock)
        listOf(identity.copy(version = "1.2.4"),
            identity.copy(integrity = "sha512-" + Base64.getEncoder().encodeToString(ByteArray(64) { 8 })),
            identity.copy(nodeVersion = "26.13.0"), identity.copy(npmVersion = "11.20.0")).forEach {
            assertNull(cache.load(it))
            assertNotEquals(identity.key, it.key)
        }
        assertArrayEquals(lock, cache.load(identity))
    }

    @Test fun truncatedOrModifiedRecordCannotBeReused() = fixture { base ->
        val cache = NpmLockCache(base)
        cache.store(identity, lock)
        val bytes = entry(base).readBytes()
        entry(base).writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(cache.load(identity))
        cache.store(identity, lock)
        val modified = entry(base).readBytes()
        modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        entry(base).writeBytes(modified)
        assertNull(cache.load(identity))
    }

    @Test fun anotherIdentityCannotBeCopiedOverTheWantedRecord() = fixture { base ->
        val cache = NpmLockCache(base)
        val other = identity.copy(version = "1.2.4")
        cache.store(other, lock)
        entry(base, other).copyTo(entry(base), overwrite = true)
        assertNull(cache.load(identity))
    }

    @Test fun incompleteTemporaryWriteDoesNotHideLastCommittedLock() = fixture { base ->
        val cache = NpmLockCache(base)
        cache.store(identity, lock)
        File(entry(base).parentFile, "abandoned.pending").writeText("unfinished")
        assertArrayEquals(lock, NpmLockCache(base).load(identity))
    }

    @Test fun oversizedReplacementPreservesTheOldRecord() = fixture { base ->
        val cache = NpmLockCache(base)
        cache.store(identity, lock)
        try { cache.store(identity, ByteArray(NpmLockCache.MAX_LOCK_BYTES + 1)); fail("oversized lock accepted") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(lock, cache.load(identity))
    }

    @Test fun redirectedCacheAndCacheParentsAreRejectedWithoutTouchingOutsideFiles() = fixture { base ->
        val outside = Files.createTempDirectory("tanuki-npm-outside").toFile()
        try {
            val sentinel = File(outside, "sentinel").apply { writeText("unchanged") }
            Files.createSymbolicLink(File(base, "downloads").toPath(), outside.toPath())
            try { NpmLockCache(base).store(identity, lock); fail("redirected parent accepted") }
            catch (_: IllegalStateException) { }
            assertEquals("unchanged", sentinel.readText())
            assertEquals(listOf("sentinel"), outside.list()!!.toList())
            Files.delete(File(base, "downloads").toPath())
            NpmLockCache(base).store(identity, lock)
            entry(base).delete()
            Files.createSymbolicLink(entry(base).toPath(), sentinel.toPath())
            try { NpmLockCache(base).load(identity); fail("redirected cache accepted") }
            catch (_: IllegalStateException) { }
            assertEquals("unchanged", sentinel.readText())
        } finally { outside.deleteRecursively() }
    }

    @Test fun downloadFailureDoesNotForceAnotherOfficialResolution() = fixture { base -> runBlocking {
        var requests = 0
        var checks = 0
        suspend fun prepare(): NpmLockOrigin = NpmDependencyLocks(NpmLockCache(base)).prepare(identity,
            reuse = { null }, resolveOfficial = { requests++; lock }, verify = { checks++; assertArrayEquals(lock, it) })
        assertEquals(NpmLockOrigin.OFFICIAL, prepare())
        // 解析与校验完成后的网络故障，不应使下一次候选安装重新请求元数据。
        try { throw IOException("synthetic archive download failure") } catch (_: IOException) { }
        assertEquals(NpmLockOrigin.SAVED, prepare())
        assertEquals(1, requests)
        assertEquals(2, checks)
    } }

    @Test fun reusablePinnedLockAvoidsOfficialResolutionButStillRunsGraphValidation() = fixture { base -> runBlocking {
        var checked = false
        val origin = NpmDependencyLocks(NpmLockCache(base)).prepare(identity,
            reuse = { NpmLockCandidate(lock, NpmLockOrigin.BUNDLED) },
            resolveOfficial = { error("pinned lock must not resolve over the network") },
            verify = { checked = true; assertArrayEquals(lock, it) })
        assertEquals(NpmLockOrigin.BUNDLED, origin)
        assertTrue(checked)
        assertArrayEquals(lock, NpmLockCache(base).load(identity))
    } }

    @Test fun failedGraphValidationIsNeverPublished() = fixture { base -> runBlocking {
        val failure = IOException("invalid dependency graph")
        try {
            NpmDependencyLocks(NpmLockCache(base)).prepare(identity, reuse = { null },
                resolveOfficial = { lock }, verify = { throw failure })
            fail("invalid graph accepted")
        } catch (error: IOException) { assertSame(failure, error) }
        assertNull(NpmLockCache(base).load(identity))
    } }

    @Test fun cancellationDoesNotResolveAgainOrPublishAnUnverifiedLock() = fixture { base -> runBlocking {
        val cancelled = CancellationException("synthetic cancellation")
        try {
            NpmDependencyLocks(NpmLockCache(base)).prepare(identity,
                reuse = { NpmLockCandidate(lock, NpmLockOrigin.INSTALLED) },
                resolveOfficial = { error("cancel must not fall back to the network") }, verify = { throw cancelled })
            fail("cancellation swallowed")
        } catch (error: CancellationException) { assertSame(cancelled, error) }
        assertNull(NpmLockCache(base).load(identity))
    } }

    @Test fun corruptSavedLockFallsBackToOfficialInsteadOfBeingTrusted() = fixture { base -> runBlocking {
        NpmLockCache(base).store(identity, lock)
        entry(base).writeText("incomplete")
        var calls = 0
        val origin = NpmDependencyLocks(NpmLockCache(base)).prepare(identity, reuse = { null },
            resolveOfficial = { calls++; lock }, verify = { assertArrayEquals(lock, it) })
        assertEquals(NpmLockOrigin.OFFICIAL, origin)
        assertEquals(1, calls)
    } }

    @Test fun cacheWriteFailureDoesNotInvalidateAnOtherwiseVerifiedInstall() = fixture { base -> runBlocking {
        File(base, "downloads").writeText("synthetic unavailable cache directory")
        var warned = false
        val origin = NpmDependencyLocks(NpmLockCache(base)).prepare(identity, reuse = { null },
            resolveOfficial = { lock }, verify = { }, onCacheError = { warned = true })
        assertEquals(NpmLockOrigin.OFFICIAL, origin)
        assertTrue(warned)
    } }

    @Test fun bundledLockIsBoundToExactReleaseAndApkDigest() {
        val bytes = File("src/main/assets/dsh-locks/${BundledDshLock.VERSION}.json").readBytes()
        assertArrayEquals(bytes, BundledDshLock.load(BundledDshLock.VERSION, BundledDshLock.INTEGRITY) { bytes })
        assertNull(BundledDshLock.load("9.9.9", BundledDshLock.INTEGRITY) { error("wrong version read assets") })
        assertNull(BundledDshLock.load(BundledDshLock.VERSION, integrity) { error("wrong integrity read assets") })
        val altered = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try { BundledDshLock.load(BundledDshLock.VERSION, BundledDshLock.INTEGRITY) { altered }; fail("altered bundled lock accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun invalidIdentityCannotEscapeCacheOrDisableSha512() {
        listOf({ identity.copy(version = "../outside") }, { identity.copy(integrity = "sha1-unsafe") },
            { identity.copy(nodeVersion = "../node") }, { identity.copy(npmVersion = "") }).forEach { make ->
            try { make(); fail("invalid identity accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
