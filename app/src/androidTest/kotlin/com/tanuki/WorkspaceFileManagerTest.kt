package com.tanuki

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.Paths
import com.tanuki.storage.WorkspaceFileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class WorkspaceFileManagerTest {
    private suspend fun fixture(block: suspend (Paths, WorkspaceFileManager) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = Files.createTempDirectory(context.cacheDir.toPath(), "file-manager-test-").toFile()
        val paths = Paths(object : ContextWrapper(context) { override fun getFilesDir(): File = cache })
        try { block(paths, WorkspaceFileManager(paths)) }
        finally {
            Files.walkFileTree(cache.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
            })
        }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Expected operation to fail")
    }
    @Test fun ordinaryOperationsAndDirectoryOrdering(): Unit = runBlocking {
        fixture { _, manager ->
            manager.createText("", "a.txt", "你好")
            manager.createFolder("", "z")
            assertEquals(listOf("z", "a.txt"), manager.list().map { it.name })
            assertEquals(6L, manager.list().last().bytes)
            manager.copy("a.txt", "z")
            manager.rename("z/a.txt", "副本.txt")
            manager.move("a.txt", "z", "原文.txt")
            assertEquals("你好", manager.preview("z/副本.txt").text)
            manager.delete("z/原文.txt")
            assertEquals(listOf("副本.txt"), manager.list("z").map { it.name })
        }
    }
    @Test fun rejectsTraversalRootMutationAndRedirectedDirectories(): Unit = runBlocking {
        fixture { paths, manager ->
            val outside = File(paths.base, "outside").apply { mkdir() }
            File(outside, "keep").writeText("keep")
            for (bad in listOf("../escape", "/absolute", "a/b", "..", ".", "nul\u0000", "a\\b", ".l2s.secret0001")) {
                rejected { manager.createText("", bad, "bad") }
            }
            rejected { manager.list("../outside") }
            rejected { manager.list("/outside") }
            rejected { manager.delete("") }
            Files.createSymbolicLink(File(paths.workspaces, "redirect").toPath(), outside.toPath())
            rejected { manager.createText("redirect", "new", "bad") }
            rejected { manager.preview("redirect/keep") }
            manager.delete("redirect")
            assertEquals("keep", File(outside, "keep").readText())
            assertTrue(paths.workspaces.delete())
            Files.createSymbolicLink(paths.workspaces.toPath(), outside.toPath())
            rejected { manager.list() }
        }
    }
    @Test fun refusesOverwriteAndDirectoryCycles(): Unit = runBlocking {
        fixture { _, manager ->
            manager.createText("", "source", "source")
            manager.createText("", "keep", "original")
            rejected { manager.createText("", "keep", "overwrite") }
            rejected { manager.copy("source", "", "keep") }
            rejected { manager.move("source", "", "keep") }
            rejected { manager.rename("source", "keep") }
            manager.createFolder("", "tree")
            manager.createFolder("tree", "child")
            rejected { manager.copy("tree", "tree/child") }
            rejected { manager.move("tree", "tree/child") }
            assertEquals("original", manager.preview("keep").text)
            assertEquals("source", manager.preview("source").text)
        }
    }
    @Test fun failedCopiesCleanStagesAndNeverFollowOrdinaryLinks(): Unit = runBlocking {
        fixture { paths, manager ->
            val outside = File(paths.base, "outside").apply { writeText("private") }
            manager.createFolder("", "tree")
            manager.createText("tree", "good", "good")
            Files.createSymbolicLink(File(paths.workspaces, "tree/bad").toPath(), outside.toPath())
            rejected { manager.preview("tree/bad") }
            rejected { manager.copy("tree", "", "copy") }
            assertEquals(setOf("tree"), paths.workspaces.list()!!.toSet())
            manager.createFolder("", "target")
            rejected { manager.move("tree", "target") }
            rejected { manager.rename("tree", "changed") }
            manager.delete("tree")
            assertEquals("private", outside.readText())
            Files.createSymbolicLink(File(paths.workspaces, "dangling").toPath(), File(paths.base, "missing").toPath())
            rejected { manager.createText("", "dangling", "bad") }
            manager.rename("dangling", "renamed")
            manager.delete("renamed")
        }
    }
    @Test fun approvedLinksReadAndCopyButDeletionPreservesSharedPayload(): Unit = runBlocking {
        fixture { paths, manager ->
            paths.linkStore.mkdir()
            val index = File(paths.linkStore, ".l2s.fixture0001").toPath()
            val payload = File(paths.linkStore, ".l2s.fixture0001.0002").apply { writeText("shared") }
            Files.createSymbolicLink(index, payload.toPath())
            Files.createSymbolicLink(File(paths.workspaces, "published").toPath(), index)
            assertEquals("shared", manager.preview("published").text)
            manager.copy("published", "", "independent")
            manager.rename("published", "renamed")
            manager.delete("renamed")
            assertEquals("shared", payload.readText())
            assertTrue(Files.isSymbolicLink(index))
            assertFalse(Files.isSymbolicLink(File(paths.workspaces, "independent").toPath()))
            val unapproved = File(paths.base, "unapproved").apply { mkdir() }
            val badIndex = File(unapproved, ".l2s.other0001").toPath()
            val badPayload = File(unapproved, ".l2s.other0001.0002").apply { writeText("private") }
            Files.createSymbolicLink(badIndex, badPayload.toPath())
            Files.createSymbolicLink(File(paths.workspaces, "bad").toPath(), badIndex)
            rejected { manager.preview("bad") }
            rejected { manager.copy("bad", "", "bad-copy") }
            manager.delete("bad")
            assertEquals("private", badPayload.readText())
            Files.createSymbolicLink(File(paths.workspaces, "broken").toPath(), File(paths.linkStore, ".l2s.missing0001").toPath())
            rejected { manager.preview("broken") }
            rejected { manager.copy("broken", "", "broken-copy") }
            manager.delete("broken")
            assertFalse(File(paths.workspaces, "broken-copy").exists())
        }
    }
    @Test fun editorDetectsConflictsPreservesModeAndRejectsLinkedTargets(): Unit = runBlocking {
        fixture { paths, manager ->
            manager.createText("", "edit.sh", "before")
            val file = File(paths.workspaces, "edit.sh")
            android.system.Os.chmod(file.path, 448)
            val initialRevision = requireNotNull(manager.preview("edit.sh").revision)
            manager.saveText("edit.sh", "after", initialRevision)
            assertEquals("after", manager.preview("edit.sh").text)
            assertEquals(448, android.system.Os.stat(file.path).st_mode and 511)
            rejected { manager.saveText("edit.sh", "stale", initialRevision) }
            assertEquals("after", file.readText())
            val latest = manager.preview("edit.sh")
            file.writeText("external")
            rejected { manager.saveText("edit.sh", "overwrite", latest.revision!!) }
            assertEquals("external", file.readText())
            val outside = File(paths.base, "outside-edit").apply { writeText("private") }
            Files.createSymbolicLink(File(paths.workspaces, "link").toPath(), outside.toPath())
            rejected { manager.saveText("link", "bad", initialRevision) }
            assertEquals("private", outside.readText())
            assertFalse(paths.workspaces.list()!!.any { it.startsWith(".workspace-tmp-") })
            assertTrue(manager.list().first { it.name == "edit.sh" }.modified > 0)
        }
    }
    @Test fun readonlyGuestViewsMirrorMountedDataAndProtectEveryRoot(): Unit = runBlocking {
        fixture { paths, manager ->
            File(paths.root, "workspaces").mkdirs()
            File(paths.root, "home/tanuki").mkdirs()
            File(paths.root, "opt/node").mkdirs()
            File(paths.root, "workspaces/decoy").writeText("image-only")
            File(paths.root, "etc").mkdirs()
            File(paths.root, "etc/fixture").writeText("system")
            paths.node.mkdirs()
            File(paths.node, "node-fixture").writeText("mounted node")
            File(paths.home, "home-fixture").writeText("mounted home")
            manager.createText("", "shared.txt", "project")
            val ubuntu = manager.inLocation(com.tanuki.storage.FileLocation.UBUNTU)
            val home = manager.inLocation(com.tanuki.storage.FileLocation.HOME)
            assertEquals("/", ubuntu.guestPath(""))
            assertEquals("/home/tanuki/home-fixture", home.guestPath("home-fixture"))
            assertEquals(listOf("shared.txt"), ubuntu.list("workspaces").map { it.name })
            assertEquals("project", ubuntu.preview("workspaces/shared.txt").text)
            assertEquals("mounted home", ubuntu.preview("home/tanuki/home-fixture").text)
            assertEquals("mounted home", home.preview("home-fixture").text)
            assertEquals("mounted node", ubuntu.preview("opt/node/node-fixture").text)
            assertEquals("system", ubuntu.preview("etc/fixture").text)
            rejected { ubuntu.delete("workspaces/shared.txt") }
            rejected { home.createText("", "new", "bad") }
            rejected { ubuntu.list("../") }
            val redirected = File(paths.root, "redirect").toPath()
            Files.createSymbolicLink(redirected, paths.home.toPath())
            rejected { ubuntu.list("redirect") }
            Files.createSymbolicLink(File(paths.root, "etc/link").toPath(), File(paths.home, "home-fixture").toPath())
            rejected { ubuntu.preview("etc/link") }
            assertEquals("project", manager.preview("shared.txt").text)
            // A bind's parent may not be replaced with a symlink, even if its leaf exists.
            Files.move(paths.data.toPath(), File(paths.base, "original-data").toPath())
            val redirectedData = File(paths.base, "other-data").apply { mkdirs() }
            File(redirectedData, "home").mkdirs()
            Files.createSymbolicLink(paths.data.toPath(), redirectedData.toPath())
            rejected { home.list() }
        }
    }
    @Test fun protectsLegacyBackingAndBoundsTextPreview(): Unit = runBlocking {
        fixture { paths, manager ->
            manager.createFolder("", "legacy")
            File(paths.workspaces, "legacy/.l2s.payload0001.0002").writeText("shared")
            rejected { manager.delete("legacy") }
            rejected { manager.rename("legacy", "changed") }
            manager.createText("", "limit", "x".repeat(WorkspaceFileManager.TEXT_LIMIT))
            assertEquals(WorkspaceFileManager.TEXT_LIMIT, manager.preview("limit").text!!.length)
            File(paths.workspaces, "large").writeBytes(ByteArray(WorkspaceFileManager.TEXT_LIMIT + 1) { 65 })
            rejected { manager.preview("large") }
            rejected { manager.createText("", "too-large", "中".repeat(WorkspaceFileManager.TEXT_LIMIT)) }
            File(paths.workspaces, "binary").writeBytes(byteArrayOf(0, 1))
            rejected { manager.preview("binary") }
            File(paths.workspaces, "invalid-utf8").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            rejected { manager.preview("invalid-utf8") }
            assertFalse(File(paths.workspaces, "too-large").exists())
        }
    }
}
