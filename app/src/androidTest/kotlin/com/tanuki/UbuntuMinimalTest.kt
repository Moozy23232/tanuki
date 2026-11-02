package com.tanuki

import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Requires an initialized, dedicated App test installation; no network or model calls. */
class UbuntuMinimalTest {
    private suspend fun runtime(): RuntimeManager {
        val m = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TanukiApp).runtime
        withTimeout(120000) { m.stop(); while (m.hasActiveOperation) delay(100) }
        assertTrue("Initialize the dedicated test runtime first", m.paths.metadata().optBoolean("ready"))
        return m
    }

    private suspend fun command(m: RuntimeManager, vararg args: String): CommandResult =
        m.launcher.run(m.paths.root, args.toList(), 60000).also {
            assertEquals("${args.toList()}: ${it.stdout}\n${it.stderr}", 0, it.exitCode)
        }

    private suspend fun slim(m: RuntimeManager) {
        // Reset the observable result so a rejected/no-op request cannot reuse READY.
        m.state.value = m.state.value.copy(stage = "minimal-regression-${UUID.randomUUID()}", error = "")
        try {
            m.slimUbuntu()
            withTimeout(120000) { while (m.hasActiveOperation) delay(100) }
            val result = m.state.value
            assertEquals(result.error, EnvironmentState.READY, result.environment)
            assertEquals("", result.error)
            assertTrue(result.stage, result.stage.startsWith("Ubuntu 精简策略已启用"))
        } finally {
            if (m.hasActiveOperation) withContext(NonCancellable) {
                m.cancel()
                withTimeout(30000) { while (m.hasActiveOperation) delay(100) }
            }
        }
    }

    @Test fun minimalPolicyAndAptCacheCleanupPreserveRuntimeAndProjects(): Unit = runBlocking {
        val m = runtime()
        val before = m.paths.metadata().toString()
        val id = UUID.randomUUID().toString()
        val aptDir = File(m.paths.root, "etc/apt/apt.conf.d")
        val config = File(aptDir, "zzzz-tanuki-regression-$id")
        assertTrue("Temporary override must sort last", aptDir.list().orEmpty().all { it < config.name })
        val project = File(m.paths.workspaces, "minimal-proof-$id")
        val sentinels = listOf(File(m.paths.workspaces, "minimal-proof-$id.deb"),
            File(m.paths.workspaces, "minimal-proof-$id.txt"), File(project, "partial/keep.deb"))
        val archives = File(m.paths.root, "var/cache/apt/archives")
        assertEquals(File(m.paths.root.canonicalFile, "var/cache/apt/archives"), archives.canonicalFile)
        val cache = File(archives, "tanuki-proof-$id.deb")
        val copyright = File(m.paths.root, "usr/share/doc/apt/copyright")
        val legalBefore = copyright.readBytes()
        val engine = File(m.paths.engines, "${m.paths.metadata().getString("dsh")}/node_modules/@deepseek-ai/dsh/lib/bin.js")
        val engineBefore = engine.readBytes()
        val tools = "set -e; /opt/node/bin/node --version; /usr/bin/git --version; /usr/bin/python3 --version; /usr/bin/curl --version; /usr/bin/xz --version"
        val toolsBefore = command(m, "/bin/bash", "-c", tools).stdout
        assertTrue(config.createNewFile())
        try {
            config.writeText("Dir::Cache::archives \"/workspaces\";\n")
            sentinels.forEach { it.parentFile!!.mkdirs(); it.writeText("keep-project-$id:${it.name}") }
            archives.mkdirs(); cache.writeText("cache fixture")
            val effective = command(m, "/usr/bin/apt-config", "dump").stdout
            assertTrue(effective, effective.contains("Dir::Cache::archives \"/workspaces\";"))
            slim(m)
            assertFalse("Default archive cache must be cleaned", cache.exists())
            sentinels.forEach { assertEquals("keep-project-$id:${it.name}", it.readText()) }
            assertArrayEquals(legalBefore, copyright.readBytes())
            assertArrayEquals(engineBefore, engine.readBytes())
            assertEquals(before, m.paths.metadata().toString())
            assertEquals(toolsBefore, command(m, "/bin/bash", "-c", tools).stdout)
            val policy = command(m, "/usr/bin/apt-config", "dump").stdout
            assertTrue(policy, policy.contains("Dir::Cache::archives \"/workspaces\";"))
            assertTrue(policy.contains("APT::Install-Recommends \"false\""))
            assertTrue(policy, Regex("Acquire::Languages(?:::)?\\s+\"none\"").containsMatchIn(policy))
            // Drain a real child's 1 MiB line and independent stderr without deadlocking.
            val large = m.launcher.run(m.paths.root, listOf("/opt/node/bin/node", "-e",
                "process.stdout.write('x'.repeat(1024*1024)+'\\n');process.stderr.write('REAL_STDERR\\n')"), 30000)
            assertEquals(large.stderr, 0, large.exitCode)
            assertTrue(large.stdout.contains("单行输出已截断")); assertTrue(large.stdout.length < 13000)
            assertTrue(large.stderr.contains("REAL_STDERR"))
        } finally {
            try {
                assertTrue("Remove temporary APT override", config.delete())
            } finally {
                cache.delete()
                sentinels.take(2).forEach { it.delete() }
                project.deleteRecursively()
            }
        }
    }

    @Test fun realDpkgUnpackKeepsLegalNoticesAfterVendorExcludes(): Unit = runBlocking {
        val m = runtime()
        slim(m) // Each method establishes the managed policy independently of test order.
        val pkg = "tanuki-minimal-${UUID.randomUUID()}"
        val tmp = File(m.paths.root, "tmp")
        assertEquals(File(m.paths.root.canonicalFile, "tmp"), tmp.canonicalFile)
        val source = File(tmp, "$pkg-source")
        val archive = File(tmp, "$pkg.deb")
        assertFalse(archive.exists())
        assertTrue(source.mkdir())
        var unpackAttempted = false
        try {
            val control = File(source, "DEBIAN/control")
            control.parentFile!!.mkdirs()
            android.system.Os.chmod(control.parentFile!!.absolutePath, 493) // 0755: dpkg-deb rejects private 0700 directories.
            control.writeText("""
                Package: $pkg
                Version: 1.0
                Architecture: all
                Maintainer: Tanuki Test <test@invalid.example>
                Description: Offline minimal policy regression fixture
            """.trimIndent() + "\n")
            val payload = listOf("copyright", "LICENSE.test", "COPYING.test", "OPTIONAL.txt")
            payload.forEach { name ->
                File(source, "usr/share/doc/$pkg/$name").apply {
                    parentFile!!.mkdirs(); writeText("$pkg:$name\n")
                }
            }
            val man = "usr/share/man/man1/$pkg.1"
            File(source, man).apply { parentFile!!.mkdirs(); writeText(".TH TANUKI 1\n") }
            command(m, "/usr/bin/dpkg-deb", "-Zgzip", "--build", "/tmp/${source.name}", "/tmp/${archive.name}")
            unpackAttempted = true // Even a partial unpack must be purged, without touching other packages.
            command(m, "/usr/bin/dpkg", "--no-triggers", "--unpack", "/tmp/${archive.name}")
            payload.take(3).forEach { name ->
                assertEquals("$pkg:$name\n", File(m.paths.root, "usr/share/doc/$pkg/$name").readText())
            }
            assertFalse("Optional documentation must be excluded", File(m.paths.root, "usr/share/doc/$pkg/OPTIONAL.txt").exists())
            assertFalse("Manual page must be excluded", File(m.paths.root, man).exists())
        } finally {
            try {
                if (unpackAttempted) withContext(NonCancellable) {
                    command(m, "/usr/bin/dpkg", "--no-triggers", "--purge", pkg)
                }
            } finally {
                source.deleteRecursively()
                archive.delete()
            }
        }
    }
}
