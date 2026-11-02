package com.tanuki

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.download.Downloader
import com.tanuki.runtime.*
import com.tanuki.update.Release
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Dedicated no-key data only; exercises real npm, isolated startup, snapshot and rollback. */
class UpdateRecoveryTest {
    @Test fun failedCandidateAndOfficialUpgradePreserveProjectsAndRestoreMatchingData(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as TanukiApp
        instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val m=app.runtime;val update=app.updates
        assertTrue(m.paths.metadata().optBoolean("ready"))
        m.ensurePnpm();m.stop()
        val original=m.paths.metadata().getString("dsh")
        val lock=File(m.paths.engines,"$original/package-lock.json")
        val originalHash=Downloader.hash(lock)
        val id=System.nanoTime().toString()
        val project=File(m.paths.workspaces,"update-preserve-$id.txt").apply { writeText("project-$id") }
        val data=File(m.paths.home,"update-preserve-$id.txt").apply { writeText("snapshot-$id") }
        update.install(Release("0.0.0-tanuki-missing","sha512-deliberately-missing","https://registry.npmjs.org/", "unknown", "controlled missing candidate",false))
        withTimeout(240000){update.awaitIdle()}
        assertEquals(UpdateState.FAILED,m.state.value.update)
        assertEquals(original,m.paths.metadata().getString("dsh"));assertEquals(originalHash,Downloader.hash(lock))
        assertEquals("project-$id",project.readText())
        Log.i("TANUKI_PROBE","MISSING_CANDIDATE_PRESERVED_OLD_ENGINE error=${m.state.value.error}")
        update.check();withTimeout(40000){update.awaitIdle()}
        assertTrue(m.state.value.error,m.state.value.update in setOf(UpdateState.CURRENT,UpdateState.AVAILABLE))
        val release=update.releases.value.firstOrNull { it.version=="0.2.2-alpha.2" }
            ?: error("The pinned official QA candidate is not in current registry metadata")
        update.install(release)
        withTimeout(1500000){update.awaitIdle()}
        assertTrue(m.state.value.error,m.state.value.update in setOf(UpdateState.SUCCESS,UpdateState.RECOVERY_REQUIRED))
        assertEquals("project-$id",project.readText())
        assertEquals(originalHash,Downloader.hash(lock))
        Log.i("TANUKI_PROBE","OFFICIAL_CANDIDATE_RESULT version=${release.version} state=${m.state.value.update} error=${m.state.value.error}")
        if(m.state.value.update==UpdateState.SUCCESS) {
            m.paths.atomic("update-transaction",org.json.JSONObject().put("candidate",release.version).put("snapshot",m.paths.read("last-snapshot")).put("phase","switching").toString())
            m.state.value=m.state.value.copy(update=UpdateState.RECOVERY_REQUIRED)
            update.resumeInterrupted();withTimeout(240000){update.awaitIdle()}
            assertEquals(m.state.value.error,UpdateState.SUCCESS,m.state.value.update)
            assertEquals("project-$id",project.readText())
            Log.i("TANUKI_PROBE","INTERRUPTED_SWITCH_REVALIDATED_WITHOUT_DATA_ROLLBACK")
        }
        m.stop()
        assertNotNull(m.paths.read("last-snapshot"))
        data.writeText("after-upgrade-$id")
        update.restore();withTimeout(120000){update.awaitIdle()}
        assertEquals(m.state.value.error,UpdateState.RESTORED_PENDING_CHECK,m.state.value.update)
        assertEquals(original,m.paths.metadata().getString("dsh"))
        assertEquals("snapshot-$id",data.readText())
        assertEquals("project-$id",project.readText())
        m.start();withTimeout(200000){while(m.state.value.service !in setOf(ServiceState.RUNNING,ServiceState.FAILED))delay(200)}
        assertEquals(m.state.value.error,ServiceState.RUNNING,m.state.value.service)
        m.stop()
        Log.i("TANUKI_PROBE","MATCHING_SNAPSHOT_RESTORE_OK dsh=$original projectPreserved=true oldEnginePreserved=true")
    }
}
