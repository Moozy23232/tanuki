package com.tanuki

import com.tanuki.update.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 公开 Feed/清单的合成样本；整个检查流程不需要 GitHub API 或账号 token。 */
class AppReleaseFeedTest {
    private val installed = AppInstallIdentity("com.tanuki.modern.workspacecheck", "0.1.2", 3, 26, setOf("b".repeat(64)))
    private fun document() = JSONObject("""{"schemaVersion":1,"packages":[{"packageName":"com.tanuki.modern.workspacecheck","versionName":"0.1.3","versionCode":4,"minSdk":26,"assetName":"Tanuki-0.1.3-workspacecheck-arm64-release.apk","sha256":"${"a".repeat(64)}","bytes":32,"certificateSha256":"${"b".repeat(64)}"}]}""")
    @Test fun releaseFeedSelectsOnlyThisRepositoryTagsWithoutApi() {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><link href="https://github.com/leezzou/tanuki/releases/tag/v0.1.3"/></entry><link href="https://github.com/attacker/tanuki/releases/tag/v9.9.9"/><link href="https://github.com/leezzou/tanuki/releases/tag/../escape"/><link href="https://github.com/leezzou/tanuki/releases/tag/v0.1.3"/><link href="https://github.com/leezzou/tanuki/releases/tag/v0.1.2"/></feed>"""
        assertEquals(listOf("v0.1.3", "v0.1.2"), AppReleaseManifest.releaseTags(feed))
        assertThrows(IllegalArgumentException::class.java) { AppReleaseManifest.releaseTags("<html>Access denied</html>") }
    }
    @Test fun stableRedirectTagMustComeFromThisRepositoryManifest() {
        assertEquals("v0.1.3", AppReleaseManifest.manifestTag("https://github.com/leezzou/tanuki/releases/download/v0.1.3/app-update.json"))
        assertNull(AppReleaseManifest.manifestTag("https://attacker.test/leezzou/tanuki/releases/download/v0.1.3/app-update.json"))
        assertNull(AppReleaseManifest.manifestTag("https://github.com/leezzou/other/releases/download/v0.1.3/app-update.json"))
    }
    @Test fun directManifestConstructsTrustedAttachmentWithoutGithubApi() {
        val selected = AppReleaseManifest.selectDirect(document(), "v0.1.3", installed, 34)!!
        assertEquals("https://github.com/leezzou/tanuki/releases/download/v0.1.3/${selected.assetName}", selected.url)
        assertEquals(4L, selected.versionCode)
    }
    @Test fun directManifestCannotChangeRepositoryOrEscapeTag() {
        for (tag in listOf("../escape", "v1?bad", "https://attacker.test")) {
            assertThrows(IllegalArgumentException::class.java) { AppReleaseManifest.selectDirect(document(), tag, installed, 34) }
        }
        val document = document()
        document.getJSONArray("packages").getJSONObject(0).put("assetName", "../bad.apk")
        assertThrows(IllegalArgumentException::class.java) { AppReleaseManifest.selectDirect(document, "v0.1.3", installed, 34) }
    }
    @Test fun emptyFeedOrLowerVersionCannotBeReportedAsAnUpgrade() {
        assertTrue(AppReleaseManifest.releaseTags("""<feed xmlns="http://www.w3.org/2005/Atom"/>""").isEmpty())
        val document = document()
        document.getJSONArray("packages").getJSONObject(0).put("versionCode", 3)
        assertNull(AppReleaseManifest.selectDirect(document, "v0.1.3", installed, 34))
    }
}
