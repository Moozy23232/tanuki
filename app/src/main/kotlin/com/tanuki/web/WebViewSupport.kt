package com.tanuki.web

import org.json.JSONObject

enum class WebViewSupportState { UNCHECKED, CHECKING, SUPPORTED, UPDATE_REQUIRED, FAILED }

data class WebViewSupport(
    val state:WebViewSupportState=WebViewSupportState.UNCHECKED,
    val provider:String="尚未检测",
    val version:String="未知",
    val subgrid:Boolean?=null,
    val lightDark:Boolean?=null,
    val error:String=""
) {
    val chromium:Int? get()=version.substringBefore('.').toIntOrNull()
    val missing:List<String> get()=listOfNotNull(if(subgrid==false)"subgrid" else null,if(lightDark==false)"light-dark()" else null)
    val description:String get()=buildString {
        append("WebView 提供方 $provider\n版本 $version（Chromium ${chromium ?: "未知"}）")
        when(state) {
            WebViewSupportState.UNCHECKED->append("\n尚未检测渲染能力")
            WebViewSupportState.CHECKING->append("\n正在检测实际 CSS 渲染能力…")
            WebViewSupportState.SUPPORTED->append("\n已通过 Chromium 123+ 与 CSS 能力检查")
            WebViewSupportState.UPDATE_REQUIRED->append("\nDSH 需要 Chromium 123 或更新版本。当前缺失：${missing.joinToString().ifEmpty { "内核版本要求" }}。请通过官方渠道更新系统 WebView 后重新打开 App。")
            WebViewSupportState.FAILED->append("\n检测失败：$error")
        }
    }
    companion object {
        const val MIN_CHROMIUM=123
        const val PROBE_SCRIPT="""(() => ({subgrid:typeof CSS!=='undefined' && CSS.supports('grid-template-columns','subgrid'),lightDark:typeof CSS!=='undefined' && CSS.supports('color','light-dark(white, black)')}))()"""
        fun measured(provider:String,version:String,json:String):WebViewSupport {
            val result=JSONObject(json)
            val subgrid=result.getBoolean("subgrid");val lightDark=result.getBoolean("lightDark")
            val major=version.substringBefore('.').toIntOrNull()
            val supported=major!=null && major>=MIN_CHROMIUM && subgrid && lightDark
            return WebViewSupport(if(supported)WebViewSupportState.SUPPORTED else WebViewSupportState.UPDATE_REQUIRED,provider,version,subgrid,lightDark)
        }
    }
}
