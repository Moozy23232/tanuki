package com.tanuki

import com.tanuki.web.DshOAuth
import com.tanuki.web.DshOAuthAttempt
import com.tanuki.web.DshOAuthCallback
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class DshOAuthPolicyTest {
    private val origin = "http://127.0.0.1:45678"
    private val authorization = "https://platform.deepseek.com/dsh/authorize?authorize_id=synthetic&theme=dark"
    private val callback = "$origin/oauth/callback?code=synthetic-code&state=synthetic-state"
    private val completion = "https://platform.deepseek.com/dsh/authorized?result=fixture&login_source=web"

    @Test fun officialAuthorizationQueryIsOpaqueAndAsyncPopupOutlivesHostInitialization() {
        assertTrue(DshOAuth.isAuthorization(authorization))
        assertTrue(DshOAuth.isAuthorization("https://platform.deepseek.com/dsh/authorize?opaque=fixture"))
        assertTrue(DshOAuth.POPUP_WAIT_MILLIS > 30_000L)
        listOf("http://platform.deepseek.com/dsh/authorize", "https://elsewhere.example/dsh/authorize",
            "https://platform.deepseek.com/dsh/authorize/", authorization.replace("https://", "https://synthetic-user" + '@'),
            "$authorization#fragment", "https://platform.deepseek.com:444/dsh/authorize").forEach {
            assertFalse(it, DshOAuth.isAuthorization(it))
        }
    }

    @Test fun onlyExactTopLevelGetCallbackWithoutManagementFieldsMayCrossBoundary() {
        assertTrue(DshOAuth.isCallback(callback, origin, "GET", true))
        listOf("$origin/", "$origin/?token=synthetic", "$origin/api/session?code=c&state=s",
            "$origin/oauth/callback/?code=c&state=s", "$origin/oauth/%63allback?code=c&state=s",
            "$origin/oauth/callback?code=c&code=d&state=s", "$origin/oauth/callback?code=c&state=s&state=t",
            "$origin/oauth/callback?code=&state=s", "$origin/oauth/callback?code=c&state=",
            "$callback&token=synthetic", "$callback&error=access_denied", "$callback#fragment",
            "http://localhost:45678/oauth/callback?code=c&state=s",
            "http://127.0.0.1:45679/oauth/callback?code=c&state=s").forEach {
            assertFalse(it, DshOAuth.isCallback(it, origin, "GET", true))
        }
        assertFalse(DshOAuth.isCallback(callback, origin, "POST", true))
        assertFalse(DshOAuth.isCallback(callback, origin, "GET", false))
    }

    @Test fun attemptIsTabOwnedOneShotAndBoundToRuntimeGenerationAndDeadline() {
        val attempt = DshOAuthAttempt(authorization, origin, "synthetic-launch-1", 1_000L)
        assertFalse(attempt.claim(callback, "GET", true, origin, "synthetic-launch-2", 1_001L))
        assertFalse(attempt.claim(callback, "GET", false, origin, "synthetic-launch-1", 1_001L))
        assertTrue(attempt.claim(callback, "GET", true, origin, "synthetic-launch-1", 1_001L))
        assertFalse(attempt.claim(callback, "GET", true, origin, "synthetic-launch-1", 1_002L))
        val expired = DshOAuthAttempt(authorization, origin, "synthetic-launch-1", 1_000L)
        assertFalse(expired.claim(callback, "GET", true, origin, "synthetic-launch-1", 601_000L))
        assertFalse(expired.isCurrent(origin, "synthetic-launch-1", 999L))
    }

    @Test fun completionMustStayOnOfficialOriginWithExactlyOneWebLoginSource() {
        assertTrue(DshOAuth.isCompletion(completion))
        listOf("https://elsewhere.example/dsh/authorized?login_source=web",
            "https://platform.deepseek.com/dsh/authorized?login_source=desktop",
            "https://platform.deepseek.com/dsh/authorized?login_source=web&login_source=web",
            "https://platform.deepseek.com/dsh/authorized", "$completion#fragment",
            "$origin/oauth/callback?login_source=web", "dsh://open").forEach {
            assertFalse(it, DshOAuth.isCompletion(it))
        }
    }

    private class Response(url: URL, private val status: Int, private val location: String?) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
    }

    @Test fun callbackTransportNeverFollowsRedirectOrAddsManagementCredentials() {
        lateinit var response: Response
        val client = DshOAuthCallback { Response(it, 302, completion).also { value -> response = value } }
        assertEquals(completion, client.exchange(callback, origin))
        assertFalse(response.instanceFollowRedirects)
        assertFalse(response.useCaches)
        assertNull(response.getRequestProperty("Cookie"))
        assertNull(response.getRequestProperty("Authorization"))
        assertNull(response.getRequestProperty("Referer"))
        assertEquals("GET", response.requestMethod)
        assertTrue(response.closed)
    }

    @Test fun rejectedStateOrUntrustedCompletionCannotBecomeARegularBrowserNavigation() {
        assertNull(DshOAuthCallback { Response(it, 400, null) }.exchange(callback, origin))
        assertNull(DshOAuthCallback { Response(it, 200, null) }.exchange(callback, origin))
        assertNull(DshOAuthCallback { Response(it, 302, "$origin/?token=synthetic") }.exchange(callback, origin))
        assertNull(DshOAuthCallback { Response(it, 302, "https://elsewhere.example/") }.exchange(callback, origin))
        var requested = false
        assertNull(DshOAuthCallback { requested = true; Response(it, 302, completion) }
            .exchange("$callback&token=synthetic", origin))
        assertFalse(requested)
    }
}
