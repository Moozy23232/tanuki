package com.tanuki.web

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/** 官方账号使用独立浏览器；只转交固定登录入口和一次性本机回调，不共享管理资料。 */
object DshOAuth {
    const val POPUP_WAIT_MILLIS = 90_000L
    private const val PLATFORM = "platform.deepseek.com"

    private fun platform(raw: String, path: String): URI? = runCatching {
        if (raw.length > 8192) return null
        URI(raw).takeIf { it.scheme == "https" && it.host == PLATFORM && it.port in setOf(-1, 443) &&
            it.rawUserInfo == null && it.rawFragment == null && it.rawPath == path }
    }.getOrNull()

    // 授权 query 是平台的不透明数据；官方 state 经 Host POST 发送，不能要求它出现在此 URL。
    fun isAuthorization(url: String): Boolean = platform(url, "/dsh/authorize") != null

    fun isCallback(url: String, origin: String, method: String, mainFrame: Boolean): Boolean = runCatching {
        if (!mainFrame || method != "GET" || url.length > 8192 || !OriginPolicy.sameOrigin(url, origin)) return false
        val uri = URI(url)
        if (uri.rawPath != "/oauth/callback" || uri.rawFragment != null) return false
        val params = parameters(uri)
        params.keys == setOf("code", "state") && params.getValue("code").singleOrNull()?.length in 1..4096 &&
            params.getValue("state").singleOrNull()?.length in 1..1024
    }.getOrDefault(false)

    fun isCompletion(url: String): Boolean = runCatching {
        val uri = platform(url, "/dsh/authorized") ?: return false
        parameters(uri)["login_source"] == listOf("web")
    }.getOrDefault(false)

    private fun parameters(uri: URI): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        for (part in uri.rawQuery.orEmpty().split('&')) {
            require('=' in part)
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('='), "UTF-8")
            require(key.none { it.isISOControl() } && value.none { it.isISOControl() })
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }
}

/** 只存内存，不使用 data class，避免把启动身份或回调参数意外写入日志。 */
class DshOAuthAttempt(authorization: String, val origin: String, private val launchIdentity: String,
                      private val started: Long) {
    private val claimed = AtomicBoolean(false)
    init {
        require(DshOAuth.isAuthorization(authorization) && OriginPolicy.sameOrigin(origin, origin))
        require(launchIdentity.isNotEmpty())
    }
    fun isCurrent(currentOrigin: String?, currentIdentity: String?, now: Long): Boolean =
        currentOrigin == origin && currentIdentity == launchIdentity && now >= started && now - started < 600_000L

    fun claim(url: String, method: String, mainFrame: Boolean, currentOrigin: String?,
              currentIdentity: String?, now: Long): Boolean =
        isCurrent(currentOrigin, currentIdentity, now) && DshOAuth.isCallback(url, origin, method, mainFrame) &&
            claimed.compareAndSet(false, true)
}

/** 此公开回调由官方 Host 继续验证 state/PKCE；原生不附加 Cookie、token 或 Authorization。 */
class DshOAuthCallback(private val connection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    fun exchange(url: String, origin: String): String? {
        if (!DshOAuth.isCallback(url, origin, "GET", true)) return null
        val request = connection(URL(url))
        return try {
            request.instanceFollowRedirects = false
            request.useCaches = false
            request.requestMethod = "GET"
            request.connectTimeout = 10_000
            request.readTimeout = 60_000
            request.setRequestProperty("Cache-Control", "no-store")
            if (request.responseCode != HttpURLConnection.HTTP_MOVED_TEMP) null
            else request.getHeaderField("Location")?.takeIf(DshOAuth::isCompletion)
        } finally { request.disconnect() }
    }
}
