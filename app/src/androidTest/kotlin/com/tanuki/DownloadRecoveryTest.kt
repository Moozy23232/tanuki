package com.tanuki

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.download.Downloader
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DownloadRecoveryTest {
    @Test fun interruptedOfficialDownloadCanBeVerifiedAfterRetry(): Unit = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"download-${System.nanoTime()}").apply { mkdirs() }
        val target = File(directory,"ubuntu.tar.gz")
        try {
            Downloader { done, _ -> if(done >= 512*1024) throw CancellationException("deliberate download interruption") }.fetch(RuntimeManager.UBUNTU_URL,target,RuntimeManager.UBUNTU_SHA)
            fail("download did not stop")
        } catch(_:CancellationException) { }
        assertFalse(target.exists())
        val retained = File(target.path+".part").length()
        assertTrue(retained >= 512*1024)
        var first:Long? = null
        Downloader { done, _ -> if(first==null)first=done }.fetch(RuntimeManager.UBUNTU_URL,target,RuntimeManager.UBUNTU_SHA)
        assertEquals(RuntimeManager.UBUNTU_SHA,Downloader.hash(target))
        Log.i("TANUKI_PROBE","DOWNLOAD_RETRY_OK retained=$retained firstRetryProgress=$first verifiedBytes=${target.length()}")
        RuntimeManager.deleteTree(directory)
    }
}
