package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import com.tanuki.storage.DirectoryUsage
import com.tanuki.storage.WorkspaceCapacity
import com.tanuki.storage.WorkspaceStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real filesystem/native-process tests in disposable fixtures. No user DSH, keys or model requests. */
class WorkspaceStorageTest {
    private class FixtureContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }
    private class Fixture(val context: Context, val directory: File) {
        val wrapped = FixtureContext(context, directory)
        val paths = Paths(wrapped)
        val storage = WorkspaceStorage(paths)
        var manager: RuntimeManager? = null
        fun runtime(): RuntimeManager = RuntimeManager(wrapped).also { manager = it }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "workspace-fixture-").toFile()
        val test = Fixture(context, directory)
        try { test.block() }
        finally { test.manager?.scope?.cancel(); RuntimeManager.deleteTree(directory) }
    }
    private fun allocate(file: File, bytes: Long) {
        RandomAccessFile(file, "rw").use { Os.posix_fallocate(it.fd, 0, bytes) }
    }
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        var observed: Throwable? = null
        try { block() } catch (error: Throwable) { observed = error }
        return observed ?: throw AssertionError("operation unexpectedly succeeded")
    }
    private fun stream(bytes: Long) = object : InputStream() {
        var left = bytes
        override fun read(): Int = if (left > 0) { left--; 0x5a } else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (left <= 0) return -1
            val count = minOf(length.toLong(), left).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0x5a.toByte())
            left -= count
            return count
        }
    }

    @Test fun capacityPersistsAndRejectsShrinkWithoutChangingData(): Unit = runBlocking {
        fixture {
            val project = File(paths.workspaces, "existing.bin")
            allocate(project, 80L * 1024 * 1024)
            val home = File(paths.home, "fixture-marker").apply { writeText("keep-home") }
            val metadata = File(paths.state, "installed.json").apply { writeText("{\"fixture\":\"keep-version\"}") }
            storage.configure(96L * 1024 * 1024)
            assertEquals(96L * 1024 * 1024, WorkspaceStorage(paths).state.value.limitBytes)
            val rejected = failure { storage.configure(WorkspaceCapacity.MIN_LIMIT) }
            assertTrue(rejected.message.orEmpty().contains("不能小于"))
            assertEquals(96L * 1024 * 1024, WorkspaceStorage(paths).state.value.limitBytes)
            assertEquals(80L * 1024 * 1024, project.length())
            assertEquals("keep-home", home.readText())
            assertEquals("{\"fixture\":\"keep-version\"}", metadata.readText())
        }
    }

    @Test fun streamingImportRollsBackOverflowAndCountsStageOnlyOnce(): Unit = runBlocking {
        fixture {
            val existing = File(paths.workspaces, "existing.bin")
            allocate(existing, 40L * 1024 * 1024)
            storage.configure(WorkspaceCapacity.MIN_LIMIT)
            val before = storage.refresh().usedBytes!!
            val rejected = failure { storage.importFiles(listOf("unknown-size.bin")) { stream(40L * 1024 * 1024) } }
            assertTrue(rejected.message.orEmpty().contains("预算"))
            assertEquals(listOf("existing.bin"), paths.workspaces.list()!!.toList())
            assertEquals(40L * 1024 * 1024, existing.length())
            assertTrue(storage.refresh().usedBytes!! <= before + 65536)
            // 40 + 16 MiB fits; incorrectly adding the stage twice would reject it.
            val committed = storage.importFiles(listOf("accepted.bin")) { stream(16L * 1024 * 1024) }
            assertTrue(committed.isDirectory)
            assertEquals(16L * 1024 * 1024, File(committed, "accepted.bin").length())
            assertTrue(storage.refresh().usedBytes!! <= WorkspaceCapacity.MIN_LIMIT)
            assertFalse(paths.workspaces.list()!!.any { it.startsWith(".import-") })
        }
    }

    @Test fun ioFailureAndCancellationRemoveOnlyTheirImportStage(): Unit = runBlocking {
        fixture {
            val existing = File(paths.workspaces, "keep.txt").apply { writeText("existing-project") }
            for (cancelled in listOf(false, true)) {
                val observed = failure {
                    storage.importFiles(listOf("partial.bin")) {
                        object : InputStream() {
                            var first = true
                            override fun read(): Int = error("bulk reads expected")
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if (first) { first = false; buffer[offset] = 7; return 1 }
                                if (cancelled) throw CancellationException("fixture cancellation")
                                throw IOException("fixture read failure")
                            }
                        }
                    }
                }
                assertTrue(if (cancelled) observed is CancellationException else observed is IOException)
                assertEquals("existing-project", existing.readText())
                assertEquals(listOf("keep.txt"), paths.workspaces.list()!!.toList())
            }
        }
    }

    @Test fun allocatedUsageIgnoresLinkTargetsAndBoundsIncompleteScans(): Unit = runBlocking {
        fixture {
            val allocated = File(paths.workspaces, "allocated.bin")
            allocate(allocated, 10L * 1024 * 1024)
            val original = DirectoryUsage.measure(paths.workspaces).allocatedBytes
            // Android app SELinux denies link(2); ordinary symlinks must still be excluded.
            // PRoot's emulated hardlinks and deduplication are covered by FilesystemSemanticsTest.
            Files.createSymbolicLink(File(paths.workspaces, "inside-link").toPath(), allocated.toPath())
            val outside = File(directory, "outside.bin")
            allocate(outside, 40L * 1024 * 1024)
            Files.createSymbolicLink(File(paths.workspaces, "outside-link").toPath(), outside.toPath())
            RandomAccessFile(File(paths.workspaces, "sparse.bin"), "rw").use { it.setLength(WorkspaceCapacity.GIB) }
            val measured = DirectoryUsage.measure(paths.workspaces)
            assertTrue(measured.complete)
            assertTrue(measured.allocatedBytes >= original)
            assertTrue("symbolic targets and sparse length must not inflate allocation", measured.allocatedBytes - original < 1024 * 1024)
            val bounded = DirectoryUsage.measure(paths.workspaces, maxEntries = 1)
            assertFalse(bounded.complete)
            assertNotNull(bounded.incompleteReason)
        }
    }

    @Test fun corruptSettingsAndRedirectedRootsCannotPassAdmission(): Unit = runBlocking {
        fixture {
            paths.atomic("workspace-capacity.json", "{invalid")
            val corrupt = WorkspaceStorage(paths)
            assertNotNull(corrupt.state.value.configurationError)
            failure { corrupt.requireCanStart() }.also { assertTrue(it.message.orEmpty().contains("设置无法读取")) }
            corrupt.configure(null)
            assertNull(WorkspaceStorage(paths).state.value.configurationError)
            val outside = File(directory, "outside").apply { mkdirs() }
            val keep = File(outside, "keep.txt").apply { writeText("outside-kept") }
            assertTrue(paths.workspaces.delete())
            Files.createSymbolicLink(paths.workspaces.toPath(), outside.toPath())
            failure { corrupt.requireCanStart() }.also { assertTrue(it.message.orEmpty().contains("重定向")) }
            assertEquals("outside-kept", keep.readText())
        }
    }

    @Test fun overBudgetStartupNeverLaunchesTheRuntime(): Unit = runBlocking {
        fixture {
            val manager = runtime()
            manager.workspaceStorage.configure(WorkspaceCapacity.MIN_LIMIT)
            allocate(File(paths.workspaces, "grew.bin"), WorkspaceCapacity.MIN_LIMIT + 1024 * 1024)
            manager.state.update { it.copy(environment = EnvironmentState.READY) }
            manager.start()
            withTimeout(10000) { while (manager.state.value.service != ServiceState.FAILED || manager.hasActiveOperation) delay(25) }
            assertTrue(manager.state.value.error.contains("容量预算"))
            assertFalse(manager.hasLiveChild)
            assertFalse(manager.log.recent.value.any { it.contains("launch [") })
        }
    }

    private data class Native(val child: Child, val collected: Deferred<CommandResult>)
    private suspend fun Fixture.native(manager: RuntimeManager): Native {
        val ready = CountDownLatch(1)
        val builder = ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libsupervisor.so", "/system/bin/sh", "-c", "while :; do /system/bin/sleep 1; done")
        builder.environment().apply { clear(); put("PATH", "/system/bin") }
        val child = Child(builder.start(), manager.log, "workspace-native-fixture")
        val collected = manager.scope.async(Dispatchers.IO) {
            manager.launcher.collect(child) { if (it.startsWith("TANUKI_IDENTITY ")) ready.countDown() }
        }
        check(withContext(Dispatchers.IO) { ready.await(5, TimeUnit.SECONDS) }) { "native ownership handshake missing" }
        return Native(child, collected)
    }
    private fun setChild(manager: RuntimeManager, child: Child) {
        RuntimeManager::class.java.getDeclaredField("child").apply { isAccessible = true }.set(manager, child)
    }
    private suspend fun Native.close() = withContext(NonCancellable) {
        child.stop()
        withTimeoutOrNull(5000) { collected.await() }
        collected.cancelAndJoin()
    }

    @Test fun quotaWatcherStopsItsOwnNativeRuntimeWithoutJoiningItself(): Unit = runBlocking {
        fixture {
            val manager = runtime()
            manager.workspaceStorage.configure(WorkspaceCapacity.MIN_LIMIT)
            val native = native(manager)
            try {
                setChild(manager, native.child)
                manager.state.update { it.copy(service = ServiceState.RUNNING, environment = EnvironmentState.READY) }
                RuntimeManager::class.java.getDeclaredMethod("monitorWorkspace", Child::class.java).apply { isAccessible = true }.invoke(manager, native.child)
                allocate(File(paths.workspaces, "grew.bin"), WorkspaceCapacity.MIN_LIMIT + 1024 * 1024)
                withTimeout(30000) { while (manager.state.value.service != ServiceState.INTERRUPTED || native.child.process.isAlive) delay(50) }
                assertFalse(manager.hasLiveChild)
                assertTrue(manager.state.value.error.contains("容量预算"))
                assertTrue(File(paths.workspaces, "grew.bin").exists())
                withTimeout(5000) { native.collected.await() }
            } finally { native.close() }
        }
    }

    @Test fun delayedQuotaMeasurementNeverStopsAReplacementRuntime(): Unit = runBlocking {
        fixture {
            val manager = runtime()
            manager.workspaceStorage.configure(WorkspaceCapacity.MIN_LIMIT)
            val old = native(manager)
            var replacement: Native? = null
            val measurement = WorkspaceStorage::class.java.getDeclaredField("measurement").apply { isAccessible = true }.get(manager.workspaceStorage) as Mutex
            try {
                setChild(manager, old.child)
                manager.state.update { it.copy(service = ServiceState.RUNNING) }
                allocate(File(paths.workspaces, "grew.bin"), WorkspaceCapacity.MIN_LIMIT + 1024 * 1024)
                measurement.lock()
                try {
                    // Undispatched execution reaches the held measurement lock before replacement.
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { manager.enforceWorkspaceBudget(old.child) }
                    replacement = native(manager)
                    setChild(manager, replacement!!.child)
                    measurement.unlock()
                    withTimeout(5000) { pending.await() }
                    assertTrue("replacement must remain alive", replacement!!.child.process.isAlive)
                    assertTrue("old watcher must not stop either process", old.child.process.isAlive)
                    assertEquals(ServiceState.RUNNING, manager.state.value.service)
                } finally { if (measurement.isLocked) measurement.unlock() }
            } finally { replacement?.close(); old.close() }
        }
    }
}
