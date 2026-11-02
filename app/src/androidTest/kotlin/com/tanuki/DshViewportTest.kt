package com.tanuki

import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.web.DshViewport
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/** DSH-only density: actual renderer at narrow/wide phone widths, never wm density. */
class DshViewportTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun metrics(view: WebView): JSONObject {
        val latch = CountDownLatch(1); var result = "null"
        ui { view.evaluateJavascript("JSON.stringify({width:document.documentElement.clientWidth,visualWidth:visualViewport.width,scale:visualViewport.scale,scrollWidth:document.documentElement.scrollWidth,url:location.href,meta:document.querySelector('meta[name=viewport]')?.content,marker:window.marker,origin:performance.timeOrigin})") { result=it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return JSONObject(JSONTokener(result).nextValue() as String)
    }
    private fun await(condition: () -> Boolean) {
        repeat(80) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue("DSH logical viewport did not settle", condition())
    }
    @Test fun logicalWidthMakesTheWholeDshPageCompactWithoutReloadOrSystemChanges() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".workspacecheck"))
        val original = android.content.res.Configuration(context.resources.configuration)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val width = mutableStateOf(320)
        val server = BrowserFixtureServer()
        val policy = DshViewport { it.startsWith(server.base + "/") }
        lateinit var web: WebView
        ui {
            web = WebView(activity).apply {
                settings.javaScriptEnabled = true
                policy.install(this)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { policy.apply(view) }
                }
            }
            activity.setContent { AndroidView(factory = { web }, modifier = Modifier.width(width.value.dp).fillMaxHeight()) }
            web.loadUrl(server.base + "/dsh")
        }
        try {
            await { runCatching { metrics(web).optString("marker") == "kept" }.getOrDefault(false) }
            val origin = metrics(web).getDouble("origin")
            for (dp in listOf(320, 390)) {
                ui { width.value = dp }
                await {
                    val physical = web.width / web.resources.displayMetrics.density
                    val expected = max(411.0, ceil(physical * 1.12)).toInt()
                    val measured = metrics(web)
                    measured.getInt("width") >= expected - 2 && kotlin.math.abs(measured.getDouble("visualWidth") - expected) < 3
                }
                val measured = metrics(web)
                assertEquals("kept", measured.getString("marker"))
                assertEquals("resizing must not reload DSH", origin, measured.getDouble("origin"), 0.0)
                assertTrue("whole page should fit horizontally", measured.getInt("scrollWidth") <= measured.getInt("width") + 1)
                assertTrue("compact default must zoom out", measured.getDouble("scale") < 1.0)
                instrumentation.waitForIdleSync(); SystemClock.sleep(200)
                val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(context.cacheDir, "dsh-compact-$dp.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { image.recycle() }
            }
            assertEquals(original.densityDpi, context.resources.configuration.densityDpi)
            assertEquals(original.fontScale, context.resources.configuration.fontScale, 0f)
        } finally {
            ui { (web.parent as? ViewGroup)?.removeView(web); web.destroy(); activity.finish() }
            server.close()
        }
    }
}
