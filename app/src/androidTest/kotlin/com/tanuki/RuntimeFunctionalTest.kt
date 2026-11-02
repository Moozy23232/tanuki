package com.tanuki

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** No model calls. Run only against the application's dedicated test installation. */
class RuntimeFunctionalTest {
    @Test fun nodeNetworkingPtyAndDshRestart(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val m = (context.applicationContext as TanukiApp).runtime
        assertTrue("Initialize from the real App UI first", m.paths.metadata().optBoolean("ready"))
        m.ensurePnpm()
        val diagnostics = m.diagnostics()
        assertEquals(0, diagnostics.exitCode)
        diagnostics.stdout.chunked(3000).forEach { Log.i("TANUKI_PROBE", "functional=$it") }
        val commands = """
            set -e
            node -e 'const http=require("http");const s=http.createServer((q,r)=>r.end("ANDROID_LOCAL_HTTP"));s.listen(0,"127.0.0.1",async()=>{try{const r=await fetch("http://127.0.0.1:"+s.address().port);if(await r.text()!=="ANDROID_LOCAL_HTTP")process.exitCode=2;console.log("HTTP_LOCAL_OK")}finally{s.close()}})'
            python3 -c 'import pty,os; a,b=pty.openpty();os.write(b,b"PTY_OK");assert b"PTY_OK" in os.read(a,128);os.close(a);os.close(b);print("PTY_OK")'
            node /opt/engines/${m.paths.metadata().getString("dsh")}/node_modules/@deepseek-ai/dsh/lib/bin.js --help
            node --input-type=module -e 'import { createRequire } from "node:module"; const req=createRequire("/opt/engines/${m.paths.metadata().getString("dsh")}/node_modules/@deepseek-ai/dsh/lib/bin.js"); const m=await import(req.resolve("@deepseek-ai/node-addon-system/landlock-run")); console.log("LANDLOCK_ENFORCEMENT="+m.probe(m.launcherPath(),{timeoutMs:5000}))'
        """.trimIndent()
        val result = m.launcher.run(m.paths.root, listOf("/bin/bash", "-c", commands))
        Log.i("TANUKI_PROBE", "functional_stdout=${result.stdout}; stderr=${result.stderr}; exit=${result.exitCode}")
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("HTTP_LOCAL_OK")); assertTrue(result.stdout.contains("PTY_OK"))
        repeat(2) {
            m.start()
            withTimeout(200000) { while (m.state.value.service !in setOf(ServiceState.RUNNING, ServiceState.FAILED)) delay(200) }
            assertEquals(m.state.value.error, ServiceState.RUNNING, m.state.value.service)
            val url = m.authenticatedUrl ?: error("missing official token URL")
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            try {
                assertEquals(303, connection.responseCode)
                assertNotNull(connection.getHeaderField("Set-Cookie"))
            } finally { connection.disconnect() }
            Log.i("TANUKI_PROBE", "DSH_AUTH_EXCHANGE_OK restart=$it uid=${android.os.Process.myUid()}")
            m.stop()
            assertEquals("probe-data", java.io.File(m.paths.workspaces,"probe.txt").readText())
        }
    }
}
