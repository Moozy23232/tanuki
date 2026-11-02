package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import com.tanuki.download.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ComponentReinstallTest {
    private class FixtureContext(base:Context,private val directory:File):ContextWrapper(base) {
        override fun getFilesDir()=directory
        override fun getCacheDir()=File(directory,"cache").apply { mkdirs() }
    }
    private suspend fun awaitIdle(m:RuntimeManager) {
        withTimeout(720000) { while(m.hasActiveOperation)delay(100) }
        assertEquals("component failed: ${m.state.value.error}","",m.state.value.error)
    }
    private fun marker(file:File,text:String) { file.parentFile!!.mkdirs();file.writeText(text) }
    private suspend fun cleanup(m:RuntimeManager,directory:File) {
        m.cancel()
        withTimeout(30000) { while(m.hasActiveOperation)delay(50) }
        m.scope.cancel();RuntimeManager.deleteTree(directory)
    }

    /** Real Ubuntu/Node archives and real PRoot; all installed files belong to a disposable fixture. */
    @Test fun actualLayersSurviveIndependentUninstallAndReinstall():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val downloads=File(context.filesDir,"downloads")
        val archives=listOf(
            "ubuntu-${RuntimeManager.UBUNTU}-arm64.tar.gz" to RuntimeManager.UBUNTU_SHA,
            "node-${RuntimeManager.NODE}-arm64.tar.gz" to RuntimeManager.NODE_SHA,
            "pnpm-${RuntimeManager.PNPM}.tgz" to RuntimeManager.PNPM_SHA)
        assumeTrue("requires verified public Ubuntu and Node archive caches",
            archives.take(2).all { File(downloads,it.first).isFile })
        assumeTrue("requires 2 GB free for disposable Ubuntu",context.filesDir.usableSpace>2L*1024*1024*1024)
        val foreground=InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory=File(context.cacheDir,"component-real-${System.nanoTime()}").apply { mkdirs() }
        val m=RuntimeManager(FixtureContext(context,directory))
        try {
            for((name,sha) in archives) {
                val source=File(downloads,name)
                if(source.isFile) {
                    assertEquals("public archive integrity: $name",sha,Downloader.hash(source))
                    source.copyTo(File(m.paths.downloads,name))
                }
            }
            val sentinels=listOf(File(m.paths.home,"keep-home"),File(m.paths.dshData,"keep-config"),File(m.paths.workspaces,"keep-project"))
            sentinels.forEach { marker(it,"preserve-${it.name}") }
            fun assertData()=sentinels.forEach { assertEquals("preserve-${it.name}",it.readText()) }
            m.mutation.withLock { m.ensureBase();m.components.ensureNode();m.components.refresh() }
            suspend fun nodeVersion() {
                val result=m.launcher.run(m.paths.root,listOf("/opt/node/bin/node","--version"))
                assertEquals(result.stderr,0,result.exitCode)
                assertEquals("v${RuntimeManager.NODE}",result.stdout.trim())
            }
            nodeVersion()
            val nodeHash=Downloader.hash(File(m.paths.node,"bin/node"))
            m.mutation.withLock { m.components.ensureNode(true);m.components.refresh() }
            assertEquals(nodeHash,Downloader.hash(File(m.paths.node,"bin/node")))
            nodeVersion();assertData()
            m.components.uninstall(RuntimeComponent.NODE);awaitIdle(m)
            assertFalse(m.components.state.value.nodeInstalled)
            assertTrue(m.components.state.value.ubuntuInstalled);assertData()
            m.components.install(RuntimeComponent.NODE);awaitIdle(m)
            assertTrue(m.components.state.value.nodeInstalled);nodeVersion();assertData()
            m.components.uninstall(RuntimeComponent.UBUNTU);awaitIdle(m)
            assertFalse(m.components.state.value.ubuntuInstalled)
            assertTrue(m.components.state.value.nodeInstalled)
            assertTrue(File(m.paths.pnpm,"${RuntimeManager.PNPM}/.verified").isFile)
            assertEquals(nodeHash,Downloader.hash(File(m.paths.node,"bin/node")));assertData()
            // Reinstall Ubuntu again; required-tool checks must preserve the independent Node and user data.
            m.components.install(RuntimeComponent.UBUNTU);awaitIdle(m)
            assertTrue(m.components.state.value.ubuntuInstalled);nodeVersion();assertData()
            val pnpm=m.launcher.run(m.paths.root,listOf("/opt/node/bin/node","/opt/pnpm/${RuntimeManager.PNPM}/package/bin/pnpm.cjs","--version"))
            assertEquals(pnpm.stderr,0,pnpm.exitCode)
            assertEquals(RuntimeManager.PNPM,pnpm.stdout.trim())
            assertEquals(nodeHash,Downloader.hash(File(m.paths.node,"bin/node")))
            assertFalse(File(m.paths.state,"component-transaction").exists())
            android.util.Log.i("TANUKI_PROBE","REAL_COMPONENT_REINSTALL ubuntu=${RuntimeManager.UBUNTU} node=${RuntimeManager.NODE} pnpm=${RuntimeManager.PNPM} data_preserved=true")
        } finally {
            try { cleanup(m,directory) } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { foreground.finish() } }
        }
    }

    /** Synthetic interrupted rename journal, not a simulated package installation. */
    @Test fun interruptedUbuntuSwapRestoresPreviousAndRetainsIndependentNode():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"component-journal-${System.nanoTime()}").apply { mkdirs() }
        val m=RuntimeManager(FixtureContext(context,directory))
        try {
            marker(File(m.paths.root,"new-image"),"new")
            marker(File(m.paths.runtime,"ubuntu.previous/old-image"),"old")
            marker(File(m.paths.node,"keep-node"),"node")
            marker(File(m.paths.workspaces,"keep-project"),"project")
            m.paths.atomic("component-transaction",JSONObject().put("component","ubuntu").put("phase","switching").put("hadPrevious",true).toString())
            m.components.recover()
            assertEquals("old",File(m.paths.root,"old-image").readText())
            assertFalse(File(m.paths.root,"new-image").exists())
            assertEquals("node",File(m.paths.node,"keep-node").readText())
            assertEquals("project",File(m.paths.workspaces,"keep-project").readText())
            assertFalse(File(m.paths.state,"component-transaction").exists())
        } finally { cleanup(m,directory) }
    }
}
