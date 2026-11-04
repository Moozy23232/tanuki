package com.tanuki

import java.net.InetAddress
import java.net.ServerSocket

/** 只提供合成页面；底部控件用于核验原生浮动导航是否拦截真实触摸。 */
internal object FixedBottomControlsFixture {
    const val HTML = """<!doctype html><html><head><meta charset="utf-8"><title>Browser laboratory</title>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;font:16px sans-serif;background:#e3f2fd;color:#172b4d}
        h1{margin:16px;font-size:22px}#fixed-controls{position:fixed;left:0;right:0;bottom:0;height:96px;
        box-sizing:border-box;display:flex;align-items:center;gap:4px;padding:0 8px;background:#ffd54f}
        #fixed-controls>*{box-sizing:border-box;min-width:0;height:48px;font:16px sans-serif}
        #fixed-input{width:40%}button{flex:1}</style></head><body>
        <h1>Browser laboratory</h1><p>合成底部操作区，不读取工作区或调用模型。</p>
        <div id="fixed-controls"><input id="fixed-input" aria-label="底部合成输入框" value="合成输入" readonly>
        <button id="fixed-send">合成发送</button><button id="fixed-more">合成更多</button></div>
        <script>window.__fixedBottomEvents={send:0,more:0,focus:0};
        document.getElementById('fixed-send').onclick=()=>window.__fixedBottomEvents.send++;
        document.getElementById('fixed-more').onclick=()=>window.__fixedBottomEvents.more++;
        document.getElementById('fixed-input').onfocus=()=>window.__fixedBottomEvents.focus++;
        </script></body></html>"""

    /** 普通 HTTP 文档，不以 evaluateJavascript 触发控件事件。 */
    class Server : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/fixed-bottom"
        private val thread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                runCatching { socket.use { connection ->
                    connection.soTimeout = 2000
                    val reader = connection.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = HTML.toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body); flush()
                    }
                } }
            }
        }.apply { name = "fixed-bottom-fixture"; isDaemon = true; start() }

        override fun close() { server.close(); thread.join(3000) }
    }
}
