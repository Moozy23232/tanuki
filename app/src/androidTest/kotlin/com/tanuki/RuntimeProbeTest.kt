package com.tanuki

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeProbeTest {
    @Test fun ubuntuRunsWithApplicationIdentity(): Unit = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val m = (c.applicationContext as TanukiApp).runtime
        Log.i("TANUKI_PROBE", m.deviceInfo())
        m.ensureBase()
        val result = m.launcher.run(m.paths.root, listOf("/bin/bash", "-c", "cat /etc/os-release; uname -m; printf 'probe-data' > /workspaces/probe.txt; cat /workspaces/probe.txt; exit 17"), 30000)
        Log.i("TANUKI_PROBE", "stdout=${result.stdout}; stderr=${result.stderr}; exit=${result.exitCode}")
        assertTrue(result.stdout.contains("Ubuntu"))
        assertTrue(result.stdout.contains("probe-data"))
        assertEquals(17, result.exitCode)
        val before = System.currentTimeMillis()
        try { m.launcher.run(m.paths.root, listOf("/bin/sleep", "120"), 1500); fail("Expected timeout") } catch (_: kotlinx.coroutines.TimeoutCancellationException) { }
        assertTrue(System.currentTimeMillis() - before < 15000)
        Log.i("TANUKI_PROBE", "PASS hostUid=${android.os.Process.myUid()} debuggable=${c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0}")
    }
}
