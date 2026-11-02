package com.tanuki

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.*
import com.tanuki.download.Downloader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real native process lifecycle; only disposable fixtures, never user data. */
class ProcessOwnershipTest {
    private class FixtureContext(base:Context,private val directory:File):ContextWrapper(base) {
        override fun getFilesDir()=directory
        override fun getCacheDir()=File(directory,"cache").apply { mkdirs() }
    }

    @Test fun nativeChildSurvivesCallingThreadExit():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val archive=File(context.filesDir,"downloads/ubuntu-${RuntimeManager.UBUNTU}-arm64.tar.gz")
        assumeTrue("requires verified public Ubuntu archive cache",archive.isFile)
        assertEquals(RuntimeManager.UBUNTU_SHA,Downloader.hash(archive))
        val foreground=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val directory=File(context.cacheDir,"process-owner-${System.nanoTime()}").apply { mkdirs() }
        val manager=RuntimeManager(FixtureContext(context,directory))
        val childRef=AtomicReference<Child?>()
        try {
            archive.copyTo(File(manager.paths.downloads,archive.name))
            manager.mutation.withLock { manager.ensureBase() }
            val ready=CompletableFuture<Child>()
            val armed=CountDownLatch(1)
            val caller=Thread({
                try {
                    val child=manager.launcher.start(manager.paths.root,listOf("/bin/sh","-c","sleep 10; printf owner-alive"))
                    childRef.set(child)
                    ready.complete(child)
                    // Return only after collect observes the supervisor's armed signal.
                    check(armed.await(10,TimeUnit.SECONDS)) { "missing native ownership handshake" }
                }catch(error:Throwable){ready.completeExceptionally(error)}
            },"short-lived-process-caller").apply { isDaemon=true;start() }
            val child=ready.get(15,TimeUnit.SECONDS)
            val collected=async(Dispatchers.IO) {
                manager.launcher.collect(child) { if(it.startsWith("TANUKI_IDENTITY "))armed.countDown() }
            }
            caller.join(15000)
            assertFalse("calling thread must have exited before child completes",caller.isAlive)
            assertEquals("native ownership handshake must have been observed",0L,armed.count)
            delay(150)
            val aliveAfterCallerExit=child.process.isAlive
            val result=withTimeout(15000) { collected.await() }
            assertEquals("child must outlive its caller; aliveAfterCallerExit=$aliveAfterCallerExit: ${result.stderr}",0,result.exitCode)
            assertTrue("direct native child must survive caller exit",aliveAfterCallerExit)
            assertEquals("owner-alive",result.stdout.trim())
        }finally {
            childRef.get()?.let { if(it.process.isAlive)it.stop() }
            manager.scope.cancel()
            RuntimeManager.deleteTree(directory)
            instrumentation.runOnMainSync { foreground.finish() }
        }
    }

    @Test fun stopReapsTermIgnoringProcessGroup()=stopGroup(true)
    @Test fun stopReapsDescendantAfterLeaderExit()=stopGroup(false)

    private fun stopGroup(leaderIgnoresTerm:Boolean):Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"process-stop-${System.nanoTime()}").apply { mkdirs() }
        val manager=RuntimeManager(FixtureContext(context,directory))
        val group=java.util.concurrent.atomic.AtomicInteger(0)
        val descendant=java.util.concurrent.atomic.AtomicInteger(0)
        val leaderTrap=if(leaderIgnoresTerm)"trap '' TERM" else "trap 'exit 0' TERM"
        val script="$leaderTrap; /system/bin/sh -c 'trap \"\" TERM; while :; do /system/bin/sleep 1; done' & printf 'TANUKI_DESCENDANT pid=%s\\n' \"\$!\"; wait"
        val builder=ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libsupervisor.so","/system/bin/sh","-c",script)
        builder.environment().apply { clear();put("PATH","/system/bin") }
        val process=builder.start()
        val child=Child(process,manager.log,"stop-group-test")
        val collected=async(Dispatchers.IO) {
            manager.launcher.collect(child) { line ->
                if(line.startsWith("TANUKI_IDENTITY "))Regex("pid=(\\d+)").find(line)?.groupValues?.get(1)?.toInt()?.let(group::set)
                if(line.startsWith("TANUKI_DESCENDANT "))Regex("pid=(\\d+)").find(line)?.groupValues?.get(1)?.toInt()?.let(descendant::set)
            }
        }
        fun running(pid:Int):Boolean {
            if(pid<=1)return false
            try { android.system.Os.kill(pid,0) }
            catch(error:android.system.ErrnoException) {
                if(error.errno==android.system.OsConstants.ESRCH)return false
                throw error
            }
            val stat=File("/proc/$pid/stat")
            return try { !stat.readText().substringAfterLast(") ").startsWith("Z ") }
            catch(error:java.io.FileNotFoundException) {
                try { android.system.Os.kill(pid,0);throw error }
                catch(exited:android.system.ErrnoException) { if(exited.errno==android.system.OsConstants.ESRCH)false else throw exited }
            }
        }
        try {
            withTimeout(5000) { while(group.get()==0 || descendant.get()==0)delay(25) }
            assertTrue("term-ignoring descendant must be running before stop",running(descendant.get()))
            val started=android.os.SystemClock.elapsedRealtime()
            val result=withTimeout(10000) { child.stop();collected.await() }
            assertFalse("stop must reap the native leader",process.isAlive)
            assertTrue("termination must be bounded",android.os.SystemClock.elapsedRealtime()-started<8000)
            if(leaderIgnoresTerm)assertTrue("ignored TERM must escalate to a terminating signal",result.exitCode!=0)
            else assertEquals("leader exits gracefully while descendant still requires escalation",0,result.exitCode)
            withTimeout(3000) { while(running(descendant.get()))delay(25) }
        }finally {
            withContext(NonCancellable) {
                if(group.get()>1 && (process.isAlive || running(descendant.get())))runCatching { android.system.Os.kill(-group.get(),android.system.OsConstants.SIGKILL) }
                withTimeoutOrNull(3000) { collected.await() }
                collected.cancelAndJoin()
                if(process.isAlive)process.destroyForcibly()
                manager.scope.cancel()
                RuntimeManager.deleteTree(directory)
            }
        }
    }
}
