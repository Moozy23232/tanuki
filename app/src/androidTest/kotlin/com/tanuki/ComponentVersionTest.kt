package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

/**
 * Component version selection, persistence and interruption-recovery tests.
 *
 * Tests 1 and 2 are FAST SYNTHETIC FILESYSTEM FIXTURES: a ContextWrapper redirects
 * getFilesDir/getCacheDir into a unique directory under the target app's cacheDir, so
 * RuntimeManager never touches the real app root or user data. No fake Linux/PRoot
 * claims are made there — only file/marker/persistence contracts are exercised.
 *
 * Test 3 is OPT-IN and REAL: it downloads official public Ubuntu/Node archives,
 * installs missing necessary Ubuntu packages from signed apt repositories, then
 * deliberately removes and repairs a fixture runtime library. No model or DSH
 * user config is read. Runs only with fullComponentProbe=true.
 */
class ComponentVersionTest {
    private class FixtureContext(base:Context,private val directory:File):ContextWrapper(base) {
        override fun getFilesDir()=directory
        override fun getCacheDir()=File(directory,"cache").apply { mkdirs() }
    }

    /** Synthetic fixture only: fake node binary + component-version marker, never a real Linux layer. */
    private fun fakeNode(runtimeRoot:File,version:String) {
        val node=File(runtimeRoot,"runtime/node")
        File(node,"bin").mkdirs()
        File(node,"bin/node").writeText("fake node binary for fixture")
        File(node,".component-version").writeText(version)
    }

    private fun ubuntuMarker(manager:RuntimeManager,version:String) {
        val artifact=RuntimeCatalog.find(RuntimeComponent.UBUNTU,version) ?: error("unknown ubuntu $version")
        manager.paths.root.mkdirs()
        File(manager.paths.root,".base-verified").writeText("${artifact.version} ${artifact.sha256}")
    }

    private suspend fun cleanupManagers(directory:File,vararg managers:RuntimeManager) {
        for(m in managers) {
            runCatching { m.cancel() }
            withTimeout(30000) { while(m.hasActiveOperation)delay(50) }
            m.scope.cancel()
        }
        RuntimeManager.deleteTree(directory)
    }

