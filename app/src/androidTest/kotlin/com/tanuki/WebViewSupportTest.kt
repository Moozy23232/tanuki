package com.tanuki

import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.web.DshWebView
import com.tanuki.web.WebViewSupportState
import com.tanuki.runtime.WebState
import org.junit.Assert.*
import org.junit.Test

class WebViewSupportTest {
    @Test fun actualRendererCapabilitiesControlNativeCompatibilityState() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var browser:DshWebView
        inst.runOnMainSync {
            browser=MainActivity::class.java.getDeclaredField("browser").apply{isAccessible=true}.get(activity) as DshWebView
        }
        val deadline=System.nanoTime()+15_000_000_000L
        while(browser.support.value.state in setOf(WebViewSupportState.CHECKING,WebViewSupportState.UNCHECKED) && System.nanoTime()<deadline)Thread.sleep(50)
        val measured=browser.support.value
        assertNotEquals(measured.description,WebViewSupportState.FAILED,measured.state)
        assertNotNull(measured.subgrid);assertNotNull(measured.lightDark)
        val expected=measured.chromium!=null && measured.chromium!!>=123 && measured.subgrid==true && measured.lightDark==true
        assertEquals(if(expected)WebViewSupportState.SUPPORTED else WebViewSupportState.UPDATE_REQUIRED,measured.state)
        if(!expected) {
            assertEquals(WebState.INCOMPATIBLE,(activity.application as TanukiApp).runtime.state.value.web)
            inst.runOnMainSync { assertFalse(browser.view.url.orEmpty().startsWith("http://127.0.0.1:")) }
        }
        Log.i("TANUKI_PROBE","WEBVIEW_CAPABILITY_RESULT ${measured.description.replace('\n',' ')} subgrid=${measured.subgrid} lightDark=${measured.lightDark}")
    }
}
