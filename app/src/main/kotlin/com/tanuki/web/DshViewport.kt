package com.tanuki.web

import android.webkit.WebView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DSH-only logical-width adjustment. This is not Android's wm density and does not
 * change system fonts, browser tabs, saved credentials, or the official DSH files.
 */
internal class DshViewport(private val officialPage: (String) -> Boolean) {
    private var installed = false
    fun install(view: WebView) {
        if (installed) return
        installed = true
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = false
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) view.post { apply(view) }
        }
    }
    fun apply(view: WebView) {
        if (view.width <= 0 || !officialPage(view.url.orEmpty())) return
        val density = view.resources.displayMetrics.density
        val physicalWidthDp = view.width / density
        // Equivalent in spirit to a larger smallest-width qualifier, scoped to DSH only.
        // Preserve the user's WebView/font settings; scale the whole official page uniformly.
        val logicalWidth = max(411.0, ceil(physicalWidthDp * 1.12)).roundToInt().coerceIn(411, 1920)
        val factor = (physicalWidthDp / logicalWidth).coerceAtMost(1f)
        val script = """(function(){
            if(!document.head)return false;
            let meta=document.querySelector('meta[name="viewport"]');
            if(!meta){meta=document.createElement('meta');meta.name='viewport';document.head.appendChild(meta);}
            const content='width=$logicalWidth,initial-scale=$factor,minimum-scale=$factor,maximum-scale=5,user-scalable=yes,viewport-fit=cover';
            if(meta.content!==content)meta.content=content;
            return true;
        })()"""
        view.evaluateJavascript(script, null)
    }
}
