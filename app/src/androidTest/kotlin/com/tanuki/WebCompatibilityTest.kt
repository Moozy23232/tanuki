package com.tanuki

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.web.LegacyWebCompatibility
import com.tanuki.runtime.RuntimeManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebCompatibilityTest {
    @Test fun standardPromiseShimRunsBeforePageScriptOnlyAtAllowedOrigin(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val major = WebView.getCurrentWebViewPackage()!!.versionName.substringBefore('.').toInt()
        assumeTrue(major < 119)
        context.getSharedPreferences("web",0).edit().putBoolean("legacyPromise",true).commit()
        val server = ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))
        val other = ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))
        fun serve(socket: ServerSocket) = launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                client.use { c ->
                    c.soTimeout=3000
                    val input=c.getInputStream().bufferedReader();while(input.readLine()?.isNotEmpty()==true) { }
                    val body="<html><head><script>window.firstScriptHasPromise=typeof Promise.withResolvers==='function'; if(window.firstScriptHasPromise){const x=Promise.withResolvers();x.resolve(9);x.promise.then(v=>window.promiseValue=v);const a=new AbortController();const b=new AbortController();const combined=AbortSignal.any([a.signal,b.signal]);b.abort('bounded-test');window.abortCombined=combined.aborted&&combined.reason==='bounded-test'}</script></head><body>test</body></html>".toByteArray()
                    c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
                }
            }
        }
        val firstServer=serve(server);val secondServer=serve(other)
        lateinit var view: WebView
        lateinit var adapter: LegacyWebCompatibility
        val first=CountDownLatch(1);var firstResult=""
        instrumentation.runOnMainSync {
            view=WebView(context).apply { settings.javaScriptEnabled=true }
            adapter=LegacyWebCompatibility(context)
            adapter.install(view,"http://127.0.0.1:${server.localPort}",RuntimeManager.DSH)
            view.webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView,url:String){v.evaluateJavascript("JSON.stringify([window.firstScriptHasPromise,window.promiseValue,window.abortCombined])"){firstResult=it;first.countDown()}}}
            view.loadUrl("http://127.0.0.1:${server.localPort}/")
        }
        try {
            assertTrue(first.await(15,TimeUnit.SECONDS));assertTrue(firstResult.contains("true,9,true"))
            val second=CountDownLatch(1);var secondResult=""
            instrumentation.runOnMainSync {
                view.webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView,url:String){v.evaluateJavascript("window.firstScriptHasPromise"){secondResult=it;second.countDown()}}}
                view.loadUrl("http://127.0.0.1:${other.localPort}/")
            }
            assertTrue(second.await(15,TimeUnit.SECONDS));assertEquals("false",secondResult)
        } finally {
            instrumentation.runOnMainSync { adapter.dispose();view.destroy() }
            server.close();other.close();firstServer.cancelAndJoin();secondServer.cancelAndJoin()
        }
    }
}
