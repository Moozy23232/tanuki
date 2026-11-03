package com.tanuki.update

import java.net.URI

/** 发布清单与 APK 解析结果分开建模，不能仅凭清单中的证书声明相信下载文件。 */
data class AppInstallIdentity(val packageName: String, val versionName: String, val versionCode: Long,
                              val minSdk: Int, val signers: Set<String>)
data class AppUpdateAsset(val packageName: String, val versionName: String, val versionCode: Long, val minSdk: Int,
                          val assetName: String, val sha256: String, val bytes: Long,
                          val certificateSha256: String, val url: String)

object AppUpdatePolicy {
    const val REPOSITORY = "leezzou/tanuki"
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    private val hex = Regex("[0-9a-f]{64}")
    private val version = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
    private val name = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")

    fun releaseAssetUrl(url: String, assetName: String): Boolean = runCatching {
        if (url.length > 2048 || !name.matches(assetName)) return false
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("/$REPOSITORY/releases/download/[A-Za-z0-9._-]+/${Regex.escape(assetName)}").matches(uri.rawPath.orEmpty())
    }.getOrDefault(false)

    fun requireCompatible(asset: AppUpdateAsset, installed: AppInstallIdentity, sdk: Int) {
        require(asset.packageName == installed.packageName) { "更新包名不同，不能覆盖当前应用" }
        require(asset.versionCode > installed.versionCode && asset.versionCode <= 2_100_000_000L) { "更新版本必须高于当前安装" }
        require(version.matches(asset.versionName)) { "更新版本号格式无效" }
        require(asset.minSdk in 26..sdk) { "当前 Android 版本不支持此更新" }
        require(hex.matches(asset.sha256) && hex.matches(asset.certificateSha256)) { "更新校验信息不完整" }
        require(installed.signers == setOf(asset.certificateSha256)) { "更新签名与当前应用不同，已保留现有环境" }
        require(asset.bytes in 1..MAX_APK_BYTES) { "更新大小未知或超过 256 MiB 限制" }
        require(asset.assetName.endsWith(".apk") && releaseAssetUrl(asset.url, asset.assetName)) { "更新来源不是本项目的 HTTPS Release 附件" }
    }

    fun requireArchive(asset: AppUpdateAsset, installed: AppInstallIdentity, archive: AppInstallIdentity, sdk: Int) {
        requireCompatible(asset, installed, sdk)
        require(archive.packageName == asset.packageName) { "APK 实际包名与发布清单不符" }
        require(archive.versionCode == asset.versionCode && archive.versionName == asset.versionName) { "APK 实际版本与发布清单不符" }
        require(archive.minSdk == asset.minSdk && archive.minSdk <= sdk) { "APK 实际 Android 要求与清单不符" }
        require(archive.signers == installed.signers && archive.signers == setOf(asset.certificateSha256)) { "APK 实际签名与当前应用不符" }
    }
}
