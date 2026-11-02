package com.tanuki

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.browser.AgentBrowserController
import com.tanuki.browser.BrowserIntegration
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Public Ubuntu/Node archives + the actual guest mount and shipped client. No model call. */
class BrowserRuntimeIntegrationTest {
    @Test fun realGuestClientRequestsNativePermissionWithoutReadingPageBeforeApproval(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val foreground = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val controller = MainActivity::class.java.getDeclaredField("agentBrowser").apply { isAccessible = true }
            .get(foreground) as AgentBrowserController
        val directory = File(context.cacheDir, "browser-runtime-${System.nanoTime()}").apply { mkdirs() }
        val fixtureContext = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val manager = RuntimeManager(fixtureContext)
        try {
            for (archive in File(context.filesDir, "downloads").listFiles().orEmpty()) {
                if (archive.isFile && (archive.name.endsWith(".gz") || archive.name.endsWith(".tgz")))
                    archive.copyTo(File(manager.paths.downloads, archive.name))
            }
            manager.mutation.withLock { manager.ensureBase(); manager.components.ensureNode() }
            BrowserIntegration.publish(context, manager.paths, controller.connection)
            val script = """
                import assert from 'node:assert/strict';
                import {readFile} from 'node:fs/promises';
                import {callBrowser} from '/opt/tanuki/tanuki-browser.mjs';
                const skill=await readFile(process.env.HOME+'/.agents/skills/tanuki-browser/SKILL.md','utf8');
                assert.match(skill,/managed-by: Tanuki/);
                const initial=await callBrowser('status');
                assert.equal(initial.ok,true); assert.equal(initial.result.authorized,false);
                assert.equal(initial.result.visible,false);
                assert.equal('url' in initial.result,false); assert.equal('title' in initial.result,false);
                const request=await callBrowser('request_control'); assert.equal(request.ok,true);
                const client=request.clientId;
                const status=await callBrowser('status',{},client);
                assert.equal(status.result.pending,true); assert.equal(status.result.authorized,false);
                for(const method of ['snapshot','screenshot','navigate']) {
                    const response=await callBrowser(method,method==='navigate'?{url:'https://example.com'}:{},client);
                    assert.equal(response.ok,false); assert.equal(response.error.code,'permission_required');
                }
                console.log('GUEST_BROWSER_BRIDGE_OK');
            """.trimIndent()
            val result = manager.launcher.run(manager.paths.root, listOf("/opt/node/bin/node", "--input-type=module", "-e", script))
            assertEquals(result.stderr, 0, result.exitCode)
            assertTrue(result.stdout.contains("GUEST_BROWSER_BRIDGE_OK"))
            assertNotNull(controller.state.value.pendingClient)
            assertNull(controller.state.value.authorizedClient)
            assertEquals("", controller.state.value.url)
            android.util.Log.i("TANUKI_PROBE", "GUEST_BROWSER_BRIDGE node=true bind=true skill=true request=true denied_before_grant=true model=false")
        } finally {
            BrowserIntegration.disconnect(manager.paths, controller.connection)
            manager.cancel(); manager.scope.cancel(); RuntimeManager.deleteTree(directory)
            instrumentation.runOnMainSync { foreground.finish() }
        }
    }
}
