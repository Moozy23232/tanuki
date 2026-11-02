package com.tanuki.browser

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import com.tanuki.runtime.Paths
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** App-owned transport files; no changes to models, official profiles or approval policy. */
object BrowserIntegration {
    fun directory(paths: Paths) = File(paths.base, "browser-tools")

    @Synchronized fun publish(context: Context, paths: Paths, connection: BrowserConnection) {
        val tools = directory(paths)
        safeDirectory(tools)
        copyAsset(context, "browser/tanuki-browser.mjs", File(tools, "tanuki-browser.mjs"))
        val agents = File(paths.home, ".agents")
        safeDirectory(agents)
        val skills = File(agents, "skills")
        safeDirectory(skills)
        val skill = File(skills, "tanuki-browser")
        safeDirectory(skill)
        val instructions = File(skill, "SKILL.md")
        check(!Files.isSymbolicLink(instructions.toPath())) { "浏览器 Skill 路径被重定向" }
        if (instructions.exists()) check(instructions.readText().contains("managed-by: Tanuki")) {
            "已有同名自定义 Skill，未覆盖"
        }
        copyAsset(context, "browser/SKILL.md", instructions)
        atomic(File(tools, "connection.json"), JSONObject().put("port", connection.port).put("token", connection.token).put("clientId", connection.clientId).toString())
    }

    @Synchronized fun disconnect(paths: Paths, connection: BrowserConnection) {
        val tools = directory(paths)
        if (Files.isSymbolicLink(tools.toPath())) return
        val file = File(tools, "connection.json")
        if (Files.isSymbolicLink(file.toPath())) return
        val current = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        if (current.optString("token") == connection.token) AtomicFile(file).delete()
    }

    private fun safeDirectory(file: File) {
        check(!Files.isSymbolicLink(file.toPath())) { "浏览器目录被重定向" }
        Files.createDirectories(file.toPath())
        check(Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        Os.chmod(file.absolutePath, 448)
    }
    private fun copyAsset(context: Context, asset: String, file: File) {
        check(!Files.isSymbolicLink(file.toPath())) { "浏览器工具被重定向" }
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        if (!file.exists() || file.readText() != text) atomic(file, text)
    }
    private fun atomic(file: File, text: String) {
        check(listOf(file, File(file.path + ".bak"), File(file.path + ".new")).none { Files.isSymbolicLink(it.toPath()) }) {
            "浏览器工具暂存路径被重定向"
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            // AtomicFile writes <base>.new on newer Android versions; the base may not exist yet.
            Os.fchmod(output.fd, 384)
            output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}
