package com.tanuki

import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.browser.BrowserConnection
import com.tanuki.browser.BrowserIntegration
import com.tanuki.runtime.Paths
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class BrowserIntegrationTest {
    private fun fixture(block: (Paths) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = Files.createTempDirectory(context.cacheDir.toPath(), "browser-integration-").toFile()
        val paths = Paths(object : ContextWrapper(context) { override fun getFilesDir() = temp })
        try { block(paths) } finally { temp.deleteRecursively() }
    }
    private fun connection(value: Byte) = BrowserConnection(12345, Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { value }))
    private fun publish(paths: Paths, connection: BrowserConnection) = BrowserIntegration.publish(
        InstrumentationRegistry.getInstrumentation().targetContext, paths, connection)
    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Expected redirected or user-owned destination to be rejected")
    }
    @Test fun firstPublishInstallsSkillAndPrivateTransportThenRotatesConnection() = fixture { paths ->
        val first = connection(1); val next = connection(2)
        publish(paths, first)
        val tools = BrowserIntegration.directory(paths)
        val config = File(tools, "connection.json")
        val client = File(tools, "tanuki-browser.mjs")
        val skill = File(paths.home, ".agents/skills/tanuki-browser/SKILL.md")
        assertTrue(client.readText().contains("export async function callBrowser"))
        assertTrue(skill.readText().contains("managed-by: Tanuki"))
        assertEquals(first.token, JSONObject(config.readText()).getString("token"))
        for (file in listOf(config, client, skill)) assertEquals("Private file permissions", 384, Os.stat(file.path).st_mode and 511)
        assertEquals(448, Os.stat(tools.path).st_mode and 511)
        publish(paths, next)
        assertEquals(next.token, JSONObject(config.readText()).getString("token"))
        BrowserIntegration.disconnect(paths, first)
        assertTrue("An old Activity must not remove the replacement connection", config.exists())
        BrowserIntegration.disconnect(paths, next)
        assertFalse(config.exists())
        assertTrue("Installed client and Skill survive disconnect", client.exists() && skill.exists())
        assertFalse(File(tools, "connection.json.new").exists())
    }
    @Test fun customSkillIsNeverOverwritten() = fixture { paths ->
        val skill = File(paths.home, ".agents/skills/tanuki-browser/SKILL.md")
        skill.parentFile!!.mkdirs(); skill.writeText("my own skill")
        rejected { publish(paths, connection(1)) }
        assertEquals("my own skill", skill.readText())
        assertFalse(File(BrowserIntegration.directory(paths), "connection.json").exists())
    }
    @Test fun atomicStagingAndBackupSymlinksCannotRedirectWrites() = fixture { paths ->
        val tools = BrowserIntegration.directory(paths).apply { mkdirs() }
        val target = File(paths.base, "keep").apply { writeText("unchanged") }
        for (suffix in listOf("", ".new", ".bak")) {
            val link = File(tools, "tanuki-browser.mjs$suffix")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            rejected { publish(paths, connection(1)) }
            assertEquals("unchanged", target.readText())
            Files.delete(link.toPath())
        }
    }
}
