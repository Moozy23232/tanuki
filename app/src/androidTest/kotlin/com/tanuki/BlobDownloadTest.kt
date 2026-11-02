package com.tanuki

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.web.BlobDownloads
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONTokener
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BlobDownloadTest {
    @Test fun blobBytesRequireBoundOriginAndPendingRequest():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val server=ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))
        val other=ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))
        fun serve(socket:ServerSocket)=launch(Dispatchers.IO) {
            while(isActive && !socket.isClosed) {
                val client=try{socket.accept()}catch(_:java.net.SocketException){break}
                client.use { c ->
                    c.soTimeout=3000;val input=c.getInputStream().bufferedReader();while(input.readLine()?.isNotEmpty()==true){}
                    val body="<html><body>download fixture</body></html>".toByteArray()
                    c.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()}
                }
            }
        }
        val first=serve(server);val second=serve(other)
        lateinit var view:WebView;lateinit var bridge:BlobDownloads
        val received=CountDownLatch(1);val loaded=CountDownLatch(1)
        val calls=AtomicInteger();var file:File?=null;var error=""
        instrumentation.runOnMainSync {
            view=WebView(context).apply{settings.javaScriptEnabled=true}
            bridge=BlobDownloads(context,view,{ f,_ ->file=f;calls.incrementAndGet();received.countDown()},error={message->error=message;received.countDown()})
            bridge.bind("http://127.0.0.1:${server.localPort}")
            view.webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView,url:String){loaded.countDown()}}
            view.loadUrl("http://127.0.0.1:${server.localPort}/")
        }
        try {
            assertTrue(loaded.await(15,TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                view.evaluateJavascript("window.__tanukiFileExport.postMessage(JSON.stringify({nonce:'forged',data:'bm8='})); URL.createObjectURL(new Blob(['Android 上的文件'],{type:'application/octet-stream'}))") { value ->
                    bridge.request(JSONTokener(value).nextValue() as String,"fixture.txt")
                }
            }
            assertTrue(received.await(15,TimeUnit.SECONDS));assertEquals(error,"",error)
            assertEquals(1,calls.get());assertEquals("Android 上的文件",file!!.readText())
            val foreign=CountDownLatch(1);var exposed=""
            instrumentation.runOnMainSync {
                view.webViewClient=object:WebViewClient(){override fun onPageFinished(v:WebView,url:String){v.evaluateJavascript("typeof window.__tanukiFileExport"){exposed=it;foreign.countDown()}}}
                view.loadUrl("http://127.0.0.1:${other.localPort}/")
            }
            assertTrue(foreign.await(15,TimeUnit.SECONDS));assertEquals("\"undefined\"",exposed)
        }finally{
            file?.delete();instrumentation.runOnMainSync{bridge.dispose();view.destroy()}
            server.close();other.close();first.cancelAndJoin();second.cancelAndJoin()
        }
    }
}
