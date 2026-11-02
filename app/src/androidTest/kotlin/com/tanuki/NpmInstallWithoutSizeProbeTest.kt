package com.tanuki

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.download.Downloader
import com.tanuki.runtime.NpmPackageDownloads
import com.tanuki.runtime.RuntimeDownloadProgress
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real staged official npm install; existing runtime reused, no live engine/user data replacement. */
class NpmInstallWithoutSizeProbeTest {
    @Test(timeout = 600_000L)
    fun officialCandidateInstallsWithoutSizePreflightAndPreservesLiveEngine(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val runtime = (context.applicationContext as TanukiApp).runtime
        val version = runtime.paths.metadata().optString("dsh")
        assumeTrue("Needs an existing official runtime", NpmPackageDownloads.VERSION.matches(version))
        val current = File(runtime.paths.engines, version)
        val lock = File(current, "package-lock.json")
        assumeTrue("Needs the installed engine's public lock", lock.isFile && File(current, ".installed").isFile)
        check(!runtime.hasLiveChild && !runtime.hasActiveOperation) { "Do not interrupt an active user task" }
        val beforeHash = Downloader.hash(lock)
        val beforeMarker = File(current, ".installed").readText()
        val integrity = JSONObject(lock.readText()).getJSONObject("packages")
            .getJSONObject("node_modules/@deepseek-ai/dsh").getString("integrity")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(context.cacheDir, "npm-no-size-probe-${System.nanoTime()}").apply { check(mkdirs()) }
        val container = File(directory, "engines").apply { check(mkdirs()) }
        val isolated = File(directory, "isolated").apply { check(mkdirs()) }
        val candidate = File(container, version).apply { check(mkdirs()) }
        // Public manifests only; never copy accounts, project contents or model configuration.
        lock.copyTo(File(candidate, "package-lock.json"))
        File(current, "package.json").copyTo(File(candidate, "package.json"))
        val before = runtime.state.value
        val phases = CopyOnWriteArrayList<String>()
        val progress = CopyOnWriteArrayList<RuntimeDownloadProgress>()
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            runtime.state.collect { snapshot ->
                if (phases.lastOrNull() != snapshot.stage) phases.add(snapshot.stage)
                snapshot.download?.takeIf { it.component == "dsh" }?.let { progress.add(it) }
            }
        }
        try {
            runtime.mutation.withLock {
                val staged = NpmPackageDownloads(runtime).install(version, integrity, container, isolated)
                assertEquals(candidate, staged)
                assertTrue(File(staged, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
                assertEquals(version, JSONObject(File(staged, "node_modules/@deepseek-ai/dsh/package.json").readText()).getString("version"))
                assertFalse(File(staged, ".dsh-probe.cjs").exists())
                assertFalse(File(staged, ".dsh-sized.json").exists())
                assertTrue(phases.none { it.contains("测量依赖") })
                val archives = JSONObject(File(staged, ".dsh-downloaded.json").readText()).getJSONArray("archives")
                assertTrue(archives.length() > 0)
                for (index in 0 until archives.length()) {
                    val item = archives.getJSONObject(index)
                    val retained = File(runtime.paths.npmArchives, item.getString("archive").substringAfterLast('/'))
                    assertEquals(item.getLong("bytes"), retained.length())
                    Downloader { _, _ -> error("verified archive unexpectedly contacted network") }
                        .fetchIntegrity(item.getString("url"), retained, item.getString("integrity"), maxBytes = NpmPackageDownloads.MAX_BYTES)
                }
                assertTrue("must show unknown totals without blocking", progress.any { it.total < 0 })
                assertTrue("exact completed bytes required", progress.any { it.total > 0 && it.received == it.total })
                assertTrue(progress.all { it.received >= 0 && (it.total < 0 || it.received <= it.total) })
                assertEquals(beforeHash, Downloader.hash(lock))
                assertEquals(beforeMarker, File(current, ".installed").readText())
                Log.i("TANUKI_PROBE", "NPM_NO_SIZE_PROBE official_install=true archives=${archives.length()} sha512=true unknown_progress=true live_engine_unchanged=true model=false")
            }
        } finally {
            watcher.cancelAndJoin()
            runtime.state.value = before
            withContext(NonCancellable + Dispatchers.IO) { RuntimeManager.deleteTree(directory) }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
