package com.tanuki.update

import org.json.JSONArray
import org.json.JSONObject

/** JSON 只是发布声明，安装前仍必须独立解析 APK 并比较真实证书、包名和版本。 */
object AppReleaseManifest {
    private val tagPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,119}"

    /** 不执行 XML 实体，也不请求 GitHub API；只提取本仓库的有界发布 tag。 */
    fun releaseTags(feed: String): List<String> {
        require(feed.length <= 1_048_576 && feed.contains("<feed") && feed.contains("http://www.w3.org/2005/Atom")) { "GitHub 未返回有效的公开发布 Feed" }
        return Regex("""https://github\.com/${Regex.escape(AppUpdatePolicy.REPOSITORY)}/releases/tag/($tagPattern)(?=["'<>\s])""")
            .findAll(feed).map { it.groupValues[1] }.distinct().take(10).toList()
    }

    fun manifestTag(url: String): String? = runCatching {
        if (!AppUpdatePolicy.releaseAssetUrl(url, "app-update.json")) return null
        Regex("""/${Regex.escape(AppUpdatePolicy.REPOSITORY)}/releases/download/($tagPattern)/app-update\.json""")
            .matchEntire(java.net.URI(url).rawPath)?.groupValues?.get(1)
    }.getOrNull()

    /** 清单只提供预期值，下载后的实际字节与 APK 证书仍须独立核对。 */
    fun selectDirect(manifest: JSONObject, tag: String, installed: AppInstallIdentity, sdk: Int): AppUpdateAsset? {
        require(Regex(tagPattern).matches(tag)) { "公开发布 tag 无效" }
        val packages = manifest.getJSONArray("packages")
        require(packages.length() in 1..8) { "应用更新清单条目数量无效" }
        val assets = JSONArray()
        for (i in 0 until packages.length()) {
            val entry = packages.getJSONObject(i)
            val name = entry.getString("assetName")
            val url = "https://github.com/${AppUpdatePolicy.REPOSITORY}/releases/download/$tag/$name"
            require(AppUpdatePolicy.releaseAssetUrl(url, name)) { "发布附件地址无效" }
            assets.put(JSONObject().put("name", name).put("size", entry.get("bytes")).put("browser_download_url", url))
        }
        return select(manifest, assets, installed, sdk)
    }

    fun select(manifest: JSONObject, assets: JSONArray, installed: AppInstallIdentity, sdk: Int): AppUpdateAsset? {
        require(number(manifest, "schemaVersion") == 1L) { "应用更新清单版本不支持" }
        val packages = manifest.getJSONArray("packages")
        require(packages.length() in 1..8) { "应用更新清单条目数量无效" }
        val current = (0 until packages.length()).map { packages.getJSONObject(it) }
            .filter { it.getString("packageName") == installed.packageName }
        require(current.size <= 1) { "应用更新清单存在重复包名" }
        val entry = current.singleOrNull() ?: return null
        val code = number(entry, "versionCode")
        if (code <= installed.versionCode) return null
        val name = entry.getString("assetName")
        require(assets.length() <= 100) { "Release 附件数量超过限制" }
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name") == name }
        require(matches.size == 1) { "更新清单对应的 APK 附件不存在或重复" }
        val attachment = matches.single()
        val size = number(entry, "bytes")
        require(number(attachment, "size") == size) { "APK 附件大小与清单不符" }
        val minSdk = number(entry, "minSdk")
        require(minSdk in 26..Int.MAX_VALUE) { "更新 Android 要求无效" }
        return AppUpdateAsset(installed.packageName, entry.getString("versionName"), code, minSdk.toInt(), name,
            entry.getString("sha256"), size, entry.getString("certificateSha256"), attachment.getString("browser_download_url"))
            .also { AppUpdatePolicy.requireCompatible(it, installed, sdk) }
    }

    private fun number(json: JSONObject, key: String): Long {
        val raw = json.get(key)
        require(raw is Number && Regex("[0-9]+").matches(raw.toString())) { "更新清单的数字字段无效" }
        return raw.toString().toLongOrNull() ?: throw IllegalArgumentException("更新清单的数字超出范围")
    }
}
