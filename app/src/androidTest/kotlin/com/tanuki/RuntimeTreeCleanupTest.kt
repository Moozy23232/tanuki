package com.tanuki

import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.Paths
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Regression for an interrupted PRoot bind leaving mode-000 directories in the discarded rootfs. */
class RuntimeTreeCleanupTest {
    private fun fixture(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath(), "tree-cleanup-").toFile()
        try { block(base) } finally { RuntimeManager.deleteTree(base) }
    }
    @Test fun removesUnreadableAndUnwritableDirectoriesIncludingTheRoot() = fixture { base ->
        val root = File(base, "discard").apply { mkdirs() }
        val parent = File(root, "data/user/0/app/files").apply { mkdirs() }
        val placeholder = File(parent, "link-store").apply { mkdirs() }
        File(placeholder, "partial").writeText("incomplete installation")
        Os.chmod(placeholder.path, 0)
        Os.chmod(parent.path, 256) // 0400: readable but not searchable/writable
        Os.chmod(root.path, 0)
        RuntimeManager.deleteTree(root)
        assertFalse(Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
    @Test fun neverChangesOrDeletesSymlinkTargetsAndDeletesDanglingRootLinks() = fixture { base ->
        val keep = File(base, "keep").apply { mkdirs() }
        File(keep, "data").writeText("preserve")
        Os.chmod(keep.path, 320) // 0500; target mode must not be changed by cleanup
        val root = File(base, "discard").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "mounted-data").toPath(), keep.toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(base, "absent").toPath())
        Os.chmod(root.path, 0)
        RuntimeManager.deleteTree(root)
        assertEquals("preserve", File(keep, "data").readText())
        assertEquals(320, Os.lstat(keep.path).st_mode and 511)
        val rootLink = File(base, "root-link")
        Files.createSymbolicLink(rootLink.toPath(), File(base, "absent").toPath())
        RuntimeManager.deleteTree(rootLink)
        assertFalse(Files.exists(rootLink.toPath(), LinkOption.NOFOLLOW_LINKS))
    }
    @Test fun startupRecoversInterruptedFirstInstallWithoutDeletingPersistentData() = fixture { base ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = base }
        val paths = Paths(wrapped)
        val sentinels = listOf(File(paths.workspaces, "project"), File(paths.home, "config"),
            File(paths.dshData, "session"), File(paths.linkStore, "persistent-payload"), File(paths.downloads, "archive"))
        sentinels.forEach { it.parentFile!!.mkdirs(); it.writeText("keep-${it.name}") }
        val placeholder = File(paths.root, paths.linkStore.absolutePath.removePrefix("/")).apply { mkdirs() }
        Os.chmod(placeholder.path, 0)
        paths.atomic("component-transaction", JSONObject().put("component", "ubuntu")
            .put("phase", "switching").put("hadPrevious", false).toString())
        val manager = RuntimeManager(wrapped)
        try {
            assertEquals("", manager.state.value.error)
            assertFalse(paths.root.exists())
            assertNull(paths.read("component-transaction"))
            sentinels.forEach { assertEquals("keep-${it.name}", it.readText()) }
        } finally { manager.scope.cancel() }
    }
    @Test fun rollbackWithUnreadableCandidateRestoresPreviousUbuntu() = fixture { base ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = base }
        val paths = Paths(wrapped)
        File(paths.runtime, "ubuntu.previous").mkdirs()
        File(paths.runtime, "ubuntu.previous/old-image").writeText("previous Ubuntu")
        val placeholder = File(paths.root, "locked/mount").apply { mkdirs() }
        Os.chmod(placeholder.path, 0)
        paths.atomic("component-transaction", JSONObject().put("component", "ubuntu")
            .put("phase", "switching").put("hadPrevious", true).toString())
        val manager = RuntimeManager(wrapped)
        try {
            assertEquals("", manager.state.value.error)
            assertEquals("previous Ubuntu", File(paths.root, "old-image").readText())
            assertFalse(File(paths.root, "locked").exists())
            assertNull(paths.read("component-transaction"))
        } finally { manager.scope.cancel() }
    }
}
