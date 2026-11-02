package com.tanuki

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.ComponentInventory
import com.tanuki.runtime.RuntimeComponent
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Filesystem scoping tests use fake binaries in a disposable ContextWrapper filesDir. */
class ComponentLifecycleTest {

    private companion object {
        const val NODE_BIN_TEXT = "fake-node-elf-binary-marker"
        const val PNPM_ENTRY_TEXT = "fake-pnpm-entry-marker"
        const val OTHER_ENGINE = "0.0.1-fixture"
        const val IDLE_TIMEOUT_MS = 30_000L
        const val STATE_TIMEOUT_MS = 15_000L
    }

    private class Fixture {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "component-fixture-${UUID.randomUUID()}")
        val wrapper = object : ContextWrapper(target) {
            override fun getFilesDir(): File = root
        }
        val m = RuntimeManager(wrapper)
        val components get() = m.components

        fun writeInstalledMetadata() = m.paths.atomic(
            "installed.json",
            JSONObject()
                .put("ready", true)
                .put("ubuntu", RuntimeManager.UBUNTU)
                .put("node", RuntimeManager.NODE)
                .put("dsh", RuntimeManager.DSH)
                .toString()
        )

        fun seedUbuntuRoot() {
            File(m.paths.root, ".base-verified").apply { parentFile.mkdirs() }.writeText("${RuntimeManager.UBUNTU} ${RuntimeManager.UBUNTU_SHA}")
            File(m.paths.root, "bin/sh").apply { parentFile.mkdirs() }.writeText("fake-binary-not-executable")
        }

        fun seedNewNode() {
            File(m.paths.node, "bin/node").apply { parentFile.mkdirs() }.writeText(NODE_BIN_TEXT)
            File(m.paths.pnpm, "${RuntimeManager.PNPM}/package/bin/pnpm.cjs").apply { parentFile.mkdirs() }.writeText(PNPM_ENTRY_TEXT)
            File(m.paths.pnpm, "${RuntimeManager.PNPM}/.verified").writeText("fixture")
        }

        fun seedLegacyNode() {
            File(m.paths.root, "opt/node/bin/node").apply { parentFile.mkdirs() }.writeText(NODE_BIN_TEXT)
            File(m.paths.root, "opt/node/lib/node_modules/npm/legacy-marker.txt")
                .apply { parentFile.mkdirs() }.writeText("legacy")
            File(m.paths.root, "opt/pnpm/${RuntimeManager.PNPM}/package/bin/pnpm.cjs")
                .apply { parentFile.mkdirs() }.writeText(PNPM_ENTRY_TEXT)
        }

        fun seedDshEngines(vararg versions: String) {
            versions.forEach { v ->
                File(m.paths.engines, "$v/node_modules/@deepseek-ai/dsh/lib/bin.js")
                    .apply { parentFile.mkdirs() }.writeText("fake-dsh-entry-$v")
                File(m.paths.engines,"$v/.installed").writeText("fixture-lock-marker")
            }
        }

        fun seedUserSentinels() {
            File(m.paths.home, "home-sentinel.txt").writeText("home")
            File(m.paths.dshData, "dsh-sentinel.txt").writeText("dsh-data")
            File(m.paths.workspaces, "ws-sentinel.txt").writeText("workspace")
            File(m.paths.backups, "backup-sentinel.txt").writeText("backup")
        }

