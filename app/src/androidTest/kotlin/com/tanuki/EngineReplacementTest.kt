package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Real directory renames and recovery journals in disposable app-owned storage. */
class EngineReplacementTest {
    private class Fixture(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }
    private suspend fun fixture(block: suspend (RuntimeManager) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val directory = File(context.cacheDir, "engine-replace-${System.nanoTime()}")
        val manager = RuntimeManager(Fixture(context, directory))
        try { manager.mutation.withLock { block(manager) } }
        finally {
            manager.scope.cancel(); manager.scope.coroutineContext[Job]?.join()
            RuntimeManager.deleteTree(directory)
        }
    }
    private fun marker(file: File, value: String) { file.parentFile!!.mkdirs(); file.writeText(value) }

    @Test fun failedAndCancelledReplacementRestoreTheOriginalProgram(): Unit = runBlocking {
        fixture { manager ->
            val version = RuntimeManager.DSH
            val engine = File(manager.paths.engines, version)
            val project = File(manager.paths.workspaces, "keep.txt")
            val data = File(manager.paths.dshData, "keep.txt")
            marker(project, "project"); marker(data, "session")
            for (failure in listOf(IOException("injected rename failure"), CancellationException("injected cancellation"))) {
                marker(File(engine, "program"), "original")
                try {
                    manager.components.replaceEngine(version) {
                        assertFalse("original is retained outside the destination", engine.exists())
                        marker(File(engine, "program"), "incomplete candidate")
                        throw failure
                    }
                    fail("replacement should fail")
                } catch (caught: Exception) { assertSame(failure, caught) }
                assertEquals("original", File(engine, "program").readText())
                assertEquals("project", project.readText()); assertEquals("session", data.readText())
                assertFalse(File(manager.paths.runtime, "dsh-$version.previous").exists())
                assertFalse(File(manager.paths.state, "component-transaction").exists())
            }
        }
    }

    @Test fun completedReplacementKeepsCandidateAndRemovesOnlyPreviousProgram(): Unit = runBlocking {
        fixture { manager ->
            val version = RuntimeManager.DSH
            val engine = File(manager.paths.engines, version)
            val spare = File(manager.paths.engines, "0.0.1/spare")
            marker(File(engine, "program"), "original"); marker(spare, "spare")
            manager.components.replaceEngine(version) { marker(File(engine, "program"), "candidate") }
            assertEquals("candidate", File(engine, "program").readText())
            assertEquals("spare", spare.readText())
            assertFalse(File(manager.paths.runtime, "dsh-$version.previous").exists())
            assertFalse(File(manager.paths.state, "component-transaction").exists())
        }
    }

    @Test fun interruptedSwapRollsBackAndCommittedCleanupResumes(): Unit = runBlocking {
        fixture { manager ->
            val version = RuntimeManager.DSH
            val engine = File(manager.paths.engines, version)
            val previous = File(manager.paths.runtime, "dsh-$version.previous")
            val project = File(manager.paths.workspaces, "keep.txt")
            marker(project, "project")
            for (phase in listOf("switching", "committed")) {
                marker(File(engine, "program"), "candidate")
                marker(File(previous, "program"), "original")
                manager.paths.atomic("component-transaction", JSONObject().put("component", "dsh")
                    .put("version", version).put("phase", phase).put("hadPrevious", true).toString())
                manager.components.recover()
                assertEquals(if (phase == "switching") "original" else "candidate", File(engine, "program").readText())
                assertFalse(previous.exists())
                assertEquals("project", project.readText())
                assertFalse(File(manager.paths.state, "component-transaction").exists())
            }
        }
    }
}
