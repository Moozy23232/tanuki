package com.tanuki

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ExtensionPersistenceTest {
    @Test fun officialPluginAndSkillLoadingSurviveRestart(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val m = (instrumentation.targetContext.applicationContext as TanukiApp).runtime
        assertTrue(m.paths.metadata().optBoolean("ready"))
        m.ensurePnpm()
        val plugin = File(m.paths.workspaces, ".tanuki-smoke-plugin").apply { mkdirs() }
        listOf("package.json", "cordis.patch.yml", "index.mjs").forEach { name ->
            instrumentation.context.assets.open("smoke-plugin/$name").use { input -> File(plugin,name).outputStream().use { input.copyTo(it) } }
        }
        val skill = File(m.paths.home, ".agents/skills/tanuki-smoke").apply { mkdirs() }
        instrumentation.context.assets.open("SKILL-smoke.md").use { input -> File(skill,"SKILL.md").outputStream().use { input.copyTo(it) } }
        val cli = "/opt/engines/${m.paths.metadata().getString("dsh")}/node_modules/@deepseek-ai/dsh/lib/bin.js"
        val install = m.launcher.run(m.paths.root, listOf("/opt/node/bin/node", cli, "plugin", "--profile", "web", "add", "/workspaces/.tanuki-smoke-plugin"), 600000)
        install.stdout.chunked(3000).forEach { Log.i("TANUKI_PROBE", "plugin_install=$it") }
        assertEquals(install.stderr, 0, install.exitCode)
        val marker = File(m.paths.dshData,"tanuki-smoke-counter.json")
        val before = if (marker.exists()) marker.readText().toInt() else 0
        repeat(2) { i ->
            m.start()
            withTimeout(200000) { while(m.state.value.service !in setOf(ServiceState.RUNNING,ServiceState.FAILED))delay(200) }
            assertEquals(m.state.value.error, ServiceState.RUNNING,m.state.value.service)
            withTimeout(15000) {
                while(true) {
                    val loaded=File(m.paths.dshData,"tanuki-skill-loaded.json")
                    val startup=runCatching { org.json.JSONObject(loaded.readText()).optInt("startup") }.getOrDefault(0)
                    if(startup>=before+i+1)break
                    delay(200)
                }
            }
            assertTrue(marker.readText().toInt() >= before + i + 1)
            Log.i("TANUKI_PROBE", "PLUGIN_EXECUTED restart=$i count=${marker.readText()}; SKILL_REGISTRY_BODY=${File(m.paths.dshData,"tanuki-skill-loaded.json").readText()}")
            m.stop()
        }
        // This proves official provider loading, not a model's execution of the skill instructions.
    }
}
