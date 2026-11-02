package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.Paths
import com.tanuki.runtime.RuntimeManager
import com.tanuki.storage.DataSnapshots
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RestoreJournalTest {
    @Test fun preparedRestoreSurvivesDirectorySwapInterruption():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"restore-fixture-${System.nanoTime()}").apply{mkdirs()}
        val wrapper=object:ContextWrapper(context){override fun getFilesDir():File=root}
        val p=Paths(wrapper)
        val version="0.2.1-rc.5"
        File(p.engines,"$version/.installed").apply{parentFile!!.mkdirs();writeText("file-transaction fixture only")}
        p.atomic("installed.json",JSONObject().put("ready",true).put("dsh",version).put("node",RuntimeManager.NODE).put("ubuntu",RuntimeManager.UBUNTU).toString())
        val source=File(p.home,"session-fixture.txt").apply{writeText("snapshot-data")}
        val project=File(p.workspaces,"project.txt").apply{writeText("newer-project")}
        try {
            val backup=DataSnapshots.create(p)
            source.writeText("newer-session-data")
            val stage=File(root,"userdata.restore")
            DataSnapshots.copy(File(backup,"userdata"),stage,root)
            File(stage,".tanuki-restore-token").writeText("transaction-fixture-token")
            p.atomic("restore-transaction",JSONObject().put("phase","prepared").put("snapshot",backup.name).put("token","transaction-fixture-token").put("previous",p.metadata()).toString())
            assertTrue(p.data.renameTo(File(root,"userdata.before-restore")))
            // A new Paths reproduces the empty scaffold created during App startup.
            val reopened=Paths(wrapper)
            assertNotNull(DataSnapshots.recoverPreparedRestore(reopened))
            assertEquals("snapshot-data",File(reopened.home,"session-fixture.txt").readText())
            assertEquals("newer-session-data",File(root,"userdata.before-restore/home/session-fixture.txt").readText())
            assertEquals("newer-project",project.readText())
            assertEquals("switched",JSONObject(reopened.read("restore-transaction")!!).getString("phase"))
        }finally{RuntimeManager.deleteTree(root)}
    }
}
