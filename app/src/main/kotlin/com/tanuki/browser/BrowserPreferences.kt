package com.tanuki.browser

import java.net.URLEncoder

enum class BrowserPageMode(val title: String, val platform: String?) {
    MOBILE("移动端", null), WINDOWS("Windows PC", "Windows NT 10.0; Win64; x64"),
    MAC("Mac", "Macintosh; Intel Mac OS X 10_15_7"), LINUX("Linux PC", "X11; Linux x86_64");
    fun userAgent(mobile: String): String {
        if (platform == null) return mobile
        val chrome = Regex("Chrome/([0-9.]+)").find(mobile)?.groupValues?.get(1) ?: "120.0.0.0"
        return "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }
    companion object { fun from(value: String) = entries.firstOrNull { it.name == value } ?: WINDOWS }
}
enum class BrowserSearchEngine(val title: String, val prefix: String) {
    BING("必应", "https://www.bing.com/search?q="), BAIDU("百度", "https://www.baidu.com/s?wd="),
    GOOGLE("Google", "https://www.google.com/search?q="), DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=");
    fun search(query: String): String = prefix + URLEncoder.encode(query, "UTF-8")
    companion object { fun from(value: String) = entries.firstOrNull { it.name == value } ?: BING }
}
/** 保留服务器提供的附件名，避免 URLUtil 将文本附件的扩展名改为 .bin。 */
internal fun attachmentName(header: String?): String? {
    val value = header.orEmpty()
    val encoded = Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8''([^;]+)").find(value)?.groupValues?.get(1)
    val utf8 = encoded?.let { runCatching { java.net.URLDecoder.decode(it.trim().replace("+", "%2B"), "UTF-8") }.getOrNull() }
    val plain = Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))").find(value)
        ?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
    return (utf8 ?: plain)?.trim()?.takeIf { it.isNotBlank() }?.take(240)
}
/** 显式协议交给 URL 策略判断，不把它转成搜索词。 */
internal fun browserAddress(input: String, engine: BrowserSearchEngine): String {
    val value = input.trim()
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value) && !Regex("^[^/\\s:]+:[0-9]+(?:/.*)?$").matches(value)) return value
    val host = value.substringBefore('/').substringBefore('?')
    val address = !value.any { it.isWhitespace() } && (host.contains('.') || host.startsWith("localhost", true) || host.startsWith('[') || Regex("^[^:]+:[0-9]+$").matches(host))
    return if (address) "https://$value" else engine.search(value)
}
