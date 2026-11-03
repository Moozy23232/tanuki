package com.tanuki

import com.tanuki.update.AppInstallIdentity
import com.tanuki.update.AppReleaseManifest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseManifestTest {
    private val signer = "a".repeat(64)
    private val installed = AppInstallIdentity("com.tanuki.modern.workspacecheck", "0.1.0-dev", 1, 26, setOf(signer))
    private val asset = "fixture.apk"
    private val url = "https://github.com/leezzou/tanuki/releases/download/v0.1.1/$asset"
    private fun item() = JSONObject().put("packageName", installed.packageName).put("versionName", "0.1.1")
        .put("versionCode", 2).put("minSdk", 26).put("assetName", asset).put("sha256", "b".repeat(64))
        .put("bytes", 12345).put("certificateSha256", signer)
    private fun manifest(items: JSONArray = JSONArray().put(item())) = JSONObject().put("schemaVersion", 1).put("packages", items)
    private fun assets() = JSONArray().put(JSONObject().put("name", asset).put("browser_download_url", url).put("size", 12345))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid release accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun selectsOnlyAnUpdateForTheInstalledPackage() {
        assertEquals(2L, AppReleaseManifest.select(manifest(), assets(), installed, 34)?.versionCode)
        val other = item().put("packageName", "com.tanuki.modern")
        assertNull(AppReleaseManifest.select(manifest(JSONArray().put(other)), assets(), installed, 34))
        assertNull(AppReleaseManifest.select(manifest(JSONArray().put(item().put("versionCode", 1))), assets(), installed, 34))
    }
    @Test fun manifestMustMatchAnActualReleaseAttachment() {
        rejects { AppReleaseManifest.select(manifest(), JSONArray(), installed, 34) }
        rejects { AppReleaseManifest.select(manifest(), JSONArray().put(JSONObject().put("name", asset).put("browser_download_url", url).put("size", 999)), installed, 34) }
        rejects { AppReleaseManifest.select(manifest(), assets().put(assets().getJSONObject(0)), installed, 34) }
    }
    @Test fun stringNumbersWrongSchemaAndDuplicatePackagesAreRejected() {
        rejects { AppReleaseManifest.select(manifest().put("schemaVersion", 2), assets(), installed, 34) }
        rejects { AppReleaseManifest.select(manifest(JSONArray().put(item().put("versionCode", "2"))), assets(), installed, 34) }
        rejects { AppReleaseManifest.select(manifest(JSONArray().put(item()).put(item())), assets(), installed, 34) }
    }
    @Test fun invalidSignatureAndUntrustedAssetAddressFailClosed() {
        rejects { AppReleaseManifest.select(manifest(JSONArray().put(item().put("certificateSha256", "c".repeat(64)))), assets(), installed, 34) }
        rejects { AppReleaseManifest.select(manifest(), JSONArray().put(JSONObject().put("name", asset).put("browser_download_url", "https://elsewhere.example/fixture.apk").put("size", 12345)), installed, 34) }
    }
}