        fun teardown() {
            runCatching { m.scope.cancel() }
            runCatching { RuntimeManager.deleteTree(root) }
        }
    }

    private suspend fun awaitIdle(m: RuntimeManager) {
        withTimeout(IDLE_TIMEOUT_MS) {
            while (m.hasActiveOperation) delay(50)
        }
    }

    private suspend fun awaitInventory(
        f: Fixture,
        timeoutMs: Long = STATE_TIMEOUT_MS,
        predicate: (ComponentInventory) -> Boolean
    ) {
        withTimeout(timeoutMs) {
            while (!predicate(f.components.state.value)) delay(50)
        }
    }

    @Test
    fun legacyMigrationThenUbuntuUninstallPreservesOtherLayersAndData(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.seedUserSentinels()
            f.seedUbuntuRoot()
            f.seedLegacyNode()
            f.seedDshEngines(RuntimeManager.DSH, OTHER_ENGINE)
            f.writeInstalledMetadata()
            f.components.refresh()
            awaitInventory(f) { it.ubuntuInstalled && it.nodeInstalled && it.dshInstalled }

            f.components.migrateLegacyLayers()

            val migratedNode = File(f.m.paths.node, "bin/node")
            assertTrue("legacy node should move to runtime/node", migratedNode.isFile && migratedNode.readText() == NODE_BIN_TEXT)
            assertEquals(PNPM_ENTRY_TEXT,File(f.m.paths.pnpm,"${RuntimeManager.PNPM}/package/bin/pnpm.cjs").readText())
            assertFalse("legacy root/opt/node should be gone after migration", File(f.m.paths.root, "opt/node").exists())
            assertFalse("legacy root/opt/pnpm should be gone after migration", File(f.m.paths.root, "opt/pnpm").exists())

            f.components.uninstall(RuntimeComponent.UBUNTU)
            awaitIdle(f.m)
            awaitInventory(f) { !it.ubuntuInstalled }
            delay(200)

            assertFalse("ubuntu uninstall must remove runtime/ubuntu", f.m.paths.root.exists())
            assertFalse("ubuntu uninstall must remove runtime/ubuntu.staging", f.m.paths.stage.exists())
            assertTrue("migrated node must survive ubuntu uninstall", File(f.m.paths.node, "bin/node").isFile)
            assertTrue("migrated pnpm must survive ubuntu uninstall", File(f.m.paths.pnpm, "${RuntimeManager.PNPM}/package/bin/pnpm.cjs").isFile)
            assertTrue(File(f.m.paths.home, "home-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.dshData, "dsh-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.workspaces, "ws-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.backups, "backup-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.engines, "${RuntimeManager.DSH}/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
            assertTrue(File(f.m.paths.engines, "$OTHER_ENGINE/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
            assertTrue("installed.json must survive ubuntu uninstall", f.m.paths.metadata().optBoolean("ready"))
        } finally {
            f.teardown()
        }
    }

    @Test
    fun nodeUninstallPreservesUbuntuDshAndData(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.seedUserSentinels()
            f.seedUbuntuRoot()
            f.seedNewNode()
            f.seedDshEngines(RuntimeManager.DSH, OTHER_ENGINE)
            f.writeInstalledMetadata()
            f.components.refresh()
            awaitInventory(f) { it.ubuntuInstalled && it.nodeInstalled && it.dshInstalled }

            f.components.uninstall(RuntimeComponent.NODE)
            awaitIdle(f.m)
            awaitInventory(f) { !it.nodeInstalled }
            delay(200)

            assertFalse("node uninstall must remove runtime/node", f.m.paths.node.exists())
            assertFalse("node uninstall must remove runtime/pnpm", f.m.paths.pnpm.exists())
            assertTrue("ubuntu root must survive node uninstall", File(f.m.paths.root, ".base-verified").isFile)
            assertTrue("selected dsh engine must survive node uninstall",
                File(f.m.paths.engines, "${RuntimeManager.DSH}/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
            assertTrue("other cached engine must survive node uninstall",
                File(f.m.paths.engines, "$OTHER_ENGINE/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
            assertTrue(File(f.m.paths.home, "home-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.dshData, "dsh-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.workspaces, "ws-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.backups, "backup-sentinel.txt").isFile)
            val meta = f.m.paths.metadata()
            assertTrue("installed.json must survive node uninstall", meta.optBoolean("ready"))
            assertEquals(RuntimeManager.DSH, meta.optString("dsh"))
            assertTrue(f.components.state.value.ubuntuInstalled)
        } finally {
            f.teardown()
        }
    }

    @Test
    fun activeDshUninstallPreservesOtherEnginesMetadataAndData(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.seedUserSentinels()
            f.seedUbuntuRoot()
            f.seedNewNode()
            f.seedDshEngines(RuntimeManager.DSH, OTHER_ENGINE)
            f.writeInstalledMetadata()
            f.components.refresh()
            awaitInventory(f) { it.ubuntuInstalled && it.nodeInstalled && it.dshInstalled }

            f.components.uninstallDsh()
            awaitIdle(f.m)
            awaitInventory(f) { !it.dshInstalled }
            delay(200)

            assertFalse("selected dsh engine dir must be removed", f.m.paths.engines.resolve(RuntimeManager.DSH).exists())
            val meta = f.m.paths.metadata()
            assertTrue("installed.json must survive dsh uninstall", meta.optBoolean("ready"))
            assertEquals("selected dsh version must be preserved in installed.json",
                RuntimeManager.DSH, meta.optString("dsh"))
            assertTrue("other cached engine must survive dsh uninstall",
                File(f.m.paths.engines, "$OTHER_ENGINE/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
            assertTrue("dsh data must survive dsh uninstall", File(f.m.paths.dshData, "dsh-sentinel.txt").isFile)
            assertTrue("ubuntu root must survive dsh uninstall", File(f.m.paths.root, ".base-verified").isFile)
            assertTrue("node must survive dsh uninstall", File(f.m.paths.node, "bin/node").isFile)
            assertTrue(File(f.m.paths.home, "home-sentinel.txt").isFile)
            assertTrue(File(f.m.paths.workspaces, "ws-sentinel.txt").isFile)
        } finally {
            f.teardown()
        }
    }

    @Test
    fun migrateLegacyLayersRejectsSymlinkedLegacyNode(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.seedUserSentinels()
            f.seedUbuntuRoot()
            f.writeInstalledMetadata()
            // Real workspace content that the legacy symlink points at; the new installer rejects
            // symlinked canonical paths, so migrate must throw and the workspace file must be
            // untouched.
            val workspaceBinDir = File(f.m.paths.workspaces, "legacy-node-repo/bin").apply { mkdirs() }
            val workspaceNode = File(workspaceBinDir, "node").apply { writeText(NODE_BIN_TEXT) }
            File(f.m.paths.root, "opt").apply { mkdirs() }
            Files.createSymbolicLink(File(f.m.paths.root, "opt/node").toPath(), f.m.paths.workspaces.resolve("legacy-node-repo").toPath())

            var threw = false
            try {
                f.components.migrateLegacyLayers()
            } catch (t: Throwable) {
                if (t is AssertionError) throw t
                threw = true
            }
            assertTrue("migrateLegacyLayers must reject symlinked legacy node", threw)

            assertEquals("workspace file must be unchanged after rejected migration", NODE_BIN_TEXT, workspaceNode.readText())
        } finally {
            f.teardown()
        }
    }
}
