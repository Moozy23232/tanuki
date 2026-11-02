package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import com.tanuki.storage.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream

class FilesystemSemanticsTest {
    private class FixtureContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }
    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".workspacecheck")) { "This regression must run in the isolated verification app" }
    }
    private fun chain(paths: Paths, file: File, name: String, payload: String): File {
        L2sStore.prepare(paths)
        val index = File(paths.linkStore, ".l2s.${name}0001")
        val data = File(paths.linkStore, "${index.name}.0002").apply { writeText(payload) }
        Files.createSymbolicLink(index.toPath(), data.toPath())
        file.parentFile!!.mkdirs()
        Files.createSymbolicLink(file.toPath(), index.toPath())
        return data
    }

    @Test fun linkedPayloadIsCountedExportedAndSnapshottedIndependently(): Unit = runBlocking {
        val directory = File(context().cacheDir, "l2s-host-${System.nanoTime()}")
        val paths = Paths(FixtureContext(context(), directory))
        try {
            val project = File(paths.workspaces, "project.txt")
            val data = chain(paths, project, "project", "project-data\n".repeat(2000))
            val single = DirectoryUsage.measure(paths.workspaces, linkedFilesRoot = paths.base)
            assertTrue(single.complete)
            Files.createSymbolicLink(File(paths.workspaces, "alias.txt").toPath(), Files.readSymbolicLink(project.toPath()))
            val result = DirectoryUsage.measure(paths.workspaces, linkedFilesRoot = paths.base)
            val payloadBytes = android.system.Os.stat(data.path).st_blocks * 512
            assertTrue("linked allocation is incomplete: ${result.incompleteReason}; unreadable=${result.unreadableEntries}", result.complete)
            assertTrue(result.allocatedBytes >= payloadBytes)
            assertTrue("an additional link must not count the payload again: single=${single.allocatedBytes}, linked=${result.allocatedBytes}, payload=$payloadBytes",
                result.allocatedBytes - single.allocatedBytes < payloadBytes)
            File(paths.workspaces, "empty-directory").mkdirs()
            val output = ByteArrayOutputStream()
            ProjectArchive.write(paths.workspaces, paths.base, output)
            val exported = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; exported[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals(data.readText(), exported["project.txt"])
            assertEquals(data.readText(), exported["alias.txt"])
            assertTrue("empty project folders must survive export", exported.containsKey("empty-directory/"))
            val ordinaryLink = File(paths.workspaces, "ordinary-link")
            Files.createSymbolicLink(ordinaryLink.toPath(), java.nio.file.Paths.get("project.txt"))
            try {
                ProjectArchive.write(paths.workspaces, paths.base, ByteArrayOutputStream())
                fail("unsupported ordinary links must not be silently dropped from an export")
            } catch (_: IOException) { }
            Files.delete(ordinaryLink.toPath())
            val user = File(paths.dshData, "state.txt")
            val userData = chain(paths, user, "state", "snapshot-data")
            val snapshot = File(paths.backups, "test-copy")
            Files.createSymbolicLink(File(paths.home, "node-tool").toPath(), java.nio.file.Paths.get("/opt/node/bin/node"))
            DataSnapshots.copy(paths.data, snapshot, paths.base)
            assertEquals("/opt/node/bin/node", Files.readSymbolicLink(File(snapshot, "home/node-tool").toPath()).toString())
            val copied = File(snapshot, "dsh/state.txt")
            assertFalse(Files.isSymbolicLink(copied.toPath()))
            userData.writeText("changed-after-snapshot")
            assertEquals("snapshot-data", copied.readText())
            assertEquals(0, L2sStore.collectUnreferenced(paths))
            Files.delete(project.toPath())
            Files.delete(File(paths.workspaces, "alias.txt").toPath())
            assertEquals(2, L2sStore.collectUnreferenced(paths))
            assertEquals("changed-after-snapshot", user.readText())
        } finally { RuntimeManager.deleteTree(directory) }
    }

    @Test fun missingLinkedContentFailsExportAndSnapshotAndMakesUsageUnknown(): Unit = runBlocking {
        val directory = File(context().cacheDir, "l2s-missing-${System.nanoTime()}")
        val paths = Paths(FixtureContext(context(), directory))
        try {
            val file = File(paths.workspaces, "lost.txt")
            val data = chain(paths, file, "lost", "present-before-removal")
            assertTrue(data.delete())
            assertFalse(DirectoryUsage.measure(paths.workspaces, linkedFilesRoot = paths.base).complete)
            try { ProjectArchive.write(paths.workspaces, paths.base, ByteArrayOutputStream()); fail("missing payload must fail export") }
            catch (_: IOException) { }
            try { DataSnapshots.copy(paths.workspaces, File(paths.backups, "broken"), paths.base); fail("missing payload must fail snapshot") }
            catch (_: IOException) { }
        } finally { RuntimeManager.deleteTree(directory) }
    }

    /** Real PRoot + Node, public installation archives, no real DSH data or model request. */
    @Test fun atomicPublicationAndTemporaryDirectoriesSurviveCleanupAndRestart(): Unit = runBlocking {
        val context = context()
        val foreground = InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(context.cacheDir, "l2s-real-${System.nanoTime()}").apply { mkdirs() }
        val manager = RuntimeManager(FixtureContext(context, directory))
        try {
            for (archive in File(context.filesDir, "downloads").listFiles().orEmpty()) {
                if (archive.isFile && archive.name.endsWith(".gz") || archive.isFile && archive.name.endsWith(".tgz")) {
                    archive.copyTo(File(manager.paths.downloads, archive.name))
                }
            }
            manager.mutation.withLock {
                manager.ensureBase()
                manager.components.ensureNode()
            }
            // The old rootfs entry may be broken; an explicit /tmp bind must still work.
            val oldTmp = File(manager.paths.root, "tmp")
            RuntimeManager.deleteTree(oldTmp)
            Files.createSymbolicLink(oldTmp.toPath(), java.nio.file.Paths.get("/missing-tanuki-tmp-fixture"))
            val script = """
                const fs=require('node:fs'),os=require('node:os'),assert=require('node:assert/strict');
                const stage='/workspaces/.atomic-stage';fs.mkdirSync(stage);
                const temp=stage+'/new.tmp';fs.writeFileSync(temp,'durable-payload',{flag:'wx'});
                fs.linkSync(temp,'/workspaces/published.txt');
                fs.linkSync(temp,'/workspaces/second.txt');
                assert.throws(()=>fs.linkSync(temp,'/workspaces/published.txt'),{code:'EEXIST'});
                fs.rmSync(stage,{recursive:true,force:true});
                assert.equal(fs.readFileSync('/workspaces/published.txt','utf8'),'durable-payload');
                fs.unlinkSync('/workspaces/second.txt');
                assert.equal(fs.readFileSync('/workspaces/published.txt','utf8'),'durable-payload');
                assert.equal(os.tmpdir(),'/tmp');
                const tmp=fs.mkdtempSync('/tmp/node-');fs.writeFileSync(tmp+'/ok','temp-ok');
                fs.rmSync(tmp,{recursive:true});
                fs.writeFileSync('/dev/shm/shm-probe','shared-ok');
                fs.unlinkSync('/dev/shm/shm-probe');
                console.log('ATOMIC_PUBLICATION_AND_NODE_TMP_OK');
            """.trimIndent()
            val first = manager.launcher.run(manager.paths.root, listOf("/opt/node/bin/node", "-e", script))
            assertEquals(first.stderr, 0, first.exitCode)
            assertTrue(first.stdout.contains("ATOMIC_PUBLICATION_AND_NODE_TMP_OK"))
            val restart = manager.launcher.run(manager.paths.root, listOf("/opt/node/bin/node", "-e",
                "require('node:assert/strict').equal(require('node:fs').readFileSync('/workspaces/published.txt','utf8'),'durable-payload');console.log('RESTART_OK')"))
            assertEquals(restart.stderr, 0, restart.exitCode)
            assertTrue(restart.stdout.contains("RESTART_OK"))
            android.util.Log.i("TANUKI_PROBE", "ATOMIC_PUBLICATION cleanup=true restart=true node_tmp=true broken_root_tmp=true disk_shm=true")
            manager.mutation.withLock { manager.ensureUbuntuTools() }
            val python = manager.launcher.run(manager.paths.root, listOf("/usr/bin/python3", "-I", "-c",
                "import tempfile; p=tempfile.mkdtemp(); assert p.startswith('/tmp/'); print('PYTHON_TMP_OK')"))
            assertEquals(python.stderr, 0, python.exitCode)
            assertTrue(python.stdout.contains("PYTHON_TMP_OK"))
            val payload = L2sStore.payload(File(manager.paths.workspaces, "published.txt").toPath(), manager.paths.base.toPath())
            assertNotNull("host resolver must see the actual PRoot chain", payload)
            assertEquals("durable-payload", payload!!.toFile().readText())
            android.util.Log.i("TANUKI_PROBE", "ATOMIC_PUBLICATION cleanup=true restart=true persistent_store=true node_tmp=true python_tmp=true disk_shm=true")
        } finally {
            manager.cancel(); manager.scope.cancel()
            RuntimeManager.deleteTree(directory)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { foreground.finish() }
        }
    }
}
