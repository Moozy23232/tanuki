package com.tanuki

import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.RuntimeManager
import com.tanuki.runtime.RuntimeRequirements
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** User-requested tool expansion in the dedicated emulator. No model, SSH connection or real key. */
class RuntimeFoundationToolsTest {
    @Test fun thirdStepInstallsTheVersionedLightweightToolProfileAndPreservesProjects(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val runtime=(context.applicationContext as TanukiApp).runtime
        runtime.stop()
        withTimeout(30_000) { while(runtime.hasActiveOperation)delay(50) }
        val installed=runtime.paths.metadata().toString()
        val directory=File(runtime.paths.workspaces,"foundation-tools-${System.nanoTime()}").apply { check(mkdirs()) }
        val marker=File(directory,"preserve.txt").apply { writeText("KEEP_PROJECT") }
        val observed=AtomicBoolean(false)
        val watcher=launch { runtime.state.collect { if(it.operation=="component-tools")observed.set(true) } }
        try {
            runtime.installRequiredTools()
            withTimeout(480_000) { while(runtime.hasActiveOperation)delay(100) }
            assertEquals(runtime.state.value.error,"",runtime.state.value.error)
            assertTrue("Tool-check stage must be visible",observed.get())
            assertTrue(runtime.requiredTools.value.toString(),runtime.requiredTools.value.passed)
            assertEquals(RuntimeRequirements.PROFILE,JSONObject(runtime.paths.read("required-tools.json")!!).getInt("schema"))
            val command="""
                set -e
                /usr/bin/ssh -V
                /usr/bin/openssl version
                /usr/bin/rg --version
                /usr/bin/jq --version
                /usr/bin/zip -v
                /usr/bin/unzip -v
                /usr/bin/rsync --version
                /usr/bin/file --version
                /usr/bin/less --version
                /usr/bin/patch --version
                /usr/bin/make --version
                /usr/bin/pkg-config --version
                /usr/bin/python3 -I -m venv '/workspaces/${directory.name}/venv'
                '/workspaces/${directory.name}/venv/bin/python' -I -m pip --version
                printf '\nFOUNDATION_PROFILE_OK\n'
            """.trimIndent()
            val result=runtime.launcher.run(runtime.paths.root,listOf("/bin/bash","-c",command),120_000)
            assertEquals(result.stderr.takeLast(1500),0,result.exitCode)
            assertTrue(result.stdout.contains("FOUNDATION_PROFILE_OK"))
            assertEquals("KEEP_PROJECT",marker.readText())
            assertEquals("Existing Ubuntu/Node/DSH identity must not be replaced",installed,runtime.paths.metadata().toString())
            android.util.Log.i("TANUKI_PROBE","FOUNDATION_TOOLS profile=2 packages=${RuntimeRequirements.packages.size} venv=true ssh_client_only=true model=false real_key=false")
        } finally {
            watcher.cancelAndJoin()
            if(runtime.hasActiveOperation)withContext(NonCancellable) {
                runtime.cancel(); withTimeout(30_000) { while(runtime.hasActiveOperation)delay(50) }
            }
            RuntimeManager.deleteTree(directory)
        }
    }
}
