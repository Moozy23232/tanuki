package com.tanuki.browser

/** 只为已授权连接的临时接管计时；普通触碰和显式撤销不能创建新的授权。 */
class BrowserIdleReturnPolicy(private val timeoutMillis: Long = 30_000L) {
    private var lastInteraction: Long? = null
    fun pause(now: Long) { lastInteraction = now }
    fun interact(now: Long) { if (lastInteraction != null) lastInteraction = now }
    fun cancel() { lastInteraction = null }
    fun remaining(now: Long): Long? = lastInteraction?.let { (timeoutMillis - (now - it).coerceAtLeast(0)).coerceAtLeast(0) }
    fun claim(now: Long, defaultEnabled: Boolean): Boolean {
        if (!defaultEnabled || remaining(now) != 0L) return false
        cancel()
        return true
    }
}
