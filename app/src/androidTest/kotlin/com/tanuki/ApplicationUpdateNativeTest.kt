package com.tanuki

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.tanuki.download.Downloader
import com.tanuki.update.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 仅检查当前测试 APK 的公开身份，不请求网络、不启动安装或读取用户资料。 */
class ApplicationUpdateNativeTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TanukiApp
    private fun manager() = ApplicationUpdateManager(app)
    private fun installed(manager: ApplicationUpdateManager): AppInstallIdentity = manager.javaClass.getDeclaredMethod("installedIdentity").apply { isAccessible = true }.invoke(manager) as AppInstallIdentity
    @Suppress("DEPRECATION")
    private fun archive(manager: ApplicationUpdateManager): AppInstallIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = app.packageManager.getPackageArchiveInfo(app.applicationInfo.sourceDir, flags)!!
        return manager.javaClass.getDeclaredMethod("identity", PackageInfo::class.java).apply { isAccessible = true }.invoke(manager, info) as AppInstallIdentity
    }
    @Test fun androidArchiveAndInstalledSignerMatchActualPublishedFields() {
        val manager = manager()
        val current = installed(manager)
        val parsed = archive(manager)
        assertEquals(current, parsed)
        assertEquals("com.tanuki.modern.workspacecheck", parsed.packageName)
        assertEquals(BuildConfig.VERSION_NAME, parsed.versionName)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), parsed.versionCode)
        assertEquals(1, parsed.signers.size)
        val apk = File(app.applicationInfo.sourceDir)
        val name = "Tanuki-${parsed.versionName}-workspacecheck-arm64-release.apk"
        val candidate = AppUpdateAsset(parsed.packageName, parsed.versionName, parsed.versionCode, parsed.minSdk,
            assetName = name, sha256 = Downloader.hash(apk), bytes = apk.length(), certificateSha256 = parsed.signers.single(),
            url = "https://github.com/leezzou/tanuki/releases/download/v${parsed.versionName}/$name")
        AppUpdatePolicy.requireArchive(candidate, current.copy(versionCode = current.versionCode - 1), parsed, Build.VERSION.SDK_INT)
        assertThrows(IllegalArgumentException::class.java) { AppUpdatePolicy.requireCompatible(candidate, current, Build.VERSION.SDK_INT) }
    }
    /** 默认先种合成文件；覆盖 APK 后带 verifyUpgradeMarker=true，必须读到原值而不是重建。 */
    @Test fun privateSyntheticFileSurvivesCoverInstall() {
        check(app.packageName.endsWith(".workspacecheck"))
        val marker = File(app.filesDir, "synthetic-apk-upgrade-probe.txt")
        val expected = "synthetic-tanuki-cover-install-only"
        val verify = InstrumentationRegistry.getArguments().getString("verifyUpgradeMarker") == "true"
        if (!verify) marker.writeText(expected)
        assertTrue("覆盖安装不能丢失原有的合成私有文件", marker.isFile)
        assertEquals(expected, marker.readText())
        if (verify) assertTrue(marker.delete())
    }
    @Test fun androidCacheRootIsAcceptedButOwnUpdateLinkIsNotNeeded() {
        val directory = AppUpdateFiles.directory(app.cacheDir)
        assertEquals(File(app.cacheDir.canonicalFile, "application-updates"), directory)
        assertEquals(app.cacheDir.canonicalFile, directory.canonicalFile.parentFile)
    }
}
