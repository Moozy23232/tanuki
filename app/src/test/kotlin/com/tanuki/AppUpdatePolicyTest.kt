package com.tanuki

import com.tanuki.update.AppInstallIdentity
import com.tanuki.update.AppUpdateAsset
import com.tanuki.update.AppUpdatePolicy
import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    private val signer = "a".repeat(64)
    private val installed = AppInstallIdentity("com.tanuki.modern.workspacecheck", "0.1.0-dev", 1, 26, setOf(signer))
    private val candidate = AppUpdateAsset(installed.packageName, "0.1.1", 2, 26,
        "Tanuki-0.1.1-workspacecheck-release.apk", "b".repeat(64), 12_345, signer,
        "https://github.com/leezzou/tanuki/releases/download/v0.1.1/Tanuki-0.1.1-workspacecheck-release.apk")
    private val archive = AppInstallIdentity(installed.packageName, candidate.versionName, candidate.versionCode, 26, setOf(signer))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Unsafe update accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun onlyKnownRepositoryReleaseAssetsOverHttpsAreEligible() {
        assertTrue(AppUpdatePolicy.releaseAssetUrl(candidate.url, candidate.assetName))
        listOf(candidate.url.replace("https:", "http:"), candidate.url.replace("leezzou/tanuki", "other/tanuki"),
            candidate.url.replace("github.com/", "github.com:444/"), candidate.url.replace("https://", "https://synthetic-user" + '@'),
            candidate.url + "?redirect=elsewhere", candidate.url + "#fragment", candidate.url.replace("github.com/", "github.com.evil.example/")
        ).forEach { assertFalse(it, AppUpdatePolicy.releaseAssetUrl(it, candidate.assetName)) }
        assertFalse(AppUpdatePolicy.releaseAssetUrl(candidate.url, "../" + candidate.assetName))
    }

    @Test fun compatibleHigherVersionKeepsTheSamePackageAndCertificate() {
        AppUpdatePolicy.requireCompatible(candidate, installed, 34)
        AppUpdatePolicy.requireArchive(candidate, installed, archive, 34)
    }

    @Test fun differentPackageOrCertificateCannotBecomeAnUpdate() {
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(packageName = "com.tanuki.modern"), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(certificateSha256 = "c".repeat(64)), installed, 34) }
        rejects { AppUpdatePolicy.requireArchive(candidate, installed, archive.copy(signers = setOf("c".repeat(64))), 34) }
        rejects { AppUpdatePolicy.requireArchive(candidate, installed, archive.copy(packageName = "com.tanuki.modern"), 34) }
        rejects { AppUpdatePolicy.requireArchive(candidate, installed, archive.copy(signers = setOf(signer, "c".repeat(64))), 34) }
    }

    @Test fun sameVersionOrDowngradeIsNeverOffered() {
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(versionCode = 1), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(versionCode = 0), installed, 34) }
    }

    @Test fun missingIntegrityUnknownSizeOversizeOrIncompatibleSdkIsRejected() {
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(sha256 = ""), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(bytes = -1), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(bytes = 256L * 1024 * 1024 + 1), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(minSdk = 35), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(assetName = "../candidate.apk"), installed, 34) }
        rejects { AppUpdatePolicy.requireCompatible(candidate.copy(versionName = "../../bad"), installed, 34) }
    }

    @Test fun archiveMustMatchTheAnnouncedVersionAndMinimumSdk() {
        rejects { AppUpdatePolicy.requireArchive(candidate, installed, archive.copy(versionCode = 3), 34) }
        rejects { AppUpdatePolicy.requireArchive(candidate, installed, archive.copy(versionName = "0.9.0"), 34) }
        rejects { AppUpdatePolicy.requireArchive(candidate, installed, archive.copy(minSdk = 27), 34) }
    }
}
