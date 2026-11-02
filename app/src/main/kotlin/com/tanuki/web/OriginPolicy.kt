package com.tanuki.web

import java.net.URI

/** Origin is set only from the authenticated server's locally captured startup URL. */
object OriginPolicy {
    fun sameOrigin(url: String, origin: String?): Boolean = runCatching {
        if (origin == null) return false
        val u = URI(url); val o = URI(origin)
        u.scheme == "http" && u.host == "127.0.0.1" && u.port == o.port && u.port in 1..65535 && u.userInfo == null && u.rawAuthority == o.rawAuthority
    }.getOrDefault(false)
    fun preview(url:String,origin:String?):Boolean=runCatching {
        val u=URI(url);val management=origin?.let(::URI)
        u.scheme=="http" && u.host in setOf("127.0.0.1","localhost","[::1]","::1") && u.port in 1..65535 && u.userInfo==null &&
            !sameOrigin(url,origin) && !(u.host==management?.host && u.port==management?.port) && u.rawQuery?.contains(Regex("(?i)(token|auth|key)="))!=true
    }.getOrDefault(false)
    /** User-opened browser links may carry an OAuth code, never the local DSH origin. */
    fun browserPage(url: String, origin: String?): Boolean = runCatching {
        if (url.length > 8192 || sameOrigin(url, origin)) return false
        val u = URI(url)
        val host = u.host?.lowercase()?.removeSuffix(".") ?: return false
        val managementPort = origin?.let { URI(it).port }
        val loopback = host in setOf("127.0.0.1", "localhost", "[::1]", "::1")
        u.scheme?.lowercase() in setOf("http", "https") && u.rawUserInfo == null && u.port in -1..65535 && u.port != 0 &&
            !(managementPort != null && u.port == managementPort) && (!loopback || preview(url, origin))
    }.getOrDefault(false)
    fun external(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host != null && u.userInfo == null && u.host != "127.0.0.1" && u.host != "localhost" && u.rawQuery?.contains(Regex("(?i)(token|auth|key)=")) != true
    }.getOrDefault(false)
}
