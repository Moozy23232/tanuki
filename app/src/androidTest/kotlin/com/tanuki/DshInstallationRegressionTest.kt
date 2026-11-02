package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.download.Downloader
import com.tanuki.runtime.*
import com.tanuki.storage.L2sStore
import com.tanuki.storage.ProjectArchive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

/** Real official DSH install plus the installed LocalFileSystem module; no model or user data. */
class DshInstallationRegressionTest {
    private class FixtureContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir() = directory
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName.endsWith(".workspacecheck")) { "This regression must run in the isolated verification app" }
    }

    private fun copyPublicArchives(from: File, to: File) {
        val known = mapOf(
            "ubuntu-${RuntimeManager.UBUNTU}-arm64.tar.gz" to RuntimeManager.UBUNTU_SHA,
            "node-${RuntimeManager.NODE}-arm64.tar.gz" to RuntimeManager.NODE_SHA,
            "pnpm-${RuntimeManager.PNPM}.tgz" to RuntimeManager.PNPM_SHA)
        for ((name, sha) in known) {
            runCatching {
                val source = File(from, name)
                if (source.isFile && Downloader.hash(source) == sha) {
                    to.mkdirs()
                    source.copyTo(File(to, name), overwrite = true)
                }
            }
        }
    }

    private fun evidence(context: Context, manager: RuntimeManager, failure: Throwable): AssertionError {
        val tail = (manager.log.recent.value + manager.log.tail().lines().takeLast(80))
            .joinToString("\n").let(Redact::text)
            .replace(Regex("""http://127\.0\.0\.1:\d+/\S*"""), "[DSH_URL]")
            .takeLast(12000)
        val file = File(context.cacheDir, "dsh-install-evidence-${System.nanoTime()}.log")
        runCatching { file.writeText(tail) }
        return AssertionError("DSH installation regression failed; evidence=${file.absolutePath}\n${tail.takeLast(4000)}", failure)
    }

    @Test(timeout = 1_200_000L)
    fun coldOfficialDshInstallAndInstalledFilesystemRegression(): Unit = runBlocking {
        val context = context()
        val foreground = InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory = File(context.cacheDir, "dsh-regression-${System.nanoTime()}").apply { mkdirs() }
        val manager = RuntimeManager(FixtureContext(context, directory))
        var child: Child? = null
        var collector: Deferred<CommandResult>? = null
        try {
            copyPublicArchives(File(context.filesDir, "downloads"), manager.paths.downloads)
            assertEquals("0.2.1-rc.5", RuntimeManager.DSH)
            assertFalse(File(manager.paths.engines, RuntimeManager.DSH).exists())

            val samples = CopyOnWriteArrayList<RuntimeDownloadProgress>()
            val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.state.collect { snapshot ->
                    snapshot.download?.takeIf { it.component == "dsh" }?.let { samples.add(it) }
                }
            }
            try {
                manager.mutation.withLock {
                    manager.ensureBase()
                    manager.components.ensureNode()
                    manager.ensureUbuntuTools()
                    manager.ensurePnpm()
                    manager.installEngine(RuntimeManager.DSH)
                }
            } finally {
                watcher.cancelAndJoin()
            }

            val engine = File(manager.paths.engines, RuntimeManager.DSH)
            assertTrue(File(engine, ".installed").isFile)
            assertTrue(File(engine, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile)
            val archivePlan=JSONObject(File(engine,".dsh-downloaded.json").readText()).getJSONArray("archives")
            assertTrue("successful installs retain verified public archives",archivePlan.length()>0)
            for(index in 0 until archivePlan.length()) {
                val item=archivePlan.getJSONObject(index)
                val retained=File(manager.paths.npmArchives,item.getString("archive").substringAfterLast('/'))
                assertEquals(item.getLong("bytes"),retained.length())
            }
            val sample=archivePlan.getJSONObject(0)
            val retained=File(manager.paths.npmArchives,sample.getString("archive").substringAfterLast('/'))
            Downloader { _,_ -> error("verified retained archive unexpectedly used the network") }
                .fetchIntegrity(sample.getString("url"),retained,sample.getString("integrity"),sample.getLong("bytes"))
            Log.i("TANUKI_PROBE","DSH_CACHE retained=true verified_reuse=true archives=${archivePlan.length()}")
            assertEquals(RuntimeManager.DSH,
                JSONObject(File(engine, "node_modules/@deepseek-ai/dsh/package.json").readText()).getString("version"))
            val dshSamples = samples.toList()
            val progressSamples = dshSamples.map { "${it.received}/${it.total}" }.distinct().take(20).joinToString(",")
            assertTrue("no dsh download progress observed", dshSamples.isNotEmpty())
            assertTrue("dsh download total unknown: $progressSamples", dshSamples.any { it.total > 0 })
            assertTrue("dsh download never advanced: $progressSamples", dshSamples.any { it.received > 0 })
            assertTrue("dsh download never reached 100%: $progressSamples", dshSamples.any { it.total > 0 && it.received == it.total })
            assertTrue("no intermediate dsh progress: $progressSamples",
                dshSamples.any { it.total > 0 && it.received in 1 until it.total })
            assertTrue("dsh progress invented bytes: $progressSamples",
                dshSamples.all { it.total <= 0 || it.received in 0..it.total })

            val workspace = File(manager.paths.workspaces, "official-fs-regression").apply { mkdirs() }
            suspend fun guest(label: String, script: String): CommandResult {
                val result = manager.launcher.run(
                    manager.paths.root,
                    listOf("/opt/node/bin/node", "--input-type=module", "-e", script),
                    timeoutMs = 120_000,
                    home = manager.paths.home,
                    dshHome = manager.paths.dshData,
                    workspace = manager.paths.workspaces,
                    engineHome = manager.paths.engines)
                assertEquals("$label: ${result.stderr}", 0, result.exitCode)
                return result
            }

            val guarded = """
                import {createRequire} from 'node:module';
                const req=createRequire('/opt/engines/${RuntimeManager.DSH}/node_modules/@deepseek-ai/dsh/lib/bin.js');
                const fsMod=await import(req.resolve('@deepseek-ai/dsh-fs-local'));
                const cordis=await import(req.resolve('@deepseek-ai/cordis'));
                const LocalFileSystem=fsMod.LocalFileSystem??fsMod.default?.LocalFileSystem??fsMod.default?.default??fsMod.default;
                const Context=cordis.Context??cordis.default?.Context??cordis.default?.default??cordis.default;
                const fs=new LocalFileSystem(new Context(),{cwd:'/workspaces/official-fs-regression',diffBasisMaxBytes:10485760});
                const target=await fs.resolve('created.txt');
                await fs.writeText(target,'created-payload',{kind:'createIfAbsent'});
                if(String(await fs.readText(target))!=='created-payload')throw new Error('guarded content');
                let rejected=false;
                try{await fs.writeText(target,'second-payload',{kind:'createIfAbsent'});}catch(e){rejected=true;}
                if(!rejected)throw new Error('guarded create replaced existing');
                if(String(await fs.readText(target))!=='created-payload')throw new Error('guarded create changed old content');
                console.log('FS_GUARDED_CREATE_OK');
            """.trimIndent()
            assertTrue(guest("guarded create", guarded).stdout.contains("FS_GUARDED_CREATE_OK"))

            val target = File(workspace, "created.txt")
            val payload = L2sStore.payload(target.toPath(), manager.paths.base.toPath())
            assertNotNull("guarded create must publish through the L2s store", payload)
            assertEquals("created-payload", payload!!.toFile().readText())
            val exported = ByteArrayOutputStream()
            ProjectArchive.write(workspace, manager.paths.base, exported)
            val entries = mutableMapOf<String, String>()
            ZipInputStream(exported.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals("created-payload", entries.entries.firstOrNull { it.key.endsWith("created.txt") }?.value)

            val overwrite = """
                import {createRequire} from 'node:module';
                const req=createRequire('/opt/engines/${RuntimeManager.DSH}/node_modules/@deepseek-ai/dsh/lib/bin.js');
                const fsMod=await import(req.resolve('@deepseek-ai/dsh-fs-local'));
                const cordis=await import(req.resolve('@deepseek-ai/cordis'));
                const LocalFileSystem=fsMod.LocalFileSystem??fsMod.default?.LocalFileSystem??fsMod.default?.default??fsMod.default;
                const Context=cordis.Context??cordis.default?.Context??cordis.default?.default??cordis.default;
                const fs=new LocalFileSystem(new Context(),{cwd:'/workspaces/official-fs-regression',diffBasisMaxBytes:10485760});
                const target=await fs.resolve('created.txt');
                await fs.writeText(target,'overwrite-payload');
                if(String(await fs.readText(target))!=='overwrite-payload')throw new Error('overwrite content');
                console.log('FS_OVERWRITE_OK');
            """.trimIndent()
            assertTrue(guest("overwrite", overwrite).stdout.contains("FS_OVERWRITE_OK"))

            val durable = """
                import fs from 'node:fs';
                const value=fs.readFileSync('/workspaces/official-fs-regression/created.txt','utf8');
                if(value!=='overwrite-payload')throw new Error('durable content');
                console.log('FS_DURABLE_OK');
            """.trimIndent()
            assertTrue(guest("fresh process durability", durable).stdout.contains("FS_DURABLE_OK"))

            val web = manager.launcher.start(
                root = manager.paths.root,
                args = listOf("/opt/node/bin/node",
                    "/opt/engines/${RuntimeManager.DSH}/node_modules/@deepseek-ai/dsh/lib/bin.js",
                    "web", "--no-open", "--host", "127.0.0.1", "--port", "0"),
                home = manager.paths.home,
                dshHome = manager.paths.dshData,
                workspace = manager.paths.workspaces,
                engineHome = manager.paths.engines)
            child = web
            val webUrl = CompletableDeferred<String>()
            collector = async(Dispatchers.IO) {
                manager.launcher.collect(web) { line ->
                    if (!webUrl.isCompleted && line.contains("dsh web:")) {
                        Regex("""http://127\.0\.0\.1:\d+/[^\s\x1B]*""").find(line)?.value?.let(webUrl::complete)
                    }
                }
            }
            var lastWebStatus = "尚未响应"
            try {
                withTimeout(180_000) {
                    while (!webUrl.isCompleted) {
                        check(web.process.isAlive) { "DSH web exited before URL" }
                        delay(200)
                    }
                    val url = webUrl.await()
                    val uri = java.net.URI(url)
                    check(uri.host == "127.0.0.1" && uri.port in 1..65535)
                    val origin = "http://127.0.0.1:${uri.port}/"
                    var phase = 0
                    var cookie: String? = null
                    while (true) {
                        check(web.process.isAlive) { "DSH web exited before authentication" }
                        val response = try {
                            withContext(Dispatchers.IO) {
                                val connection = URL(if (phase == 1) url else origin).openConnection() as HttpURLConnection
                                connection.instanceFollowRedirects = false
                                connection.connectTimeout = 5000
                                connection.readTimeout = 10000
                                if (phase == 2) connection.setRequestProperty("Cookie", requireNotNull(cookie))
                                try {
                                    connection.responseCode to connection.headerFields.entries
                                        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                                        .flatMap { it.value }.joinToString("; ") { it.substringBefore(';').trim() }
                                } finally { connection.disconnect() }
                            }
                        } catch (failure: java.io.IOException) {
                            // Cold PRoot startup may exceed one socket timeout. Never log the bootstrap URL or cookies.
                            lastWebStatus = "阶段 $phase：${failure.javaClass.simpleName}"
                            null
                        }
                        if (response != null) {
                            val (status, issuedCookie) = response
                            lastWebStatus = "阶段 $phase：HTTP $status"
                            when (phase) {
                                0 -> if (status == 401) phase = 1
                                1 -> if (status in 200..399) {
                                    check(issuedCookie.isNotBlank()) { "DSH bootstrap did not issue an authentication cookie" }
                                    cookie = issuedCookie; phase = 2
                                }
                                2 -> if (status == 200) break
                            }
                        }
                        delay(350)
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("DSH web 认证未就绪：$lastWebStatus", timeout)
            } finally {
                runCatching { if (web.process.isAlive) web.stop() }
                runCatching { withTimeoutOrNull(15_000) { collector?.await() } }
                runCatching { collector?.cancel() }
            }

            Log.i("TANUKI_PROBE", "DSH_INSTALL_OK version=${RuntimeManager.DSH} guarded_create=true guarded_reject=true " +
                "overwrite=true durable=true l2s=true archive=true web_auth=true progress_samples=$progressSamples")
        } catch (failure: Throwable) {
            throw evidence(context, manager, failure)
        } finally {
            runCatching { child?.let { if (it.process.isAlive) it.stop() } }
            runCatching { child?.process?.takeIf { it.isAlive }?.destroyForcibly() }
            runCatching { withTimeoutOrNull(15_000) { collector?.join() } }
            runCatching { collector?.cancel() }
            runCatching { manager.cancel(); manager.scope.cancel() }
            runCatching { copyPublicArchives(manager.paths.downloads, File(context.filesDir, "downloads")) }
            runCatching { RuntimeManager.deleteTree(directory) }
            runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { foreground.finish() } }
        }
    }
}