    /**
     * Fixture test 1: selecting newer versions must not touch the installed components,
     * must persist across a fresh RuntimeManager, and must switch download mirror URLs.
     */
    @Test fun selectionPersistsWithoutTouchingInstalledComponents():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"component-version-sel-${System.nanoTime()}").apply { mkdirs() }
        val m=RuntimeManager(FixtureContext(context,directory))
        try {
            // Installed (old) components inside the fixture: Ubuntu 24.04.5 + Node 24.22.2.
            ubuntuMarker(m,RuntimeManager.UBUNTU)
            fakeNode(directory,RuntimeManager.NODE)
            m.paths.atomic("installed.json",JSONObject().put("ready",true).put("ubuntu",RuntimeManager.UBUNTU).put("node",RuntimeManager.NODE).put("dsh",RuntimeManager.DSH).toString())
            val projectSentinel=File(m.paths.workspaces,"project/sentinel.txt")
            projectSentinel.parentFile!!.mkdirs();projectSentinel.writeText("fixture project data")
            val metadataBefore=File(m.paths.state,"installed.json").readText()

            // Select newer versions; this must only persist preferences, never mutate installs.
            m.components.selectVersion(RuntimeComponent.UBUNTU,"22.04.5")
            m.components.selectVersion(RuntimeComponent.NODE,"26.13.0")

            assertEquals(RuntimeManager.UBUNTU,m.components.installedUbuntuVersion())
            assertEquals(RuntimeManager.NODE,m.components.installedNodeVersion())
            assertEquals(metadataBefore,File(m.paths.state,"installed.json").readText())
            assertEquals("fixture project data",projectSentinel.readText())
            assertEquals("22.04.5",m.components.selected(RuntimeComponent.UBUNTU).version)
            assertEquals("26.13.0",m.components.selected(RuntimeComponent.NODE).version)

            // Mirror URLs must point at the newly selected versions.
            val ubuntuSource=m.components.sourceFor(RuntimeComponent.UBUNTU).url
            val nodeSource=m.components.sourceFor(RuntimeComponent.NODE).url
            assertTrue(ubuntuSource,ubuntuSource.contains("22.04.5"))
            assertTrue(nodeSource,nodeSource.contains("v26.13.0"))

            // Selection persists across a fresh RuntimeManager on the same fixture.
            val m2=RuntimeManager(FixtureContext(context,directory))
            try {
                assertEquals("22.04.5",m2.components.selected(RuntimeComponent.UBUNTU).version)
                assertEquals("26.13.0",m2.components.selected(RuntimeComponent.NODE).version)
            } finally { m2.scope.cancel() }
        } finally { cleanupManagers(directory,m) }
    }

    /**
     * Fixture test 2: an interrupted Node replace (old moved to node.previous, new candidate
     * at the destination, journal not yet committed) must roll back to the old Node via
     * recover(), delete the previous snapshot, and keep the user's version selection.
     */
    @Test fun interruptedNodeReplaceRecoversPreviousVersion():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"component-version-rec-${System.nanoTime()}").apply { mkdirs() }
        try {
            // Build the interrupted state BEFORE the manager exists: its init runs recover().
            fakeNode(directory,RuntimeManager.NODE) // destination now holds the NEW node 26.13.0
            File(File(directory,"runtime/node"),".component-version").writeText("26.13.0")
            val previous=File(File(directory,"runtime"),"node.previous")
            val previousNode=File(previous,"bin").apply { mkdirs() }
            File(previousNode,"node").writeText("fake node binary for fixture")
            File(previous,".component-version").writeText(RuntimeManager.NODE)
            val projectSentinel=File(File(directory,"workspaces"),"project/sentinel.txt")
            projectSentinel.parentFile!!.mkdirs();projectSentinel.writeText("fixture project data")
            // Uncommitted component-transaction journal in the "switching" phase.
            val journal=JSONObject().put("component","node").put("phase","switching").put("hadPrevious",true)
            val atomicState=File(directory,"state").apply { mkdirs() }
            File(atomicState,"component-versions.json").writeText(JSONObject().put("NODE","26.13.0").toString())
            android.util.AtomicFile(File(atomicState,"component-transaction")).let { af ->
                val out=af.startWrite();try { out.write(journal.toString().toByteArray());af.finishWrite(out) } catch(t:Throwable) { af.failWrite(out);throw t }
            }

            val m=RuntimeManager(FixtureContext(context,directory))
            try {
                m.components.recover() // explicit + idempotent; init already performed recovery

                // The old Node 24.22.2 is restored at the destination; the new 26.13.0 was discarded.
                assertEquals(RuntimeManager.NODE,m.components.installedNodeVersion())
                assertEquals(RuntimeManager.NODE,File(m.paths.node,".component-version").readText().trim())
                assertFalse(previous.exists())
                assertFalse(File(m.paths.runtime,"node.install").exists())
                assertEquals("fixture project data",projectSentinel.readText())

                // Rollback does not change the user's persisted selection (still 26.13.0).
                assertEquals("26.13.0",m.components.selected(RuntimeComponent.NODE).version)
            } finally { m.scope.cancel() }
        } finally { RuntimeManager.deleteTree(directory) }
    }

    /**
     * OPT-IN REAL test (run with: -e fullComponentProbe true). Downloads the official
     * Ubuntu 22.04.5 base image (~28MB, verified SHA) and official Node.js 26.13.0
     * (~62MB, verified SHA) from public archives, using signed apt repositories to
     * install necessary components and repair a deliberately removed fixture library.
     * NO model, NO DSH or user
     * configuration — then boots them under real PRoot and checks actual versions.
     */
    @Test fun actualAlternativeUbuntuAndNode():Unit=runBlocking {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("opt-in real download test; pass -e fullComponentProbe true to run",
            arguments.getString("fullComponentProbe")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val foreground=InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory=File(context.cacheDir,"component-version-real-${System.nanoTime()}").apply { mkdirs() }
        val m=RuntimeManager(FixtureContext(context,directory))
        try {
            m.components.selectVersion(RuntimeComponent.UBUNTU,"22.04.5")
            m.components.selectVersion(RuntimeComponent.NODE,"26.13.0")
            m.mutation.withLock {
                m.ensureBase()
                m.components.ensureNode()
                m.components.refresh()
            }
            val ubuntu=m.components.installedUbuntuVersion()
            val node=m.components.installedNodeVersion()
            // Versions are logged publicly, and only the versions.
            Log.i("TANUKI_PROBE","ubuntu=$ubuntu node=$node")
            assertEquals("22.04.5",ubuntu)
            assertEquals("26.13.0",node)

            // REAL PRoot execution: actual Node inside the actual Ubuntu root.
            val nodeResult=m.launcher.run(m.paths.root,listOf("/opt/node/bin/node","--version"),120000)
            assertEquals(nodeResult.stdout,nodeResult.exitCode,0)
            assertEquals("v26.13.0",nodeResult.stdout.trim())

            // MEANINGFUL REPAIR FAILURE INJECTION (opt-in real test only):
            // delete the libatomic.so.1 fixture file itself (it is a symlink; Files.delete
            // removes the link, never the real library or any user file outside m.paths.root),
            // then verify Node 26 actually breaks and RuntimeRequirements/ensureNodeLibraries
            // auto-detects libatomic1 as missing and repairs it via apt --reinstall.
            val libAtomic=Paths.get(m.paths.root.absolutePath,"usr/lib/aarch64-linux-gnu/libatomic.so.1")
            assertTrue(libAtomic.toString(),Files.exists(libAtomic,LinkOption.NOFOLLOW_LINKS))
            val sentinel=File(m.paths.workspaces,"project/sentinel.txt")
            sentinel.parentFile!!.mkdirs();sentinel.writeText("real fixture project data")
            Files.delete(libAtomic)
            val broken=m.launcher.run(m.paths.root,listOf("/opt/node/bin/node","--version"),120000)
            assertNotEquals("libatomic.so.1 deleted; node must fail",0,broken.exitCode)
            m.mutation.withLock {
                assertEquals(listOf("libatomic1"),RuntimeRequirements(m).inspect(tools=false))
                m.ensureNodeLibraries()
                assertEquals("repair must clear missing list",emptyList<String>(),RuntimeRequirements(m).inspect(tools=false))
            }
            assertEquals("real fixture project data",sentinel.readText())
            val repaired=m.launcher.run(m.paths.root,listOf("/opt/node/bin/node","--version"),120000)
            assertEquals(repaired.stderr,repaired.exitCode,0)
            assertEquals("v26.13.0",repaired.stdout.trim())

            val osResult=m.launcher.run(m.paths.root,listOf("/bin/cat","/etc/os-release"),120000)
            assertEquals(osResult.stderr,osResult.exitCode,0)
            assertTrue(osResult.stdout,osResult.stdout.contains("VERSION_ID=\"22.04\""))
        } finally {
            // Heavy test runs on the instrumentation (foreground) thread; keep UID foreground.
            runCatching { m.cancel() }
            withTimeout(60000) { while(m.hasActiveOperation)delay(100) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { foreground.finish() }
            m.scope.cancel()
            RuntimeManager.deleteTree(directory)
        }
    }
}
