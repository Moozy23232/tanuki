package com.tanuki.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 将已校验的单个更新APK交给系统安装器，兼容安装器在Activity启动前的后台预读。 */
internal object ApkInstallAccess {
    private const val MIME = "application/vnd.android.package-archive"
    private const val PREFERENCES = "apk-install-read-lease"
    private const val MAX_LIFETIME = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var expiry: Job? = null

    private fun valid(context: Context, uri: Uri): Boolean = uri.scheme == "content" &&
        uri.authority == "${context.packageName}.updates" &&
        uri.pathSegments.size == 2 && uri.pathSegments.first() == "application_updates" &&
        uri.lastPathSegment?.endsWith(".apk") == true

    suspend fun prepare(context: Context, uri: Uri, lifetimeMillis: Long = MAX_LIFETIME): Intent = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        require(valid(app, uri)) { "只允许共享已校验的应用更新缓存" }
        require(lifetimeMillis in 1..MAX_LIFETIME) { "安装器读取授权必须有界" }
        mutex.withLock {
            revokeLocked(app)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME).apply {
                clipData = ClipData.newRawUri("Tanuki APK", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 不预授予普通APK关联应用；只允许实际匹配的、可导出的系统安装处理器。
            val handlers = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo }
                .filter { it.exported && it.applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .distinctBy { ComponentName(it.packageName, it.name) }
            if (handlers.isEmpty()) throw ActivityNotFoundException("没有可用的系统APK安装器")
            val preferred = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            val selected = handlers.firstOrNull { it.packageName == preferred?.packageName && it.name == preferred?.name }
                ?: handlers.first()
            intent.component = ComponentName(selected.packageName, selected.name)
            val deadline = System.currentTimeMillis() + lifetimeMillis
            // 先持久化公开URI及期限；进程被系统回收后仍能恢复计时并收回授权。
            val saved = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString("uri", uri.toString()).putLong("deadline", deadline).commit()
            check(saved) { "无法记录安装器授权期限" }
            try {
                handlers.map { it.packageName }.distinct().forEach { packageName ->
                    app.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                scheduleLocked(app, uri, deadline)
                intent
            } catch (error: Exception) {
                revokeLocked(app)
                throw error
            }
        }
    }

    /** 不依赖Activity返回时机；有些安装器会先转交后台组件再返回Activity结果。 */
    private fun scheduleLocked(app: Context, uri: Uri, deadline: Long) {
        expiry?.cancel()
        expiry = scope.launch {
            delay((deadline - System.currentTimeMillis()).coerceIn(0, MAX_LIFETIME))
            mutex.withLock {
                val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                if (prefs.getString("uri", null) == uri.toString() && prefs.getLong("deadline", 0) == deadline) revokeLocked(app)
            }
        }
    }

    fun restore(context: Context): Job = scope.launch {
        val app = context.applicationContext
        mutex.withLock {
            val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val uri = prefs.getString("uri", null)?.let(Uri::parse) ?: return@withLock
            val deadline = prefs.getLong("deadline", 0)
            if (!valid(app, uri) || deadline <= System.currentTimeMillis()) revokeLocked(app)
            else scheduleLocked(app, uri, deadline)
        }
    }

    suspend fun revoke(context: Context) = withContext(Dispatchers.IO) { mutex.withLock { revokeLocked(context.applicationContext) } }
    fun revokeAsync(context: Context): Job = scope.launch { revoke(context) }

    private fun revokeLocked(app: Context) {
        expiry?.cancel(); expiry = null
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val uri = prefs.getString("uri", null)?.let(Uri::parse)
        if (uri != null && valid(app, uri)) app.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(prefs.edit().clear().commit()) { "无法清理安装器授权记录" }
    }
}
