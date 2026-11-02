package com.tanuki

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.runtime.RuntimeManager
import com.tanuki.runtime.ServiceState
import com.tanuki.runtime.WebState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserStateTest {
    @Test fun lateAuthenticationCannotReplaceRendererFailureOrCompatibilityDecision():Unit=runBlocking {
        for(decision in listOf(WebState.RENDER_ERROR,WebState.INCOMPATIBLE)) {
            val target=InstrumentationRegistry.getInstrumentation().targetContext
            val directory=File(target.cacheDir,"browser-state-${System.nanoTime()}").apply{mkdirs()}
            val context=object:ContextWrapper(target){override fun getFilesDir():File=directory}
            val manager=RuntimeManager(context)
            val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
            val received=CountDownLatch(1)
            val release=CountDownLatch(1)
            val response=async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream().bufferedReader()
                    while(true){val line=input.readLine() ?: error("HTTP request ended early");if(line.isEmpty())break}
                    received.countDown()
                    check(release.await(5,TimeUnit.SECONDS)) { "Authentication fixture was not released" }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
            try {
                RuntimeManager::class.java.getDeclaredField("origin").apply{isAccessible=true}.set(manager,"http://127.0.0.1:${server.localPort}")
                manager.state.value=manager.state.value.copy(service=ServiceState.RUNNING)
                manager.web(WebState.AUTHENTICATING)
                manager.verifyBrowserCookie(null)
                assertTrue("No real authentication request reached fixture",received.await(5,TimeUnit.SECONDS))
                manager.web(decision)
                release.countDown()
                withTimeout(10000) {
                    response.await()
                    manager.scope.coroutineContext[Job]!!.children.toList().joinAll()
                }
                assertEquals("Late HTTP 200 must not replace $decision",decision,manager.state.value.web)
            } finally {
                release.countDown();server.close();response.cancel()
                manager.scope.cancel();RuntimeManager.deleteTree(directory)
            }
        }
    }
}
