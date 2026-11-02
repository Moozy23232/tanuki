package com.tanuki

import com.tanuki.download.DownloadLimitException
import com.tanuki.runtime.NpmDownloadProgress
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NpmDownloadProgressTest {
    @Test fun unknownLengthsPublishBytesWithoutInventingTotal() {
        val progress = NpmDownloadProgress(2, 1000)
        assertEquals(-1, progress.snapshot().total)
        assertEquals(NpmDownloadProgress.Snapshot(30, -1, 0, 2), progress.update(0, 30, -1))
        assertEquals(NpmDownloadProgress.Snapshot(35, -1, 0, 2), progress.update(1, 5, 50))
        assertEquals(NpmDownloadProgress.Snapshot(45, 90, 1, 2), progress.complete(0, 40))
        assertEquals(NpmDownloadProgress.Snapshot(90, 90, 2, 2), progress.complete(1, 50))
    }
    @Test fun allResponseHeadersGiveAnExactTotalButDoNotPretendIntegrityIsVerified() {
        val progress = NpmDownloadProgress(2, 1000)
        progress.update(0, 20, 100)
        assertEquals(NpmDownloadProgress.Snapshot(50, 300, 0, 2), progress.update(1, 30, 200))
        progress.update(0, 100, 100)
        assertEquals(0, progress.snapshot().verified)
        progress.complete(0, 100)
        assertEquals(1, progress.snapshot().verified)
    }
    @Test fun mirrorRetryClearsStaleLengthsAndBytes() {
        val progress = NpmDownloadProgress(2, 1000)
        progress.update(0, 50, 100)
        progress.update(1, 30, 200)
        assertEquals(NpmDownloadProgress.Snapshot(30, -1, 0, 2), progress.update(0, 0, -1))
        assertEquals(NpmDownloadProgress.Snapshot(40, 290, 0, 2), progress.update(0, 10, 90))
    }
    @Test fun aggregateBoundCoversUnknownAndKnownLengthsWithoutMutatingOnFailure() {
        val progress = NpmDownloadProgress(2, 100)
        progress.update(0, 0, 60)
        for (total in listOf(-1L, 50L, Long.MAX_VALUE)) {
            try { progress.update(1, 50, total); fail("aggregate size limit missing") }
            catch (_: DownloadLimitException) { }
            assertEquals(NpmDownloadProgress.Snapshot(0, -1, 0, 2), progress.snapshot())
        }
        progress.update(1, 40, -1)
        assertEquals(40L, progress.snapshot().received)
    }
    @Test fun verifiedCachesLearnSizesWithoutNetworkAndEmptyArchivesFail() {
        val progress = NpmDownloadProgress(2, 1000)
        progress.complete(0, 40)
        assertEquals(-1, progress.snapshot().total)
        try { progress.complete(1, 0); fail("empty archive accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(NpmDownloadProgress.Snapshot(100, 100, 2, 2), progress.complete(1, 60))
    }
    @Test fun concurrentPackageProgressDoesNotLoseBytesOrVerifiedCount() = runBlocking {
        val progress = NpmDownloadProgress(4, 1000)
        (0 until 4).map { index -> async(Dispatchers.Default) {
            for (bytes in 0L..100L) progress.update(index, bytes, -1)
            progress.complete(index, 100)
        } }.awaitAll()
        assertEquals(NpmDownloadProgress.Snapshot(400, 400, 4, 4), progress.snapshot())
    }
}
