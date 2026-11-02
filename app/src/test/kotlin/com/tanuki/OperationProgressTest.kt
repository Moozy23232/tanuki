package com.tanuki

import com.tanuki.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OperationProgressTest {
    @Test fun elapsedTimeDoesNotFakeWorkerActivityAndTerminalStateResetsClock() {
        val clock = OperationClock()
        val busy = Snapshot(environment = EnvironmentState.INSTALLING, stage = "解析 DSH 依赖")
        assertEquals(OperationTiming(), clock.observe(Snapshot().operationSignal(), 10, 100))
        assertEquals(OperationTiming(1000, 1000), clock.observe(busy.operationSignal(), 10, 1000))
        assertEquals(OperationTiming(1000, 1000), clock.observe(busy.operationSignal(), 10, 90_000))
        val received = busy.copy(bytes = 1024)
        assertEquals(OperationTiming(1000, 90_100), clock.observe(received.operationSignal(), 10, 90_100))
        assertEquals(OperationTiming(1000, 90_200), clock.observe(received.operationSignal(), 90_150, 90_200))
        assertEquals(OperationTiming(), clock.observe(busy.copy(environment = EnvironmentState.FAILED).operationSignal(), 90_150, 91_000))
        assertEquals(OperationTiming(92_000, 92_000), clock.observe(busy.operationSignal(), 90_150, 92_000))
    }
    @Test fun everyRuntimeInstallPhaseIsActiveButIdleServerIsNot() {
        for (state in listOf(EnvironmentState.CHECKING, EnvironmentState.DOWNLOADING, EnvironmentState.INSTALLING, EnvironmentState.VERIFYING))
            assertTrue(Snapshot(environment = state).operationActive)
        for (state in listOf(UpdateState.CHECKING, UpdateState.STAGING, UpdateState.TESTING, UpdateState.BACKUP, UpdateState.SWITCHING, UpdateState.RESTORING))
            assertTrue(Snapshot(environment = EnvironmentState.READY, update = state).operationActive)
        assertTrue(Snapshot(operation = "component-node").operationActive)
        assertTrue(Snapshot(service = ServiceState.STARTING).operationActive)
        assertFalse(Snapshot(environment = EnvironmentState.READY, service = ServiceState.RUNNING).operationActive)
    }
    @Test fun percentageRequiresMeasuredTotalsAndAppliesOnlyToCurrentPhase() {
        assertNull(Snapshot(bytes = 50, total = -1).measuredFraction())
        assertNull(Snapshot(total = 0).measuredFraction())
        assertEquals(0.25f, Snapshot(bytes = 25, total = 100).measuredFraction()!!, 0f)
        assertEquals(1f, Snapshot(bytes = 101, total = 100).measuredFraction()!!, 0f)
        assertEquals(0.45f, Snapshot(phasePercent = 45, bytes = 101, total = 100).measuredFraction()!!, 0f)
        assertEquals(0.5f, Snapshot(download = RuntimeDownloadProgress("node", "test", 50, 100, 1)).measuredFraction()!!, 0f)
    }
    @Test fun dependencyProgressAcceptsOnlyBoundedMatchingStructuredCounts() {
        assertEquals(1 to 4, NpmPackageDownloads.installProgress("DSH_PROGRESS measure 1 4", "measure"))
        assertEquals(4 to 4, NpmPackageDownloads.installProgress("DSH_PROGRESS seed 4 4", "seed"))
        for (line in listOf("DSH_PROGRESS seed 1 4", "DSH_PROGRESS measure 5 4", "DSH_PROGRESS measure 0 0",
            "DSH_PROGRESS measure 1 4001", "DSH_PROGRESS measure -1 4", "untrusted DSH_PROGRESS measure 1 4"))
            assertNull(line, NpmPackageDownloads.installProgress(line, "measure"))
    }
    @Test fun fileCopyPublishesBytesAndFinishesOnlyAfterBlockReturns() = runBlocking {
        var now = 1000L
        val transfers = FileTransfers { now += 250; now }
        val input = ByteArray(128 * 1024 + 5) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        transfers.track("网页下载", input.size.toLong()) { id ->
            transfers.copy(ByteArrayInputStream(input), output, id, input.size.toLong())
            assertEquals(input.size.toLong(), transfers.state.value.single().received)
            assertEquals(input.size.toLong(), transfers.state.value.single().total)
        }
        assertArrayEquals(input, output.toByteArray())
        assertTrue(transfers.state.value.isEmpty())
    }
    @Test fun unknownTotalsAndIndependentConcurrentTransfersDoNotEraseEachOther() = runBlocking {
        val transfers = FileTransfers { 1000 }
        val other = transfers.begin("其他下载")
        transfers.track("未知长度下载") { id ->
            transfers.copy(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), id)
            assertEquals(-1L, transfers.state.value.last().total)
            assertEquals(3L, transfers.state.value.last().received)
        }
        assertEquals(other, transfers.state.value.single().id)
        transfers.finish(other)
        assertTrue(transfers.state.value.isEmpty())
    }
    @Test fun truncatedDownloadsAndCancellationNeverLeavePermanentLoading() = runBlocking {
        val transfers = FileTransfers { 1000 }
        try {
            transfers.track("截断文件", 5) { id ->
                transfers.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), id, 5)
            }
            fail("truncated response must fail")
        } catch (_: IllegalStateException) { }
        assertTrue(transfers.state.value.isEmpty())
        val task = launch(start = CoroutineStart.UNDISPATCHED) { transfers.track("取消测试") { awaitCancellation() } }
        assertEquals(1, transfers.state.value.size)
        task.cancelAndJoin()
        assertTrue(transfers.state.value.isEmpty())
    }
}
