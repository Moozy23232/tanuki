package com.tanuki

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.update.ApkInstallAccess
import com.tanuki.update.AppUpdateFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 只使用专用测试安装与合成APK，核对系统真实的跨UID授权，不安装或读取账号数据。 */
class ApkInstallAccessTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as TanukiApp
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private fun allowed(uri: Uri, uid: Int) = app.checkUriPermission(uri, -1, uid, read) == PackageManager.PERMISSION_GRANTED
    private suspend fun fixture(block: suspend (Uri, Uri) -> Unit) {
        check(app.packageName.endsWith(".workspacecheck") && !app.runtime.hasLiveChild && !app.appUpdates.hasActiveOperation)
        val directory = AppUpdateFiles.directory(app.cacheDir).apply { check(mkdirs() || isDirectory) }
        val first = File(directory, "installer-probe-${System.nanoTime()}.apk")
        val second = File(directory, "installer-probe-${System.nanoTime()}-next.apk")
        try {
            first.writeText("synthetic-apk-first"); second.writeText("synthetic-apk-next")
            ApkInstallAccess.revoke(app)
            block(FileProvider.getUriForFile(app, "${app.packageName}.updates", first),
                FileProvider.getUriForFile(app, "${app.packageName}.updates", second))
        } finally { ApkInstallAccess.revoke(app); first.delete(); second.delete() }
    }
    private fun installerUid(intent: Intent): Int = app.packageManager.getActivityInfo(requireNotNull(intent.component), 0).applicationInfo.uid

    @Test fun installerCanReadBeforeActivityStartsButMatchingUserAppCannot() = runBlocking {
        fixture { uri, _ ->
            val legacy = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(read)
            val handlers = app.packageManager.queryIntentActivities(legacy, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("必须存在真正匹配APK的不同UID合成接收方", handlers.any { it.activityInfo.packageName == instrumentation.context.packageName })
            val intent = ApkInstallAccess.prepare(app, uri)
            val info = app.packageManager.getActivityInfo(requireNotNull(intent.component), 0)
            assertTrue(info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            assertNotEquals(Process.myUid(), info.applicationInfo.uid)
            assertEquals(uri, intent.data); assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertEquals(1, intent.clipData!!.itemCount)
            assertEquals(read, intent.flags and read)
            assertEquals(0, intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
            assertTrue("系统安装器在Activity开始前的预读已获授权", allowed(uri, info.applicationInfo.uid))
            val other = app.packageManager.getApplicationInfo(instrumentation.context.packageName, 0)
            assertNotEquals(Process.myUid(), other.uid)
            assertFalse("匹配相同MIME的普通应用不能获得显式预读权限", allowed(uri, other.uid))
            assertFalse(app.packageManager.resolveContentProvider("${app.packageName}.updates", 0)!!.exported)
            assertNotEquals("两个authority必须有不同的provider组件身份",
                app.packageManager.resolveContentProvider("${app.packageName}.updates", 0)!!.name,
                app.packageManager.resolveContentProvider("${app.packageName}.browserfiles", 0)!!.name)
            ApkInstallAccess.revoke(app)
            assertFalse("回收后安装器不能继续读取", allowed(uri, info.applicationInfo.uid))
        }
    }

    @Test fun plainViewFlagAloneDoesNotGrantInstallerPreRead() = runBlocking {
        fixture { uri, _ ->
            val prepared = ApkInstallAccess.prepare(app, uri)
            val uid = installerUid(prepared)
            ApkInstallAccess.revoke(app)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(read)
            assertFalse("只构造旧Intent并不会给安装器后台预读发放权限", allowed(uri, uid))
        }
    }

    @Test fun nextApkRevokesThePreviousFileAndExpiryRevokesTheCurrentFile() = runBlocking {
        fixture { first, second ->
            val uid = installerUid(ApkInstallAccess.prepare(app, first))
            assertTrue(allowed(first, uid))
            ApkInstallAccess.prepare(app, second, lifetimeMillis = 700)
            assertFalse("下一次授权不能保留旧APK读取权限", allowed(first, uid))
            assertTrue(allowed(second, uid))
            withTimeout(8_000) { while (allowed(second, uid)) delay(50) }
            assertFalse(allowed(second, uid))
        }
    }

    @Test fun foreignAndBrowserProviderUrisAreRejected() = runBlocking {
        fixture { _, _ ->
            for (uri in listOf(Uri.parse("file:///tmp/synthetic.apk"),
                Uri.parse("content://other.invalid/application_updates/synthetic.apk"),
                Uri.parse("content://${app.packageName}.browserfiles/browser-downloads/synthetic.apk"))) {
                try { ApkInstallAccess.prepare(app, uri); fail("必须拒绝更新缓存以外的URI") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun actualInstallerReadsApkMetadataBeforeUserConfirmation() = runBlocking {
        fixture { uri, _ ->
            assertTrue("先通过系统设置允许专用测试App安装未知应用", app.packageManager.canRequestPackageInstalls())
            val file = File(AppUpdateFiles.directory(app.cacheDir), requireNotNull(uri.lastPathSegment))
            File(app.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val intent = ApkInstallAccess.prepare(app, uri)
            val installer = requireNotNull(intent.component).packageName
            try {
                instrumentation.runOnMainSync { activity.startActivity(intent) }
                withTimeout(20_000) {
                    var found = false
                    while (!found) {
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                        fun containsLabel(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                            if (node == null) return false
                            if (node.packageName?.toString() == installer && node.text?.toString() == "Tanuki") return true
                            return (0 until node.childCount).any { containsLabel(node.getChild(it)) }
                        }
                        found = containsLabel(root)
                        if (!found) delay(100)
                    }
                }
                // 只核对安装器已成功读取APK标签；不点击最终安装，不替用户批准安装。
            } catch (error: Throwable) {
                val rows = mutableListOf("resolved=$installer")
                fun record(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    rows += "${node.packageName} | ${node.text} | ${node.contentDescription}"
                    repeat(node.childCount) { record(node.getChild(it)) }
                }
                record(instrumentation.uiAutomation.rootInActiveWindow)
                File(app.cacheDir, "installer-read-failure.txt").writeText(rows.joinToString("\n"))
                instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                    File(app.cacheDir, "installer-read-failure.png").outputStream().use {
                        shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    shot.recycle()
                }
                throw error
            } finally {
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
