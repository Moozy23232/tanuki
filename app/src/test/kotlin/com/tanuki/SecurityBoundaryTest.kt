package com.tanuki

import com.tanuki.runtime.Redact
import com.tanuki.storage.SafeTar
import com.tanuki.web.OriginPolicy
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class SecurityBoundaryTest {
    @Test fun credentialedOriginCannotBeConfusedWithAnotherLocalService() {
        val origin = "http://127.0.0.1:3080"
        assertTrue(OriginPolicy.sameOrigin("$origin/?token=private", origin))
        listOf("http://127.0.0.1:3081/", "http://127.0.0.1.evil.example:3080/", "http://evil@127.0.0.1:3080/", "https://127.0.0.1:3080/", "http://localhost:3080/", "file:///data/data/x").forEach { assertFalse(it, OriginPolicy.sameOrigin(it, origin)) }
        assertFalse(OriginPolicy.external("https://example.com/?token=private"))
        assertFalse(OriginPolicy.external("intent://example.com"))
        assertTrue(OriginPolicy.preview("http://127.0.0.1:3000/",origin))
        assertFalse(OriginPolicy.preview("http://127.0.0.1:3080/",origin))
        assertFalse(OriginPolicy.preview("http://127.0.0.1.evil.example:3000/",origin))
        assertFalse(OriginPolicy.preview("http://127.0.0.1:3000/?token=private",origin))
    }
    @Test fun dshBrowserLinksAllowOauthButNeverForwardManagementOrAppSchemes() {
        val origin = "http://127.0.0.1:3080"
        listOf("https://open.weixin.qq.com/connect?code=public-oauth&token=public-flow", "https://example.com/guide", "http://127.0.0.1:3000/", "http://example.com/").forEach {
            assertTrue(it, OriginPolicy.browserPage(it, origin))
        }
        listOf("$origin/?token=private", "http://localhost:3080/", "https://elsewhere.example:3080/", "file:///data/data/x", "intent://login", "javascript:alert(1)", "https://name:password@example.com/", "http://127.0.0.1:3000/?token=private").forEach {
            assertFalse(it, OriginPolicy.browserPage(it, origin))
        }
    }
    @Test fun diagnosticsDoNotExposeKnownCredentials() {
        val redacted = Redact.text("http://127.0.0.1:123/?token=very-secret\nAuthorization: Bearer live-secret\napi_key=secret-value\nsk-abcdefgh12345678")
        listOf("very-secret", "live-secret", "secret-value", "sk-abcdefgh12345678").forEach { assertFalse(redacted.contains(it)) }
    }
    @Test fun archiveCannotNameHostFiles() {
        val root = Paths.get("/private/stage")
        assertEquals(root.resolve("usr/bin/bash"), SafeTar.entryPath(root, "./usr/bin/bash"))
        listOf("../../host", "/etc/passwd", "a/../../../host").forEach { path ->
            try { SafeTar.entryPath(root, path); fail("accepted $path") } catch (_: IllegalArgumentException) { }
        }
    }
}
